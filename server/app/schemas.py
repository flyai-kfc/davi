"""앱 <-> 서버 계약. 이 스키마가 곧 API 계약이다(테스트로 고정한다).

이 파일은 앱·서버·eval 셋이 동시에 무는 지점이라 바꾸면 세 곳이 함께 움직인다.
결정 근거는 server/CLAUDE.md "API 계약 결정"(C1~C15) 참고.

두 가지 규칙을 지킨다(HANDOFF §10 실측):
- **근거 필드가 먼저 온다.** GuideAction의 필드 선언 순서가 곧 Gemini `propertyOrdering`이고,
  그게 곧 생성 순서 = 추론 순서다. action을 앞에 두면 모델이 근거를 나중에 대충 채운다.
  순서는 `gemini_schema.build_response_schema()`가 **이 선언에서 자동으로 뽑는다**(C1).
  따라서 필드 순서를 바꾸면 프롬프트 동작이 바뀐다 — 무심코 재배치하지 말 것.
- **"해당 없음"은 빈 문자열로 표현하지 않는다.** enum에 빈 문자열을 넣으면 Gemini가 HTTP 400으로
  거부한다. 그래서 선택 필드는 전부 `X | None = None`이다.
  **실제로 무엇을 보내는지는 공급자마다 다르다** — Gemini는 필드 생략, OpenAI·Anthropic strict
  모드는 null이다. 그 규약을 여기 선언에 쓰지 않는 이유는 아래 `GuideAction.direction` 주석에
  있다. 규약 문장은 각 스키마 변환기가 붙인다(`gemini_schema.py` / `json_schema.py`).
"""

from __future__ import annotations

import re
from enum import StrEnum
from typing import Annotated, Self
from uuid import UUID

from pydantic import BaseModel, Field, field_validator, model_validator


class ActionType(StrEnum):
    """모델이 고를 수 있는 다음 동작.

    HANDOFF에서 실제로 관찰된 것만 둔다(C6). "기다리기"는 없다 —
    정체 판정은 앱의 시간 기반 폴링이 담당하므로 LLM이 고를 액션이 아니다.

    전역 제스처는 **실기기 로그로 필요성이 확인된 것만** 하나씩 넣는다(C9). 미리 여러 개 넣으면
    모델이 쓸 데 없이 고른다. 홈/뒤로는 아직 근거가 없어 여전히 보류다.
    """

    TAP = "tap"
    SCROLL = "scroll"
    TYPE = "type"
    OPEN_QUICK_SETTINGS = "open_quick_settings"
    # 화면 요소로 표현되지 않는 제스처다. 앱서랍은 위로 쓸어올려야 열리고 트리에 아무 노드도 없다.
    # 이게 없으면 홈에 아이콘이 없는 앱이 목표일 때 모델의 선택지에 정답이 아예 없다(#35).
    #
    # **언제 쓸지는 프롬프트에 규칙으로 박지 않는다.** "홈에서 안 보이면 앱서랍"은 자주 쓰는 앱이
    # 홈에 있다는 현실과 어긋나고, 조건을 붙여도 소용없다 — 삼성 런처 홈은 페이지가 여러 장이어도
    # scrollable 노드가 0개라(HANDOFF §3-2) "넘겨서 찾을 수 있는 홈"과 "넘겨도 소용없는 홈"을
    # 지금 계약으로는 구분할 수 없다. 전환 판단은 이력을 보는 가드레일의 일이다(#36, §5-4).
    OPEN_APP_DRAWER = "open_app_drawer"
    DONE = "done"


class SwipeDirection(StrEnum):
    """**화면이 이동하는 방향** — 사용자가 보러 가는 쪽이다(C17, 2026-08-17 재정의).

    예: 목록의 아래 내용을 보려면 DOWN, 다음 페이지로 넘어가려면 RIGHT.
    **손가락이 움직이는 방향은 그 반대**이고, 변환은 앱이 화면에 그릴 때 한 번 한다
    (`GestureMotion.fingerDirectionFor`).

    **이 docstring은 스키마에 실리지 않는다.** 모델에게 가는 문구는 `GuideAction.direction`의
    description과 `prompt.SCROLL_DIRECTION_INSTRUCTION`이다 — 기준을 바꾸면 셋을 함께 고친다
    (#109가 정확히 여기만 고쳐져 있어서 생긴 문제다).
    """

    UP = "up"
    DOWN = "down"
    LEFT = "left"
    RIGHT = "right"


class ScrollAxis(StrEnum):
    VERTICAL = "vertical"
    HORIZONTAL = "horizontal"


class CompletionVerdict(StrEnum):
    """현재 관측으로 본 목표 완료 신뢰도(#424)."""

    VERIFIED = "verified"
    INCONCLUSIVE = "inconclusive"
    CONTRADICTED = "contradicted"


class CompletionEvidenceKind(StrEnum):
    """모델이 현재 노드를 완료의 어느 방향 근거로 읽었는가."""

    SUCCESS = "success"
    FAILURE = "failure"


class ObservableState(StrEnum):
    """서버가 현재 접근성 트리에서 다시 읽을 수 있는 boolean 상태."""

    CHECKED = "checked"
    SELECTED = "selected"
    ENABLED = "enabled"
    SHOWING_HINT_TEXT = "showing_hint_text"


