"""#36 반복 가드를 실기기 fixture와 결정론적 fake LLM으로 재현한다."""

from __future__ import annotations

from collections.abc import Iterable

import pytest
from app.llm import LlmClient
from app.reasoning import MAX_IDENTICAL_ACTIONS, next_step
from app.schemas import (
    ActionType,
    GuideAction,
    NextStepRequest,
    RecentAction,
    Screen,
    SwipeDirection,
)

from harness import FIXTURES, load_fixture


class SequenceLlmClient(LlmClient):
    """문구는 달라도 행동 의미가 같은 실기기 실패를 재현하는 fake LLM."""

    def __init__(self, actions: Iterable[GuideAction]) -> None:
        self._actions = iter(actions)

    def reason(self, req: NextStepRequest) -> GuideAction:
        return next(self._actions)


def _scroll(
    instruction_number: int,
    *,
    direction: SwipeDirection = SwipeDirection.LEFT,
    target: str | None = None,
) -> GuideAction:
    return GuideAction(
        expected_result="목표 앱을 찾는다",
        evidence="현재 화면에서 목표 앱이 보이지 않음",
        action_type=ActionType.SCROLL,
        direction=direction,
        target_node_id=target,
        instruction=f"화면을 넘기는 안내 {instruction_number}",
    )


def _launcher_request(
    recent_actions: list[RecentAction] | None = None,
    *,
    screen: Screen | None = None,
) -> NextStepRequest:
    fixture = load_fixture(FIXTURES / "samsung_launcher_open_camera.json")
    launcher = screen or fixture.request.screen.model_copy(
        update={"has_scrollable_element": False}
    )
    return fixture.request.model_copy(
        update={
            "goal": "open an app",
            "screen": launcher,
            "recent_actions": recent_actions or [],
        }
    )


def _record(response) -> RecentAction:
    action = response.action
    return RecentAction(
        screen_signature=response.screen_signature,
        action_type=action.action_type,
        direction=action.direction,
        target_node_id=action.target_node_id,
    )


def test_same_screen_same_scroll_with_different_words_is_interrupted():
    fake = SequenceLlmClient(_scroll(index) for index in range(MAX_IDENTICAL_ACTIONS))
    history: list[RecentAction] = []
    returned: list[ActionType] = []

    for _ in range(MAX_IDENTICAL_ACTIONS):
        response = next_step(_launcher_request(history), fake)
        returned.append(response.action.action_type)
        history.append(_record(response))

    assert returned == [
        ActionType.SCROLL,
        ActionType.SCROLL,
        ActionType.OPEN_APP_DRAWER,
    ]
    assert ActionType.DONE not in returned


def test_structural_screen_change_resets_the_consecutive_count():
    fake = SequenceLlmClient([_scroll(1), _scroll(2), _scroll(3)])
    history: list[RecentAction] = []

    for _ in range(MAX_IDENTICAL_ACTIONS - 1):
        response = next_step(_launcher_request(history), fake)
        history.append(_record(response))

    changed = _launcher_request().screen.model_copy(update={"current_page": 2})
    response = next_step(_launcher_request(history, screen=changed), fake)

    assert response.action.action_type is ActionType.SCROLL


def test_volatile_label_changes_do_not_hide_the_repeat():
    fake = SequenceLlmClient([_scroll(1), _scroll(2), _scroll(3)])
    history: list[RecentAction] = []
    base = _launcher_request().screen

    for text in ("12:34", "12:35", "배터리 84%"):
        elements = [
            element.model_copy(update={"label": text}) if index == 0 else element
            for index, element in enumerate(base.elements)
        ]
        screen = base.model_copy(update={"elements": elements})
        response = next_step(_launcher_request(history, screen=screen), fake)
        history.append(_record(response))

    assert response.action.action_type is ActionType.OPEN_APP_DRAWER


@pytest.mark.parametrize(
    "previous",
    [
        RecentAction(
            screen_signature="0" * 64,
            action_type=ActionType.TAP,
        ),
        RecentAction(
            screen_signature="0" * 64,
            action_type=ActionType.SCROLL,
            direction=SwipeDirection.RIGHT,
        ),
        RecentAction(
            screen_signature="0" * 64,
            action_type=ActionType.SCROLL,
            direction=SwipeDirection.LEFT,
            target_node_id="n2",
        ),
    ],
)
def test_different_action_direction_or_target_is_not_a_repeat(previous: RecentAction):
    current = next_step(_launcher_request(), SequenceLlmClient([_scroll(0)]))
    mismatched = previous.model_copy(
        update={"screen_signature": current.screen_signature}
    )
    response = next_step(
        _launcher_request([mismatched, mismatched]),
        SequenceLlmClient([_scroll(1)]),
    )

    assert response.action.action_type is ActionType.SCROLL


def test_same_goal_restart_with_empty_session_history_does_not_leak_old_state():
    fake = SequenceLlmClient([_scroll(1), _scroll(2), _scroll(3)])
    old_history: list[RecentAction] = []
    for _ in range(MAX_IDENTICAL_ACTIONS - 1):
        response = next_step(_launcher_request(old_history), fake)
        old_history.append(_record(response))

    # GuideSession은 중지/재시작 때 recent_actions를 비운다. 같은 goal이어도 새 요청은 빈 이력이다.
    restarted = next_step(_launcher_request([]), fake)

    assert restarted.action.action_type is ActionType.SCROLL
