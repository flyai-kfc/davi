"""사용자 LLM — 시나리오를 인용해 '정보가 빠진' 발화를 만들고, 핑퐁의 질문에 답한다.

핵심은 유출 통제다. 사용자 LLM이 정답을 통째로 뱉으면 핑퐁이 한 번에 끝나 아무것도 측정되지
않는다. 그래서 **무엇을 숨길지는 코드가 정하고**, LLM은 그것을 자연스러운 말로 바꾸기만 한다.
"""

from __future__ import annotations

import random

from .llm import GeminiClient, LlmError
from .models import Difficulty, Exposure, PingpongTurn, Scenario, Slot, UserReply

# 난이도별로 첫 발화에서 빼먹을 슬롯 수. 남길 것이 없으면 코드가 하한을 지킨다.
_HIDE_BUDGET = {Difficulty.EASY: 1, Difficulty.NORMAL: 2, Difficulty.HARD: 3}

_SYSTEM = """당신은 스마트폰이 서툰 한국 노인입니다. 실제 사람처럼 짧게, 구어체로 말합니다.
아래 규칙을 지키는 것이 당신의 유일한 임무입니다.

- 묻지 않은 것을 먼저 말하지 않습니다. 한 번에 여러 조각을 쏟지 않습니다.
- 목록에 없는 정보를 지어내지 않습니다. 특히 사람 이름, 가게 이름, 약 이름은 절대 지어내지 않습니다.
- 모르는 것은 "몰라요"라고 답합니다. 아는 척하지 않습니다.
- 어려운 말(앱 메뉴 이름, 전문 용어)을 쓰지 않습니다. 아는 대로 뭉뚱그려 말합니다.
- 한 문장, 길어야 두 문장으로 말합니다."""

_OPENING_SCHEMA = {
    "type": "object",
    "properties": {"utterance": {"type": "string"}},
    "required": ["utterance"],
}

_REPLY_SCHEMA = {
    "type": "object",
    "properties": {
        "chosen_option": {"type": "integer"},
        "text": {"type": "string"},
        "revealed_slots": {"type": "array", "items": {"type": "string"}},
    },
    "required": ["chosen_option", "text", "revealed_slots"],
}


