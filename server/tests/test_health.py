import logging
import re

import app.main as main_module
from app.config import settings
from app.llm import StubLlmClient
from app.main import app
from fastapi.testclient import TestClient
from pydantic import SecretStr

# 토큰 없는 development 기본값은 loopback 전용이다. TestClient의 기본 ``testclient`` host는
# 실제 IP가 아니므로, 평소 테스트는 운영의 로컬 개발 요청과 같은 loopback으로 명시한다.
client = TestClient(app, client=("127.0.0.1", 50000))


def _next_step_payload() -> dict:
    return {
        "goal": "카카오톡 열기",
        "client_version": "0.1.0",
        "screen": {
            "package_name": "com.sec.android.app.launcher",
            "elements": [
                {"node_id": "n1", "label": "카카오톡", "clickable": True},
            ],
        },
    }


def test_development_health_includes_diagnostics():
    r = client.get("/health")

    assert r.status_code == 200
    assert r.json() == {
        "status": "ok",
        "environment": "development",
        "model": "gemini-3.5-flash-lite",
        "stub_llm": True,
    }


def test_production_health_only_includes_status(monkeypatch):
    monkeypatch.setattr(settings, "davi_env", "production")
    monkeypatch.setattr(settings, "davi_use_stub_llm", False)
    monkeypatch.setattr(settings, "gemini_api_key", SecretStr("server-side-key"))

    r = client.get("/health")

    assert r.status_code == 200
    assert r.json() == {"status": "ok"}


def test_next_step_stub_returns_action():
    payload = _next_step_payload()
    payload["screen"]["current_page"] = 2
    payload["screen"]["total_pages"] = 4
    payload["screen"]["elements"].append(
        {"node_id": "n2", "label": "시계", "clickable": False},
    )
    r = client.post("/v1/next-step", json=payload)
    assert r.status_code == 200
    body = r.json()
    assert body["action"]["action_type"] == "tap"
    assert body["action"]["target_node_id"] == "n1"
    # 근거 필드가 비어 있지 않아야 한다(HANDOFF: 성의 없는/빈 근거 방지의 출발점).
    assert body["action"]["evidence"]
    assert body["action"]["expected_result"]
    # scroll이 아니면 방향은 생략된다.
    assert body["action"]["direction"] is None


def test_development_token_is_required_when_configured(monkeypatch):
    token = "t" * 32
    monkeypatch.setattr(settings, "davi_dev_access_token", SecretStr(token))

    missing = client.post("/v1/next-step", json=_next_step_payload())
    wrong = client.post(
        "/v1/next-step",
        json=_next_step_payload(),
        headers={"X-Davi-Dev-Token": "w" * 32},
    )

    assert missing.status_code == 401
    assert missing.json()["kind"] == "AUTH"
    assert missing.json()["request_id"]
    assert wrong.status_code == 401
    assert wrong.json()["kind"] == "AUTH"


def test_remote_client_is_denied_when_token_is_not_configured(monkeypatch):
    """실제 bind가 선언과 달라도 원격 요청은 토큰 없이 통과하지 못한다."""
    monkeypatch.setattr(settings, "davi_bind_host", "127.0.0.1")
    monkeypatch.setattr(settings, "davi_dev_access_token", SecretStr(""))
    remote_client = TestClient(app, client=("192.168.1.23", 50000))

    response = remote_client.post("/v1/next-step", json=_next_step_payload())

    assert response.status_code == 401
    assert response.json()["kind"] == "AUTH"
    assert response.json()["request_id"]


def test_loopback_client_is_allowed_when_token_is_not_configured(monkeypatch):
    monkeypatch.setattr(settings, "davi_dev_access_token", SecretStr(""))

    response = client.post("/v1/next-step", json=_next_step_payload())

    assert response.status_code == 200


def test_development_token_allows_next_step(monkeypatch):
    token = "t" * 32
    monkeypatch.setattr(settings, "davi_dev_access_token", SecretStr(token))

    response = client.post(
        "/v1/next-step",
        json=_next_step_payload(),
        headers={"X-Davi-Dev-Token": token},
    )

    assert response.status_code == 200


