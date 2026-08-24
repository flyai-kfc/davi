"""카카오 로그인 계약: 토큰 검증 분기, 세션 JWT 왕복, 비밀값 로그 금지."""

import json
import logging

import httpx
import pytest
from app import kakao_auth
from app.config import settings
from app.kakao_auth import (
    AuthError,
    KakaoLoginRequest,
    fetch_access_token_info,
    issue_session_token,
    login_with_kakao,
    verify_session_token,
)
from app.main import app
from app.schemas import ErrorKind
from fastapi.testclient import TestClient
from pydantic import SecretStr

client = TestClient(app, client=("127.0.0.1", 50000))

_SECRET = "s" * 32
_APP_ID = 777


@pytest.fixture
def configured_auth(monkeypatch):
    monkeypatch.setattr(settings, "davi_auth_jwt_secret", SecretStr(_SECRET))
    monkeypatch.setattr(settings, "davi_kakao_app_id", _APP_ID)


def _kakao_transport(status_code: int, body: dict | str) -> httpx.MockTransport:
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url == kakao_auth.KAKAO_TOKEN_INFO_URL
        if isinstance(body, dict):
            return httpx.Response(status_code, json=body)
        return httpx.Response(status_code, text=body)

    return httpx.MockTransport(handler)


# --- 세션 JWT 단위 ---


def test_session_token_roundtrip(configured_auth):
    issued = issue_session_token(1234)

    assert issued.expires_in_seconds == settings.davi_auth_session_ttl_seconds
    assert verify_session_token(issued.session_token) == 1234


def test_expired_session_token_is_rejected(configured_auth):
    ttl = settings.davi_auth_session_ttl_seconds
    issued = issue_session_token(1234, clock=lambda: 0)  # epoch 발급 -> 이미 만료

    with pytest.raises(AuthError) as excinfo:
        verify_session_token(issued.session_token)

    assert ttl > 0
    assert excinfo.value.kind is ErrorKind.AUTH
    assert excinfo.value.http_status == 401


def test_tampered_session_token_is_rejected(configured_auth):
    token = issue_session_token(1234).session_token
    tampered = token[:-2] + ("aa" if not token.endswith("aa") else "bb")

    with pytest.raises(AuthError) as excinfo:
        verify_session_token(tampered)

    assert excinfo.value.kind is ErrorKind.AUTH


def test_auth_requires_configuration():
    # conftest 기본 프로필은 비밀·앱 ID가 비어 있다 -> 로그인 계열 함수는 SERVER로 닫힌다.
    with pytest.raises(AuthError) as excinfo:
        issue_session_token(1234)

    assert excinfo.value.kind is ErrorKind.SERVER
    assert excinfo.value.http_status == 503


# --- 카카오 토큰 검증 분기 ---


def test_kakao_401_maps_to_auth_error():
    with pytest.raises(AuthError) as excinfo:
        fetch_access_token_info("bad-token", transport=_kakao_transport(401, {"code": -401}))

    assert excinfo.value.kind is ErrorKind.AUTH
    assert excinfo.value.http_status == 401


def test_kakao_5xx_maps_to_server_error():
    with pytest.raises(AuthError) as excinfo:
        fetch_access_token_info("token", transport=_kakao_transport(500, {"msg": "boom"}))

    assert excinfo.value.kind is ErrorKind.SERVER


def test_kakao_network_failure_maps_to_network_error():
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("unreachable", request=request)

    with pytest.raises(AuthError) as excinfo:
        fetch_access_token_info("token", transport=httpx.MockTransport(handler))

    assert excinfo.value.kind is ErrorKind.NETWORK


def test_kakao_invalid_json_maps_to_server_error():
    with pytest.raises(AuthError) as excinfo:
        fetch_access_token_info("token", transport=_kakao_transport(200, "not-json"))

    assert excinfo.value.kind is ErrorKind.SERVER


