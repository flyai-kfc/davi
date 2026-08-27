package com.davi.helper

/**
 * 목표 구체화 구간의 **전문 진단 로그 — release 구현**(#470).
 *
 * 발화·질문 문구·보기 문구·목표 문장은 제품 빌드의 logcat에 남기지 않는다. 이 경로는 패턴
 * 마스킹을 일부러 안 걸었고(`SPEC.md` §4 · #92) 그 결정이 성립하는 근거가 **로그에 안 남는
 * 다는 것**이라, "마스킹했으니 남겨도 된다"가 여기서는 아예 성립하지 않는다.
 *
 * **비식별 수치는 [GuideTelemetry]가 main에서 그대로 낸다** — 상태·질문 수·보기 수·길이는
 * 제품 빌드에도 남으므로 여기서 다시 남길 것이 없다.
 *
 * 화면 경로의 [GuideScreenLog] release 구현과 같은 자리·같은 이유다(#58).
 */
object ConversationLog {
    /** release는 발화를 남기지 않는다. */
    @Suppress("UNUSED_PARAMETER")
    fun utterance(kind: String, text: String) = Unit

    /** release는 질문·보기 원문을 남기지 않는다. 개수는 `voice_response_shape`에 있다. */
    @Suppress("UNUSED_PARAMETER")
    fun question(index: Int, count: Int, prompt: String, options: List<String>) = Unit

    /** release는 목표 문장을 남기지 않는다. */
    @Suppress("UNUSED_PARAMETER")
    fun goal(goal: String) = Unit

    /** release는 답 원문을 남기지 않는다. 보기 번호·길이는 `clarification_answer`에 있다. */
    @Suppress("UNUSED_PARAMETER")
    fun answer(source: String, answer: String) = Unit
}
