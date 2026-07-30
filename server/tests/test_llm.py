import json
import logging

import httpx
import pytest
from app.llm import (
    GUIDE_ACTION_SCHEMA,
    GeminiLlmClient,
    LlmError,
    StubLlmClient,
    get_llm_client,
)
from app.schemas import ErrorKind, NextStepRequest, Screen, UiElement


def _request() -> NextStepRequest:
    return NextStepRequest(
        goal="카카오톡 열기",
        recent_instructions=["앱 서랍을 여세요."],
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
        "evidence": "카카오톡 아이콘이 보임",
        "action_type": action_type,
        "target_node_id": "n1",
        "instruction": "카카오톡을 누르세요.",
    }
    if direction is not None:
        action["direction"] = direction
    return {"candidates": [{"content": {"parts": [{"text": json.dumps(action)}]}}]}


def _client(handler) -> GeminiLlmClient:
    return GeminiLlmClient(
        "test-key",
        "gemini-test",
        httpx.Client(transport=httpx.MockTransport(handler)),
    )


def test_gemini_sends_prompt_and_response_schema():
    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        prompt = body["contents"][0]["parts"][0]["text"]
        assert "목표: 카카오톡 열기" in prompt
        assert "id=n1" in prompt
        assert "카카오톡" in prompt
        assert "앱 서랍을 여세요." in prompt
        assert "페이지: 전체 4쪽 중 1쪽" in prompt
        assert body["generationConfig"]["responseMimeType"] == "application/json"
        assert body["generationConfig"]["maxOutputTokens"] == 1024
        assert body["generationConfig"]["responseSchema"] == GUIDE_ACTION_SCHEMA
        assert request.headers["x-goog-api-key"] == "test-key"
        return httpx.Response(200, json=_action_response())

    action = _client(handler).reason(_request())

    assert action.action_type == "tap"
    assert action.target_node_id == "n1"


def test_gemini_retries_once_without_schema_after_schema_400():
    requests: list[dict] = []

    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        requests.append(body)
        if len(requests) == 1:
            assert "responseSchema" in body["generationConfig"]
            return httpx.Response(400, json={"error": {"message": "schema rejected"}})
        assert "responseSchema" not in body["generationConfig"]
        assert body["generationConfig"]["maxOutputTokens"] == 1024
        assert "JSON 계약:" in body["contents"][0]["parts"][0]["text"]
        return httpx.Response(200, json=_action_response())

    action = _client(handler).reason(_request())

    assert action.action_type == "tap"
    assert len(requests) == 2


def test_gemini_retries_missing_scroll_direction_once(caplog):
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
    assert all(request["generationConfig"]["maxOutputTokens"] == 1024 for request in requests)
    messages = [record.getMessage() for record in caplog.records]
    assert messages == ["gemini_parse_recovery reason=scroll_direction_missing action=retry"]
    assert all("카카오톡" not in message for message in messages)


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
    assert all(request["generationConfig"]["maxOutputTokens"] == 1024 for request in requests)


def test_missing_scroll_direction_after_retry_is_a_distinct_parse_failure(caplog):
    with (
        caplog.at_level(logging.WARNING, logger="uvicorn.error.davi.llm"),
        pytest.raises(LlmError) as raised,
    ):
        _client(lambda _: httpx.Response(200, json=_action_response("scroll"))).reason(_request())

    assert raised.value.kind is ErrorKind.PARSE
    assert raised.value.message == "Gemini scroll 응답에 direction이 없음 (1회 재시도 실패)"
    assert [record.getMessage() for record in caplog.records] == [
        "gemini_parse_recovery reason=scroll_direction_missing action=retry",
        "gemini_parse_recovery reason=scroll_direction_missing action=fail",
    ]


def test_gemini_classifies_rate_limit_and_uses_retry_after():
    client = _client(
        lambda _: httpx.Response(429, headers={"Retry-After": "17"}, json={"error": {}})
    )

    with pytest.raises(LlmError) as raised:
        client.reason(_request())

    assert raised.value.kind is ErrorKind.QUOTA
    assert raised.value.retry_after_seconds == 17


def test_gemini_keeps_zero_retry_after():
    client = _client(
        lambda _: httpx.Response(429, headers={"Retry-After": "0"}, json={"error": {}})
    )

    with pytest.raises(LlmError) as raised:
        client.reason(_request())

    assert raised.value.retry_after_seconds == 0


def test_gemini_classifies_auth_failure():
    client = _client(lambda _: httpx.Response(403, json={"error": {}}))

    with pytest.raises(LlmError) as raised:
        client.reason(_request())

    assert raised.value.kind is ErrorKind.AUTH


def test_gemini_classifies_network_failure():
    def handler(_: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("offline")

    with pytest.raises(LlmError) as raised:
        _client(handler).reason(_request())

    assert raised.value.kind is ErrorKind.NETWORK


def test_gemini_classifies_server_and_fallback_bad_request():
    with pytest.raises(LlmError) as server_error:
        _client(lambda _: httpx.Response(500, json={"error": {}})).reason(_request())
    assert server_error.value.kind is ErrorKind.SERVER

    with pytest.raises(LlmError) as bad_request_error:
        _client(lambda _: httpx.Response(400, json={"error": {}})).reason(_request())
    assert bad_request_error.value.kind is ErrorKind.BAD_REQUEST


def test_gemini_classifies_invalid_model_output_as_parse_error(caplog):
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
    assert raised.value.message == "Gemini 응답이 GuideAction 계약과 맞지 않음"
    assert [record.getMessage() for record in caplog.records] == [
        "gemini_parse_error reason=invalid_json action=fail"
    ]


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
    )

    client = get_llm_client()

    assert isinstance(client, GeminiLlmClient)
    assert client.api_key == "server-side-key"
    assert client.model == "gemini-test"