def test_foreign_app_token_is_rejected(configured_auth):
    transport = _kakao_transport(200, {"id": 1234, "app_id": _APP_ID + 1, "expires_in": 100})

    with pytest.raises(AuthError) as excinfo:
        login_with_kakao(KakaoLoginRequest(kakao_access_token="token"), transport=transport)

    assert excinfo.value.kind is ErrorKind.AUTH
    assert excinfo.value.http_status == 401


def test_login_issues_verifiable_session_token(configured_auth):
    transport = _kakao_transport(200, {"id": 1234, "app_id": _APP_ID, "expires_in": 100})

    issued = login_with_kakao(KakaoLoginRequest(kakao_access_token="token"), transport=transport)

    assert verify_session_token(issued.session_token) == 1234


# --- HTTP endpoint ---


def _fake_token_info(payload: dict):
    def fake(kakao_access_token: str, transport=None) -> dict:
        return payload

    return fake


def test_login_endpoint_returns_session_token(configured_auth, monkeypatch):
    monkeypatch.setattr(
        kakao_auth,
        "fetch_access_token_info",
        _fake_token_info({"id": 1234, "app_id": _APP_ID}),
    )

    r = client.post("/v1/auth/kakao", json={"kakao_access_token": "kakao-token"})

    assert r.status_code == 200
    body = r.json()
    assert body["expires_in_seconds"] == settings.davi_auth_session_ttl_seconds
    assert verify_session_token(body["session_token"]) == 1234
    assert r.headers["X-Request-ID"]


def test_login_success_response_is_not_cacheable(configured_auth, monkeypatch):
    """세션 토큰이 담긴 성공 응답은 캐시 금지다(#167 재리뷰 P2)."""

    monkeypatch.setattr(
        kakao_auth,
        "fetch_access_token_info",
        _fake_token_info({"id": 1234, "app_id": _APP_ID}),
    )

    r = client.post("/v1/auth/kakao", json={"kakao_access_token": "kakao-token"})

    assert r.status_code == 200
    assert r.headers["Cache-Control"] == "no-store"


def test_login_endpoint_maps_auth_error(configured_auth, monkeypatch):
    monkeypatch.setattr(
        kakao_auth,
        "fetch_access_token_info",
        _fake_token_info({"id": 1234, "app_id": _APP_ID + 1}),
    )

    r = client.post("/v1/auth/kakao", json={"kakao_access_token": "kakao-token"})

    assert r.status_code == 401
    assert r.json()["kind"] == "AUTH"
    assert r.json()["request_id"] == r.headers["X-Request-ID"]


def test_login_endpoint_when_not_configured_returns_server_error():
    r = client.post("/v1/auth/kakao", json={"kakao_access_token": "kakao-token"})

    assert r.status_code == 503
    assert r.json()["kind"] == "SERVER"


def test_login_endpoint_validation_error_uses_api_error_shape():
    r = client.post("/v1/auth/kakao", json={})

    assert r.status_code == 422
    assert r.json()["kind"] == "BAD_REQUEST"
    assert r.json()["request_id"]


def test_login_rejects_oversized_and_blank_inputs():
    """인증 전 공개 endpoint의 입력 상한(#167 리뷰 P2). 카카오 API 호출 전에 스키마에서 자른다."""

    oversized_token = client.post("/v1/auth/kakao", json={"kakao_access_token": "t" * 513})
    blank_token = client.post("/v1/auth/kakao", json={"kakao_access_token": "   "})
    oversized_version = client.post(
        "/v1/auth/kakao",
        json={"kakao_access_token": "token", "client_version": "v" * 65},
    )

    for response in (oversized_token, blank_token, oversized_version):
        assert response.status_code == 422
        assert response.json()["kind"] == "BAD_REQUEST"


