package com.davi.helper.e2e

import android.app.UiAutomation
import androidx.test.runner.AndroidJUnitRunner

/** UIAutomator의 반복적인 기본 UiAutomation 요청에서도 Davi 접근성 서비스를 유지하는 test runner. */
class DaviE2eRunner : AndroidJUnitRunner() {
    override fun getUiAutomation(): UiAutomation =
        requireNotNull(super.getUiAutomation(DONT_SUPPRESS))

    override fun getUiAutomation(flags: Int): UiAutomation =
        requireNotNull(super.getUiAutomation(flags or DONT_SUPPRESS))

    private companion object {
        const val DONT_SUPPRESS = UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
    }
}
