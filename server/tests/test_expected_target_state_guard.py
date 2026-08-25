"""직전 조작의 기대 상태가 실제 tap 대상과만 함께 나가는지 고정한다(#424)."""

from app.reasoning import _apply_guardrails
from app.schemas import (
    ActionType,
    CheckState,
    ExpectedTargetState,
    GuideAction,
    NextStepRequest,
    ObservableState,
    Screen,
    SwipeDirection,
    UiElement,
)


def _action(action_type: ActionType, target: str | None = "n1") -> GuideAction:
    return GuideAction(
        expected_result="상태가 바뀐다",
        screen_summary="설정 화면",
        evidence="'기능'이 보입니다",
        action_type=action_type,
        direction=SwipeDirection.DOWN if action_type is ActionType.SCROLL else None,
        target_node_id=target,
        expected_target_state=ExpectedTargetState(
            attribute=ObservableState.CHECKED,
            value=True,
        ),
        instruction="기능을 눌러 주세요.",
    )


def _guard(action: GuideAction, *elements: UiElement) -> GuideAction:
    request = NextStepRequest(goal="기능 켜기", screen=Screen(elements=list(elements)))
    return _apply_guardrails(action, request)


def test_observable_tap_target_keeps_expected_state():
    action = _guard(
        _action(ActionType.TAP),
        UiElement(
            node_id="n1",
            label="기능",
            clickable=True,
            check_state=CheckState.UNCHECKED,
        ),
    )

    assert action.expected_target_state is not None


def test_missing_or_unobserved_tap_target_clears_expected_state():
    for element in (
        UiElement(
            node_id="n2",
            label="다른 기능",
            clickable=True,
            check_state=CheckState.UNCHECKED,
        ),
        UiElement(node_id="n1", label="기능", clickable=True),
        UiElement(
            node_id="n1",
            label="기능",
            clickable=True,
            check_state=CheckState.PARTIAL,
        ),
    ):
        action = _guard(_action(ActionType.TAP), element)
        assert action.expected_target_state is None


def test_scroll_target_replacement_cannot_keep_model_expected_state():
    action = _guard(
        _action(ActionType.SCROLL, target="n1"),
        UiElement(node_id="n1", label="기능", check_state=CheckState.UNCHECKED),
        UiElement(
            node_id="n2",
            label="목록",
            can_scroll_forward=True,
            next_swipe_direction=SwipeDirection.DOWN,
        ),
    )

    assert action.target_node_id == "n2"
    assert action.expected_target_state is None
