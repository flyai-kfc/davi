"""대화 한 판을 돌린다: 사용자 LLM ↔ 핑퐁.

핑퐁이 몇 왕복을 돌든, 한 번에 선택지를 주든 이 루프는 같다.
"""

from __future__ import annotations

import time

from .metrics import transcript_metrics
from .models import (
    PingpongStatus,
    Round,
    Scenario,
    ScenarioResult,
    StopReason,
    Transcript,
)
from .sut import PingpongAdapter, PingpongError
from .user_sim import UserSimulator

# davi의 VoiceDialogueSession.MAX_HISTORY_MESSAGES = 10 (5왕복)과 같게 둔다.
MAX_ROUNDS = 5


def run_scenario(
    scenario: Scenario,
    user: UserSimulator,
    adapter: PingpongAdapter,
    *,
    avoid_openings: list[str] | None = None,
    max_rounds: int = MAX_ROUNDS,
) -> ScenarioResult:
    started = time.monotonic()
    opening, revealed = user.opening(scenario, avoid=avoid_openings)
    said = [slot.key for slot in revealed]

    history: list[dict[str, str]] = []
    utterance = opening
    rounds: list[Round] = []
    final_goal: str | None = None
    stop = StopReason.LIMIT
    error: str | None = None

    for index in range(1, max_rounds + 1):
        try:
            turn = adapter.send(utterance, history)
        except PingpongError as failure:
            stop, error = StopReason.ERROR, str(failure)
            break

        if turn.status is PingpongStatus.READY:
            final_goal = turn.goal
            stop = StopReason.READY
            rounds.append(Round(index=index, pingpong=turn))
            break

        reply = user.reply(scenario, turn, said=said)
        said.extend(reply.revealed_slots)
        rounds.append(Round(index=index, pingpong=turn, reply=reply))

        history.append({"role": "user", "content": utterance})
        history.append({"role": "assistant", "content": turn.question})
        utterance = reply.text

    transcript = Transcript(
        scenario_id=scenario.id,
        opening=opening,
        rounds=rounds,
        final_goal=final_goal,
        stop_reason=stop,
        error=error,
        elapsed_ms=int((time.monotonic() - started) * 1000),
    )
    return ScenarioResult(
        scenario_id=scenario.id,
        difficulty=scenario.difficulty,
        factors=scenario.factors,
        transcript=transcript,
        metrics=transcript_metrics(transcript),
    )