class CompletionFeedbackStatus(StrEnum):
    """완료 확인 UI에서 사용자가 서버에 돌려주는 명시적 판정."""

    USER_REJECTED = "user_rejected"


class UiElement(BaseModel):
    """직렬화된 화면 요소 하나. 앱의 UiTreeSerializer가 만든다.

    스크롤 메타는 모델에게 "지식" 대신 "상태"를 주기 위한 것이다(HANDOFF §3).
    스크롤 끝을 몰라 엉뚱한 방향을 안내하던 문제가 이 필드들로 풀렸다.
    """

    node_id: str
    label: str = ""
    role: str = ""
    clickable: bool = False
    # 입력 원문은 label에 넣지 않는다. 이 둘은 접근성 상태만 전달한다(#112).
    editable: bool = False
    focused: bool = False

    # 행동 상태 (C18). clickable/editable은 뷰의 설정값이라 이걸 대신하지 못한다.
    #   None = 낡은 앱이 안 보냄(미관측). **False로 기본값을 두지 않는다** — 침묵을
    #   "비활성/꺼짐"으로 읽으면 안내가 통째로 막힌다(C10과 같은 세 상태 규약).
    enabled: bool | None = None
    # 켤 수 없는 요소는 None이다. 그 False는 "꺼짐"이 아니라 해당 없음이다.
    checked: bool | None = None
    selected: bool | None = None
    # True = 비어 있음(보이는 글자가 hint) / False = 값이 들어 있음. 입력 원문은 오지 않는다(#112).
    showing_hint_text: bool | None = None

    # 위에 그려진 다른 요소에게 완전히 덮여 사용자가 보지도 누르지도 못하는가.
    #   접근성 트리는 겹쳐 쌓인 화면을 평평한 목록으로 눌러 편 것이라 모달 뒤 요소가 그대로
    #   딸려 온다. 앱의 isVisibleToUser는 형제 가림을 보지 않는다(안드로이드 스펙).
    #   None = 낡은 앱이 안 보냄(미관측). False로 기본값을 두지 않는 이유는 C10·C18과 같다.
    obscured: bool | None = None

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
    # 창 제목(C18). package만으로는 화면을 알 수 없다 — 설정>배터리가 com.samsung.android.lool로
    # 오는데 창 제목은 '배터리'를 바로 준다(#269 실측, SM-S921N). 제목이 없는 창이 흔해서
    # None이 정상이고, 값이 있으면 **label과 같은 2차 마스킹 대상이다**(대화방 제목 = 사람 이름).
    window_title: str | None = None
    elements: list[UiElement] = Field(default_factory=list)
    current_page: int | None = None
    total_pages: int | None = None
    # 스크롤 가능한 요소가 화면에 하나라도 있었는가(C10). 세 가지 상태를 구분한다:
    #   True  = 있다 / False = 하나도 없다 / None = 앱이 이 값을 채우지 않는다(낡은 앱)
    # 요소를 훑어 유추하면 False와 None이 섞인다. 삼성 런처 홈은 scrollable 노드가 0개인데
    # (실측 2026-07-27) 그 침묵을 모델이 "넘길 수 있다"로 읽어 같은 스크롤 안내를 8회 반복했다.
    # **없다는 것도 상태다.** 이름은 "화면이 스크롤된다"가 아니라 관찰한 사실 그대로 붙인다 —
    # 접근성 트리에 스크롤 노드가 없어도 실제로는 넘어가는 화면이 있다(런처 페이징).
    has_scrollable_element: bool | None = None
    # True = IME 창 관측 / False = 정상 window 목록에 없음 / None = 낡은 앱·window 미관측.
    ime_visible: bool | None = None
    # 지금 이 화면의 내용 축 서명(#410 · C11). 앱이 기기 시그니처의 내용 축을 **세션 salt로
    # 해시한** 값이라 원문 label은 여기로 오지 않는다. 이력의 `GuideStep.content_signature`와
    # 대조해 "구조는 같고 내용만 다른 화면"과 A→B→A 회귀를 가른다.
    # **서버가 스스로 계산할 수 없다** — salt가 기기 안에만 있고 프로세스 밖으로 나가지 않는다.
    # None은 label 없는 화면이거나 이 값을 아직 안 채우는 낡은 앱이다.
    content_signature: str | None = Field(default=None, pattern=r"^[0-9a-f]{64}$")


# 한 요청에 실을 수 있는 스텝 수의 상한(C11). C11은 앱 보관 개수도 같은 10으로 정했지만
# **코드에 공유 상수는 없다** — 서버는 앱의 `GuideSession.MAX_HISTORY`를 볼 수 없고 두 값이
# 어긋나도 잡아 줄 검사가 없다. 그 일치를 지키는 것은 문서다.
#
# **내리는 쪽이 특히 위험하다.** 앱은 보관분을 통째로 실어 보내므로, 이 값이 이미 설치된 앱의
# 보관 개수보다 작아지면 **세션이 새 상한을 넘는 순간부터 그 세션의 남은 요청이 422**가 되고
# 재시작해도 같은 지점에서 다시 막힌다 — 낡은 앱은 자기 상한을 줄일 수 없다. 아래
# `client_version`이 말하는 C7 비대칭이 그대로 걸리는 자리다.
#
# **올리면 프롬프트에 싣는 개수도 함께 오른다** — `prompt.MAX_HISTORY`가 이 값에서 파생된다
# (#308). 이력 줄은 스텝당 **매 호출 선형으로** 들고 늘려서 얻는 이득은 관측되지 않았다.
# 실측은 그 상수 옆 주석 한 곳에 있으니 올리기 전에 읽을 것. 레이트리밋의 짧은 창도 이 값을
# 근거로 산정했다(`config.davi_external_rate_limit_per_source_requests` 주석).
MAX_STEPS = 10


