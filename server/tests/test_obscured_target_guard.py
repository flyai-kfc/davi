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
from app.prompt import (
    FOCUSED_INPUT_INSTRUCTION,
    SCROLLABLE_NOT_OBSERVED,
    SCROLLABLE_OBSERVED,
    SCROLLABLE_OBSERVED_BUT_COVERED,
    build_prompt,
)
from app.reasoning import _no_scroll_target_reason, _verify_done, next_step
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
    return next_step(request, FixedLlmClient(action)).action


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


def _covered_list_screen() -> Screen:
    """스크롤 안 되는 모달이 스크롤 목록을 덮은 화면.

    `has_scrollable_element`는 화면 단위 관측이라 앱이 **가려진 노드까지 세서** 채운다.
    그래서 관측 사실만 남고 목록에는 그 요소가 없다.
    """

    return Screen(
        package_name="com.sampleapp",
        has_scrollable_element=True,
        elements=[
            UiElement(node_id="n1", label="닫기", role="View", clickable=True, obscured=False),
            _scrollable("n27", obscured=True),
        ],
    )


def test_scroll_observation_line_says_the_scrollable_is_covered():
    """미끼를 없앤다 — 목록에 스크롤 요소가 없는데 "스크롤 가능하다"고만 말하면 안 된다."""

    prompt = build_prompt(NextStepRequest(goal="치킨을 주문한다", screen=_covered_list_screen()))

    assert SCROLLABLE_OBSERVED_BUT_COVERED in prompt
    assert SCROLLABLE_OBSERVED not in prompt
    # 관측은 실제로 있었으므로 "관측되지 않았다"로 뒤집으면 거짓이 된다.
    assert SCROLLABLE_NOT_OBSERVED not in prompt


def test_scroll_observation_line_is_unchanged_when_the_scrollable_is_visible():
    screen = Screen(
        package_name="com.sampleapp",
        has_scrollable_element=True,
        elements=[_scrollable("n2", obscured=False)],
    )
    prompt = build_prompt(NextStepRequest(goal="치킨을 주문한다", screen=screen))

    assert SCROLLABLE_OBSERVED in prompt
    assert SCROLLABLE_OBSERVED_BUT_COVERED not in prompt


def test_failure_reason_says_covered_not_already_at_the_end():
    """후보가 가려져서 빈 것을 "이미 끝"으로 적으면 실기기 로그가 엉뚱한 데를 판다."""

    reason = _no_scroll_target_reason(_covered_list_screen(), SwipeDirection.DOWN)

    assert "가려져" in reason
    assert "이미 끝" not in reason


def test_completion_evidence_cannot_lean_on_a_hidden_label():
    """모델이 볼 수 없었던 label로 완료가 통과되면 검증이 느슨해진다."""

    screen = Screen(
        package_name="com.sampleapp",
        elements=[
            UiElement(node_id="n1", label="닫기", role="View", clickable=True, obscured=False),
            UiElement(node_id="n2", label="주문이 완료되었습니다", role="View", obscured=True),
        ],
    )
    done = GuideAction(
        expected_result="주문이 완료된 화면",
        screen_summary="배달 앱 화면",
        evidence="주문이 완료되었습니다",
        action_type=ActionType.DONE,
        instruction="주문이 끝났어요.",
    )

    request = NextStepRequest(goal="치킨을 주문한다", screen=screen)

    assert _verify_done(done, request) is False


def test_completion_evidence_still_passes_on_a_visible_label():
    screen = Screen(
        package_name="com.sampleapp",
        elements=[
            UiElement(node_id="n1", label="주문이 완료되었습니다", role="View", obscured=False),
        ],
    )
    done = GuideAction(
        expected_result="주문이 완료된 화면",
        screen_summary="배달 앱 화면",
        evidence="주문이 완료되었습니다",
        action_type=ActionType.DONE,
        instruction="주문이 끝났어요.",
    )

    request = NextStepRequest(goal="치킨을 주문한다", screen=screen)

    assert _verify_done(done, request) is True


def _page_screen(
    *,
    package: str = "com.sec.android.app.launcher",
    obscured: bool | None,
    current_page: int | None = 1,
    total_pages: int | None = 2,
) -> Screen:
    """페이지로 넘기는 화면. 팝업 하나가 그 위를 덮고 있다.

    덮개(`n1`)는 자기 자신이 보이고, 뒤에 깔린 요소(`n2`)만 가려진다 — 실제 관측이 그 모양이라
    "전부 가려짐"(관측 불신)과 구분된다.
    """

    return Screen(
        package_name=package,
        has_scrollable_element=False,
        current_page=current_page,
        total_pages=total_pages,
        elements=[
            UiElement(node_id="n1", label="지금 받기", role="View", clickable=True, obscured=False),
            UiElement(
                node_id="n2", label="카카오톡", role="View", clickable=True, obscured=obscured
            ),
        ],
    )