class UserSimulator:
    def __init__(self, client: GeminiClient, *, rng: random.Random | None = None) -> None:
        self._client = client
        self._rng = rng or random.Random()

    # --- 첫 발화 ------------------------------------------------------------

    def opening(self, scenario: Scenario, *, avoid: list[str] | None = None) -> tuple[str, list[Slot]]:
        """빼먹을 슬롯을 코드가 고르고, 남은 것만으로 말하게 한다. (발화, 말한 슬롯)"""

        revealed = self._pick_revealed(scenario)
        prompt = self._opening_prompt(scenario, revealed, avoid or [])
        for attempt in range(2):
            body = self._client.generate_json(prompt, _OPENING_SCHEMA, system=_SYSTEM)
            utterance = str(body.get("utterance", "")).strip()
            leaked = leaked_slots(utterance, [s for s in scenario.slots if s not in revealed])
            if utterance and not leaked:
                return utterance, revealed
            prompt = (
                f"{prompt}\n\n방금 답에 말하면 안 되는 내용이 들어갔습니다: "
                f"{', '.join(s.key for s in leaked) or '빈 문장'}. 그 내용을 빼고 다시 한 문장으로 말하세요."
            )
        raise LlmError(f"[{scenario.id}] 첫 발화에서 숨긴 슬롯이 계속 새어 나온다")

    def _pick_revealed(self, scenario: Scenario) -> list[Slot]:
        """'행위'는 언제나 말한다. 숨기는 예산은 나머지 조각에만 쓴다.

        예산을 전체 슬롯에 적용했더니 조각이 둘뿐인 시나리오에서 첫 발화가 "네이버" 한
        단어로 나왔다(h07 실측). 실제 어르신은 앱 이름은 몰라도 **하고 싶은 일은 말한다** —
        그쪽이 시나리오로도 더 정확하다.
        """

        first = scenario.first_slots
        always = [slot for slot in first if slot.key == "행위"]
        pool = [slot for slot in first if slot not in always]
        hide = min(_HIDE_BUDGET[scenario.difficulty], len(pool) if always else max(len(pool) - 1, 0))
        hidden = set(self._rng.sample(range(len(pool)), hide)) if hide else set()
        kept = [slot for index, slot in enumerate(pool) if index not in hidden]
        return [slot for slot in first if slot in always or slot in kept]

    def _opening_prompt(self, scenario: Scenario, revealed: list[Slot], avoid: list[str]) -> str:
        말할것 = "\n".join(f"- {s.key}: {s.value}" for s in revealed) or "- (없음)"
        숨길것 = "\n".join(
            f"- {s.key}"
            + (f" (물어보면 그때 '{s.value}'라고 답한다)" if s.exposure is Exposure.ON_ASK else "")
            for s in scenario.slots
            if s not in revealed
        ) or "- (없음)"
        회피 = (
            "\n\n아래 표현들은 최근에 이미 쓴 것입니다. 뜻은 같아도 **다른 말투와 다른 어순**으로 말하세요.\n"
            + "\n".join(f"- {a}" for a in avoid[-5:])
            if avoid
            else ""
        )
        메모 = f"\n\n당신에 대한 메모: {scenario.user_note}" if scenario.user_note else ""
        return f"""당신은 지금 스마트폰 도우미 앱을 처음 켜고 무엇을 하고 싶은지 말하려고 합니다.

말해도 되는 것(이것만 말합니다):
{말할것}

절대 먼저 말하면 안 되는 것:
{숨길것}{메모}{회피}

말해도 되는 것만으로 한 문장을 말하세요. 도우미에게 하는 말 그대로만 쓰세요."""

    # --- 되묻기 응답 --------------------------------------------------------

    def reply(
        self,
        scenario: Scenario,
        turn: PingpongTurn,
        *,
        said: list[str],
    ) -> UserReply:
        prompt = self._reply_prompt(scenario, turn, said)
        body = self._client.generate_json(prompt, _REPLY_SCHEMA, system=_SYSTEM)

        text = str(body.get("text", "")).strip()
        raw_choice = body.get("chosen_option", -1)
        chosen = int(raw_choice) if isinstance(raw_choice, int) else -1
        valid = 0 <= chosen < len(turn.options)
        said_text = turn.options[chosen] if valid else text
        return UserReply(
            chosen_option=chosen if valid else None,
            text=said_text,
            used_other=bool(turn.options) and not valid,
            # 자기보고만 믿으면 비어서 온다(실측). 문장에서 직접 찾은 것과 합친다.
            revealed_slots=sorted(
                {str(k) for k in body.get("revealed_slots", []) if str(k).strip()}
                | {slot.key for slot in leaked_slots(said_text, scenario.slots)}
            ),
        )

    def _reply_prompt(self, scenario: Scenario, turn: PingpongTurn, said: list[str]) -> str:
        아는것 = "\n".join(
            f"- {s.key}: {s.value}"
            + (" ← 물어보면 답한다" if s.exposure is Exposure.ON_ASK else "")
            + (" ← 당신은 이걸 모릅니다. 물어보면 '몰라요'라고만 답합니다." if s.exposure is Exposure.UNKNOWN else "")
            for s in scenario.slots
        )
        이미 = ", ".join(said) if said else "(아직 없음)"
        선택지 = (
            "\n".join(f"{i}. {o}" for i, o in enumerate(turn.options))
            if turn.options
            else "(선택지 없음 — 말로 답합니다)"
        )
        메모 = f"\n당신에 대한 메모: {scenario.user_note}" if scenario.user_note else ""

        return f"""도우미가 당신에게 물었습니다.

질문: {turn.question}

선택지:
{선택지}

당신이 마음속에 가진 정보(묻는 것에만 답합니다):
{아는것}

이미 말한 것: {이미}{메모}

규칙:
- 선택지 중에 당신 마음속 답과 **정확히 맞는 것**이 있으면 그 번호를 chosen_option에 넣습니다.
- 맞는 것이 없으면 chosen_option은 -1로 두고 text에 당신 말로 답합니다. 억지로 고르지 않습니다.
- 묻지 않은 조각은 말하지 않습니다.
- '몰라요'로 되어 있는 조각은 무슨 일이 있어도 지어내지 않습니다.
- revealed_slots에는 이번 답에서 실제로 말한 조각의 이름만 적습니다."""


def leaked_slots(text: str, forbidden: list[Slot]) -> list[Slot]:
    """숨겨야 할 슬롯 값이 문장에 그대로 들어갔는지 본다.

    한 글자·두 글자 값(수량 "1" 등)은 오탐이 많아 검사하지 않는다 — 그런 값은 유출돼도
    측정을 망가뜨리지 않는다.
    """

    leaked = []
    for slot in forbidden:
        for candidate in filter(None, (slot.value, slot.user_phrase)):
            if len(candidate) >= 3 and candidate in text:
                leaked.append(slot)
                break
    return leaked
