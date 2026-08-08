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
from typing import Any, Never, Self

import httpx
from pydantic import BaseModel, Field, ValidationError, field_validator, model_validator

from .config import settings
from .errors import LlmError
from .gemini_response import (
    GeminiCandidateError,
    GeminiCandidateErrorReason,
    extract_candidate_text,
    strip_json_fence,
)
from .gemini_schema import build_response_schema
from .intent_prompt import build_intent_prompt, build_intent_repair_prompt
from .observability import LLM_LOGGER_NAME, log_event
from .prompt import fallback_prompt
from .schemas import (
    MAX_INTENT_MESSAGE_LENGTH,
    MAX_VOICE_TEXT_LENGTH,
    ErrorKind,
    IntentDialogueStatus,
    SlotFillRequest,
    SlotFillState,
)

_GEMINI_API_BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
_DEFAULT_RETRY_AFTER_SECONDS = 60
_MAX_OUTPUT_TOKENS = 512
llm_logger = logging.getLogger(LLM_LOGGER_NAME)


class _IntentDialogueDecision(BaseModel):
    """Gemini 내부 판단 계약. 공개 wire 응답과 분리해 근거를 결정보다 먼저 생성한다."""

    decision_basis: str = Field(
        min_length=1,
        max_length=MAX_INTENT_MESSAGE_LENGTH,
        description="현재 대화만으로 질문 필요 여부를 판단한 짧은 한국어 근거 한 문장",
    )
    status: IntentDialogueStatus = Field(description="추가 질문 필요 또는 목표 준비 완료")
    message: str = Field(
        min_length=1,
        max_length=MAX_INTENT_MESSAGE_LENGTH,
        description="TTS로 읽을 한국어 질문 또는 목표 준비 완료 안내 한 문장",
    )
    goal: str | None = Field(
        default=None,
        max_length=MAX_VOICE_TEXT_LENGTH,
        description="ready일 때 기존 안내 세션에 넘길 구체적인 목표",
    )

    @field_validator("decision_basis", "message")
    @classmethod
    def reject_blank_required_text(cls, value: str) -> str:
        stripped = value.strip()
        if not stripped:
            raise ValueError("필수 판단 텍스트는 공백일 수 없다")
        return stripped

    @field_validator("goal")
    @classmethod
    def normalize_goal(cls, value: str | None) -> str | None:
        if value is None:
            return None
        stripped = value.strip()
        if not stripped:
            raise ValueError("완성 목표는 공백일 수 없다")
        return stripped

    @model_validator(mode="after")
    def validate_status_and_goal(self) -> Self:
        if self.status is IntentDialogueStatus.NEEDS_CLARIFICATION and self.goal is not None:
            raise ValueError("추가 질문 상태에는 goal이 없어야 한다")
        if self.status is IntentDialogueStatus.READY and self.goal is None:
            raise ValueError("준비 완료 상태에는 goal이 필요하다")
        return self

    def to_wire_state(self) -> SlotFillState:
        return SlotFillState(status=self.status, message=self.message, goal=self.goal)


INTENT_DIALOGUE_SCHEMA = build_response_schema(_IntentDialogueDecision)


