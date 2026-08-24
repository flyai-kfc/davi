"""대화 한 판을 돌린다: 사용자 LLM ↔ 핑퐁.

#326의 확정 흐름은 **요청 두 번**이다 — 첫 요청이 질문 1~2개를 주고, 답은 **로컬에서** 모으고,
두 번째 요청이 목표를 확정한다. 두 번째 요청은 앱과 같이 **원래 발화를 그대로 다시 보낸다**
(`VoiceDialogueSession`의 `originalUtterance`).

#326 이전 형식(텍스트 질문 하나를 여러 왕복)도 계속 돈다. 그 형식으로 뜬 기준선을 다시 재거나
비교할 수 있어야 하기 때문이다. 어느 쪽인지는 어댑터가 `legacy`로 알려 준다.
"""

from __future__ import annotations

import time

from .metrics import transcript_metrics
from .models import (
    Exchange,
    PingpongStatus,
    Scenario,
    ScenarioResult,
    StopReason,
    Transcript,
)
from .sut import PingpongAdapter, PingpongError
from .user_sim import UserSimulator

# #326 흐름은 요청 두 번이면 끝난다. legacy 형식은 davi의 5왕복 상한을 따른다.
MAX_REQUESTS = 2
MAX_LEGACY_REQUESTS = 5


def run_scenario(
    scenario: Scenario,
    user: UserSimulator,
    adapter: PingpongAdapter,
    *,
    avoid_openings: list[str] | None = None,
    max_requests: int = MAX_REQUESTS,
    max_legacy_requests: int = MAX_LEGACY_REQUESTS,
) -> ScenarioResult:
    started = time.monotonic()
    opening, revealed = user.opening(scenario, avoid=avoid_openings)
    said = [slot.key for slot in revealed]
    missing_slot_keys = [slot.key for slot in scenario.slots if slot.key not in said]

    history: list[dict[str, str]] = []
    utterance = opening
    answers_payload = []
    exchanges: list[Exchange] = []
    final_goal: str | None = None
    stop = StopReason.LIMIT
    error: str | None = None
    limit = max_requests

    index = 0
    while index < limit:
        index += 1
        try:
            turn = adapter.send(utterance, history, answers_payload)
        except PingpongError as failure:
            stop, error = StopReason.ERROR, str(failure)
            break

        if turn.status is PingpongStatus.READY:
            final_goal, stop = turn.goal, StopReason.READY
            exchanges.append(Exchange(index=index, turn=turn))
            break
        if turn.status is PingpongStatus.UNABLE_TO_FINALIZE:
            stop = StopReason.UNABLE
            exchanges.append(Exchange(index=index, turn=turn))
            break

        # 질문을 한 화면씩 답한다 — 앱이 하는 것과 같이 추가 요청 없이 로컬에서.
        answers = [
            user.answer(scenario, question, said=said) for question in turn.questions
        ]
        for answer in answers:
            said.extend(answer.revealed_slots)
        exchanges.append(Exchange(index=index, turn=turn, answers=answers))

        if turn.legacy:
            # 옛 형식은 답 하나를 다음 발화로 올리고 이력을 쌓는다.
            limit = max_legacy_requests
            history.append({"role": "user", "content": utterance})
            history.append({"role": "assistant", "content": turn.message})
            utterance = answers[0].answer
            answers_payload = []
        else:
            # #326 — 발화는 그대로 두고 답만 실어 두 번째 요청을 보낸다.
            answers_payload = answers

    transcript = Transcript(
        scenario_id=scenario.id,
        opening=opening,
        exchanges=exchanges,
        final_goal=final_goal,
        stop_reason=stop,
        error=error,
        elapsed_ms=int((time.monotonic() - started) * 1000),
    )
    return ScenarioResult(
        scenario_id=scenario.id,
        difficulty=scenario.difficulty,
        factors=scenario.factors,
        missing_slot_keys=missing_slot_keys,
        transcript=transcript,
        metrics=transcript_metrics(transcript),
    )
