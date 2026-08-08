"""고정 슬롯 없는 음성 목표 대화와 Gemini 구조화 출력 경계."""

import json
import logging
import uuid

import httpx
import pytest
from app.errors import LlmError
from app.intent_prompt import (
    _FALLBACK_REPAIR_GUIDANCE,
    _REPAIR_GUIDANCE,
    build_intent_prompt,
    build_intent_repair_prompt,
)
from app.intent_resolution import (
    INTENT_DIALOGUE_SCHEMA,
    GeminiIntentResolver,
    StubIntentResolver,
    recoverable_reason_codes,
    resolve_intent,
)
from app.schemas import ErrorKind, SlotFillRequest, SlotFillState
from pydantic import ValidationError


def _request(
    utterance: str = "카메라 앱 열어줘",
    history: list[dict[str, str]] | None = None,
) -> SlotFillRequest:
    return SlotFillRequest(
        utterance=utterance,
        session_id=uuid.uuid4(),
        conversation_history=history or [],
    )


def _gemini_response(payload: dict, *, add_basis: bool = True) -> dict:
    if add_basis and "status" in payload:
        payload = {"decision_basis": "현재 대화로 판단한 근거", **payload}
    return {
        "candidates": [
            {
                "finishReason": "STOP",
                "content": {"parts": [{"text": json.dumps(payload, ensure_ascii=False)}]},
            }
        ]
    }


def _resolver(handler) -> GeminiIntentResolver:
    return GeminiIntentResolver(
        "test-key",
        "gemini-test",
        httpx.Client(transport=httpx.MockTransport(handler)),
    )


def _events(caplog) -> list[dict]:
    return [json.loads(record.getMessage()) for record in caplog.records]


def test_request_strips_text_and_keeps_current_utterance_outside_completed_pairs():
    request = _request(
        "  보호자  ",
        [
            {"role": "user", "content": "  사진을 보내고 싶어  "},
            {"role": "assistant", "content": "  누구에게 보낼까요?  "},
        ],
    )

    assert request.utterance == "보호자"
    assert [turn.content for turn in request.conversation_history] == [
        "사진을 보내고 싶어",
        "누구에게 보낼까요?",
    ]


@pytest.mark.parametrize(
    "payload",
    [
        {"utterance": "   ", "session_id": str(uuid.uuid4())},
        {
            "utterance": "답변",
            "session_id": str(uuid.uuid4()),
            "conversation_history": [{"role": "user", "content": "질문"}],
        },
        {
            "utterance": "답변",
            "session_id": str(uuid.uuid4()),
            "conversation_history": [
                {"role": "assistant", "content": "질문"},
                {"role": "user", "content": "답변"},
            ],
        },
        {
            "utterance": "답변",
            "session_id": str(uuid.uuid4()),
            "conversation_history": [
                {"role": "user", "content": "   "},
                {"role": "assistant", "content": "질문"},
            ],
        },
    ],
)
def test_request_rejects_blank_or_incomplete_history(payload):
    with pytest.raises(ValidationError):
        SlotFillRequest.model_validate(payload)


def test_response_contract_has_no_fixed_slot_or_action_taxonomy():
    assert set(SlotFillState.model_fields) == {"status", "message", "goal"}
    assert set(INTENT_DIALOGUE_SCHEMA["properties"]) == {
        "decision_basis",
        "status",
        "message",
        "goal",
    }
    assert INTENT_DIALOGUE_SCHEMA["propertyOrdering"] == [
        "decision_basis",
        "status",
        "message",
        "goal",
    ]
    assert INTENT_DIALOGUE_SCHEMA["required"][0] == "decision_basis"


@pytest.mark.parametrize(
    "payload",
    [
        {"status": "needs_clarification", "message": "어느 앱인가요?", "goal": "앱 열기"},
        {"status": "ready", "message": "목표를 정리했어요.", "goal": None},
        {"status": "ready", "message": "목표를 정리했어요.", "goal": "   "},
    ],
)
def test_response_rejects_status_and_goal_mismatch(payload):
    with pytest.raises(ValidationError):
        SlotFillState.model_validate(payload)


def test_stub_is_only_a_keyless_contract_smoke():
    state = resolve_intent(_request("카메라 앱 열어줘"), StubIntentResolver())

    assert state.status == "ready"
    assert state.goal == "카메라 앱 열어줘"


def test_prompt_excludes_session_id_and_does_not_define_fixed_slots():
    request = _request(
        "보호자",
        [
            {"role": "user", "content": "사진을 보내고 싶어"},
            {"role": "assistant", "content": "누구에게 보낼까요?"},
        ],
    )

    prompt = build_intent_prompt(request)

    assert request.utterance in prompt
    assert request.conversation_history[0].content in prompt
    assert str(request.session_id) not in prompt
    assert "고정 슬롯 목록을 가정하지 마세요" in prompt
    assert "먼저 decision_basis" in prompt
    assert "target_person" not in prompt
    assert "답변 위치만 보고 의미 없는 현재 답변" in prompt


