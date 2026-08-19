package com.davi.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #249 / #144. PR #243이 지운 "모션 끄기 × 지연" 가드의 나머지 절반.
 *
 * **지연은 프레임 시계로 재지 않는다** — 모션이 꺼지면 [MotionDriver]는 `elapsed=0` 프레임을 딱
 * 한 장 준다. 그 한 장을 "아직 때가 아니다"로 판정하면 다음 기회가 없어 정지 프레임이 아니라
 * 빈 화면이 된다. 여는 신호가 프레임과 무관해야 하는 이유가 그것이다.
 *
 * 짝이 되는 가드는 [MotionActivationTest]다.
 */
class LoadingDotsGateTest {

    private val gate = LoadingDotsGate()

    // --- 드러나기 전 ---------------------------------------------------------------

    @Test
    fun `드러나기 전에는 그리지 않는다`() {
        assertFalse(gate.isRevealed())
    }

    @Test
    fun `프레임이 아무리 흘러도 트리거 없이는 열리지 않는다`() {
        // 여는 조건이 프레임 시계로 되돌아가면 여기서 걸린다 — 그게 #144였다.
        var seconds = 0f
        repeat(100) {
            gate.onFrame(seconds)
            seconds += 0.1f
        }

        assertFalse("프레임만으로 열리면 안 된다", gate.isRevealed())
    }

    // --- 모션 끄기 x 지연 (PR #144 리뷰 회귀) ----------------------------------------

    @Test
    fun `한 프레임만 와도 드러난 뒤에는 그린다`() {
        // 모션 끄기의 실제 순서를 그대로 재현한다 — 정지 프레임 한 장이 먼저 흐르고(#249
        // MotionActivation의 "구동 조작이 훅보다 먼저"), 그 뒤 전이 훅이 rearm한다.
        gate.onFrame(0f)
        gate.rearm()

        gate.reveal()

        assertTrue("정지 프레임 한 장으로도 그려져야 한다", gate.isRevealed())
        assertEquals(0f, gate.animationSeconds(), 0.001f)
    }

    // --- 드러난 뒤 -----------------------------------------------------------------

    @Test
    fun `드러난 순간의 프레임 시각을 뺀다`() {
        // 지연 상수가 아니라 드러난 시각을 빼야 첫 프레임이 시안의 정지 화면이 된다 —
        // 메인 스레드가 밀려 핸들러가 늦게 발화해도 어긋나지 않는다.
        gate.onFrame(0.42f)
        gate.reveal()
        assertEquals(0f, gate.animationSeconds(), 0.001f)

        gate.onFrame(0.72f)
        assertEquals(0.3f, gate.animationSeconds(), 0.001f)
    }

    @Test
    fun `가려진 동안에도 프레임 시각을 따라간다`() {
        // 안 따라가면 reveal이 낡은 시각을 집어 점이 주기 중간에서 튀어나온다.
        gate.rearm()
        gate.onFrame(1.5f)

        gate.reveal()

        assertEquals(0f, gate.animationSeconds(), 0.001f)
    }

    @Test
    fun `되감으면 다시 감춘다`() {
        gate.onFrame(0.4f)
        gate.reveal()
        assertTrue(gate.isRevealed())

        gate.rearm()

        assertFalse("대기에 다시 들어가면 지연부터 다시 센다", gate.isRevealed())
    }

    @Test
    fun `프레임 시각이 0으로 되돌아가도 경과는 음수가 되지 않는다`() {
        // driver가 재시작되면 elapsed가 0부터 다시 온다.
        gate.onFrame(2f)
        gate.reveal()

        gate.onFrame(0f)

        assertEquals(0f, gate.animationSeconds(), 0.001f)
    }
}