def test_login_rate_limit_applies_in_every_profile(
    configured_auth,
    monkeypatch,
    set_runtime_profile,
):
    """로그인 리미터는 dev 게이트와 달리 production에서도 돈다(#167 리뷰 P1)."""

    set_runtime_profile(
        davi_env="production",
        davi_use_stub_llm=False,
        gemini_api_key="server-side-key",
    )
    monkeypatch.setattr(settings, "davi_auth_jwt_secret", SecretStr(_SECRET))
    monkeypatch.setattr(settings, "davi_kakao_app_id", _APP_ID)
    monkeypatch.setattr(settings, "davi_auth_rate_limit_requests", 2)
    monkeypatch.setattr(
        kakao_auth,
        "fetch_access_token_info",
        _fake_token_info({"id": 1234, "app_id": _APP_ID}),
    )

    first = client.post("/v1/auth/kakao", json={"kakao_access_token": "token"})
    second = client.post("/v1/auth/kakao", json={"kakao_access_token": "token"})
    limited = client.post("/v1/auth/kakao", json={"kakao_access_token": "token"})

    assert first.status_code == 200
    assert second.status_code == 200
    assert limited.status_code == 429
    assert limited.json()["kind"] == "QUOTA"
    assert limited.json()["retry_after_seconds"] >= 1
    assert limited.headers["Retry-After"] == str(limited.json()["retry_after_seconds"])


def test_login_rate_limit_is_per_source(monkeypatch, set_runtime_profile):
    """한 출처의 소진이 다른 출처의 로그인을 막지 않는다(#167 재리뷰 P1).

    dev 게이트는 loopback 밖 클라이언트를 막으므로 production 프로필로 검증한다.
    """

    set_runtime_profile(
        davi_env="production",
        davi_use_stub_llm=False,
        gemini_api_key="server-side-key",
    )
    monkeypatch.setattr(settings, "davi_auth_jwt_secret", SecretStr(_SECRET))
    monkeypatch.setattr(settings, "davi_kakao_app_id", _APP_ID)
    monkeypatch.setattr(settings, "davi_auth_rate_limit_per_source_requests", 1)
    monkeypatch.setattr(
        kakao_auth,
        "fetch_access_token_info",
        _fake_token_info({"id": 1234, "app_id": _APP_ID}),
    )
    attacker = TestClient(app, client=("203.0.113.9", 40000))
    normal_user = TestClient(app, client=("198.51.100.7", 40001))

    ok = attacker.post("/v1/auth/kakao", json={"kakao_access_token": "token"})
    limited = attacker.post("/v1/auth/kakao", json={"kakao_access_token": "token"})
    other_source_ok = normal_user.post("/v1/auth/kakao", json={"kakao_access_token": "token"})

    assert ok.status_code == 200
    assert limited.status_code == 429
    assert limited.json()["kind"] == "QUOTA"
    assert limited.headers["Retry-After"] == str(limited.json()["retry_after_seconds"])
    assert other_source_ok.status_code == 200


def test_aca_login_rate_limit_trusts_only_rightmost_forwarded_ip(
    monkeypatch,
    set_runtime_profile,
):
    """클라이언트가 넣을 수 있는 왼쪽 값은 출처 분리에 영향을 주지 않는다."""

    set_runtime_profile(
        davi_env="production",
        davi_use_stub_llm=False,
        gemini_api_key="server-side-key",
        davi_auth_source="azure_container_apps",
        davi_auth_rate_limit_per_source_requests=1,
        davi_auth_rate_limit_requests=100,
    )
    monkeypatch.setattr(
        kakao_auth,
        "fetch_access_token_info",
        _fake_token_info({"id": 1234, "app_id": _APP_ID}),
    )

    first = client.post(
        "/v1/auth/kakao",
        json={"kakao_access_token": "token"},
        headers={"X-Forwarded-For": "192.0.2.10, 203.0.113.9"},
    )
    same_rightmost = client.post(
        "/v1/auth/kakao",
        json={"kakao_access_token": "token"},
        headers={"X-Forwarded-For": "198.51.100.88, 203.0.113.9"},
    )
    different_rightmost = client.post(
        "/v1/auth/kakao",
        json={"kakao_access_token": "token"},
        headers={"X-Forwarded-For": "192.0.2.10, 203.0.113.10"},
    )

    assert first.status_code == 200
    assert same_rightmost.status_code == 429
    assert different_rightmost.status_code == 200


