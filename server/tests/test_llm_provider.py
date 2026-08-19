"""공급자 경계: Gemini·OpenAI·Anthropic 교체와 공통 오류·로그 규칙.

호출부(next-step / slot-fill / app-resolution)의 대화 흐름은 각 테스트 파일이 본다. 여기서는
**공급자를 바꿔도 같은 계약이 나가는지**와, 공급자별 요청·응답 모양만 본다.
"""

from __future__ import annotations

import json
import logging

import httpx
import pytest
from app.llm import GUIDE_ACTION_CONTRACT, RemoteLlmClient, get_llm_client
from app.llm_provider import (
    AnthropicProvider,
    GeminiProvider,
    OpenAiProvider,
    ProviderCallError,
    build_provider,
)
from app.schemas import ErrorKind, NextStepRequest, Screen, UiElement

_ACTION = {
    "expected_result": "카카오톡이 열린다",
    "evidence": "'카카오톡' 아이콘이 보임",
    "action_type": "tap",
    "direction": None,
    "target_node_id": "n1",
    "instruction": "'카카오톡'을 눌러 주세요.",
}


def _request() -> NextStepRequest:
    return NextStepRequest(
        goal="카카오톡 열기",
        screen=Screen(
            package_name="com.android.launcher",
            elements=[UiElement(node_id="n1", label="카카오톡", clickable=True)],
        ),
    )


def _provider(cls, handler, **kwargs):
    return cls(
        api_key="test-key",
        model="test-model",
        operation="next_step",
        contract=GUIDE_ACTION_CONTRACT,
        max_output_tokens=1024,
        http_client=httpx.Client(transport=httpx.MockTransport(handler)),
        **kwargs,
    )


def _openai_body(text: str, **extra) -> dict:
    payload = {
        "status": "completed",
        "output": [
            {"type": "reasoning", "summary": []},
            {"type": "message", "content": [{"type": "output_text", "text": text}]},
        ],
    }
    payload.update(extra)
    return payload


def _anthropic_body(text: str, **extra) -> dict:
    payload = {"stop_reason": "end_turn", "content": [{"type": "text", "text": text}]}
    payload.update(extra)
    return payload


def _events(caplog) -> list[dict]:
    return [json.loads(record.getMessage()) for record in caplog.records]


# --- OpenAI --------------------------------------------------------------


def test_openai_sends_strict_json_schema_and_reasoning_effort():
    seen: dict = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["url"] = str(request.url)
        seen["auth"] = request.headers["Authorization"]
        seen["body"] = json.loads(request.content)
        return httpx.Response(200, json=_openai_body(json.dumps(_ACTION)))

    provider = _provider(OpenAiProvider, handler, reasoning_effort="low")
    action = RemoteLlmClient(provider).reason(_request())

    assert action.target_node_id == "n1"
    assert seen["url"] == "https://api.openai.com/v1/responses"
    assert seen["auth"] == "Bearer test-key"
    body = seen["body"]
    assert body["model"] == "test-model"
    assert body["max_output_tokens"] == 1024
    assert body["reasoning"] == {"effort": "low"}
    assert body["text"]["format"] == {
        "type": "json_schema",
        "name": "next_step",
        "strict": True,
        "schema": GUIDE_ACTION_CONTRACT.json_schema,
    }
    assert "목표: 카카오톡 열기" in body["input"][0]["content"]


def test_openai_schema_rejection_falls_back_to_a_prompt_contract():
    """스키마 400 하나로 앱 전체가 죽지 않게, 계약을 프롬프트로 옮겨 한 번 복구한다."""

    bodies: list[dict] = []

    def handler(request: httpx.Request) -> httpx.Response:
        bodies.append(json.loads(request.content))
        if len(bodies) == 1:
            return httpx.Response(400, json={"error": "unsupported schema"})
        return httpx.Response(200, json=_openai_body(json.dumps(_ACTION)))

    RemoteLlmClient(_provider(OpenAiProvider, handler)).reason(_request())

    assert len(bodies) == 2
    assert "text" not in bodies[1]
    assert "JSON 계약" in bodies[1]["input"][0]["content"]


def test_openai_truncated_response_is_a_parse_error_not_a_contract_error():
    def handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json=_openai_body(
                '{"expected_result": "잘린',
                status="incomplete",
                incomplete_details={"reason": "max_output_tokens"},
            ),
        )

    with pytest.raises(Exception) as raised:
        RemoteLlmClient(_provider(OpenAiProvider, handler)).reason(_request())

    assert raised.value.kind is ErrorKind.PARSE
    assert raised.value.message == "OpenAI 응답이 출력 토큰 상한에서 잘림"


def test_openai_refusal_is_reported_instead_of_parsed_as_an_empty_answer():
    def handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "status": "completed",
                "output": [{"type": "message", "content": [{"type": "refusal", "refusal": "..."}]}],
            },
        )

    with pytest.raises(Exception) as raised:
        RemoteLlmClient(_provider(OpenAiProvider, handler)).reason(_request())

    assert raised.value.message == "OpenAI 공급자가 요청을 거절함"


