"""앱 <-> 서버 계약. 이 스키마가 곧 API 계약이다(테스트로 고정한다).

이 파일은 앱·서버·eval 셋이 동시에 무는 지점이라 바꾸면 세 곳이 함께 움직인다.
결정 근거는 server/CLAUDE.md "API 계약 결정"(C1~C8) 참고.

두 가지 규칙을 지킨다(HANDOFF §10 실측):
- **근거 필드가 먼저 온다.** GuideAction의 필드 선언 순서가 곧 Gemini `propertyOrdering`이고,
  그게 곧 생성 순서 = 추론 순서다. action을 앞에 두면 모델이 근거를 나중에 대충 채운다.
  순서는 `gemini_schema.build_response_schema()`가 **이 선언에서 자동으로 뽑는다**(C1).
  따라서 필드 순서를 바꾸면 프롬프트 동작이 바뀐다 — 무심코 재배치하지 말 것.
- **"해당 없음"은 빈 문자열이 아니라 필드 생략으로 표현한다.** enum에 빈 문자열을 넣으면
  Gemini가 HTTP 400으로 거부한다. 그래서 선택 필드는 전부 `X | None = None`이다.
"""

from __future__ import annotations

from enum import StrEnum

from pydantic import BaseModel, Field


class ActionType(StrEnum):
    """모델이 고를 수 있는 다음 동작.

    HANDOFF에서 실제로 관찰된 것만 둔다(C6). "기다리기"는 없다 —
    정체 판정은 앱의 시간 기반 폴링이 담당하므로 LLM이 고를 액션이 아니다.
    전역 제스처(홈/뒤로)는 실기기 로그로 필요성이 확인되면 추가한다(보류).
    """

    TAP = "tap"
    SCROLL = "scroll"
    TYPE = "type"
    OPEN_QUICK_SETTINGS = "open_quick_settings"
    DONE = "done"


class SwipeDirection(StrEnum):
    """손가락을 움직이는 방향. '무엇이 보이게 되는지'가 아니라 **동작** 기준이다.

    예: 목록을 아래로 더 보려면 손가락은 위로 쓸어올린다 -> UP.
    """

    UP = "up"
    DOWN = "down"
    LEFT = "left"
    RIGHT = "right"


class ScrollAxis(StrEnum):
    VERTICAL = "vertical"
    HORIZONTAL = "horizontal"


class UiElement(BaseModel):
    """직렬화된 화면 요소 하나. 앱의 UiTreeSerializer가 만든다.

    스크롤 메타는 모델에게 "지식" 대신 "상태"를 주기 위한 것이다(HANDOFF §3).
    스크롤 끝을 몰라 엉뚱한 방향을 안내하던 문제가 이 필드들로 풀렸다.
    """

    node_id: str
    label: str = ""
    role: str = ""
    clickable: bool = False
    bounds: list[int] | None = None  # [left, top, right, bottom]

    # 스크롤 상태 (C4). 방향·끝 판정이 여기서 나온다.
    can_scroll_forward: bool | None = None
    can_scroll_backward: bool | None = None
    scroll_axis: ScrollAxis | None = None
    next_swipe_direction: SwipeDirection | None = None
    prev_swipe_direction: SwipeDirection | None = None


class Screen(BaseModel):
    """지금 사용자가 보는 화면 하나.

    페이지 위치는 **화면 단위 상태라 여기 둔다**(C5). 요소마다 들고 있으면,
    홈처럼 페이지마다 dot 노드가 따로 있는 화면에서 "아무거나 집어 틀린 값을 쓰는"
    HANDOFF §2의 버린 길을 그대로 재현하게 된다. dot 자체는 role="page_dot" 요소로 남는다.
    """

    package_name: str = ""
    elements: list[UiElement] = Field(default_factory=list)
    current_page: int | None = None
    total_pages: int | None = None


class NextStepRequest(BaseModel):
    goal: str
    screen: Screen
    device_id: str | None = None
    # 반복 안내 방지용 최근 안내 이력(최대 MAX_HISTORY). HANDOFF §3-10.
    recent_instructions: list[str] = Field(default_factory=list)
    # 서버는 즉시 바뀌지만 앱은 기기에 설치된 채 낡는다. 서버가 그 비대칭을 알아채야 한다(C7).
    client_version: str | None = None


class GuideAction(BaseModel):
    """모델이 정한 다음 한 걸음.

    **필드 순서 = 추론 순서**다(위 모듈 문서 참고). 근거 두 개를 먼저 쓰게 하고,
    그다음에 동작을 고르고, 마지막에 사용자 문구를 만들게 한다.
    """

    expected_result: str = Field(description="목표만 보고 정한 최종 상태")
    evidence: str = Field(description="현재 화면 label 인용 등 근거")
    action_type: ActionType = Field(description="다음에 할 동작")
    direction: SwipeDirection | None = Field(
        default=None,
        description="scroll일 때 손가락을 움직일 방향. 그 외에는 생략한다.",
    )
    target_node_id: str | None = Field(
        default=None,
        description="동작 대상 요소의 node_id. 대상이 없는 동작이면 생략한다.",
    )
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
    """앱이 사용자 안내를 분기하는 근거(C8).

    `message`는 **디버그용**이다. 시니어에게 그대로 보여주지 않는다 —
    사용자 문구는 앱이 `kind`를 보고 만든다("사용량 한도" vs "인터넷 불량").
    """

    kind: ErrorKind
    message: str
    # QUOTA에서 "잠시 후 다시" 와 "오늘 한도 소진"을 구분하려면 필요하다.
    # 무료 티어 RPD 리셋은 한국시간 17:00(HANDOFF §10).
    retry_after_seconds: int | None = None
    # 도그푸딩 로그 대조용.
    request_id: str | None = None