@pytest.mark.parametrize("forwarded_for", [None, "not-an-ip", "203.0.113.9, "])
def test_aca_login_rejects_missing_or_invalid_rightmost_before_kakao(
    forwarded_for,
    monkeypatch,
    set_runtime_profile,
):
    set_runtime_profile(
        davi_env="production",
        davi_use_stub_llm=False,
        gemini_api_key="server-side-key",
        davi_auth_source="azure_container_apps",
    )
    called = False

    def unexpected_call(kakao_access_token: str, transport=None) -> dict:
        nonlocal called
        called = True
        return {"id": 1234, "app_id": _APP_ID}

    monkeypatch.setattr(kakao_auth, "fetch_access_token_info", unexpected_call)
    headers = {} if forwarded_for is None else {"X-Forwarded-For": forwarded_for}

    response = client.post(
        "/v1/auth/kakao",
        json={"kakao_access_token": "token"},
        headers=headers,
    )

    assert response.status_code == 503
    assert response.json()["kind"] == "SERVER"
    assert called is False


def test_development_peer_mode_ignores_forwarded_header(
    configured_auth,
    monkeypatch,
):
    monkeypatch.setattr(settings, "davi_auth_rate_limit_per_source_requests", 1)
    monkeypatch.setattr(
        kakao_auth,
        "fetch_access_token_info",
        _fake_token_info({"id": 1234, "app_id": _APP_ID}),
    )

    first = client.post(
        "/v1/auth/kakao",
        json={"kakao_access_token": "token"},
        headers={"X-Forwarded-For": "203.0.113.9"},
    )
    second = client.post(
        "/v1/auth/kakao",
        json={"kakao_access_token": "token"},
        headers={"X-Forwarded-For": "203.0.113.10"},
    )

    assert first.status_code == 200
    assert second.status_code == 429


def test_login_global_cap_still_limits_across_sources(monkeypatch, set_runtime_profile):
    """출처별 창과 별개로 전역 상한이 카카오 API 소모 총량을 지킨다."""

    set_runtime_profile(
        davi_env="production",
        davi_use_stub_llm=False,
        gemini_api_key="server-side-key",
    )
    monkeypatch.setattr(settings, "davi_auth_jwt_secret", SecretStr(_SECRET))
    monkeypatch.setattr(settings, "davi_kakao_app_id", _APP_ID)
    monkeypatch.setattr(settings, "davi_auth_rate_limit_requests", 2)
    monkeypatch.setattr(
        kakao_auth,
        "fetch_access_token_info",
        _fake_token_info({"id": 1234, "app_id": _APP_ID}),
    )
    sources = [TestClient(app, client=(f"203.0.113.{i}", 40000 + i)) for i in range(3)]

    first = sources[0].post("/v1/auth/kakao", json={"kakao_access_token": "token"})
    second = sources[1].post("/v1/auth/kakao", json={"kakao_access_token": "token"})
    limited = sources[2].post("/v1/auth/kakao", json={"kakao_access_token": "token"})

    assert first.status_code == 200
    assert second.status_code == 200
    assert limited.status_code == 429
    assert limited.json()["kind"] == "QUOTA"


