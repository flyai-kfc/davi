"""복사 신호(`input_text`)가 **안내문이 집어 말한 값**일 때만 나가는지 고정한다(C21).

**왜 코드로 받는가.** 타이핑은 사용자가 화면을 보며 한 글자씩 치므로 틀린 값이 손에서 걸리지만,
버튼에는 그 단계가 없다. 대신 사용자는 **버튼을 누르기 직전에 안내문을 읽는다** — 채워지는 값이
그 문장에 있는 말과 같으면 버튼은 눈으로 확인된 일만 한다. 그 등가를 서버가 보장한다.

**목표 문장과는 대조하지 않는다**(2026-08-24 개정). 검색어는 목표에 그대로 있지 않은 것이
정상이라(`한국이랑 미국의 시차를 알고 싶어` -> `한국 미국 시차`) 출처를 목표로 좁히면 정상
안내에서 버튼이 사라지거나 모델이 어색한 검색어를 쓰게 된다.

경계 규칙(어절 시작 · 조사 허용 · 값 연결자 배제)의 근거는 여기 옮겨 적지 않는다 —
`reasoning._contains_quoted_phrase` 한 곳에 있고, 완료 근거 대조와 공유한다(루트 CLAUDE.md).
"""

from __future__ import annotations

import pytest
from app.prompt import INPUT_TEXT_INSTRUCTION, build_prompt
from app.reasoning import _instruction_quotes_input_text, next_step
from app.schemas import (
    ActionType,
    GuideAction,
    NextStepRequest,
    Screen,
    UiElement,
)

from tests.conftest import FixedLlmClient

GOAL = "교촌치킨에서 후라이드 시켜줘"


def _search_screen() -> Screen:
    """검색창이 열린 화면 — 입력칸과 결과 목록이 함께 있다."""
    return Screen(
        package_name="com.sampleapp",
        elements=[
            UiElement(
                node_id="n1",
                label="검색어를 입력하세요",
                role="EditText",
                clickable=True,
                editable=True,
                focused=True,
            ),
            UiElement(node_id="n2", label="최근 검색어", role="TextView", clickable=False),
        ],
    )


def _list_screen() -> Screen:
    """입력칸이 없는 화면."""
    return Screen(
        package_name="com.sampleapp",
        elements=[UiElement(node_id="n1", label="주문 내역", role="Button", clickable=True)],
    )


def _action(
    action_type: ActionType,
    input_text: str | None,
    instruction: str | None = None,
) -> GuideAction:
    """기본 안내문은 **값을 집어 말한다** — 가드가 그것까지 요구하기 때문이다.

    안내문이 값을 안 부르는 경우는 그 자체가 검사 대상이라 테스트가 직접 넘긴다.
    """
    return GuideAction(
        expected_result="검색 결과가 보이는 상태",
        screen_summary="배달 앱의 검색 화면",
        evidence="'검색어를 입력하세요'가 보임",
        action_type=action_type,
        target_node_id="n1",
        input_text=input_text,
        instruction=instruction
        or (
            f"검색창에 '{input_text}'를 입력해 주세요"
            if input_text
            else "검색창에 가게 이름을 넣어 주세요"
        ),
    )


def _guide(action: GuideAction, goal: str = GOAL, screen: Screen | None = None) -> GuideAction:
    request = NextStepRequest(goal=goal, screen=screen or _search_screen())
    return next_step(request, FixedLlmClient(action)).action


# ── 안내문이 집어 말한 값만 남는다 ──────────────────────────────────


def test_input_text_named_by_the_instruction_is_kept():
    action = _guide(
        _action(ActionType.TYPE, "교촌치킨", instruction="검색창에 '교촌치킨'을 입력해 주세요")
    )
    assert action.input_text == "교촌치킨"


def test_input_text_not_named_by_the_instruction_is_dropped():
    """안내문은 두루뭉술한데 값만 온 경우 — 사용자가 읽지 않은 말이 채워진다."""
    action = _guide(_action(ActionType.TYPE, "교촌치킨", instruction="검색어를 입력해 주세요"))
    assert action.input_text is None


def test_input_text_absent_from_the_goal_is_kept_when_the_instruction_names_it():
    """검색어는 목표에 그대로 없는 것이 정상이다 — 개정 전 규칙이 죽이던 자리."""
    action = _guide(
        _action(
            ActionType.TYPE,
            "한국 미국 시차",
            instruction="검색창에 '한국 미국 시차'라고 입력해 주세요",
        ),
        goal="한국이랑 미국의 시차를 알고 싶어",
    )
    assert action.input_text == "한국 미국 시차"


@pytest.mark.parametrize(
    "instruction",
    [
        "검색창에 '교촌치킨'을 입력해 주세요",
        "검색창에 「교촌치킨」을 입력해 주세요",
        "검색창에 교촌치킨이라고 입력하세요",
        "교촌치킨 을 입력해 주세요",
    ],
)
def test_instruction_check_does_not_parse_quote_marks(instruction: str):
    """따옴표 모양을 맞추지 않는다 — 값이 문장 안에 있기만 하면 된다.

    구조화 필드를 둔 이유(모델 문구가 조금만 달라져도 깨진다, C21)를 되살리지 않으려는 것이다.
    """
    action = _guide(_action(ActionType.TYPE, "교촌치킨", instruction=instruction))
    assert action.input_text == "교촌치킨"


