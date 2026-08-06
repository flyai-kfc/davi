"""음성 발화 대화를 LLM으로 정리하는 경계.

규칙 기반 사람 표식이나 답변 위치 매핑은 두지 않는다. 실제 실행은 Gemini 구조화 출력이고,
스텁은 API 키 없는 계약·CI 스모크만 위한 결정론적 대역이다.
"""

from __future__ import annotations

import json
import logging
import time
from collections.abc import Mapping
from functools import cache
from typing import Any

import httpx

from .config import settings
from .errors import LlmError
from .gemini_response import (
    GeminiCandidateError,
    GeminiCandidateErrorReason,
    extract_candidate_text,
    strip_json_fence,
)
from .gemini_schema import build_response_schema
from .intent_prompt import build_intent_prompt
from .observability import LLM_LOGGER_NAME, log_event
from .prompt import fallback_prompt
from .schemas import ErrorKind, IntentDialogueStatus, SlotFillRequest, SlotFillState

INTENT_DIALOGUE_SCHEMA = build_response_schema(SlotFillState)
_GEMINI_API_BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
_DEFAULT_RETRY_AFTER_SECONDS = 60
_MAX_OUTPUT_TOKENS = 512
llm_logger = logging.getLogger(LLM_LOGGER_NAME)


class IntentResolver:
    def resolve(self, request: SlotFillRequest) -> SlotFillState:  # pragma: no cover - interface
        raise NotImplementedError


class StubIntentResolver(IntentResolver):
    """키 없는 스모크용 대역. 의미 품질이나 질문 필요 여부를 주장하지 않는다."""

    def resolve(self, request: SlotFillRequest) -> SlotFillState:
        return SlotFillState(
            status=IntentDialogueStatus.READY,
            message="목표를 정리했어요. 내용을 확인한 뒤 시작해 주세요.",
            goal=request.utterance,
        )


class _GeminiHttpError(Exception):
    def __init__(self, status_code: int, retry_after_seconds: int | None = None) -> None:
        super().__init__(f"Gemini API HTTP {status_code}")
        self.status_code = status_code
        self.retry_after_seconds = retry_after_seconds