def test_login_flood_does_not_consume_dev_window_in_lan_mode(monkeypatch, set_runtime_profile):
    """LAN+토큰 development에서도 로그인 폭주가 팀 추론 쿼터(dev 창)를 소모하지 않는다.

    dev 게이트의 토큰 검사는 로그인에도 적용되지만, dev 창은 Gemini 쿼터 보호용이라
    카카오만 부르는 로그인이 소모하면 안 된다. loopback·무토큰 경로만 테스트하면
    dev 리미터가 아예 돌지 않아 이 계약을 검증하지 못한다.
    """

    dev_token = "d" * 32
    set_runtime_profile(
        davi_env="development",
        davi_dev_access_token=dev_token,
        davi_bind_host="0.0.0.0",
        davi_dev_rate_limit_requests=2,
    )
    monkeypatch.setattr(settings, "davi_auth_jwt_secret", SecretStr(_SECRET))
    monkeypatch.setattr(settings, "davi_kakao_app_id", _APP_ID)
    monkeypatch.setattr(
        kakao_auth,
        "fetch_access_token_info",
        _fake_token_info({"id": 1234, "app_id": _APP_ID}),
    )
    lan_client = TestClient(app, client=("192.168.0.10", 40000))
    headers = {"X-Davi-Dev-Token": dev_token}

    # dev 창(2회)보다 많은 로그인을 보낸다. 성공 3회는 출처별 기본(10회) 안이다.
    for _ in range(3):
        login = lan_client.post(
            "/v1/auth/kakao", json={"kakao_access_token": "token"}, headers=headers
        )
        assert login.status_code == 200

    # 로그인이 dev 창을 소모하지 않았으므로 추론 요청 2회가 그대로 허용된다.
    for _ in range(2):
        next_step = lan_client.post(
            "/v1/next-step",
            json={
                "goal": "카카오톡 열기",
                "client_version": "0.1.0",
                "screen": {
                    "package_name": "com.sec.android.app.launcher",
                    "elements": [{"node_id": "n1", "label": "카카오톡", "clickable": True}],
                },
            },
            headers=headers,
        )
        assert next_step.status_code == 200

    # 토큰 검사 자체는 로그인에도 그대로다 — 무토큰 LAN 로그인은 401이다.
    no_token = lan_client.post("/v1/auth/kakao", json={"kakao_access_token": "token"})
    assert no_token.status_code == 401


def test_login_rate_limit_does_not_share_the_dev_window(configured_auth, monkeypatch):
    """로그인 폭주가 팀 추론 쿼터(dev 리미터)를 잠그지 않는다 — 창이 분리돼 있다."""

    monkeypatch.setattr(settings, "davi_auth_rate_limit_requests", 1)
    monkeypatch.setattr(
        kakao_auth,
        "fetch_access_token_info",
        _fake_token_info({"id": 1234, "app_id": _APP_ID}),
    )

    ok = client.post("/v1/auth/kakao", json={"kakao_access_token": "token"})
    limited = client.post("/v1/auth/kakao", json={"kakao_access_token": "token"})
    next_step_still_ok = client.post(
        "/v1/next-step",
        json={
            "goal": "카카오톡 열기",
            "client_version": "0.1.0",
            "screen": {
                "package_name": "com.sec.android.app.launcher",
                "elements": [{"node_id": "n1", "label": "카카오톡", "clickable": True}],
            },
        },
    )

    assert ok.status_code == 200
    assert limited.status_code == 429
    assert next_step_still_ok.status_code == 200


def test_login_endpoint_never_logs_tokens(configured_auth, monkeypatch, caplog):
    kakao_token = "kakao-secret-access-token-value"
    monkeypatch.setattr(
        kakao_auth,
        "fetch_access_token_info",
        _fake_token_info({"id": 1234, "app_id": _APP_ID}),
    )

    with caplog.at_level(logging.INFO, logger="uvicorn.error.davi.requests"):
        r = client.post("/v1/auth/kakao", json={"kakao_access_token": kakao_token})

    assert r.status_code == 200
    session_token = r.json()["session_token"]
    messages = [
        json.dumps(json.loads(record.getMessage()), ensure_ascii=False)
        for record in caplog.records
        if record.name == "uvicorn.error.davi.requests"
    ]
    assert messages
    assert all(kakao_token not in message for message in messages)
    assert all(session_token not in message for message in messages)
    assert all(_SECRET not in message for message in messages)
