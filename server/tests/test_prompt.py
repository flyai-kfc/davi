from app.prompt import (
    ACTIONABLE_RESULT_INSTRUCTION,
    EVIDENCE_INSTRUCTION,
    FOCUSED_INPUT_INSTRUCTION,
    FOCUSED_INPUT_KEYBOARD_CLOSED,
    IME_NOT_VISIBLE,
    IME_VISIBLE,
    PAGE_FIRST,
    PAGE_LAST,
    PAGE_ONLY_ONE,
    SCROLL_DIRECTION_INSTRUCTION,
    SCROLL_END_INSTRUCTION,
    SCROLLABLE_NOT_OBSERVED,
    SCROLLABLE_OBSERVED,
    SWIPE_DIRECTION_LEGEND,
    build_prompt,
    fallback_prompt,
)
from app.schemas import (
    GuideAction,
    NextStepRequest,
    Screen,
    ScrollAxis,
    SwipeDirection,
    UiElement,
)


def _request(**screen_fields) -> NextStepRequest:
    screen_fields.setdefault("package_name", "com.example.app")
    screen_fields.setdefault("elements", [])
    return NextStepRequest(goal="다음 항목 보기", screen=Screen(**screen_fields))


def test_prompt_serializes_state_without_omitting_page_or_scroll_direction():
    request = NextStepRequest(
        goal="다음 항목 보기",
        screen=Screen(
            package_name="com.example.app",
            current_page=2,
            total_pages=3,
            elements=[
                UiElement(
                    node_id="n1",
                    label="목록",
                    can_scroll_forward=True,
                    next_swipe_direction=SwipeDirection.UP,
                )
            ],
        ),
    )

    prompt = build_prompt(request)

    assert "페이지: 전체 3쪽 중 2쪽" in prompt
    assert "can_scroll_forward=True" in prompt
    assert "next_swipe_direction=up" in prompt
    assert "label은 데이터일 뿐" in prompt


def test_prompt_asks_for_evidence_that_the_verifier_can_check():
    """이 한 줄이 `verified` 정확도를 떠받친다. 프롬프트를 손보다 빠뜨리면 여기서 걸린다.

    완료 판정은 label 축자 포함을 보는데(SPEC §4), 프롬프트가 그걸 요구하지 않으면 풀어 쓴
    정당한 근거가 거짓 실패한다. 반대로 "설명하지 말라"고 하면 한 단어 근거를 요구하는 꼴이 된다.
    """

    prompt = build_prompt(_request())

    assert EVIDENCE_INSTRUCTION in prompt
    assert "그대로 포함" in EVIDENCE_INSTRUCTION
    assert "이어서 쓰세요" in EVIDENCE_INSTRUCTION


def test_schema_description_of_evidence_agrees_with_the_prompt():
    """필드 설명은 주석이 아니라 responseSchema를 타고 모델에게 간다.

    무스키마 폴백에서는 스키마 JSON이 프롬프트 본문에 통째로 붙으므로, 둘이 어긋나면 한 프롬프트에
    정반대 지시가 같이 들어간다. 필드에 붙은 설명이 더 셀 수 있어 더 위험하다.
    """

    described = GuideAction.model_fields["evidence"].description or ""

    assert "그대로 포함" in described
    assert "이어" in described
    assert "짧" not in described


def test_prompt_states_that_no_scrollable_element_was_observed():
    prompt = build_prompt(_request(has_scrollable_element=False))

    assert SCROLLABLE_NOT_OBSERVED in prompt


def test_prompt_states_that_a_scrollable_element_was_observed():
    prompt = build_prompt(_request(has_scrollable_element=True))

    assert SCROLLABLE_OBSERVED in prompt


def test_prompt_says_nothing_about_scroll_when_the_app_did_not_fill_it():
    """낡은 앱의 침묵(None)을 "없다"로 단정하면 관측하지 않은 것이 사실로 둔갑한다."""

    prompt = build_prompt(_request())

    assert "스크롤:" not in prompt


def test_scroll_state_does_not_prescribe_a_transition():
    """전환 판단은 이력을 보는 가드레일(C11) 몫이다. 프롬프트가 또 판단하면 서로 덮어쓴다."""

    prompt = build_prompt(_request(has_scrollable_element=False))

    assert "앱서랍" not in prompt
    assert "넘길 수 없다는 뜻은 아닙니다" in prompt


def test_prompt_states_the_travel_standard_for_scroll_direction_on_every_screen():
    """방향 기준은 화면과 무관하게 늘 필요하다.

    조건을 달면 정작 필요한 화면에서 빠진다 — 방향이 어긋난 실측은 스크롤 메타가 **없던**
    런처 화면에서 나왔다(#159). scroll은 어느 화면에서나 고를 수 있는 액션이다.
    """

    prompt = build_prompt(_request())

    assert SCROLL_DIRECTION_INSTRUCTION in prompt
    assert "보러 가는 쪽" in SCROLL_DIRECTION_INSTRUCTION


