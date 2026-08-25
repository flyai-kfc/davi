"""눌러도 반응하지 않는 요소가 tap 대상이 되지 않는지 고정한다(C20 · #311).

실측 세 건이 출발점이다(SM-S921N).
- 셰이드 화면에서 상태표시줄 Wi-Fi **아이콘**(`ImageView`, clickable=False)을 tap 대상으로 골랐다.
- 웹뷰 로그인 화면에서 안내 **문구**(`TextView`)를 두 번 연속 골랐다 — 같은 화면에 정답 버튼
  ('선택')이 있었고, 안내대로 문구를 실제로 눌러도 아무 일이 없었다.
- 스플래시에서 유일한 요소(clickable=False)를 tap 대상으로 골랐다.

셋 다 실패 모양이 가림 가드와 같다: 눌러도 아무 일이 없으니 화면이 그대로 → 정체 →
**정체에서는 서버를 다시 부르지 않으므로**(#39) 회복 경로가 없다.

`clickable=False`를 믿는 근거(2026-08-22 프로브 실측)와 경계는 여기 옮겨 적지 않는다 —
`reasoning._reject_unclickable_tap` docstring 한 곳에 있다. 사본을 두면 근거가 갱신될 때
한쪽만 낡는다(루트 CLAUDE.md).
"""

from __future__ import annotations

import pytest
from app.errors import LlmError
from app.prompt import (
    CLICKABLE_TARGET_INSTRUCTION,
    INPUT_NOT_FOCUSED,
    NO_CLICKABLE_ELEMENT_NOTICE,
    build_prompt,
)
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

from tests.conftest import FixedLlmClient


def _action(action_type: ActionType, target: str | None, **extra) -> GuideAction:
    return GuideAction(
        expected_result="목표 화면에 도달",
        screen_summary="테스트 화면",
        evidence="'무언가'가 보임",
        action_type=action_type,
        target_node_id=target,
        instruction="안내 문장",
        **extra,
    )


def _shade_screen() -> Screen:
    """셰이드 실측 화면의 축약 — 상태 아이콘(clickable=False)과 진짜 버튼이 함께 있다."""
    return Screen(
        package_name="com.android.systemui",
        elements=[
            UiElement(
                node_id="n5",
                label="Wi-Fi 신호가 강합니다.",
                role="ImageView",
                clickable=False,
            ),
            UiElement(node_id="n7", label="설정 열기", role="Button", clickable=True),
        ],
    )


def _splash_screen() -> Screen:
    """스플래시 실측 화면 — 요소가 하나뿐이고 그마저 클릭 불가."""
    return Screen(
        package_name="com.sampleapp",
        elements=[
            UiElement(
                node_id="n1",
                label="배달의민족 앱을 시작합니다.",
                role="ImageView",
                clickable=False,
            ),
        ],
    )


def _guide(action: GuideAction, screen: Screen) -> GuideAction:
    request = NextStepRequest(goal="치킨을 주문한다", screen=screen)
    return next_step(request, FixedLlmClient(action)).action


# ── 가드 ──────────────────────────────────────────────────────────────


def test_tap_on_unclickable_element_is_rejected():
    with pytest.raises(LlmError) as exc:
        _guide(_action(ActionType.TAP, "n5"), _shade_screen())
    assert exc.value.kind is ErrorKind.SERVER


def test_tap_on_the_only_element_of_a_splash_screen_is_rejected():
    with pytest.raises(LlmError):
        _guide(_action(ActionType.TAP, "n1"), _splash_screen())


def test_tap_on_clickable_element_passes():
    action = _guide(_action(ActionType.TAP, "n7"), _shade_screen())
    assert action.target_node_id == "n7"


def test_tap_without_target_is_not_judged_here():
    # 대상 없는 tap과 환각 id는 이 가드의 주제가 아니다 — 앱의 highlight miss가 따로 드러낸다.
    action = _guide(_action(ActionType.TAP, None), _shade_screen())
    assert action.action_type is ActionType.TAP


def test_tap_on_unknown_target_id_is_not_judged_here():
    action = _guide(_action(ActionType.TAP, "n99"), _shade_screen())
    assert action.target_node_id == "n99"


