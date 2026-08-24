"""복사 신호(`input_text`)가 확인받은 목표 문장에서만 나오는지 고정한다(C21).

**왜 코드로 받는가.** 타이핑은 사용자가 화면을 보며 한 글자씩 치므로 틀린 값이 손에서 걸리지만,
이 필드를 소비하는 경로에는 그 단계가 없다. 그래서 "모델이 지어낸 말"과 "사용자가 확인 화면에서
승인한 말"을 서버가 가른다 — 프롬프트가 먼저 좁히고(`prompt.INPUT_TEXT_INSTRUCTION`),
가드가 마지막에 목표 문장과 대조한다.

경계 규칙(어절 시작 · 조사 허용)의 근거는 여기 옮겨 적지 않는다 —
`reasoning._contains_quoted_phrase` 한 곳에 있고, 완료 근거 대조와 공유한다(루트 CLAUDE.md).
"""

from __future__ import annotations

import pytest
from app.prompt import INPUT_TEXT_INSTRUCTION, build_prompt
from app.reasoning import _goal_quotes_input_text, next_step
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


# ── 목표 문장에서 온 값만 남는다 ────────────────────────────────────────


def test_input_text_quoted_from_goal_is_kept():
    action = _guide(_action(ActionType.TYPE, "교촌치킨"))
    assert action.input_text == "교촌치킨"


def test_input_text_kept_when_goal_attaches_a_particle():
    """목표에는 조사가 붙어 있고 입력칸에 넣을 말은 붙지 않은, 가장 흔한 모양."""
    action = _guide(_action(ActionType.TYPE, "테헤란로 4번지"), goal="테헤란로 4번지로 안내해줘")
    assert action.input_text == "테헤란로 4번지"


def test_input_text_kept_across_spacing_difference():
    action = _guide(_action(ActionType.TYPE, "교촌치킨"), goal="교촌 치킨 시켜줘")
    assert action.input_text == "교촌치킨"


def test_invented_input_text_is_dropped():
    """목표에 없는 가게 이름 — 사용자가 승인한 적 없는 값이라 내보내지 않는다."""
    action = _guide(_action(ActionType.TYPE, "BBQ치킨"))
    assert action.input_text is None


def test_input_text_starting_mid_word_is_dropped():
    """어절 중간에서 시작하는 조각은 인용이 아니다 — 완료 근거와 같은 경계 규칙."""
    action = _guide(_action(ActionType.TYPE, "촌치킨"))
    assert action.input_text is None


@pytest.mark.parametrize("blank", ["", "   "])
def test_blank_input_text_is_dropped(blank: str):
    action = _guide(_action(ActionType.TYPE, blank))
    assert action.input_text is None


# ── type이 아니면 값이 없다 ────────────────────────────────────────────


@pytest.mark.parametrize("action_type", [ActionType.TAP, ActionType.DONE])
def test_input_text_is_dropped_for_non_type_actions(action_type: ActionType):
    """목표에 있는 말이어도 마찬가지다 — tap 화면에 복사 제안이 뜨는 경로를 남기지 않는다."""
    action = _guide(_action(action_type, "교촌치킨"))
    assert action.input_text is None


def test_absent_input_text_stays_absent():
    action = _guide(_action(ActionType.TYPE, None))
    assert action.input_text is None


# ── 프롬프트 ───────────────────────────────────────────────────────────


def test_prompt_carries_the_rule_when_an_input_exists():
    prompt = build_prompt(NextStepRequest(goal=GOAL, screen=_search_screen()))
    assert INPUT_TEXT_INSTRUCTION in prompt


def test_prompt_omits_the_rule_without_an_input():
    """입력칸이 없으면 type을 고를 자리가 아니다 — 없는 상황을 설명하지 않는다."""
    prompt = build_prompt(NextStepRequest(goal=GOAL, screen=_list_screen()))
    assert INPUT_TEXT_INSTRUCTION not in prompt


# ── 어절 경계가 만든 오탐(PR #406 리뷰) ──────────────────────────────


