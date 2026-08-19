"""대상 없는 `scroll`이 앱까지 나가지 않는지 고정한다(#202).

실측된 실패의 모양은 하나다 — `action=scroll target=-` → `highlight miss target=-` →
화면이 안 바뀌어 `screen stuck` → **정체에서는 서버를 다시 부르지 않으므로**(#39) 회복 경로가
없다. 서로 다른 앱에서 두 번 났고 한쪽은 `has_scrollable_element=true`였다. 그래서 여기서
고정하는 것은 "스크롤 못 하는 화면"이 아니라 **대상 해석 실패**라는 일반 조건이다.
"""

from __future__ import annotations

import pytest
from app.errors import LlmError
from app.llm import LlmClient
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


def _scroll(
    *,
    direction: SwipeDirection = SwipeDirection.DOWN,
    target: str | None = None,
) -> GuideAction:
    return GuideAction(
        expected_result="목표 화면에 도달",
        evidence="목표에 맞는 요소가 아직 보이지 않음",
        action_type=ActionType.SCROLL,
        direction=direction,
        target_node_id=target,
        instruction="아래쪽으로 스크롤해 주세요.",
    )


def _vertical_list(
    node_id: str,
    *,
    forward: bool | None = True,
    backward: bool | None = False,
    bounds: list[int] | None = None,
) -> UiElement:
    return UiElement(
        node_id=node_id,
        label="목록",
        bounds=bounds,
        can_scroll_forward=forward,
        can_scroll_backward=backward,
        scroll_axis=ScrollAxis.VERTICAL,
        next_swipe_direction=SwipeDirection.DOWN,
        prev_swipe_direction=SwipeDirection.UP,
    )


def _request(screen: Screen) -> NextStepRequest:
    return NextStepRequest(goal="알람을 만든다", screen=screen)


def _guide(action: GuideAction, screen: Screen) -> GuideAction:
    return next_step(_request(screen), _FixedLlmClient(action)).action


def test_scroll_without_a_target_gets_the_scrollable_element_of_the_screen():
    """실측 ②: `has_scrollable_element=true`인데 `target=-`로 나갔다(삼성 시계 알람 편집기)."""

    screen = Screen(
        package_name="com.sec.android.app.clockpackage",
        has_scrollable_element=True,
        elements=[UiElement(node_id="n1", label="취소", clickable=True), _vertical_list("n2")],
    )

    action = _guide(_scroll(), screen)

    assert action.action_type is ActionType.SCROLL
    assert action.target_node_id == "n2"


def test_target_that_is_not_on_the_screen_is_corrected():
    """없는 node_id는 대상이 없는 것과 같다 — 앱은 강조할 것을 못 찾는다."""

    screen = Screen(has_scrollable_element=True, elements=[_vertical_list("n2")])

    assert _guide(_scroll(target="n99"), screen).target_node_id == "n2"


def test_element_that_already_reached_that_end_is_not_kept_as_the_target():
    """그 방향으로 못 가는 요소를 대상으로 두면 안내는 나가도 화면은 안 움직인다.

    #217이 프롬프트에 사실로 적어 준 것과 같은 상태다.
    """

    screen = Screen(
        has_scrollable_element=True,
        elements=[
            _vertical_list("n1", forward=False, backward=True),
            _vertical_list("n2", forward=True, backward=False),
        ],
    )

    assert _guide(_scroll(target="n1"), screen).target_node_id == "n2"


def test_the_widest_scrollable_area_wins_when_several_can_scroll():
    """사용자는 요소가 아니라 화면을 쓴다 — 화면 제스처가 실제로 움직이는 넓은 쪽을 고른다."""

    screen = Screen(
        has_scrollable_element=True,
        elements=[
            _vertical_list("n1", bounds=[0, 400, 1080, 600]),
            _vertical_list("n2", bounds=[0, 600, 1080, 2200]),
        ],
    )

    assert _guide(_scroll(), screen).target_node_id == "n2"


def test_a_valid_target_chosen_by_the_model_is_left_alone():
    """가드는 교정이지 재선택이 아니다. 모델이 제대로 고른 대상은 넓이로 덮지 않는다."""

    screen = Screen(
        has_scrollable_element=True,
        elements=[
            _vertical_list("n1", bounds=[0, 400, 1080, 600]),
            _vertical_list("n2", bounds=[0, 600, 1080, 2200]),
        ],
    )

    assert _guide(_scroll(target="n1"), screen).target_node_id == "n1"