class ExpectedTargetState(BaseModel):
    """안내한 조작 뒤 대상에 기대하는 검증 가능한 상태."""

    attribute: ObservableState
    value: bool


class CompletionFeedback(BaseModel):
    """사용자가 완료 확인을 부정한 화면의 비식별 identity."""

    status: CompletionFeedbackStatus
    structure_signature: str = Field(pattern=r"^[0-9a-f]{64}$")
    content_signature: str | None = Field(default=None, pattern=r"^[0-9a-f]{64}$")


class CompletionEvidence(BaseModel):
    """모델의 완료 판단을 현재 화면 사실에 고정하는 구조화 근거."""

    node_id: str = Field(description="현재 화면에서 완료 판단의 직접 근거인 요소 node_id")
    kind: CompletionEvidenceKind = Field(
        description="그 요소가 목표의 직접 성공 근거인지 명시 실패·거절·취소 근거인지"
    )
    state: ExpectedTargetState | None = Field(
        default=None,
        description=(
            "근거가 checked·selected·enabled·showing_hint_text 상태에 의존할 때 "
            "현재 관측한 속성과 값"
        ),
    )


class GuideStep(BaseModel):
    """안내 한 번의 기록. **스텝 하나 = 안내 하나**다(C11 개정, #270).

    ``recent_instructions``(문장)와 ``recent_actions``(구조)로 나뉘어 있던 두 이력을 합친 것이다.
    나뉘어 있던 동안 모델은 "무엇을 안내했는지"만 알고 **그래서 화면이 바뀌었는지**를 몰랐고,
    구조 쪽은 소비자가 사라져 빈 계약으로 남아 있었다(#251).

    값의 출처는 두 가지뿐이다 — **서버 응답의 echo**(``instruction``·``action_type``·
    ``direction``·``target_label``·``target_node_id``·``screen_hint``·``structure_signature``)와
    **앱의 관측**(``screen_changed``·``performed``·``content_signature``). 앱은 값을 나르기만 하고
    해석하지 않는다. ``target_label``과 ``target_node_id`` 둘만은 앱이 응답의
    ``target_node_id``로 **현재 화면 요소에서 찾은 결과**다 — 강조를 그리려고 어차피 하는
    조회라 새 해석이 아니다. 그래서 모델이 화면에 없는 id를 고른 스텝에는 둘 다 실리지 않는다.

    **wire는 구조화, 프롬프트는 문장이다.** 서버가 매 요청 스텝당 한 문장으로 편다
    (``prompt._step_line``, #305).

    ``structure_signature``는 응답 ``screen_signature``의 echo다. 스텝 안에서만 이름이 다른
    이유는 내용 기반 서명(``content_signature``)이 같은 레코드에 함께 서기 때문이다 —
    구조와 내용을 한 이름으로 부르면 어느 쪽인지 읽을 수 없다.
    """

    instruction: str
    action_type: ActionType
    direction: SwipeDirection | None = None
    # 안내 대상 요소의 label(1차 마스킹된 값). 대상이 없는 동작에는 없다.
    target_label: str | None = None
    # 안내 대상 요소의 node id. 응답 `GuideAction.target_node_id`의 echo다.
    #
    # **화면마다 다시 매겨지는 값이라 `structure_signature`와 짝으로만 쓴다.** 서명이 그 id를
    # 포함하므로(`signature.screen_signature`) 서명이 같으면 같은 요소를 가리킨다. 이 짝이
    # 필요한 이유는 label이 요소 신원으로 못 쓰이기 때문이다 — 한 화면에 같은 label이 여럿이고
    # (목록의 '삭제' 버튼들), 부제목이 값이라 같은 행의 label이 바뀌며, scroll 대상에는 아예 없다.
    target_node_id: str | None = None
    # 직전 안내 뒤 서버가 검증할 수 있는 대상 상태. 응답 값을 앱이 해석하지 않고 echo한다.
    expected_target_state: ExpectedTargetState | None = None
    # 그 안내가 나간 화면이 무엇이었는지. 응답 `GuideAction.screen_summary`의 echo다.
    screen_hint: str | None = None
    structure_signature: str = Field(pattern=r"^[0-9a-f]{64}$")
    # 그 안내 뒤 화면이 실제로 바뀌었는가. True/False/None(아직 모름)을 구분한다 —
    # 앱은 안내를 표시한 시점에 스텝을 만들고, 이 값은 그다음 관측에서 확정된다.
    # False는 같은 화면을 다시 묻는 모든 경로에서 온다 — 오류 백오프 재시도·미검증 done
    # 재질의 등, 그리고 눌림 확인 재질의(#319 — 안내한 대상을 누르고도 7초 무변화면 앱이
    # 한 번 더 묻는다). 마지막을 뺀 경로들은 "사용자가 아직 안 했다"가 흔한 자리라 False만으로
    # 오안내를 단정하면 안 된다 — 단정은 performed=True와 겹칠 때만이다
    # (prompt.PRESSED_UNCHANGED_INSTRUCTION).
    screen_changed: bool | None = None
    # 안내한 대상을 사용자가 실제로 눌렀는가(앱 `PressMatch`, #319). 세 상태다 —
    # True는 좌표·텍스트 어느 축이든 일치, False는 **좌표 축의 확신 불일치**(클릭된 노드
    # 중심이 대상 밖)에서만 온다. None은 귀속 미확인이다: 클릭 이벤트가 안 왔거나(미조작
    # 또는 미탐), source 없는 텍스트 불일치거나, 판정 밖 액션(TAP·TYPE 외)이다 —
    # **None을 "안 누름"으로 읽지 않는다.**
    performed: bool | None = None
    # 그 안내가 나간 화면의 내용 축 서명(#410). 출처는 같은 요청의 `Screen.content_signature`다 —
    # 앱이 보낼 때 한 번 계산해 두 자리에 같은 값을 싣는다. `structure_signature`와 형식을
    # 맞춘다: 한 레코드에 나란히 서는 두 값이라 한쪽만 다르면 읽는 눈이 갈린다.
    # None은 label 없는 화면이거나 이 값을 아직 안 채우는 낡은 앱이다.
    content_signature: str | None = Field(default=None, pattern=r"^[0-9a-f]{64}$")

    @model_validator(mode="after")
    def validate_scroll_direction(self) -> Self:
        if self.action_type is ActionType.SCROLL and self.direction is None:
            raise ValueError("scroll 이력에는 direction이 필요하다")
        return self


