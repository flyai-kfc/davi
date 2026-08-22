package com.davi.helper

/** 안내 API 실패 뒤 세션이 취할 동작. 서비스 배선과 분리해 JVM 테스트로 고정한다. */
enum class GuideApiFailureAction {
    RETRY,
    REQUIRE_LOGIN,
    STOP_DAILY_LIMIT,
}

object GuideApiFailurePolicy {

    fun actionFor(result: NextStepResult.ApiFailure): GuideApiFailureAction = when {
        result.sessionAuthenticationRequired -> GuideApiFailureAction.REQUIRE_LOGIN
        bucketOf(result.error) == ApiErrorBucket.DAILY_LIMIT ->
            GuideApiFailureAction.STOP_DAILY_LIMIT
        else -> GuideApiFailureAction.RETRY
    }
}
