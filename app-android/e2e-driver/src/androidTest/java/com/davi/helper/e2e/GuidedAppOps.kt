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

    /**
     * fragment가 보이는 노드가 나타날 때까지 기다렸다가 돌려준다.
     *
     * 콜드 스타트 직후에는 패키지 창이 떠도 홈 화면이 아직 그려지지 않는다 — 한 번만 찾고
     * 포기하면 스플래시를 "그 화면에 없다"로 읽는다(2026-08-21 배민 16.19.0 실측에서
     * 정리가 cart_entry_missing으로 죽었다).
     */
    fun waitForVisibleLabelLike(
        device: UiDevice,
        fragment: String,
        timeoutMs: Long,
    ): UiObject2? {
        val pattern = containsPattern(fragment)
        val half = (timeoutMs / 2).coerceAtLeast(1L)
        return device.wait(Until.findObject(By.text(pattern)), half)
            ?: device.wait(Until.findObject(By.desc(pattern)), half)
    }

    /**
     * fragment가 보이는 노드를 찾아 누른다. 누르기 직전에 다시 찾는다.
     *
     * 목록·달력처럼 계속 다시 그려지는 화면에서는 잡아 둔 노드가 누르기 전에 stale이 된다
     * (2026-08-21 에이닷 캘린더 실측에서 StaleObjectException으로 정리가 죽었다).
     *
     * @return 눌렀는가. 못 찾았거나 재시도까지 stale이면 거짓.
     */
    fun clickVisibleLabelLike(
        device: UiDevice,
        fragment: String,
        timeoutMs: Long,
    ): Boolean {
        repeat(CLICK_ATTEMPTS) {
            val node = waitForVisibleLabelLike(device, fragment, timeoutMs) ?: return false
            if (runCatching { node.click() }.isSuccess) return true
        }
        return false
    }

    /**
     * label이 **정확히** 그 문구인 노드를 찾아 누른다.
     *
     * 부분 문자열로는 구분이 안 되는 자리에 쓴다 — 예: 확인 대화상자의 "삭제"는 뒤에 남은
     * 상세 화면의 "일정 삭제"에도 걸린다(2026-08-21 에이닷 실측).
     */
    fun clickExactLabel(
        device: UiDevice,
        label: String,
        timeoutMs: Long,
    ): Boolean {
        repeat(CLICK_ATTEMPTS) {
            val half = (timeoutMs / 2).coerceAtLeast(1L)
            val node = device.wait(Until.findObject(By.text(label)), half)
                ?: device.wait(Until.findObject(By.desc(label)), half)
                ?: return false
            if (runCatching { node.click() }.isSuccess) return true
        }
        return false
    }

    fun containsPattern(fragment: String): Pattern = Pattern.compile(
        ".*" + Pattern.quote(fragment) + ".*",
        Pattern.CASE_INSENSITIVE or Pattern.DOTALL,
    )

    /** stale 재시도 횟수. 다시 그려지는 중이면 한 번 더 찾으면 대개 잡힌다. */
    private const val CLICK_ATTEMPTS = 3

    private const val LAUNCH_TIMEOUT_MS = 10_000L
    private const val IDLE_TIMEOUT_MS = 1_500L
}
