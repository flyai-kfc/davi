package com.davi.helper

import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 오류 표시 게이트(#29).
 *
 * 게이트가 **결과를 통째로 받는** 이유가 아래 `서버가 오류를 돌려준 것도 실패로 센다`다 —
 * 호출자가 성공/실패를 골라 넘기면 `ApiFailure`를 "닿았으니 성공"으로 배선하는 실수가 들어오고,
 * 그러면 500·429가 계속 도는 동안 문구가 **영영 안 뜬다**(PR #189 2차 리뷰).
 * 백오프 카운터와 독립이어야 하는 이유는 `백오프 카운터가 올라가도…`가 지킨다(1차 리뷰).
 */
class ErrorNoticeGateTest {

    private val gate = ErrorNoticeGate()

    private val trace = ServerResponseTrace(requestId = null, logicalRequestId = null, attempt = null)

    private fun apiFailure(kind: ApiErrorKind = ApiErrorKind.SERVER) = NextStepResult.ApiFailure(
        statusCode = 500,
        error = ApiError(kind = kind, message = "디버그용 상세"),
        trace = trace,
    )

    private fun networkFailure() = NextStepResult.NetworkFailure(IOException("끊김"))

    private fun success() = NextStepResult.Success(
        NextStepResponse(
            action = GuideAction(
                expectedResult = "시계 앱이 열려 있다",
                evidence = "시계",
                actionType = ActionType.TAP,
                targetNodeId = "n1",
                instruction = "시계를 눌러 주세요",
            ),
            model = "stub",
            screenSignature = "sig",
        ),
        trace,
    )

    @Test
    fun `첫 실패는 알리지 않고 두 번째부터 알린다`() {
        assertFalse(gate.record(networkFailure()))
        assertTrue(gate.record(networkFailure()))
    }

    /** 상한 뒤로도 계속 true다 — 오류는 걷힐 때까지 화면에 있어야 한다(재그리기 억제는 표시 쪽 몫). */
    @Test
    fun `상한을 넘어도 계속 알린다`() {
        repeat(2) { gate.record(networkFailure()) }

        assertTrue(gate.record(networkFailure()))
        assertTrue(gate.record(networkFailure()))
    }

    @Test
    fun `성공하면 다시 처음부터 센다`() {
        repeat(3) { gate.record(networkFailure()) }

        assertFalse(gate.record(success()))

        assertFalse(gate.record(networkFailure()))
        assertTrue(gate.record(networkFailure()))
    }

    /**
     * **막으려는 실수를 실제로 잡는 테스트.** `ApiFailure`·`HttpFailure`·`ParseFailure`는 서버에
     * 닿았지만 안내를 못 그렸다 — 이것들을 성공으로 세면 서버가 계속 오류를 돌리는 동안 카운터가
     * 매번 0으로 돌아가 `guide_error_server`·`guide_error_busy`가 영원히 안 뜬다.
     */
    @Test
    fun `서버가 오류를 돌려준 것도 실패로 센다`() {
        assertFalse(gate.record(apiFailure(ApiErrorKind.SERVER)))
        assertTrue(gate.record(apiFailure(ApiErrorKind.QUOTA)))
        assertTrue(gate.record(NextStepResult.HttpFailure(statusCode = 503, trace = trace)))
        assertTrue(gate.record(NextStepResult.ParseFailure(IllegalStateException("계약 불일치"))))
    }

    /** 원인이 섞여도 "연속 실패"는 이어진다 — 사용자에겐 전부 "안내를 못 받았다"로 같다. */
    @Test
    fun `원인이 달라도 연속으로 센다`() {
        assertFalse(gate.record(networkFailure()))
        assertTrue(gate.record(apiFailure()))
    }

    @Test
    fun `세션이 바뀌면 이전 세션의 실패를 끌고 가지 않는다`() {
        gate.record(networkFailure())

        gate.reset()

        assertFalse(gate.record(networkFailure()))
    }

    /**
     * 백오프 카운터와 **독립**이어야 한다. `ScreenStabilityTracker.markFailed`는 요청 실패 말고도
     * `onConfirmReject`·done 불신 `RETRY`에서 불리는데, 그 값으로 게이트를 열면 사용자가
     * ×아직이에요를 누른 직후 첫 끊김에 문구가 떠 버린다.
     */
    @Test
    fun `백오프 카운터가 올라가도 게이트는 열리지 않는다`() {
        val tracker = ScreenStabilityTracker()
        tracker.markFailed(0) // onConfirmReject 경로 — 요청은 아직 한 번도 실패하지 않았다
        tracker.markFailed(0) // done 불신 RETRY 경로

        assertFalse(gate.record(networkFailure())) // 요청 실패 1회째 — 아직 알리지 않는다
    }
}
