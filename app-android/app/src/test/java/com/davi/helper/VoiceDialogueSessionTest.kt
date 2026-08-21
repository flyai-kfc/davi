package com.davi.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceDialogueSessionTest {
    private fun question(
        id: String,
        prompt: String,
        options: List<String> = listOf("카카오톡", "문자"),
    ) = ClarificationQuestion(
        id = id,
        prompt = prompt,
        options = options,
        allowFreeform = true,
    )

    @Test
    fun `first request contains only original utterance`() {
        val session = VoiceDialogueSession { SESSION_ID }

        val generation = session.start()
        val pending = requireNotNull(session.nextRequest("  사진 보내고 싶어  "))

        assertEquals(generation, pending.generation)
        assertEquals(0, pending.turnIndex)
        assertEquals("사진 보내고 싶어", pending.request.utterance)
        assertEquals(SESSION_ID, pending.request.sessionId)
        assertTrue(pending.request.conversationHistory.isEmpty())
        assertTrue(pending.request.clarificationAnswers.isEmpty())
        assertEquals(VoiceDialogueSession.Stage.REQUESTING, session.stage)
    }

    @Test
    fun `two questions are answered locally before one final request`() {
        val session = VoiceDialogueSession { SESSION_ID }
        session.start()
        val first = requireNotNull(session.nextRequest("사진 보내고 싶어"))
        val questions = listOf(
            question("q1", "어느 방식으로 보낼까요?"),
            question("q2", "누구에게 보낼까요?"),
        )

        val firstQuestion = session.applyResponse(
            first,
            SlotFillState(
                status = IntentDialogueStatus.NEEDS_CLARIFICATION,
                message = questions.first().prompt,
                clarification = ClarificationPlan(questions),
            ),
        ) as VoiceDialogueSession.Outcome.Clarify

        assertEquals(0, firstQuestion.questionIndex)
        assertEquals(2, firstQuestion.questionCount)
        assertEquals(VoiceDialogueSession.Stage.CLARIFYING, session.stage)

        val next = session.answerCurrent(ClarificationAnswerSource.OPTION, "문자")
            as VoiceDialogueSession.AnswerOutcome.NextQuestion
        assertEquals("q2", next.outcome.question.id)
        assertEquals(VoiceDialogueSession.Stage.CLARIFYING, session.stage)

        val final = session.answerCurrent(ClarificationAnswerSource.FREEFORM, "보호자에게")
            as VoiceDialogueSession.AnswerOutcome.FinalRequest
        assertEquals(1, final.pending.turnIndex)
        assertEquals("사진 보내고 싶어", final.pending.request.utterance)
        assertEquals(2, final.pending.request.clarificationAnswers.size)
        assertEquals("문자", final.pending.request.clarificationAnswers[0].answer)
        assertEquals("보호자에게", final.pending.request.clarificationAnswers[1].answer)
        assertEquals(VoiceDialogueSession.Stage.REQUESTING, session.stage)
    }

    @Test
    fun `legacy unknown answer source remains wire compatible`() {
        val session = VoiceDialogueSession { SESSION_ID }
        session.start()
        val first = requireNotNull(session.nextRequest("사진 보내기"))
        session.applyResponse(
            first,
            SlotFillState(
                IntentDialogueStatus.NEEDS_CLARIFICATION,
                "어느 앱인가요?",
                clarification = ClarificationPlan(listOf(question("q1", "어느 앱인가요?"))),
            ),
        )

        val final = session.answerCurrent(
            ClarificationAnswerSource.UNKNOWN_OR_DECLINED,
            "모르거나 답변하지 않음",
        ) as VoiceDialogueSession.AnswerOutcome.FinalRequest

        assertEquals(
            ClarificationAnswerSource.UNKNOWN_OR_DECLINED,
            final.pending.request.clarificationAnswers.single().source,
        )
    }

    @Test
    fun `spoken unknown phrase is sent as freeform`() {
        val session = VoiceDialogueSession { SESSION_ID }
        session.start()
        val first = requireNotNull(session.nextRequest("사진 보내기"))
        session.applyResponse(
            first,
            SlotFillState(
                IntentDialogueStatus.NEEDS_CLARIFICATION,
                "어느 앱인가요?",
                clarification = ClarificationPlan(listOf(question("q1", "어느 앱인가요?"))),
            ),
        )

        val final = session.answerCurrent(
            ClarificationAnswerSource.FREEFORM,
            "잘 모르겠어요",
        ) as VoiceDialogueSession.AnswerOutcome.FinalRequest
        val answer = final.pending.request.clarificationAnswers.single()

        assertEquals(ClarificationAnswerSource.FREEFORM, answer.source)
        assertEquals("잘 모르겠어요", answer.answer)
    }

    @Test
    fun `question without options remains answerable by freeform`() {
        val session = VoiceDialogueSession { SESSION_ID }
        session.start()
        val first = requireNotNull(session.nextRequest("도움이 필요해"))
        val question = question("q1", "어떻게 하고 싶으세요?", options = emptyList())

        val outcome = session.applyResponse(
            first,
            SlotFillState(
                IntentDialogueStatus.NEEDS_CLARIFICATION,
                question.prompt,
                clarification = ClarificationPlan(listOf(question)),
            ),
        )

        assertTrue(outcome is VoiceDialogueSession.Outcome.Clarify)
        val final = session.answerCurrent(ClarificationAnswerSource.FREEFORM, "화면을 크게 보고 싶어요")
        assertTrue(final is VoiceDialogueSession.AnswerOutcome.FinalRequest)
    }

    @Test
    fun `ready response returns server goal`() {
        val session = VoiceDialogueSession { SESSION_ID }
        val generation = session.start()
        val pending = requireNotNull(session.nextRequest("카메라 열어줘"))

        val outcome = session.applyResponse(
            pending,
            SlotFillState(IntentDialogueStatus.READY, "정리했어요.", "카메라 앱 열기"),
        ) as VoiceDialogueSession.Outcome.Ready

        assertEquals("카메라 앱 열기", outcome.goal)
        assertEquals(VoiceDialogueSession.Stage.READY, session.stage)
        assertTrue(session.finish(generation))
        assertEquals(VoiceDialogueSession.Stage.IDLE, session.stage)
    }

    @Test
    fun `second response cannot create a third question`() {
        val session = VoiceDialogueSession { SESSION_ID }
        session.start()
        val first = requireNotNull(session.nextRequest("사진 보내기"))
        val q = question("q1", "어느 앱인가요?")
        session.applyResponse(
            first,
            SlotFillState(
                IntentDialogueStatus.NEEDS_CLARIFICATION,
                q.prompt,
                clarification = ClarificationPlan(listOf(q)),
            ),
        )
        val second = (
            session.answerCurrent(ClarificationAnswerSource.OPTION, "문자")
                as VoiceDialogueSession.AnswerOutcome.FinalRequest
            ).pending

        val outcome = session.applyResponse(
            second,
            SlotFillState(
                IntentDialogueStatus.NEEDS_CLARIFICATION,
                q.prompt,
                clarification = ClarificationPlan(listOf(q)),
            ),
        )

        assertEquals(VoiceDialogueSession.Outcome.InvalidResponse, outcome)
        assertEquals(VoiceDialogueSession.Stage.IDLE, session.stage)
    }

    @Test
    fun `late response cannot affect restarted dialogue`() {
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
        assertTrue(
            session.applyResponse(
                current,
                SlotFillState(IntentDialogueStatus.READY, "현재 응답", "현재 목표"),
            ) is VoiceDialogueSession.Outcome.Ready,
        )
    }

    @Test
    fun `request failure invalidates only matching request`() {
        val session = VoiceDialogueSession { SESSION_ID }
        session.start()
        val pending = requireNotNull(session.nextRequest("목표"))

        assertTrue(session.fail(pending))
        assertEquals(VoiceDialogueSession.Stage.IDLE, session.stage)
        assertFalse(session.fail(pending))
    }

    private companion object {
        const val SESSION_ID = "00000000-0000-4000-8000-000000000001"
    }
}
