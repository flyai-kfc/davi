package com.davi.helper

import org.junit.Assert.assertEquals
import org.junit.Test

class GuideApiFailurePolicyTest {

    private val trace = ServerResponseTrace(requestId = null, logicalRequestId = null, attempt = null)

    @Test
    fun `세션 challenge는 로그인 복구로 보낸다`() {
        val result = failure(sessionAuthenticationRequired = true)

        assertEquals(
            GuideApiFailureAction.REQUIRE_LOGIN,
            GuideApiFailurePolicy.actionFor(result),
        )
    }

    @Test
    fun `하루 한도는 재시도하지 않고 안내를 끝낸다`() {
        val result = failure(rateLimitScope = RateLimitScope.DAILY)

        assertEquals(
            GuideApiFailureAction.STOP_DAILY_LIMIT,
            GuideApiFailurePolicy.actionFor(result),
        )
    }

    @Test
    fun `짧은 한도는 기존 백오프로 재시도한다`() {
        val result = failure(rateLimitScope = RateLimitScope.BURST)

        assertEquals(GuideApiFailureAction.RETRY, GuideApiFailurePolicy.actionFor(result))
    }

    private fun failure(
        rateLimitScope: RateLimitScope? = null,
        sessionAuthenticationRequired: Boolean = false,
    ) = NextStepResult.ApiFailure(
        statusCode = if (sessionAuthenticationRequired) 401 else 429,
        error = ApiError(
            kind = if (sessionAuthenticationRequired) ApiErrorKind.AUTH else ApiErrorKind.QUOTA,
            message = "request failed",
            rateLimitScope = rateLimitScope,
        ),
        trace = trace,
        sessionAuthenticationRequired = sessionAuthenticationRequired,
    )
}
