"""LLM 앱 후보 생성과 기기 대조 결과의 무상태 최종 판정."""

from __future__ import annotations

import json
import logging
import re
import time
import unicodedata
from collections.abc import Mapping
from functools import cache
from typing import Any

import httpx
from pydantic import BaseModel, Field, ValidationError, field_validator

from .app_resolution_prompt import build_app_candidate_prompt, build_app_candidate_repair_prompt
from .config import settings
from .errors import LlmError
from .gemini_response import (
    GeminiCandidateError,
    GeminiCandidateErrorReason,
    extract_candidate_text,
    strip_json_fence,
)
from .gemini_schema import build_response_schema
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

_GEMINI_API_BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
_MAX_OUTPUT_TOKENS = 1024
_DEFAULT_RETRY_AFTER_SECONDS = 60
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


APP_CANDIDATE_SCHEMA = build_response_schema(_AppCandidateDecision)


class AppCandidateResolver:
    def candidates(self, goal: str) -> AppCandidatePlan:  # pragma: no cover - interface
        raise NotImplementedError


class StubAppCandidateResolver(AppCandidateResolver):
    """키 없는 계약 스모크. 앱 의미 품질이나 설치 여부를 주장하지 않는다."""

    def candidates(self, goal: str) -> AppCandidatePlan:
        return AppCandidatePlan(capability="unknown", candidates=[])


class _GeminiHttpError(Exception):
    def __init__(self, status_code: int, retry_after_seconds: int | None = None) -> None:
        super().__init__(f"Gemini API HTTP {status_code}")
        self.status_code = status_code
        self.retry_after_seconds = retry_after_seconds


class GeminiAppCandidateResolver(AppCandidateResolver):
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

    def candidates(self, goal: str) -> AppCandidatePlan:
        prompt = build_app_candidate_prompt(goal)
        try:
            text = self._call_once(prompt, with_schema=True, attempt=1, trigger="initial")
        except _GeminiHttpError as error:
            if error.status_code != 400:
                raise self._to_llm_error(error) from error
            try:
                text = self._call_once(
                    fallback_prompt(prompt, APP_CANDIDATE_SCHEMA),
                    with_schema=False,
                    attempt=2,
                    trigger="schema_recovery",
                )
            except _GeminiHttpError as fallback_error:
                raise self._to_llm_error(fallback_error) from fallback_error
            return self._parse(text)

        try:
            return self._parse(text)
        except LlmError as error:
            if error.kind is not ErrorKind.PARSE:
                raise
            try:
                repaired = self._call_once(
                    build_app_candidate_repair_prompt(prompt),
                    with_schema=True,
                    attempt=2,
                    trigger="contract_recovery",
                )
            except _GeminiHttpError as repair_error:
                raise self._to_llm_error(repair_error) from repair_error
            return self._parse(repaired)

    def _call_once(self, prompt: str, *, with_schema: bool, attempt: int, trigger: str) -> str:
        generation_config: dict[str, Any] = {
            "responseMimeType": "application/json",
            "maxOutputTokens": _MAX_OUTPUT_TOKENS,
        }
        if with_schema:
            generation_config["responseSchema"] = APP_CANDIDATE_SCHEMA
        started_at = time.perf_counter()
        schema_mode = "schema" if with_schema else "fallback"
        log_event(
            llm_logger,
            logging.INFO,
            "gemini_attempt_started",
            model=self.model,
            operation="app_candidates",
            provider_attempt=attempt,
            schema_mode=schema_mode,
            trigger=trigger,
        )
        try:
            response = self.http_client.post(
                f"{_GEMINI_API_BASE_URL}/models/{self.model}:generateContent",
                headers={"x-goog-api-key": self.api_key},
                json={
                    "contents": [{"parts": [{"text": prompt}]}],
                    "generationConfig": generation_config,
                },
            )
        except httpx.RequestError as error:
            self._log_finished(
                started_at, attempt, schema_mode, trigger, "error", ErrorKind.NETWORK
            )
            raise LlmError(ErrorKind.NETWORK, "Gemini API 네트워크 오류") from error
        if response.is_error:
            kind = _provider_error_kind(response.status_code)
            self._log_finished(
                started_at,
                attempt,
                schema_mode,
                trigger,
                "error",
                kind,
                provider_status_class=f"{response.status_code // 100}xx",
            )
            raise _GeminiHttpError(response.status_code, _retry_after_seconds(response.headers))
        self._log_finished(
            started_at,
            attempt,
            schema_mode,
            trigger,
            "success",
            provider_status_class=f"{response.status_code // 100}xx",
        )
        return _response_text(response)

    def _parse(self, response_text: str) -> AppCandidatePlan:
        try:
            payload = json.loads(strip_json_fence(response_text))
            return _AppCandidateDecision.model_validate(payload).to_plan()
        except (json.JSONDecodeError, ValidationError, TypeError, ValueError) as error:
            log_event(
                llm_logger,
                logging.WARNING,
                "gemini_parse_error",
                operation="app_candidates",
                reason="invalid_app_candidates",
                action="fail",
            )
            raise LlmError(ErrorKind.PARSE, "Gemini 앱 후보 응답 계약 오류") from error

    def _log_finished(
        self,
        started_at: float,
        attempt: int,
        schema_mode: str,
        trigger: str,
        outcome: str,
        error_kind: ErrorKind | None = None,
        provider_status_class: str | None = None,
    ) -> None:
        log_event(
            llm_logger,
            logging.INFO if error_kind is None else logging.WARNING,
            "gemini_attempt_finished",
            model=self.model,
            operation="app_candidates",
            provider_attempt=attempt,
            schema_mode=schema_mode,
            trigger=trigger,
            outcome=outcome,
            provider_status_class=provider_status_class,
            duration_ms=round((time.perf_counter() - started_at) * 1000, 2),
            error_kind=error_kind.value if error_kind else None,
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


@cache
def get_app_candidate_resolver() -> AppCandidateResolver:
    if settings.davi_use_stub_llm:
        return StubAppCandidateResolver()
    return GeminiAppCandidateResolver(
        settings.gemini_api_key.get_secret_value(),
        settings.davi_model,
    )


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


def _response_text(response: httpx.Response) -> str:
    try:
        return extract_candidate_text(response.json())
    except (ValueError, GeminiCandidateError) as error:
        if (
            isinstance(error, GeminiCandidateError)
            and error.reason is GeminiCandidateErrorReason.MAX_TOKENS
        ):
            reason = "max_output_tokens"
        else:
            reason = "missing_candidate_text"
        log_event(
            llm_logger,
            logging.WARNING,
            "gemini_response_error",
            operation="app_candidates",
            reason=reason,
            action="fail",
        )
        raise LlmError(ErrorKind.PARSE, "Gemini 앱 후보 응답 본문 오류") from error


def _retry_after_seconds(headers: Mapping[str, str]) -> int | None:
    try:
        return max(0, int(headers["Retry-After"]))
    except (KeyError, ValueError):
        return None


def _provider_error_kind(status_code: int) -> ErrorKind:
    if status_code == 429:
        return ErrorKind.QUOTA
    if status_code in {401, 403}:
        return ErrorKind.AUTH
    if status_code == 400:
        return ErrorKind.BAD_REQUEST
    return ErrorKind.SERVER
