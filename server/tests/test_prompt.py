from app.prompt import (
    ACTIONABLE_RESULT_INSTRUCTION,
    EVIDENCE_INSTRUCTION,
    FOCUSED_INPUT_INSTRUCTION,
    FOCUSED_INPUT_KEYBOARD_CLOSED,
    IME_NOT_VISIBLE,
    IME_VISIBLE,
    SCROLLABLE_NOT_OBSERVED,
    SCROLLABLE_OBSERVED,
    build_prompt,
    fallback_prompt,
)
from app.schemas import (
    GuideAction,
    NextStepRequest,
    Screen,
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


def test_prompt_prioritizes_type_when_focused_input_has_no_actionable_result():
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
    assert ACTIONABLE_RESULT_INSTRUCTION not in prompt
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


def test_unlabeled_or_editable_clickable_element_is_not_treated_as_result():
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
                UiElement(node_id="n2", label="", clickable=True),
            ],
        )
    )

    assert FOCUSED_INPUT_INSTRUCTION in prompt
    assert ACTIONABLE_RESULT_INSTRUCTION not in prompt


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
