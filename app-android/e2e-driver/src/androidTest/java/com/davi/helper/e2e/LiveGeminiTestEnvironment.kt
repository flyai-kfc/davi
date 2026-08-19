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
            val bound = dumpsysSection(state, "Bound services").contains(DAVI_SERVICE)
            val crashed = dumpsysSection(state, "Crashed services").contains(DAVI_SERVICE)
            if (bound && !crashed) {
                // dumpsys 표시는 앱 callback보다 약간 먼저 바뀔 수 있어 한 번 더 안정화한다.
                SystemClock.sleep(BIND_SETTLE_MS)
                return
            }
            SystemClock.sleep(BIND_POLL_MS)
        }
        throw GuidedScenarioFailure("accessibility_service_not_bound")
    }

    /**
     * `dumpsys accessibility`의 섹션 하나(`라벨:{...}`)를 통째로 잘라낸다. 섹션은 전부 `이름:{`
     * 꼴이라 다음 섹션 머리 직전까지가 한 블록이다.
     *
     * **한 줄만 보면 안 된다** — 바인딩된 서비스가 둘 이상이면 두 번째부터 줄이 바뀌어서(#302
     * 실측), 첫 줄만 읽으면 첫 서비스만 본다. debug/release가 함께 켜진 기기에서 우리 서비스가
     * 둘째 자리에 붙으면 멀쩡히 바인딩된 것을 `accessibility_service_not_bound`로 떨어뜨린다.
     */
    private fun dumpsysSection(dump: String, label: String): String {
        val header = Regex("""^\s*${Regex.escape(label)}\s*:\{""")
        val section = StringBuilder()
        var inside = false
        for (line in dump.lineSequence()) {
            if (!inside) {
                if (header.containsMatchIn(line)) {
                    inside = true
                    section.appendLine(line)
                }
            } else {
                if (ANY_SECTION_HEADER.containsMatchIn(line)) break
                section.appendLine(line)
            }
        }
        return section.toString()
    }

    private const val ARG_LIVE_GEMINI = "davi.liveGemini"

    /** 섹션 머리(`이름:{`). 서비스가 여러 줄로 이어질 때 어디서 끊을지 판단하는 기준이다. */
    private val ANY_SECTION_HEADER = Regex("""^\s*[A-Za-z][A-Za-z0-9 ]*:\{""")

    /**
     * 접근성 서비스 컴포넌트. **축약형(`pkg/.Cls`)을 쓰지 않는다** — 축약형은 클래스를
     * `<applicationId>.Cls`로 풀기 때문에 접미어가 붙은 debug에서 틀린 이름이 된다(#58).
     * `ENABLED_ACCESSIBILITY_SERVICES`가 찍는 것도 이 완전형이다(실측).
     *
     * dumpsys는 **대상에 따라 다르다** — 클래스가 applicationId로 시작하면 축약형으로 줄여서,
     * release는 `com.davi.helper/.GuideAccessibilityService`로 찍힌다. 접미어가 붙은 debug만
     * 완전형으로 나온다(#302 실측). 여기 확인은 debug만 대상으로 하므로 완전형 하나면 된다.
     */
    private const val DAVI_SERVICE =
        "${GuideE2eContract.DAVI_PACKAGE}/${GuideE2eContract.DAVI_CLASS_ROOT}.GuideAccessibilityService"
    private const val BIND_ATTEMPTS = 30
    private const val BIND_POLL_MS = 500L
    private const val BIND_SETTLE_MS = 1_000L
}
