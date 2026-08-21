"""FastAPI 앱. 라우터는 얇게: reasoning.next_step을 호출하고 에러를 분류만 한다."""

from __future__ import annotations

import logging
import re
import time
import uuid
from ipaddress import ip_address

from fastapi import FastAPI, Request, Response, status
from fastapi.exception_handlers import request_validation_exception_handler
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from .app_resolution import resolve_app_request
from .config import settings
from .dev_access import (
    RateLimitDecision,
    SlidingWindowRateLimiter,
    SourceAndGlobalRateLimiter,
    token_matches,
)
from .errors import LlmError, api_error_for, http_status_for, retry_after_header
from .intent_resolution import resolve_intent
from .kakao_auth import (
    AuthError,
    KakaoLoginRequest,
    SessionTokenResponse,
    login_with_kakao,
    verify_session_token,
)
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

# 2차 마스킹 우회는 시작 시점에 한 번 크게 알린다(#203). 설정을 켜 놓은 것을 잊은 채
# 실기기를 붙이면 그 기기의 원문 화면이 실제 Gemini까지 나간다.
# `Settings` 검증이 development·loopback·개발 토큰 없음을 이미 강제하므로 여기서는 경고만 한다.
if settings.davi_disable_privacy_masking:
    logging.getLogger(__name__).warning(
        "2차 개인정보 마스킹이 꺼져 있습니다(DAVI_DISABLE_PRIVACY_MASKING=true). "
        "앱이 보낸 label이 치환 없이 실제 모델까지 전달되고 raw 봉투 저장은 비활성화됩니다. "
        "합성 데이터만 넣은 전용 테스트 기기에서만 사용하세요(#203).",
    )

_NEXT_STEP_PATH = "/v1/next-step"
_SLOT_FILL_PATH = "/v1/intent/slot-fill"
_APP_RESOLUTION_PATH = "/v1/intent/app-resolution"
_AUTH_KAKAO_PATH = "/v1/auth/kakao"
_HEALTH_PATH = "/health"
_TRACED_API_PATHS = frozenset(
    {_NEXT_STEP_PATH, _SLOT_FILL_PATH, _APP_RESOLUTION_PATH, _AUTH_KAKAO_PATH}
)
_DEV_TOKEN_HEADER = "X-Davi-Dev-Token"
_SESSION_AUTH_HEADER = "Authorization"
_SESSION_AUTH_PREFIX = "Bearer "
_ATTEMPT_PATTERN = re.compile(r"[1-9][0-9]*")
_EXTERNAL_DAILY_WINDOW_SECONDS = 24 * 60 * 60
# 세션 인증이 필요한 추론 endpoint만이다. 로그인(_AUTH_KAKAO_PATH)은 토큰이 없는 상태로
# 호출되므로 대상이 아니다 — 자체 auth_rate_limiter가 프로필과 무관하게 따로 막는다.
_SESSION_PROTECTED_PATHS = frozenset({_NEXT_STEP_PATH, _SLOT_FILL_PATH, _APP_RESOLUTION_PATH})
dev_rate_limiter = SlidingWindowRateLimiter()
# 로그인 전용 리미터(#167 리뷰 P1). dev 리미터는 development에서만 돌지만, 로그인은 인증 전
# 공개 endpoint가 카카오 API를 소모하므로 프로필과 무관하게 제한한다. 별도 창을 쓰는
# 이유: dev 창을 공유하면 로그인 폭주가 팀의 추론 쿼터까지 잠근다.
# 출처별 제한과 전역 상한을 **하나의 리미터에서 원자적으로** 판정한다(#167 재리뷰·3차 P1):
# 출처별 제한이 한 출처의 폭주로부터 정상 사용자를, 전역 상한이 카카오 API 소모 총량을
# 지키며, 거부된 요청은 출처 키를 남기지 않아 분산 출처 폭주에도 키 수가 전역 상한으로 묶인다.
auth_rate_limiter = SourceAndGlobalRateLimiter()
# beta·production 추론 endpoint 전용 리미터(#214). 출처는 카카오 회원번호(세션 토큰의 sub)다.
# 짧은 창(폭주)과 하루 창(누적)을 별개 인스턴스로 둔다 — 같은 인스턴스에 두 판정을 섞으면
# 어느 창이 막았는지 구분이 안 되고, 하루 창의 오래된 타임스탬프가 짧은 창의 정리 주기와
# 어긋난다.
external_rate_limiter = SourceAndGlobalRateLimiter()
external_daily_rate_limiter = SourceAndGlobalRateLimiter()


