"""LLM 클라이언트 경계. 스텁과 Gemini 두 구현.

여기서 HANDOFF §10 함정을 흡수한다:
- 스키마 400 시 무스키마 재시도 폴백(스키마 하나로 앱 전체가 죽는 사고 방지).
- 에러를 QUOTA/NETWORK/AUTH/SERVER/PARSE/BAD_REQUEST로 분류.

development 스텁 덕에 키 없이도 서버가 뜨고 eval 하니스가 돈다(DAVI_USE_STUB_LLM).
"""

from __future__ import annotations

from collections.abc import Mapping
from functools import cache
from typing import Any

import httpx

from .config import settings
from .errors import LlmError
from .gemini_schema import build_response_schema
from .prompt import build_prompt, fallback_prompt
from .schemas import (
    ActionType,
    ErrorKind,
    GuideAction,
    NextStepRequest,
    SwipeDirection,
)

# 모델 선언에서 뽑은 Gemini 응답 스키마. propertyOrdering이 곧 추론 순서다(C1).
# import 시점에 만들어 두므로, 스키마를 못 만드는 변경은 서버 기동 때 바로 드러난다.
GUIDE_ACTION_SCHEMA = build_response_schema(GuideAction)
_GEMINI_API_BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
_DEFAULT_RETRY_AFTER_SECONDS = 60


class LlmClient:
    """구현 교체 지점. reason()이 다음 안내 하나를 돌려준다."""

    def reason(self, req: NextStepRequest) -> GuideAction:  # pragma: no cover - interface
        raise NotImplementedError


class _GeminiHttpError(Exception):
    """스키마 400 재시도 여부를 정하기 위해 HTTP 정보를 보존하는 내부 예외."""

    def __init__(self, status_code: int, retry_after_seconds: int | None = None) -> None:
        super().__init__(f"Gemini API HTTP {status_code}")
        self.status_code = status_code
        self.retry_after_seconds = retry_after_seconds


class StubLlmClient(LlmClient):
    """규칙 기반 가짜 추론. 계약·파이프라인 테스트용. LLM 품질을 대변하지 않는다.

    우선순위: 누를 수 있는 것을 누른다 -> 없으면 더 넘겨본다 -> 그래도 없으면 실패로 알린다.
    "기다리기"는 돌려주지 않는다(C6). 정체 판정은 앱의 폴링 담당이다.
    """

    def reason(self, req: NextStepRequest) -> GuideAction:
        expected = f"목표 '{req.goal}' 달성"

        clickable = [e for e in req.screen.elements if e.clickable and e.label]
        if clickable:
            target = clickable[0]
            return GuideAction(
                expected_result=expected,
                evidence=f"화면에 '{target.label}' 버튼이 보임",
                action_type=ActionType.TAP,
                target_node_id=target.node_id,
                instruction=f"'{target.label}'을(를) 눌러 주세요.",
            )

        scrollable = next(
            (e for e in req.screen.elements if e.can_scroll_forward),
            None,
        )
        if scrollable is not None:
            direction = scrollable.next_swipe_direction or SwipeDirection.UP
            return GuideAction(
                expected_result=expected,
                evidence="누를 수 있는 요소가 없고 더 넘길 수 있는 목록이 있음",
                action_type=ActionType.SCROLL,
                direction=direction,
                target_node_id=scrollable.node_id,
                instruction="화면을 쓸어서 더 내려 보세요.",
            )

        # 막힘 상태의 제품적 종착지는 아직 미설계다(SPEC §11). 스텁은 조용히 끝내지 않고 알린다.
        raise LlmError(
            ErrorKind.SERVER,
            "스텁이 다음 동작을 정할 수 없다: 누를 수 있는 요소도, 넘길 목록도 없음",
        )


class GeminiLlmClient(LlmClient):
    """Gemini REST 구조화 출력 클라이언트.

    `http_client`는 테스트에서 MockTransport를 주입하기 위한 경계다. 운영에서는 재사용 가능한
    httpx.Client를 하나 생성한다.
    """

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

    def reason(self, req: NextStepRequest) -> GuideAction:
        prompt = build_prompt(req)
        try:
            response_text = self._call_once(prompt, with_schema=True)
        except _GeminiHttpError as error:
            if error.status_code != 400:
                raise self._to_llm_error(error) from error
            # Gemini가 responseSchema를 거부한 경우에만, 계약을 프롬프트로 옮겨 한 번 복구한다.
            try:
                response_text = self._call_once(
                    fallback_prompt(prompt, GUIDE_ACTION_SCHEMA),
                    with_schema=False,
                )
            except _GeminiHttpError as fallback_error:
                raise self._to_llm_error(fallback_error) from fallback_error

        return self._parse_action(response_text)

    def _call_once(self, prompt: str, *, with_schema: bool) -> str:
        generation_config: dict[str, Any] = {"responseMimeType": "application/json"}
        if with_schema:
            generation_config["responseSchema"] = GUIDE_ACTION_SCHEMA
        payload = {
            "contents": [{"parts": [{"text": prompt}]}],
            "generationConfig": generation_config,
        }
        try:
            response = self.http_client.post(
                f"{_GEMINI_API_BASE_URL}/models/{self.model}:generateContent",
                headers={"x-goog-api-key": self.api_key},
                json=payload,
            )
        except httpx.TimeoutException as error:
            raise LlmError(ErrorKind.NETWORK, "Gemini API 요청 시간 초과") from error
        except httpx.RequestError as error:
            raise LlmError(ErrorKind.NETWORK, "Gemini API 네트워크 오류") from error

        if response.is_error:
            raise _GeminiHttpError(
                response.status_code,
                _retry_after_seconds(response.headers),
            )
        return _response_text(response)

    def _parse_action(self, response_text: str) -> GuideAction:
        try:
            return GuideAction.model_validate_json(_strip_json_fence(response_text))
        except (ValueError, TypeError) as error:
            raise LlmError(ErrorKind.PARSE, "Gemini 응답이 GuideAction 계약과 맞지 않음") from error

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
        candidates = payload["candidates"]
        parts = candidates[0]["content"]["parts"]
        text = "".join(part["text"] for part in parts if "text" in part)
    except (IndexError, KeyError, TypeError, ValueError) as error:
        raise LlmError(ErrorKind.PARSE, "Gemini 응답 본문에 텍스트 후보가 없음") from error
    if not text.strip():
        raise LlmError(ErrorKind.PARSE, "Gemini 응답 텍스트가 비어 있음")
    return text


def _retry_after_seconds(headers: Mapping[str, str]) -> int | None:
    value = headers.get("Retry-After")
    if value is None:
        return None
    try:
        return max(0, int(value))
    except ValueError:
        return None


def _strip_json_fence(text: str) -> str:
    """무스키마 폴백에서 모델이 드물게 붙이는 Markdown fence를 허용한다."""

    stripped = text.strip()
    if not stripped.startswith("```"):
        return stripped
    lines = stripped.splitlines()
    if len(lines) >= 3 and lines[-1].strip() == "```":
        return "\n".join(lines[1:-1]).strip()
    return stripped


@cache
def get_llm_client() -> LlmClient:
    """설정은 프로세스 기동 시 고정되므로 HTTP 연결 풀도 프로세스 동안 재사용한다."""

    if settings.davi_use_stub_llm:
        return StubLlmClient()
    return GeminiLlmClient(settings.gemini_api_key.get_secret_value(), settings.davi_model)
