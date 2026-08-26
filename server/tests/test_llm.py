import json
import logging

import httpx
import pytest
from app.llm import (
    GUIDE_ACTION_SCHEMA,
    LlmError,
    RemoteLlmClient,
    StubLlmClient,
    get_llm_client,
)
from app.schemas import (
    ActionType,
    ErrorKind,
    GuideStep,
    NextStepRequest,
    Screen,
    UiElement,
)

from tests.factories import gemini_llm_client


def _request() -> NextStepRequest:
    return NextStepRequest(
        goal="카카오톡 열기",
        steps=[
            GuideStep(
                instruction="앱 서랍을 여세요.",
                action_type=ActionType.OPEN_APP_DRAWER,
                screen_hint="런처 홈 화면",
                structure_signature="a" * 64,
                screen_changed=True,
            )
        ],
        screen=Screen(
            package_name="com.android.launcher",
            current_page=1,
            total_pages=4,
            elements=[
                UiElement(
                    node_id="n1",
                    label="카카오톡",
                    role="ImageView",
                    clickable=True,
                    bounds=[10, 20, 110, 120],
                )
            ],
        ),
    )


def _action_response(action_type: str = "tap", direction: str | None = None) -> dict:
    action = {
        "expected_result": "카카오톡이 열린다",
        "screen_summary": "런처 홈 화면",
        "evidence": "카카오톡 아이콘이 보임",
        "action_type": action_type,
        "target_node_id": "n1",
        "instruction": "카카오톡을 누르세요.",
    }
    if direction is not None:
        action["direction"] = direction
    return {
        "candidates": [
            {
                "finishReason": "STOP",
                "content": {"parts": [{"text": json.dumps(action)}]},
            }
        ]
    }


def _client(handler) -> RemoteLlmClient:
    return gemini_llm_client(
        "test-key",
        "gemini-test",
        httpx.Client(transport=httpx.MockTransport(handler)),
    )


def _events(caplog) -> list[dict]:
    return [json.loads(record.getMessage()) for record in caplog.records]


def test_llm_sends_prompt_and_response_schema():
    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        prompt = body["contents"][0]["parts"][0]["text"]
        assert "목표: 카카오톡 열기" in prompt
        assert "id=n1" in prompt
        assert "카카오톡" in prompt
        assert "앱 서랍을 여세요." in prompt
        assert "페이지: 전체 4쪽 중 1쪽" in prompt
        assert body["generationConfig"]["responseMimeType"] == "application/json"
        assert body["generationConfig"]["maxOutputTokens"] == 1824
        assert body["generationConfig"]["responseSchema"] == GUIDE_ACTION_SCHEMA
        # 알 수 없는/3.x 모델에는 2.5 전용 numeric budget을 보내지 않는다(#457).
        assert "thinkingConfig" not in body["generationConfig"]
        assert request.headers["x-goog-api-key"] == "test-key"
        return httpx.Response(200, json=_action_response())

    action = _client(handler).reason(_request())

    assert action.action_type == "tap"
    assert action.target_node_id == "n1"


def test_llm_retries_once_without_schema_after_schema_400():
    requests: list[dict] = []

    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        requests.append(body)
        if len(requests) == 1:
            assert "responseSchema" in body["generationConfig"]
            return httpx.Response(400, json={"error": {"message": "schema rejected"}})
        assert "responseSchema" not in body["generationConfig"]
        assert body["generationConfig"]["maxOutputTokens"] == 1824
        assert "JSON 계약:" in body["contents"][0]["parts"][0]["text"]
        return httpx.Response(200, json=_action_response())

    action = _client(handler).reason(_request())

    assert action.action_type == "tap"
    assert len(requests) == 2


def test_missing_screen_summary_does_not_kill_a_valid_action():
    """요약 한 줄이 없다고 멀쩡한 안내를 PARSE로 닫지 않는다(#289 셀프 리뷰).

    스키마에서는 required지만 강제하는 것이 없는 경로가 있다 — 스키마가 400으로 거부돼
    프롬프트로만 계약을 말하는 폴백이다. 거기서 부수 필드 하나가 빠졌다고 실패로 닫으면
    사용자는 쓸 수 있는 안내 대신 오류 문구를 본다.
    """

    def handler(request: httpx.Request) -> httpx.Response:
        payload = _action_response()
        candidate = payload["candidates"][0]["content"]["parts"][0]
        action = json.loads(candidate["text"])
        del action["screen_summary"]
        candidate["text"] = json.dumps(action, ensure_ascii=False)
        return httpx.Response(200, json=payload)

    action = _client(handler).reason(_request())

    assert action.action_type == "tap"
    assert action.screen_summary == ""


def test_llm_retries_missing_scroll_direction_once(caplog):
    requests: list[dict] = []

    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        requests.append(body)
        if len(requests) == 1:
            return httpx.Response(200, json=_action_response("scroll"))
        assert (
            "direction을 up, down, left, right 중 하나로 반드시 포함"
            in (body["contents"][0]["parts"][0]["text"])
        )
        return httpx.Response(200, json=_action_response("scroll", "up"))

    with caplog.at_level(logging.WARNING, logger="uvicorn.error.davi.llm"):
        action = _client(handler).reason(_request())

    assert action.action_type == "scroll"
    assert action.direction == "up"
    assert len(requests) == 2
    assert all(request["generationConfig"]["maxOutputTokens"] == 1824 for request in requests)
    events = _events(caplog)
    assert [(event["event"], event["reason"], event["action"]) for event in events] == [
        ("llm_parse_recovery", "scroll_direction_missing", "retry")
    ]
    assert all("카카오톡" not in json.dumps(event, ensure_ascii=False) for event in events)


