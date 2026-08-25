"""음성 발화 대화를 LLM으로 정리하는 경계.

규칙 기반 사람 표식이나 답변 위치 매핑은 두지 않는다. 실제 실행은 공급자 구조화 출력이고
(공급자 선택·HTTP·오류 분류는 `llm_provider.py`), 스텁은 API 키 없는 계약·CI 스모크만 위한
결정론적 대역이다.
"""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass
from functools import cache
from typing import Any, Never, Self

import httpx
from pydantic import BaseModel, Field, ValidationError, field_validator, model_validator

from .config import settings
from .errors import LlmError
from .gemini_response import strip_json_fence
from .intent_prompt import build_intent_prompt
from .llm_provider import (
    ProviderCallError,
    ResponseContract,
    StructuredJsonProvider,
    build_provider,
)
from .observability import LLM_LOGGER_NAME, log_event
from .schemas import (
    MAX_INTENT_MESSAGE_LENGTH,
    MAX_VOICE_TEXT_LENGTH,
    ClarificationAnswerSource,
    ClarificationPlan,
    ClarificationQuestion,
    ErrorKind,
    IntentDialogueStatus,
    SlotFillRequest,
    SlotFillState,
)

_OPERATION = "intent_dialogue"
llm_logger = logging.getLogger(LLM_LOGGER_NAME)


