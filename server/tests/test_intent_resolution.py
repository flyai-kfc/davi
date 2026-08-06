"""고정 슬롯 없는 음성 목표 대화와 Gemini 구조화 출력 경계."""

import json
import logging
import uuid

import httpx
import pytest
from app.errors import LlmError
from app.intent_prompt import build_intent_prompt
from app.intent_resolution import (
    INTENT_DIALOGUE_SCHEMA,
    GeminiIntentResolver,
    StubIntentResolver,
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


def _gemini_response(payload: dict) -> dict:
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
    assert set(INTENT_DIALOGUE_SCHEMA["properties"]) == {"status", "message", "goal"}


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
    assert "target_person" not in prompt
    assert "답변 위치만 보고 의미 없는 현재 답변" in prompt


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
    private_values = ["private-utterance-canary", "private-key-canary"]
    resolver = GeminiIntentResolver(
        private_values[1],
        "gemini-test",
        httpx.Client(
            transport=httpx.MockTransport(
                lambda _: httpx.Response(
                    200,
                    json=_gemini_response(
                        {
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
