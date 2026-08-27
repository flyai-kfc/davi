"""코드로 재는 값. 평가자 LLM에게 맡기지 않는다 — 세는 일은 모델이 틀린다."""

from __future__ import annotations

from statistics import mean

from .models import Achievement, AnswerSource, ScenarioResult, SlotVerdict, Transcript


def _tts_texts(transcript: Transcript) -> list[str]:
    """앱이 실제로 읽는 문구만 고른다.

    **문항마다 읽는다**(#452 · PR #465에서 「첫 문항만 읽는다」를 뒤집었다). 근거는 접기다 —
    안 읽어 주는 질문은 ``speaking=false``라 처음부터 접힌 채로 떠서, 어르신이 그것을 **읽을
    수도 들을 수도 없다**. 예전에는 안 읽어 줘도 글자가 화면에 그냥 있었다.

    옛 형식은 매 요청의 단일 질문을 읽었으므로 세는 규칙이 같아졌다. 다만 옛 형식은 화면용
    ``message``가 그 질문과 같은 문장이라 둘을 함께 세면 이중 집계가 되고, 새 형식은
    ``message``가 **첫 질문의 복사본**(구버전 앱 호환용, ``intent_prompt.py``)이라 마찬가지다.
    그래서 어느 쪽이든 **질문 문장만** 센다.

    실기기 대조(2026-08-26 SM-S921N · 실모델 6세션): 질문 9개가 **전부** 읽혔고 낭독 시작
    신호(``clarification_fold expanded=true speaking=true``)도 9회로 일치했다. 옛 규칙으로
    세면 114자, 실제는 158자다 — **옛 규칙이 실제의 72%만 셌다**(질문 부분만 비교한 값이고,
    ready 문구는 양쪽 규칙이 똑같이 센다).

    **첫 요청의 계획만 읽는다.** 새 형식에서 앱이 화면에 띄우는 질문은 첫 응답의 계획뿐이고,
    두 번째 요청이 계약을 어겨 또 질문을 주면 앱은 그것을 ``InvalidResponse``로 버린다
    (``VoiceDialogueSession.applyResponse``의 ``pending.turnIndex != 0``). 옛 형식은 매 요청이
    질문 하나였고 그때마다 읽었으므로 형식별로 가른다.

    ``ready``의 ``message``는 최종 확인 화면이 읽는다(``ConversationActivity.showReadyGoal``).
    **앱이 그 밖에 읽는 두 문구는 여기서 셀 수 없다** — 앱 가능성 경고와 그 뒤의 안내는
    ``strings.xml``에 있고 기기에 무엇이 깔렸느냐로 갈려, 서버 응답만 보는 이 하니스가 알 수
    없다. 그만큼 이 수치는 **하한**이다.
    """

    texts: list[str] = []
    new_format_plan_seen = False
    for exchange in transcript.exchanges:
        turn = exchange.turn
        if turn.status.value == "ready":
            if turn.message.strip():
                texts.append(turn.message.strip())
            continue
        if not turn.questions:
            continue
        if turn.legacy:
            # 옛 형식은 매 요청의 단일 질문을 그때마다 읽었다.
            text = turn.questions[0].prompt.strip()
            if text:
                texts.append(text)
        elif not new_format_plan_seen:
            for question in turn.questions:
                text = question.prompt.strip()
                if text:
                    texts.append(text)
            new_format_plan_seen = True
        # **두 번째 요청의 needs_clarification은 세지 않는다**(#488 리뷰). 계약은 답을 받은
        # 뒤 ready 또는 unable_to_finalize만 허용하는데, 모델이 그것을 어기면 러너는 그 turn을
        # transcript에 넣고 상한으로 끝낸다. **앱은 그것을 읽지 않는다** —
        # `VoiceDialogueSession.applyResponse`가 `pending.turnIndex != 0`이면 `InvalidResponse`로
        # 버려 MA-5로 가고, 그 화면에는 `speak` 호출이 없다. 여기서 세면 **계약 위반 회차의
        # TTS만 부풀어** 실패가 오히려 더 많이 읽은 것처럼 보인다.
    return texts


def transcript_metrics(transcript: Transcript) -> dict:
    exchanges = transcript.exchanges
    answers = [answer for exchange in exchanges for answer in exchange.answers]
    questions = [q for exchange in exchanges for q in exchange.turn.questions]
    with_options = [q for q in questions if q.options]
    tts_texts = _tts_texts(transcript)
    return {
        "요청수": len(exchanges),
        "질문수": len(questions),
        "종료사유": transcript.stop_reason.value,
        "2회이내_ready": transcript.stop_reason.value == "ready"
        and len(exchanges) <= 2,
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
        "보기_선택수": sum(1 for a in answers if a.source is AnswerSource.OPTION),
        "답변수": len(answers),
        "TTS_발화수": len(tts_texts),
        "TTS_문자수": sum(len(text) for text in tts_texts),
        "옛형식": any(e.turn.legacy for e in exchanges),
    }