def test_type_only():
    """type이 아니면 계약상 뜻이 없다 — 안내문이 불러도 비운다."""
    action = _guide(_action(ActionType.TAP, "교촌치킨", instruction="'교촌치킨'을 눌러 주세요"))
    assert action.input_text is None


def test_single_character_is_not_copied():
    """한 글자는 버튼이 아낄 타이핑이 없다."""
    action = _guide(_action(ActionType.TYPE, "그", instruction="검색창에 '그'를 입력해 주세요"))
    assert action.input_text is None


# ── 어절 경계가 만든 오탐(PR #406 리뷰) ──────────────────────────────


# 토큰화(`\w+`)는 구두점을 경계로 삼는데 그 경계가 **원문에는 없던 것**일 수 있다.
# 잘린 복합값이 두 어절의 경계에 정확히 맞아떨어져 "인용됨"으로 통과했다 — 리뷰 재현 그대로.
@pytest.mark.parametrize(
    ("input_text", "instruction"),
    [
        ("010-1234", "전화번호는 010-1234-5678 입니다"),
        ("1234-5678", "전화번호는 010-1234-5678 입니다"),
        ("naver", "naver.com에 접속해줘"),
        ("2026-08", "2026-08-24에 알람 맞춰줘"),
    ],
)
def test_truncated_connected_value_is_not_quoted(input_text: str, instruction: str) -> None:
    """값 연결자로 이어진 복합값의 **조각**은 인용이 아니다."""

    assert not _instruction_quotes_input_text(input_text, instruction)


def test_whole_connected_value_is_quoted() -> None:
    """조각이 아니라 통째면 그대로 통과한다 — 좁힌 것이 값 전체까지 막으면 안 된다."""

    assert _instruction_quotes_input_text("010-1234-5678", "전화번호는 010-1234-5678 입니다")


@pytest.mark.parametrize(
    ("input_text", "instruction"),
    [
        # 괄호·쉼표는 값을 잇는 자리가 아니라 묶거나 나열하는 자리다. 연결자로 넣으면 이
        # 인용들이 막히고, 같은 함수를 쓰는 완료 근거 대조가 먼저 손해를 본다.
        ("설정", "설정(Settings)을 열어줘"),
        ("사과", "사과,배를 사줘"),
        # 구두점 차이를 흡수하는 원래 성질은 그대로다.
        ("WiFi", "와이파이 Wi-Fi 설정을 켜줘"),
    ],
)
def test_grouping_punctuation_still_allows_quoting(input_text: str, instruction: str) -> None:
    assert _instruction_quotes_input_text(input_text, instruction)


# ── 지어낸 민감값·목표 echo는 코드가 받는다(PR #406 재리뷰) ───────────

# 안내문 대조만 남기면 `input_text`와 `instruction`이 같은 호출에서 나온 두 필드라,
# 대조가 보는 것은 내용의 사실 여부가 아니라 둘의 자기 일관성뿐이다. 두 자리를 코드가 막는다.


@pytest.mark.parametrize(
    ("value", "instruction"),
    [
        ("010-9999-8888", "연락처 입력칸에 '010-9999-8888'을 입력해 주세요"),
        ("123-456-789012", "계좌번호 '123-456-789012'를 입력해 주세요"),
        ("hong@example.com", "'hong@example.com'을 입력해 주세요"),
    ],
)
def test_sensitive_looking_values_are_dropped(value: str, instruction: str) -> None:
    """모델이 지어내도 안내문에 같이 쓰면 대조를 통과한다 — 그래서 모양으로 막는다.

    사용자가 스스로 정해야 하는 값이라 애초에 버튼이 대신할 자리가 아니다(C21).
    """
    action = _guide(_action(ActionType.TYPE, value, instruction=instruction))
    assert action.input_text is None


def test_goal_echoed_into_both_fields_is_dropped() -> None:
    """실기기에서 재현됐던 버그. 프롬프트가 막고 있지만 코드도 받는다."""
    goal = "인터넷에 검색해 줘"
    action = _guide(
        _action(ActionType.TYPE, goal, instruction=f"검색창에 '{goal}'라고 입력해 주세요"),
        goal=goal,
    )
    assert action.input_text is None


# ── 값이 없을 때 (회귀) ──────────────────────────────────────────────


@pytest.mark.parametrize("value", [None, "", "   "])
def test_absent_input_text_stays_absent(value: str | None) -> None:
    action = _guide(_action(ActionType.TYPE, value, instruction="검색어를 입력해 주세요"))
    assert not action.input_text


# ── 프롬프트에 지시가 실리는 조건 (회귀) ─────────────────────────────


def test_instruction_is_included_when_an_input_field_is_visible() -> None:
    prompt = build_prompt(NextStepRequest(goal=GOAL, screen=_search_screen()))
    assert INPUT_TEXT_INSTRUCTION in prompt


def test_instruction_is_omitted_without_an_input_field() -> None:
    """C18의 「정보가 있을 때만」 — 입력칸이 없는 화면에는 싣지 않는다."""
    prompt = build_prompt(NextStepRequest(goal=GOAL, screen=_list_screen()))
    assert INPUT_TEXT_INSTRUCTION not in prompt
