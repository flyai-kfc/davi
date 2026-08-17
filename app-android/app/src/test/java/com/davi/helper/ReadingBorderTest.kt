package com.davi.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 읽는 중 표시의 판정과 위상 매핑 고정(GD-1, #127).
 * 특히 "응답 대기 중에는 판정이 IDLE로 온다"는 트래커 계약을 여기서 못 박는다 —
 * 그걸 놓치면 요청이 도는 내내, 즉 가장 오래 기다리는 구간에 표시가 꺼진다.
 */
class ReadingBorderTest {

    private fun reading(
        sessionActive: Boolean = true,
        screenReadable: Boolean = true,
        decision: ScreenStabilityTracker.Decision? = null,
        requestInFlight: Boolean = false,
        awaitingConfirm: Boolean = false,
    ) = ReadingBorder.isReading(
        sessionActive,
        screenReadable,
        decision,
        requestInFlight,
        awaitingConfirm,
    )

    @Test
    fun `세션 시작 후 첫 샘플 전에는 켠다`() {
        assertTrue(reading(decision = null))
    }

    @Test
    fun `화면이 흔들리는 동안 켠다`() {
        assertTrue(reading(decision = ScreenStabilityTracker.Decision.WAIT))
    }

    @Test
    fun `요청을 보내는 순간 켠다`() {
        assertTrue(reading(decision = ScreenStabilityTracker.Decision.SEND))
    }

    @Test
    fun `응답 대기 중에는 판정이 IDLE이어도 켠다`() {
        assertTrue(
            reading(decision = ScreenStabilityTracker.Decision.IDLE, requestInFlight = true),
        )
    }

    /** 완료 확인 중에는 같은 자리를 성공 tone 테두리가 쓴다(GD-3, #139). 겹치면 색만 깜빡인다. */
    @Test
    fun `완료 확인을 기다리는 동안에는 끈다`() {
        assertFalse(reading(decision = null, awaitingConfirm = true))
        assertFalse(
            reading(
                decision = ScreenStabilityTracker.Decision.WAIT,
                requestInFlight = true,
                awaitingConfirm = true,
            ),
        )
    }

    @Test
    fun `처리 끝난 화면에서는 끈다`() {
        assertFalse(reading(decision = ScreenStabilityTracker.Decision.IDLE))
    }

    @Test
    fun `정체 알림이 뜬 화면에서는 끈다`() {
        assertFalse(reading(decision = ScreenStabilityTracker.Decision.STUCK))
    }

    @Test
    fun `세션이 없으면 무조건 끈다`() {
        assertFalse(reading(sessionActive = false, requestInFlight = true))
        assertFalse(reading(sessionActive = false, decision = ScreenStabilityTracker.Decision.WAIT))
    }

    @Test
    fun `읽을 화면이 없으면 무조건 끈다`() {
        // 우리 앱이 앞에 있는 경우 등 — 아래에 그릴 대상이 없다
        assertFalse(reading(screenReadable = false, requestInFlight = true))
        assertFalse(reading(screenReadable = false, decision = ScreenStabilityTracker.Decision.WAIT))
    }

    @Test
    fun `위상 0은 가장 옅고 0_5는 가장 진하다`() {
        val low = ReadingBorder.frame(0f, BASE, SPREAD)
        val high = ReadingBorder.frame(0.5f, BASE, SPREAD)
        assertTrue(high.coreAlpha > low.coreAlpha)
        assertTrue(high.glowAlpha > low.glowAlpha)
        assertTrue(high.glowStroke > low.glowStroke)
        // 한 주기를 돌면 처음으로 돌아온다
        assertEquals(low, ReadingBorder.frame(1f, BASE, SPREAD))
    }

    @Test
    fun `숨을 들이키는 구간은 단조 증가한다`() {
        var previous = ReadingBorder.frame(0f, BASE, SPREAD).coreAlpha
        for (step in 1..10) {
            val current = ReadingBorder.frame(step * 0.05f, BASE, SPREAD).coreAlpha
            assertTrue("step=$step", current >= previous)
            previous = current
        }
    }

    @Test
    fun `주기 밖 위상도 감싸서 받고 alpha는 항상 범위 안이다`() {
        listOf(-1.25f, -0.5f, 0f, 0.5f, 1.75f, 3f).forEach { phase ->
            val frame = ReadingBorder.frame(phase, BASE, SPREAD)
            assertTrue("phase=$phase", frame.coreAlpha in 0..255)
            assertTrue("phase=$phase", frame.glowAlpha in 0..255)
        }
        // 위상이 정확히 한 주기 어긋나도 같은 그림이다
        assertEquals(ReadingBorder.frame(0.25f, BASE, SPREAD), ReadingBorder.frame(1.25f, BASE, SPREAD))
    }

    @Test
    fun `글로우는 항상 안쪽 선보다 굵고 옅다`() {
        listOf(0f, 0.25f, 0.5f, 0.75f).forEach { phase ->
            val frame = ReadingBorder.frame(phase, BASE, SPREAD)
            assertTrue("phase=$phase", frame.glowStroke >= frame.coreStroke)
            assertTrue("phase=$phase", frame.glowAlpha < frame.coreAlpha)
        }
    }

    @Test
    fun `강조 사각형보다 훨씬 옅다`() {
        // 색이 같아도 층이 갈려야 한다 — 강조는 불투명(255)이다
        val strongest = ReadingBorder.frame(0.5f, BASE, SPREAD)
        assertTrue(strongest.coreAlpha < 128)
    }

    private companion object {
        const val BASE = 4f
        const val SPREAD = 12f
    }
}
