package com.davi.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 강조 사각형 브리딩 계산의 고정(#147).
 *
 * "숨 쉬어 보이는가"는 실기기 확인 항목이고, 여기서는 그것이 가능하려면 참이어야 하는 성질을
 * 지킨다 — 특히 **안쪽 선이 불투명을 유지하는가**와 **읽는 중 테두리보다 진한가**다. 이 둘이
 * 무너지면 "지금 눌러야 할 곳"과 "배경 표시"의 층 구분이 사라진다.
 */
class HighlightMotionTest {

    // --- 층 구분 (ReadingBorder와의 관계) ------------------------------------------

    @Test
    fun `안쪽 선은 두께가 변하지 않는다`() {
        // 자리와 굵기가 함께 흔들리면 대상이 떨려 보인다. 숨 쉬는 것은 글로우뿐이다.
        val strokes = SAMPLE_PHASES.map { HighlightMotion.frame(it, BASE, SPREAD).coreStroke }
        strokes.forEach { assertEquals(BASE, it, TOLERANCE) }
    }

    @Test
    fun `가장 퍼진 후광은 읽는 중 테두리보다 진하다`() {
        // ReadingBorder가 세운 규칙 — 색이 같아도 층이 갈려야 한다. 강조가 배경 표시보다
        // 물러서면 "지금 눌러야 할 곳"이 흐려진다.
        val strongestHalo = SAMPLE_PHASES.maxOf { HighlightMotion.frame(it, BASE, SPREAD).glowBaseAlpha }
        // 읽는 중 테두리에서 더 두드러지는 층은 glow가 아니라 core다 — 둘 중 큰 값과 견준다.
        val strongestReading = SAMPLE_PHASES.maxOf {
            val frame = ReadingBorder.frame(it, BASE, SPREAD)
            maxOf(frame.glowAlpha, frame.coreAlpha)
        }
        assertTrue(
            "강조 최대 $strongestHalo 가 읽는 중 최대 $strongestReading 이하다",
            strongestHalo > strongestReading,
        )
    }

    @Test
    fun `가장 오므라들면 후광이 보이지 않는다`() {
        // 그때는 안쪽 선에 겹치므로, 보이면 이중 테두리가 된다.
        val frame = HighlightMotion.frame(0f, BASE, SPREAD)
        assertEquals(0f, frame.glowSpread, TOLERANCE)
        assertEquals(0, frame.glowBaseAlpha)
    }

    @Test
    fun `읽는 중 테두리와 같은 리듬을 쓴다`() {
        // 두 테두리가 한 화면에 뜨므로 최대·최소 시점이 같아야 따로 놀지 않는다.
        // (같은 애니메이터를 공유하므로 위상도 같다 — 곡선까지 같아야 리듬이 맞는다.)
        val highlightPeak = SAMPLE_PHASES.maxByOrNull { HighlightMotion.frame(it, BASE, SPREAD).glowSpread }
        val readingPeak = SAMPLE_PHASES.maxByOrNull { ReadingBorder.frame(it, BASE, SPREAD).glowStroke }
        assertEquals(readingPeak, highlightPeak)
    }

    // --- 숨쉬기 ------------------------------------------------------------------

    @Test
    fun `후광은 퍼지면서 함께 진해진다`() {
        val thin = HighlightMotion.frame(0f, BASE, SPREAD)
        val thick = HighlightMotion.frame(0.5f, BASE, SPREAD)
        assertTrue(thick.glowSpread > thin.glowSpread)
        assertTrue(thick.glowBaseAlpha > thin.glowBaseAlpha)
    }

    @Test
    fun `가장 퍼져도 정해진 여유를 넘지 않는다`() {
        // 그리는 쪽이 이 여유만큼 미리 물려 두므로, 넘으면 화면 가장자리에서 잘린다.
        val widest = SAMPLE_PHASES.maxOf { HighlightMotion.frame(it, BASE, SPREAD).glowSpread }
        assertTrue("최대 $widest 가 상한 $SPREAD 를 넘었다", widest <= SPREAD)
    }

    @Test
    fun `한 주기가 지나면 같은 모습으로 돌아온다`() {
        val at0 = HighlightMotion.frame(0f, BASE, SPREAD)
        val at1 = HighlightMotion.frame(1f, BASE, SPREAD)
        assertEquals(at0.glowSpread, at1.glowSpread, TOLERANCE)
        assertEquals(at0.glowBaseAlpha, at1.glowBaseAlpha)
    }

    @Test
    fun `범위 밖 위상도 감싸서 받는다`() {
        // 애니메이터가 반복하며 1을 넘길 수 있고, 음수도 안전해야 한다.
        val base = HighlightMotion.frame(0.25f, BASE, SPREAD)
        assertEquals(base.glowSpread, HighlightMotion.frame(3.25f, BASE, SPREAD).glowSpread, TOLERANCE)
        assertEquals(base.glowSpread, HighlightMotion.frame(-0.75f, BASE, SPREAD).glowSpread, TOLERANCE)
    }

    @Test
    fun `위상이 다르면 모습도 다르다`() {
        assertNotEquals(
            HighlightMotion.frame(0f, BASE, SPREAD).glowBaseAlpha,
            HighlightMotion.frame(0.5f, BASE, SPREAD).glowBaseAlpha,
        )
    }

    // --- 정지 프레임 ---------------------------------------------------------------

    @Test
    fun `정지 위상은 가장 진한 한 장이다`() {
        // 애니메이터가 꺼진 기기에서 그릴 한 장. 가장 옅은 순간이 걸리면 강조가 약해 보인다.
        // **기준은 ReadingBorder다** — 애니메이터를 공유하므로 위상도 하나뿐이다.
        val static = HighlightMotion.frame(ReadingBorder.STATIC_PHASE, BASE, SPREAD)
        val strongest = SAMPLE_PHASES.maxOf { HighlightMotion.frame(it, BASE, SPREAD).glowBaseAlpha }
        assertEquals(strongest, static.glowBaseAlpha)
        assertTrue("정지 화면에서 후광이 보여야 한다", static.glowBaseAlpha > 0)
    }

    @Test
    fun `후광 겹은 바깥으로 갈수록 옅어진다`() {
        // 선형으로 줄면 번짐이 아니라 굵은 띠로 보인다.
        //
        // 겹이 많아지면 바깥쪽 몇 겹은 정수 절삭으로 같은 값(0)이 될 수 있다 — 거기서는
        // "줄지 않는다"가 아니라 "이미 사라졌다"는 뜻이므로 단조 비증가로 본다.
        val alphas = (0 until HighlightMotion.GLOW_RING_COUNT).map { HighlightMotion.ringAlphaAt(it, 200) }
        alphas.zipWithNext { inner, outer -> assertTrue("$inner >= $outer 여야 한다", inner >= outer) }
        assertTrue("안쪽은 진해야 한다", alphas.first() > alphas[alphas.size / 2])
        assertTrue("가운데도 옅어져야 한다", alphas[alphas.size / 2] > alphas.last())
        assertEquals("가장 바깥은 사라진다", 0, alphas.last())
    }

    @Test
    fun `후광 겹은 바깥으로 갈수록 멀어지고 상한을 넘지 않는다`() {
        val offsets = (0 until HighlightMotion.GLOW_RING_COUNT).map { HighlightMotion.ringOffsetAt(it, SPREAD) }
        offsets.zipWithNext { inner, outer -> assertTrue(inner < outer) }
        assertEquals("가장 바깥이 번짐 거리와 같아야 한다", SPREAD, offsets.last(), TOLERANCE)
    }

    @Test
    fun `후광 겹은 간격보다 두꺼워 서로 겹친다`() {
        // 간격만큼만 두면 알파 차이가 경계선으로 드러나 동심원 띠로 보인다.
        val spacing = SPREAD / HighlightMotion.GLOW_RING_COUNT
        val stroke = HighlightMotion.ringStrokeFor(SPREAD, BASE)
        assertTrue("두께 $stroke 가 간격 $spacing 보다 커야 한다", stroke > spacing)
    }

    @Test
    fun `번짐이 0이어도 겹 두께가 0이 되지 않는다`() {
        assertTrue(HighlightMotion.ringStrokeFor(0f, BASE) >= BASE)
    }

    @Test
    fun `후광 투명도는 언제나 유효 범위 안이다`() {
        SAMPLE_PHASES.forEach {
            val alpha = HighlightMotion.frame(it, BASE, SPREAD).glowBaseAlpha
            assertTrue("위상 $it 에서 $alpha", alpha in 0..255)
        }
    }

    private companion object {
        const val BASE = 4f
        const val SPREAD = 8f
        const val TOLERANCE = 0.001f
        val SAMPLE_PHASES = (0..40).map { it / 40f }
    }
}