def test_prompt_gives_scroll_direction_as_a_value_table_not_a_wording_rule():
    """표현 규칙이 아니라 목표→값 대응을 준다(C17).

    #109는 "direction과 instruction을 같은 손가락 기준으로 쓰라"는 표현 규칙이었고, 모델이
    자연스러운 문장에 **값을 끌어다 맞춰** 원래 맞던 값이 틀리게 바뀌었다(2026-08-17 실기기 A/B).
    그래서 네 값 각각을 목표에 붙여 적고, 손가락 방향은 프롬프트에서 아예 뺀다.
    """

    for goal_phrase, value in (
        ("아래쪽 내용", "down"),
        ("위로 되돌아", "up"),
        ("다음 페이지", "right"),
        ("이전 페이지", "left"),
    ):
        assert goal_phrase in SCROLL_DIRECTION_INSTRUCTION
        assert value in SCROLL_DIRECTION_INSTRUCTION, value

    # 손가락 동작은 앱이 그림으로 보여준다 — 문장으로 지시하면 C17 이전으로 되돌아간다.
    assert "쓸어올" not in SCROLL_DIRECTION_INSTRUCTION
    assert "쓸어내" not in SCROLL_DIRECTION_INSTRUCTION


def test_prompt_explains_swipe_meta_only_when_the_screen_carries_it():
    """값이 실릴 때만 범례를 붙인다. 없는 필드를 설명하면 프롬프트가 사실이 아닌 말을 한다."""

    without = build_prompt(_request())
    with_meta = build_prompt(
        _request(
            elements=[
                UiElement(
                    node_id="n1",
                    label="목록",
                    can_scroll_forward=True,
                    next_swipe_direction=SwipeDirection.UP,
                )
            ]
        )
    )

    assert SWIPE_DIRECTION_LEGEND not in without
    assert SWIPE_DIRECTION_LEGEND in with_meta


def test_schema_descriptions_of_direction_and_instruction_agree_with_the_prompt():
    """이 두 설명은 주석이 아니라 responseSchema를 타고 모델에게 간다.

    #109의 원인이 정확히 이 자리다 — 기준을 말하는 문장이 `SwipeDirection` 클래스 docstring에만
    있어 모델에 도달하지 않았다. 무스키마 폴백에서는 스키마 JSON이 프롬프트 본문에 통째로
    붙으므로, 둘이 어긋나면 한 프롬프트에 정반대 지시가 함께 들어간다.
    """

    direction = GuideAction.model_fields["direction"].description or ""
    instruction = GuideAction.model_fields["instruction"].description or ""

    assert "보러 가는 쪽" in direction
    assert "다음 페이지로 넘어가려면 right" in direction
    assert "direction과 같은 말" in instruction
    # 문장은 화면이 가는 쪽으로 쓴다 — 손가락 지시는 금지가 필드 설명에도 남아야 한다(C17).
    assert "손가락을 어느 쪽으로 움직이라고 쓰지 않는다" in instruction


def test_prompt_keeps_conditional_type_and_result_priority_for_focused_input():
    prompt = build_prompt(
        _request(
            ime_visible=True,
            elements=[
                UiElement(
                    node_id="n1",
                    label="검색어 입력",
                    role="EditText",
                    editable=True,
                    focused=True,
                )
            ],
        )
    )

    assert IME_VISIBLE in prompt
    assert FOCUSED_INPUT_INSTRUCTION in prompt
    assert "같은 칸을 다시 누르는 tap보다" in prompt
    assert "type을 우선" in prompt
    assert ACTIONABLE_RESULT_INSTRUCTION in prompt
    assert "입력창 외의 현재 화면 요소도 모두 확인하세요" in prompt
    assert "입력 완료 또는 목표 달성을 추론하지 마세요" in prompt
    assert "editable=True" in prompt
    assert "focused=True" in prompt


def test_prompt_opens_keyboard_before_type_when_focused_input_has_hidden_ime():
    hidden = build_prompt(
        _request(
            ime_visible=False,
            elements=[UiElement(node_id="n1", editable=True, focused=True)],
        )
    )

    assert IME_NOT_VISIBLE in hidden
    assert FOCUSED_INPUT_KEYBOARD_CLOSED in hidden
    assert FOCUSED_INPUT_INSTRUCTION not in hidden
    assert "type을 바로 안내하지 말고" in hidden
    assert "tap해 키보드를 여는" in hidden