def test_development_rate_limit_is_global_and_returns_retry_after(monkeypatch):
    token = "t" * 32
    monkeypatch.setattr(settings, "davi_dev_access_token", SecretStr(token))
    monkeypatch.setattr(settings, "davi_dev_rate_limit_requests", 2)
    headers = {"X-Davi-Dev-Token": token}

    first = client.post("/v1/next-step", json=_next_step_payload(), headers=headers)
    second = client.post("/v1/next-step", json=_next_step_payload(), headers=headers)
    limited = client.post("/v1/next-step", json=_next_step_payload(), headers=headers)

    assert first.status_code == 200
    assert second.status_code == 200
    assert limited.status_code == 429
    assert limited.json()["kind"] == "QUOTA"
    assert limited.json()["retry_after_seconds"] >= 1
    assert limited.json()["request_id"]
    assert limited.headers["Retry-After"] == str(limited.json()["retry_after_seconds"])


def test_development_access_token_is_not_logged(caplog, monkeypatch):
    token = "private-development-token-value-123"
    monkeypatch.setattr(settings, "davi_dev_access_token", SecretStr(token))

    with caplog.at_level(logging.INFO, logger="uvicorn.error.davi.requests"):
        response = client.post(
            "/v1/next-step",
            json=_next_step_payload(),
            headers={"X-Davi-Dev-Token": token},
        )

    assert response.status_code == 200
    messages = [
        record.getMessage()
        for record in caplog.records
        if record.name == "uvicorn.error.davi.requests"
    ]
    assert messages
    assert all(token not in message for message in messages)


def test_next_step_stuck_returns_classified_error(caplog):
    """막히면 kind로 분류된 에러가 오고, 앱이 그걸 보고 안내를 분기한다(C8)."""
    with caplog.at_level(logging.INFO, logger="uvicorn.error.davi.requests"):
        r = client.post("/v1/next-step", json={"goal": "x", "screen": {"elements": []}})

    assert r.status_code == 502
    body = r.json()
    assert body["kind"] == "SERVER"
    assert body["request_id"]
    assert any(
        f"request_id={body['request_id']}" in record.getMessage()
        for record in caplog.records
        if record.name == "uvicorn.error.davi.requests"
    )


def test_production_next_step_logs_only_safe_metadata(caplog, monkeypatch):
    monkeypatch.setattr(settings, "davi_env", "production")
    monkeypatch.setattr(settings, "davi_use_stub_llm", False)
    monkeypatch.setattr(settings, "gemini_api_key", SecretStr("server-side-key"))
    monkeypatch.setattr(main_module, "get_llm_client", StubLlmClient)

    secret_goal = "민감한 목표 원문"
    secret_label = "개인정보 hong@example.com"
    secret_instruction = "최근 민감 안내 원문"
    secret_token = "development-access-secret"
    payload = {
        "goal": secret_goal,
        "client_version": "0.1.0",
        "recent_instructions": [secret_instruction],
        "screen": {
            "package_name": "com.sec.android.app.launcher",
            "elements": [
                {
                    "node_id": "n1",
                    "label": secret_label,
                    "clickable": True,
                },
                {"node_id": "n2", "label": "시계", "clickable": False},
            ],
        },
    }

    with caplog.at_level(logging.INFO, logger="uvicorn.error.davi.requests"):
        r = client.post(
            "/v1/next-step",
            json=payload,
            headers={
                "X-Davi-Dev-Token": secret_token,
                "Authorization": f"Bearer {secret_token}",
            },
        )

    assert r.status_code == 200
    messages = [
        record.getMessage()
        for record in caplog.records
        if record.name == "uvicorn.error.davi.requests"
    ]
    assert len(messages) == 1
    message = messages[0]
    assert "package_name=com.sec.android.app.launcher" in message
    assert "element_count=2" in message
    assert "client_version=0.1.0" in message
    assert "status_code=200" in message
    assert "duration_ms=" in message
    assert re.search(r"request_id=[0-9a-f]{32}(?:\s|$)", message)
    assert "goal=" not in message
    assert secret_goal not in message
    assert secret_label not in message
    assert secret_instruction not in message
    assert secret_token not in message
    assert "시계" not in message