def test_schema_fallback_uses_same_missing_direction_retry_policy():
    requests: list[dict] = []

    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        requests.append(body)
        if len(requests) == 1:
            return httpx.Response(400, json={"error": {"message": "schema rejected"}})
        if len(requests) == 2:
            return httpx.Response(200, json=_action_response("scroll"))
        return httpx.Response(200, json=_action_response("scroll", "left"))

    action = _client(handler).reason(_request())

    assert action.direction == "left"
    assert len(requests) == 3
    assert "responseSchema" in requests[0]["generationConfig"]
    assert all("responseSchema" not in request["generationConfig"] for request in requests[1:])
    assert all(request["generationConfig"]["maxOutputTokens"] == 1824 for request in requests)


def test_missing_scroll_direction_after_retry_is_a_distinct_parse_failure(caplog):
    with (
        caplog.at_level(logging.WARNING, logger="uvicorn.error.davi.llm"),
        pytest.raises(LlmError) as raised,
    ):
        _client(lambda _: httpx.Response(200, json=_action_response("scroll"))).reason(_request())

    assert raised.value.kind is ErrorKind.PARSE
    assert raised.value.message == "scroll 응답에 direction이 없음 (1회 재시도 실패)"
    assert [(event["reason"], event["action"]) for event in _events(caplog)] == [
        ("scroll_direction_missing", "retry"),
        ("scroll_direction_missing", "fail"),
    ]


def test_llm_classifies_rate_limit_and_uses_retry_after():
    client = _client(
        lambda _: httpx.Response(429, headers={"Retry-After": "17"}, json={"error": {}})
    )

    with pytest.raises(LlmError) as raised:
        client.reason(_request())

    assert raised.value.kind is ErrorKind.QUOTA
    assert raised.value.retry_after_seconds == 17


def test_llm_keeps_zero_retry_after():
    client = _client(
        lambda _: httpx.Response(429, headers={"Retry-After": "0"}, json={"error": {}})
    )

    with pytest.raises(LlmError) as raised:
        client.reason(_request())

    assert raised.value.retry_after_seconds == 0


def test_llm_classifies_auth_failure():
    client = _client(lambda _: httpx.Response(403, json={"error": {}}))

    with pytest.raises(LlmError) as raised:
        client.reason(_request())

    assert raised.value.kind is ErrorKind.AUTH


def test_llm_classifies_network_failure():
    def handler(_: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("offline")

    with pytest.raises(LlmError) as raised:
        _client(handler).reason(_request())

    assert raised.value.kind is ErrorKind.NETWORK


def test_llm_classifies_server_and_fallback_bad_request():
    with pytest.raises(LlmError) as server_error:
        _client(lambda _: httpx.Response(500, json={"error": {}})).reason(_request())
    assert server_error.value.kind is ErrorKind.SERVER

    with pytest.raises(LlmError) as bad_request_error:
        _client(lambda _: httpx.Response(400, json={"error": {}})).reason(_request())
    assert bad_request_error.value.kind is ErrorKind.BAD_REQUEST


def test_llm_classifies_invalid_model_output_as_parse_error(caplog):
    client = _client(
        lambda _: httpx.Response(
            200,
            json={"candidates": [{"content": {"parts": [{"text": "not json"}]}}]},
        )
    )

    with (
        caplog.at_level(logging.WARNING, logger="uvicorn.error.davi.llm"),
        pytest.raises(LlmError) as raised,
    ):
        client.reason(_request())

    assert raised.value.kind is ErrorKind.PARSE
    assert raised.value.message == "응답이 GuideAction 계약과 맞지 않음"
    assert [(event["event"], event["reason"], event["action"]) for event in _events(caplog)] == [
        ("llm_parse_error", "invalid_json", "fail")
    ]


def test_llm_classifies_max_tokens_without_logging_response_text(caplog):
    truncated_text = '{"expected_result":"sensitive-model-fragment"'
    client = _client(
        lambda _: httpx.Response(
            200,
            json={
                "candidates": [
                    {
                        "finishReason": "MAX_TOKENS",
                        "content": {"parts": [{"text": truncated_text}]},
                    }
                ]
            },
        )
    )

    with (
        caplog.at_level(logging.WARNING, logger="uvicorn.error.davi.llm"),
        pytest.raises(LlmError) as raised,
    ):
        client.reason(_request())

    assert raised.value.kind is ErrorKind.PARSE
    assert raised.value.message == "Gemini 응답이 출력 토큰 상한에서 잘림"
    events = _events(caplog)
    assert [(event["event"], event["reason"], event["action"]) for event in events] == [
        ("llm_response_error", "max_output_tokens", "fail")
    ]
    assert all(truncated_text not in json.dumps(event) for event in events)


def test_get_llm_client_reuses_one_connection_pool():
    first = get_llm_client()
    second = get_llm_client()

    assert first is second


def test_get_llm_client_uses_new_settings_after_cache_clear(set_runtime_profile):
    assert isinstance(get_llm_client(), StubLlmClient)

    set_runtime_profile(
        davi_use_stub_llm=False,
        gemini_api_key="server-side-key",
        davi_model="gemini-test",
        davi_gemini_thinking_budget=321,
    )

    client = get_llm_client()

    assert isinstance(client, RemoteLlmClient)
    assert client.provider.api_key == "server-side-key"
    assert client.provider.model == "gemini-test"
    # DAVI_GEMINI_THINKING_BUDGET이 공급자까지 흘러가는지(config.py) 확인한다.
    # maxOutputTokens는 이 값 위에 원래 여유분(1024)을 얹은 것이어야 잘림을 안 만든다.
    assert client.provider.thinking_budget == 321
    assert client.provider.max_output_tokens == 321 + 1024
