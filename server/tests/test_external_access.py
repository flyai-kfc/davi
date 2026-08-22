"""beta·production 추론 endpoint의 세션 인증·요청 제한 계약(#214).

``_development_access_error``는 development에서만 동작한다. 그 밖의 프로필에서 실제
원격 요청을 막는 유일한 경계가 ``_external_access_error``이므로, 이 파일은 그 경계만 검증한다.
추론 로직 자체(성공 응답의 내용)는 다른 파일이 이미 덮는다.
"""

import app.main as main_module
import pytest
from app.config import settings
from app.kakao_auth import AuthError, issue_session_token
from app.llm import StubLlmClient
from app.main import app
from app.schemas import ErrorKind
from fastapi.testclient import TestClient
from pydantic import SecretStr

# 세션 게이트는 클라이언트 주소가 아니라 Authorization 헤더로 판정하므로 loopback 여부는
# 무관하다. dev 게이트와 섞이지 않도록 dev 전용 client 튜플은 쓰지 않는다.
client = TestClient(app)

_AUTH_SECRET = "s" * 32
_APP_ID = 777
_USER_ID = 1234


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


def _beta_profile(set_runtime_profile, **overrides):
    return set_runtime_profile(
        davi_env="beta",
        davi_use_stub_llm=False,
        gemini_api_key="server-side-key",
        davi_bind_host="0.0.0.0",
        **overrides,
    )


def _configure_auth(monkeypatch) -> None:
    monkeypatch.setattr(settings, "davi_auth_jwt_secret", SecretStr(_AUTH_SECRET))
    monkeypatch.setattr(settings, "davi_kakao_app_id", _APP_ID)


def _session_header(user_id: int = _USER_ID) -> dict[str, str]:
    token = issue_session_token(user_id).session_token
    return {"Authorization": f"Bearer {token}"}


def test_development_next_step_does_not_require_session_token():
    """development는 여전히 dev 게이트(로컬/공용 dev 토큰)만 적용받는다 — 회귀 방지."""

    dev_client = TestClient(app, client=("127.0.0.1", 50000))

    r = dev_client.post("/v1/next-step", json=_next_step_payload())

    assert r.status_code == 200


def test_development_access_denial_is_not_a_session_challenge(set_runtime_profile):
    set_runtime_profile(davi_dev_access_token="d" * 32)

    r = client.post("/v1/next-step", json=_next_step_payload())

    assert r.status_code == 401
    assert "WWW-Authenticate" not in r.headers


def test_development_keeps_local_api_documentation():
    assert client.get("/docs").status_code == 200


@pytest.mark.parametrize("path", ["/docs", "/redoc", "/openapi.json"])
def test_beta_does_not_expose_api_documentation(path, set_runtime_profile):
    _beta_profile(set_runtime_profile)

    response = client.get(path)

    assert response.status_code == 404


def test_beta_next_step_without_authorization_header_is_rejected(set_runtime_profile):
    _beta_profile(set_runtime_profile)

    r = client.post("/v1/next-step", json=_next_step_payload())

    assert r.status_code == 401
    assert r.json()["kind"] == "AUTH"
    assert r.headers["WWW-Authenticate"] == "Bearer"


def test_beta_next_step_with_malformed_authorization_is_rejected(set_runtime_profile):
    _beta_profile(set_runtime_profile)

    r = client.post(
        "/v1/next-step",
        json=_next_step_payload(),
        headers={"Authorization": "garbage"},
    )

    assert r.status_code == 401
    assert r.json()["kind"] == "AUTH"


def test_beta_next_step_with_invalid_session_token_is_rejected(set_runtime_profile, monkeypatch):
    _beta_profile(set_runtime_profile)
    _configure_auth(monkeypatch)

    r = client.post(
        "/v1/next-step",
        json=_next_step_payload(),
        headers={"Authorization": "Bearer not-a-real-jwt"},
    )

    assert r.status_code == 401
    assert r.json()["kind"] == "AUTH"


def test_beta_next_step_with_valid_session_token_succeeds(set_runtime_profile, monkeypatch):
    _beta_profile(set_runtime_profile)
    _configure_auth(monkeypatch)
    monkeypatch.setattr(main_module, "get_llm_client", StubLlmClient)

    r = client.post(
        "/v1/next-step",
        json=_next_step_payload(),
        headers=_session_header(),
    )

    assert r.status_code == 200


def test_beta_slot_fill_and_app_resolution_also_require_session(set_runtime_profile):
    _beta_profile(set_runtime_profile)

    slot_fill = client.post(
        "/v1/intent/slot-fill",
        json={
            "utterance": "카메라 앱 열어줘",
            "session_id": "11111111-1111-1111-1111-111111111111",
        },
    )
    app_resolution = client.post("/v1/intent/app-resolution", json={"goal": "카메라 앱 열기"})

    assert slot_fill.status_code == 401
    assert app_resolution.status_code == 401