class NextStepRequest(BaseModel):
    goal: str
    screen: Screen
    device_id: str | None = None
    # 이 세션에서 실제로 표시한 안내들. 오래된 것이 앞에 온다(HANDOFF §3-10).
    # 서버 상태 대신 활성 앱 세션이 보관한다(C11). **공유 상수가 있는 것이 아니다** — 앱은 자기
    # 상한만큼 보관해 보내고 서버는 `MAX_STEPS` 초과를 거부해, 세션 메모리와 요청 크기 양쪽에
    # 각각 상한이 선다.
    steps: list[GuideStep] = Field(default_factory=list, max_length=MAX_STEPS)
    # 완료 확인에서 사용자가 "아직요"라고 답한 화면. 서버는 현재 두 서명과 모두 맞을 때만
    # 명시적인 반대로 사용한다. 세션 식별자나 화면 원문은 싣지 않는다(#424).
    completion_feedback: CompletionFeedback | None = None
    # 서버는 즉시 바뀌지만 앱은 기기에 설치된 채 낡는다. 서버가 그 비대칭을 알아채야 한다(C7).
    client_version: str | None = None


class GuideAction(BaseModel):
    """모델이 정한 다음 한 걸음.

    **필드 순서 = 추론 순서**다(위 모듈 문서 참고). 근거 두 개를 먼저 쓰게 하고,
    그다음에 동작을 고르고, 마지막에 사용자 문구를 만들게 한다.
    """

    expected_result: str = Field(description="목표만 보고 정한 최종 상태")
    # 화면 정체성을 **모델이 정한다**(C11 개정, #270). 창 제목·코드 휴리스틱을 버린 근거는
    # 서드파티 실측이다 — 카카오T 택시 흐름 5화면의 창 제목이 null/'카카오 T' 둘뿐이고
    # 배민 주문 흐름 6화면이 전부 '배달의민족'이라 내부 화면을 가르지 못했다(2026-08-19 SM-S921N).
    #
    # **자리는 두 번째다.** `expected_result` 앞에 두면 "목표만 보고 정한 최종 상태"가 현재
    # 화면에 끌려간다 — 완료 검증이 조기 종료를 잡는 근거가 거기서 나온다(SPEC §4). 뒤이어
    # 화면 관측(요약) → 근거(evidence) 순으로 이어진다. 필드 순서 = 추론 순서다(C1).
    #
    # **공백 검증을 두지 않는다.** 요약 한 줄이 비었다고 안내 전체를 실패로 닫으면, 부수적인
    # 필드가 주 기능을 죽인다. 앱은 빈 값을 이력에 싣지 않는 것으로 처리한다.
    screen_summary: str = Field(
        description=(
            "지금 사용자가 보고 있는 화면이 무엇인지 한국어 한 줄로 짧게. "
            "어느 앱의 어떤 화면인지가 드러나게 쓴다 — 예: '설정 앱의 연결 목록 화면', "
            "'카카오T의 출발지 설정 화면'. 다음에 할 행동은 여기 쓰지 않는다."
        )
    )
    # 이 설명은 주석이 아니라 responseSchema를 타고 모델에게 간다. 무스키마 폴백에서는 스키마
    # JSON이 프롬프트 본문에 통째로 붙으므로, `prompt.EVIDENCE_INSTRUCTION`과 **같은 뜻**이어야
    # 한다. 한쪽이 "짧게 적어라"가 되면 한 프롬프트에 정반대 지시가 둘 들어간다(PR #62 리뷰).
    evidence: str = Field(
        description=(
            "현재 화면 요소의 label 하나를 작은따옴표로 감싸 글자 그대로 인용하고, "
            "조사나 설명은 닫는 따옴표 뒤에 붙인 뒤 그것이 판단 근거인 이유까지 쓴 문장"
        )
    )
    completion_evidence: CompletionEvidence | None = Field(
        default=None,
        description=(
            "현재 화면에서 목표 성공을 직접 증명하거나 명시적으로 반박하는 구조화 근거. "
            "node_id는 현재 요소 중 하나이고 상태에 의존하면 state도 채운다. 성공 근거는 done을 "
            "고를 때 쓰고, 명시 실패 근거는 복구 행동을 고를 때도 쓸 수 있다. 화면만으로 "
            "성공·실패를 정할 수 없으면 값이 없다."
        ),
    )
    action_type: ActionType = Field(description="다음에 할 동작")
    # 기준을 여기서도 말하는 이유는 `SwipeDirection` **클래스 docstring이 스키마에 실리지
    # 않아서**다 — 스키마 변환기가 싣는 것은 필드 description뿐이라 기준이 모델에게 도달하지
    # 않았다(#109 원인, `../CLAUDE.md`의 "구조화 출력 함정").
    # `prompt.SCROLL_DIRECTION_INSTRUCTION`과 같은 뜻이어야 한다.
    #
    # **"해당 없을 때 어떻게 표현하는가"는 여기 쓰지 않는다.** 그 규약이 공급자마다 다르기
    # 때문이다 — Gemini는 필드 생략, OpenAI·Anthropic strict 모드는 null이다. 여기에 한쪽
    # 규약을 박으면 다른 쪽 스키마와 정반대 지시가 한 프롬프트에 함께 들어간다(PR #62 리뷰가
    # 금지한 패턴). 규약은 각 변환기가 자기 표현에 맞게 덧붙인다.
    direction: SwipeDirection | None = Field(
        default=None,
        description=(
            "scroll일 때 화면이 이동할 방향, 즉 사용자가 보러 가는 쪽. "
            "아래 내용을 보려면 down, 위로 되돌아가려면 up, "
            "다음 페이지로 넘어가려면 right, 이전 페이지로 돌아가려면 left. "
            "scroll이 아니면 방향이 없다."
        ),
    )
    target_node_id: str | None = Field(
        default=None,
        description="동작 대상 요소의 node_id. 대상이 없는 동작에는 값이 없다.",
    )
    expected_target_state: ExpectedTargetState | None = Field(
        default=None,
        description=(
            "tap 뒤 같은 대상에서 검증할 boolean 상태와 기대값. 토글·선택처럼 현재 트리로 "
            "확인할 수 있을 때만 쓰며 단순 화면 전환이나 type에는 값이 없다."
        ),
    )
    # 복사 신호(C21). **필드만 늘고 제품 동작은 그대로다** — 앱은 모르는 키를 무시하므로
    # (`GuideApiClient`의 `ignoreUnknownKeys`) 이 값을 읽는 소비자가 생기기 전까지 안내는 지금과
    # 같다. 소비자는 안내창의 「바로 입력」 버튼이다(#405 — 누르면 앱이 `ACTION_SET_TEXT`로
    # 대상 입력칸에 채운다. 2026-08-24 팀 합의).
    #
    # **자리는 `instruction` 앞이다.** 넣을 글자를 먼저 확정하고 그 위에 사용자 문구를 쓰게
    # 하려는 것이고, 사용자 문구를 마지막에 만든다는 C1 순서와도 맞는다.
    #
    # 값은 화면 label이 아니라 **모델이 쓴 말**이라 마스킹을 거치지 않는다(SPEC §4).
    # 로그·저장 경계에서는 `instruction`과 같은 값으로 다룬다 — 실제로 같은 말이 두 필드에 있다.
    input_text: str | None = Field(
        default=None,
        description=(
            "type일 때 입력칸에 넣을 글자. **instruction에 적은 말과 같아야 한다** — "
            "사용자는 그 문장을 읽고 버튼을 누르므로, 채워지는 값이 방금 읽은 말과 달라서는 "
            "안 된다. 검색어는 목표 문장을 그대로 옮기지 않고 사람이 실제로 칠 말로 쓴다"
            "(목표 「한국이랑 미국의 시차를 알고 싶어」 -> 「한국 미국 시차」). "
            "무엇을 칠지 목표만으로 정할 수 없으면 예시를 지어내지 말고 값을 비운다. "
            "검색어처럼 입력한 뒤 결과에서 사용자가 다시 고르는 자리에서만 쓰고, 입력한 값이 "
            "곧바로 확정되는 칸이거나 사용자가 지금 정해야 하는 값이면 값이 없다. "
            "type이 아니면 값이 없다."
        ),
    )
    # 문장은 `direction`과 **같은 말**로 쓴다. 손가락 동작은 앱이 그림으로 보여주므로 문장까지
    # 손가락으로 쓰면 시니어가 읽는 말이 부자연스러워진다(C17).
    instruction: str = Field(
        description=(
            "시니어에게 보여줄 한국어 안내 한 줄. scroll이면 direction과 같은 말로 쓴다 — "
            "화면이 어디로 가는지로 쓰고(예: '아래쪽으로 스크롤해 주세요') "
            "손가락을 어느 쪽으로 움직이라고 쓰지 않는다."
        )
    )

    @model_validator(mode="after")
    def validate_scroll_direction(self) -> Self:
        """scroll은 앱이 안내할 수 있는 방향을 반드시 가져야 한다(C3)."""
        if self.action_type is ActionType.SCROLL and self.direction is None:
            raise ValueError("scroll action에는 direction이 필요하다")
        return self


