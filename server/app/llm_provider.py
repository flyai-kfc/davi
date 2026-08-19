"""LLM 공급자 경계. Gemini·OpenAI·Anthropic 중 하나에서 구조화 JSON 하나를 받아온다.

**왜 이 모듈이 있나.** 다음 안내(`llm.py`)·의도 대화(`intent_resolution.py`)·앱 후보
(`app_resolution.py`) 세 호출부가 같은 모양의 Gemini REST 클라이언트를 각자 복제하고 있었다.
공급자를 바꾸려면 세 곳을 같이 고쳐야 했고, 실제로 재시도·로그·오류 문구가 셋 사이에서
조금씩 어긋나 있었다. 여기 하나로 모아 **공급자 교체를 환경변수 한 줄**로 만든다
(`DAVI_LLM_PROVIDER`, HANDOFF §6 "아키텍처가 모델에 묶여 있지 않다").

호출부가 아는 것은 `StructuredJsonProvider` 하나뿐이다. 프롬프트를 주면 모델 원문 텍스트를
돌려주고, 실패는 두 가지로만 나온다:

- `ProviderCallError` — HTTP 오류. **400은 "스키마 거부"로 보고** 호출부가 계약을 프롬프트로
  옮겨 한 번 더 시도한다(무스키마 폴백). 스키마 하나로 앱 전체가 죽는 사고를 막는 자리다.
- `LlmError` — 네트워크 오류, 응답 본문 오류처럼 같은 요청을 다시 보내도 같은 자리에서
  실패하는 것. QUOTA/NETWORK/AUTH/SERVER/PARSE/BAD_REQUEST로 이미 분류돼 있다.

공급자마다 다른 것은 **요청 본문을 만드는 법과 응답에서 텍스트를 꺼내는 법 둘뿐**이다.
재시도 판단, 오류 분류, 로그 필드는 이 파일의 공통 코드에 있다.

## 공급자별 결정

- **Gemini** — 기존 동작 그대로. `thinkingConfig`로 thinking 상한을 주고 응답 예산은
  `thinking 상한 + 여유분`이다(thinking 토큰이 maxOutputTokens를 같이 먹는다, 실측 #203 이전).
- **OpenAI** — Responses API. `text.format`에 strict json_schema를 싣는다. thinking에 해당하는
  것은 `reasoning.effort`이고, 속도를 위해 기본을 낮게 둔다(`DAVI_OPENAI_REASONING_EFFORT`).
  reasoning 토큰도 `max_output_tokens`를 같이 먹으므로 effort가 켜져 있으면 여유분을 더 얹는다.
- **Anthropic** — Messages API. `output_config.format`에 같은 strict json_schema를 싣는다.
  **thinking은 보내지 않는다.** 우리 호출은 짧은 JSON 객체 하나를 뽑는 일이라 thinking이
  지연만 늘리고, 모델 세대마다 파라미터 규약이 달라(구형은 `budget_tokens`, 신형은 400) 여기서
  분기를 만들 값이 없다. 더 깊은 추론이 필요하면 모델을 올린다(haiku -> sonnet).
"""

from __future__ import annotations

import logging
import time
from abc import ABC, abstractmethod
from collections.abc import Mapping
from dataclasses import dataclass
from enum import StrEnum
from typing import Any, ClassVar

import httpx
from pydantic import BaseModel

from .config import settings
from .errors import LlmError
from .gemini_response import (
    GeminiCandidateError,
    GeminiCandidateErrorReason,
    extract_candidate_text,
)
from .gemini_schema import build_response_schema
from .json_schema import build_json_schema
from .observability import LLM_LOGGER_NAME, log_event
from .schemas import ErrorKind

_DEFAULT_RETRY_AFTER_SECONDS = 60
_REQUEST_TIMEOUT_SECONDS = 20.0
# reasoning이 켜진 OpenAI 호출에서 max_output_tokens에 얹는 여유분. reasoning 토큰도 이 예산을
# 같이 소모하므로, 다 써도 답 쓸 자리가 남게 한다(Gemini thinking 예산과 같은 이유).
_OPENAI_REASONING_HEADROOM = 2048

llm_logger = logging.getLogger(LLM_LOGGER_NAME)


class ProviderName(StrEnum):
    GEMINI = "gemini"
    OPENAI = "openai"
    ANTHROPIC = "anthropic"