def test_every_recoverable_reason_has_dedicated_repair_guidance():
    """교정 문구 사전이 낡으면 여기서 먼저 실패한다.

    reason code가 늘어나는 것은 `_recoverable_contract_reason`이 보는 필드가 늘 때이고,
    그때 사전을 같이 갱신하지 않으면 모델은 무엇이 틀렸는지 모르는 일반 문구만 받는다.
    서버는 죽지 않아 알림도 없고 정상 경로에서는 재현되지도 않으므로, 이 대조가 유일한 신호다.
    """

    missing = recoverable_reason_codes() - set(_REPAIR_GUIDANCE)

    assert not missing, (
        f"교정 문구가 없는 reason: {sorted(missing)}. "
        "app/intent_prompt.py의 _REPAIR_GUIDANCE에 전용 문구를 추가할 것."
    )


def test_repair_guidance_has_no_unused_entry():
    """반대 방향도 본다 — 쓰이지 않는 문구는 사라진 reason의 잔해다."""

    unused = set(_REPAIR_GUIDANCE) - recoverable_reason_codes()

    assert not unused, f"생성되지 않는 reason의 문구가 남아 있다: {sorted(unused)}"


@pytest.mark.parametrize("reason", sorted(recoverable_reason_codes()))
def test_repair_prompt_carries_the_reason_specific_guidance(reason):
    """각 reason의 교정 경로가 자기 문구를 싣는지 확인한다."""

    prompt = build_intent_repair_prompt(build_intent_prompt(_request()), reason)

    assert _REPAIR_GUIDANCE[reason] in prompt
    assert _FALLBACK_REPAIR_GUIDANCE not in prompt
    assert "이전 응답 원문을 답변에 인용하지 말고" in prompt


def test_unknown_reason_falls_back_instead_of_raising():
    """사전에 없는 reason이 와도 500으로 죽지 않는다(안전망).

    다만 이 경로로 들어오는 것 자체가 결함이며, 위 대조 테스트가 그것을 먼저 막는다.
    """

    prompt = build_intent_repair_prompt(build_intent_prompt(_request()), "done_signal_blank")

    assert _FALLBACK_REPAIR_GUIDANCE in prompt


def test_gemini_sends_dialogue_schema_and_parses_dynamic_question():
    private_session = uuid.uuid4()
    request = SlotFillRequest(
        utterance="사진 보내줘",
        session_id=private_session,
    )

    def handler(http_request: httpx.Request) -> httpx.Response:
        body = json.loads(http_request.content)
        assert body["generationConfig"]["responseSchema"] == INTENT_DIALOGUE_SCHEMA
        assert body["generationConfig"]["maxOutputTokens"] == 512
        assert str(private_session) not in body["contents"][0]["parts"][0]["text"]
        assert http_request.headers["x-goog-api-key"] == "test-key"
        return httpx.Response(
            200,
            json=_gemini_response(
                {
                    "status": "needs_clarification",
                    "message": "사진을 어디로 보내고 싶으세요?",
                }
            ),
        )

    state = _resolver(handler).resolve(request)

    assert state.status == "needs_clarification"
    assert state.goal is None


def test_gemini_parses_ready_goal():
    resolver = _resolver(
        lambda _: httpx.Response(
            200,
            json=_gemini_response(
                {
                    "status": "ready",
                    "message": "카메라 앱 열기로 정리했어요.",
                    "goal": "카메라 앱 열기",
                }
            ),
        )
    )

    state = resolver.resolve(_request())

    assert state.status == "ready"
    assert state.goal == "카메라 앱 열기"


def test_gemini_normalizes_clarification_goal_without_another_call(caplog):
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return httpx.Response(
            200,
            json=_gemini_response(
                {
                    "status": "needs_clarification",
                    "message": "어느 앱을 말씀하시는 건가요?",
                    "goal": "앱 열기",
                }
            ),
        )

    with caplog.at_level(logging.WARNING, logger="uvicorn.error.davi.llm"):
        state = _resolver(handler).resolve(_request())

    assert state.status == "needs_clarification"
    assert state.goal is None
    assert len(requests) == 1
    assert any(
        event.get("reason") == "status_goal_mismatch" and event.get("action") == "normalize"
        for event in _events(caplog)
    )


def test_gemini_retries_ready_without_goal_once_without_reusing_raw_output():
    requests: list[dict] = []
    private_invalid_message = "private-invalid-model-message"

    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        requests.append(body)
        if len(requests) == 1:
            return httpx.Response(
                200,
                json=_gemini_response(
                    {
                        "status": "ready",
                        "message": private_invalid_message,
                    }
                ),
            )
        prompt = body["contents"][0]["parts"][0]["text"]
        assert "ready 상태라면 goal을 반드시 채우고" in prompt
        assert private_invalid_message not in prompt
        return httpx.Response(
            200,
            json=_gemini_response(
                {
                    "status": "ready",
                    "message": "목표를 정리했어요.",
                    "goal": "카메라 앱 열기",
                }
            ),
        )

    state = _resolver(handler).resolve(_request())

    assert state.goal == "카메라 앱 열기"
    assert len(requests) == 2