def test_scroll_targeting_unclickable_container_is_not_rejected():
    """스크롤 컨테이너는 대개 clickable=False다 — 이 가드가 scroll을 건드리면 안 된다."""
    screen = Screen(
        package_name="com.sampleapp",
        elements=[
            UiElement(
                node_id="n3",
                label="",
                role="RecyclerView",
                clickable=False,
                can_scroll_forward=True,
                can_scroll_backward=False,
                scroll_axis=ScrollAxis.VERTICAL,
                next_swipe_direction=SwipeDirection.DOWN,
                prev_swipe_direction=SwipeDirection.UP,
            ),
        ],
    )
    action = _guide(
        _action(ActionType.SCROLL, "n3", direction=SwipeDirection.DOWN),
        screen,
    )
    assert action.action_type is ActionType.SCROLL
    assert action.target_node_id == "n3"


def test_tap_on_editable_but_unclickable_input_passes():
    """clickable=False라도 입력칸이면 tap을 막지 않는다.

    직렬화는 isClickable || isEditable로 요소를 방출하므로 clickable=False인 입력칸이
    계약상 존재할 수 있고, 키보드가 닫힌 입력칸은 프롬프트가 tap을 명시적으로 안내한다
    (FOCUSED_INPUT_KEYBOARD_CLOSED). 막으면 프롬프트 지시를 가드가 반려하는 모순이 된다.
    """
    screen = Screen(
        package_name="com.sampleapp",
        elements=[
            UiElement(node_id="n1", label="", role="View", clickable=False, editable=True),
        ],
    )
    action = _guide(_action(ActionType.TAP, "n1"), screen)
    assert action.target_node_id == "n1"


def test_type_is_not_judged_by_the_tap_guard():
    """`type`은 이 가드의 주제가 아니다 — 대상을 갖는 액션이라 경계를 고정해 둔다.

    입력칸은 clickable=False로 올 수 있고(직렬화가 isClickable || isEditable로 방출한다),
    tap 면제와 달리 여기는 애초에 판정 대상이 아니다.
    """
    screen = Screen(
        package_name="com.sampleapp",
        elements=[
            UiElement(
                node_id="n1", label="검색어", role="EditText", clickable=False, editable=True
            ),
        ],
    )
    action = _guide(_action(ActionType.TYPE, "n1"), screen)
    assert action.action_type is ActionType.TYPE


def test_disabled_but_clickable_element_is_not_rejected():
    """`enabled=False`는 이 가드가 보지 않는다(C20 — 텔레메트리 실측 뒤 별도 결정).

    비활성 버튼도 clickable=True로 오므로 여기를 통과한다. 두 조건을 한 가드에 섞으면
    오탐의 원인 축을 가를 수 없어서 일부러 남긴 경계다.
    """
    screen = Screen(
        package_name="com.sampleapp",
        elements=[
            UiElement(node_id="n1", label="다음", role="Button", clickable=True, enabled=False),
        ],
    )
    action = _guide(_action(ActionType.TAP, "n1"), screen)
    assert action.target_node_id == "n1"


def test_done_is_not_judged_by_the_tap_guard():
    action = _guide(_action(ActionType.DONE, None), _splash_screen())
    assert action.action_type is ActionType.DONE


def test_tap_on_page_dot_is_rejected():
    """page_dot은 직렬화가 clickable=false로 내보낸다.

    정보원이지 탭 대상이 아니라는 결정(HANDOFF §3-3)과 같은 방향이다.
    """
    screen = Screen(
        package_name="com.sec.android.app.launcher",
        elements=[
            UiElement(node_id="n1", label="6 페이지 중 2 페이지", role="page_dot", clickable=False),
            UiElement(node_id="n2", label="카카오톡", role="View", clickable=True),
        ],
    )
    with pytest.raises(LlmError):
        _guide(_action(ActionType.TAP, "n1"), screen)


# ── 프롬프트 표기 ─────────────────────────────────────────────────────


def _prompt(screen: Screen) -> str:
    return build_prompt(NextStepRequest(goal="치킨을 주문한다", screen=screen))


def _element_lines(screen: Screen) -> str:
    """요소 줄만 잘라낸다.

    **전체 프롬프트로 표기를 단언하면 안 된다** — [CLICKABLE_TARGET_INSTRUCTION]이 문장 안에
    `clickable=True`·`editable=True`를 글자 그대로 담고 있어, 요소 줄에서 그 표기가 통째로
    사라져도 단언이 초록으로 남는다.
    """

    return "\n".join(line for line in _prompt(screen).splitlines() if line.startswith("- id="))