class ProviderCallError(Exception):
    """HTTP 상태를 보존하는 공급자 호출 실패. 400 재시도 판단에 쓴다."""

    def __init__(self, status_code: int, retry_after_seconds: int | None = None) -> None:
        super().__init__(f"LLM 공급자 HTTP {status_code}")
        self.status_code = status_code
        self.retry_after_seconds = retry_after_seconds


class ProviderResponseReason(StrEnum):
    """응답 본문에서 텍스트를 못 꺼낸 이유. 로그 reason과 같은 값이다."""

    MAX_OUTPUT_TOKENS = "max_output_tokens"
    MISSING_CANDIDATE_TEXT = "missing_candidate_text"
    EMPTY_TEXT = "empty_text"
    REFUSAL = "refusal"


class _ProviderResponseError(Exception):
    """공급자별 추출기가 공통 코드에 이유만 알려주는 내부 예외."""

    def __init__(self, reason: ProviderResponseReason) -> None:
        super().__init__(reason.value)
        self.reason = reason


_RESPONSE_ERROR_MESSAGES: dict[ProviderResponseReason, str] = {
    ProviderResponseReason.MAX_OUTPUT_TOKENS: "응답이 출력 토큰 상한에서 잘림",
    ProviderResponseReason.MISSING_CANDIDATE_TEXT: "응답 본문에 텍스트 후보가 없음",
    ProviderResponseReason.EMPTY_TEXT: "응답 텍스트가 비어 있음",
    ProviderResponseReason.REFUSAL: "공급자가 요청을 거절함",
}


@dataclass(frozen=True)
class ResponseContract:
    """한 호출부의 응답 계약을 공급자별 스키마 표현으로 한 자리에서 만든다.

    같은 pydantic 선언에서 뽑으므로 둘이 어긋날 수 없다. import 시점에 만들어 두면 스키마를
    못 만드는 변경은 서버 기동 때 바로 드러난다.
    """

    name: str
    gemini_schema: dict
    json_schema: dict

    @classmethod
    def of(cls, name: str, model: type[BaseModel]) -> ResponseContract:
        return cls(
            name=name,
            gemini_schema=build_response_schema(model),
            json_schema=build_json_schema(model),
        )


