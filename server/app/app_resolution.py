"""LLM 앱 후보 생성과 기기 대조 결과의 무상태 최종 판정."""

from __future__ import annotations

import json
import logging
import re
import unicodedata
from functools import cache

import httpx
from pydantic import BaseModel, Field, ValidationError, field_validator

from .app_resolution_prompt import build_app_candidate_prompt, build_app_candidate_repair_prompt
from .config import settings
from .errors import LlmError
from .gemini_response import strip_json_fence
from .llm_provider import (
    ProviderCallError,
    ResponseContract,
    StructuredJsonProvider,
    build_provider,
)
from .observability import LLM_LOGGER_NAME, log_event
from .prompt import fallback_prompt
from .schemas import (
    MAX_APP_CANDIDATES,
    MAX_APP_CAPABILITY_LENGTH,
    MAX_APP_LABEL_ALIASES,
    MAX_APP_NAME_LENGTH,
    MAX_APP_PACKAGE_NAMES,
    MAX_INTENT_MESSAGE_LENGTH,
    MAX_VOICE_TEXT_LENGTH,
    AppCandidate,
    AppCandidateAvailability,
    AppCandidatePlan,
    AppResolutionFeedback,
    AppResolutionRequest,
    AppResolutionState,
    AppResolutionStatus,
    ErrorKind,
)

_OPERATION = "app_candidates"
# 정상 응답 하나에 필요한 출력 토큰. 공급자가 보이지 않는 추론(Gemini thinking, OpenAI
# reasoning)에 쓰는 몫은 `llm_provider.build_provider`가 여기에 더한다 — 그 몫이 예산을 다
# 먹어 응답이 잘리는 문제가 intent_dialogue 경로에서 재현됐다(2026-08-18).
_RESPONSE_HEADROOM_TOKENS = 1024
_PACKAGE_NAME = re.compile(r"^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$")
llm_logger = logging.getLogger(LLM_LOGGER_NAME)


class _ProposedAppCandidate(BaseModel):
    canonical_name: str = Field(min_length=1, max_length=MAX_APP_NAME_LENGTH)
    package_names: list[str] = Field(default_factory=list, max_length=MAX_APP_PACKAGE_NAMES)
    label_aliases: list[str] = Field(default_factory=list, max_length=MAX_APP_LABEL_ALIASES)
    confidence: float = Field(ge=0.0, le=1.0)

    @field_validator("canonical_name")
    @classmethod
    def normalize_name(cls, value: str) -> str:
        stripped = value.strip()
        if not stripped:
            raise ValueError("canonical_name blank")
        return stripped

    @field_validator("package_names")
    @classmethod
    def validate_packages(cls, values: list[str]) -> list[str]:
        normalized = [value.strip() for value in values]
        # package는 모델의 보조 힌트다. 잘못된 값 하나 때문에 유효한 후보와 label 별칭까지
        # 버리지 않고, Android package 문법을 만족하는 값만 기기 대조 계약으로 보낸다.
        valid = [
            value
            for value in normalized
            if len(value) <= MAX_APP_NAME_LENGTH and _PACKAGE_NAME.fullmatch(value) is not None
        ]
        return list(dict.fromkeys(valid))

    @field_validator("label_aliases")
    @classmethod
    def normalize_aliases(cls, values: list[str]) -> list[str]:
        normalized = [value.strip() for value in values]
        if any(not value or len(value) > MAX_APP_NAME_LENGTH for value in normalized):
            raise ValueError("blank alias")
        return list(dict.fromkeys(normalized))


class _AppCandidateDecision(BaseModel):
    decision_basis: str = Field(
        min_length=1,
        max_length=MAX_INTENT_MESSAGE_LENGTH,
        description="현재 목표에 직접 필요한 앱 기능과 후보 범위를 정한 짧은 한국어 근거 한 문장",
    )
    capability: str = Field(min_length=1, max_length=MAX_APP_CAPABILITY_LENGTH)
    candidates: list[_ProposedAppCandidate] = Field(
        default_factory=list,
        max_length=MAX_APP_CANDIDATES,
    )

    @field_validator("decision_basis", "capability")
    @classmethod
    def normalize_capability(cls, value: str) -> str:
        stripped = value.strip()
        if not stripped:
            raise ValueError("capability blank")
        return stripped

    def to_plan(self) -> AppCandidatePlan:
        unique: list[_ProposedAppCandidate] = []
        seen: set[str] = set()
        for candidate in self.candidates:
            key = _normalize(candidate.canonical_name)
            if key in seen:
                continue
            seen.add(key)
            unique.append(candidate)
        return AppCandidatePlan(
            capability=self.capability,
            candidates=[
                AppCandidate(candidate_id=f"candidate-{index}", **candidate.model_dump())
                for index, candidate in enumerate(unique, start=1)
            ],
        )


APP_CANDIDATE_CONTRACT = ResponseContract.of(_OPERATION, _AppCandidateDecision)
# 기존 이름 유지 — Gemini 표현이다. 공급자 중립 계약은 APP_CANDIDATE_CONTRACT를 쓴다.
APP_CANDIDATE_SCHEMA = APP_CANDIDATE_CONTRACT.gemini_schema


class AppCandidateResolver:
    def candidates(self, goal: str) -> AppCandidatePlan:  # pragma: no cover - interface
        raise NotImplementedError


class StubAppCandidateResolver(AppCandidateResolver):
    """키 없는 계약 스모크. 앱 의미 품질이나 설치 여부를 주장하지 않는다."""

    def candidates(self, goal: str) -> AppCandidatePlan:
        return AppCandidatePlan(capability="unknown", candidates=[])


