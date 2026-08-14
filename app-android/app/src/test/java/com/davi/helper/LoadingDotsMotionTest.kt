package com.davi.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 응답 대기 점 3개 계산의 고정(#142).
 *
 * 시안 캡션이 규격을 확정해 뒀으므로("0.6s 주기, 0.15s 간격, 모션 끄기 시 정지 프레임"),
 * 여기서는 그 규격이 지켜지는지와 **정지 프레임이 쓸 만한지**를 지킨다.
 */
class LoadingDotsMotionTest {

    // --- 시간 0의 모습 = 시안의 정지 화면 ----------------------------------------

    @Test
    fun `시간 0에서 첫 점이 가장 크고 셋째가 가장 작다`() {
        // 모션 끄기에서 이 순간이 정지 프레임으로 그려진다. 시안(메인 6)이 그린 순간과 같아야
        // 한다 — 모든 점이 최소인 순간이 걸리면 점이 사라진 것처럼 보인다.
        val first = progress(0, 0f)
        val second = progress(1, 0f)
        val third = progress(2, 0f)
        assertEquals("첫 점은 최대여야 한다", 1f, first, 0.001f)
        assertEquals("셋째 점은 최소여야 한다", 0f, third, 0.001f)
        assertTrue("둘째는 그 사이여야 한다", second in 0.1f..0.9f)
    }

    @Test
    fun `정지 프레임에서도 모든 점이 보인다`() {
        // 최소값이 0이어도 지름·불투명도는 하한이 있어 사라지지 않는다.
        repeat(LoadingDotsMotion.DOT_COUNT) { index ->
            val p = progress(index, 0f)
            assertTrue(LoadingDotsMotion.lerp(13f, 20f, p) >= 13f)
            assertTrue(LoadingDotsMotion.lerp(74f, 255f, p) >= 74f)
        }
    }

    @Test
    fun `주기가 0이어도 시간 0의 모습을 준다`() {
        // 0으로 나누는 사고 없이 정지 프레임을 그릴 수 있어야 한다.
        val p = LoadingDotsMotion.progressAt(0, elapsedSeconds = 5f, periodSeconds = 0f, staggerSeconds = 0.15f)
        assertEquals(1f, p, 0.001f)
    }

    // --- 순서대로 커졌다 작아진다 -------------------------------------------------

    @Test
    fun `점들은 서로 다른 시점에 최대가 된다`() {
        val peaks = (0 until LoadingDotsMotion.DOT_COUNT).map { index ->
            // 각 점이 최대가 되는 시각은 자기 시차만큼 밀려 있다.
            progress(index, index * STAGGER)
        }
        peaks.forEach { assertEquals("자기 차례에는 최대여야 한다", 1f, it, 0.001f) }
    }

    @Test
    fun `한 주기가 지나면 같은 모습으로 돌아온다`() {
        repeat(LoadingDotsMotion.DOT_COUNT) { index ->
            assertEquals(progress(index, 0f), progress(index, PERIOD), 0.001f)
        }
    }

    @Test
    fun `진행도는 항상 0과 1 사이다`() {
        var t = 0f
        while (t < 3f) {
            repeat(LoadingDotsMotion.DOT_COUNT) { index ->
                val p = progress(index, t)
                assertTrue("t=$t index=$index 에서 $p", p in -0.001f..1.001f)
            }
            t += 0.01f
        }
    }

    @Test
    fun `같은 시각에 세 점이 모두 같지는 않다`() {
        // 셋이 같이 커졌다 작아지면 "순서대로"가 아니다.
        val values = (0 until LoadingDotsMotion.DOT_COUNT).map { progress(it, 0.07f) }
        assertEquals(values.size, values.distinct().size)
    }

    // --- 지연 표시 ----------------------------------------------------------------

    @Test
    fun `지연 전에는 점을 그리지 않는다`() {
        // 응답이 빠르면 점이 한 번 깜빡이고 사라져 오히려 산만하다. 그 구간에는 문구만 보인다.
        assertFalse(LoadingDotsMotion.shouldDrawAt(0f, DELAY, motionDisabled = false))
        assertFalse(LoadingDotsMotion.shouldDrawAt(DELAY - 0.01f, DELAY, motionDisabled = false))
    }

    @Test
    fun `지연이 지나면 점을 그린다`() {
        assertTrue(LoadingDotsMotion.shouldDrawAt(DELAY, DELAY, motionDisabled = false))
        assertTrue(LoadingDotsMotion.shouldDrawAt(DELAY + 1f, DELAY, motionDisabled = false))
    }

    @Test
    fun `점이 나오는 첫 순간은 시안의 정지 화면과 같다`() {
        // 지연을 빼지 않으면 점이 주기 중간에서 튀어나온다.
        val animationSeconds = LoadingDotsMotion.animationSecondsAt(DELAY, DELAY, motionDisabled = false)
        assertEquals(0f, animationSeconds, 0.001f)
        assertEquals(1f, progress(0, animationSeconds), 0.001f)
    }

    @Test
    fun `지연이 0이면 곧바로 그린다`() {
        assertTrue(LoadingDotsMotion.shouldDrawAt(0f, delaySeconds = 0f, motionDisabled = false))
    }

    @Test
    fun `지연 구간의 경과 시간은 음수가 되지 않는다`() {
        assertEquals(0f, LoadingDotsMotion.animationSecondsAt(0f, DELAY, motionDisabled = false), 0.001f)
    }

    // --- 모션 끄기 x 지연 (PR #144 리뷰 회귀) --------------------------------------

    @Test
    fun `모션이 꺼져 있으면 지연을 기다리지 않고 곧바로 그린다`() {
        // 이전 구현은 모션이 꺼졌을 때 elapsed=0 프레임 하나를 지연 구간으로 판정해
        // 아무것도 그리지 않았다. 다음 프레임이 오지 않으므로 점이 영영 안 나왔다 —
        // 정지 프레임이 아니라 빈 화면이었다.
        //
        // **두 조건을 조합해야 잡힌다.** 시간 0의 진행도와 지연을 각각만 검사하면
        // 둘 다 통과하면서 화면에는 아무것도 없다.
        assertTrue(LoadingDotsMotion.shouldDrawAt(0f, DELAY, motionDisabled = true))
    }

    @Test
    fun `모션이 꺼져 있으면 시간 0의 모습을 그린다`() {
        val animationSeconds = LoadingDotsMotion.animationSecondsAt(0f, DELAY, motionDisabled = true)
        assertEquals(0f, animationSeconds, 0.001f)
        assertEquals("첫 점은 최대여야 한다", 1f, progress(0, animationSeconds), 0.001f)
        assertEquals("셋째 점은 최소여야 한다", 0f, progress(2, animationSeconds), 0.001f)
    }

    @Test
    fun `모션이 꺼진 유일한 프레임으로 점 세 개가 모두 그려진다`() {
        // MotionDriver가 흘리는 프레임 하나 (elapsed=0, delta=0) 만으로 화면이 완성돼야 한다.
        val elapsed = 0f
        assertTrue(LoadingDotsMotion.shouldDrawAt(elapsed, DELAY, motionDisabled = true))
        val animationSeconds = LoadingDotsMotion.animationSecondsAt(elapsed, DELAY, motionDisabled = true)
        repeat(LoadingDotsMotion.DOT_COUNT) { index ->
            val p = progress(index, animationSeconds)
            assertTrue("점 $index 의 지름이 0이면 안 된다", LoadingDotsMotion.lerp(13f, 20f, p) >= 13f)
            assertTrue("점 $index 이 완전히 투명하면 안 된다", LoadingDotsMotion.lerp(74f, 255f, p) >= 74f)
        }
    }

    @Test
    fun `모션이 꺼져 있으면 시간이 흘러도 같은 모습이다`() {
        // 정지 프레임이므로 elapsed가 무엇이든 결과가 같아야 한다.
        listOf(0f, 1f, 10f).forEach { elapsed ->
            assertEquals(
                0f,
                LoadingDotsMotion.animationSecondsAt(elapsed, DELAY, motionDisabled = true),
                0.001f,
            )
        }
    }

    // --- 값 보간 ------------------------------------------------------------------

    @Test
    fun `보간은 최소와 최대 사이를 잇는다`() {
        assertEquals(13f, LoadingDotsMotion.lerp(13f, 20f, 0f), 0.001f)
        assertEquals(20f, LoadingDotsMotion.lerp(13f, 20f, 1f), 0.001f)
        assertEquals(16.5f, LoadingDotsMotion.lerp(13f, 20f, 0.5f), 0.001f)
    }

    @Test
    fun `범위 밖 진행도는 접힌다`() {
        assertEquals(13f, LoadingDotsMotion.lerp(13f, 20f, -1f), 0.001f)
        assertEquals(20f, LoadingDotsMotion.lerp(13f, 20f, 2f), 0.001f)
    }

    private fun progress(index: Int, elapsedSeconds: Float) =
        LoadingDotsMotion.progressAt(index, elapsedSeconds, PERIOD, STAGGER)

    private companion object {
        /** 시안 캡션이 준 값. */
        const val PERIOD = 0.6f
        const val STAGGER = 0.15f
        const val DELAY = 0.4f
    }
}
