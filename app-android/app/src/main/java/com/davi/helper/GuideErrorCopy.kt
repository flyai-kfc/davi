package com.davi.helper

/**
 * 안내 중 실패 → 안내창에 띄울 문구(#29).
 *
 * 대화 화면의 같은 대응은 `ConversationActivity.errorMessageFor`에 있다. 둘은 문구가 다르지만
 * **가르는 기준은 [bucketOf] 하나를 공유한다** — 같은 원인이 두 화면에서 다른 말로 나오지
 * 않게 하는 것이 이 분리의 목적이다.
 *
 * 안내 중 오류는 앱이 알아서 재시도하므로 문구에 행동 지시를 넣지 않는다.
 */
object GuideErrorCopy {

    /** [result]가 실패면 띄울 문구, 성공이면 null. */
    fun messageRes(result: NextStepResult): Int? = when (result) {
        is NextStepResult.Success -> null
        is NextStepResult.ApiFailure -> messageRes(bucketOf(result.error))
        // 서버가 200 밖의 코드를 줬거나(HttpFailure) 준 body가 계약과 다르다(ParseFailure).
        // 둘 다 사용자가 할 수 있는 일이 없어 SERVER로 간다.
        is NextStepResult.HttpFailure -> messageRes(ApiErrorBucket.SERVER)
        is NextStepResult.ParseFailure -> messageRes(ApiErrorBucket.SERVER)
        is NextStepResult.NetworkFailure -> messageRes(ApiErrorBucket.NETWORK)
    }

    fun messageRes(bucket: ApiErrorBucket): Int = when (bucket) {
        ApiErrorBucket.NETWORK -> R.string.guide_error_network
        ApiErrorBucket.BUSY -> R.string.guide_error_busy
        ApiErrorBucket.DAILY_LIMIT -> R.string.guide_error_daily_limit
        ApiErrorBucket.SERVER -> R.string.guide_error_server
    }
}
