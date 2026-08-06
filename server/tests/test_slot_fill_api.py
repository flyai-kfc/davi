"""POST /v1/intent/slot-fill의 HTTP·관측 계약."""

import json
import logging
import uuid

import app.main as main_module
from app.config import settings
from app.errors import LlmError
from app.main import app
from app.observability import ATTEMPT_HEADER, LOGICAL_REQUEST_ID_HEADER, REQUEST_ID_HEADER
from app.schemas import ErrorKind
from fastapi.testclient import TestClient
from pydantic import SecretStr

client = TestClient(app, client=("127.0.0.1", 50000))
_PATH = "/v1/intent/slot-fill"


def _payload(utterance: str = "카메라 앱 열어줘", **overrides) -> dict:
    return {
        "utterance": utterance,
        "session_id": str(uuid.uuid4()),
    } | overrides


def _trace_headers(attempt: int = 1) -> dict[str, str]:
    return {
        LOGICAL_REQUEST_ID_HEADER: str(uuid.uuid4()),
        ATTEMPT_HEADER: str(attempt),
    }


def _request_events(caplog) -> list[dict]:
    return [
        json.loads(record.getMessage())
        for record in caplog.records
        if record.name == "uvicorn.error.davi.requests"
    ]


def test_keyless_stub_returns_additive_dialogue_contract():
    response = client.post(_PATH, json=_payload())

    assert response.status_code == 200
    assert set(response.json()) == {"status", "message", "goal"}
    assert response.json()["status"] == "ready"
    assert response.json()["goal"] == "카메라 앱 열어줘"
    assert response.headers[REQUEST_ID_HEADER]


def test_completed_history_pairs_are_accepted():
    response = client.post(
        _PATH,
        json=_payload(
            "보호자",
            conversation_history=[
                {"role": "user", "content": "사진을 보내고 싶어"},
                {"role": "assistant", "content": "누구에게 보낼까요?"},
            ],
        ),
    )

    assert response.status_code == 200


def test_invalid_voice_contract_returns_safe_bad_request():
    invalid_payloads = [
        {"session_id": str(uuid.uuid4())},
        _payload("   "),
        _payload(session_id="not-a-uuid"),
        _payload(conversation_history=[{"role": "user", "content": "미완성"}]),
        _payload(
            conversation_history=[
                {"role": "assistant", "content": "순서 오류"},
                {"role": "user", "content": "답변"},
            ]
        ),
        _payload(conversation_history=[{"role": "user", "content": "x"}] * 11),
    ]

    for payload in invalid_payloads:
        response = client.post(_PATH, json=payload)
        assert response.status_code == 422
        assert response.json()["kind"] == "BAD_REQUEST"
        assert response.headers[REQUEST_ID_HEADER] == response.json()["request_id"]
        assert "input" not in response.json()


def test_logical_request_headers_are_echoed_on_success_and_validation_error():
    headers = _trace_headers(attempt=2)

    success = client.post(_PATH, json=_payload(), headers=headers)
    invalid = client.post(_PATH, json={"session_id": str(uuid.uuid4())}, headers=headers)

    assert success.status_code == 200
    assert invalid.status_code == 422
    for response in [success, invalid]:
        assert response.headers[LOGICAL_REQUEST_ID_HEADER] == headers[LOGICAL_REQUEST_ID_HEADER]
        assert response.headers[ATTEMPT_HEADER] == "2"


def test_partial_or_invalid_trace_headers_are_rejected_without_echo():
    cases = [
        {LOGICAL_REQUEST_ID_HEADER: str(uuid.uuid4())},
        {ATTEMPT_HEADER: "1"},
        {LOGICAL_REQUEST_ID_HEADER: "not-a-uuid", ATTEMPT_HEADER: "1"},
    ]

    for headers in cases:
        response = client.post(_PATH, json=_payload(), headers=headers)
        assert response.status_code == 400
        assert response.json()["kind"] == "BAD_REQUEST"
        assert LOGICAL_REQUEST_ID_HEADER not in response.headers
        assert ATTEMPT_HEADER not in response.headers


def test_development_access_controls_are_shared(monkeypatch):
    token = "t" * 32
    monkeypatch.setattr(settings, "davi_dev_access_token", SecretStr(token))

    denied = client.post(_PATH, json=_payload())
    allowed = client.post(
        _PATH,
        json=_payload(),
        headers={"X-Davi-Dev-Token": token},
    )

    assert denied.status_code == 401
    assert denied.json()["kind"] == "AUTH"
    assert allowed.status_code == 200


def test_llm_error_uses_existing_api_error_contract(monkeypatch):
    def fail(_):
        raise LlmError(ErrorKind.QUOTA, "private-provider-message", 19)

    monkeypatch.setattr(main_module, "resolve_intent", fail)

    response = client.post(_PATH, json=_payload(), headers=_trace_headers())

    assert response.status_code == 429
    assert response.json()["kind"] == "QUOTA"
    assert response.json()["retry_after_seconds"] == 19
    assert response.headers["Retry-After"] == "19"
    assert response.headers[REQUEST_ID_HEADER] == response.json()["request_id"]


def test_operational_logs_exclude_voice_session_history_and_response(caplog):
    sensitive_values = [
        "private-utterance-canary",
        str(uuid.uuid4()),
        "private-history-canary",
        "private-question-canary",
    ]
    payload = _payload(
        sensitive_values[0],
        session_id=sensitive_values[1],
        conversation_history=[
            {"role": "user", "content": sensitive_values[2]},
            {"role": "assistant", "content": sensitive_values[3]},
        ],
    )

    with caplog.at_level(logging.INFO, logger="uvicorn.error.davi.requests"):
        response = client.post(_PATH, json=payload, headers=_trace_headers())

    assert response.status_code == 200
    events = _request_events(caplog)
    assert [event["event"] for event in events] == ["request_received", "request_finished"]
    assert events[-1]["conversation_message_count"] == 2
    serialized = json.dumps(events, ensure_ascii=False)
    assert all(value not in serialized for value in sensitive_values)
    assert all("goal" not in event and "message" not in event for event in events)


def test_slot_fill_is_never_written_to_dogfood_raw_store(monkeypatch):
    def forbidden(*_args, **_kwargs):
        raise AssertionError("voice dialogue must not enter the raw screen fixture channel")

    monkeypatch.setattr(main_module, "store_raw_exchange", forbidden)

    response = client.post(_PATH, json=_payload())

    assert response.status_code == 200
