from app.prompt import build_prompt, fallback_prompt
from app.schemas import NextStepRequest, Screen, SwipeDirection, UiElement


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


def test_fallback_prompt_carries_the_same_json_contract():
    prompt = fallback_prompt("원래 프롬프트", {"type": "OBJECT", "required": ["instruction"]})

    assert "원래 프롬프트" in prompt
    assert '"required":["instruction"]' in prompt
