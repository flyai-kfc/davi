"""사용자 LLM(Gemini)·평가자 LLM(Claude)의 얇은 경계.

핑퐁 자체는 여기를 지나지 않는다 — 그건 `sut.py`다.
"""

from __future__ import annotations

import json
import os
import time
from typing import Any, TypeVar

import httpx
from pydantic import BaseModel, ValidationError

_GEMINI_BASE = "https://generativelanguage.googleapis.com/v1beta"

T = TypeVar("T", bound=BaseModel)


class LlmError(RuntimeError):
    pass


class GeminiClient:
    """사용자 역할. 구조화 출력으로만 받는다."""

    def __init__(
        self,
        *,
        api_key: str | None = None,
        model: str | None = None,
        timeout: float = 60.0,
        max_retries: int = 6,
        client: httpx.Client | None = None,
    ) -> None:
        self.api_key = (api_key or os.getenv("GEMINI_API_KEY", "")).strip()
        self._max_retries = max_retries
        self.model = (
            model or os.getenv("PINGPONG_USER_MODEL", "")
        ).strip() or "gemini-3.5-flash-lite"
        if not self.api_key:
            raise LlmError("GEMINI_API_KEY가 없다. 사용자 LLM을 돌릴 수 없다.")
        self._client = client or httpx.Client(timeout=timeout)

    def generate_json(
        self,
        prompt: str,
        schema: dict[str, Any],
        *,
        system: str | None = None,
        temperature: float = 1.0,
    ) -> dict[str, Any]:
        payload: dict[str, Any] = {
            "contents": [{"parts": [{"text": prompt}]}],
            "generationConfig": {
                "responseMimeType": "application/json",
                "responseSchema": schema,
                # 매 실행 첫 발화가 겹치지 않아야 해서 사용자 LLM은 온도를 낮추지 않는다.
                "temperature": temperature,
            },
        }
        if system is not None:
            payload["systemInstruction"] = {"parts": [{"text": system}]}

        # 분당 한도(RPM)에 걸리는 것은 정상이다 — 한 번의 429로 실행 전체를 죽이지 않는다.
        # 한 번 죽으면 이미 끝낸 시나리오까지 전부 버려진다(실측으로 두 번 겪었다).
        for attempt in range(self._max_retries + 1):
            try:
                response = self._client.post(
                    f"{_GEMINI_BASE}/models/{self.model}:generateContent",
                    headers={"x-goog-api-key": self.api_key},
                    json=payload,
                )
            except httpx.RequestError as error:
                if attempt >= self._max_retries:
                    raise LlmError(f"사용자 LLM 네트워크 오류: {error}") from error
                time.sleep(2**attempt)
                continue

            if (
                response.status_code in (429, 500, 502, 503)
                and attempt < self._max_retries
            ):
                time.sleep(_gemini_retry_delay(response, attempt))
                continue
            break

        if response.is_error:
            raise LlmError(
                f"사용자 LLM HTTP {response.status_code}: {response.text[:200]}"
            )

        try:
            candidates = response.json()["candidates"]
            text = "".join(
                part.get("text", "") for part in candidates[0]["content"]["parts"]
            )
            return json.loads(_strip_fence(text))
        except (KeyError, IndexError, TypeError, ValueError) as error:
            raise LlmError("사용자 LLM 응답이 구조화 계약과 맞지 않는다") from error

    def close(self) -> None:
        self._client.close()