class NextStepResponse(BaseModel):
    action: GuideAction
    model: str
    verified: bool = False
    # 기존 앱은 모르는 키를 무시하고 verified를 계속 쓴다. 새 앱은 이 세 상태를 우선한다.
    completion_verdict: CompletionVerdict = CompletionVerdict.INCONCLUSIVE
    # label을 제외한 현재 화면 구조의 SHA-256. 앱은 행동 이력에 붙여 다음 요청에서 그대로 돌려준다.
    screen_signature: str = Field(pattern=r"^[0-9a-f]{64}$")


class ErrorKind(StrEnum):
    QUOTA = "QUOTA"
    NETWORK = "NETWORK"
    AUTH = "AUTH"
    SERVER = "SERVER"
    PARSE = "PARSE"
    BAD_REQUEST = "BAD_REQUEST"


class RateLimitScope(StrEnum):
    """외부 공개 프로필의 QUOTA가 짧은 창인지 24시간 창인지 구분한다."""

    BURST = "burst"
    DAILY = "daily"


class ApiError(BaseModel):
    """앱이 사용자 안내를 분기하는 근거(C8).

    `message`는 **디버그용**이다. 시니어에게 그대로 보여주지 않는다 —
    사용자 문구는 앱이 `kind`와 선택 `rate_limit_scope`를 보고 만든다.
    """

    kind: ErrorKind
    message: str
    # 공급자 QUOTA의 실제 재개 시각 또는 자체 제한 창의 남은 시간이다.
    retry_after_seconds: int | None = None
    # 외부 공개 프로필의 자체 제한에만 채운다. 공급자 QUOTA와 기존 development 제한은 null이다.
    rate_limit_scope: RateLimitScope | None = None
    # 도그푸딩 로그 대조용.
    request_id: str | None = None


