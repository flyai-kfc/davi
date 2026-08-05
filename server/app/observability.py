"""비식별 요청 상관관계와 JSON Lines 로그 경계(#81).

사용자 입력이나 공급자 본문을 받지 않는 작은 API만 노출한다. 로그 호출자가 허용 필드만
넘기는 구조로 만들어 goal·label·prompt가 실수로 직렬화되는 경로를 두지 않는다.
"""

from __future__ import annotations

import json
import logging
from contextvars import ContextVar, Token
from dataclasses import dataclass
from datetime import UTC, datetime
from enum import Enum
from typing import Any

LOGICAL_REQUEST_ID_HEADER = "X-Davi-Logical-Request-ID"
ATTEMPT_HEADER = "X-Davi-Attempt"
REQUEST_ID_HEADER = "X-Request-ID"

REQUEST_LOGGER_NAME = "uvicorn.error.davi.requests"
LLM_LOGGER_NAME = "uvicorn.error.davi.llm"

_MAX_LOG_STRING_LENGTH = 200


@dataclass(frozen=True)
class TraceContext:
    """한 HTTP 전송과 앱 논리 요청을 연결하는 비식별 문맥."""

    request_id: str
    logical_request_id: str | None = None
    attempt: int | None = None


_trace_context: ContextVar[TraceContext | None] = ContextVar(
    "davi_trace_context",
    default=None,
)


def set_trace_context(context: TraceContext) -> Token[TraceContext | None]:
    return _trace_context.set(context)


def reset_trace_context(token: Token[TraceContext | None]) -> None:
    _trace_context.reset(token)


def current_trace_context() -> TraceContext | None:
    return _trace_context.get()


def utc_timestamp() -> str:
    return datetime.now(UTC).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def _safe_value(value: Any) -> str | int | float | bool:
    if isinstance(value, Enum):
        value = value.value
    if isinstance(value, bool | int | float):
        return value
    one_line = " ".join(str(value).splitlines())
    if len(one_line) > _MAX_LOG_STRING_LENGTH:
        return f"{one_line[:_MAX_LOG_STRING_LENGTH]}…"
    return one_line


def event_payload(event: str, **fields: Any) -> dict[str, Any]:
    """허용 필드만 받은 이벤트에 공통 추적 문맥과 시간을 붙인다."""

    payload: dict[str, Any] = {"timestamp": utc_timestamp(), "event": event}
    context = current_trace_context()
    if context is not None:
        payload["request_id"] = context.request_id
        if context.logical_request_id is not None:
            payload["logical_request_id"] = context.logical_request_id
        if context.attempt is not None:
            payload["attempt"] = context.attempt
    for key, value in fields.items():
        if value is not None:
            payload[key] = _safe_value(value)
    return payload


def log_event(
    logger: logging.Logger,
    level: int,
    event: str,
    **fields: Any,
) -> None:
    """한 이벤트를 단일 JSON 객체로 기록한다."""

    payload = event_payload(event, **fields)
    payload["level"] = logging.getLevelName(level).lower()
    logger.log(level, json.dumps(payload, ensure_ascii=False, separators=(",", ":")))


class SafeJsonFormatter(logging.Formatter):
    """Davi 이벤트와 Uvicorn 자체 진단을 모두 한 줄 JSON으로 만든다."""

    def format(self, record: logging.LogRecord) -> str:
        message = record.getMessage()
        try:
            parsed = json.loads(message)
        except (TypeError, ValueError):
            parsed = None
        if isinstance(parsed, dict) and isinstance(parsed.get("event"), str):
            return json.dumps(parsed, ensure_ascii=False, separators=(",", ":"))

        if message == "Invalid HTTP request received.":
            payload: dict[str, Any] = {
                "timestamp": utc_timestamp(),
                "level": record.levelname.lower(),
                "event": "malformed_http",
            }
        else:
            payload = {
                "timestamp": utc_timestamp(),
                "level": record.levelname.lower(),
                "event": "server_log",
                "component": record.name,
                "message": _safe_value(message),
            }
        if record.exc_info and record.exc_info[0] is not None:
            payload["exception_class"] = record.exc_info[0].__name__
        return json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
