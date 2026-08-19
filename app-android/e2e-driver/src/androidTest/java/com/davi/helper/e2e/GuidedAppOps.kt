package com.davi.helper.e2e

import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

/**
 * 외부 앱 시나리오 Robot들이 공유하는 최소 기기 조작.
 *
 * 준비·독립 검증·정리 전용이다 — 안내 본 흐름의 조작 대상은 항상 Gemini 결정의 bounds를 쓴다.
 * launch가 shell(monkey)인 이유는 [SamsungClockRobot.openAlarmList]와 같다 — 제품·드라이버
 * manifest에 대상 앱 `<queries>`를 추가하지 않기 위해서다.
 */
internal object GuidedAppOps {
    fun forceStop(device: UiDevice, packageName: String) {
        runCatching { device.executeShellCommand("am force-stop $packageName") }
    }

    fun launch(device: UiDevice, packageName: String) {
        runCatching {
            device.executeShellCommand(
                "monkey -p $packageName -c android.intent.category.LAUNCHER 1",
            )
        }
        if (!device.wait(Until.hasObject(By.pkg(packageName).depth(0)), LAUNCH_TIMEOUT_MS)) {
            throw GuidedScenarioFailure("app_launch_failed")
        }
        device.waitForIdle(IDLE_TIMEOUT_MS)
    }

    fun isForeground(device: UiDevice, packageName: String): Boolean =
        device.hasObject(By.pkg(packageName).depth(0))

    /** text 또는 contentDescription에 fragment가 부분 문자열로 보이는 노드가 있는가. */
    fun hasVisibleLabelLike(device: UiDevice, fragment: String): Boolean =
        findVisibleLabelLike(device, fragment) != null

    fun findVisibleLabelLike(device: UiDevice, fragment: String): UiObject2? {
        val pattern = containsPattern(fragment)
        return device.findObject(By.text(pattern)) ?: device.findObject(By.desc(pattern))
    }

    fun containsPattern(fragment: String): Pattern = Pattern.compile(
        ".*" + Pattern.quote(fragment) + ".*",
        Pattern.CASE_INSENSITIVE or Pattern.DOTALL,
    )

    private const val LAUNCH_TIMEOUT_MS = 10_000L
    private const val IDLE_TIMEOUT_MS = 1_500L
}
