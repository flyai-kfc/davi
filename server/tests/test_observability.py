import json
import logging
import uuid
from pathlib import Path

import app.main as main_module
import httpx
import pytest
from app.config import settings
from app.errors import LlmError
from app.main import app
from app.observability import (
    ATTEMPT_HEADER,
    LOGICAL_REQUEST_ID_HEADER,
    REQUEST_ID_HEADER,
    SafeJsonFormatter,
    TraceContext,
    reset_trace_context,
    set_trace_context,
)
from app.schemas import NextStepRequest, Screen, UiElement
from fastapi.testclient import TestClient
from pydantic import SecretStr

from tests.factories import gemini_llm_client

client = TestClient(app, client=("127.0.0.1", 50000))
ANDROID_TRACING = (
    Path(__file__).resolve().parents[2]
    / "app-android/app/src/main/java/com/davi/helper/RequestTracing.kt"
)
LOGGING_CONFIG = Path(__file__).resolve().parents[1] / "app/logging.json"


def _payload() -> dict:
    return {
        "goal": "open app",
        "client_version": "0.1.0",
        "screen": {
            "package_name": "com.example.launcher",
            "elements": [{"node_id": "n1", "label": "masked", "clickable": True}],
        },
    }


def _trace_headers(logical_request_id: str | None = None, attempt: int = 1) -> dict[str, str]:
    return {
        LOGICAL_REQUEST_ID_HEADER: logical_request_id or str(uuid.uuid4()),
        ATTEMPT_HEADER: str(attempt),
    }


def _events(caplog, logger_name: str) -> list[dict]:
    return [
        json.loads(record.getMessage()) for record in caplog.records if record.name == logger_name
    ]