def test_horizontal_element_is_not_used_as_a_vertical_scroll_target():
    """축이 다른 요소를 대상으로 채우면 대상 없는 scroll을 다른 오답으로 바꾸는 것뿐이다."""

    screen = Screen(
        has_scrollable_element=True,
        elements=[
            UiElement(
                node_id="n1",
                label="탭",
                can_scroll_forward=True,
                scroll_axis=ScrollAxis.HORIZONTAL,
                next_swipe_direction=SwipeDirection.RIGHT,
                prev_swipe_direction=SwipeDirection.LEFT,
            )
        ],
    )

    with pytest.raises(LlmError) as raised:
        _guide(_scroll(direction=SwipeDirection.DOWN), screen)

    assert raised.value.kind is ErrorKind.SERVER


def test_screen_without_any_scrollable_element_does_not_get_a_scroll():
    """실측 ①과 같은 모양(스크롤 요소 0개). 안내를 지어내는 대신 실패로 닫는다.

    앱은 이 화면을 백오프 후 다시 물어보므로(`retryThisScreen`) 정체와 달리 회복 경로가 남는다.
    """

    screen = Screen(
        package_name="com.sec.android.app.clockpackage",
        has_scrollable_element=False,
        current_page=2,
        total_pages=2,
        elements=[UiElement(node_id="n1", label="확인", clickable=True)],
    )

    with pytest.raises(LlmError) as raised:
        _guide(_scroll(direction=SwipeDirection.LEFT), screen)

    assert raised.value.kind is ErrorKind.SERVER


def test_launcher_without_scrollable_elements_switches_to_the_app_drawer_at_once():
    """대체 행동이 있는 화면은 실패로 닫지 않는다. **반복 3회를 기다리지 않는다**(#202).

    반복 카운트는 "이 scroll이 듣지 않는다"는 증거를 모으는 장치였는데, 대상 해석 실패가 그
    증거를 첫 요청에 결정론적으로 준다. 기다리는 동안 나가던 헛안내 두 번이 곧 #202의 증상이다.
    """

    screen = Screen(
        package_name="com.sec.android.app.launcher",
        has_scrollable_element=False,
        elements=[UiElement(node_id="n1", label="전화", clickable=True)],
    )

    action = _guide(_scroll(), screen)

    assert action.action_type is ActionType.OPEN_APP_DRAWER
    assert action.target_node_id is None


def test_launcher_page_metadata_still_keeps_the_app_drawer_out():
    """앱서랍도 같은 package + `has_scrollable_element=false`다 — page 메타로 갈린다(C11).

    앱서랍에서 앱서랍을 열라고 하지 않는다. 이 화면의 scroll은 대상이 없으니 실패로 닫힌다.
    """

    screen = Screen(
        package_name="com.sec.android.app.launcher",
        has_scrollable_element=False,
        current_page=2,
        total_pages=2,
        elements=[UiElement(node_id="n1", label="전화", clickable=True)],
    )

    with pytest.raises(LlmError):
        _guide(_scroll(direction=SwipeDirection.LEFT), screen)


def test_app_that_never_observed_scroll_state_is_left_alone():
    """관측하지 않은 것은 판정하지 않는다(C10).

    낡은 앱의 침묵을 "대상이 없다"로 읽으면, 실제로는 넘어가던 화면의 안내를 서버가 없앤다.
    """

    screen = Screen(elements=[UiElement(node_id="n1", label="목록")])

    action = _guide(_scroll(target=None), screen)

    assert action.action_type is ActionType.SCROLL
    assert action.target_node_id is None


def test_axis_only_metadata_is_enough_to_be_a_target():
    """여유(`can_scroll_*`)를 못 채운 앱도 축은 보낸다.

    여유를 아는 후보가 하나도 없을 때만 축으로 대상을 정한다.
    """

    screen = Screen(
        has_scrollable_element=True,
        elements=[UiElement(node_id="n1", label="목록", scroll_axis=ScrollAxis.VERTICAL)],
    )

    assert _guide(_scroll(), screen).target_node_id == "n1"


def test_other_action_types_are_not_touched():
    """가드는 scroll만 본다. tap의 대상 판정은 모델의 몫이다."""

    screen = Screen(has_scrollable_element=True, elements=[_vertical_list("n2")])
    tap = GuideAction(
        expected_result="완료",
        evidence="근거",
        action_type=ActionType.TAP,
        target_node_id="n9",
        instruction="누르세요.",
    )

    assert _guide(tap, screen).target_node_id == "n9"
