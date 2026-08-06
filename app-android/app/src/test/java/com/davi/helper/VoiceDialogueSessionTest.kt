package com.davi.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceDialogueSessionTest {
    @Test
    fun `first request keeps current utterance outside empty history`() {
        val session = VoiceDialogueSession { "00000000-0000-4000-8000-000000000001" }

        val generation = session.start()
        val pending = requireNotNull(session.nextRequest("  카메라 앱 열어줘  "))

        assertEquals(generation, pending.generation)
        assertEquals(0, pending.turnIndex)
        assertEquals("카메라 앱 열어줘", pending.request.utterance)
        assertEquals("00000000-0000-4000-8000-000000000001", pending.request.sessionId)
        assertTrue(pending.request.conversationHistory.isEmpty())
        assertEquals(VoiceDialogueSession.Stage.REQUESTING, session.stage)
    }

    @Test
    fun `follow-up stores only completed user assistant pair`() {
        val session = VoiceDialogueSession { "00000000-0000-4000-8000-000000000001" }
        session.start()
        val first = requireNotNull(session.nextRequest("사진을 보내고 싶어"))

        val outcome = session.applyResponse(
            first,
            SlotFillState(
                IntentDialogueStatus.NEEDS_CLARIFICATION,
                "사진을 어디로 보내고 싶으세요?",
            ),
        ) as VoiceDialogueSession.Outcome.Ask

        assertEquals(VoiceDialogueSession.Stage.SPEAKING, session.stage)
        assertTrue(session.continueListening(outcome.generation))
        val second = requireNotNull(session.nextRequest("메신저로 보호자에게"))
        assertEquals("메신저로 보호자에게", second.request.utterance)
        assertEquals(
            listOf(
                ConversationTurn(ConversationRole.USER, "사진을 보내고 싶어"),
                ConversationTurn(
                    ConversationRole.ASSISTANT,
                    "사진을 어디로 보내고 싶으세요?",
                ),
            ),
            second.request.conversationHistory,
        )
    }

    @Test
    fun `ready response returns server goal without starting guide session`() {
        val session = VoiceDialogueSession { "00000000-0000-4000-8000-000000000001" }
        session.start()
        val pending = requireNotNull(session.nextRequest("카메라 열어줘"))

        val outcome = session.applyResponse(
            pending,
            SlotFillState(
                IntentDialogueStatus.READY,
                "카메라 앱 열기로 정리했어요.",
                "카메라 앱 열기",
            ),
        ) as VoiceDialogueSession.Outcome.Ready

        assertEquals("카메라 앱 열기", outcome.goal)
        assertEquals(VoiceDialogueSession.Stage.READY, session.stage)
        assertTrue(session.finish(outcome.generation))
        assertEquals(VoiceDialogueSession.Stage.IDLE, session.stage)
    }

    @Test
    fun `late response from cancelled generation cannot affect restarted dialogue`() {
        var index = 0
        val session = VoiceDialogueSession {
            "00000000-0000-4000-8000-${String.format("%012d", ++index)}"
        }
        session.start()
        val old = requireNotNull(session.nextRequest("같은 목표"))

        session.cancel()
        session.start()
        val current = requireNotNull(session.nextRequest("같은 목표"))

        assertNull(
            session.applyResponse(
                old,
                SlotFillState(IntentDialogueStatus.READY, "이전 응답", "이전 목표"),
            ),
        )
        assertEquals(VoiceDialogueSession.Stage.REQUESTING, session.stage)
        assertTrue(
            session.applyResponse(
                current,
                SlotFillState(IntentDialogueStatus.READY, "현재 응답", "현재 목표"),
            ) is VoiceDialogueSession.Outcome.Ready,
        )
    }

    @Test
    fun `five completed follow-ups are bounded and sixth question stops`() {
        val session = VoiceDialogueSession { "00000000-0000-4000-8000-000000000001" }
        var generation = session.start()

        repeat(5) { turn ->
            val pending = requireNotNull(session.nextRequest("답변 $turn"))
            val outcome = session.applyResponse(
                pending,
                SlotFillState(
                    IntentDialogueStatus.NEEDS_CLARIFICATION,
                    "질문 $turn",
                ),
            ) as VoiceDialogueSession.Outcome.Ask
            generation = outcome.generation
            assertTrue(session.continueListening(generation))
        }

        val last = requireNotNull(session.nextRequest("추가 답변"))
        assertEquals(VoiceDialogueSession.MAX_HISTORY_MESSAGES, last.request.conversationHistory.size)
        assertEquals(
            VoiceDialogueSession.Outcome.LimitReached,
            session.applyResponse(
                last,
                SlotFillState(IntentDialogueStatus.NEEDS_CLARIFICATION, "추가 질문"),
            ),
        )
        assertEquals(VoiceDialogueSession.Stage.IDLE, session.stage)
        assertFalse(session.continueListening(generation))
    }

    @Test
    fun `request failure invalidates only matching pending turn`() {
        val session = VoiceDialogueSession { "00000000-0000-4000-8000-000000000001" }
        session.start()
        val pending = requireNotNull(session.nextRequest("목표"))

        assertTrue(session.fail(pending))
        assertEquals(VoiceDialogueSession.Stage.IDLE, session.stage)
        assertFalse(session.fail(pending))
    }
}
