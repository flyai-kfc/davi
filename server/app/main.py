"""FastAPI 앱. 라우터는 얇게: reasoning.next_step을 호출하고 에러를 분류만 한다."""

from __future__ import annotations

import logging
import time
import uuid

from fastapi import FastAPI, Request, status
from fastapi.responses import JSONResponse

from .config import settings
from .dev_access import SlidingWindowRateLimiter, token_matches
from .errors import LlmError, api_error_for, http_status_for, retry_after_header
from .llm import get_llm_client
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
    """설정된 development 토큰과 팀 전체 요청 제한을 검사한다."""

    if settings.davi_env != "development":
        return None

    expected = settings.davi_dev_access_token.get_secret_value().strip()
    if not expected:
        return None

    supplied = request.headers.get(_DEV_TOKEN_HEADER)
    if not token_matches(expected, supplied):
        error = ApiError(
            kind=ErrorKind.AUTH,
            message="development access denied",
            request_id=request.state.request_id,
        )
        return JSONResponse(
            status_code=status.HTTP_401_UNAUTHORIZED,
            content=error.model_dump(),
        )

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
        return next_step(req, get_llm_client())
    except LlmError as e:
        # 앱이 사용자에게 다르게 안내하도록 kind를 그대로 전달(HANDOFF §5, LlmException).
        # message는 디버그용이며 시니어에게 그대로 보여주지 않는다(C8).
        error = api_error_for(e, request.state.request_id)
        return JSONResponse(
            status_code=http_status_for(e),
            content=error.model_dump(),
            headers=retry_after_header(e) or None,
        )
