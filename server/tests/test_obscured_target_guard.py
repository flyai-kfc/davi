"""모달 뒤에 가려진 요소가 안내 대상이 되지 않는지 고정한다.

2026-08-21 SM-S921N 실측이 출발점이다. 배민 타임세일 시트가 떠 있는 화면에서 요소 53개 중
39개가 시트 뒤였는데 **전부 `isVisibleToUser=true`로 올라왔고**(안드로이드에서 그 플래그는
형제 가림을 보지 않는다), 모델이 그중 하나인 '치킨' 버튼을 탭 대상으로 골랐다. 사용자는 볼
수도 누를 수도 없으니 화면이 그대로고 → `screen stuck` → **정체에서는 서버를 다시 부르지
않으므로**(#39) 회복 경로가 없다. 실패 모양이 #202의 대상 없는 `scroll`과 같아서 대응도 같다.
"""

from __future__ import annotations

import pytest
from app.errors import LlmError
from app.llm import LlmClient
from app.prompt import FOCUSED_INPUT_INSTRUCTION, build_prompt
from app.reasoning import next_step
from app.schemas import (
    ActionType,
    ErrorKind,
    GuideAction,
    NextStepRequest,
    Screen,
    ScrollAxis,
    SwipeDirection,
    UiElement,
)


class _FixedLlmClient(LlmClient):
    """모델이 내놓은 행동을 그대로 돌려주는 fake. 가드레일만 관측한다."""

    def __init__(self, action: GuideAction) -> None:
        self._action = action

    def reason(self, req: NextStepRequest) -> GuideAction:
        return self._action


def _tap(target: str) -> GuideAction:
    return GuideAction(
        expected_result="목표 화면에 도달",
        screen_summary="배달 앱 첫 화면",
        evidence="'치킨' 버튼이 보임",
        action_type=ActionType.TAP,
        target_node_id=target,
        instruction="'치킨'을 눌러 주세요.",
    )


def _sheet_screen(*, obscured: bool | None) -> Screen:
    """모달 시트 하나와 그 뒤에 깔린 요소 하나."""
    return Screen(
        package_name="com.sampleapp",
        elements=[
            UiElement(node_id="n1", label="닫기", role="View", clickable=True, obscured=False),
            UiElement(node_id="n2", label="치킨", role="Button", clickable=True, obscured=obscured),
        ],
    )


def _guide(action: GuideAction, screen: Screen) -> GuideAction:
    request = NextStepRequest(goal="치킨을 주문한다", screen=screen)
    return next_step(request, _FixedLlmClient(action)).action


def test_obscured_target_is_closed_as_a_failure_instead_of_going_out():
    """실측 그대로의 상황. 틀린 안내를 내보내는 대신 실패로 닫아 재질의 경로를 남긴다."""

    with pytest.raises(LlmError) as raised:
        _guide(_tap("n2"), _sheet_screen(obscured=True))

    assert raised.value.kind is ErrorKind.SERVER


def test_unobscured_target_passes_through():
    screen = _sheet_screen(obscured=False)

    assert _guide(_tap("n2"), screen).target_node_id == "n2"


def test_unobserved_obscured_does_not_block_the_guide():
    """`None`은 낡은 앱의 침묵이다. "가려짐"으로도 "안 가려짐"으로도 읽지 않는다(C10 세 상태)."""

    screen = _sheet_screen(obscured=None)

    assert _guide(_tap("n2"), screen).target_node_id == "n2"


def test_the_guard_does_not_pick_a_replacement_target():
    """가드는 교정이지 재선택이 아니다 — 가리지 않은 `n1`이 있어도 대신 고르지 않는다."""

    with pytest.raises(LlmError):
        _guide(_tap("n2"), _sheet_screen(obscured=True))


def test_obscured_is_unobserved_by_default():
    """기본값을 `False`로 두면 낡은 앱의 침묵이 "안 가려짐"이 된다(C10·C18과 같은 이유)."""

    assert UiElement(node_id="n1").obscured is None


def test_prompt_leaves_obscured_elements_out_of_the_element_list():
    """가려진 요소는 **오답 선택지**라 아예 싣지 않는다. 실측 화면에서 74%가 그것이었다."""

    prompt = build_prompt(
        NextStepRequest(goal="치킨을 주문한다", screen=_sheet_screen(obscured=True))
    )

    assert "'닫기'" in prompt
    assert "'치킨'" not in prompt
    assert "n2" not in prompt


def test_prompt_keeps_elements_that_are_not_obscured():
    prompt = build_prompt(
        NextStepRequest(goal="치킨을 주문한다", screen=_sheet_screen(obscured=False))
    )

    assert "'치킨'" in prompt


def test_prompt_keeps_unobserved_elements():
    """`None`은 뺄 근거가 아니다 — 낡은 앱의 화면이 통째로 비어 버린다(C10)."""

    prompt = build_prompt(
        NextStepRequest(goal="치킨을 주문한다", screen=_sheet_screen(obscured=None))
    )

    assert "'치킨'" in prompt


def test_scroll_target_is_not_filled_with_an_obscured_element():
    """가드가 **직접 채우는** 대상도 가림 검사를 받아야 한다(PR #336 리뷰).

    모달이 뜬 화면에서 스크롤 가능한 것은 대개 뒤에 깔린 목록이라 흔한 경로다. 후보가 비면
    아래 단들이 페이지 이동·앱서랍·실패로 알아서 간다.
    """

    screen = Screen(
        package_name="com.sampleapp",
        has_scrollable_element=True,
        elements=[
            UiElement(node_id="n1", label="닫기", role="View", clickable=True, obscured=False),
            UiElement(
                node_id="n2",
                label="가게 목록",
                role="View",
                obscured=True,
                can_scroll_forward=True,
                scroll_axis=ScrollAxis.VERTICAL,
                next_swipe_direction=SwipeDirection.DOWN,
            ),
        ],
    )
    scroll = GuideAction(
        expected_result="목표 화면에 도달",
        screen_summary="배달 앱 첫 화면",
        evidence="목표에 맞는 가게가 아직 보이지 않음",
        action_type=ActionType.SCROLL,
        direction=SwipeDirection.DOWN,
        instruction="아래쪽으로 스크롤해 주세요.",
    )

    with pytest.raises(LlmError) as raised:
        _guide(scroll, screen)

    assert raised.value.kind is ErrorKind.SERVER