def _request_route(path: str) -> str:
    """사용자가 만든 raw path를 로그에 넣지 않고 고정 route만 분류한다."""

    if path == _NEXT_STEP_PATH:
        return _NEXT_STEP_PATH
    if path == _SLOT_FILL_PATH:
        return _SLOT_FILL_PATH
    if path == _APP_RESOLUTION_PATH:
        return _APP_RESOLUTION_PATH
    if path == _AUTH_KAKAO_PATH:
        return _AUTH_KAKAO_PATH
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

    accepted = settings.access_tokens
    if not accepted:
        client_host = request.client.host if request.client else ""
        try:
            is_loopback = ip_address(client_host).is_loopback
        except ValueError:
            is_loopback = False
        if is_loopback:
            return None
        return _development_auth_error(request)

    # 팀 개발 토큰과 외부 테스트 토큰을 **둘 다** 받는다. 헤더는 하나뿐이고 값만 다르므로
    # 앱은 고칠 것이 없다 — 테스터 빌드는 받은 값을 그대로 실으면 된다.
    #
    # 하나라도 맞으면 통과시키되 **모든 후보를 끝까지 비교한다.** 맞는 순간 끊으면 비교 횟수가
    # 토큰 종류에 따라 달라져, 응답 시간으로 "어느 토큰이 맞았는지"를 재는 길이 열린다.
    supplied = request.headers.get(_DEV_TOKEN_HEADER)
    matched_kind: str | None = None
    for kind, expected in accepted.items():
        if token_matches(expected, supplied):
            matched_kind = kind
    if matched_kind is None:
        return _development_auth_error(request)
    # 어느 토큰이 들어왔는지만 남긴다(값은 남기지 않는다). 테스트 기간에 참가자 트래픽과
    # 팀 트래픽을 로그에서 갈라 보기 위한 것이다.
    request.state.access_token_kind = matched_kind

    if request.url.path == _AUTH_KAKAO_PATH:
        # 로그인은 전용 리미터가 모든 프로필에서 따로 제한한다. dev 창은 팀 추론 쿼터
        # (Gemini) 보호용이라, 카카오만 부르는 로그인 폭주가 이 창을 소모해 팀의 추론을
        # 잠그면 안 된다 — 토큰 검사까지만 적용하고 dev 창은 건너뛴다.
        return None

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


