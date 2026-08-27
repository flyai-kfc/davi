package com.davi.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 관측이 **무엇을 남기고 무엇을 안 남기는지**를 고정한다(#470).
 *
 * 이 파일이 지키는 것은 화면 정렬이 아니라 **개인정보 경계**다 — `SPEC.md` §4가 음성 경로에
 * 패턴 마스킹을 일부러 안 걸었고, 그 결정이 성립하는 근거가 「로그에 문장이 안 남는다」 하나다.
 * 그래서 「길이만 남는다」가 깨지는 것은 정렬이 3dp 어긋나는 것과 다른 등급의 사고다.
 */
class ConversationObservationTest {

    private fun question(id: String, prompt: String, options: List<String>) =
        ClarificationQuestion(id = id, prompt = prompt, options = options, allowFreeform = true)

    @Test
    fun `구체화 응답은 개수와 길이만 남긴다`() {
        val shape = responseShapeOf(
            SlotFillState(
                status = IntentDialogueStatus.NEEDS_CLARIFICATION,
                message = "어느 앱으로 사진을 보낼까요?",
                clarification = ClarificationPlan(
                    questions = listOf(
                        question("app", "어느 앱으로 사진을 보낼까요?", listOf("카카오톡", "문자 메시지")),
                        question("who", "누구에게 보낼까요?", emptyList()),
                    ),
                ),
            ),
        )

        assertEquals("needs_clarification", shape.status)
        assertEquals(2, shape.questionCount)
        assertEquals("2,0", shape.optionCounts)
        assertEquals("어느 앱으로 사진을 보낼까요?".length, shape.messageLength)
        assertNull("질문 응답에는 goal 축이 없다", shape.hasGoal)
    }

    @Test
    fun `준비 완료 응답에는 질문 축이 없다 — 0으로 지어내지 않는다`() {
        val shape = responseShapeOf(
            SlotFillState(
                status = IntentDialogueStatus.READY,
                message = "카카오톡으로 사진을 보낼게요",
                goal = "카카오톡에서 엄마에게 사진 보내기",
            ),
        )

        assertEquals("ready", shape.status)
        assertEquals(true, shape.hasGoal)
        // **0이 아니라 null이다.** 0으로 채우면 「질문이 와야 하는데 안 왔다」와
        // 「애초에 질문이 없는 상태다」가 로그에서 구별되지 않는다.
        assertNull(shape.questionCount)
        assertNull(shape.optionCounts)
    }

    @Test
    fun `안전 종료 응답은 두 축이 다 비어 있다`() {
        val shape = responseShapeOf(
            SlotFillState(
                status = IntentDialogueStatus.UNABLE_TO_FINALIZE,
                message = "무엇을 도와드릴지 못 알아들었어요",
            ),
        )

        assertEquals("unable_to_finalize", shape.status)
        assertNull(shape.questionCount)
        assertNull(shape.hasGoal)
    }

    /**
     * **이 테스트가 이 파일의 이유다.** 뽑아낸 값 어디에도 원문이 없어야 한다 — 필드가 하나
     * 늘어날 때 여기서 걸린다.
     */
    @Test
    fun `뽑아낸 값 어디에도 문장이 실리지 않는다`() {
        val goal = "김영희에게 병원 예약 문자 보내기"
        val prompt = "010-1234-5678로 보낼까요?"
        val option = "김영희"
        val shape = responseShapeOf(
            SlotFillState(
                status = IntentDialogueStatus.NEEDS_CLARIFICATION,
                message = prompt,
                clarification = ClarificationPlan(
                    questions = listOf(question("who", prompt, listOf(option))),
                ),
            ),
        )

        val rendered = shape.toString()
        listOf(goal, prompt, option, "김영희", "010-1234-5678").forEach { secret ->
            assertTrue(
                "관측 값에 원문이 실렸다: $secret in $rendered",
                !rendered.contains(secret),
            )
        }
        // **문자열 축이 둘이고 둘 다 사용자 입력이 아니다**(#471 리뷰 3번 — 「하나뿐」이라고
        // 적었다가 지적받았다). 개수가 아니라 **각 축이 무엇에서 오는가**가 안전의 근거라,
        // 그것을 직접 단정한다.
        assertEquals("계약 enum의 이름", "needs_clarification", shape.status)
        assertTrue(
            "option_counts에 숫자와 쉼표 말고 다른 것이 들어갔다: ${shape.optionCounts}",
            shape.optionCounts!!.all { it.isDigit() || it == ',' },
        )
    }

    // ── 답의 형태 (#471 리뷰 1번·6번) ──────────────────────────────────────

    private fun asked(options: List<String>) = VoiceDialogueSession.Outcome.Clarify(
        generation = 0L,
        question = question("app", "어느 앱으로 보낼까요?", options),
        questionIndex = 1,
        questionCount = 2,
    )

    @Test
    fun `보기를 누르면 번호로 남는다`() {
        val shape = answerShapeOf(
            asked(listOf("카카오톡", "문자 메시지")),
            ClarificationAnswerSource.OPTION,
            "문자 메시지",
        )

        assertEquals(1, shape.questionIndex)
        assertEquals(2, shape.questionCount)
        assertEquals("option", shape.source)
        assertEquals(1, shape.optionIndex)
        assertEquals("문자 메시지".length, shape.answerLength)
    }

    /**
     * **이 갈래가 통째로 안 찍히고 있었다**(#471 리뷰 1번) — 호출부가 구체화 상태에서만
     * 질문을 꺼냈는데 직접 말하기는 청취 상태에서 온다. 그래서 「보기냐 직접 말하기냐」를
     * 알려고 넣은 이벤트가 **직접 말한 쪽에서는 한 번도 안 나갔다.**
     */
    @Test
    fun `직접 말하면 번호가 없고 길이만 남는다`() {
        val shape = answerShapeOf(
            asked(listOf("카카오톡", "문자 메시지")),
            ClarificationAnswerSource.FREEFORM,
            "김영희 언니한테",
        )

        assertEquals("freeform", shape.source)
        assertNull("직접 말한 답에는 보기 번호가 없다", shape.optionIndex)
        assertEquals(8, shape.answerLength)
        assertFalse(
            "답 원문이 관측 값에 실렸다: $shape",
            shape.toString().contains("김영희"),
        )
    }

    @Test
    fun `보기를 누르면 번호로 남고 직접 말하면 번호가 없다`() {
        val options = listOf("카카오톡", "문자 메시지", "인스타그램")

        assertEquals(1, optionIndexOf(ClarificationAnswerSource.OPTION, options, "문자 메시지"))
        assertNull(optionIndexOf(ClarificationAnswerSource.FREEFORM, options, "문자 메시지"))
        // 보기에 없는 값이 OPTION으로 오면 번호를 지어내지 않는다.
        assertNull(optionIndexOf(ClarificationAnswerSource.OPTION, options, "카카오T"))
    }
}