class RemoteAppCandidateResolver(AppCandidateResolver):
    """공급자 구조화 출력으로 목표에 맞는 앱 후보를 제안한다."""

    def __init__(self, provider: StructuredJsonProvider) -> None:
        self.provider = provider

    def candidates(self, goal: str) -> AppCandidatePlan:
        prompt = build_app_candidate_prompt(goal)
        try:
            text = self.provider.generate_json(
                prompt, with_schema=True, attempt=1, trigger="initial"
            )
        except ProviderCallError as error:
            if error.status_code != 400:
                raise self.provider.to_llm_error(error) from error
            try:
                text = self.provider.generate_json(
                    fallback_prompt(prompt, self.provider.contract_schema),
                    with_schema=False,
                    attempt=2,
                    trigger="schema_recovery",
                )
            except ProviderCallError as fallback_error:
                raise self.provider.to_llm_error(fallback_error) from fallback_error
            return self._parse(text)

        try:
            return self._parse(text)
        except LlmError as error:
            if error.kind is not ErrorKind.PARSE:
                raise
            try:
                repaired = self.provider.generate_json(
                    build_app_candidate_repair_prompt(prompt),
                    with_schema=True,
                    attempt=2,
                    trigger="contract_recovery",
                )
            except ProviderCallError as repair_error:
                raise self.provider.to_llm_error(repair_error) from repair_error
            return self._parse(repaired)

    def _parse(self, response_text: str) -> AppCandidatePlan:
        try:
            payload = json.loads(strip_json_fence(response_text))
            return _AppCandidateDecision.model_validate(payload).to_plan()
        except (json.JSONDecodeError, ValidationError, TypeError, ValueError) as error:
            log_event(
                llm_logger,
                logging.WARNING,
                "llm_parse_error",
                provider=self.provider.name,
                operation=_OPERATION,
                reason="invalid_app_candidates",
                action="fail",
            )
            raise LlmError(ErrorKind.PARSE, "앱 후보 응답 계약 오류") from error


def resolve_app_request(
    request: AppResolutionRequest,
    resolver: AppCandidateResolver | None = None,
) -> AppResolutionState:
    if request.feedback is None:
        plan = (resolver or get_app_candidate_resolver()).candidates(request.goal)
        if not plan.candidates:
            return AppResolutionState(
                status=AppResolutionStatus.NEEDS_APP_NAME,
                message="사용할 앱 이름을 말씀해 주세요.",
            )
        return AppResolutionState(
            status=AppResolutionStatus.CANDIDATES,
            message="앱 후보를 확인했어요.",
            plan=plan,
        )
    return _finalize_feedback(request.goal, request.feedback)


def _finalize_feedback(goal: str, feedback: AppResolutionFeedback) -> AppResolutionState:
    by_id = {candidate.candidate_id: candidate for candidate in feedback.plan.candidates}
    statuses = {result.candidate_id: result.status for result in feedback.candidate_results}
    if any(status is AppCandidateAvailability.UNKNOWN for status in statuses.values()):
        return AppResolutionState(
            status=AppResolutionStatus.UNAVAILABLE_OR_UNKNOWN,
            message="앱 실행 가능 여부를 확인하지 못했어요. 사용할 앱 이름을 말씀해 주세요.",
        )

    launchable = [
        candidate_id
        for candidate_id, status in statuses.items()
        if status is AppCandidateAvailability.LAUNCHABLE
    ]
    ambiguous = any(status is AppCandidateAvailability.AMBIGUOUS for status in statuses.values())
    if ambiguous or len(launchable) > 1:
        return AppResolutionState(
            status=AppResolutionStatus.NEEDS_USER_CHOICE,
            message="사용할 수 있는 앱이 여러 개예요. 사용할 앱 이름을 말씀해 주세요.",
        )
    if len(launchable) == 1:
        selected_id = launchable[0]
        selected = by_id[selected_id]
        resolved_goal = _resolved_goal(goal, selected.canonical_name)
        return AppResolutionState(
            status=AppResolutionStatus.RESOLVED,
            message=f"{selected.canonical_name} 앱을 사용하도록 목표를 정리했어요.",
            selected_candidate_id=selected_id,
            goal=resolved_goal,
        )
    return AppResolutionState(
        status=AppResolutionStatus.NEEDS_APP_NAME,
        message="사용할 앱 이름을 말씀해 주세요.",
    )


def app_candidate_provider(http_client: httpx.Client | None = None) -> StructuredJsonProvider:
    """설정이 고른 공급자로 앱 후보용 클라이언트를 만든다."""

    return build_provider(
        contract=APP_CANDIDATE_CONTRACT,
        operation=_OPERATION,
        response_headroom=_RESPONSE_HEADROOM_TOKENS,
        http_client=http_client,
    )


@cache
def get_app_candidate_resolver() -> AppCandidateResolver:
    if settings.davi_use_stub_llm:
        return StubAppCandidateResolver()
    return RemoteAppCandidateResolver(app_candidate_provider())


def _normalize(value: str) -> str:
    return "".join(unicodedata.normalize("NFKC", value).casefold().split())


def _resolved_goal(goal: str, canonical_name: str) -> str:
    if _normalize(canonical_name) in _normalize(goal):
        return goal
    suffix = f" (사용할 앱: {canonical_name})"
    if len(goal) + len(suffix) <= MAX_VOICE_TEXT_LENGTH:
        return f"{goal}{suffix}"
    # 서버가 선택한 앱 정보가 잘리지 않게 긴 원목표 쪽만 계약 상한 안에서 줄인다.
    goal_limit = MAX_VOICE_TEXT_LENGTH - len(suffix) - 1
    return f"{goal[:goal_limit].rstrip()}…{suffix}"
