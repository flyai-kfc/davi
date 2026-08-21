"""코드로 재는 값. 평가자 LLM에게 맡기지 않는다 — 세는 일은 모델이 틀린다."""

from __future__ import annotations

from statistics import mean

from .models import Achievement, AnswerSource, ScenarioResult, SlotVerdict, Transcript


def transcript_metrics(transcript: Transcript) -> dict:
    exchanges = transcript.exchanges
    answers = [answer for exchange in exchanges for answer in exchange.answers]
    questions = [q for exchange in exchanges for q in exchange.turn.questions]
    with_options = [q for q in questions if q.options]
    return {
        "요청수": len(exchanges),
        "질문수": len(questions),
        "종료사유": transcript.stop_reason.value,
        "총_소요ms": transcript.elapsed_ms,
        "핑퐁_지연ms_평균": (
            round(mean([e.turn.latency_ms for e in exchanges])) if exchanges else 0
        ),
        "질문_평균길이": round(mean([len(q.prompt) for q in questions]))
        if questions
        else 0,
        "보기_제시_질문": len(with_options),
        "보기_평균개수": round(mean([len(q.options) for q in with_options]), 2)
        if with_options
        else 0,
        # 보기가 답을 못 담아 사용자가 직접 말한 비율. 선택지 형식의 핵심 축이다.
        "자유입력": sum(1 for a in answers if a.source is AnswerSource.FREEFORM),
        "모름": sum(1 for a in answers if a.source is AnswerSource.UNKNOWN_OR_DECLINED),
        "보기_적중률": (
            round(
                sum(1 for a in answers if a.source is AnswerSource.OPTION)
                / len(answers),
                3,
            )
            if answers
            else None
        ),
        "옛형식": any(e.turn.legacy for e in exchanges),
    }


def summarize(results: list[ScenarioResult]) -> dict:
    """티어별·요인별 집계. 요인별은 어려움의 원인을 가르는 자리다."""

    def block(subset: list[ScenarioResult]) -> dict:
        judged = [r for r in subset if r.judge is not None]
        slots = [s for r in judged for s in r.judge.slot_scores]
        return {
            "건수": len(subset),
            "충족": sum(1 for r in judged if r.judge.achievement is Achievement.FULL),
            "부분": sum(
                1 for r in judged if r.judge.achievement is Achievement.PARTIAL
            ),
            "실패": sum(1 for r in judged if r.judge.achievement is Achievement.FAIL),
            "평균_질문수": (
                round(mean([r.metrics.get("질문수", 0) for r in subset]), 2)
                if subset
                else 0
            ),
            "확정실패": sum(
                1
                for r in subset
                if r.metrics.get("종료사유") in {"limit", "unable_to_finalize"}
            ),
            "자유입력": sum(r.metrics.get("자유입력", 0) for r in subset),
            "슬롯_정확률": (
                round(
                    sum(1 for s in slots if s.verdict is SlotVerdict.CORRECT)
                    / len(slots),
                    3,
                )
                if slots
                else None
            ),
            "위반수": sum(len(r.judge.violations) for r in judged),
        }

    by_difficulty: dict[str, list[ScenarioResult]] = {}
    for result in results:
        by_difficulty.setdefault(result.difficulty.value, []).append(result)
    by_factor: dict[str, list[ScenarioResult]] = {}
    for result in results:
        for factor in result.factors or ["요인없음"]:
            by_factor.setdefault(factor, []).append(result)

    violations: dict[str, int] = {}
    for result in results:
        for violation in result.judge.violations if result.judge else []:
            violations[violation.axis.value] = (
                violations.get(violation.axis.value, 0) + 1
            )

    return {
        "전체": block(results),
        "난이도별": {k: block(v) for k, v in by_difficulty.items()},
        "요인별": {k: block(v) for k, v in by_factor.items()},
        "위반_유형별": dict(sorted(violations.items(), key=lambda kv: -kv[1])),
    }