# --- 음성 목표 대화 (#90) ----------------------------------------------------

MAX_VOICE_TEXT_LENGTH = 500
MAX_CONVERSATION_MESSAGES = 10
MAX_INTENT_MESSAGE_LENGTH = 300
MAX_CLARIFICATION_QUESTIONS = 2
MAX_CLARIFICATION_OPTIONS = 3
MAX_CLARIFICATION_ID_LENGTH = 32
MAX_CLARIFICATION_PROMPT_LENGTH = 200
MAX_CLARIFICATION_OPTION_LENGTH = 80


class ConversationRole(StrEnum):
    USER = "user"
    ASSISTANT = "assistant"


class ConversationTurn(BaseModel):
    """이미 끝난 음성 목표 대화 한 줄. 현재 사용자 발화는 여기에 넣지 않는다."""

    role: ConversationRole
    content: str = Field(min_length=1, max_length=MAX_VOICE_TEXT_LENGTH)

    @field_validator("content")
    @classmethod
    def reject_blank_content(cls, value: str) -> str:
        stripped = value.strip()
        if not stripped:
            raise ValueError("대화 내용은 공백일 수 없다")
        return stripped


class IntentDialogueStatus(StrEnum):
    NEEDS_CLARIFICATION = "needs_clarification"
    READY = "ready"
    UNABLE_TO_FINALIZE = "unable_to_finalize"


class ClarificationAnswerSource(StrEnum):
    OPTION = "option"
    FREEFORM = "freeform"
    # #316 초기 APK 요청 호환용. 새 앱은 모름·거절도 직접 발화의 freeform으로 보낸다.
    UNKNOWN_OR_DECLINED = "unknown_or_declined"


class ClarificationQuestion(BaseModel):
    """앱이 추가 호출 없이 한 화면씩 보여 줄 객관식 질문."""

    id: str = Field(min_length=1, max_length=MAX_CLARIFICATION_ID_LENGTH)
    prompt: str = Field(min_length=1, max_length=MAX_CLARIFICATION_PROMPT_LENGTH)
    options: list[
        Annotated[str, Field(min_length=1, max_length=MAX_CLARIFICATION_OPTION_LENGTH)]
    ] = Field(
        max_length=MAX_CLARIFICATION_OPTIONS,
    )
    allow_freeform: bool = Field(
        description="항상 true. 앱은 보기 외 직접 말하기를 함께 제공한다.",
    )

    @field_validator("id", "prompt")
    @classmethod
    def normalize_required_text(cls, value: str) -> str:
        stripped = value.strip()
        if not stripped:
            raise ValueError("질문 식별자와 문장은 공백일 수 없다")
        return stripped

    @field_validator("options")
    @classmethod
    def normalize_options(cls, value: list[str]) -> list[str]:
        normalized = [option.strip() for option in value]
        if any(not option for option in normalized):
            raise ValueError("보기는 공백일 수 없다")
        if len(set(normalized)) != len(normalized):
            raise ValueError("보기는 중복될 수 없다")
        return normalized

    @model_validator(mode="after")
    def require_freeform(self) -> Self:
        if not self.allow_freeform:
            raise ValueError("모든 질문은 자유 발화를 허용해야 한다")
        return self