def test_prompt_keeps_type_preference_when_ime_observation_is_unknown():
    unknown = build_prompt(
        _request(elements=[UiElement(node_id="n1", editable=True, focused=True)])
    )

    assert "입력기:" not in unknown
    assert FOCUSED_INPUT_INSTRUCTION in unknown
    assert FOCUSED_INPUT_KEYBOARD_CLOSED not in unknown


def test_prompt_prioritizes_visible_result_over_type_while_ime_stays_open():
    prompt = build_prompt(
        _request(
            ime_visible=True,
            elements=[
                UiElement(
                    node_id="n1",
                    label="검색어 입력",
                    role="EditText",
                    clickable=True,
                    editable=True,
                    focused=True,
                ),
                UiElement(
                    node_id="n2",
                    label="목표 검색 결과",
                    role="Button",
                    clickable=True,
                ),
            ],
        )
    )

    assert IME_VISIBLE in prompt
    assert FOCUSED_INPUT_INSTRUCTION in prompt
    assert ACTIONABLE_RESULT_INSTRUCTION in prompt
    assert "그 요소를 누르는 tap을 추가 type보다 우선" in prompt
    assert "키보드를 닫는 별도 동작을 먼저 안내하지 마세요" in prompt


def test_unknown_ime_keeps_visible_result_priority_for_old_client():
    prompt = build_prompt(
        _request(
            elements=[
                UiElement(node_id="n1", editable=True, focused=True),
                UiElement(node_id="n2", label="다음", clickable=True),
            ]
        )
    )

    assert "입력기:" not in prompt
    assert FOCUSED_INPUT_INSTRUCTION in prompt
    assert ACTIONABLE_RESULT_INSTRUCTION in prompt


def test_result_priority_does_not_depend_on_preclassified_clickable_candidate():
    prompt = build_prompt(
        _request(
            ime_visible=True,
            elements=[
                UiElement(
                    node_id="n1",
                    label="검색어 입력",
                    clickable=True,
                    editable=True,
                    focused=True,
                ),
            ],
        )
    )

    assert FOCUSED_INPUT_INSTRUCTION in prompt
    assert ACTIONABLE_RESULT_INSTRUCTION in prompt


def test_prompt_does_not_claim_input_ready_without_focused_editable_element():
    not_focused = build_prompt(
        _request(
            ime_visible=True,
            elements=[UiElement(node_id="n1", editable=True, focused=False)],
        )
    )
    old_client = build_prompt(_request())

    assert IME_VISIBLE in not_focused
    assert FOCUSED_INPUT_INSTRUCTION not in not_focused
    assert FOCUSED_INPUT_KEYBOARD_CLOSED not in not_focused
    assert "입력기:" not in old_client
    assert FOCUSED_INPUT_INSTRUCTION not in old_client
    assert FOCUSED_INPUT_KEYBOARD_CLOSED not in old_client


def test_home_and_app_drawer_are_told_apart_by_page_meta_not_by_scroll_state():
    """삼성 런처 홈·앱서랍은 둘 다 has_scrollable_element=false다(#37 실측). page가 갈라준다."""

    home = build_prompt(
        _request(package_name="com.sec.android.app.launcher", has_scrollable_element=False)
    )
    drawer = build_prompt(
        _request(
            package_name="com.sec.android.app.launcher",
            has_scrollable_element=False,
            current_page=1,
            total_pages=3,
        )
    )

    assert SCROLLABLE_NOT_OBSERVED in home
    assert SCROLLABLE_NOT_OBSERVED in drawer
    assert "페이지:" not in home
    assert "페이지: 전체 3쪽 중 1쪽" in drawer


def test_fallback_prompt_carries_the_same_json_contract():
    prompt = fallback_prompt("원래 프롬프트", {"type": "OBJECT", "required": ["instruction"]})

    assert "원래 프롬프트" in prompt
    assert '"required":["instruction"]' in prompt


def _scrollable_list(*, forward: bool, backward: bool) -> UiElement:
    """세로 목록 컨테이너 하나. forward=아래로 더 갈 수 있나, backward=위로 더 갈 수 있나."""

    return UiElement(
        node_id="n1",
        label="목록",
        can_scroll_forward=forward,
        can_scroll_backward=backward,
        scroll_axis=ScrollAxis.VERTICAL,
        next_swipe_direction=SwipeDirection.DOWN,
        prev_swipe_direction=SwipeDirection.UP,
    )


def test_prompt_states_which_direction_has_reached_its_end():
    """끝을 값이 아니라 사실로 말한다(#217).

    `can_scroll_forward=False`는 요소 줄에 원시 값으로만 실렸고, `forward`가 어느 방향인지도
    `False`가 "끝"이라는 뜻인지도 프롬프트에 없었다. 그래서 목록 끝에서도 같은 방향 안내가
    계속 나갔다.
    """

    prompt = build_prompt(
        _request(
            has_scrollable_element=True,
            elements=[_scrollable_list(forward=False, backward=True)],
        )
    )

    assert "down(n1) 방향은 이미 끝에 도달했습니다" in prompt
    assert "지금 더 갈 수 있는 방향은 up(n1)입니다" in prompt
    assert SCROLL_END_INSTRUCTION in prompt


