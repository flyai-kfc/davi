"""핑퐁 슬롯 채우기 순수 함수의 대화 프로토콜을 고정한다."""

import pytest
from app.intent_resolution import resolve_intent
from app.schemas import SlotFillActionType, SlotFillRequest, SlotFillState
from pydantic import ValidationError


def _request(utterance: str, history: list[dict[str, str]] | None = None) -> SlotFillRequest:
    return SlotFillRequest(
        utterance=utterance,
        session_id="session-test",
        conversation_history=history or [],
    )


def test_person_directed_action_requires_target_person():
    state = resolve_intent(_request("보호자님께 사진 보내줘"))

    assert state.action_type is SlotFillActionType.PERSON_DIRECTED
    assert "target_person" in state.slots


def test_general_action_does_not_require_target_person():
    state = resolve_intent(_request("날씨 검색해줘"))

    assert state.action_type is SlotFillActionType.GENERAL
    assert "target_person" not in state.slots
    assert "target_person" not in state.missing_slots


def test_asks_only_the_first_missing_slot():
    state = resolve_intent(_request("사진 보내줘"))

    assert len(state.missing_slots) > 1
    assert state.confirmation_question == "어느 앱에서 할까요?"
    assert state.confirmation_question.count("?") == 1


def test_missing_slots_are_not_guessed():
    state = resolve_intent(_request("뭐 좀 해줘"))

    for name in state.missing_slots:
        assert state.slots[name] is None


def test_action_type_is_fixed_by_first_user_utterance():
    history = [
        {"role": "user", "content": "보호자님께 보내줘"},
        {"role": "assistant", "content": "누구에게 보내드릴까요?"},
    ]

    state = resolve_intent(_request("보호자", history))

    assert state.action_type is SlotFillActionType.PERSON_DIRECTED
    assert state.slots["target_person"] == "보호자"


def test_ping_pong_reaches_confirmation_and_goal():
    history: list[dict[str, str]] = []
    utterance = "보호자님께 보내줘"
    answers = iter(["보호자", "메신저", "사진", "전송"])

    state = resolve_intent(_request(utterance, history))
    while state.missing_slots:
        history += [
            {"role": "user", "content": utterance},
            {"role": "assistant", "content": state.confirmation_question},
        ]
        utterance = next(answers)
        state = resolve_intent(_request(utterance, history))

    assert all(value is not None for value in state.slots.values())
    assert state.goal
    assert state.goal != state.confirmation_question
    assert "맞으시죠" not in state.goal
    for value in state.slots.values():
        assert value in state.confirmation_question
        assert value in state.goal


def test_goal_is_absent_until_all_slots_are_filled():
    state = resolve_intent(_request("보호자님께 보내줘"))

    assert state.missing_slots
    assert state.goal is None


def test_realtime_slot_value_is_not_masked():
    history = [{"role": "user", "content": "전화 걸어줘"}]

    state = resolve_intent(_request("010-0000-0000", history))

    assert "010-0000-0000" in state.slots.values()


def test_empty_history_does_not_reuse_another_session():
    state = resolve_intent(_request("저번처럼 보내줘"))

    assert state.missing_slots
    assert all(value is None for value in state.slots.values())


def test_response_rejects_slots_and_missing_list_that_disagree():
    with pytest.raises(ValidationError):
        SlotFillState(
            reasoning="invalid state",
            action_type=SlotFillActionType.GENERAL,
            slots={"app": None, "content": None, "method": None},
            missing_slots=[],
            confirmation_question="어느 앱에서 할까요?",
            goal="잘못 만들어진 목표",
        )