class ClarificationPlan(BaseModel):
    questions: list[ClarificationQuestion] = Field(
        min_length=1,
        max_length=MAX_CLARIFICATION_QUESTIONS,
    )

    @model_validator(mode="after")
    def require_unique_question_ids(self) -> Self:
        ids = [question.id for question in self.questions]
        if len(set(ids)) != len(ids):
            raise ValueError("질문 식별자는 중복될 수 없다")
        return self


class ClarificationAnswer(BaseModel):
    """첫 응답의 질문과 앱에서 받은 답을 두 번째 요청에 되돌리는 무상태 계약."""

    question_id: str = Field(min_length=1, max_length=MAX_CLARIFICATION_ID_LENGTH)
    prompt: str = Field(min_length=1, max_length=MAX_CLARIFICATION_PROMPT_LENGTH)
    answer: str = Field(min_length=1, max_length=MAX_VOICE_TEXT_LENGTH)
    source: ClarificationAnswerSource

    @field_validator("question_id", "prompt", "answer")
    @classmethod
    def normalize_answer_text(cls, value: str) -> str:
        stripped = value.strip()
        if not stripped:
            raise ValueError("질문과 답변은 공백일 수 없다")
        return stripped


class SlotFillRequest(BaseModel):
    """현재 발화와 이전에 끝난 사용자↔도우미 턴.

    엔드포인트 이름은 #88의 참고 계약을 따르지만, 서버는 고정 슬롯을 채우지 않는다.
    ``conversation_history``는 반드시 완결된 user/assistant 쌍만 담고 ``utterance``가
    이번 요청의 현재 사용자 발화다. ``session_id``는 앱 대화 경계일 뿐 모델 입력이나
    운영 로그에 포함하지 않는다.
    """

    utterance: str = Field(min_length=1, max_length=MAX_VOICE_TEXT_LENGTH)
    session_id: UUID
    conversation_history: list[ConversationTurn] = Field(
        default_factory=list,
        max_length=MAX_CONVERSATION_MESSAGES,
    )
    clarification_answers: list[ClarificationAnswer] = Field(
        default_factory=list,
        max_length=MAX_CLARIFICATION_QUESTIONS,
        description="첫 응답의 질문에 앱이 로컬로 모은 답. 있으면 최종 두 번째 요청이다.",
    )

    @field_validator("utterance")
    @classmethod
    def reject_blank_utterance(cls, value: str) -> str:
        stripped = value.strip()
        if not stripped:
            raise ValueError("현재 발화는 공백일 수 없다")
        return stripped

    @model_validator(mode="after")
    def validate_completed_turn_pairs(self) -> Self:
        if len(self.conversation_history) % 2 != 0:
            raise ValueError("대화 이력은 완결된 user/assistant 쌍이어야 한다")
        for index, turn in enumerate(self.conversation_history):
            expected = ConversationRole.USER if index % 2 == 0 else ConversationRole.ASSISTANT
            if turn.role is not expected:
                raise ValueError("대화 이력은 user와 assistant가 번갈아야 한다")
        answer_ids = [answer.question_id for answer in self.clarification_answers]
        if len(set(answer_ids)) != len(answer_ids):
            raise ValueError("구체화 답변의 질문 식별자는 중복될 수 없다")
        return self


class SlotFillState(BaseModel):
    """최대 두 질문의 구체화 계획, 최종 목표 또는 안전 종료를 돌려주는 응답."""

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
        description="needs_clarification일 때 앱이 로컬에서 진행할 질문 계획",
    )

    @field_validator("message")
    @classmethod
    def reject_blank_message(cls, value: str) -> str:
        stripped = value.strip()
        if not stripped:
            raise ValueError("사용자 안내는 공백일 수 없다")
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
        else:
            if self.goal is not None or self.clarification is not None:
                raise ValueError("안전 종료 상태에는 goal과 clarification이 없어야 한다")
        return self


# --- 목표 앱 후보와 기기 내 대조 (#102, #128) -------------------------------

MAX_APP_CANDIDATES = 5
MAX_APP_PACKAGE_NAMES = 5
MAX_APP_LABEL_ALIASES = 5
MAX_APP_NAME_LENGTH = 100
MAX_APP_CAPABILITY_LENGTH = 80
APP_PACKAGE_NAME_PATTERN = re.compile(r"^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$")


class AppCandidateAvailability(StrEnum):
    """기기 index가 후보 하나를 판정한 결과. 설치 여부 일반화가 아니라 launcher 범위다."""

    LAUNCHABLE = "launchable"
    NOT_FOUND = "not_found"
    AMBIGUOUS = "ambiguous"
    UNKNOWN = "unknown"


class AppResolutionStatus(StrEnum):
    """후보 생성 1단계와 기기 대조 뒤 최종 상태를 한 endpoint에서 구분한다."""

    CANDIDATES = "candidates"
    RESOLVED = "resolved"
    NEEDS_USER_CHOICE = "needs_user_choice"
    NEEDS_APP_NAME = "needs_app_name"
    UNAVAILABLE_OR_UNKNOWN = "unavailable_or_unknown"