class StructuredJsonProvider(ABC):
    """구조화 JSON 하나를 받아오는 공급자. 교체 지점은 여기다."""

    name: ClassVar[ProviderName]
    label: ClassVar[str]
    api_key_setting: ClassVar[str]

    def __init__(
        self,
        *,
        api_key: str,
        model: str,
        operation: str,
        contract: ResponseContract,
        max_output_tokens: int,
        http_client: httpx.Client | None = None,
    ) -> None:
        if not api_key:
            raise LlmError(ErrorKind.AUTH, f"{self.api_key_setting} 미설정")
        self.api_key = api_key
        self.model = model
        self.operation = operation
        self.contract = contract
        self.max_output_tokens = max_output_tokens
        self.http_client = http_client or httpx.Client(
            timeout=httpx.Timeout(_REQUEST_TIMEOUT_SECONDS)
        )

    @property
    def contract_schema(self) -> dict:
        """무스키마 폴백 프롬프트에 실을 계약. 공급자가 이해하는 표현으로 준다.

        기본은 표준 JSON Schema다. Gemini만 전용 표현을 쓰므로 거기서 재정의한다.
        """

        return self.contract.json_schema

    def generate_json(self, prompt: str, *, with_schema: bool, attempt: int, trigger: str) -> str:
        """프롬프트 하나를 보내고 모델 원문 텍스트를 돌려준다.

        `with_schema=False`는 공급자가 스키마를 거부했을 때의 폴백이다. 구조화 출력 설정을
        빼고 보내며, 계약은 호출부가 프롬프트에 실어 둔 상태다.
        """

        schema_mode = "schema" if with_schema else "fallback"
        started_at = time.perf_counter()
        log_event(
            llm_logger,
            logging.INFO,
            "llm_attempt_started",
            provider=self.name,
            model=self.model,
            operation=self.operation,
            provider_attempt=attempt,
            schema_mode=schema_mode,
            trigger=trigger,
        )

        try:
            response = self.http_client.post(**self._request(prompt, with_schema=with_schema))
        except httpx.TimeoutException as error:
            self._log_finished(
                started_at,
                attempt,
                schema_mode,
                trigger,
                outcome="error",
                error_kind=ErrorKind.NETWORK,
            )
            raise LlmError(ErrorKind.NETWORK, f"{self.label} API 요청 시간 초과") from error
        except httpx.RequestError as error:
            self._log_finished(
                started_at,
                attempt,
                schema_mode,
                trigger,
                outcome="error",
                error_kind=ErrorKind.NETWORK,
            )
            raise LlmError(ErrorKind.NETWORK, f"{self.label} API 네트워크 오류") from error

        if response.is_error:
            self._log_finished(
                started_at,
                attempt,
                schema_mode,
                trigger,
                outcome="error",
                provider_status_class=f"{response.status_code // 100}xx",
                error_kind=_error_kind_for(response.status_code),
            )
            raise ProviderCallError(response.status_code, _retry_after_seconds(response.headers))

        self._log_finished(
            started_at,
            attempt,
            schema_mode,
            trigger,
            outcome="success",
            provider_status_class=f"{response.status_code // 100}xx",
        )
        return self._response_text(response)

    def to_llm_error(self, error: ProviderCallError) -> LlmError:
        """HTTP 상태를 앱이 분기할 수 있는 오류 종류로 바꾼다."""

        if error.status_code == 429:
            return LlmError(
                ErrorKind.QUOTA,
                f"{self.label} API 사용량 또는 요청 한도 초과",
                (
                    error.retry_after_seconds
                    if error.retry_after_seconds is not None
                    else _DEFAULT_RETRY_AFTER_SECONDS
                ),
            )
        if error.status_code in {401, 403}:
            return LlmError(ErrorKind.AUTH, f"{self.label} API 인증 또는 권한 오류")
        if error.status_code == 400:
            return LlmError(ErrorKind.BAD_REQUEST, f"{self.label} API 요청 형식 오류")
        return LlmError(
            ErrorKind.SERVER,
            f"{self.label} API 서버 오류 (HTTP {error.status_code})",
        )

    def _response_text(self, response: httpx.Response) -> str:
        try:
            payload = response.json()
        except ValueError as error:
            raise self._response_error(ProviderResponseReason.MISSING_CANDIDATE_TEXT) from error
        try:
            return self._extract_text(payload)
        except _ProviderResponseError as error:
            raise self._response_error(error.reason) from error

    def _response_error(self, reason: ProviderResponseReason) -> LlmError:
        log_event(
            llm_logger,
            logging.WARNING,
            "llm_response_error",
            provider=self.name,
            operation=self.operation,
            reason=reason,
            action="fail",
        )
        return LlmError(ErrorKind.PARSE, f"{self.label} {_RESPONSE_ERROR_MESSAGES[reason]}")

    def _log_finished(
        self,
        started_at: float,
        attempt: int,
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
            "llm_attempt_finished",
            provider=self.name,
            model=self.model,
            operation=self.operation,
            provider_attempt=attempt,
            schema_mode=schema_mode,
            trigger=trigger,
            outcome=outcome,
            provider_status_class=provider_status_class,
            error_kind=error_kind,
            duration_ms=round((time.perf_counter() - started_at) * 1000, 2),
        )

    @abstractmethod
    def _request(self, prompt: str, *, with_schema: bool) -> dict[str, Any]:
        """`httpx.Client.post`에 그대로 넘길 인자(url·headers·json)."""

    @abstractmethod
    def _extract_text(self, payload: Any) -> str:
        """응답 본문에서 모델 텍스트 하나를 꺼낸다. 실패는 `_ProviderResponseError`."""


