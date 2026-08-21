"""Gemini 후보 응답의 순수 프로토콜 보조 로직.

HTTP 재시도, 로그, 서비스·평가 오류 분류는 호출자가 담당한다. 여기서는 두 경계가 공통으로
지켜야 할 후보 텍스트 추출, 출력 상한 종료 판별, JSON fence 제거만 다룬다.

`strip_json_fence`만은 공급자 중립이다 — 어느 모델이든 드물게 Markdown fence를 붙이므로
Gemini·OpenAI·Anthropic 경로가 모두 이 함수를 쓴다.
"""

from __future__ import annotations

from enum import StrEnum
from typing import Any


class GeminiCandidateErrorReason(StrEnum):
    MAX_TOKENS = "max_tokens"
    MISSING_TEXT = "missing_text"
    EMPTY_TEXT = "empty_text"


class GeminiCandidateError(ValueError):
    """모델 원문을 포함하지 않는 후보 응답 구조 오류."""

    def __init__(self, reason: GeminiCandidateErrorReason) -> None:
        super().__init__(reason.value)
        self.reason = reason


def extract_candidate_text(payload: Any) -> str:
    """Gemini JSON payload에서 첫 후보의 텍스트를 추출한다."""

    try:
        candidate = payload["candidates"][0]
        finish_reason = candidate.get("finishReason")
    except (AttributeError, IndexError, KeyError, TypeError) as error:
        raise GeminiCandidateError(GeminiCandidateErrorReason.MISSING_TEXT) from error

    if finish_reason == "MAX_TOKENS":
        raise GeminiCandidateError(GeminiCandidateErrorReason.MAX_TOKENS)

    try:
        parts = candidate["content"]["parts"]
        text = "".join(part["text"] for part in parts if "text" in part)
    except (IndexError, KeyError, TypeError) as error:
        raise GeminiCandidateError(GeminiCandidateErrorReason.MISSING_TEXT) from error

    if not text.strip():
        raise GeminiCandidateError(GeminiCandidateErrorReason.EMPTY_TEXT)
    return text


def strip_json_fence(text: str) -> str:
    """모델이 드물게 붙이는 Markdown JSON fence를 제거한다."""

    stripped = text.strip()
    if not stripped.startswith("```"):
        return stripped
    lines = stripped.splitlines()
    if len(lines) >= 3 and lines[-1].strip() == "```":
        return "\n".join(lines[1:-1]).strip()
    return stripped