# --- Anthropic -----------------------------------------------------------


def test_anthropic_sends_output_config_and_version_header():
    seen: dict = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["url"] = str(request.url)
        seen["headers"] = request.headers
        seen["body"] = json.loads(request.content)
        return httpx.Response(200, json=_anthropic_body(json.dumps(_ACTION)))

    action = RemoteLlmClient(_provider(AnthropicProvider, handler)).reason(_request())

    assert action.target_node_id == "n1"
    assert seen["url"] == "https://api.anthropic.com/v1/messages"
    assert seen["headers"]["x-api-key"] == "test-key"
    assert seen["headers"]["anthropic-version"] == "2023-06-01"
    body = seen["body"]
    assert body["max_tokens"] == 1024
    assert body["output_config"]["format"] == {
        "type": "json_schema",
        "schema": GUIDE_ACTION_CONTRACT.json_schema,
    }
    # thinking은 보내지 않는다: 짧은 JSON 하나를 뽑는 일이라 지연만 늘고, 모델 세대마다
    # 파라미터 규약이 갈려 여기서 분기를 만들 값이 없다(llm_provider.py 모듈 문서).
    assert "thinking" not in body


def test_anthropic_schema_rejection_falls_back_to_a_prompt_contract():
    bodies: list[dict] = []

    def handler(request: httpx.Request) -> httpx.Response:
        bodies.append(json.loads(request.content))
        if len(bodies) == 1:
            return httpx.Response(400, json={"error": {"type": "invalid_request_error"}})
        return httpx.Response(200, json=_anthropic_body(json.dumps(_ACTION)))

    RemoteLlmClient(_provider(AnthropicProvider, handler)).reason(_request())

    assert len(bodies) == 2
    assert "output_config" not in bodies[1]
    assert "JSON 계약" in bodies[1]["messages"][0]["content"]


@pytest.mark.parametrize(
    ("stop_reason", "message"),
    [
        ("max_tokens", "Anthropic 응답이 출력 토큰 상한에서 잘림"),
        ("refusal", "Anthropic 공급자가 요청을 거절함"),
    ],
)
def test_anthropic_stop_reason_is_read_before_the_content_blocks(stop_reason, message):
    """거절·잘림은 HTTP 200으로 온다. content를 먼저 읽으면 '본문 없음'으로 오분류된다."""

    def handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"stop_reason": stop_reason, "content": []})

    with pytest.raises(Exception) as raised:
        RemoteLlmClient(_provider(AnthropicProvider, handler)).reason(_request())

    assert raised.value.kind is ErrorKind.PARSE
    assert raised.value.message == message


def test_anthropic_strips_a_markdown_json_fence():
    def handler(_: httpx.Request) -> httpx.Response:
        fenced = f"```json\n{json.dumps(_ACTION)}\n```"
        return httpx.Response(200, json=_anthropic_body(fenced))

    action = RemoteLlmClient(_provider(AnthropicProvider, handler)).reason(_request())

    assert action.target_node_id == "n1"


# --- 공통 규칙 ------------------------------------------------------------


@pytest.mark.parametrize(
    ("cls", "body_factory"),
    [
        (OpenAiProvider, _openai_body),
        (AnthropicProvider, _anthropic_body),
        (
            GeminiProvider,
            lambda text: {
                "candidates": [{"finishReason": "STOP", "content": {"parts": [{"text": text}]}}]
            },
        ),
    ],
)
def test_every_provider_produces_the_same_guide_action(cls, body_factory):
    """공급자를 바꿔도 앱이 받는 계약은 같아야 한다."""

    def handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json=body_factory(json.dumps(_ACTION)))

    action = RemoteLlmClient(_provider(cls, handler)).reason(_request())

    assert action.model_dump() == {**_ACTION, "action_type": "tap", "direction": None}


@pytest.mark.parametrize("cls", [OpenAiProvider, AnthropicProvider])
def test_rate_limit_maps_to_quota_with_the_retry_after_header(cls):
    def handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(429, headers={"Retry-After": "12"}, json={})

    with pytest.raises(Exception) as raised:
        RemoteLlmClient(_provider(cls, handler)).reason(_request())

    assert raised.value.kind is ErrorKind.QUOTA
    assert raised.value.retry_after_seconds == 12


@pytest.mark.parametrize(
    ("status", "kind"),
    [(401, ErrorKind.AUTH), (403, ErrorKind.AUTH), (500, ErrorKind.SERVER)],
)
def test_http_status_maps_to_the_same_error_kind_for_every_provider(status, kind):
    def handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(status, json={})

    with pytest.raises(Exception) as raised:
        RemoteLlmClient(_provider(AnthropicProvider, handler)).reason(_request())

    assert raised.value.kind is kind


def test_missing_api_key_fails_as_auth_before_any_request():
    with pytest.raises(Exception) as raised:
        OpenAiProvider(
            api_key="",
            model="test-model",
            operation="next_step",
            contract=GUIDE_ACTION_CONTRACT,
            max_output_tokens=1024,
        )

    assert raised.value.kind is ErrorKind.AUTH
    assert "OPENAI_API_KEY" in raised.value.message


