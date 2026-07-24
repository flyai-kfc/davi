"""LLM 클라이언트 경계. 스텁과 Gemini 두 구현.

여기서 HANDOFF §10 함정을 흡수한다:
- 스키마 400 시 무스키마 재시도 폴백(스키마 하나로 앱 전체가 죽는 사고 방지).
- 에러를 QUOTA/NETWORK/AUTH/SERVER/PARSE/BAD_REQUEST로 분류.

지금은 스텁만 완성했고 Gemini 호출부는 TODO다(키·엔드포인트 붙일 때 채운다).
스텁 덕에 키 없이도 서버가 뜨고 eval 하니스가 돈다(DAVI_USE_STUB_LLM).
"""

from __future__ import annotations

from .config import settings
from .schemas import ActionType, ErrorKind, GuideAction, NextStepRequest


class LlmError(Exception):
    def __init__(self, kind: ErrorKind, message: str) -> None:
        super().__init__(message)
        self.kind = kind
        self.message = message


class LlmClient:
    """구현 교체 지점. reason()이 다음 안내 하나를 돌려준다."""

    def reason(self, req: NextStepRequest) -> GuideAction:  # pragma: no cover - interface
        raise NotImplementedError


class StubLlmClient(LlmClient):
    """규칙 기반 가짜 추론. 계약·파이프라인 테스트용. LLM 품질을 대변하지 않는다."""

    def reason(self, req: NextStepRequest) -> GuideAction:
        clickable = [e for e in req.screen.elements if e.clickable and e.label]
        if not clickable:
            return GuideAction(
                expected_result=f"목표 '{req.goal}' 달성",
                evidence="누를 수 있는 요소가 화면에 없음",
                action=ActionType.WAIT,
                instruction="잠시 기다려 주세요.",
            )
        target = clickable[0]
        return GuideAction(
            expected_result=f"목표 '{req.goal}' 달성",
            evidence=f"화면에 '{target.label}' 버튼이 보임",
            action=ActionType.TAP,
            target_node_id=target.node_id,
            instruction=f"'{target.label}'을(를) 눌러 주세요.",
        )


class GeminiLlmClient(LlmClient):
    """Gemini 구조화 출력. TODO: 실제 호출.

    구현 시 반드시(server/CLAUDE.md):
    - propertyOrdering = expected_result -> evidence -> action.
    - enum에 빈 문자열 금지. 해당 없음은 필드 생략.
    - 스키마 400이면 무스키마로 1회 재시도.
    - 예외를 LlmError(kind=...)로 분류해 올린다.
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
