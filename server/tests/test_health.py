import json
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


def _request_events(caplog) -> list[dict]:
    return [
        json.loads(record.getMessage())
        for record in caplog.records
        if record.name == "uvicorn.error.davi.requests"
    ]


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
        "provider": "gemini",
        "model": "gemini-3.5-flash-lite",
        "stub_llm": True,
        "privacy_masking_disabled": False,
        "kakao_auth_configured": False,
    }


def test_development_health_reports_the_masking_bypass(set_runtime_profile):
    """실기기 E2E 실행기가 이 값을 읽어 상세 보고서 생성을 끈다(#203)."""
    set_runtime_profile(davi_disable_privacy_masking=True)

    r = client.get("/health")

    assert r.status_code == 200
    assert r.json()["privacy_masking_disabled"] is True


def test_development_health_reports_kakao_auth_wiring(set_runtime_profile):
    """Azure 배포 검증이 카카오 배선을 확인하는 유일한 경로다(#279).

    `/v1/auth/kakao`는 개발 토큰 게이트 뒤에 있어 배포 워크플로가 찌를 수 없다.
    """

    app_id = 987654
    secret = "kakao-session-signing-secret-value-1234"
    set_runtime_profile(davi_kakao_app_id=app_id, davi_auth_jwt_secret=secret)

    r = client.get("/health")

    assert r.status_code == 200
    assert r.json()["kakao_auth_configured"] is True
    # 불리언만 알린다. 앱 ID와 서명 비밀은 응답 어디에도 없다.
    assert str(app_id) not in r.text
    assert secret not in r.text


def test_development_health_reports_half_wired_kakao_auth_as_unconfigured(set_runtime_profile):
    """한쪽만 채운 배포는 로그인이 503으로 닫힌 상태다 — 검증이 통과하면 안 된다."""

    set_runtime_profile(davi_kakao_app_id=987654, davi_auth_jwt_secret="")

    assert client.get("/health").json()["kakao_auth_configured"] is False

    set_runtime_profile(
        davi_kakao_app_id=0,
        davi_auth_jwt_secret="kakao-session-signing-secret-value-1234",
    )

    assert client.get("/health").json()["kakao_auth_configured"] is False


def test_production_health_only_includes_status(set_runtime_profile):
    set_runtime_profile(
        davi_env="production",
        davi_use_stub_llm=False,
        gemini_api_key="server-side-key",
    )

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
    assert re.fullmatch(r"[0-9a-f]{32}", r.headers["X-Request-ID"])


def test_request_id_header_does_not_implicitly_open_a_browser_cors_contract():
    r = client.post(
        "/v1/next-step",
        json=_next_step_payload(),
        headers={"Origin": "https://browser-client.example"},
    )

    # 네이티브·직접 HTTP 클라이언트는 헤더를 읽을 수 있지만, 브라우저 origin은 아직 허용하지 않는다.
    assert re.fullmatch(r"[0-9a-f]{32}", r.headers["X-Request-ID"])
    assert "Access-Control-Allow-Origin" not in r.headers
    assert "Access-Control-Expose-Headers" not in r.headers


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
    assert missing.headers["X-Request-ID"] == missing.json()["request_id"]
    assert wrong.status_code == 401
    assert wrong.json()["kind"] == "AUTH"
    assert wrong.headers["X-Request-ID"] == wrong.json()["request_id"]


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
    assert limited.headers["X-Request-ID"] == limited.json()["request_id"]
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
    assert r.headers["X-Request-ID"] == body["request_id"]
    assert any(
        event["event"] == "request_finished" and event["request_id"] == body["request_id"]
        for event in _request_events(caplog)
    )


