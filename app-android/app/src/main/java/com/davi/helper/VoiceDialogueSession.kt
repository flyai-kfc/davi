package com.davi.helper

import java.util.UUID

/**
 * #316 음성 목표 구체화의 메모리 내 상태.
 *
 * 첫 발화로 서버를 한 번 호출해 최대 두 질문을 받고, 선택·자유 발화 답은 앱에서만 모은다.
 * 모든 질문이 끝났을 때 같은 원발화와 답을 두 번째이자 마지막 요청으로 보낸다. 발화·질문·답은
 * 저장하거나 로그에 남기지 않으며 세대가 바뀌면 늦은 응답을 버린다.
 */
class VoiceDialogueSession(
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    enum class Stage { IDLE, LISTENING, REQUESTING, CLARIFYING, READY }

    data class PendingRequest(
        val generation: Long,
        /** 0=질문 계획, 1=답을 합친 최종화. */
        val turnIndex: Int,
        val utterance: String,
        val request: SlotFillRequest,
    )

    sealed interface Outcome {
        data class Clarify(
            val generation: Long,
            val question: ClarificationQuestion,
            val questionIndex: Int,
            val questionCount: Int,
        ) : Outcome

        data class Ready(
            val generation: Long,
            val message: String,
            val goal: String,
        ) : Outcome

        data class UnableToFinalize(val message: String) : Outcome
        data object InvalidResponse : Outcome
    }

    sealed interface AnswerOutcome {
        data class NextQuestion(val outcome: Outcome.Clarify) : AnswerOutcome
        data class FinalRequest(val pending: PendingRequest) : AnswerOutcome
    }

    private data class Active(
        val generation: Long,
        val sessionId: String,
        var originalUtterance: String? = null,
        var questions: List<ClarificationQuestion> = emptyList(),
        val answers: MutableList<ClarificationAnswer> = mutableListOf(),
        var questionIndex: Int = 0,
        var pendingTurnIndex: Int? = null,
    )

    private var generation = 0L
    private var active: Active? = null

    var stage: Stage = Stage.IDLE
        private set

    @Synchronized
    fun start(): Long {
        generation++
        active = Active(generation, idFactory())
        stage = Stage.LISTENING
        return generation
    }

    /** 최초 사용자 발화만 받는다. 구체화 자유 발화는 [answerCurrent]로 들어온다. */
    @Synchronized
    fun nextRequest(utterance: String): PendingRequest? {
        val current = active ?: return null
        val normalized = utterance.trim()
        if (
            normalized.isEmpty() || stage != Stage.LISTENING ||
            current.originalUtterance != null || current.pendingTurnIndex != null
        ) return null

        current.originalUtterance = normalized
        return pendingRequest(current, turnIndex = 0, answers = emptyList())
    }

    @Synchronized
    fun applyResponse(pending: PendingRequest, response: SlotFillState): Outcome? {
        val current = active ?: return null
        if (
            current.generation != pending.generation ||
            current.pendingTurnIndex != pending.turnIndex || stage != Stage.REQUESTING
        ) return null
        current.pendingTurnIndex = null

        return when (response.status) {
            IntentDialogueStatus.NEEDS_CLARIFICATION -> {
                val questions = response.clarification?.questions.orEmpty()
                if (pending.turnIndex != 0 || !validQuestions(questions)) {
                    invalidate()
                    Outcome.InvalidResponse
                } else {
                    current.questions = questions
                    current.questionIndex = 0
                    stage = Stage.CLARIFYING
                    currentQuestion(current)
                }
            }
            IntentDialogueStatus.READY -> {
                val goal = response.goal?.trim().orEmpty()
                if (goal.isEmpty()) {
                    invalidate()
                    Outcome.InvalidResponse
                } else {
                    stage = Stage.READY
                    Outcome.Ready(current.generation, response.message, goal)
                }
            }
            IntentDialogueStatus.UNABLE_TO_FINALIZE -> {
                invalidate()
                Outcome.UnableToFinalize(response.message)
            }
        }
    }

    /** 현재 질문의 보기 또는 자유 발화를 저장하고 다음 질문 또는 마지막 요청으로 진행한다. */
    @Synchronized
    fun answerCurrent(source: ClarificationAnswerSource, answer: String): AnswerOutcome? {
        val current = active ?: return null
        val normalized = answer.trim()
        if (stage != Stage.CLARIFYING || normalized.isEmpty()) return null
        val question = current.questions.getOrNull(current.questionIndex) ?: return null

        current.answers += ClarificationAnswer(
            questionId = question.id,
            prompt = question.prompt,
            answer = normalized,
            source = source,
        )
        current.questionIndex++
        if (current.questionIndex < current.questions.size) {
            return AnswerOutcome.NextQuestion(currentQuestion(current))
        }

        return AnswerOutcome.FinalRequest(
            pendingRequest(current, turnIndex = 1, answers = current.answers.toList()),
        )
    }

    /** 슬롯 응답 뒤 앱 후보 왕복이 이어지는 동안에도 같은 사용자 턴인지 확인한다(#128). */
    @Synchronized
    fun isPending(pending: PendingRequest): Boolean {
        val current = active ?: return false
        return current.generation == pending.generation &&
            current.pendingTurnIndex == pending.turnIndex && stage == Stage.REQUESTING
    }

    @Synchronized
    fun fail(pending: PendingRequest): Boolean {
        val current = active ?: return false
        if (
            current.generation != pending.generation ||
            current.pendingTurnIndex != pending.turnIndex
        ) return false
        invalidate()
        return true
    }

    @Synchronized
    fun finish(generation: Long): Boolean {
        if (active?.generation != generation) return false
        invalidate()
        return true
    }

    @Synchronized
    fun cancel() = invalidate()

    private fun pendingRequest(
        current: Active,
        turnIndex: Int,
        answers: List<ClarificationAnswer>,
    ): PendingRequest {
        val utterance = requireNotNull(current.originalUtterance)
        current.pendingTurnIndex = turnIndex
        stage = Stage.REQUESTING
        return PendingRequest(
            generation = current.generation,
            turnIndex = turnIndex,
            utterance = utterance,
            request = SlotFillRequest(
                utterance = utterance,
                sessionId = current.sessionId,
                clarificationAnswers = answers,
            ),
        )
    }

    private fun currentQuestion(current: Active): Outcome.Clarify = Outcome.Clarify(
        generation = current.generation,
        question = current.questions[current.questionIndex],
        questionIndex = current.questionIndex,
        questionCount = current.questions.size,
    )

    private fun validQuestions(questions: List<ClarificationQuestion>): Boolean =
        questions.size in 1..MAX_QUESTIONS &&
            questions.map { it.id }.distinct().size == questions.size &&
            questions.all { question ->
                question.id.isNotBlank() && question.prompt.isNotBlank() &&
                    question.options.size <= MAX_OPTIONS &&
                    question.options.all { it.isNotBlank() } &&
                    question.options.distinct().size == question.options.size &&
                    question.allowFreeform
            }

    private fun invalidate() {
        generation++
        active = null
        stage = Stage.IDLE
    }

    companion object {
        const val MAX_QUESTIONS = 2
        const val MAX_OPTIONS = 3
    }
}
