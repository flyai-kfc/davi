package com.davi.helper.e2e

import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume

internal object LiveGeminiTestEnvironment {
    fun requireReady() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        Assume.assumeTrue(
            "실제 Gemini 실기기 테스트는 -e davi.liveGemini true에서만 실행한다",
            args.getString(ARG_LIVE_GEMINI) == "true",
        )

        // 별도 driver 프로세스의 UiAutomation이 Davi 접근성 서비스를 억제하지 않게 한다.
        val automation = instrumentation.getUiAutomation(
            UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES,
        ) ?: throw GuidedScenarioFailure("ui_automation_connect_failed")

        val enabledServices = Settings.Secure.getString(
            instrumentation.targetContext.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        if (!enabledServices.contains(DAVI_SERVICE)) {
            throw GuidedScenarioFailure("accessibility_service_disabled")
        }
        waitForAccessibilityBinding(automation)
        // 실제 서버 모드·overlay app-op은 host 실행 스크립트가 모델 호출 전에 확인한다.
    }

    private fun waitForAccessibilityBinding(automation: UiAutomation) {
        repeat(BIND_ATTEMPTS) {
            val state = ParcelFileDescriptor.AutoCloseInputStream(
                automation.executeShellCommand("dumpsys accessibility"),
            ).bufferedReader().use { it.readText() }
            val bound = state.lineSequence()
                .firstOrNull { it.contains("Bound services:") }
                ?.contains(DAVI_SERVICE) == true
            val crashed = state.lineSequence()
                .firstOrNull { it.contains("Crashed services:") }
                ?.contains(DAVI_SERVICE) == true
            if (bound && !crashed) {
                // dumpsys 표시는 앱 callback보다 약간 먼저 바뀔 수 있어 한 번 더 안정화한다.
                SystemClock.sleep(BIND_SETTLE_MS)
                return
            }
            SystemClock.sleep(BIND_POLL_MS)
        }
        throw GuidedScenarioFailure("accessibility_service_not_bound")
    }

    private const val ARG_LIVE_GEMINI = "davi.liveGemini"
    /**
     * 접근성 서비스 컴포넌트. **축약형(`pkg/.Cls`)을 쓰지 않는다** — 축약형은 클래스를
     * `<applicationId>.Cls`로 풀기 때문에 접미어가 붙은 debug에서 틀린 이름이 된다(#58).
     * dumpsys와 `ENABLED_ACCESSIBILITY_SERVICES`가 찍는 것도 이 완전형이다(실측).
     */
    private const val DAVI_SERVICE =
        "${GuideE2eContract.DAVI_PACKAGE}/${GuideE2eContract.DAVI_CLASS_ROOT}.GuideAccessibilityService"
    private const val BIND_ATTEMPTS = 30
    private const val BIND_POLL_MS = 500L
    private const val BIND_SETTLE_MS = 1_000L
}
