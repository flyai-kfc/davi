package com.davi.helper

import org.junit.Assert.assertFalse
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
        // 계약이 문구를 200자로 묶는다(SlotFillApiClient.MAX_QUESTION_PROMPT_LENGTH — private다).
        val long = speechGuardMillis(200)

        assertTrue(short > 0)
        assertTrue("긴 문구가 더 짧게 잡혔다", long > short)
        // 한국어 TTS는 초당 대여섯 자다 — 글자당 300ms면 1.5배 남짓의 여유다.
        assertTrue("여유가 모자란다", short >= 300L * "어느 앱으로 보낼까요?".length)
        // 계약이 문구 길이를 묶으므로 상한이 무한이 아니다.
        assertTrue("상한이 1분을 넘는다", long <= 70_000L)
    }

    @Test
    fun `글자 수가 0이어도 상한이 음수가 되지 않는다`() {
        assertTrue(speechGuardMillis(0) > 0)
        assertTrue(speechGuardMillis(-5) > 0)
    }
}