def test_attempt_logs_name_the_provider_and_carry_no_prompt_or_response(caplog):
    private = "private-goal-canary"

    def handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json=_anthropic_body(json.dumps(_ACTION)))

    provider = _provider(AnthropicProvider, handler)
    with caplog.at_level(logging.INFO, logger="uvicorn.error.davi.llm"):
        RemoteLlmClient(provider).reason(
            NextStepRequest(
                goal=private,
                screen=Screen(elements=[UiElement(node_id="n1", label="카카오톡", clickable=True)]),
            )
        )

    events = _events(caplog)
    assert [event["event"] for event in events] == [
        "llm_attempt_started",
        "llm_attempt_finished",
    ]
    assert all(event["provider"] == "anthropic" for event in events)
    assert all(event["operation"] == "next_step" for event in events)
    assert private not in json.dumps(events, ensure_ascii=False)


def test_provider_call_error_keeps_the_http_status_for_the_schema_retry():
    """400만 '스키마 거부'로 보고 재시도한다. 나머지는 그대로 오류로 닫힌다."""

    def handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(400, json={})

    provider = _provider(AnthropicProvider, handler)
    with pytest.raises(ProviderCallError) as raised:
        provider.generate_json("prompt", with_schema=True, attempt=1, trigger="initial")

    assert raised.value.status_code == 400
    assert provider.to_llm_error(raised.value).kind is ErrorKind.BAD_REQUEST


# --- 설정에 따른 교체 ------------------------------------------------------


@pytest.mark.parametrize(
    ("provider_name", "key_field", "cls", "expected_model"),
    [
        ("gemini", "gemini_api_key", GeminiProvider, "gemini-3.5-flash-lite"),
        ("openai", "openai_api_key", OpenAiProvider, "gpt-5.6-luna"),
        ("anthropic", "anthropic_api_key", AnthropicProvider, "claude-haiku-4-5"),
    ],
)
def test_settings_select_the_provider_and_its_default_model(
    set_runtime_profile, provider_name, key_field, cls, expected_model
):
    keys = {"gemini_api_key": "", "openai_api_key": "", "anthropic_api_key": ""}
    keys[key_field] = "server-side-key"
    set_runtime_profile(
        davi_use_stub_llm=False,
        davi_llm_provider=provider_name,
        davi_model="",
        **keys,
    )

    provider = build_provider(
        contract=GUIDE_ACTION_CONTRACT,
        operation="next_step",
        response_headroom=1024,
    )

    assert isinstance(provider, cls)
    assert provider.model == expected_model
    assert provider.api_key == "server-side-key"


def test_output_budget_leaves_room_for_hidden_reasoning_tokens(set_runtime_profile):
    """보이지 않는 추론 토큰이 예산을 다 먹어 답이 잘리는 실패를 막는 자리(2026-08-18 실측)."""

    set_runtime_profile(
        davi_use_stub_llm=False,
        davi_llm_provider="gemini",
        gemini_api_key="server-side-key",
        davi_gemini_thinking_budget=300,
    )
    assert _built().max_output_tokens == 300 + 1024

    set_runtime_profile(
        davi_use_stub_llm=False,
        davi_llm_provider="openai",
        gemini_api_key="",
        openai_api_key="server-side-key",
        davi_openai_reasoning_effort="low",
    )
    assert _built().max_output_tokens == 2048 + 1024

    # reasoning을 끄면 여유분도 얹지 않는다.
    set_runtime_profile(
        davi_use_stub_llm=False,
        davi_llm_provider="openai",
        gemini_api_key="",
        openai_api_key="server-side-key",
        davi_openai_reasoning_effort="none",
    )
    assert _built().max_output_tokens == 1024

    # Anthropic은 thinking을 보내지 않으므로 정상 응답 몫만 있으면 된다.
    set_runtime_profile(
        davi_use_stub_llm=False,
        davi_llm_provider="anthropic",
        gemini_api_key="",
        anthropic_api_key="server-side-key",
    )
    assert _built().max_output_tokens == 1024


def test_max_output_tokens_override_wins(set_runtime_profile):
    set_runtime_profile(
        davi_use_stub_llm=False,
        davi_llm_provider="anthropic",
        gemini_api_key="",
        anthropic_api_key="server-side-key",
        davi_max_output_tokens=4096,
    )

    assert _built().max_output_tokens == 4096


def test_get_llm_client_follows_the_configured_provider(set_runtime_profile):
    set_runtime_profile(
        davi_use_stub_llm=False,
        davi_llm_provider="anthropic",
        davi_model="",
        gemini_api_key="",
        anthropic_api_key="server-side-key",
    )
    get_llm_client.cache_clear()

    client = get_llm_client()

    assert isinstance(client, RemoteLlmClient)
    assert isinstance(client.provider, AnthropicProvider)
    assert client.provider.model == "claude-haiku-4-5"


def _built():
    return build_provider(
        contract=GUIDE_ACTION_CONTRACT,
        operation="next_step",
        response_headroom=1024,
    )