def _external_access_error(request: Request) -> JSONResponse | None:
    """development가 아닌 프로필(beta·production)에서 세션 인증과 요청 제한을 검사한다(#214).

    ``_development_access_error``는 development에서만 동작하므로, 그 밖의 프로필에서는
    지금까지 어떤 요청 경계도 없었다. 카카오 로그인이 발급한 세션 JWT를 여기서 처음
    검증한다 — 로그인 자체(``_AUTH_KAKAO_PATH``)는 토큰이 없는 상태로 호출되므로
    ``_SESSION_PROTECTED_PATHS``에 없고, 자체 ``auth_rate_limiter``가 프로필과 무관하게
    로그인 폭주만 따로 막는다.
    """

    if settings.davi_env == "development":
        return None
    if request.url.path not in _SESSION_PROTECTED_PATHS:
        return None

    authorization = request.headers.get(_SESSION_AUTH_HEADER, "")
    if not authorization.startswith(_SESSION_AUTH_PREFIX):
        return _external_auth_error(request)
    token = authorization[len(_SESSION_AUTH_PREFIX) :].strip()
    if not token:
        return _external_auth_error(request)
    try:
        kakao_user_id = verify_session_token(token)
    except AuthError:
        return _external_auth_error(request)

    # 출처는 카카오 회원번호다 — device_id·IP가 아니라 인증된 사용자 단위로 제한한다.
    source = str(kakao_user_id)
    decision = external_rate_limiter.check(
        source,
        source_limit=settings.davi_external_rate_limit_per_source_requests,
        source_window_seconds=settings.davi_external_rate_limit_per_source_window_seconds,
        global_limit=settings.davi_external_rate_limit_requests,
        global_window_seconds=settings.davi_external_rate_limit_window_seconds,
    )
    if not decision.allowed:
        return _external_rate_limit_response(request, decision, scope="burst")

    daily_decision = external_daily_rate_limiter.check(
        source,
        source_limit=settings.davi_external_daily_limit_per_source_requests,
        source_window_seconds=_EXTERNAL_DAILY_WINDOW_SECONDS,
        global_limit=settings.davi_external_daily_limit_requests,
        global_window_seconds=_EXTERNAL_DAILY_WINDOW_SECONDS,
    )
    if not daily_decision.allowed:
        return _external_rate_limit_response(request, daily_decision, scope="daily")

    return None


def _external_auth_error(request: Request) -> JSONResponse:
    request.state.error_kind = "EXTERNAL_AUTH"
    error = ApiError(
        kind=ErrorKind.AUTH,
        message="session token required",
        request_id=request.state.request_id,
    )
    return JSONResponse(
        status_code=status.HTTP_401_UNAUTHORIZED,
        content=error.model_dump(),
    )


