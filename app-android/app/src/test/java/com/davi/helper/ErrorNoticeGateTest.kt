package com.davi.helper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 오류 표시 게이트(#29).
 *
 * 이 클래스가 따로 있는 이유가 아래 마지막 테스트다 — 백오프 카운터를 빌려 쓰면
 * ×아직이에요·done 불신에서도 값이 올라가 **첫** 전송 실패에서 문구가 뜬다(PR #189 리뷰).
 */
class ErrorNoticeGateTest {

    private val gate = ErrorNoticeGate()

    @Test
    fun `첫 실패는 알리지 않고 두 번째부터 알린다`() {
        assertFalse(gate.onTransportFailure())
        assertTrue(gate.onTransportFailure())
    }

    /** 상한 뒤로도 계속 true다 — 오류는 걷힐 때까지 화면에 있어야 한다(재그리기 억제는 표시 쪽 몫). */
    @Test
    fun `상한을 넘어도 계속 알린다`() {
        repeat(2) { gate.onTransportFailure() }

        assertTrue(gate.onTransportFailure())
        assertTrue(gate.onTransportFailure())
    }

    @Test
    fun `서버에 닿으면 다시 처음부터 센다`() {
        repeat(3) { gate.onTransportFailure() }

        gate.onReached()

        assertFalse(gate.onTransportFailure())
        assertTrue(gate.onTransportFailure())
    }

    @Test
    fun `세션이 바뀌면 이전 세션의 실패를 끌고 가지 않는다`() {
        gate.onTransportFailure()

        gate.reset()

        assertFalse(gate.onTransportFailure())
    }

    /**
     * 백오프 카운터와 **독립**이어야 한다. `ScreenStabilityTracker.markFailed`는 전송 실패 말고도
     * `onConfirmReject`·done 불신 `RETRY`에서 불리는데, 그 값으로 게이트를 열면 사용자가
     * ×아직이에요를 누른 직후 첫 끊김에 문구가 떠 버린다.
     */
    @Test
    fun `백오프 카운터가 올라가도 게이트는 열리지 않는다`() {
        val tracker = ScreenStabilityTracker()
        tracker.markFailed() // onConfirmReject 경로 — 전송은 아직 한 번도 실패하지 않았다
        tracker.markFailed() // done 불신 RETRY 경로

        assertFalse(gate.onTransportFailure()) // 전송 실패 1회째 — 아직 알리지 않는다
    }
}
