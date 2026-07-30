"""FastAPI 앱. 라우터는 얇게: reasoning.next_step을 호출하고 에러를 분류만 한다."""

from __future__ import annotations

import logging
import time
import uuid
from ipaddress import ip_address

from fastapi import FastAPI, Request, status
from fastapi.responses import JSONResponse

from .config import settings
from .dev_access import SlidingWindowRateLimiter, token_matches
from .errors import LlmError, api_error_for, http_status_for, retry_after_header
from .llm import get_llm_client
from .raw_store import raw_store_dir, store_raw_exchange
from .reasoning import next_step
from .schemas import ApiError, ErrorKind, NextStepRequest, NextStepResponse

app = FastAPI(title="davi reasoning orchestrator", version="0.1.0")
# Uvicorn 기본 핸들러를 상속해 별도 logging 설정 없이 서버 콘솔에 출력한다.
request_logger = logging.getLogger("uvicorn.error.davi.requests")

_NEXT_STEP_PATH = "/v1/next-step"
_MAX_LOG_VALUE_LENGTH = 200
_DEV_TOKEN_HEADER = "X-Davi-Dev-Token"
dev_rate_limiter = SlidingWindowRateLimiter()


def _safe_log_value(value: object) -> str:
    """허용된 운영 메타데이터를 한 줄·상한 길이로 제한한다."""
    if value is None:
        return "-"
    one_line = " ".join(str(value).splitlines())
    if len(one_line) > _MAX_LOG_VALUE_LENGTH:
        return f"{one_line[:_MAX_LOG_VALUE_LENGTH]}…"
    return one_line


def _development_access_error(request: Request) -> JSONResponse | None:
    """실제 원격 요청의 토큰과 팀 전체 요청 제한을 검사한다.

    ``DAVI_BIND_HOST``는 공식 스크립트가 선언한 값일 뿐, uvicorn CLI의 실제 ``--host``를
    증명하지 못한다. 그래서 토큰이 비어 있더라도 실제 client 주소가 loopback이 아니면
    fail-closed로 막는다. 스크립트를 우회해 0.0.0.0에 직접 띄우는 경우도 이 경계에서 닫힌다.
    """

    if settings.davi_env != "development":
        return None

    expected = settings.davi_dev_access_token.get_secret_value().strip()
    if not expected:
        client_host = request.client.host if request.client else ""
        try:
            is_loopback = ip_address(client_host).is_loopback
        except ValueError:
            is_loopback = False
        if is_loopback:
            return None
        return _development_auth_error(request)

    supplied = request.headers.get(_DEV_TOKEN_HEADER)
    if not token_matches(expected, supplied):
        return _development_auth_error(request)

    decision = dev_rate_limiter.check(
        settings.davi_dev_rate_limit_requests,
        settings.davi_dev_rate_limit_window_seconds,
    )
    if decision.allowed:
        return None

    retry_after = decision.retry_after_seconds or 1
    error = ApiError(
        kind=ErrorKind.QUOTA,
        message="development request limit exceeded",
        retry_after_seconds=retry_after,
        request_id=request.state.request_id,
    )
    return JSONResponse(
        status_code=status.HTTP_429_TOO_MANY_REQUESTS,
        content=error.model_dump(),
        headers={"Retry-After": str(retry_after)},
    )


def _development_auth_error(request: Request) -> JSONResponse:
    error = ApiError(
        kind=ErrorKind.AUTH,
        message="development access denied",
        request_id=request.state.request_id,
    )
    return JSONResponse(
        status_code=status.HTTP_401_UNAUTHORIZED,
        content=error.model_dump(),
    )


def _store_raw_if_dogfood(
    req: NextStepRequest,
    status_code: int,
    payload: NextStepResponse | ApiError,
    request: Request,
) -> None:
    """화이트리스트 기기의 왕복만 픽스처 채널에 보존한다(#41).

    운영 로그와 별개 채널이다(CLAUDE.md 실행 절). 실패 응답(ApiError)도 봉투에 담는다 —
    실패 화면이 픽스처의 주 수집 대상이다. 저장 실패가 안내를 막으면 안 되므로
    오류는 비식별 메타만 남기고 삼킨다.
    """
    if not settings.should_store_raw(req.device_id):
        return
    try:
        store_raw_exchange(
            raw_store_dir(settings.davi_dogfood_raw_dir),
            request.state.request_id,
            status_code,
            req,
            payload,
        )
    except OSError as exc:
        request_logger.warning(
            "raw_store_failed request_id=%s error=%s",
            request.state.request_id,
            type(exc).__name__,
        )


@app.middleware("http")
async def log_next_step_request(request: Request, call_next):
    """추론 요청의 비식별 운영 메타데이터만 한 줄로 남긴다."""
    if request.url.path != _NEXT_STEP_PATH:
        return await call_next(request)

    request.state.request_id = uuid.uuid4().hex
    started_at = time.perf_counter()
    status_code = 500
    try:
        response = _development_access_error(request)
        if response is None:
            response = await call_next(request)
        status_code = response.status_code
        return response
    finally:
        metadata = getattr(request.state, "next_step_metadata", {})
        duration_ms = (time.perf_counter() - started_at) * 1000
        request_logger.info(
            "next_step request_id=%s package_name=%s element_count=%s "
            "client_version=%s status_code=%s duration_ms=%.2f",
            request.state.request_id,
            _safe_log_value(metadata.get("package_name")),
            metadata.get("element_count", 0),
            _safe_log_value(metadata.get("client_version")),
            status_code,
            duration_ms,
        )


@app.get("/health")
def health() -> dict:
    if settings.davi_env == "production":
        return {"status": "ok"}
    return {
        "status": "ok",
        "environment": settings.davi_env,
        "model": settings.davi_model,
        "stub_llm": settings.davi_use_stub_llm,
    }


@app.post("/v1/next-step", response_model=NextStepResponse)
def post_next_step(req: NextStepRequest, request: Request) -> NextStepResponse | JSONResponse:
    request.state.next_step_metadata = {
        "package_name": req.screen.package_name,
        "element_count": len(req.screen.elements),
        "client_version": req.client_version,
    }
    try:
        response = next_step(req, get_llm_client())
    except LlmError as e:
        # 앱이 사용자에게 다르게 안내하도록 kind를 그대로 전달(HANDOFF §5, LlmException).
        # message는 디버그용이며 시니어에게 그대로 보여주지 않는다(C8).
        error = api_error_for(e, request.state.request_id)
        _store_raw_if_dogfood(req, http_status_for(e), error, request)
        return JSONResponse(
            status_code=http_status_for(e),
            content=error.model_dump(),
            headers=retry_after_header(e) or None,
        )
    _store_raw_if_dogfood(req, status.HTTP_200_OK, response, request)
    return response