class _IntentDialogueDecision(BaseModel):
    """모델 내부 판단 계약. 공개 wire 응답과 분리해 근거를 결정보다 먼저 생성한다."""

    decision_basis: str = Field(
        min_length=1,
        max_length=MAX_INTENT_MESSAGE_LENGTH,
        description="현재 대화만으로 질문 필요 여부를 판단한 짧은 한국어 근거 한 문장",
    )
    status: IntentDialogueStatus = Field(description="추가 질문, 목표 준비 완료 또는 안전 종료")
    message: str = Field(
        min_length=1,
        max_length=MAX_INTENT_MESSAGE_LENGTH,
        description="TTS로 읽을 질문, 목표 준비 완료 또는 안전 종료 안내 한 문장",
    )
    goal: str | None = Field(
        default=None,
        max_length=MAX_VOICE_TEXT_LENGTH,
        description="ready일 때 기존 안내 세션에 넘길 구체적인 목표",
    )
    clarification: ClarificationPlan | None = Field(
        default=None,
        description="needs_clarification일 때 최대 두 개의 질문과 각 최대 세 개 보기",
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
        if self.status is IntentDialogueStatus.NEEDS_CLARIFICATION:
            if self.goal is not None:
                raise ValueError("추가 질문 상태에는 goal이 없어야 한다")
            if self.clarification is None:
                raise ValueError("추가 질문 상태에는 clarification이 필요하다")
            if self.message != self.clarification.questions[0].prompt:
                raise ValueError("message는 첫 구체화 질문과 같아야 한다")
        elif self.status is IntentDialogueStatus.READY:
            if self.goal is None:
                raise ValueError("준비 완료 상태에는 goal이 필요하다")
            if self.clarification is not None:
                raise ValueError("준비 완료 상태에는 clarification이 없어야 한다")
        elif self.goal is not None or self.clarification is not None:
            raise ValueError("안전 종료 상태에는 goal과 clarification이 없어야 한다")
        return self

    def to_wire_state(self) -> SlotFillState:
        return SlotFillState(
            status=self.status,
            message=self.message,
            goal=self.goal,
            clarification=self.clarification,
        )


@dataclass(frozen=True)
class IntentDialogueResponseBudgets:
    """상태별 최대 계약 응답의 compact JSON 문자 예산.

    Pydantic의 `max_length`는 토큰이 아니라 Unicode 문자 수다. 실제 토큰 수는 공급자와 모델마다
    달라 이 값이 정확한 토큰 수라고 주장하지 않는다. 다만 계약 상한으로 만든 유효 응답의
    compact JSON 문자 수보다 작은 토큰 상한은 명백히 위험하므로, 공급자 중립 최소선으로 쓴다.
    보이지 않는 thinking/reasoning 몫은 `build_provider()`가 이 값 위에 따로 더한다.
    """

    ready: int
    needs_clarification: int

    @property
    def required(self) -> int:
        return max(self.ready, self.needs_clarification)


def _schema_limit(model: type[BaseModel], field: str, keyword: str) -> int:
    """Pydantic JSON Schema에서 필드 제약 하나를 읽는다. Optional의 anyOf도 따라간다."""

    field_schema = model.model_json_schema()["properties"][field]
    candidates = [field_schema, *field_schema.get("anyOf", [])]
    for candidate in candidates:
        value = candidate.get(keyword)
        if isinstance(value, int) and value > 0:
            return value
    raise RuntimeError(f"{model.__name__}.{field}에 {keyword} 양의 상한이 필요합니다")


def _maximum_text(length: int, variant: int = 0) -> str:
    """길이 상한을 정확히 채우면서 같은 목록 안에서는 구분 가능한 테스트 문자열."""

    return "가" * (length - 1) + chr(ord("나") + variant)


def _contract_maximum_decisions() -> dict[IntentDialogueStatus, _IntentDialogueDecision]:
    """필드·배열 상한에서 ready와 needs_clarification 최대 유효 응답을 만든다."""

    basis = _maximum_text(_schema_limit(_IntentDialogueDecision, "decision_basis", "maxLength"))

    ready = _IntentDialogueDecision(
        decision_basis=basis,
        status=IntentDialogueStatus.READY,
        message=_maximum_text(_schema_limit(_IntentDialogueDecision, "message", "maxLength")),
        goal=_maximum_text(_schema_limit(_IntentDialogueDecision, "goal", "maxLength")),
    )

    question_schema = ClarificationQuestion.model_json_schema()["properties"]
    option_schema = question_schema["options"]
    option_length = option_schema["items"]["maxLength"]
    option_count = option_schema["maxItems"]
    question_count = _schema_limit(ClarificationPlan, "questions", "maxItems")
    id_length = _schema_limit(ClarificationQuestion, "id", "maxLength")
    prompt_length = _schema_limit(ClarificationQuestion, "prompt", "maxLength")

    questions = [
        ClarificationQuestion(
            id=_maximum_text(id_length, question_index),
            prompt=_maximum_text(prompt_length, question_index),
            options=[
                _maximum_text(option_length, option_index) for option_index in range(option_count)
            ],
            allow_freeform=True,
        )
        for question_index in range(question_count)
    ]
    needs_clarification = _IntentDialogueDecision(
        decision_basis=basis,
        status=IntentDialogueStatus.NEEDS_CLARIFICATION,
        message=questions[0].prompt,
        clarification=ClarificationPlan(questions=questions),
    )
    return {
        IntentDialogueStatus.READY: ready,
        IntentDialogueStatus.NEEDS_CLARIFICATION: needs_clarification,
    }


def _compact_json_character_count(decision: _IntentDialogueDecision) -> int:
    """Gemini(선택 필드 생략)와 strict(null 포함) 표현 중 더 긴 compact JSON 길이."""

    return max(
        len(
            json.dumps(
                decision.model_dump(mode="json", exclude_none=exclude_none),
                ensure_ascii=False,
                separators=(",", ":"),
            )
        )
        for exclude_none in (True, False)
    )


_MAXIMUM_DECISIONS = _contract_maximum_decisions()
INTENT_DIALOGUE_RESPONSE_BUDGETS = IntentDialogueResponseBudgets(
    ready=_compact_json_character_count(_MAXIMUM_DECISIONS[IntentDialogueStatus.READY]),
    needs_clarification=_compact_json_character_count(
        _MAXIMUM_DECISIONS[IntentDialogueStatus.NEEDS_CLARIFICATION]
    ),
)
# 손으로 쓰는 512 같은 수치가 아니라 현재 Pydantic 계약에서 매번 파생한다. 공급자가 보이지 않는
# 추론에 쓰는 몫은 build_provider()가 이 최소선 위에 더한다.
_RESPONSE_HEADROOM_TOKENS = INTENT_DIALOGUE_RESPONSE_BUDGETS.required


INTENT_DIALOGUE_CONTRACT = ResponseContract.of(_OPERATION, _IntentDialogueDecision)
# 기존 이름 유지 — Gemini 표현이다. 공급자 중립 계약은 INTENT_DIALOGUE_CONTRACT를 쓴다.
INTENT_DIALOGUE_SCHEMA = INTENT_DIALOGUE_CONTRACT.gemini_schema


class _IntentContractViolation(Exception):
    """원문을 로그에 남기지 않고 고정 reason code로만 다루는 계약 위반."""

    def __init__(self, reason: str) -> None:
        super().__init__(reason)
        self.reason = reason


class IntentResolver:
    def resolve(self, request: SlotFillRequest) -> SlotFillState:  # pragma: no cover - interface
        raise NotImplementedError


class StubIntentResolver(IntentResolver):
    """키 없는 스모크용 대역. 의미 품질이나 질문 필요 여부를 주장하지 않는다."""

    def resolve(self, request: SlotFillRequest) -> SlotFillState:
        details = [
            answer.answer
            for answer in request.clarification_answers
            if answer.source is not ClarificationAnswerSource.UNKNOWN_OR_DECLINED
        ]
        # 원발화와 답은 각각 wire 상한 안이어도 합치면 goal 상한을 넘을 수 있다. 스텁은
        # 의미 품질을 주장하지 않는 CI 대역이므로, 계약 바깥 값을 만들어 500으로 죽는 대신
        # 공개 goal 상한에서 결정론적으로 자른다.
        goal = ", ".join([request.utterance, *details])[:MAX_VOICE_TEXT_LENGTH]
        return SlotFillState(
            status=IntentDialogueStatus.READY,
            message="목표를 정리했어요. 내용을 확인한 뒤 시작해 주세요.",
            goal=goal,
        )


class RemoteIntentResolver(IntentResolver):
    """공급자 구조화 출력으로 질문 필요 여부와 다음 문장을 생성한다."""

    def __init__(self, provider: StructuredJsonProvider) -> None:
        self.provider = provider

    def resolve(self, request: SlotFillRequest) -> SlotFillState:
        prompt = build_intent_prompt(request)
        finalizing = bool(request.clarification_answers)
        try:
            response_text = self.provider.generate_json(
                prompt,
                with_schema=True,
                attempt=1,
                trigger="initial",
            )
        except LlmError as error:
            if finalizing and error.kind is ErrorKind.PARSE:
                return self._safe_end_state()
            raise
        except ProviderCallError as error:
            raise self.provider.to_llm_error(error) from error

        try:
            return self._parse_state(response_text, finalizing=finalizing)
        except _IntentContractViolation as error:
            self._log_contract_recovery(error.reason, action="safe_end" if finalizing else "fail")
            if finalizing:
                return self._safe_end_state()
            raise LlmError(
                ErrorKind.PARSE,
                "응답이 음성 목표 대화 계약과 맞지 않음",
            ) from error
        except LlmError as error:
            if finalizing and error.kind is ErrorKind.PARSE:
                return self._safe_end_state()
            raise

    def _parse_state(self, response_text: str, *, finalizing: bool) -> SlotFillState:
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
            state = decision.to_wire_state()
            return self._finalize_or_return(state, finalizing=finalizing)

        try:
            decision = _IntentDialogueDecision.model_validate(normalized)
        except ValidationError as error:
            self._raise_or_recover(normalized, error)
        state = decision.to_wire_state()
        return self._finalize_or_return(state, finalizing=finalizing)

    def _finalize_or_return(self, state: SlotFillState, *, finalizing: bool) -> SlotFillState:
        """답을 받은 뒤에는 세 번째 질문 대신 서버가 안전 종료로 닫는다."""

        if not finalizing and state.status is IntentDialogueStatus.UNABLE_TO_FINALIZE:
            raise _IntentContractViolation("phase_status_mismatch")
        if not finalizing or state.status is not IntentDialogueStatus.NEEDS_CLARIFICATION:
            return state
        self._log_contract_recovery("clarification_after_finalization", action="safe_end")
        return self._safe_end_state()

    @staticmethod
    def _safe_end_state() -> SlotFillState:
        return SlotFillState(
            status=IntentDialogueStatus.UNABLE_TO_FINALIZE,
            message="목표를 충분히 구체화하지 못했어요. 처음부터 다시 말씀해 주세요.",
        )

    def _raise_or_recover(
        self,
        payload: dict[str, Any],
        error: ValidationError,
    ) -> Never:
        reason = _contract_violation_reason(payload, error)
        if reason is not None:
            raise _IntentContractViolation(reason) from error
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


def _contract_violation_reason(
    payload: dict[str, Any],
    error: ValidationError,
) -> str | None:
    """스키마로 표현하지 못한 텍스트·교차 필드 위반만 교정 대상으로 삼는다."""

    if payload.get("status") == IntentDialogueStatus.READY:
        goal = payload.get("goal")
        if goal is None or (isinstance(goal, str) and not goal.strip()):
            return "status_goal_mismatch"
    if (
        payload.get("status") == IntentDialogueStatus.NEEDS_CLARIFICATION
        and payload.get("clarification") is None
    ):
        return "status_clarification_mismatch"
    if payload.get("status") == IntentDialogueStatus.NEEDS_CLARIFICATION:
        clarification = payload.get("clarification")
        questions = clarification.get("questions") if isinstance(clarification, dict) else None
        first_prompt = (
            questions[0].get("prompt") if questions and isinstance(questions[0], dict) else None
        )
        if first_prompt != payload.get("message"):
            return "status_clarification_mismatch"

    relevant_fields = {"decision_basis", "message", "goal", "clarification"}
    for detail in error.errors(
        include_url=False,
        include_context=False,
        include_input=False,
    ):
        location = detail.get("loc", ())
        field = location[0] if location else None
        error_type = detail.get("type")
        if field not in relevant_fields:
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
