"""FastAPI 앱. 라우터는 얇게: reasoning.next_step을 호출하고 에러를 분류만 한다."""

from __future__ import annotations

import logging
import re
import time
import uuid
from ipaddress import ip_address

from fastapi import FastAPI, Request, status
from fastapi.exception_handlers import request_validation_exception_handler
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from .app_resolution import resolve_app_request
from .config import settings
from .dev_access import SlidingWindowRateLimiter, token_matches
from .errors import LlmError, api_error_for, http_status_for, retry_after_header
from .intent_resolution import resolve_intent
from .llm import get_llm_client
from .observability import (
    ATTEMPT_HEADER,
    LOGICAL_REQUEST_ID_HEADER,
    REQUEST_ID_HEADER,
    REQUEST_LOGGER_NAME,
    TraceContext,
    log_event,
    reset_trace_context,
    set_trace_context,
)
from .raw_store import raw_store_dir, store_raw_exchange
from .reasoning import next_step
from .schemas import (
    ApiError,
    AppResolutionRequest,
    AppResolutionState,
    ErrorKind,
    NextStepRequest,
    NextStepResponse,
    SlotFillRequest,
    SlotFillState,
)

app = FastAPI(title="davi reasoning orchestrator", version="0.1.0")
request_logger = logging.getLogger(REQUEST_LOGGER_NAME)

_NEXT_STEP_PATH = "/v1/next-step"
_SLOT_FILL_PATH = "/v1/intent/slot-fill"
_APP_RESOLUTION_PATH = "/v1/intent/app-resolution"
_HEALTH_PATH = "/health"
_TRACED_API_PATHS = frozenset({_NEXT_STEP_PATH, _SLOT_FILL_PATH, _APP_RESOLUTION_PATH})
_DEV_TOKEN_HEADER = "X-Davi-Dev-Token"
_ATTEMPT_PATTERN = re.compile(r"[1-9][0-9]*")
dev_rate_limiter = SlidingWindowRateLimiter()


def _request_route(path: str) -> str:
    """사용자가 만든 raw path를 로그에 넣지 않고 고정 route만 분류한다."""

    if path == _NEXT_STEP_PATH:
        return _NEXT_STEP_PATH
    if path == _SLOT_FILL_PATH:
        return _SLOT_FILL_PATH
    if path == _APP_RESOLUTION_PATH:
        return _APP_RESOLUTION_PATH
    if path == _HEALTH_PATH:
        return _HEALTH_PATH
    return "unmatched"


def _logical_request_headers(request: Request) -> tuple[str | None, int | None, bool]:
    """논리 요청 header 쌍을 검증한다. invalid 값은 절대 echo하거나 로그에 넣지 않는다."""

    logical_ids = request.headers.getlist(LOGICAL_REQUEST_ID_HEADER)
    attempts = request.headers.getlist(ATTEMPT_HEADER)
    if not logical_ids and not attempts:
        return None, None, True
    if len(logical_ids) != 1 or len(attempts) != 1:
        return None, None, False

    logical_request_id = logical_ids[0]
    attempt_value = attempts[0]
    if _ATTEMPT_PATTERN.fullmatch(attempt_value) is None:
        return None, None, False
    try:
        parsed_id = uuid.UUID(logical_request_id)
        attempt = int(attempt_value)
    except (ValueError, TypeError):
        return None, None, False
    if str(parsed_id) != logical_request_id or attempt <= 0 or attempt > 2_147_483_647:
        return None, None, False
    return logical_request_id, attempt, True


def _attach_trace_headers(response: JSONResponse, context: TraceContext) -> None:
    response.headers[REQUEST_ID_HEADER] = context.request_id
    if context.logical_request_id is not None and context.attempt is not None:
        response.headers[LOGICAL_REQUEST_ID_HEADER] = context.logical_request_id
        response.headers[ATTEMPT_HEADER] = str(context.attempt)


def _invalid_trace_headers_response(request_id: str) -> JSONResponse:
    error = ApiError(
        kind=ErrorKind.BAD_REQUEST,
        message="invalid logical request headers",
        request_id=request_id,
    )
    return JSONResponse(status_code=status.HTTP_400_BAD_REQUEST, content=error.model_dump())


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
    request.state.error_kind = "DEV_RATE_LIMIT"
    request.state.retry_after_seconds = retry_after
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
    request.state.error_kind = ErrorKind.AUTH.value
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
        log_event(
            request_logger,
            logging.WARNING,
            "raw_store_failed",
            exception_class=type(exc).__name__,
        )


@app.exception_handler(RequestValidationError)
async def validation_error_handler(request: Request, exc: RequestValidationError):
    request.state.error_kind = "VALIDATION"
    request.state.validation_error_count = len(exc.errors())
    if request.url.path in {_SLOT_FILL_PATH, _APP_RESOLUTION_PATH}:
        error = ApiError(
            kind=ErrorKind.BAD_REQUEST,
            message="intent request validation failed",
            request_id=request.state.request_id,
        )
        return JSONResponse(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            content=error.model_dump(),
        )
    return await request_validation_exception_handler(request, exc)


