"""핑퐁: 음성 발화에서 슬롯이 다 찼는지 판단한다.

HTTP와 분리한 순수 함수라 API와 eval이 같은 판단을 재사용할 수 있다. 지금 구현은 대화
프로토콜을 결정적으로 검증하기 위한 규칙 기반 스텁이며 자연어 이해 품질을 대변하지 않는다.
Gemini 연동 시 ``resolve_intent`` 내부만 교체하고 요청·응답 계약은 유지한다.
"""

from __future__ import annotations

from .schemas import (
    SLOT_FILL_REQUIRED_FIELDS,
    ConversationRole,
    ConversationTurn,
    SlotFillActionType,
    SlotFillRequest,
    SlotFillState,
)

_SLOT_QUESTIONS: dict[str, str] = {
    "target_person": "누구에게 보내드릴까요?",
    "app": "어느 앱에서 할까요?",
    "content": "무엇을 보내드릴까요?",
    "method": "어떻게 해드릴까요?",
}

_PERSON_MARKERS = ("에게", "한테", "께", "님")


def resolve_intent(req: SlotFillRequest) -> SlotFillState:
    """현재 발화와 앱이 보낸 이력으로 슬롯 상태와 다음 질문을 만든다."""

    action_type = _classify(req.utterance, req.conversation_history)
    required = SLOT_FILL_REQUIRED_FIELDS[action_type]
    slots = _fill_from_answers(required, req.utterance, req.conversation_history)
    missing = [name for name in required if slots[name] is None]

    return SlotFillState(
        reasoning=_reasoning(action_type, slots, missing),
        action_type=action_type,
        slots=slots,
        missing_slots=missing,
        confirmation_question=(
            _SLOT_QUESTIONS[missing[0]] if missing else _summary_question(action_type, slots)
        ),
        goal=None if missing else _goal(action_type, slots),
    )


def _classify(utterance: str, history: list[ConversationTurn]) -> SlotFillActionType:
    """유형이 대화 도중 뒤집히지 않도록 첫 사용자 발화로만 정한다."""

    first = next(
        (turn.content for turn in history if turn.role is ConversationRole.USER),
        utterance,
    )
    if any(marker in first for marker in _PERSON_MARKERS):
        return SlotFillActionType.PERSON_DIRECTED
    return SlotFillActionType.GENERAL


def _fill_from_answers(
    required: tuple[str, ...],
    utterance: str,
    history: list[ConversationTurn],
) -> dict[str, str | None]:
    """스텁에서는 첫 발화 뒤의 사용자 답변을 되묻는 순서대로 한 칸씩 넣는다."""

    answers = [turn.content for turn in history if turn.role is ConversationRole.USER]
    answers.append(utterance)

    slots: dict[str, str | None] = dict.fromkeys(required)
    for name, answer in zip(required, answers[1:], strict=False):
        slots[name] = answer
    return slots


def _reasoning(
    action_type: SlotFillActionType,
    slots: dict[str, str | None],
    missing: list[str],
) -> str:
    filled = [name for name, value in slots.items() if value is not None]
    return (
        f"[스텁] 유형은 {action_type.value}로 보았다. "
        f"채워진 슬롯: {', '.join(filled) if filled else '없음'}. "
        f"비어 있는 슬롯: {', '.join(missing) if missing else '없음'}."
    )


def _summary_question(
    action_type: SlotFillActionType,
    slots: dict[str, str | None],
) -> str:
    parts = []
    if action_type is SlotFillActionType.PERSON_DIRECTED:
        parts.append(f"{slots['target_person']}님께")
    parts.append(f"{slots['app']}에서")
    parts.append(f"{slots['content']}을(를)")
    parts.append(f"{slots['method']}")
    return " ".join(parts) + " 하시는 게 맞으시죠?"


def _goal(action_type: SlotFillActionType, slots: dict[str, str | None]) -> str:
    task = f"{slots['content']} {slots['method']}하기"
    if action_type is SlotFillActionType.PERSON_DIRECTED:
        return f"{slots['app']}에서 {slots['target_person']}에게 {task}"
    return f"{slots['app']}에서 {task}"