def test_prompt_marks_clickable_only_when_true():
    prompt = _prompt(_shade_screen())
    assert "clickable=True" in prompt
    # False는 표기 자체가 없다 — 모든 줄에 반복되면 값 하나 다른 줄이 묻힌다.
    assert "clickable=False" not in prompt


def test_prompt_marks_editable_and_focused_only_when_true():
    """`editable`·`focused`도 「정보가 있을 때만」 규칙에 든다(C20 · #373).

    표기의 뜻이 `clickable=True`(tap 후보)와 **대칭**이 된다 — `editable=True`는 type 후보다.
    `False`는 표기 자체가 없고, 없음=False가 관례다(계약상 non-null bool이라 미관측이 없다).
    """

    screen = Screen(
        package_name="com.example.app",
        elements=[
            UiElement(
                node_id="n1", label="검색어 입력", role="EditText", editable=True, focused=True
            ),
            UiElement(node_id="n2", label="카카오톡", role="View", clickable=True),
        ],
    )

    lines = _element_lines(screen)

    assert "editable=True" in lines
    assert "focused=True" in lines
    # 모든 줄에 반복되면 값 하나 다른 줄이 묻힌다 — clickable과 같은 근거(C20 ①).
    assert "editable=False" not in lines
    assert "focused=False" not in lines
    # 표기는 **그 요소의 줄에만** 붙는다 — 하나가 focused면 전 줄에 붙이는 구현도 막는다.
    assert lines.count("focused=True") == 1
    kakao_line = next(line for line in lines.splitlines() if "카카오톡" in line)
    assert "focused" not in kakao_line


def test_prompt_omits_focused_on_an_unfocused_input():
    """입력칸이 있는데 포커스가 없는 화면 — `focused` 표기가 통째로 빠진다.

    정보가 죽지는 않는다: `editable=True`는 실리고, 「없음=False」 관례가 나머지를 말한다.
    포커스가 없으므로 [FOCUSED_INPUT_INSTRUCTION]도 안 나가 **설명과 표기가 함께 침묵한다**.
    """

    screen = Screen(
        package_name="com.example.app",
        elements=[
            UiElement(node_id="n1", label="검색어 입력", role="EditText", editable=True),
        ],
    )

    lines = _element_lines(screen)

    assert "editable=True" in lines
    assert "focused" not in lines
    # 표기가 진 뜻을 문장이 받는다 — 그 화면에서 아무도 말하지 않으면 정보가 사라진다(#373).
    assert INPUT_NOT_FOCUSED in _prompt(screen)


def test_prompt_explains_the_selection_rule_when_a_clickable_element_exists():
    prompt = _prompt(_shade_screen())
    assert CLICKABLE_TARGET_INSTRUCTION in prompt
    assert NO_CLICKABLE_ELEMENT_NOTICE not in prompt


def test_prompt_states_the_fact_when_nothing_is_clickable():
    prompt = _prompt(_splash_screen())
    assert NO_CLICKABLE_ELEMENT_NOTICE in prompt
    assert CLICKABLE_TARGET_INSTRUCTION not in prompt


def test_prompt_counts_editable_input_as_a_tap_candidate():
    """입력칸만 있는 화면은 "누를 것이 없다"가 아니다 — 선택 범위 문장이 실린다."""
    screen = Screen(
        package_name="com.sampleapp",
        elements=[
            UiElement(node_id="n1", label="", role="View", clickable=False, editable=True),
        ],
    )
    prompt = _prompt(screen)
    assert CLICKABLE_TARGET_INSTRUCTION in prompt
    assert NO_CLICKABLE_ELEMENT_NOTICE not in prompt


def test_prompt_judges_clickability_from_the_visible_list():
    """가려진 요소만 clickable=True인 화면 — 문장은 요소 목록과 같은 것을 봐야 한다."""
    screen = Screen(
        package_name="com.sampleapp",
        elements=[
            UiElement(
                node_id="n1", label="안내문", role="TextView", clickable=False, obscured=False
            ),
            UiElement(node_id="n2", label="치킨", role="Button", clickable=True, obscured=True),
        ],
    )
    prompt = _prompt(screen)
    assert NO_CLICKABLE_ELEMENT_NOTICE in prompt
    assert CLICKABLE_TARGET_INSTRUCTION not in prompt
