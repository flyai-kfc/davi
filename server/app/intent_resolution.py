"""음성 발화 대화를 LLM으로 정리하는 경계.

규칙 기반 사람 표식이나 답변 위치 매핑은 두지 않는다. 실제 실행은 공급자 구조화 출력이고
(공급자 선택·HTTP·오류 분류는 `llm_provider.py`), 스텁은 API 키 없는 계약·CI 스모크만 위한
결정론적 대역이다.
"""

from __future__ import annotations

import json
import logging
from functools import cache
from typing import Any, Never, Self

import httpx
from pydantic import BaseModel, Field, ValidationError, field_validator, model_validator

from .config import settings
from .errors import LlmError
from .gemini_response import strip_json_fence
from .intent_prompt import build_intent_prompt, build_intent_repair_prompt
from .llm_provider import (
    ProviderCallError,
    ResponseContract,
    StructuredJsonProvider,
    build_provider,
)
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

_OPERATION = "intent_dialogue"
# 정상 응답 하나에 필요한 출력 토큰. 공급자가 보이지 않는 추론(Gemini thinking, OpenAI
# reasoning)에 쓰는 몫은 `llm_provider.build_provider`가 여기에 더한다 — 그 몫이 예산을 다
# 먹어 응답이 잘리고 PARSE로 실패하는 게 이 경로에서 실기기 재현됐다(2026-08-18).
_RESPONSE_HEADROOM_TOKENS = 512
llm_logger = logging.getLogger(LLM_LOGGER_NAME)


class _IntentDialogueDecision(BaseModel):
    """모델 내부 판단 계약. 공개 wire 응답과 분리해 근거를 결정보다 먼저 생성한다."""

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


INTENT_DIALOGUE_CONTRACT = ResponseContract.of(_OPERATION, _IntentDialogueDecision)
# 기존 이름 유지 — Gemini 표현이다. 공급자 중립 계약은 INTENT_DIALOGUE_CONTRACT를 쓴다.
INTENT_DIALOGUE_SCHEMA = INTENT_DIALOGUE_CONTRACT.gemini_schema


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


class RemoteIntentResolver(IntentResolver):
    """공급자 구조화 출력으로 질문 필요 여부와 다음 문장을 생성한다."""

    def __init__(self, provider: StructuredJsonProvider) -> None:
        self.provider = provider

    def resolve(self, request: SlotFillRequest) -> SlotFillState:
        prompt = build_intent_prompt(request)
        try:
            response_text = self.provider.generate_json(
                prompt,
                with_schema=True,
                attempt=1,
                trigger="initial",
            )
        except ProviderCallError as error:
            if error.status_code != 400:
                raise self.provider.to_llm_error(error) from error
            try:
                response_text = self.provider.generate_json(
                    fallback_prompt(
                        prompt,
                        self.provider.contract_schema,
                        omit_optional=self.provider.omits_optional_fields,
                    ),
                    with_schema=False,
                    attempt=2,
                    trigger="schema_recovery",
                )
            except ProviderCallError as fallback_error:
                raise self.provider.to_llm_error(fallback_error) from fallback_error
            return self._parse_final_state(response_text)

        try:
            return self._parse_state(response_text)
        except _RecoverableIntentContractError as error:
            self._log_contract_recovery(error.reason, action="retry")
            try:
                repaired_text = self.provider.generate_json(
                    build_intent_repair_prompt(prompt, error.reason),
                    with_schema=True,
                    attempt=2,
                    trigger="contract_recovery",
                )
            except ProviderCallError as repair_error:
                raise self.provider.to_llm_error(repair_error) from repair_error
            return self._parse_final_state(repaired_text)

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
                "응답이 음성 목표 대화 계약과 맞지 않음",
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

    def _raise_parse_error(self, reason: str, error: Exception) -> Never:
        log_event(
            llm_logger,
            logging.WARNING,
            "llm_parse_error",
            provider=self.provider.name,
            operation=_OPERATION,
            reason=reason,
            action="fail",
        )
        raise LlmError(
            ErrorKind.PARSE,
            "응답이 음성 목표 대화 계약과 맞지 않음",
        ) from error

    def _log_contract_recovery(self, reason: str, *, action: str) -> None:
        log_event(
            llm_logger,
            logging.WARNING,
            "llm_contract_recovery",
            provider=self.provider.name,
            operation=_OPERATION,
            reason=reason,
            action=action,
        )


def _recoverable_contract_reason(
    payload: dict[str, Any],
    error: ValidationError,
) -> str | None:
    """스키마로 표현하지 못한 텍스트·교차 필드 위반만 교정 대상으로 삼는다."""

    if payload.get("status") == IntentDialogueStatus.READY:
        goal = payload.get("goal")
        if goal is None or (isinstance(goal, str) and not goal.strip()):
            return "status_goal_mismatch"

    recoverable_fields = {"decision_basis", "message", "goal"}
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


def resolve_intent(
    request: SlotFillRequest,
    resolver: IntentResolver | None = None,
) -> SlotFillState:
    """HTTP와 분리된 의도 대화 진입점. eval·테스트는 resolver를 주입할 수 있다."""

    return (resolver or get_intent_resolver()).resolve(request)


def intent_dialogue_provider(http_client: httpx.Client | None = None) -> StructuredJsonProvider:
    """설정이 고른 공급자로 의도 대화용 클라이언트를 만든다."""

    return build_provider(
        contract=INTENT_DIALOGUE_CONTRACT,
        operation=_OPERATION,
        response_headroom=_RESPONSE_HEADROOM_TOKENS,
        http_client=http_client,
    )


@cache
def get_intent_resolver() -> IntentResolver:
    if settings.davi_use_stub_llm:
        return StubIntentResolver()
    return RemoteIntentResolver(intent_dialogue_provider())