@app.middleware("http")
async def observe_request(request: Request, call_next):
    """모든 ASGI 응답에 request ID를 붙이고 경로별 비식별 이벤트를 남긴다."""

    route = _request_route(request.url.path)
    request.state.request_id = uuid.uuid4().hex
    logical_request_id: str | None = None
    attempt: int | None = None
    valid_trace_headers = True
    if route in _TRACED_API_PATHS:
        logical_request_id, attempt, valid_trace_headers = _logical_request_headers(request)
    context = TraceContext(request.state.request_id, logical_request_id, attempt)
    request.state.trace_context = context
    context_token = set_trace_context(context)
    started_at = time.perf_counter()
    status_code = 500
    response: JSONResponse | None = None
    try:
        if route in _TRACED_API_PATHS:
            log_event(
                request_logger,
                logging.INFO,
                "request_received",
                method=request.method,
                route=route,
            )
        if not valid_trace_headers:
            request.state.error_kind = ErrorKind.BAD_REQUEST.value
            response = _invalid_trace_headers_response(request.state.request_id)
        else:
            response = _development_access_error(request) if route in _TRACED_API_PATHS else None
            if response is None:
                response = await call_next(request)
        _attach_trace_headers(response, context)
        status_code = response.status_code
        return response
    except Exception as exc:  # noqa: BLE001 - 응답 추적 ID를 보존하는 최종 HTTP 경계
        request.state.error_kind = "INTERNAL"
        request.state.exception_class = type(exc).__name__
        response = JSONResponse(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            content={"detail": "Internal Server Error"},
        )
        _attach_trace_headers(response, context)
        status_code = response.status_code
        return response
    finally:
        duration_ms = (time.perf_counter() - started_at) * 1000
        if not (route == _HEALTH_PATH and status_code == status.HTTP_200_OK):
            metadata = getattr(request.state, "request_metadata", {})
            error_kind = getattr(request.state, "error_kind", None)
            if status_code == status.HTTP_404_NOT_FOUND and error_kind is None:
                error_kind = "NOT_FOUND"
            log_event(
                request_logger,
                logging.INFO if status_code < 500 else logging.ERROR,
                "request_finished",
                method=request.method,
                route=route,
                status_code=status_code,
                duration_ms=round(duration_ms, 2),
                package_name=metadata.get("package_name"),
                element_count=metadata.get("element_count"),
                client_version=metadata.get("client_version"),
                conversation_message_count=metadata.get("conversation_message_count"),
                candidate_count=metadata.get("candidate_count"),
                resolution_phase=metadata.get("resolution_phase"),
                resolution_status=metadata.get("resolution_status"),
                error_kind=error_kind,
                retry_after_seconds=getattr(request.state, "retry_after_seconds", None),
                validation_error_count=getattr(request.state, "validation_error_count", None),
                exception_class=getattr(request.state, "exception_class", None),
            )
        reset_trace_context(context_token)


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
    request.state.request_metadata = {
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
        request.state.error_kind = e.kind.value
        request.state.retry_after_seconds = e.retry_after_seconds
        _store_raw_if_dogfood(req, http_status_for(e), error, request)
        return JSONResponse(
            status_code=http_status_for(e),
            content=error.model_dump(),
            headers=retry_after_header(e) or None,
        )
    _store_raw_if_dogfood(req, status.HTTP_200_OK, response, request)
    return response


@app.post("/v1/intent/slot-fill", response_model=SlotFillState)
def post_slot_fill(req: SlotFillRequest, request: Request) -> SlotFillState | JSONResponse:
    """현재 발화를 LLM으로 정리해 질문 하나 또는 기존 안내 세션용 목표를 돌려준다."""

    request.state.request_metadata = {
        "conversation_message_count": len(req.conversation_history),
    }
    try:
        return resolve_intent(req)
    except LlmError as error:
        api_error = api_error_for(error, request.state.request_id)
        request.state.error_kind = error.kind.value
        request.state.retry_after_seconds = error.retry_after_seconds
        return JSONResponse(
            status_code=http_status_for(error),
            content=api_error.model_dump(),
            headers=retry_after_header(error) or None,
        )


@app.post("/v1/intent/app-resolution", response_model=AppResolutionState)
def post_app_resolution(
    req: AppResolutionRequest,
    request: Request,
) -> AppResolutionState | JSONResponse:
    """목표 후보를 만들거나, 기기에서 돌려준 ID/status만으로 무상태 최종 판정한다."""

    phase = "feedback" if req.feedback is not None else "candidates"
    request.state.request_metadata = {
        "resolution_phase": phase,
        "candidate_count": len(req.feedback.plan.candidates) if req.feedback else None,
    }
    try:
        state = resolve_app_request(req)
    except LlmError as error:
        api_error = api_error_for(error, request.state.request_id)
        request.state.error_kind = error.kind.value
        request.state.retry_after_seconds = error.retry_after_seconds
        return JSONResponse(
            status_code=http_status_for(error),
            content=api_error.model_dump(),
            headers=retry_after_header(error) or None,
        )
    request.state.request_metadata["candidate_count"] = (
        len(state.plan.candidates)
        if state.plan is not None
        else len(req.feedback.plan.candidates)
        if req.feedback is not None
        else 0
    )
    request.state.request_metadata["resolution_status"] = state.status.value
    return state
