package com.davi.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 미루기의 기한(#380).
 *
 * 이 단위가 없던 동안 샘플러는 순수 디바운스였고, 스스로 움직이는 화면에서 **110초 동안
 * 폴링이 0회**였다(2026-08-23 SM-S921N 실측). 여기 고정하는 것은 두 가지다 —
 * 조용한 화면의 지연은 그대로일 것, 폭주하는 화면에서도 기한 안에 뜰 것.
 */
class SampleScheduleTest {

    /**
     * 서비스의 상수를 **참조한다**(값을 옮겨 적지 않는다). 옮겨 적으면 서비스 쪽 값이 바뀌어도
     * 아래 `pollMs < MAX_DEFER_MS` 관문이 조용히 통과해, 폴링 주기가 상한에 잘리는 것을 못 잡는다.
     */
    private val settleMs = GuideAccessibilityService.SETTLE_MS

    private val pollMs = GuideAccessibilityService.POLL_MS

    /** 실측된 폭주 간격 — 배민 홈의 이벤트 평균 36ms(3041건/110초). */
    private val stormGapMs = 36L

    /**
     * 기한에 닿기 전이면 요청한 지연 그대로다. 조용한 화면의 동작은 바뀌지 않아야 한다 —
     * 상한은 폭주할 때만 개입한다.
     */
    @Test
    fun `기한 안이면 요청한 지연 그대로다`() {
        val schedule = SampleSchedule()

        assertEquals(settleMs, schedule.delayFor(settleMs, nowMs = 0))
    }

    /**
     * 유휴 폴링도 상한보다 짧아 건드려지지 않는다. 상한을 `POLL_MS`보다 낮게 잡으면
     * 폴링 주기가 조용히 빨라지므로, 그 관계를 여기서 고정한다.
     */
    @Test
    fun `유휴 폴링 주기는 상한에 걸리지 않는다`() {
        val schedule = SampleSchedule()

        assertEquals(pollMs, schedule.delayFor(pollMs, nowMs = 0))
        assertTrue(pollMs < SampleSchedule.MAX_DEFER_MS)
    }

    /** 기한이 가까워지면 요청값이 아니라 남은 시간이 답이다. */
    @Test
    fun `기한이 가까우면 남은 시간까지만 미룬다`() {
        val schedule = SampleSchedule(maxDeferMs = 1_000)
        schedule.delayFor(settleMs, nowMs = 0)

        assertEquals(100L, schedule.delayFor(settleMs, nowMs = 900))
    }

    /** 기한이 이미 지났으면 음수가 아니라 0이다 — `postDelayed`에 음수를 넘기지 않는다. */
    @Test
    fun `기한이 지났으면 지연 없이 뜬다`() {
        val schedule = SampleSchedule(maxDeferMs = 1_000)
        schedule.delayFor(settleMs, nowMs = 0)

        assertEquals(0L, schedule.delayFor(settleMs, nowMs = 1_500))
    }

    /**
     * **이 테스트가 회귀의 본체다.** 순수 디바운스에서는 지연이 늘 `SETTLE_MS`(350)라
     * 36ms마다 오는 이벤트에 계속 취소돼 영원히 뜨지 않는다. 기한이 있으면 지연이 점점
     * 줄다가 이벤트 간격보다 짧아지는 순간 실제로 뜬다.
     */
    @Test
    fun `이벤트가 폭주해도 기한 안에는 뜬다`() {
        val schedule = SampleSchedule()
        var firedAtMs: Long? = null

        var nowMs = 0L
        while (nowMs <= 10_000) {
            val delay = schedule.delayFor(settleMs, nowMs)
            // 다음 이벤트가 오기 전에 예약이 만료되면 그때 샘플이 뜬다.
            if (delay <= stormGapMs) {
                firedAtMs = nowMs + delay
                break
            }
            nowMs += stormGapMs
        }

        assertTrue("폭주 중 샘플이 뜨지 않았다", firedAtMs != null)
        assertTrue(
            "기한($firedAtMs)을 넘겨 떴다",
            firedAtMs!! <= SampleSchedule.MAX_DEFER_MS,
        )
    }

    /**
     * 예약을 걷었다 다시 잡으면 기한도 새로 연다. 걷은 채로 기한이 지나가게 두면 되살아난
     * 첫 예약이 지연 0으로 계산돼 **settle 없이** 애니메이션 한복판을 뜬다 — 화면 꺼짐
     * 복귀와 세션 재시작이 그 경로다.
     */
    @Test
    fun `걷었다 다시 잡으면 기한을 새로 연다`() {
        val schedule = SampleSchedule()
        schedule.delayFor(settleMs, nowMs = 0)

        schedule.reset()

        assertEquals(settleMs, schedule.delayFor(settleMs, nowMs = 10_000))
    }

    /** 샘플이 뜬 뒤의 첫 예약도 같다 — 기한은 예약 사슬마다 하나다. */
    @Test
    fun `샘플이 뜬 뒤 예약은 상한을 다시 받는다`() {
        val schedule = SampleSchedule(maxDeferMs = 1_000)
        schedule.delayFor(settleMs, nowMs = 0)
        schedule.delayFor(settleMs, nowMs = 900)

        // 여기서 샘플이 떴다.
        schedule.reset()

        assertEquals(settleMs, schedule.delayFor(settleMs, nowMs = 1_000))
        assertEquals(200L, schedule.delayFor(settleMs, nowMs = 1_800))
    }
}