def test_production_next_step_logs_only_safe_metadata(
    caplog,
    monkeypatch,
    set_runtime_profile,
):
    set_runtime_profile(
        davi_env="production",
        davi_use_stub_llm=False,
        gemini_api_key="server-side-key",
    )
    monkeypatch.setattr(main_module, "get_llm_client", StubLlmClient)

    secret_goal = "민감한 목표 원문"
    secret_label = "개인정보 hong@example.com"
    secret_instruction = "최근 민감 안내 원문"
    secret_screen_hint = "민감한 화면 요약 원문"
    secret_token = "development-access-secret"
    payload = {
        "goal": secret_goal,
        "client_version": "0.1.0",
        "steps": [
            {
                "instruction": secret_instruction,
                "action_type": "tap",
                "target_label": secret_label,
                "screen_hint": secret_screen_hint,
                "structure_signature": "a" * 64,
            }
        ],
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
    events = _request_events(caplog)
    assert [event["event"] for event in events] == ["request_received", "request_finished"]
    finished = events[-1]
    assert finished["package_name"] == "com.sec.android.app.launcher"
    assert finished["element_count"] == 2
    assert finished["client_version"] == "0.1.0"
    assert finished["status_code"] == 200
    assert finished["duration_ms"] >= 0
    request_id = r.headers["X-Request-ID"]
    assert re.fullmatch(r"[0-9a-f]{32}", request_id)
    assert all(event["request_id"] == request_id for event in events)
    messages = [json.dumps(event, ensure_ascii=False) for event in events]
    assert all(secret_goal not in message for message in messages)
    assert all(secret_label not in message for message in messages)
    assert all(secret_instruction not in message for message in messages)
    assert all(secret_screen_hint not in message for message in messages)
    assert all(secret_token not in message for message in messages)
    assert all("시계" not in message for message in messages)


def test_test_access_token_is_accepted_alongside_the_dev_token(monkeypatch):
    """테스터에게 나눠 준 토큰도 같은 헤더로 통과한다. 앱은 값만 다르게 실으면 된다."""

    dev_token = "d" * 32
    test_token = "t" * 32
    monkeypatch.setattr(settings, "davi_dev_access_token", SecretStr(dev_token))
    monkeypatch.setattr(settings, "davi_test_access_token", SecretStr(test_token))

    with_dev = client.post(
        "/v1/next-step",
        json=_next_step_payload(),
        headers={"X-Davi-Dev-Token": dev_token},
    )
    with_test = client.post(
        "/v1/next-step",
        json=_next_step_payload(),
        headers={"X-Davi-Dev-Token": test_token},
    )
    with_wrong = client.post(
        "/v1/next-step",
        json=_next_step_payload(),
        headers={"X-Davi-Dev-Token": "w" * 32},
    )

    assert with_dev.status_code == 200
    assert with_test.status_code == 200
    assert with_wrong.status_code == 401


def test_test_access_token_alone_opens_the_gate(monkeypatch):
    """테스트 기간에 개발 토큰을 비우고 테스트 토큰만 두는 운용도 막지 않는다."""

    test_token = "t" * 32
    monkeypatch.setattr(settings, "davi_dev_access_token", SecretStr(""))
    monkeypatch.setattr(settings, "davi_test_access_token", SecretStr(test_token))

    allowed = client.post(
        "/v1/next-step",
        json=_next_step_payload(),
        headers={"X-Davi-Dev-Token": test_token},
    )
    denied = client.post("/v1/next-step", json=_next_step_payload())

    assert allowed.status_code == 200
    # 토큰이 하나라도 설정되면 loopback 예외는 닫힌다 — TestClient도 토큰을 내야 한다.
    assert denied.status_code == 401


def test_matched_token_kind_is_logged_without_the_token_value(monkeypatch, caplog):
    """테스트 기간에 참가자 트래픽과 팀 트래픽을 로그에서 갈라 보기 위한 필드다."""

    test_token = "t" * 32
    monkeypatch.setattr(settings, "davi_test_access_token", SecretStr(test_token))

    with caplog.at_level(logging.INFO, logger="uvicorn.error.davi.requests"):
        client.post(
            "/v1/next-step",
            json=_next_step_payload(),
            headers={"X-Davi-Dev-Token": test_token},
        )

    events = _request_events(caplog)
    finished = next(event for event in events if event["event"] == "request_finished")
    assert finished["access_token_kind"] == "test"
    assert test_token not in json.dumps(events)
