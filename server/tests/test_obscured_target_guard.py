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
from app.prompt import OBSCURED_ELEMENT_INSTRUCTION, build_prompt
from app.reasoning import next_step
from app.schemas import (
    ActionType,
    ErrorKind,
    GuideAction,
    NextStepRequest,
    Screen,
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


def test_prompt_carries_the_fact_only_when_something_is_obscured():
    obscured = build_prompt(
        NextStepRequest(goal="치킨을 주문한다", screen=_sheet_screen(obscured=True))
    )
    clear = build_prompt(
        NextStepRequest(goal="치킨을 주문한다", screen=_sheet_screen(obscured=False))
    )

    assert "obscured=True" in obscured
    assert OBSCURED_ELEMENT_INSTRUCTION in obscured
    # `False`는 싣지도 설명하지도 않는다 — 요소가 최대 200개라 기본값 줄이 토큰을 먹는다.
    assert "obscured" not in clear
