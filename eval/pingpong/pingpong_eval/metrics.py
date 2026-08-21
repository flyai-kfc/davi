"""코드로 재는 값. 평가자 LLM에게 맡기지 않는다 — 세는 일은 모델이 틀린다."""

from __future__ import annotations

from statistics import mean

from .models import Achievement, ScenarioResult, SlotVerdict, Transcript


def transcript_metrics(transcript: Transcript) -> dict:
    rounds = transcript.rounds
    asked = [r for r in rounds if r.reply is not None]
    with_options = [r for r in rounds if r.pingpong.options]
    return {
        "라운드수": len(asked),
        "종료사유": transcript.stop_reason.value,
        "총_소요ms": transcript.elapsed_ms,
        "핑퐁_지연ms_평균": round(mean([r.pingpong.latency_ms for r in rounds])) if rounds else 0,
        "질문_평균길이": round(mean([len(r.pingpong.question) for r in asked])) if asked else 0,
        "선택지_제시_라운드": len(with_options),
        "기타_사용": sum(1 for r in asked if r.reply and r.reply.used_other),
        "선택지_적중률": (
            round(
                sum(1 for r in with_options if r.reply and r.reply.chosen_option is not None)
                / len(with_options),
                3,
            )
            if with_options
            else None
        ),
    }


def summarize(results: list[ScenarioResult]) -> dict:
    """티어별·요인별 집계. 요인별은 어려움의 원인을 가르는 자리다."""

    def block(subset: list[ScenarioResult]) -> dict:
        judged = [r for r in subset if r.judge is not None]
        slots = [s for r in judged for s in r.judge.slot_scores]
        return {
            "건수": len(subset),
            "충족": sum(1 for r in judged if r.judge.achievement is Achievement.FULL),
            "부분": sum(1 for r in judged if r.judge.achievement is Achievement.PARTIAL),
            "실패": sum(1 for r in judged if r.judge.achievement is Achievement.FAIL),
            "평균_라운드": round(mean([r.metrics.get("라운드수", 0) for r in subset]), 2) if subset else 0,
            "상한도달": sum(1 for r in subset if r.metrics.get("종료사유") == "limit"),
            "슬롯_정확률": (
                round(sum(1 for s in slots if s.verdict is SlotVerdict.CORRECT) / len(slots), 3)
                if slots
                else None
            ),
            "위반수": sum(len(r.judge.violations) for r in judged),
        }

    by_difficulty = {}
    for result in results:
        by_difficulty.setdefault(result.difficulty.value, []).append(result)
    by_factor: dict[str, list[ScenarioResult]] = {}
    for result in results:
        for factor in result.factors or ["요인없음"]:
            by_factor.setdefault(factor, []).append(result)

    violations: dict[str, int] = {}
    for result in results:
        for violation in result.judge.violations if result.judge else []:
            violations[violation.axis.value] = violations.get(violation.axis.value, 0) + 1

    return {
        "전체": block(results),
        "난이도별": {k: block(v) for k, v in by_difficulty.items()},
        "요인별": {k: block(v) for k, v in by_factor.items()},
        "위반_유형별": dict(sorted(violations.items(), key=lambda kv: -kv[1])),
    }