def _page_scroll(direction: SwipeDirection = SwipeDirection.RIGHT) -> GuideAction:
    return GuideAction(
        expected_result="다음 쪽에서 앱을 찾음",
        screen_summary="홈 화면",
        evidence="이 쪽에는 찾는 앱이 없음",
        action_type=ActionType.SCROLL,
        direction=direction,
        instruction="화면을 옆으로 넘겨 주세요.",
    )


def test_a_covered_page_screen_does_not_get_a_page_move():
    """덮인 화면에서 **화면 자체를 조작하라는 안내**를 막는다(PR #336 리뷰 4R).

    페이지 위치는 화면 단위 상태라(C5) 요소 필터가 닿지 않는다 — 앱은 가려진 dot에서도
    `readPageDot`을 돌린다. 팝업이 제스처를 먹으면 화면이 그대로고 → 정체 → #39로 회복
    경로가 없다. 이 PR이 tap과 2단에서 닫은 실패와 같은 모양이다.
    """

    with pytest.raises(LlmError) as raised:
        _guide(_page_scroll(), _page_screen(obscured=True))

    assert raised.value.kind is ErrorKind.SERVER


def test_an_uncovered_page_screen_still_moves_a_page():
    """덮이지 않았으면 그대로 통과한다 — 가드가 정상 안내를 없애지 않는다(#251 리뷰)."""

    guided = _guide(_page_scroll(), _page_screen(obscured=False))

    assert guided.action_type is ActionType.SCROLL
    assert guided.target_node_id is None


def test_a_covered_page_screen_is_not_a_launcher_only_rule():
    """조건은 런처가 아니라 **페이지 안내가 있는 화면**이다.

    page dot은 "N 페이지 중 M 페이지" 글자로 잡으므로(`UiTreeSerializer.parsePageDot`)
    배너 캐러셀·온보딩처럼 페이지 안내가 붙은 앱 화면이면 어디서든 같은 길이 열린다.
    """

    covered_carousel = _page_screen(package="com.sampleapp", obscured=True)

    with pytest.raises(LlmError) as raised:
        _guide(_page_scroll(), covered_carousel)

    assert raised.value.kind is ErrorKind.SERVER


def test_a_covered_launcher_does_not_fall_through_to_the_app_drawer():
    """4단만 막으면 5단이 대신 나간다 — 앱서랍 열기도 덮인 화면 조작이라 같이 막는다."""

    covered_launcher = _page_screen(obscured=True, current_page=None, total_pages=None)

    with pytest.raises(LlmError) as raised:
        _guide(_page_scroll(SwipeDirection.DOWN), covered_launcher)

    assert raised.value.kind is ErrorKind.SERVER


def test_an_uncovered_launcher_still_switches_to_the_app_drawer():
    uncovered_launcher = _page_screen(obscured=False, current_page=None, total_pages=None)

    guided = _guide(_page_scroll(SwipeDirection.DOWN), uncovered_launcher)

    assert guided.action_type is ActionType.OPEN_APP_DRAWER


def test_an_unobserved_screen_keeps_its_page_move():
    """`None`은 낡은 앱의 미관측이라 덮개로 세지 않는다(C10의 세 상태 규약)."""

    guided = _guide(_page_scroll(), _page_screen(obscured=None))

    assert guided.action_type is ActionType.SCROLL
    assert guided.target_node_id is None


def test_a_fully_obscured_page_screen_keeps_its_page_move():
    """전부 가려졌다고 온 화면은 관측 자체를 불신한다 — 여기서도 같은 판정을 쓴다."""

    unreliable = Screen(
        package_name="com.sec.android.app.launcher",
        has_scrollable_element=False,
        current_page=1,
        total_pages=2,
        elements=[
            UiElement(node_id="n1", label="지금 받기", role="View", obscured=True),
            UiElement(node_id="n2", label="카카오톡", role="View", obscured=True),
        ],
    )

    guided = _guide(_page_scroll(), unreliable)

    assert guided.action_type is ActionType.SCROLL
    assert guided.target_node_id is None


def test_failure_reason_says_the_screen_is_covered_not_that_it_has_no_scrollable():
    """런처는 스크롤 요소가 실제로 0개라, 사유를 안 가르면 "없어서"로 적힌다.

    없어서가 아니라 덮여서 막은 것이라 실기기 로그가 엉뚱한 데를 판다.
    """

    reason = _no_scroll_target_reason(_page_screen(obscured=True), SwipeDirection.RIGHT)

    assert "가려져" in reason
