"""앱 <-> 서버 계약. 이 스키마가 곧 API 계약이다(테스트로 고정한다).

이 파일은 앱·서버·eval 셋이 동시에 무는 지점이라 바꾸면 세 곳이 함께 움직인다.
결정 근거는 server/CLAUDE.md "API 계약 결정"(C1~C15) 참고.

두 가지 규칙을 지킨다(HANDOFF §10 실측):
- **근거 필드가 먼저 온다.** GuideAction의 필드 선언 순서가 곧 Gemini `propertyOrdering`이고,
  그게 곧 생성 순서 = 추론 순서다. action을 앞에 두면 모델이 근거를 나중에 대충 채운다.
  순서는 `gemini_schema.build_response_schema()`가 **이 선언에서 자동으로 뽑는다**(C1).
  따라서 필드 순서를 바꾸면 프롬프트 동작이 바뀐다 — 무심코 재배치하지 말 것.
- **"해당 없음"은 빈 문자열이 아니라 필드 생략으로 표현한다.** enum에 빈 문자열을 넣으면
  Gemini가 HTTP 400으로 거부한다. 그래서 선택 필드는 전부 `X | None = None`이다.
"""

from __future__ import annotations

import re
from enum import StrEnum
from typing import Self
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
    # 입력 원문은 label에 넣지 않는다. 이 둘은 접근성 상태만 전달한다(#112).
    editable: bool = False
    focused: bool = False
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
    # 스크롤 가능한 요소가 화면에 하나라도 있었는가(C10). 세 가지 상태를 구분한다:
    #   True  = 있다 / False = 하나도 없다 / None = 앱이 이 값을 채우지 않는다(낡은 앱)
    # 요소를 훑어 유추하면 False와 None이 섞인다. 삼성 런처 홈은 scrollable 노드가 0개인데
    # (실측 2026-07-27) 그 침묵을 모델이 "넘길 수 있다"로 읽어 같은 스크롤 안내를 8회 반복했다.
    # **없다는 것도 상태다.** 이름은 "화면이 스크롤된다"가 아니라 관찰한 사실 그대로 붙인다 —
    # 접근성 트리에 스크롤 노드가 없어도 실제로는 넘어가는 화면이 있다(런처 페이징).
    has_scrollable_element: bool | None = None
    # True = IME 창 관측 / False = 정상 window 목록에 없음 / None = 낡은 앱·window 미관측.
    ime_visible: bool | None = None


class RecentAction(BaseModel):
    """같은 세션에서 직전에 실제 표시한 행동의 구조화 기록.

    ``screen_signature``는 서버가 응답에 넣어 준 구조 기반 서명을 앱이 그대로 되돌려 보낸다.
    화면 label이나 안내 문장은 포함하지 않는다. 문장 이력은 모델 프롬프트용
    ``recent_instructions``와 역할을 분리한다(C11).
    """

    screen_signature: str = Field(pattern=r"^[0-9a-f]{64}$")
    action_type: ActionType
    direction: SwipeDirection | None = None
    target_node_id: str | None = None

    @model_validator(mode="after")
    def validate_scroll_direction(self) -> Self:
        if self.action_type is ActionType.SCROLL and self.direction is None:
            raise ValueError("scroll 이력에는 direction이 필요하다")
        return self


class NextStepRequest(BaseModel):
    goal: str
    screen: Screen
    device_id: str | None = None
    # 반복 안내 방지용 최근 안내 이력. 오래된 것이 앞에 온다. HANDOFF §3-10.
    # 서버는 프롬프트에 넣을 때 MAX_HISTORY개로 자른다 — 앱이 더 보내도 계약 위반은 아니다.
    recent_instructions: list[str] = Field(default_factory=list)
    # 반복 가드 전용 구조화 이력. 서버 상태 대신 활성 앱 세션이 보관하고, 오래된 것이 앞에 온다.
    # 앱과 서버 모두 10개로 제한해 요청 크기와 세션 메모리에 상한을 둔다(C11).
    recent_actions: list[RecentAction] = Field(default_factory=list, max_length=10)
    # 서버는 즉시 바뀌지만 앱은 기기에 설치된 채 낡는다. 서버가 그 비대칭을 알아채야 한다(C7).
    client_version: str | None = None


