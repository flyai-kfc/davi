"""`POST /v1/intent/slot-fill`의 HTTP·관측 계약."""

import json
import logging
import uuid

from app.config import settings
from app.main import app
from app.observability import ATTEMPT_HEADER, LOGICAL_REQUEST_ID_HEADER, REQUEST_ID_HEADER
from fastapi.testclient import TestClient
from pydantic import SecretStr

client = TestClient(app, client=("127.0.0.1", 50000))
_PATH = "/v1/intent/slot-fill"


def _payload(utterance: str = "보호자님께 사진 보내줘", **overrides) -> dict:
    return {"utterance": utterance, "session_id": "session-test"} | overrides


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


def test_returns_slot_fill_contract_fields():
    response = client.post(_PATH, json=_payload())

    assert response.status_code == 200
    assert set(response.json()) == {
        "reasoning",
        "action_type",
        "slots",
        "missing_slots",
        "confirmation_question",
        "goal",
    }
    assert response.headers[REQUEST_ID_HEADER]


def test_optional_conversation_history_is_accepted():
    history = [
        {"role": "user", "content": "누구한테 사진 보내줘"},
        {"role": "assistant", "content": "누구에게 보내드릴까요?"},
    ]

    response = client.post(_PATH, json=_payload("보호자", conversation_history=history))

    assert response.status_code == 200
    assert response.json()["slots"]["target_person"] == "보호자"


def test_required_fields_and_input_limits_return_422():
    invalid_payloads = [
        {"session_id": "session-test"},
        {"utterance": "사진 보내줘"},
        _payload("가" * 501),
        _payload(session_id="s" * 129),
        _payload(conversation_history=[{"role": "user", "content": "x"}] * 11),
        _payload(conversation_history=[{"role": "system", "content": "x"}]),
        _payload(conversation_history=[{"role": "user", "content": "x" * 501}]),
    ]

    for payload in invalid_payloads:
        response = client.post(_PATH, json=payload)
        assert response.status_code == 422
        assert response.json()["kind"] == "BAD_REQUEST"
        assert response.headers[REQUEST_ID_HEADER] == response.json()["request_id"]


def test_goal_is_returned_only_when_complete():
    history: list[dict[str, str]] = []
    utterance = "보호자님께 보내줘"
    complete = None

    for answer in ["보호자", "메신저", "사진", "전송"]:
        response = client.post(
            _PATH,
            json=_payload(utterance, conversation_history=history),
        )
        assert response.status_code == 200
        state = response.json()
        if state["missing_slots"]:
            assert state["goal"] is None
        history += [
            {"role": "user", "content": utterance},
            {"role": "assistant", "content": state["confirmation_question"]},
        ]
        utterance = answer
        complete = state

    response = client.post(_PATH, json=_payload(utterance, conversation_history=history))
    assert response.status_code == 200
    complete = response.json()
    assert complete["missing_slots"] == []
    assert complete["goal"]


def test_logical_request_headers_are_echoed_and_logged():
    headers = _trace_headers(attempt=2)

    response = client.post(_PATH, json=_payload(), headers=headers)

    assert response.status_code == 200
    assert response.headers[LOGICAL_REQUEST_ID_HEADER] == headers[LOGICAL_REQUEST_ID_HEADER]
    assert response.headers[ATTEMPT_HEADER] == "2"
    assert "logical_request_id" not in response.json()


def test_partial_or_invalid_trace_headers_return_bad_request_without_echo():
    cases = [
        {LOGICAL_REQUEST_ID_HEADER: str(uuid.uuid4())},
        {ATTEMPT_HEADER: "1"},
        {
            LOGICAL_REQUEST_ID_HEADER: "not-a-uuid",
            ATTEMPT_HEADER: "1",
        },
    ]

    for headers in cases:
        response = client.post(_PATH, json=_payload(), headers=headers)
        assert response.status_code == 400
        assert response.json()["kind"] == "BAD_REQUEST"
        assert response.headers[REQUEST_ID_HEADER] == response.json()["request_id"]
        assert LOGICAL_REQUEST_ID_HEADER not in response.headers
        assert ATTEMPT_HEADER not in response.headers


def test_development_token_guards_slot_fill(monkeypatch):
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
    assert denied.headers[REQUEST_ID_HEADER] == denied.json()["request_id"]
    assert allowed.status_code == 200


def test_operational_logs_exclude_voice_session_history_and_slots(caplog):
    sensitive_values = [
        "private-utterance-canary",
        "private-session-canary",
        "private-history-canary",
    ]
    payload = _payload(
        sensitive_values[0],
        session_id=sensitive_values[1],
        conversation_history=[
            {"role": "user", "content": sensitive_values[2]},
            {"role": "assistant", "content": "어느 앱에서 할까요?"},
        ],
    )

    with caplog.at_level(logging.INFO, logger="uvicorn.error.davi.requests"):
        response = client.post(_PATH, json=payload, headers=_trace_headers())

    assert response.status_code == 200
    events = _request_events(caplog)
    assert [event["event"] for event in events] == ["request_received", "request_finished"]
    serialized = json.dumps(events, ensure_ascii=False)
    assert all(value not in serialized for value in sensitive_values)
    assert "confirmation_question" not in serialized
    assert "slots" not in serialized


def test_validation_error_keeps_valid_trace_echo():
    headers = _trace_headers()

    response = client.post(_PATH, json={"session_id": "session-test"}, headers=headers)

    assert response.status_code == 422
    assert response.json()["kind"] == "BAD_REQUEST"
    assert response.headers[LOGICAL_REQUEST_ID_HEADER] == headers[LOGICAL_REQUEST_ID_HEADER]
    assert response.headers[ATTEMPT_HEADER] == "1"