def test_beta_login_path_does_not_require_session_token(set_runtime_profile, monkeypatch):
    """로그인은 세션 토큰을 아직 못 받은 상태로 호출된다 — 이 게이트 대상이 아니다."""

    _beta_profile(set_runtime_profile)
    _configure_auth(monkeypatch)
    monkeypatch.setattr(
        "app.kakao_auth.fetch_access_token_info",
        lambda *a, **k: {"id": _USER_ID, "app_id": _APP_ID},
    )

    r = client.post("/v1/auth/kakao", json={"kakao_access_token": "token"})

    assert r.status_code == 200


def test_external_burst_limit_blocks_same_user_then_recovers_for_another(
    set_runtime_profile, monkeypatch
):
    _beta_profile(
        set_runtime_profile,
        davi_external_rate_limit_per_source_requests=1,
        davi_external_rate_limit_requests=100,
    )
    _configure_auth(monkeypatch)
    monkeypatch.setattr(main_module, "get_llm_client", StubLlmClient)

    first = client.post(
        "/v1/next-step", json=_next_step_payload(), headers=_session_header(user_id=1)
    )
    limited = client.post(
        "/v1/next-step", json=_next_step_payload(), headers=_session_header(user_id=1)
    )
    other_user = client.post(
        "/v1/next-step", json=_next_step_payload(), headers=_session_header(user_id=2)
    )

    assert first.status_code == 200
    assert limited.status_code == 429
    assert limited.json()["kind"] == "QUOTA"
    assert limited.json()["rate_limit_scope"] == "burst"
    assert limited.json()["retry_after_seconds"] >= 1
    assert limited.headers["Retry-After"] == str(limited.json()["retry_after_seconds"])
    assert other_user.status_code == 200


def test_external_global_burst_limit_blocks_across_users(set_runtime_profile, monkeypatch):
    _beta_profile(
        set_runtime_profile,
        davi_external_rate_limit_per_source_requests=100,
        davi_external_rate_limit_requests=2,
    )
    _configure_auth(monkeypatch)
    monkeypatch.setattr(main_module, "get_llm_client", StubLlmClient)

    first = client.post(
        "/v1/next-step", json=_next_step_payload(), headers=_session_header(user_id=1)
    )
    second = client.post(
        "/v1/next-step", json=_next_step_payload(), headers=_session_header(user_id=2)
    )
    limited = client.post(
        "/v1/next-step", json=_next_step_payload(), headers=_session_header(user_id=3)
    )

    assert first.status_code == 200
    assert second.status_code == 200
    assert limited.status_code == 429


def test_external_daily_limit_blocks_within_the_same_burst_window(set_runtime_profile, monkeypatch):
    """짧은 창 예산이 남아 있어도 하루 상한을 넘으면 막힌다."""

    _beta_profile(
        set_runtime_profile,
        davi_external_rate_limit_per_source_requests=100,
        davi_external_rate_limit_requests=100,
        davi_external_daily_limit_per_source_requests=1,
        davi_external_daily_limit_requests=100,
    )
    _configure_auth(monkeypatch)
    monkeypatch.setattr(main_module, "get_llm_client", StubLlmClient)

    first = client.post(
        "/v1/next-step", json=_next_step_payload(), headers=_session_header(user_id=1)
    )
    limited = client.post(
        "/v1/next-step", json=_next_step_payload(), headers=_session_header(user_id=1)
    )

    assert first.status_code == 200
    assert limited.status_code == 429
    assert limited.json()["kind"] == "QUOTA"
    assert limited.json()["rate_limit_scope"] == "daily"


def test_external_auth_preserves_server_failure_without_bearer_challenge(
    set_runtime_profile, monkeypatch
):
    """인증 설정 장애는 세션 무효가 아니므로 앱에 재로그인을 요구하지 않는다."""

    _beta_profile(set_runtime_profile)
    _configure_auth(monkeypatch)
    monkeypatch.setattr(
        main_module,
        "verify_session_token",
        lambda _token: (_ for _ in ()).throw(
            AuthError(ErrorKind.SERVER, "auth configuration unavailable", 503)
        ),
    )

    response = client.post(
        "/v1/next-step",
        json=_next_step_payload(),
        headers={"Authorization": "Bearer syntactically-present"},
    )

    assert response.status_code == 503
    assert response.json()["kind"] == "SERVER"
    assert "WWW-Authenticate" not in response.headers