class AppCandidate(BaseModel):
    """서버가 제안한 일반 후보. 기기에서 발견한 package·label은 이 모델에 넣지 않는다."""

    candidate_id: str = Field(pattern=r"^candidate-[1-5]$")
    canonical_name: str = Field(min_length=1, max_length=MAX_APP_NAME_LENGTH)
    package_names: list[str] = Field(default_factory=list, max_length=MAX_APP_PACKAGE_NAMES)
    label_aliases: list[str] = Field(default_factory=list, max_length=MAX_APP_LABEL_ALIASES)
    confidence: float = Field(ge=0.0, le=1.0)

    @field_validator("canonical_name")
    @classmethod
    def normalize_canonical_name(cls, value: str) -> str:
        stripped = value.strip()
        if not stripped:
            raise ValueError("후보 이름은 공백일 수 없다")
        return stripped

    @field_validator("package_names", "label_aliases")
    @classmethod
    def normalize_candidate_strings(cls, values: list[str]) -> list[str]:
        normalized = [value.strip() for value in values]
        if any(not value for value in normalized):
            raise ValueError("후보 package/alias는 공백일 수 없다")
        if any(len(value) > MAX_APP_NAME_LENGTH for value in normalized):
            raise ValueError("후보 package/alias가 길이 상한을 넘었다")
        if len(set(normalized)) != len(normalized):
            raise ValueError("후보 package/alias는 중복될 수 없다")
        return normalized

    @model_validator(mode="after")
    def validate_package_names(self) -> Self:
        if any(APP_PACKAGE_NAME_PATTERN.fullmatch(value) is None for value in self.package_names):
            raise ValueError("후보 Android package 이름이 올바르지 않다")
        return self


class AppCandidatePlan(BaseModel):
    """목표에 직접 관련된 소수 후보. 앱은 이 값을 로컬 index 대조에만 쓴다."""

    capability: str = Field(min_length=1, max_length=MAX_APP_CAPABILITY_LENGTH)
    candidates: list[AppCandidate] = Field(default_factory=list, max_length=MAX_APP_CANDIDATES)

    @field_validator("capability")
    @classmethod
    def normalize_capability(cls, value: str) -> str:
        stripped = value.strip()
        if not stripped:
            raise ValueError("앱 capability는 공백일 수 없다")
        return stripped

    @model_validator(mode="after")
    def validate_unique_candidates(self) -> Self:
        ids = [candidate.candidate_id for candidate in self.candidates]
        if len(set(ids)) != len(ids):
            raise ValueError("candidate_id는 중복될 수 없다")
        return self


class AppCandidateResult(BaseModel):
    candidate_id: str = Field(pattern=r"^candidate-[1-5]$")
    status: AppCandidateAvailability


class AppResolutionFeedback(BaseModel):
    """서버가 제안한 plan을 무상태로 되돌리고 기기 판정은 ID/status만 붙인다."""

    plan: AppCandidatePlan
    candidate_results: list[AppCandidateResult] = Field(max_length=MAX_APP_CANDIDATES)

    @model_validator(mode="after")
    def validate_result_coverage(self) -> Self:
        expected = [candidate.candidate_id for candidate in self.plan.candidates]
        actual = [result.candidate_id for result in self.candidate_results]
        if len(set(actual)) != len(actual) or set(actual) != set(expected):
            raise ValueError(
                "candidate_results는 plan의 candidate_id를 정확히 한 번씩 포함해야 한다"
            )
        return self


class AppResolutionRequest(BaseModel):
    """feedback이 없으면 후보 생성, 있으면 무상태 최종 판정 요청이다."""

    goal: str = Field(min_length=1, max_length=MAX_VOICE_TEXT_LENGTH)
    feedback: AppResolutionFeedback | None = None

    @field_validator("goal")
    @classmethod
    def normalize_resolution_goal(cls, value: str) -> str:
        stripped = value.strip()
        if not stripped:
            raise ValueError("앱 대조 목표는 공백일 수 없다")
        return stripped


class AppResolutionState(BaseModel):
    status: AppResolutionStatus
    message: str = Field(min_length=1, max_length=MAX_INTENT_MESSAGE_LENGTH)
    plan: AppCandidatePlan | None = None
    selected_candidate_id: str | None = Field(default=None, pattern=r"^candidate-[1-5]$")
    goal: str | None = Field(default=None, max_length=MAX_VOICE_TEXT_LENGTH)

    @field_validator("message")
    @classmethod
    def normalize_resolution_message(cls, value: str) -> str:
        stripped = value.strip()
        if not stripped:
            raise ValueError("앱 대조 안내는 공백일 수 없다")
        return stripped

    @model_validator(mode="after")
    def validate_status_payload(self) -> Self:
        if self.status is AppResolutionStatus.CANDIDATES:
            if self.plan is None or self.selected_candidate_id is not None or self.goal is not None:
                raise ValueError("candidates 상태에는 plan만 있어야 한다")
            return self
        if self.plan is not None:
            raise ValueError("최종 앱 대조 상태에는 plan이 없어야 한다")
        if self.status is AppResolutionStatus.RESOLVED:
            if self.selected_candidate_id is None or self.goal is None:
                raise ValueError("resolved 상태에는 선택 candidate와 goal이 필요하다")
        elif self.selected_candidate_id is not None or self.goal is not None:
            raise ValueError("미해결 상태에는 선택 candidate나 goal이 없어야 한다")
        return self
