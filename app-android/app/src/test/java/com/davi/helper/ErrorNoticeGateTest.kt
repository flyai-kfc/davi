package com.davi.helper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 오류 표시 게이트(#29).
 *
 * 이 클래스가 따로 있는 이유가 아래 두 테스트다 — 백오프 카운터를 빌려 쓰면 ×아직이에요·done
 * 불신에서도 값이 올라가 **첫** 실패에서 문구가 뜨고(PR #189 리뷰), 반대로 "서버에 닿았으면
 * 성공"으로 읽으면 500·429가 계속 도는 동안 문구가 **영영 안 뜬다**(#189 2차 리뷰).
 */
class ErrorNoticeGateTest {

    private val gate = ErrorNoticeGate()

    @Test
    fun `첫 실패는 알리지 않고 두 번째부터 알린다`() {
        assertFalse(gate.onRequestFailed())
        assertTrue(gate.onRequestFailed())
    }

    /** 상한 뒤로도 계속 true다 — 오류는 걷힐 때까지 화면에 있어야 한다(재그리기 억제는 표시 쪽 몫). */
    @Test
    fun `상한을 넘어도 계속 알린다`() {
        repeat(2) { gate.onRequestFailed() }

        assertTrue(gate.onRequestFailed())
        assertTrue(gate.onRequestFailed())
    }

    @Test
    fun `성공하면 다시 처음부터 센다`() {
        repeat(3) { gate.onRequestFailed() }

        gate.onRequestSucceeded()

        assertFalse(gate.onRequestFailed())
        assertTrue(gate.onRequestFailed())
    }

    @Test
    fun `세션이 바뀌면 이전 세션의 실패를 끌고 가지 않는다`() {
        gate.onRequestFailed()

        gate.reset()

        assertFalse(gate.onRequestFailed())
    }

    /**
     * 백오프 카운터와 **독립**이어야 한다. `ScreenStabilityTracker.markFailed`는 요청 실패 말고도
     * `onConfirmReject`·done 불신 `RETRY`에서 불리는데, 그 값으로 게이트를 열면 사용자가
     * ×아직이에요를 누른 직후 첫 끊김에 문구가 떠 버린다.
     */
    @Test
    fun `백오프 카운터가 올라가도 게이트는 열리지 않는다`() {
        val tracker = ScreenStabilityTracker()
        tracker.markFailed() // onConfirmReject 경로 — 요청은 아직 한 번도 실패하지 않았다
        tracker.markFailed() // done 불신 RETRY 경로

        assertFalse(gate.onRequestFailed()) // 요청 실패 1회째 — 아직 알리지 않는다
    }

    /**
     * **서버에 닿은 실패도 실패로 센다.** `ApiFailure`(4xx/5xx)·`HttpFailure`·`ParseFailure`에서
     * [ErrorNoticeGate.onRequestSucceeded]를 부르면 서버가 500·429를 계속 돌리는 동안 카운터가
     * 매번 0으로 돌아가 `guide_error_server`·`guide_error_busy`가 영원히 안 뜬다 —
     * 그 두 버킷이 존재하는 이유가 바로 그 상황이다(PR #189 2차 리뷰).
     */
    @Test
    fun `서버가 오류를 돌려준 것도 실패로 세어 문구가 뜬다`() {
        // 5xx가 연달아 온 상황 — 닿기는 닿았다
        assertFalse(gate.onRequestFailed())
        assertTrue(gate.onRequestFailed())
        assertTrue(gate.onRequestFailed())
    }
}