def _gemini_response(action_type: str = "tap", direction: str | None = None) -> dict:
    action = {
        "expected_result": "result",
        "evidence": "evidence",
        "action_type": action_type,
        "target_node_id": "n1",
        "instruction": "private-response-canary",
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


def test_logical_request_headers_are_echoed_without_changing_json_body(caplog):
    headers = _trace_headers(attempt=2)

    with caplog.at_level(logging.INFO, logger="uvicorn.error.davi.requests"):
        response = client.post("/v1/next-step", json=_payload(), headers=headers)

    assert response.status_code == 200
    assert response.headers[LOGICAL_REQUEST_ID_HEADER] == headers[LOGICAL_REQUEST_ID_HEADER]
    assert response.headers[ATTEMPT_HEADER] == "2"
    assert response.headers[REQUEST_ID_HEADER]
    assert "logical_request_id" not in response.json()
    events = _events(caplog, "uvicorn.error.davi.requests")
    assert [event["event"] for event in events] == ["request_received", "request_finished"]
    assert all(
        event["logical_request_id"] == headers[LOGICAL_REQUEST_ID_HEADER] for event in events
    )
    assert all(event["attempt"] == 2 for event in events)


def test_legacy_request_without_trace_headers_remains_compatible():
    response = client.post("/v1/next-step", json=_payload())

    assert response.status_code == 200
    assert REQUEST_ID_HEADER in response.headers
    assert LOGICAL_REQUEST_ID_HEADER not in response.headers
    assert ATTEMPT_HEADER not in response.headers


def test_partial_or_malformed_trace_headers_return_bad_request_without_echo():
    cases = [
        {LOGICAL_REQUEST_ID_HEADER: str(uuid.uuid4())},
        {ATTEMPT_HEADER: "1"},
        _trace_headers("NOT-A-CANONICAL-UUID"),
        _trace_headers(attempt=0),
        _trace_headers(attempt=2_147_483_648),
        {**_trace_headers(), ATTEMPT_HEADER: "+1"},
        {**_trace_headers(), ATTEMPT_HEADER: "01"},
    ]

    for headers in cases:
        response = client.post("/v1/next-step", json=_payload(), headers=headers)
        assert response.status_code == 400
        assert response.json()["kind"] == "BAD_REQUEST"
        assert response.headers[REQUEST_ID_HEADER] == response.json()["request_id"]
        assert LOGICAL_REQUEST_ID_HEADER not in response.headers
        assert ATTEMPT_HEADER not in response.headers


def test_duplicate_trace_headers_are_rejected_without_reflecting_values():
    first = str(uuid.uuid4())
    second = str(uuid.uuid4())
    headers = [
        (LOGICAL_REQUEST_ID_HEADER, first),
        (LOGICAL_REQUEST_ID_HEADER, second),
        (ATTEMPT_HEADER, "1"),
    ]

    response = client.post("/v1/next-step", json=_payload(), headers=headers)

    assert response.status_code == 400
    assert response.json()["kind"] == "BAD_REQUEST"
    assert LOGICAL_REQUEST_ID_HEADER not in response.headers


def test_valid_trace_is_echoed_on_auth_and_validation_errors(monkeypatch):
    trace = _trace_headers()
    monkeypatch.setattr(settings, "davi_dev_access_token", SecretStr("t" * 32))

    unauthorized = client.post("/v1/next-step", json=_payload(), headers=trace)
    assert unauthorized.status_code == 401
    assert unauthorized.headers[LOGICAL_REQUEST_ID_HEADER] == trace[LOGICAL_REQUEST_ID_HEADER]
    assert unauthorized.headers[ATTEMPT_HEADER] == "1"

    authorized_headers = {**trace, "X-Davi-Dev-Token": "t" * 32}
    invalid = client.post("/v1/next-step", json={"screen": {}}, headers=authorized_headers)
    assert invalid.status_code == 422
    assert invalid.headers[LOGICAL_REQUEST_ID_HEADER] == trace[LOGICAL_REQUEST_ID_HEADER]
    assert invalid.headers[ATTEMPT_HEADER] == "1"


def test_valid_trace_is_echoed_on_rate_limit_and_llm_errors(monkeypatch):
    trace = _trace_headers()

    llm_error = client.post(
        "/v1/next-step",
        json={"goal": "x", "screen": {"elements": []}},
        headers=trace,
    )
    assert llm_error.status_code == 502
    assert llm_error.headers[LOGICAL_REQUEST_ID_HEADER] == trace[LOGICAL_REQUEST_ID_HEADER]
    assert llm_error.headers[ATTEMPT_HEADER] == "1"

    token = "t" * 32
    monkeypatch.setattr(settings, "davi_dev_access_token", SecretStr(token))
    monkeypatch.setattr(settings, "davi_dev_rate_limit_requests", 1)
    authorized = {**trace, "X-Davi-Dev-Token": token}
    first = client.post("/v1/next-step", json=_payload(), headers=authorized)
    limited = client.post(
        "/v1/next-step",
        json=_payload(),
        headers={**authorized, ATTEMPT_HEADER: "2"},
    )

    assert first.status_code == 200
    assert limited.status_code == 429
    assert limited.headers[LOGICAL_REQUEST_ID_HEADER] == trace[LOGICAL_REQUEST_ID_HEADER]
    assert limited.headers[ATTEMPT_HEADER] == "2"
    assert limited.headers[REQUEST_ID_HEADER] == limited.json()["request_id"]


def test_all_asgi_responses_have_request_id_but_successful_health_is_not_logged(caplog):
    with caplog.at_level(logging.INFO, logger="uvicorn.error.davi.requests"):
        response = client.get("/health")

    assert response.status_code == 200
    assert response.headers[REQUEST_ID_HEADER]
    assert _events(caplog, "uvicorn.error.davi.requests") == []


def test_unmatched_path_is_logged_without_raw_path_or_client_address(caplog):
    private_path = "/private-user-value"

    with caplog.at_level(logging.INFO, logger="uvicorn.error.davi.requests"):
        response = client.get(private_path)

    assert response.status_code == 404
    event = _events(caplog, "uvicorn.error.davi.requests")[-1]
    serialized = json.dumps(event)
    assert event["route"] == "unmatched"
    assert event["error_kind"] == "NOT_FOUND"
    assert private_path not in serialized
    assert "127.0.0.1" not in serialized


def test_unhandled_exception_returns_request_id_and_safe_internal_log(caplog, monkeypatch):
    def explode():
        raise RuntimeError("private exception message")

    monkeypatch.setattr(main_module, "get_llm_client", explode)
    trace = _trace_headers()

    with caplog.at_level(logging.INFO, logger="uvicorn.error.davi.requests"):
        response = client.post("/v1/next-step", json=_payload(), headers=trace)

    assert response.status_code == 500
    assert response.headers[REQUEST_ID_HEADER]
    assert response.headers[LOGICAL_REQUEST_ID_HEADER] == trace[LOGICAL_REQUEST_ID_HEADER]
    event = _events(caplog, "uvicorn.error.davi.requests")[-1]
    assert event["error_kind"] == "INTERNAL"
    assert event["exception_class"] == "RuntimeError"
    assert "private exception message" not in json.dumps(event)


def test_llm_attempt_events_keep_request_context_without_prompt_or_response(caplog):
    private_goal = "private-goal-canary"
    private_label = "private-label-canary"
    request = NextStepRequest(
        goal=private_goal,
        screen=Screen(elements=[UiElement(node_id="n1", label=private_label, clickable=True)]),
    )
    response_body = _gemini_response()
    llm = gemini_llm_client(
        "private-key-canary",
        "gemini-test",
        httpx.Client(
            transport=httpx.MockTransport(lambda _: httpx.Response(200, json=response_body))
        ),
    )
    context = TraceContext("server-request", str(uuid.uuid4()), 3)
    token = set_trace_context(context)
    try:
        with caplog.at_level(logging.INFO, logger="uvicorn.error.davi.llm"):
            llm.reason(request)
    finally:
        reset_trace_context(token)

    events = _events(caplog, "uvicorn.error.davi.llm")
    assert [event["event"] for event in events] == [
        "llm_attempt_started",
        "llm_attempt_finished",
    ]
    assert all(event["request_id"] == "server-request" for event in events)
    assert all(event["logical_request_id"] == context.logical_request_id for event in events)
    assert all(event["attempt"] == 3 for event in events)
    serialized = json.dumps(events, ensure_ascii=False)
    for private_value in (
        private_goal,
        private_label,
        "private-response-canary",
        "private-key-canary",
    ):
        assert private_value not in serialized


def test_llm_timeout_logs_one_safe_failed_attempt(caplog):
    def timeout(request: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("private-timeout-canary", request=request)

    llm = gemini_llm_client(
        "private-key-canary",
        "gemini-test",
        httpx.Client(transport=httpx.MockTransport(timeout)),
    )
    with (
        caplog.at_level(logging.INFO, logger="uvicorn.error.davi.llm"),
        pytest.raises(LlmError),
    ):
        llm.reason(NextStepRequest(goal="private-goal-canary", screen=Screen()))

    events = _events(caplog, "uvicorn.error.davi.llm")
    assert [event["event"] for event in events] == [
        "llm_attempt_started",
        "llm_attempt_finished",
    ]
    assert events[-1]["outcome"] == "error"
    assert events[-1]["error_kind"] == "NETWORK"
    assert "private-timeout-canary" not in json.dumps(events)
    assert "private-key-canary" not in json.dumps(events)


def test_llm_schema_fallback_logs_each_provider_attempt(caplog):
    responses = iter(
        [
            httpx.Response(400, json={"private-error": "canary"}),
            httpx.Response(200, json=_gemini_response()),
        ]
    )
    llm = gemini_llm_client(
        "private-key-canary",
        "gemini-test",
        httpx.Client(transport=httpx.MockTransport(lambda _: next(responses))),
    )

    with caplog.at_level(logging.INFO, logger="uvicorn.error.davi.llm"):
        llm.reason(NextStepRequest(goal="private-goal-canary", screen=Screen()))

    events = _events(caplog, "uvicorn.error.davi.llm")
    assert [event["event"] for event in events] == [
        "llm_attempt_started",
        "llm_attempt_finished",
        "llm_attempt_started",
        "llm_attempt_finished",
    ]
    assert [event["provider_attempt"] for event in events] == [1, 1, 2, 2]
    assert [event["schema_mode"] for event in events] == [
        "schema",
        "schema",
        "fallback",
        "fallback",
    ]
    assert [event["trigger"] for event in events] == [
        "initial",
        "initial",
        "schema_recovery",
        "schema_recovery",
    ]
    assert "private-error" not in json.dumps(events)


def test_llm_direction_recovery_logs_retry_in_order(caplog):
    responses = iter(
        [
            httpx.Response(200, json=_gemini_response("scroll")),
            httpx.Response(200, json=_gemini_response("scroll", "up")),
        ]
    )
    llm = gemini_llm_client(
        "private-key-canary",
        "gemini-test",
        httpx.Client(transport=httpx.MockTransport(lambda _: next(responses))),
    )

    with caplog.at_level(logging.INFO, logger="uvicorn.error.davi.llm"):
        llm.reason(NextStepRequest(goal="private-goal-canary", screen=Screen()))

    events = _events(caplog, "uvicorn.error.davi.llm")
    assert [event["event"] for event in events] == [
        "llm_attempt_started",
        "llm_attempt_finished",
        "llm_parse_recovery",
        "llm_attempt_started",
        "llm_attempt_finished",
    ]
    assert events[3]["provider_attempt"] == 2
    assert events[3]["trigger"] == "direction_recovery"


def test_llm_parse_failure_follows_successful_provider_attempt(caplog):
    llm = gemini_llm_client(
        "private-key-canary",
        "gemini-test",
        httpx.Client(
            transport=httpx.MockTransport(
                lambda _: httpx.Response(
                    200,
                    json={"candidates": [{"content": {"parts": [{"text": "not-json"}]}}]},
                )
            )
        ),
    )

    with (
        caplog.at_level(logging.INFO, logger="uvicorn.error.davi.llm"),
        pytest.raises(LlmError),
    ):
        llm.reason(NextStepRequest(goal="private-goal-canary", screen=Screen()))

    events = _events(caplog, "uvicorn.error.davi.llm")
    assert [event["event"] for event in events] == [
        "llm_attempt_started",
        "llm_attempt_finished",
        "llm_parse_error",
    ]
    assert events[1]["outcome"] == "success"
    assert events[2]["reason"] == "invalid_json"


def test_malformed_http_formatter_never_includes_request_bytes():
    record = logging.LogRecord(
        "uvicorn.error",
        logging.WARNING,
        __file__,
        1,
        "Invalid HTTP request received.",
        (),
        None,
    )

    payload = json.loads(SafeJsonFormatter().format(record))

    assert payload["event"] == "malformed_http"
    assert set(payload) == {"timestamp", "level", "event"}


def test_uvicorn_access_logger_is_disabled_in_json_config():
    config = json.loads(LOGGING_CONFIG.read_text())

    access = config["loggers"]["uvicorn.access"]
    assert access["handlers"] == []
    assert access["propagate"] is False


def test_android_and_server_header_names_do_not_drift():
    android = ANDROID_TRACING.read_text()

    for header in (LOGICAL_REQUEST_ID_HEADER, ATTEMPT_HEADER, REQUEST_ID_HEADER):
        assert f'"{header}"' in android
