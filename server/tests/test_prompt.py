from app.prompt import (
    SCROLLABLE_NOT_OBSERVED,
    SCROLLABLE_OBSERVED,
    build_prompt,
    fallback_prompt,
)
from app.schemas import NextStepRequest, Screen, SwipeDirection, UiElement


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
