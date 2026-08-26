package com.davi.helper

/**
 * 서버 응답을 **비식별 수치로 줄인 것**(#470). Android 타입을 안 써서 JVM 테스트로 고정한다.
 *
 * **밖에 두는 이유는 [QuestionFoldGate]와 같다.** 「무엇을 남기고 무엇을 안 남기는가」는 뷰가
 * 필요 없는 순수 판정인데, 액티비티 안에 두면 그것을 잡는 그물이 실기기 확인뿐이 된다.
 * 그리고 여기서 지켜야 하는 것은 화면 정렬이 아니라 **개인정보 경계**라, 조용히 깨지면
 * 대가가 훨씬 크다 — `SPEC.md` §4가 이 경로에 패턴 마스킹을 일부러 안 걸었고 그 결정이
 * 성립하는 근거가 「로그에 안 남는다」 하나다.
 *
 * 그래서 이 타입의 **문자열 축은 둘 다 사용자 입력이 아니다** — [status]는 계약 enum의 이름이고
 * [optionCounts]는 숫자와 쉼표뿐이다. **「문자열 필드가 하나뿐」이 아니다**(#471 리뷰 3번:
 * 그렇게 적었다가 지적받았다) — 안전의 근거는 개수가 아니라 **각 축이 무엇에서 오는가**이고,
 * 테스트가 그 사실을 직접 단정한다.
 */
data class ResponseShape(
    /** 계약 enum([IntentDialogueStatus])의 이름. 사용자 입력이 아니다. */
    val status: String,
    val messageLength: Int,
    /** `NEEDS_CLARIFICATION`이 아니면 null. **0으로 지어내지 않는다** — 아래 KDoc 참고. */
    val questionCount: Int?,
    /** 질문 순서대로 보기 개수를 쉼표로 이은 것("3,0"). 질문이 없으면 null. */
    val optionCounts: String?,
    /** `READY`가 아니면 null. */
    val hasGoal: Boolean?,
)

/**
 * [SlotFillState]에서 남길 수치만 뽑는다.
 *
 * **없는 축은 null로 둔다.** 계약이 `NEEDS_CLARIFICATION`에서만 `clarification`을,
 * `READY`에서만 `goal`을 채우므로([SlotFillApiClient] 검증), null 자체가 「상태와 짝이
 * 맞았다」는 뜻이다. 0으로 지어내면 **상태와 내용이 어긋난 응답이 정상으로 보인다** —
 * #411 1번이 「모델이 화면을 잘못 요약해도 이력이 그대로 물고 간다」로 겪은 것과 같은 종류의
 * 조용한 거짓말이다.
 */
fun responseShapeOf(state: SlotFillState): ResponseShape {
    val questions = state.clarification?.questions
    return ResponseShape(
        status = state.status.name.lowercase(),
        messageLength = state.message.length,
        questionCount = questions?.size,
        optionCounts = questions?.joinToString(",") { it.options.size.toString() },
        hasGoal = state.goal?.isNotBlank(),
    )
}

/**
 * 사용자가 고른 보기의 **번호**. 직접 말한 답이거나 보기에 없으면 null.
 *
 * 보기 문구를 그대로 넘기는 대신 번호로 바꾸는 자리다 — 보기는 서버가 사용자의 발화를 받아
 * 만든 문장이라 고유명이 실릴 수 있다.
 */
fun optionIndexOf(source: ClarificationAnswerSource, options: List<String>, answer: String): Int? {
    if (source != ClarificationAnswerSource.OPTION) return null
    return options.indexOf(answer).takeIf { it >= 0 }
}

/**
 * 사용자의 답을 **비식별 수치로 줄인 것**(#470).
 *
 * **[ResponseShape]와 같은 이유로 타입이다**(#471 리뷰 6번). 텔레메트리 함수가 인자를 낱개로
 * 받으면 **그 인자 목록을 지키는 그물이 없다** — 나중에 누가 `answer: String`을 하나 더 다는
 * 것을 아무도 못 잡고, 그 함수는 main 소스라 **release에도 실린다.** 값을 이 타입으로만 만들 수
 * 있게 해서, 원문이 새려면 여기를 고쳐야 하고 여기에는 테스트가 걸려 있게 한다.
 *
 * 문자열 축은 [source] 하나이고 계약 enum([ClarificationAnswerSource])의 이름이다.
 */
data class AnswerShape(
    val questionIndex: Int,
    val questionCount: Int,
    val source: String,
    /** 보기를 눌렀을 때만. 직접 말했거나 보기에 없는 값이면 null. */
    val optionIndex: Int?,
    /** 답의 **길이만**. 자유 발화에서 남는 유일한 크기다. */
    val answerLength: Int,
)

/**
 * 지금 묻고 있던 질문과 사용자의 답에서 남길 수치만 뽑는다.
 *
 * **[asked]를 인자로 받는 이유**가 이 함수의 존재 이유다(#471 리뷰 1번). 예전에는 호출부가
 * `uiState as? Clarifying`으로 질문을 꺼냈는데, **자유 발화는 그 상태에서 오지 않는다** —
 * 청취 화면(`Listening.clarification`)에서 온다. 그래서 「보기를 눌렀는지 직접 말했는지」를
 * 알려고 넣은 이벤트가 **직접 말한 쪽에서는 한 번도 안 찍혔다.** 질문을 어디서 꺼내는지를
 * 호출부에 맡기지 않는다.
 */
fun answerShapeOf(
    asked: VoiceDialogueSession.Outcome.Clarify,
    source: ClarificationAnswerSource,
    answer: String,
): AnswerShape = AnswerShape(
    questionIndex = asked.questionIndex,
    questionCount = asked.questionCount,
    source = source.name.lowercase(),
    optionIndex = optionIndexOf(source, asked.question.options, answer),
    answerLength = answer.length,
)