def test_unobscured_scroll_target_is_still_filled():
    screen = Screen(
        package_name="com.sampleapp",
        has_scrollable_element=True,
        elements=[
            UiElement(
                node_id="n2",
                label="가게 목록",
                role="View",
                obscured=False,
                can_scroll_forward=True,
                scroll_axis=ScrollAxis.VERTICAL,
                next_swipe_direction=SwipeDirection.DOWN,
            ),
        ],
    )
    scroll = GuideAction(
        expected_result="목표 화면에 도달",
        screen_summary="배달 앱 첫 화면",
        evidence="목표에 맞는 가게가 아직 보이지 않음",
        action_type=ActionType.SCROLL,
        direction=SwipeDirection.DOWN,
        instruction="아래쪽으로 스크롤해 주세요.",
    )

    assert _guide(scroll, screen).target_node_id == "n2"


def _scrollable(node_id: str, *, obscured: bool | None) -> UiElement:
    return UiElement(
        node_id=node_id,
        label="가게 목록",
        role="View",
        obscured=obscured,
        can_scroll_forward=True,
        scroll_axis=ScrollAxis.VERTICAL,
        next_swipe_direction=SwipeDirection.DOWN,
    )


def test_scroll_reach_line_does_not_point_at_hidden_ids():
    """스크롤 줄이 요소 목록에 없는 id를 가리키면 안 된다(PR #336 리뷰 2R).

    가리키면 모델이 그 id로 scroll을 내고, 후보 필터가 거부하고, 백오프 후 같은
    프롬프트가 나가 같은 함정을 밟는 뺑뺑이가 된다 — 목록과 쪽지가 서로 모순인 상태다.
    """

    screen = Screen(
        package_name="com.sampleapp",
        has_scrollable_element=True,
        elements=[
            UiElement(node_id="n1", label="닫기", role="View", clickable=True, obscured=False),
            _scrollable("n27", obscured=True),
        ],
    )
    prompt = build_prompt(NextStepRequest(goal="치킨을 주문한다", screen=screen))

    assert "n27" not in prompt


def test_scroll_reach_line_still_points_at_visible_ids():
    screen = Screen(
        package_name="com.sampleapp",
        has_scrollable_element=True,
        elements=[_scrollable("n2", obscured=False)],
    )
    prompt = build_prompt(NextStepRequest(goal="치킨을 주문한다", screen=screen))

    assert "n2" in prompt


def test_focused_input_instruction_is_not_sent_for_a_hidden_input():
    """검색창에 포커스가 남은 채 시트가 올라오면, 목록에 없는 입력칸을 두고 입력 안내가 나갔다."""

    def input_screen(obscured: bool) -> Screen:
        return Screen(
            package_name="com.sampleapp",
            elements=[
                UiElement(node_id="n1", label="닫기", role="View", clickable=True, obscured=False),
                UiElement(
                    node_id="n2",
                    label="검색",
                    role="EditText",
                    editable=True,
                    focused=True,
                    obscured=obscured,
                ),
            ],
        )

    hidden = build_prompt(NextStepRequest(goal="치킨을 주문한다", screen=input_screen(True)))
    visible = build_prompt(NextStepRequest(goal="치킨을 주문한다", screen=input_screen(False)))

    assert FOCUSED_INPUT_INSTRUCTION not in hidden
    assert FOCUSED_INPUT_INSTRUCTION in visible


def _all_obscured_screen() -> Screen:
    """요소 전부가 가려졌다고 관측된 화면. 덮는 쪽은 항상 보이므로 실존하지 않는 관측이다."""

    return Screen(
        package_name="com.sampleapp",
        has_scrollable_element=True,
        elements=[
            UiElement(node_id="n1", label="치킨", role="Button", clickable=True, obscured=True),
            _scrollable("n2", obscured=True),
        ],
    )


def test_a_fully_obscured_screen_falls_back_to_the_original_list():
    """전부 가려짐 = 관측 오작동. 그대로 지우면 멀쩡한 화면에서 안내가 통째로 죽는다."""

    prompt = build_prompt(NextStepRequest(goal="치킨을 주문한다", screen=_all_obscured_screen()))

    assert "'치킨'" in prompt
    assert "요소 없음" not in prompt


def test_the_guard_stands_down_when_every_element_is_obscured():
    """폴백으로 실린 요소를 가드가 계속 거부하면 백오프 → 같은 프롬프트 → 같은 거부의 뺑뺑이다."""

    assert _guide(_tap("n1"), _all_obscured_screen()).target_node_id == "n1"


def test_scroll_filling_also_stands_down_when_every_element_is_obscured():
    scroll = GuideAction(
        expected_result="목표 화면에 도달",
        screen_summary="배달 앱 첫 화면",
        evidence="목표에 맞는 가게가 아직 보이지 않음",
        action_type=ActionType.SCROLL,
        direction=SwipeDirection.DOWN,
        instruction="아래쪽으로 스크롤해 주세요.",
    )

    assert _guide(scroll, _all_obscured_screen()).target_node_id == "n2"
