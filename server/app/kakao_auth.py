"""카카오 로그인 검증과 세션 토큰 발급.

서버가 맡는 몫만 있다: 앱이 (카카오 SDK든 웹 OAuth든) 카카오에서 받아 온 access token을
카카오 API로 검증하고, **우리 카카오 앱에서 발급된 토큰인지**(app_id) 확인한 뒤,
카카오 회원번호를 담은 무상태 세션 JWT를 돌려준다. 사용자 DB는 두지 않는다 —
세션 토큰의 서명 검증만으로 이후 요청의 사용자를 식별한다.

카카오 access token 원문은 검증에만 쓰고 로그·응답 어디에도 남기지 않는다
(CLAUDE.md 실행 절: access token은 어떤 프로필에서도 기록하지 않는다).
"""

from __future__ import annotations

import time
from collections.abc import Callable
from typing import Any

import httpx
import jwt
from pydantic import BaseModel, Field, field_validator

from .config import settings
from .schemas import ErrorKind

KAKAO_TOKEN_INFO_URL = "https://kapi.kakao.com/v1/user/access_token_info"
_KAKAO_TIMEOUT_SECONDS = 10.0
_JWT_ALGORITHM = "HS256"
_JWT_ISSUER = "davi"


class KakaoLoginRequest(BaseModel):
    """앱 -> 서버 로그인 요청. 카카오 토큰은 body로만 받고 어디에도 echo하지 않는다.

    인증 전 공개 endpoint라 입력 상한을 스키마에서 자른다(#167 리뷰 P2) — 토큰은 그대로
    Authorization 헤더로 복사되고 client_version은 로그 메타로 들어가므로, 상한이 없으면
    임의 크기 입력이 메모리·로그·HTTP 헤더를 오염시킨다. 카카오 토큰 실측은 수십~수백 자라
    512는 여유 상한이다.
    """

    kakao_access_token: str = Field(min_length=1, max_length=512)
    client_version: str | None = Field(default=None, max_length=64)

    @field_validator("kakao_access_token")
    @classmethod
    def reject_blank_token(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("kakao_access_token은 공백일 수 없습니다")
        return value


class SessionTokenResponse(BaseModel):
    """앱이 이후 보호 요청의 ``Authorization: Bearer`` 값으로 쓸 세션 토큰."""

    session_token: str
    expires_in_seconds: int


class AuthError(Exception):
    """앱이 kind로 분기할 수 있는 인증 오류. HTTP 상태는 여기서 한 번만 결정한다."""

    def __init__(self, kind: ErrorKind, message: str, http_status: int) -> None:
        super().__init__(message)
        self.kind = kind
        self.message = message
        self.http_status = http_status


def _require_auth_settings() -> tuple[str, int]:
    """비밀·앱 ID가 없으면 로그인 자체가 성립하지 않으므로 SERVER로 닫는다."""

    secret = settings.davi_auth_jwt_secret.get_secret_value().strip()
    app_id = settings.davi_kakao_app_id
    if not secret or app_id <= 0:
        raise AuthError(
            ErrorKind.SERVER,
            "kakao auth is not configured (DAVI_AUTH_JWT_SECRET / DAVI_KAKAO_APP_ID)",
            503,
        )
    return secret, app_id


def fetch_access_token_info(
    kakao_access_token: str,
    transport: httpx.BaseTransport | None = None,
) -> dict[str, Any]:
    """카카오 토큰 정보 조회. 200이 아니면 앱이 분기할 오류로 바꾼다."""

    try:
        with httpx.Client(transport=transport, timeout=_KAKAO_TIMEOUT_SECONDS) as client:
            response = client.get(
                KAKAO_TOKEN_INFO_URL,
                headers={"Authorization": f"Bearer {kakao_access_token}"},
            )
    except httpx.HTTPError as exc:
        raise AuthError(ErrorKind.NETWORK, "kakao api unreachable", 502) from exc

    if response.status_code == 401:
        raise AuthError(ErrorKind.AUTH, "kakao access token rejected", 401)
    if response.status_code != 200:
        raise AuthError(ErrorKind.SERVER, "kakao api error", 502)
    try:
        payload = response.json()
    except ValueError as exc:
        raise AuthError(ErrorKind.SERVER, "kakao api returned invalid json", 502) from exc
    if not isinstance(payload, dict):
        raise AuthError(ErrorKind.SERVER, "kakao api response missing fields", 502)
    return payload


def issue_session_token(
    kakao_user_id: int,
    clock: Callable[[], float] = time.time,
) -> SessionTokenResponse:
    secret, _ = _require_auth_settings()
    now = int(clock())
    ttl = settings.davi_auth_session_ttl_seconds
    token = jwt.encode(
        {"iss": _JWT_ISSUER, "sub": str(kakao_user_id), "iat": now, "exp": now + ttl},
        secret,
        algorithm=_JWT_ALGORITHM,
    )
    return SessionTokenResponse(session_token=token, expires_in_seconds=ttl)


def verify_session_token(session_token: str) -> int:
    """보호 endpoint가 쓸 검증 헬퍼. 유효하면 카카오 회원번호를 돌려준다."""

    secret, _ = _require_auth_settings()
    try:
        payload = jwt.decode(
            session_token,
            secret,
            algorithms=[_JWT_ALGORITHM],
            issuer=_JWT_ISSUER,
            options={"require": ["exp", "iat", "sub", "iss"]},
        )
    except jwt.ExpiredSignatureError as exc:
        raise AuthError(ErrorKind.AUTH, "session token expired", 401) from exc
    except jwt.InvalidTokenError as exc:
        raise AuthError(ErrorKind.AUTH, "session token invalid", 401) from exc
    try:
        return int(payload["sub"])
    except (KeyError, ValueError) as exc:
        raise AuthError(ErrorKind.AUTH, "session token invalid", 401) from exc


def login_with_kakao(
    request: KakaoLoginRequest,
    transport: httpx.BaseTransport | None = None,
    clock: Callable[[], float] = time.time,
) -> SessionTokenResponse:
    """카카오 토큰 검증 -> 우리 앱 발급분인지 확인 -> 세션 토큰 발급."""

    _, expected_app_id = _require_auth_settings()
    info = fetch_access_token_info(request.kakao_access_token, transport=transport)
    app_id = info.get("app_id")
    kakao_user_id = info.get("id")
    if not isinstance(app_id, int) or not isinstance(kakao_user_id, int):
        raise AuthError(ErrorKind.SERVER, "kakao api response missing fields", 502)
    if app_id != expected_app_id:
        # 다른 카카오 앱에서 발급된 토큰. 유출·오설정 어느 쪽이든 로그인으로 받지 않는다.
        raise AuthError(ErrorKind.AUTH, "kakao token issued for another app", 401)
    return issue_session_token(kakao_user_id, clock=clock)