class GuideAction(BaseModel):
    """모델이 정한 다음 한 걸음.

    **필드 순서 = 추론 순서**다(위 모듈 문서 참고). 근거 두 개를 먼저 쓰게 하고,
    그다음에 동작을 고르고, 마지막에 사용자 문구를 만들게 한다.
    """

    expected_result: str = Field(description="목표만 보고 정한 최종 상태")
    # 이 설명은 주석이 아니라 responseSchema를 타고 모델에게 간다. 무스키마 폴백에서는 스키마
    # JSON이 프롬프트 본문에 통째로 붙으므로, `prompt.EVIDENCE_INSTRUCTION`과 **같은 뜻**이어야
    # 한다. 한쪽이 "짧게 적어라"가 되면 한 프롬프트에 정반대 지시가 둘 들어간다(PR #62 리뷰).
    evidence: str = Field(
        description="현재 화면 요소의 label 하나를 그대로 포함하고, 그것이 왜 근거인지 이어 쓴 문장"
    )
    action_type: ActionType = Field(description="다음에 할 동작")
    # 기준을 여기서도 말하는 이유는 `SwipeDirection` **클래스 docstring이 스키마에 실리지
    # 않아서**다 — `gemini_schema.build_response_schema`가 싣는 것은 필드 description뿐이라
    # "동작 기준"이 모델에게 도달하지 않았다(#109 원인, `../CLAUDE.md`의 "Gemini 구조화 출력
    # 함정"). `prompt.SCROLL_DIRECTION_INSTRUCTION`과 같은 뜻이어야 한다.
    direction: SwipeDirection | None = Field(
        default=None,
        description=(
            "scroll일 때 손가락을 움직일 방향. 화면 내용이 움직일 방향이 아니다. "
            "예: 목록에서 아래쪽 내용을 보려면 손가락을 위로 쓸어올리므로 up. 그 외에는 생략한다."
        ),
    )
    target_node_id: str | None = Field(
        default=None,
        description="동작 대상 요소의 node_id. 대상이 없는 동작이면 생략한다.",
    )
    # 방향 표현이 `direction`과 어긋나면 앱이 화면에 그리는 방향과 읽히는 문장이 반대가 된다
    # (#159 실측). 그래서 문구 규칙도 필드 설명에 함께 둔다.
    instruction: str = Field(
        description=(
            "시니어에게 보여줄 한국어 안내 한 줄. scroll이면 방향 표현을 direction과 같은 "
            "손가락 기준으로 쓴다."
        )
    )

    @model_validator(mode="after")
    def validate_scroll_direction(self) -> Self:
        """scroll은 앱이 안내할 수 있는 손가락 방향을 반드시 가져야 한다(C3)."""
        if self.action_type is ActionType.SCROLL and self.direction is None:
            raise ValueError("scroll action에는 direction이 필요하다")
        return self


class NextStepResponse(BaseModel):
    action: GuideAction
    model: str
    verified: bool = False
    # label을 제외한 현재 화면 구조의 SHA-256. 앱은 행동 이력에 붙여 다음 요청에서 그대로 돌려준다.
    screen_signature: str = Field(pattern=r"^[0-9a-f]{64}$")


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


# --- 음성 목표 대화 (#90) ----------------------------------------------------

MAX_VOICE_TEXT_LENGTH = 500
MAX_CONVERSATION_MESSAGES = 10
MAX_INTENT_MESSAGE_LENGTH = 300


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
        return self


class SlotFillState(BaseModel):
    """고정 슬롯 없이 추가 질문 또는 최종 목표 하나를 돌려주는 응답."""

    status: IntentDialogueStatus = Field(description="추가 질문 필요 또는 목표 준비 완료")
    message: str = Field(
        min_length=1,
        max_length=MAX_INTENT_MESSAGE_LENGTH,
        description="TTS로 읽을 한국어 질문 또는 목표 준비 완료 안내 한 문장",
    )
    goal: str | None = Field(
        default=None,
        max_length=MAX_VOICE_TEXT_LENGTH,
        description="ready일 때 기존 안내 세션에 넘길 구체적인 목표",
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
        if self.status is IntentDialogueStatus.NEEDS_CLARIFICATION and self.goal is not None:
            raise ValueError("추가 질문 상태에는 goal이 없어야 한다")
        if self.status is IntentDialogueStatus.READY and self.goal is None:
            raise ValueError("준비 완료 상태에는 goal이 필요하다")
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