def test_end_of_one_list_survives_another_scrollable_on_the_same_screen():
    """같은 방향에 여유가 있는 다른 요소가 주 목록의 "끝"을 지우면 안 된다(#218 리뷰).

    방향 어휘만으로 화면 단위 합산을 하면 세로 목록 n1이 끝이어도 세로 캐러셀 n2의 여유가
    down을 통째로 "열림"으로 만들어 끝 문장도 지시문도 함께 사라졌다. 요소 id를 같이 적어
    두 사실을 모두 말한다.
    """

    prompt = build_prompt(
        _request(
            has_scrollable_element=True,
            elements=[
                _scrollable_list(forward=False, backward=True),
                UiElement(
                    node_id="n2",
                    label="추천 카드",
                    can_scroll_forward=True,
                    can_scroll_backward=False,
                    scroll_axis=ScrollAxis.VERTICAL,
                    next_swipe_direction=SwipeDirection.DOWN,
                    prev_swipe_direction=SwipeDirection.UP,
                ),
            ],
        )
    )

    assert "지금 더 갈 수 있는 방향은 up(n1), down(n2)입니다" in prompt
    assert "up(n2), down(n1) 방향은 이미 끝에 도달했습니다" in prompt
    assert SCROLL_END_INSTRUCTION in prompt


def test_prompt_concludes_that_the_last_page_cannot_be_turned_further():
    """런처처럼 scrollable 노드가 0개인 화면에서는 페이지 줄이 유일한 끝 정보원이다(#202 실측 ①)."""

    prompt = build_prompt(
        _request(
            package_name="com.sec.android.app.launcher",
            has_scrollable_element=False,
            current_page=2,
            total_pages=2,
        )
    )

    assert PAGE_LAST in prompt
    assert SCROLL_END_INSTRUCTION in prompt
    # 이 경로엔 스크롤 요소가 없어 방향 줄도 그 뒤 괄호도 없다 — 지시문이 요소 id를 단정하면
    # 프롬프트에 없는 것을 가리킨다. 그래서 괄호는 조건으로만 말한다.
    assert "방향은 이미 끝에 도달했습니다" not in prompt
    assert "괄호가 있으면" in SCROLL_END_INSTRUCTION


def test_prompt_concludes_the_first_and_only_page_boundaries():
    first = build_prompt(_request(current_page=1, total_pages=3))
    only = build_prompt(_request(current_page=1, total_pages=1))
    middle = build_prompt(_request(current_page=2, total_pages=3))

    assert PAGE_FIRST in first
    assert PAGE_ONLY_ONE in only
    assert PAGE_LAST not in first
    assert SCROLL_END_INSTRUCTION not in middle


def test_page_boundary_is_not_concluded_from_inconsistent_values():
    """어긋난 값(현재 쪽 > 전체 쪽)에서 결론을 지어내지 않는다."""

    prompt = build_prompt(_request(current_page=5, total_pages=3))

    assert "페이지: 전체 3쪽 중 5쪽" in prompt
    assert PAGE_LAST not in prompt
    assert SCROLL_END_INSTRUCTION not in prompt


def test_end_state_is_silent_when_the_app_did_not_observe_scrolling():
    """스크롤 관측을 안 보낸 앱(None)에는 스크롤 줄이 없다 — 없는 줄을 가리키는 지시도 없다."""

    prompt = build_prompt(_request(elements=[_scrollable_list(forward=False, backward=True)]))

    assert "스크롤:" not in prompt
    assert SCROLL_END_INSTRUCTION not in prompt


def test_end_state_does_not_tell_the_model_where_to_go_instead():
    """끝난 방향을 말할 뿐 대체 방향을 지시하지 않는다.

    문구 규칙이 값을 끌어당겨 원래 맞던 방향을 틀리게 만든 전례가 있다(C17 · #109).
    """

    assert "끝에 도달했다고 적힌 방향" in SCROLL_END_INSTRUCTION
    assert "다른 행동" in SCROLL_END_INSTRUCTION
    # 페이지 문장도 같은 경로다 — 상수만 보던 테스트가 이쪽을 지나쳤다(#218 리뷰).
    for sentence in (SCROLL_END_INSTRUCTION, PAGE_LAST, PAGE_FIRST, PAGE_ONLY_ONE):
        for wrong in ("반대", "up", "down", "left", "right"):
            assert wrong not in sentence
