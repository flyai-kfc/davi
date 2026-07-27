"""추론 오류를 API 계약으로 바꾸는 경계.

LLM 공급자·스텁 구현은 ``LlmError``만 내보낸다. HTTP 상태와 재시도 헤더는 이 모듈에서
한 번만 결정해 FastAPI 라우터가 공급자별 오류 규칙을 알 필요가 없게 한다.
"""

from __future__ import annotations

from .schemas import ApiError, ErrorKind


class LlmError(Exception):
    """LLM 호출·파싱 단계에서 앱이 분기할 수 있는 오류."""

    def __init__(
        self,
        kind: ErrorKind,
        message: str,
        retry_after_seconds: int | None = None,
    ) -> None:
        super().__init__(message)
        self.kind = kind
        self.message = message
        self.retry_after_seconds = retry_after_seconds


def http_status_for(error: LlmError) -> int:
    """앱 계약의 오류 종류를 HTTP 상태로 투영한다."""

    return 429 if error.kind is ErrorKind.QUOTA else 502


def api_error_for(error: LlmError, request_id: str) -> ApiError:
    """내부 오류를 응답 본문으로 바꾼다. 사용자 문구는 앱이 ``kind``로 만든다."""

    return ApiError(
        kind=error.kind,
        message=error.message,
        retry_after_seconds=error.retry_after_seconds,
        request_id=request_id,
    )


def retry_after_header(error: LlmError) -> dict[str, str]:
    """재시도 시간이 있는 경우에만 HTTP 헤더를 만든다. 0도 유효한 값이다."""

    if error.retry_after_seconds is None:
        return {}
    return {"Retry-After": str(error.retry_after_seconds)}