def test_gemini_contract_recovery_stops_after_second_invalid_output():
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return httpx.Response(
            200,
            json=_gemini_response({"status": "ready", "message": "목표를 정리했어요."}),
        )

    with pytest.raises(LlmError) as raised:
        _resolver(handler).resolve(_request())

    assert raised.value.kind is ErrorKind.PARSE
    assert len(requests) == 2


def test_gemini_retries_overlong_message_once():
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        if len(requests) == 1:
            return httpx.Response(
                200,
                json=_gemini_response(
                    {
                        "status": "needs_clarification",
                        "message": "가" * 301,
                    }
                ),
            )
        return httpx.Response(
            200,
            json=_gemini_response(
                {
                    "status": "needs_clarification",
                    "message": "어느 앱인가요?",
                }
            ),
        )

    state = _resolver(handler).resolve(_request())

    assert state.message == "어느 앱인가요?"
    assert len(requests) == 2


def test_gemini_retries_once_without_schema_after_schema_400():
    requests: list[dict] = []

    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        requests.append(body)
        if len(requests) == 1:
            return httpx.Response(400, json={"error": {}})
        assert "responseSchema" not in body["generationConfig"]
        assert "JSON 계약:" in body["contents"][0]["parts"][0]["text"]
        return httpx.Response(
            200,
            json=_gemini_response(
                {
                    "status": "ready",
                    "message": "목표를 정리했어요.",
                    "goal": "카메라 앱 열기",
                }
            ),
        )

    state = _resolver(handler).resolve(_request())

    assert state.status == "ready"
    assert len(requests) == 2


def test_schema_fallback_uses_total_two_call_limit_for_contract_failure():
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        if len(requests) == 1:
            return httpx.Response(400, json={"error": {}})
        return httpx.Response(
            200,
            json=_gemini_response({"status": "ready", "message": "목표를 정리했어요."}),
        )

    with pytest.raises(LlmError) as raised:
        _resolver(handler).resolve(_request())

    assert raised.value.kind is ErrorKind.PARSE
    assert len(requests) == 2


def test_invalid_status_is_not_retried():
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return httpx.Response(
            200,
            json=_gemini_response(
                {
                    "status": "unknown",
                    "message": "목표를 정리했어요.",
                    "goal": "카메라 앱 열기",
                }
            ),
        )

    with pytest.raises(LlmError) as raised:
        _resolver(handler).resolve(_request())

    assert raised.value.kind is ErrorKind.PARSE
    assert len(requests) == 1


@pytest.mark.parametrize(
    ("response", "kind"),
    [
        (httpx.Response(429, headers={"Retry-After": "17"}), ErrorKind.QUOTA),
        (httpx.Response(403), ErrorKind.AUTH),
        (httpx.Response(500), ErrorKind.SERVER),
    ],
)
def test_gemini_classifies_provider_errors(response, kind):
    with pytest.raises(LlmError) as raised:
        _resolver(lambda _: response).resolve(_request())

    assert raised.value.kind is kind
    if kind is ErrorKind.QUOTA:
        assert raised.value.retry_after_seconds == 17


def test_gemini_classifies_network_and_invalid_output_without_logging_raw_text(caplog):
    private_output = "private-model-output-canary"
    resolver = _resolver(
        lambda _: httpx.Response(
            200,
            json={"candidates": [{"content": {"parts": [{"text": private_output}]}}]},
        )
    )

    with (
        caplog.at_level(logging.WARNING, logger="uvicorn.error.davi.llm"),
        pytest.raises(LlmError) as raised,
    ):
        resolver.resolve(_request("private-utterance-canary"))

    assert raised.value.kind is ErrorKind.PARSE
    assert private_output not in json.dumps(_events(caplog), ensure_ascii=False)

    def offline(_: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("private-network-message")

    with pytest.raises(LlmError) as network_error:
        _resolver(offline).resolve(_request())
    assert network_error.value.kind is ErrorKind.NETWORK


def test_gemini_logs_only_safe_operation_metadata(caplog):
    private_values = [
        "private-utterance-canary",
        "private-key-canary",
        "private-decision-basis-canary",
    ]
    resolver = GeminiIntentResolver(
        private_values[1],
        "gemini-test",
        httpx.Client(
            transport=httpx.MockTransport(
                lambda _: httpx.Response(
                    200,
                    json=_gemini_response(
                        {
                            "decision_basis": private_values[2],
                            "status": "ready",
                            "message": "private-message-canary",
                            "goal": "private-goal-canary",
                        }
                    ),
                )
            )
        ),
    )

    with caplog.at_level(logging.INFO, logger="uvicorn.error.davi.llm"):
        resolver.resolve(_request(private_values[0]))

    events = _events(caplog)
    serialized = json.dumps(events, ensure_ascii=False)
    assert [event["event"] for event in events] == [
        "gemini_attempt_started",
        "gemini_attempt_finished",
    ]
    assert all(event["operation"] == "intent_dialogue" for event in events)
    assert all(value not in serialized for value in private_values)
    assert "private-message-canary" not in serialized
    assert "private-goal-canary" not in serialized