# 토큰화(`\w+`)는 구두점을 경계로 삼는데 그 경계가 **원문에는 없던 것**일 수 있다.
# 잘린 복합값이 두 어절의 경계에 정확히 맞아떨어져 "인용됨"으로 통과했다 — 리뷰 재현 그대로.
@pytest.mark.parametrize(
    ("input_text", "goal"),
    [
        ("010-1234", "전화번호는 010-1234-5678 입니다"),
        ("1234-5678", "전화번호는 010-1234-5678 입니다"),
        ("naver", "naver.com에 접속해줘"),
        ("2026-08", "2026-08-24에 알람 맞춰줘"),
    ],
)
def test_truncated_connected_value_is_not_quoted(input_text: str, goal: str) -> None:
    """값 연결자로 이어진 복합값의 **조각**은 인용이 아니다."""

    assert not _goal_quotes_input_text(input_text, goal)


def test_whole_connected_value_is_quoted() -> None:
    """조각이 아니라 통째면 그대로 통과한다 — 좁힌 것이 값 전체까지 막으면 안 된다."""

    assert _goal_quotes_input_text("010-1234-5678", "전화번호는 010-1234-5678 입니다")


@pytest.mark.parametrize(
    ("input_text", "goal"),
    [
        # 괄호·쉼표는 값을 잇는 자리가 아니라 묶거나 나열하는 자리다. 연결자로 넣으면 이
        # 인용들이 막히고, 같은 함수를 쓰는 완료 근거 대조가 먼저 손해를 본다.
        ("설정", "설정(Settings)을 열어줘"),
        ("사과", "사과,배를 사줘"),
        # 구두점 차이를 흡수하는 원래 성질은 그대로다.
        ("WiFi", "와이파이 Wi-Fi 설정을 켜줘"),
    ],
)
def test_grouping_punctuation_still_allows_quoting(input_text: str, goal: str) -> None:
    assert _goal_quotes_input_text(input_text, goal)


def test_single_character_is_not_copied() -> None:
    """어절 경계에 우연히 걸린 한 글자. 아낄 타이핑이 없어 버튼을 띄울 값이 아니다."""

    assert not _goal_quotes_input_text("그", "그 집으로 가자")


# ── 안내문이 집어 말한 값만 채운다(#406 리뷰 후속, 실기기 제보) ─────

# 제보: 목적어가 없는 목표면 안내는 「검색창에 입력하세요」만 나오는데 버튼을 누르면 목표가
# 그대로 들어갔다. 출처(목표) 판정만으로는 못 막는다 — 목표는 자기 자신을 인용한다.
# 그래서 **사용자가 지금 읽는 문장**이 그 말을 집어 말하는지도 함께 본다.


def test_input_text_not_named_by_the_instruction_is_dropped() -> None:
    action = _guide(
        _action(ActionType.TYPE, GOAL, instruction="검색어를 입력해 주세요"),
        goal=GOAL,
    )
    assert action.input_text is None


def test_input_text_named_by_the_instruction_is_kept() -> None:
    action = _guide(
        _action(ActionType.TYPE, "교촌치킨", instruction="검색창에 '교촌치킨'을 입력해 주세요"),
    )
    assert action.input_text == "교촌치킨"


@pytest.mark.parametrize(
    "instruction",
    [
        "검색창에 '교촌치킨'을 입력해 주세요",
        "검색창에 「교촌치킨」을 입력해 주세요",
        "검색창에 교촌치킨이라고 입력하세요",
        "교촌치킨 을 입력해 주세요",
    ],
)
def test_instruction_check_does_not_parse_quote_marks(instruction: str) -> None:
    """따옴표 모양을 맞추지 않는다 — 값이 문장 안에 있기만 하면 된다.

    구조화 필드를 둔 이유(모델 문구가 조금만 달라져도 깨진다, C21)를 되살리지 않으려는 것이다.
    """
    action = _guide(_action(ActionType.TYPE, "교촌치킨", instruction=instruction))
    assert action.input_text == "교촌치킨"


def test_goal_check_still_applies_when_the_instruction_names_it() -> None:
    """안내문이 불러도 목표에 없는 말이면 비운다 — 두 축은 서로를 대신하지 않는다."""
    action = _guide(
        _action(ActionType.TYPE, "굽네치킨", instruction="검색창에 '굽네치킨'을 입력해 주세요"),
    )
    assert action.input_text is None


def test_goal_that_is_only_the_search_term_is_kept() -> None:
    """목표가 검색어 자체인 짧은 경우. 「목표 전체면 버린다」로 막았다면 죽었을 정상 경로다."""
    action = _guide(
        _action(
            ActionType.TYPE, "종로 칼국수", instruction="검색창에 '종로 칼국수'를 입력해 주세요"
        ),
        goal="종로 칼국수",
    )
    assert action.input_text == "종로 칼국수"
