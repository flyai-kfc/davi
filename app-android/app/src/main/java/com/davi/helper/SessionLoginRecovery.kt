package com.davi.helper

import android.app.Activity
import android.content.Intent

/** 세션 challenge 뒤 이전 로그인 결정을 지우고 진행 중 흐름을 취소해 로그인 화면으로 복구한다. */
internal class SessionLoginRecovery(
    private val activity: Activity,
    private val authStore: AuthStore,
    private val cancelCurrentFlow: () -> Unit,
) {
    private var started = false

    fun start() {
        if (started || activity.isFinishing || activity.isDestroyed) return
        started = true
        authStore.clearAuthenticationDecision()
        cancelCurrentFlow()
        activity.startActivity(
            Intent(activity, LoginActivity::class.java)
                .putExtra(LoginActivity.EXTRA_SESSION_AUTHENTICATION_REQUIRED, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        activity.finish()
    }
}