class GeminiIntentResolver(IntentResolver):
    """Gemini REST 구조화 출력으로 질문 필요 여부와 다음 문장을 생성한다."""

    def __init__(
        self,
        api_key: str,
        model: str,
        http_client: httpx.Client | None = None,
    ) -> None:
        if not api_key:
            raise LlmError(ErrorKind.AUTH, "GEMINI_API_KEY 미설정")
        self.api_key = api_key
        self.model = model
        self.http_client = http_client or httpx.Client(timeout=httpx.Timeout(20.0))

    def resolve(self, request: SlotFillRequest) -> SlotFillState:
        prompt = build_intent_prompt(request)
        try:
            response_text = self._call_once(
                prompt,
                with_schema=True,
                provider_attempt=1,
                trigger="initial",
            )
        except _GeminiHttpError as error:
            if error.status_code != 400:
                raise self._to_llm_error(error) from error
            try:
                response_text = self._call_once(
                    fallback_prompt(prompt, INTENT_DIALOGUE_SCHEMA),
                    with_schema=False,
                    provider_attempt=2,
                    trigger="schema_recovery",
                )
            except _GeminiHttpError as fallback_error:
                raise self._to_llm_error(fallback_error) from fallback_error
        return self._parse_state(response_text)

    def _call_once(
        self,
        prompt: str,
        *,
        with_schema: bool,
        provider_attempt: int,
        trigger: str,
    ) -> str:
        generation_config: dict[str, Any] = {
            "responseMimeType": "application/json",
            "maxOutputTokens": _MAX_OUTPUT_TOKENS,
        }
        if with_schema:
            generation_config["responseSchema"] = INTENT_DIALOGUE_SCHEMA
        payload = {
            "contents": [{"parts": [{"text": prompt}]}],
            "generationConfig": generation_config,
        }
        schema_mode = "schema" if with_schema else "fallback"
        started_at = time.perf_counter()
        log_event(
            llm_logger,
            logging.INFO,
            "gemini_attempt_started",
            model=self.model,
            operation="intent_dialogue",
            provider_attempt=provider_attempt,
            schema_mode=schema_mode,
            trigger=trigger,
        )
        try:
            response = self.http_client.post(
                f"{_GEMINI_API_BASE_URL}/models/{self.model}:generateContent",
                headers={"x-goog-api-key": self.api_key},
                json=payload,
            )
        except httpx.TimeoutException as error:
            self._log_attempt_finished(
                started_at,
                provider_attempt,
                schema_mode,
                trigger,
                outcome="error",
                error_kind=ErrorKind.NETWORK,
            )
            raise LlmError(ErrorKind.NETWORK, "Gemini API 요청 시간 초과") from error
        except httpx.RequestError as error:
            self._log_attempt_finished(
                started_at,
                provider_attempt,
                schema_mode,
                trigger,
                outcome="error",
                error_kind=ErrorKind.NETWORK,
            )
            raise LlmError(ErrorKind.NETWORK, "Gemini API 네트워크 오류") from error

        if response.is_error:
            self._log_attempt_finished(
                started_at,
                provider_attempt,
                schema_mode,
                trigger,
                outcome="error",
                provider_status_class=f"{response.status_code // 100}xx",
                error_kind=_provider_error_kind(response.status_code),
            )
            raise _GeminiHttpError(
                response.status_code,
                _retry_after_seconds(response.headers),
            )

        self._log_attempt_finished(
            started_at,
            provider_attempt,
            schema_mode,
            trigger,
            outcome="success",
            provider_status_class=f"{response.status_code // 100}xx",
        )
        return _response_text(response)

    def _log_attempt_finished(
        self,
        started_at: float,
        provider_attempt: int,
        schema_mode: str,
        trigger: str,
        *,
        outcome: str,
        provider_status_class: str | None = None,
        error_kind: ErrorKind | None = None,
    ) -> None:
        log_event(
            llm_logger,
            logging.INFO if outcome == "success" else logging.WARNING,
            "gemini_attempt_finished",
            model=self.model,
            operation="intent_dialogue",
            provider_attempt=provider_attempt,
            schema_mode=schema_mode,
            trigger=trigger,
            outcome=outcome,
            provider_status_class=provider_status_class,
            error_kind=error_kind,
            duration_ms=round((time.perf_counter() - started_at) * 1000, 2),
        )

    def _parse_state(self, response_text: str) -> SlotFillState:
        try:
            payload = json.loads(strip_json_fence(response_text))
            return SlotFillState.model_validate(payload)
        except (ValueError, TypeError) as error:
            log_event(
                llm_logger,
                logging.WARNING,
                "gemini_parse_error",
                operation="intent_dialogue",
                reason="invalid_intent_dialogue",
                action="fail",
            )
            raise LlmError(
                ErrorKind.PARSE,
                "Gemini 응답이 음성 목표 대화 계약과 맞지 않음",
            ) from error

    @staticmethod
    def _to_llm_error(error: _GeminiHttpError) -> LlmError:
        if error.status_code == 429:
            return LlmError(
                ErrorKind.QUOTA,
                "Gemini API 사용량 또는 요청 한도 초과",
                (
                    error.retry_after_seconds
                    if error.retry_after_seconds is not None
                    else _DEFAULT_RETRY_AFTER_SECONDS
                ),
            )
        if error.status_code in {401, 403}:
            return LlmError(ErrorKind.AUTH, "Gemini API 인증 또는 권한 오류")
        if error.status_code == 400:
            return LlmError(ErrorKind.BAD_REQUEST, "Gemini API 요청 형식 오류")
        return LlmError(ErrorKind.SERVER, f"Gemini API 서버 오류 (HTTP {error.status_code})")


def _response_text(response: httpx.Response) -> str:
    try:
        payload = response.json()
    except ValueError as error:
        raise LlmError(ErrorKind.PARSE, "Gemini 응답 본문에 텍스트 후보가 없음") from error
    try:
        return extract_candidate_text(payload)
    except GeminiCandidateError as error:
        if error.reason is GeminiCandidateErrorReason.MAX_TOKENS:
            log_event(
                llm_logger,
                logging.WARNING,
                "gemini_response_error",
                operation="intent_dialogue",
                reason="max_output_tokens",
                action="fail",
            )
            raise LlmError(ErrorKind.PARSE, "Gemini 응답이 출력 토큰 상한에서 잘림") from error
        if error.reason is GeminiCandidateErrorReason.EMPTY_TEXT:
            raise LlmError(ErrorKind.PARSE, "Gemini 응답 텍스트가 비어 있음") from error
        raise LlmError(ErrorKind.PARSE, "Gemini 응답 본문에 텍스트 후보가 없음") from error


def _retry_after_seconds(headers: Mapping[str, str]) -> int | None:
    value = headers.get("Retry-After")
    if value is None:
        return None
    try:
        return max(0, int(value))
    except ValueError:
        return None


def _provider_error_kind(status_code: int) -> ErrorKind:
    if status_code == 429:
        return ErrorKind.QUOTA
    if status_code in {401, 403}:
        return ErrorKind.AUTH
    if status_code == 400:
        return ErrorKind.BAD_REQUEST
    return ErrorKind.SERVER


def resolve_intent(
    request: SlotFillRequest,
    resolver: IntentResolver | None = None,
) -> SlotFillState:
    """HTTP와 분리된 의도 대화 진입점. eval·테스트는 resolver를 주입할 수 있다."""

    return (resolver or get_intent_resolver()).resolve(request)


@cache
def get_intent_resolver() -> IntentResolver:
    if settings.davi_use_stub_llm:
        return StubIntentResolver()
    return GeminiIntentResolver(
        settings.gemini_api_key.get_secret_value(),
        settings.davi_model,
    )
