"""대상 없는 `scroll`을 실기기 fixture와 결정론적 fake LLM으로 재현한다(#202).

#36 반복 가드(같은 화면·같은 행동 3회 → 앱서랍 전환)를 대신하는 파일이다. 반복 카운트는
"이 scroll이 듣지 않는다"는 증거를 모으는 장치였는데, **대상 해석 실패가 그 증거를 첫 요청에
결정론적으로 준다** — 런처 화면에서는 scroll이 나가기도 전에 앱서랍으로 갈린다. 반복을 기다리는
동안 나가던 헛안내 두 번이 곧 #202가 없애려는 증상이었다.
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
    SwipeDirection,
)

from harness import FIXTURES, load_fixture


class SequenceLlmClient(LlmClient):
    """문구는 달라도 행동 의미가 같은 실기기 실패를 재현하는 fake LLM."""

    def __init__(self, actions: list[GuideAction]) -> None:
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


def _request(fixture_name: str, *, screen: Screen | None = None) -> NextStepRequest:
    fixture = load_fixture(FIXTURES / fixture_name)
    if screen is None:
        return fixture.request
    return fixture.request.model_copy(update={"screen": screen})


def test_launcher_last_page_switches_to_the_app_drawer_on_the_first_scroll():
    """실측 ①: 스크롤 노드가 0개인 런처 홈. 첫 요청에서 바로 앱서랍으로 간다.

    반복 3회를 기다리며 헛안내를 두 번 내보내지 않는다. fixture는 페이지 2/2까지 넘긴 화면이라
    앱서랍과 구분되지 않으므로(C11), page 메타를 지운 홈 화면이 전환이 열리는 자리다.
    """

    fixture_screen = _request("samsung_launcher_last_page_home.json").screen
    home = fixture_screen.model_copy(update={"current_page": None, "total_pages": None})

    response = next_step(
        _request("samsung_launcher_open_camera.json", screen=home),
        SequenceLlmClient([_scroll(1)]),
    )

    assert response.action.action_type is ActionType.OPEN_APP_DRAWER
    assert response.action.target_node_id is None


def test_launcher_last_page_with_page_metadata_does_not_emit_a_targetless_scroll():
    """실측 ① 그대로. 앱서랍일 수도 있는 화면이라 전환은 막히고, scroll도 나가지 않는다."""

    with pytest.raises(LlmError) as raised:
        next_step(
            _request("samsung_launcher_last_page_home.json"),
            SequenceLlmClient([_scroll(1)]),
        )

    assert raised.value.kind is ErrorKind.SERVER


def test_alarm_editor_scroll_gets_the_time_picker_as_its_target():
    """실측 ②: `scrollable=true`인데도 `target=-`로 나갔다. 화면의 스크롤 요소로 교정한다."""

    response = next_step(
        _request("samsung_clock_alarm_editor.json"),
        SequenceLlmClient([_scroll(1, direction=SwipeDirection.DOWN)]),
    )

    action = response.action
    assert action.action_type is ActionType.SCROLL
    assert action.target_node_id is not None
    picker = next(
        element
        for element in _request("samsung_clock_alarm_editor.json").screen.elements
        if element.node_id == action.target_node_id
    )
    assert picker.can_scroll_forward is True
    assert picker.next_swipe_direction is SwipeDirection.DOWN


def test_target_the_model_already_resolved_is_kept():
    """가드는 교정이지 재선택이 아니다 — 제대로 고른 대상은 그대로 둔다."""

    response = next_step(
        _request("samsung_clock_alarm_editor.json"),
        SequenceLlmClient([_scroll(1, direction=SwipeDirection.DOWN, target="n2")]),
    )

    assert response.action.target_node_id == "n2"


def test_app_that_never_observed_scroll_state_keeps_its_guidance():
    """스크롤 메타를 안 보낸 낡은 앱의 침묵을 "대상이 없다"로 읽지 않는다(C10)."""

    fixture = _request("samsung_launcher_open_camera.json")

    response = next_step(fixture, SequenceLlmClient([_scroll(1)]))

    assert response.action.action_type is ActionType.SCROLL
    assert response.action.target_node_id is None
