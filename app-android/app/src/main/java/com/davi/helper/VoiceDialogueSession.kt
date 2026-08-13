package com.davi.helper

import java.util.UUID

/**
 * 음성 목표 대화 하나의 메모리 내 상태. Android 타입·저장소·로그에 의존하지 않는다.
 *
 * 서버는 무상태이므로 이전에 끝난 user/assistant 쌍만 요청에 되돌려 보낸다. 세대가 바뀌면 늦은
 * 응답을 버리고, 최대 5왕복 뒤에도 질문이 필요하면 자동 추측하지 않고 텍스트 입력으로 넘긴다.
 */
class VoiceDialogueSession(
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    enum class Stage { IDLE, LISTENING, REQUESTING, SPEAKING, READY }

    data class PendingRequest(
        val generation: Long,
        val turnIndex: Int,
        val utterance: String,
        val request: SlotFillRequest,
    )

    sealed interface Outcome {
        data class Ask(
            val generation: Long,
            val message: String,
        ) : Outcome

        data class Ready(
            val generation: Long,
            val message: String,
            val goal: String,
        ) : Outcome

        data object LimitReached : Outcome
        data object InvalidResponse : Outcome
    }

    private data class Active(
        val generation: Long,
        val sessionId: String,
        val history: ArrayDeque<ConversationTurn> = ArrayDeque(),
        var turnIndex: Int = 0,
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

    @Synchronized
    fun nextRequest(utterance: String): PendingRequest? {
        val current = active ?: return null
        val normalized = utterance.trim()
        if (normalized.isEmpty() || stage != Stage.LISTENING || current.pendingTurnIndex != null) {
            return null
        }
        val turnIndex = current.turnIndex
        current.pendingTurnIndex = turnIndex
        stage = Stage.REQUESTING
        return PendingRequest(
            generation = current.generation,
            turnIndex = turnIndex,
            utterance = normalized,
            request = SlotFillRequest(
                utterance = normalized,
                sessionId = current.sessionId,
                conversationHistory = current.history.toList(),
            ),
        )
    }

    @Synchronized
    fun applyResponse(pending: PendingRequest, response: SlotFillState): Outcome? {
        val current = active ?: return null
        if (
            current.generation != pending.generation ||
            current.pendingTurnIndex != pending.turnIndex ||
            stage != Stage.REQUESTING
        ) {
            return null
        }
        current.pendingTurnIndex = null
        return when (response.status) {
            IntentDialogueStatus.NEEDS_CLARIFICATION -> {
                if (current.history.size >= MAX_HISTORY_MESSAGES) {
                    invalidate()
                    Outcome.LimitReached
                } else {
                    current.history.addLast(ConversationTurn(ConversationRole.USER, pending.utterance))
                    current.history.addLast(
                        ConversationTurn(ConversationRole.ASSISTANT, response.message),
                    )
                    current.turnIndex++
                    stage = Stage.SPEAKING
                    Outcome.Ask(current.generation, response.message)
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
        }
    }

    /** 슬롯 응답 뒤 앱 후보 왕복이 이어지는 동안에도 같은 사용자 턴인지 확인한다(#128). */
    @Synchronized
    fun isPending(pending: PendingRequest): Boolean {
        val current = active ?: return false
        return current.generation == pending.generation &&
            current.pendingTurnIndex == pending.turnIndex &&
            stage == Stage.REQUESTING
    }

    /** 질문 TTS가 끝났거나 실패해 화면 문구로 대체됐을 때 같은 대화를 다시 듣기 상태로 옮긴다. */
    @Synchronized
    fun continueListening(generation: Long): Boolean {
        val current = active ?: return false
        if (current.generation != generation || stage != Stage.SPEAKING) return false
        stage = Stage.LISTENING
        return true
    }

    /** 현재 HTTP 요청이 실패했다. 같은 발화를 추측해 재사용하지 않고 대화 전체를 닫는다. */
    @Synchronized
    fun fail(pending: PendingRequest): Boolean {
        val current = active ?: return false
        if (
            current.generation != pending.generation ||
            current.pendingTurnIndex != pending.turnIndex
        ) {
            return false
        }
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

    private fun invalidate() {
        generation++
        active = null
        stage = Stage.IDLE
    }

    companion object {
        const val MAX_HISTORY_MESSAGES = 10
    }
}
