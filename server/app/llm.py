"""LLM 클라이언트 경계. 스텁과 Gemini 두 구현.

여기서 HANDOFF §10 함정을 흡수한다:
- 스키마 400 시 무스키마 재시도 폴백(스키마 하나로 앱 전체가 죽는 사고 방지).
- 에러를 QUOTA/NETWORK/AUTH/SERVER/PARSE/BAD_REQUEST로 분류.

지금은 스텁만 완성했고 Gemini 호출부는 TODO다(키·엔드포인트 붙일 때 채운다).
스텁 덕에 키 없이도 서버가 뜨고 eval 하니스가 돈다(DAVI_USE_STUB_LLM).
"""

from __future__ import annotations

from .config import settings
from .gemini_schema import build_response_schema
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


class LlmError(Exception):
    def __init__(
        self,
        kind: ErrorKind,
        message: str,
        retry_after_seconds: int | None = None,
    ) -> None:
        super().__init__(message)
        self.kind = kind
        self.message = message
        self.retry_after_seconds = retry_after_seconds


class LlmClient:
    """구현 교체 지점. reason()이 다음 안내 하나를 돌려준다."""

    def reason(self, req: NextStepRequest) -> GuideAction:  # pragma: no cover - interface
        raise NotImplementedError


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
    """Gemini 구조화 출력. TODO: 실제 호출.

    구현 시 반드시(server/CLAUDE.md):
    - 응답 스키마는 GUIDE_ACTION_SCHEMA를 쓴다(손으로 propertyOrdering을 적지 않는다).
    - 스키마 400이면 무스키마로 1회 재시도.
    - 예외를 LlmError(kind=...)로 분류해 올린다. QUOTA면 retry_after_seconds도 채운다.
    """

    def __init__(self, api_key: str, model: str) -> None:
        if not api_key:
            raise LlmError(ErrorKind.AUTH, "GEMINI_API_KEY 미설정")
        self.api_key = api_key
        self.model = model

    def reason(self, req: NextStepRequest) -> GuideAction:  # pragma: no cover - TODO
        raise LlmError(ErrorKind.SERVER, "Gemini 호출 미구현 — DAVI_USE_STUB_LLM=true 로 실행")


def get_llm_client() -> LlmClient:
    if settings.davi_use_stub_llm:
        return StubLlmClient()
    return GeminiLlmClient(settings.gemini_api_key, settings.davi_model)