def _external_rate_limit_response(
    request: Request,
    decision: RateLimitDecision,
    *,
    scope: str,
) -> JSONResponse:
    retry_after = decision.retry_after_seconds or 1
    scope_label = "SOURCE" if decision.limited_scope == "source" else "GLOBAL"
    request.state.error_kind = f"EXTERNAL_{scope.upper()}_LIMIT_{scope_label}"
    request.state.retry_after_seconds = retry_after
    error = ApiError(
        kind=ErrorKind.QUOTA,
        message="request limit exceeded",
        retry_after_seconds=retry_after,
        request_id=request.state.request_id,
    )
    return JSONResponse(
        status_code=status.HTTP_429_TOO_MANY_REQUESTS,
        content=error.model_dump(),
        headers={"Retry-After": str(retry_after)},
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
    if request.url.path in {_SLOT_FILL_PATH, _APP_RESOLUTION_PATH, _AUTH_KAKAO_PATH}:
        error = ApiError(
            kind=ErrorKind.BAD_REQUEST,
            message="request validation failed",
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
            response = None
            if route in _TRACED_API_PATHS:
                response = _development_access_error(request)
                if response is None:
                    response = _external_access_error(request)
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
                clarification_answer_count=metadata.get("clarification_answer_count"),
                intent_phase=metadata.get("intent_phase"),
                candidate_count=metadata.get("candidate_count"),
                resolution_phase=metadata.get("resolution_phase"),
                resolution_status=metadata.get("resolution_status"),
                error_kind=error_kind,
                access_token_kind=getattr(request.state, "access_token_kind", None),
                retry_after_seconds=getattr(request.state, "retry_after_seconds", None),
                validation_error_count=getattr(request.state, "validation_error_count", None),
                exception_class=getattr(request.state, "exception_class", None),
            )
        reset_trace_context(context_token)


@app.get("/health")
def health() -> dict:
    if settings.davi_env != "development":
        return {"status": "ok"}
    return {
        "status": "ok",
        "environment": settings.davi_env,
        # provider와 model을 함께 알린다. DAVI_MODEL을 비워 두면 공급자 기본 모델이 쓰이므로,
        # 둘 중 하나만 보면 "어느 공급자에 어떤 모델을 보내고 있는지"를 확인할 수 없다.
        "provider": settings.davi_llm_provider,
        "model": settings.resolved_model,
        "stub_llm": settings.davi_use_stub_llm,
        # 서버 2차 상태만 알린다. 앱 1차 상태는 앱이 logcat으로 따로 드러내며(GuideSvc의
        # privacyMasking=), 두 값을 합치지 않아야 한쪽만 우회한 상태를 오인하지 않는다(#203).
        "privacy_masking_disabled": settings.davi_disable_privacy_masking,
        # 배포 워크플로가 카카오 로그인 배선을 확인하는 유일한 자리다(#279). production 분기는
        # 그대로 status만 알린다 — 진단 값은 개발 환경에서만 드러낸다.
        "kakao_auth_configured": settings.kakao_auth_configured,
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


def _auth_rate_limit_response(
    request: Request,
    decision: RateLimitDecision,
) -> JSONResponse:
    retry_after = decision.retry_after_seconds or 1
    request.state.error_kind = (
        "AUTH_RATE_LIMIT_SOURCE" if decision.limited_scope == "source" else "AUTH_RATE_LIMIT"
    )
    request.state.retry_after_seconds = retry_after
    error = ApiError(
        kind=ErrorKind.QUOTA,
        message="kakao login request limit exceeded",
        retry_after_seconds=retry_after,
        request_id=request.state.request_id,
    )
    return JSONResponse(
        status_code=status.HTTP_429_TOO_MANY_REQUESTS,
        content=error.model_dump(),
        headers={"Retry-After": str(retry_after)},
    )


@app.post("/v1/auth/kakao", response_model=SessionTokenResponse)
def post_kakao_login(
    req: KakaoLoginRequest,
    request: Request,
    response: Response,
) -> SessionTokenResponse | JSONResponse:
    """앱이 받아 온 카카오 access token을 검증하고 우리 세션 토큰을 발급한다.

    토큰 원문은 로그에 남기지 않는다(kakao_auth.py). 사용자 문구는 앱이 kind로 만든다(C8).
    """

    request.state.request_metadata = {"client_version": req.client_version}
    # 출처는 직접 연결 peer IP만 쓴다. X-Forwarded-For는 클라이언트가 임의로 채울 수 있어
    # 여기서 파싱하지 않는다 — 프록시 뒤 배포에서는 uvicorn --proxy-headers와
    # --forwarded-allow-ips로 신뢰 프록시가 request.client를 재작성한 값이 그대로 들어온다.
    source = request.client.host if request.client else "unknown"
    decision = auth_rate_limiter.check(
        source,
        source_limit=settings.davi_auth_rate_limit_per_source_requests,
        source_window_seconds=settings.davi_auth_rate_limit_per_source_window_seconds,
        global_limit=settings.davi_auth_rate_limit_requests,
        global_window_seconds=settings.davi_auth_rate_limit_window_seconds,
    )
    if not decision.allowed:
        return _auth_rate_limit_response(request, decision)
    try:
        issued = login_with_kakao(req)
    except AuthError as e:
        error = ApiError(kind=e.kind, message=e.message, request_id=request.state.request_id)
        request.state.error_kind = e.kind.value
        return JSONResponse(status_code=e.http_status, content=error.model_dump())
    # 성공 응답에는 30일 bearer JWT가 들어간다. 클라이언트·중간 계층이 토큰 응답을
    # 저장하지 못하게 캐시를 금지한다(#167 재리뷰 P2).
    response.headers["Cache-Control"] = "no-store"
    return issued


@app.post("/v1/intent/slot-fill", response_model=SlotFillState)
def post_slot_fill(req: SlotFillRequest, request: Request) -> SlotFillState | JSONResponse:
    """첫 호출은 질문 계획, 답을 담은 두 번째 호출은 목표 또는 안전 종료를 돌려준다."""

    request.state.request_metadata = {
        "conversation_message_count": len(req.conversation_history),
        "clarification_answer_count": len(req.clarification_answers),
        "intent_phase": "finalize" if req.clarification_answers else "plan",
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