def _has_missing_information_hallucination(result: ScenarioResult) -> bool:
    """질문으로만 얻을 수 있던 슬롯을 틀리게 채웠거나 고유명을 지어낸 경우."""

    if result.judge is None:
        return False
    missing = set(result.missing_slot_keys or [])
    wrong_missing_slot = any(
        score.key in missing and score.verdict is SlotVerdict.WRONG
        for score in result.judge.slot_scores
    )
    invented_proper_noun = any(
        violation.axis.value == "고유명_환각" for violation in result.judge.violations
    )
    return wrong_missing_slot or invented_proper_noun


def batch_of(meta: dict) -> tuple[str, str, str] | None:
    """meta가 가리키는 '이번 채점 배치'. 채점자가 없으면 None(제한 없이 집계)."""

    model = meta.get("평가자모델")
    if not model:
        return None
    return (
        model,
        str(meta.get("채점시_시나리오해시") or meta.get("시나리오해시") or ""),
        str(meta.get("채점계약") or ""),
    )


def in_batch(result: ScenarioResult, batch: tuple[str, str, str] | None) -> bool:
    """이 결과의 judge를 지금 집계에 넣어도 되는가 — 같은 배치만 비교 가능하다."""

    if result.judge is None:
        return False
    if batch is None:
        return True
    return result.judge_batch is not None and (
        (
            result.judge_batch.model,
            result.judge_batch.scenario_digest,
            result.judge_batch.judge_contract,
        )
        == batch
    )


def summarize(
    results: list[ScenarioResult], *, batch: tuple[str, str, str] | None = None
) -> dict:
    """티어별·요인별 집계. 요인별은 어려움의 원인을 가르는 자리다.

    ``batch``가 주어지면 그 배치(평가자 모델·시나리오 해시·채점 계약)로 채점된 결과만 집계한다 —
    부분 재채점이 겹칠 때 서로 다른 평가 배치가 한 모델의 수치처럼 보이면 안 된다.
    """

    def block(subset: list[ScenarioResult]) -> dict:
        judged = [r for r in subset if in_batch(r, batch)]
        slots = [s for r in judged for s in r.judge.slot_scores]
        ready = [r for r in subset if r.metrics.get("종료사유") == "ready"]
        ready_within_two = [r for r in subset if r.metrics.get("2회이내_ready")]
        valid_goals = [
            r
            for r in judged
            if r.metrics.get("2회이내_ready")
            and r.judge.achievement is not Achievement.FAIL
        ]
        hallucination_judged = [r for r in judged if r.missing_slot_keys is not None]
        hallucinated = [
            r for r in hallucination_judged if _has_missing_information_hallucination(r)
        ]
        selection_biased = [
            r
            for r in judged
            if any(v.axis.value == "선택_강요" for v in r.judge.violations)
        ]
        answers = sum(r.metrics.get("답변수", 0) for r in subset)
        option_answers = sum(r.metrics.get("보기_선택수", 0) for r in subset)
        completed_tts = [
            r.metrics.get("TTS_문자수", 0)
            for r in ready
            if r.metrics.get("TTS_발화수", 0) > 0
        ]
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
            # #316 완료 조건의 A/B 축. 새 형식은 요청 상한이 2라 ready와
            # 2회 이내 ready가 같다. 두 값을 따로 남겨 이 구조적 관계를 숨기지 않는다.
            "ready": len(ready),
            "ready율": round(len(ready) / len(subset), 3) if subset else None,
            "2회이내_ready": len(ready_within_two),
            "2회이내_ready율": round(len(ready_within_two) / len(subset), 3)
            if subset
            else None,
            "goal_유효": len(valid_goals),
            "goal_유효율": round(len(valid_goals) / len(judged), 3) if judged else None,
            "누락정보_환각": len(hallucinated) if hallucination_judged else None,
            "누락정보_환각_분모": len(hallucination_judged),
            "누락정보_환각률": round(len(hallucinated) / len(hallucination_judged), 3)
            if hallucination_judged
            else None,
            "선택_강요": len(selection_biased),
            "선택_강요율": round(len(selection_biased) / len(judged), 3)
            if judged
            else None,
            "보기_선택률": round(option_answers / answers, 3) if answers else None,
            # 성공한 사용자 여정 1건의 총 청취 부담이다. 질문 수 감소도
            # 의도한 개선으로 포함하되, 안전 종료·중도 오류는 실패 편향이라 뺀다.
            "ready_평균_TTS_문자수": round(mean(completed_tts), 1)
            if completed_tts
            else None,
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
        for violation in result.judge.violations if in_batch(result, batch) else []:
            violations[violation.axis.value] = (
                violations.get(violation.axis.value, 0) + 1
            )

    return {
        "전체": block(results),
        "난이도별": {k: block(v) for k, v in by_difficulty.items()},
        "요인별": {k: block(v) for k, v in by_factor.items()},
        "위반_유형별": dict(sorted(violations.items(), key=lambda kv: -kv[1])),
    }
