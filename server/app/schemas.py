"""앱 <-> 서버 계약. 이 스키마가 곧 API 계약이다(테스트로 고정한다).

주의(HANDOFF §10, server/CLAUDE.md):
- 근거 필드(expected_result, evidence)를 action보다 앞에 뒀다. 다만 이 순서는 **아직 Gemini에
  전달되지 않는다** — `propertyOrdering`은 표준 JSON Schema가 아닌 Gemini 전용 키이고,
  llm.py의 Gemini 호출부가 아직 stub이라 연결이 없다. 지금은 문서상 약속일 뿐이다.
  server/CLAUDE.md "API 계약 결정" C1 참고(필드 순서에서 코드로 생성할 것).
- "해당 없음"은 빈 문자열이 아니라 필드 생략으로 표현한다.

이 파일의 미확정 사항(개명·누락 필드·WAIT 제거 등)은 server/CLAUDE.md "API 계약 결정" 표에 있다.
"""

from __future__ import annotations

from enum import StrEnum

from pydantic import BaseModel, Field


class ActionType(StrEnum):
    TAP = "tap"
    SCROLL = "scroll"
    TYPE = "type"
    OPEN_QUICK_SETTINGS = "open_quick_settings"
    WAIT = "wait"
    DONE = "done"


class UiElement(BaseModel):
    """직렬화된 화면 요소 하나. 앱의 UiTreeSerializer가 만든다."""

    node_id: str
    label: str = ""
    role: str = ""
    clickable: bool = False
    bounds: list[int] | None = None  # [left, top, right, bottom]
    # 모델에게 "지식" 대신 "상태"를 준다(HANDOFF §3).
    can_scroll_forward: bool | None = None
    can_scroll_backward: bool | None = None
    # 페이지 dot 등 부가 상태
    current_page: int | None = None
    total_pages: int | None = None


class Screen(BaseModel):
    package_name: str = ""
    elements: list[UiElement] = Field(default_factory=list)


class NextStepRequest(BaseModel):
    goal: str
    screen: Screen
    device_id: str | None = None
    # 반복 안내 방지용 최근 안내 이력(최대 MAX_HISTORY). HANDOFF §3-10.
    recent_instructions: list[str] = Field(default_factory=list)


class GuideAction(BaseModel):
    """모델이 정한 다음 한 걸음. 근거 필드가 앞에 온다(생성 순서 = 추론 순서)."""

    expected_result: str = Field(description="목표만 보고 정한 최종 상태")
    evidence: str = Field(description="현재 화면 label 인용 등 근거")
    action: ActionType
    target_node_id: str | None = None
    instruction: str = Field(description="시니어에게 보여줄 한국어 안내 한 줄")


class NextStepResponse(BaseModel):
    action: GuideAction
    model: str
    verified: bool = False


class ErrorKind(StrEnum):
    QUOTA = "QUOTA"
    NETWORK = "NETWORK"
    AUTH = "AUTH"
    SERVER = "SERVER"
    PARSE = "PARSE"
    BAD_REQUEST = "BAD_REQUEST"


class ApiError(BaseModel):
    kind: ErrorKind
    message: str