class _RecoverableIntentContractError(Exception):
    """원문을 보존하지 않고 한 번만 교정할 수 있는 계약 위반."""

    def __init__(self, reason: str) -> None:
        super().__init__(reason)
        self.reason = reason


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
            return self._parse_final_state(response_text)

        try:
            return self._parse_state(response_text)
        except _RecoverableIntentContractError as error:
            self._log_contract_recovery(error.reason, action="retry")
            try:
                repaired_text = self._call_once(
                    build_intent_repair_prompt(prompt, error.reason),
                    with_schema=True,
                    provider_attempt=2,
                    trigger="contract_recovery",
                )
            except _GeminiHttpError as repair_error:
                raise self._to_llm_error(repair_error) from repair_error
            return self._parse_final_state(repaired_text)

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
        except (ValueError, TypeError) as error:
            self._raise_parse_error("invalid_json", error)

        if not isinstance(payload, dict):
            self._raise_parse_error("invalid_shape", TypeError("응답이 객체가 아님"))

        normalized = dict(payload)
        if (
            normalized.get("status") == IntentDialogueStatus.NEEDS_CLARIFICATION
            and normalized.get("goal") is not None
        ):
            normalized["goal"] = None
            try:
                decision = _IntentDialogueDecision.model_validate(normalized)
            except ValidationError as error:
                self._raise_or_recover(normalized, error)
            self._log_contract_recovery("status_goal_mismatch", action="normalize")
            return decision.to_wire_state()

        try:
            decision = _IntentDialogueDecision.model_validate(normalized)
        except ValidationError as error:
            self._raise_or_recover(normalized, error)
        return decision.to_wire_state()

    def _parse_final_state(self, response_text: str) -> SlotFillState:
        """두 번째 공급자 호출은 다시 교정하지 않고 실패로 닫는다."""

        try:
            return self._parse_state(response_text)
        except _RecoverableIntentContractError as error:
            self._log_contract_recovery(error.reason, action="fail")
            raise LlmError(
                ErrorKind.PARSE,
                "Gemini 응답이 음성 목표 대화 계약과 맞지 않음",
            ) from error

    def _raise_or_recover(
        self,
        payload: dict[str, Any],
        error: ValidationError,
    ) -> Never:
        reason = _recoverable_contract_reason(payload, error)
        if reason is not None:
            raise _RecoverableIntentContractError(reason) from error
        self._raise_parse_error("invalid_intent_dialogue", error)

    @staticmethod
    def _raise_parse_error(reason: str, error: Exception) -> Never:
        log_event(
            llm_logger,
            logging.WARNING,
            "gemini_parse_error",
            operation="intent_dialogue",
            reason=reason,
            action="fail",
        )
        raise LlmError(
            ErrorKind.PARSE,
            "Gemini 응답이 음성 목표 대화 계약과 맞지 않음",
        ) from error

    @staticmethod
    def _log_contract_recovery(reason: str, *, action: str) -> None:
        log_event(
            llm_logger,
            logging.WARNING,
            "gemini_contract_recovery",
            operation="intent_dialogue",
            reason=reason,
            action=action,
        )

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


# 교정 대상 텍스트 필드와 교차 필드 위반. reason code는 이 둘에서만 나온다.
#
# **새 텍스트 필드를 교정 대상으로 넣으면 여기부터 고친다.** 그러면 파생되는 reason code가
# 늘어나고, `intent_prompt._REPAIR_GUIDANCE`에 해당 문구가 없으면 계약 테스트가 먼저 실패한다.
# 사전이 낡은 채로 굴러가면 모델은 무엇이 틀렸는지 모르는 일반 문구만 받아 같은 실수를 되풀이하고,
# 공급자 호출 2회 상한 안에서 교정이 무력해진다 — 서버는 죽지 않으므로 아무도 알아채지 못한다.
RECOVERABLE_TEXT_FIELDS = ("decision_basis", "message", "goal")
CROSS_FIELD_REASON_CODES = ("status_goal_mismatch",)


def recoverable_reason_codes() -> frozenset[str]:
    """`_recoverable_contract_reason`이 만들 수 있는 reason code 전체.

    손으로 적은 목록이 아니라 위 두 상수에서 파생한다. 목록을 두 벌 유지하면 반드시 어긋나고,
    어긋난 쪽이 교정 문구 사전이면 실패가 조용하다(C1이 `propertyOrdering`에서 택한 판단과 같다).
    """

    codes = set(CROSS_FIELD_REASON_CODES)
    for field in RECOVERABLE_TEXT_FIELDS:
        codes.add(f"{field}_blank")
        codes.add(f"{field}_too_long")
    return frozenset(codes)


def _recoverable_contract_reason(
    payload: dict[str, Any],
    error: ValidationError,
) -> str | None:
    """스키마로 표현하지 못한 텍스트·교차 필드 위반만 교정 대상으로 삼는다."""

    if payload.get("status") == IntentDialogueStatus.READY:
        goal = payload.get("goal")
        if goal is None or (isinstance(goal, str) and not goal.strip()):
            return "status_goal_mismatch"

    recoverable_fields = frozenset(RECOVERABLE_TEXT_FIELDS)
    for detail in error.errors(
        include_url=False,
        include_context=False,
        include_input=False,
    ):
        location = detail.get("loc", ())
        field = location[0] if location else None
        error_type = detail.get("type")
        if field not in recoverable_fields:
            continue
        if error_type == "string_too_long":
            return f"{field}_too_long"
        if error_type in {"string_too_short", "value_error"}:
            value = payload.get(field)
            if isinstance(value, str) and not value.strip():
                return f"{field}_blank"
    return None


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
