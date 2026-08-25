package com.davi.helper

import android.content.Context

/** release에는 test receiver·broadcast가 없고 호출도 아무 효과가 없다. */
class GuideE2ePublisher(@Suppress("UNUSED_PARAMETER") context: Context) {
    val suppressDetailedScreenLogs: Boolean = false

    @Suppress("UNUSED_PARAMETER")
    fun displayed(
        generation: Long,
        requestSignature: String,
        request: NextStepRequest,
        response: NextStepResponse,
        target: UiElement?,
    ) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun rootState(available: Boolean) = Unit

    fun requestStarted() = Unit

    fun serverError() = Unit
}
