package com.davi.helper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 구체화 카드의 접힘 규칙(#452). **예전에는 이 셋이 액티비티 안에 변수 세 개로 흩어져 있어
 * 그물이 실기기 확인뿐이었다** — 그 사이 TTS 끝 신호가 안 오는 갈래가 조용히 열려 있었다.
 */
class QuestionFoldGateTest {

    @Test
    fun `읽어 주는 동안 펼치고 끝나면 접는다`() {
        val gate = QuestionFoldGate()

        gate.sync("어느 앱으로 보낼까요?", speaking = true)
        assertTrue(gate.expanded)

        gate.sync("어느 앱으로 보낼까요?", speaking = false)
        assertFalse(gate.expanded)
    }

    @Test
    fun `안 읽어 주면 처음부터 접힌다`() {
        // TTS가 꺼져 있거나 읽을 이유가 없는 갈래. 잃는 것은 없다 — 원문은 손잡이로 돌아온다.
        //
        // **이 규칙이 「첫 문항만 읽는다」를 뒤집게 만들었다**(2026-08-26). 그 규칙이 있는 동안
        // 두 번째 질문은 안 읽혀서 여기 걸렸고, 그러면 어르신이 그 질문을 **읽을 수도 들을 수도
        // 없다** — 손잡이를 눌러야만 원문이 나온다(showClarification KDoc).
        val gate = QuestionFoldGate()

        gate.sync("어느 앱으로 보낼까요?", speaking = false)

        assertFalse(gate.expanded)
    }

    @Test
    fun `손잡이를 누른 뒤에는 낭독 상태가 안 덮는다`() {
        // 펼쳐 놓고 읽는 중에 낭독이 끝났다고 접히면 **읽던 것이 사라진다.**
        val gate = QuestionFoldGate()
        gate.sync("질문", speaking = false)

        gate.toggle()
        assertTrue(gate.expanded)

        gate.sync("질문", speaking = false)
        assertTrue("낭독 상태가 손잡이를 덮었다", gate.expanded)
    }

    @Test
    fun `접어 둔 사람의 화면이 낭독 때문에 다시 열리지 않는다`() {
        val gate = QuestionFoldGate()
        gate.sync("질문", speaking = true)

        gate.toggle()
        assertFalse(gate.expanded)

        gate.sync("질문", speaking = true)
        assertFalse("손잡이로 접은 것을 낭독이 다시 폈다", gate.expanded)
    }

    @Test
    fun `질문이 바뀌면 손잡이 기록이 초기화된다`() {
        val gate = QuestionFoldGate()
        gate.sync("첫 질문", speaking = false)
        gate.toggle()

        gate.sync("두 번째 질문", speaking = false)

        assertFalse("앞 질문의 손잡이 기록이 남았다", gate.expanded)
    }

    @Test
    fun `구체화를 떠나면 기록을 통째로 지운다`() {
        // 남겨 두면 다른 화면을 거쳐 같은 질문으로 돌아왔을 때 옛 손잡이 기록이 되살아난다.
        val gate = QuestionFoldGate()
        gate.sync("질문", speaking = false)
        gate.toggle()

        gate.sync(null, speaking = false)
        assertFalse(gate.expanded)

        gate.sync("질문", speaking = false)
        assertFalse("떠났다 돌아왔는데 옛 기록이 살아났다", gate.expanded)
    }

    @Test
    fun `낭독 상한은 문구가 길수록 길고 계약 상한에서 멈춘다`() {
        // **이 값이 낭독보다 짧으면 읽는 도중에 접힌다** — 지금 고치려는 것보다 나쁘다.
        val short = speechGuardMillis("어느 앱으로 보낼까요?".length)
        // **계약 상수를 직접 읽는다**(#465 리뷰). 200을 베껴 두면 계약이 늘어도 이 테스트가
        // 옛 값만 재서 「상한이 무한이 아니다」가 조용히 거짓이 된다.
        val long = speechGuardMillis(SlotFillApiClient.MAX_QUESTION_PROMPT_LENGTH)

        assertTrue(short > 0)
        assertTrue("긴 문구가 더 짧게 잡혔다", long > short)
        // 한국어 TTS는 초당 대여섯 자다 — 글자당 300ms면 1.5배 남짓의 여유다.
        assertTrue("여유가 모자란다", short >= 300L * "어느 앱으로 보낼까요?".length)
        // 계약이 문구 길이를 묶으므로 상한이 무한이 아니다.
        assertTrue("상한이 1분을 넘는다", long <= 70_000L)
    }

    @Test
    fun `글자 수는 음수일 수 없다는 것이 계약이다`() {
        // 방어가 아니라 계약이다 — 입력은 String.length 한 곳에서만 온다.
        assertTrue(speechGuardMillis(0) > 0)
        assertThrows(IllegalArgumentException::class.java) { speechGuardMillis(-1) }
    }

    @Test
    fun `복귀한 화면은 낭독 없이도 펼쳐 둔다`() {
        // 답을 못 해서 돌아온 순간이 **질문이 가장 필요한 순간**이다 — 접힌 채로 뜨면
        // 어르신이 그것을 읽을 수도 들을 수도 없다(#465 리뷰).
        val gate = QuestionFoldGate()
        gate.sync("q1", speaking = true)
        gate.sync(null, speaking = false) // 「직접 말하기」 청취로 나갔다

        gate.sync("q1", speaking = false, resumed = true)
        assertTrue("복귀 화면이 접힌 채로 떴다", gate.expanded)

        // **같은 상태로 render가 한 번 더 돌아도 펼친 채다** — 첫 호출에만 성립하는 규칙이면
        // 뒤따르는 render가 조용히 접는다.
        gate.sync("q1", speaking = false, resumed = true)
        assertTrue("두 번째 render가 복귀 화면을 접었다", gate.expanded)
    }

    @Test
    fun `그물은 세션 세대 질문 낭독 세 축이 다 맞을 때만 유효하다`() {
        // 늦게 깨어난 그물이 그 사이 갈린 화면을 건드리면 안 된다.
        val armed = clarifyOutcome(generation = 1L, id = "q1")

        assertTrue(speechGuardApplies(armed, clarifying(armed, speaking = true)))
        assertFalse("낭독이 끝났는데 그물이 돌았다", speechGuardApplies(armed, clarifying(armed, speaking = false)))
        assertFalse(
            "다음 질문의 펼침을 걷어 갔다",
            speechGuardApplies(armed, clarifying(clarifyOutcome(1L, "q2"), speaking = true)),
        )
        assertFalse(
            "다음 세션의 화면을 건드렸다",
            speechGuardApplies(armed, clarifying(clarifyOutcome(2L, "q1"), speaking = true)),
        )
        assertFalse("구체화가 아닌 화면을 건드렸다", speechGuardApplies(armed, ConversationUiState.Home()))
        assertFalse(speechGuardApplies(armed, null))
    }

    private fun clarifyOutcome(generation: Long, id: String) = VoiceDialogueSession.Outcome.Clarify(
        generation = generation,
        question = ClarificationQuestion(id, "어느 앱으로 보낼까요?", listOf("카카오톡"), true),
        questionIndex = 0,
        questionCount = 1,
    )

    private fun clarifying(outcome: VoiceDialogueSession.Outcome.Clarify, speaking: Boolean) =
        ConversationUiState.Clarifying(outcome, speaking = speaking)
}