class GeminiProvider(StructuredJsonProvider):
    name = ProviderName.GEMINI
    label = "Gemini"
    api_key_setting = "GEMINI_API_KEY"

    _BASE_URL = "https://generativelanguage.googleapis.com/v1beta"

    def __init__(self, *, thinking_budget: int = 0, **kwargs: Any) -> None:
        super().__init__(**kwargs)
        self.thinking_budget = thinking_budget

    @property
    def contract_schema(self) -> dict:
        return self.contract.gemini_schema

    def _request(self, prompt: str, *, with_schema: bool) -> dict[str, Any]:
        generation_config: dict[str, Any] = {
            "responseMimeType": "application/json",
            "maxOutputTokens": self.max_output_tokens,
            # thinking이 없는 모델(flash-lite 등)에 보내도 조용히 무시된다(실측).
            "thinkingConfig": {"thinkingBudget": self.thinking_budget},
        }
        if with_schema:
            generation_config["responseSchema"] = self.contract.gemini_schema
        return {
            "url": f"{self._BASE_URL}/models/{self.model}:generateContent",
            "headers": {"x-goog-api-key": self.api_key},
            "json": {
                "contents": [{"parts": [{"text": prompt}]}],
                "generationConfig": generation_config,
            },
        }

    def _extract_text(self, payload: Any) -> str:
        try:
            return extract_candidate_text(payload)
        except GeminiCandidateError as error:
            raise _ProviderResponseError(_GEMINI_REASONS[error.reason]) from error


_GEMINI_REASONS: dict[GeminiCandidateErrorReason, ProviderResponseReason] = {
    GeminiCandidateErrorReason.MAX_TOKENS: ProviderResponseReason.MAX_OUTPUT_TOKENS,
    GeminiCandidateErrorReason.MISSING_TEXT: ProviderResponseReason.MISSING_CANDIDATE_TEXT,
    GeminiCandidateErrorReason.EMPTY_TEXT: ProviderResponseReason.EMPTY_TEXT,
}


class OpenAiProvider(StructuredJsonProvider):
    name = ProviderName.OPENAI
    label = "OpenAI"
    api_key_setting = "OPENAI_API_KEY"

    _BASE_URL = "https://api.openai.com/v1"

    def __init__(self, *, reasoning_effort: str = "low", **kwargs: Any) -> None:
        super().__init__(**kwargs)
        self.reasoning_effort = reasoning_effort

    def _request(self, prompt: str, *, with_schema: bool) -> dict[str, Any]:
        body: dict[str, Any] = {
            "model": self.model,
            "input": [{"role": "user", "content": prompt}],
            "max_output_tokens": self.max_output_tokens,
            "reasoning": {"effort": self.reasoning_effort},
        }
        if with_schema:
            body["text"] = {
                "format": {
                    "type": "json_schema",
                    "name": self.contract.name,
                    "strict": True,
                    "schema": self.contract.json_schema,
                }
            }
        return {
            "url": f"{self._BASE_URL}/responses",
            "headers": {"Authorization": f"Bearer {self.api_key}"},
            "json": body,
        }

    def _extract_text(self, payload: Any) -> str:
        if not isinstance(payload, dict):
            raise _ProviderResponseError(ProviderResponseReason.MISSING_CANDIDATE_TEXT)

        # 예산을 다 쓰면 status=incomplete로 끝나고 output에는 잘린 조각만 남는다. 그 조각을
        # 파싱해서 "계약 위반"으로 잘못 분류하지 않도록 여기서 먼저 걸러낸다.
        incomplete = payload.get("incomplete_details") or {}
        if incomplete.get("reason") == "max_output_tokens":
            raise _ProviderResponseError(ProviderResponseReason.MAX_OUTPUT_TOKENS)

        chunks: list[str] = []
        for item in payload.get("output") or []:
            if not isinstance(item, dict) or item.get("type") != "message":
                continue
            for block in item.get("content") or []:
                if not isinstance(block, dict):
                    continue
                if block.get("type") == "refusal":
                    raise _ProviderResponseError(ProviderResponseReason.REFUSAL)
                if block.get("type") == "output_text" and isinstance(block.get("text"), str):
                    chunks.append(block["text"])

        if not chunks:
            raise _ProviderResponseError(ProviderResponseReason.MISSING_CANDIDATE_TEXT)
        text = "".join(chunks)
        if not text.strip():
            raise _ProviderResponseError(ProviderResponseReason.EMPTY_TEXT)
        return text