class ClaudeJudge:
    """평가자 역할. 온도 조절 없이 구조화 출력만 쓴다(Opus 5는 sampling 파라미터를 받지 않는다)."""

    def __init__(
        self,
        *,
        api_key: str | None = None,
        model: str | None = None,
        max_tokens: int = 8000,
        base_url: str | None = None,
    ) -> None:
        try:
            import anthropic
        except ImportError as error:  # pragma: no cover
            raise LlmError(
                "anthropic 패키지가 없다. requirements.txt를 설치한다."
            ) from error
        # 학교 LLM 게이트웨이처럼 Anthropic 네이티브 호환 엔드포인트를 쓸 수 있다.
        # SDK가 base_url 뒤에 /v1/messages를 붙이므로 게이트웨이 접두어까지만 준다.
        self.base_url = (
            base_url or os.getenv("PINGPONG_JUDGE_BASE_URL", "")
        ).strip() or None
        # `GeminiClient`와 같은 결로 여기서 닫는다. SDK는 빈 문자열을 부재로 접고
        # (`api_key = os.environ.get(...) or None`) **호출 시점에** `TypeError`를 던지는데,
        # `cmd_run`·`cmd_judge`는 `LlmError`만 잡으므로 raw 트레이스백이 뜬다.
        if not (api_key or os.getenv("ANTHROPIC_API_KEY", "")).strip():
            raise LlmError("ANTHROPIC_API_KEY가 없다. 평가자 LLM을 돌릴 수 없다.")
        self._client = anthropic.Anthropic(
            **({"base_url": self.base_url} if self.base_url else {})
        )
        self.model = (
            model or os.getenv("PINGPONG_JUDGE_MODEL", "")
        ).strip() or "claude-opus-5"
        self._max_tokens = max_tokens
        # 게이트웨이 크레딧은 상한이 있다. 실행마다 쓴 양을 남겨야 다음 실행을 계획할 수 있다.
        self.input_tokens = 0
        self.output_tokens = 0

    def parse(
        self, prompt: str, response_model: type[T], *, system: str | None = None
    ) -> T:
        kwargs: dict[str, Any] = {
            "model": self.model,
            "max_tokens": self._max_tokens,
            "messages": [{"role": "user", "content": prompt}],
            "output_format": response_model,
        }
        if system is not None:
            kwargs["system"] = system
        try:
            response = self._client.messages.parse(**kwargs)
        except (
            Exception
        ) as error:  # SDK 예외 계층은 여기서 나누지 않는다 — 실행을 세우는 게 목적이다.
            raise LlmError(f"평가자 LLM 호출 실패: {error}") from error
        usage = getattr(response, "usage", None)
        if usage is not None:
            self.input_tokens += getattr(usage, "input_tokens", 0) or 0
            self.output_tokens += getattr(usage, "output_tokens", 0) or 0
        parsed = response.parsed_output
        if parsed is None:
            raise LlmError("평가자 LLM이 구조화 출력을 내지 않았다")
        try:
            return response_model.model_validate(parsed)
        except ValidationError as error:
            raise LlmError(f"평가자 LLM 출력이 스키마와 다르다: {error}") from error


def _strip_fence(text: str) -> str:
    stripped = text.strip()
    if stripped.startswith("```"):
        stripped = stripped.split("\n", 1)[-1]
        stripped = stripped.removesuffix("```")
    return stripped.strip()


# 게이트웨이 청구 실측으로 맞춘 보정 계수(2026-08-21).
# 초기 식 (입력 + 출력×5)/100 은 누적 4,452를 예측했는데 실제 청구는 1,910이었다.
# 소액 1건에서 맞춘 식을 큰 실행에 그대로 늘린 것이 화근이었다 — 계수는 누적 청구로 다시 맞춘다.
_CREDIT_CALIBRATION = 0.43


def estimate_credits(input_tokens: int, output_tokens: int) -> float:
    """학교 게이트웨이 크레딧 추정.

    (입력 + 출력×5) / 100 × 보정계수. 가중치 5는 Sonnet 5의 입력·출력 단가비다.
    **청구 총액 한 번에 맞춘 값이라 ±30%로 본다.** 실행마다 실제 차감액과 대조해 고친다.
    """

    return (input_tokens + output_tokens * 5) / 100 * _CREDIT_CALIBRATION


def _gemini_retry_delay(response: httpx.Response, attempt: int) -> float:
    """Gemini가 RetryInfo로 알려 준 대기 시간을 쓰고, 없으면 지수 백오프."""

    try:
        for detail in response.json()["error"].get("details", []):
            raw = detail.get("retryDelay")
            if isinstance(raw, str) and raw.endswith("s"):
                return min(float(raw[:-1]) + 1, 65)
    except (KeyError, TypeError, ValueError):
        pass
    return min(2**attempt, 60)