class AnthropicProvider(StructuredJsonProvider):
    name = ProviderName.ANTHROPIC
    label = "Anthropic"
    api_key_setting = "ANTHROPIC_API_KEY"

    _BASE_URL = "https://api.anthropic.com/v1"
    _API_VERSION = "2023-06-01"

    def _request(self, prompt: str, *, with_schema: bool) -> dict[str, Any]:
        body: dict[str, Any] = {
            "model": self.model,
            "max_tokens": self.max_output_tokens,
            "messages": [{"role": "user", "content": prompt}],
        }
        if with_schema:
            body["output_config"] = {
                "format": {"type": "json_schema", "schema": self.contract.json_schema}
            }
        return {
            "url": f"{self._BASE_URL}/messages",
            "headers": {
                "x-api-key": self.api_key,
                "anthropic-version": self._API_VERSION,
            },
            "json": body,
        }

    def _extract_text(self, payload: Any) -> str:
        if not isinstance(payload, dict):
            raise _ProviderResponseError(ProviderResponseReason.MISSING_CANDIDATE_TEXT)

        # 안전 분류기가 거절하면 HTTP 200에 stop_reason=refusal로 온다. content를 먼저 읽으면
        # 빈 배열을 "본문 없음"으로 오분류하므로 stop_reason을 먼저 본다.
        stop_reason = payload.get("stop_reason")
        if stop_reason == "refusal":
            raise _ProviderResponseError(ProviderResponseReason.REFUSAL)
        if stop_reason == "max_tokens":
            raise _ProviderResponseError(ProviderResponseReason.MAX_OUTPUT_TOKENS)

        chunks = [
            block["text"]
            for block in payload.get("content") or []
            if isinstance(block, dict)
            and block.get("type") == "text"
            and isinstance(block.get("text"), str)
        ]
        if not chunks:
            raise _ProviderResponseError(ProviderResponseReason.MISSING_CANDIDATE_TEXT)
        text = "".join(chunks)
        if not text.strip():
            raise _ProviderResponseError(ProviderResponseReason.EMPTY_TEXT)
        return text


def build_provider(
    *,
    contract: ResponseContract,
    operation: str,
    response_headroom: int,
    http_client: httpx.Client | None = None,
) -> StructuredJsonProvider:
    """설정이 고른 공급자를 만든다. 호출부는 어느 공급자인지 알 필요가 없다.

    `response_headroom`은 "정상 응답 하나에 필요한 출력 토큰"이다. 여기에 공급자가 보이지 않는
    추론에 쓰는 몫을 더해 출력 상한을 정한다 — 그 몫이 예산을 다 먹어 답이 잘리는 실패가
    실기기에서 재현된 적이 있다(2026-08-18). `DAVI_MAX_OUTPUT_TOKENS`로 통째로 덮을 수 있다.
    """

    provider = ProviderName(settings.davi_llm_provider)
    model = settings.resolved_model
    override = settings.davi_max_output_tokens

    if provider is ProviderName.GEMINI:
        thinking_budget = settings.davi_gemini_thinking_budget
        return GeminiProvider(
            api_key=settings.gemini_api_key.get_secret_value(),
            model=model,
            operation=operation,
            contract=contract,
            max_output_tokens=override or thinking_budget + response_headroom,
            thinking_budget=thinking_budget,
            http_client=http_client,
        )

    if provider is ProviderName.OPENAI:
        effort = settings.davi_openai_reasoning_effort
        reasoning_headroom = 0 if effort == "none" else _OPENAI_REASONING_HEADROOM
        return OpenAiProvider(
            api_key=settings.openai_api_key.get_secret_value(),
            model=model,
            operation=operation,
            contract=contract,
            max_output_tokens=override or reasoning_headroom + response_headroom,
            reasoning_effort=effort,
            http_client=http_client,
        )

    return AnthropicProvider(
        api_key=settings.anthropic_api_key.get_secret_value(),
        model=model,
        operation=operation,
        contract=contract,
        # thinking을 보내지 않으므로 정상 응답 몫만 있으면 된다(모듈 문서 참고).
        max_output_tokens=override or response_headroom,
        http_client=http_client,
    )


def _retry_after_seconds(headers: Mapping[str, str]) -> int | None:
    value = headers.get("Retry-After")
    if value is None:
        return None
    try:
        return max(0, int(value))
    except ValueError:
        return None


def _error_kind_for(status_code: int) -> ErrorKind:
    if status_code == 429:
        return ErrorKind.QUOTA
    if status_code in {401, 403}:
        return ErrorKind.AUTH
    if status_code == 400:
        return ErrorKind.BAD_REQUEST
    return ErrorKind.SERVER
