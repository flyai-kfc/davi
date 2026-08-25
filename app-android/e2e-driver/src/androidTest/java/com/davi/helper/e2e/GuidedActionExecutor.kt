package com.davi.helper.e2e

import android.graphics.Rect
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlin.math.max
import kotlin.math.min

/** Gemini가 선택한 bounds를 사용자 손동작으로 옮긴다. 앱별 정답 selector는 사용하지 않는다. */
internal class GuidedActionExecutor(private val device: UiDevice) {
    fun execute(plan: GuidedActionPlan, step: Int) {
        when (plan) {
            is GuidedActionPlan.Tap -> tap(plan.bounds, step)
            is GuidedActionPlan.Scroll -> scroll(plan)
            is GuidedActionPlan.Type -> type(plan, step)
            is GuidedActionPlan.SwipeWholeScreen -> swipeWholeScreen(plan.fingerDirection)
            GuidedActionPlan.OpenQuickSettings -> {
                if (!device.openQuickSettings()) {
                    throw GuidedScenarioFailure("quick_settings_failed", step)
                }
            }
        }
        device.waitForIdle(IDLE_TIMEOUT_MS)
    }

    private fun tap(bounds: GuidedBounds, step: Int) {
        if (!device.click(bounds.centerX(), bounds.centerY())) {
            throw GuidedScenarioFailure("tap_injection_failed", step)
        }
    }

    private fun type(
        plan: GuidedActionPlan.Type,
        step: Int,
    ) {
        tap(plan.bounds, step)
        val focused = device.wait(Until.findObject(By.focused(true)), FOCUS_TIMEOUT_MS)
            ?: throw GuidedScenarioFailure("type_focus_missing", step)
        focused.text = plan.value
        // 입력 원문은 화면 시그니처에서 빠진다. 사용자가 입력을 마치고 키보드를 닫는 것까지 TYPE
        // 한 동작의 commit으로 재현해야 IME/focus 상태 변화 뒤 다음 안내를 받을 수 있다.
        if (isImeVisible()) device.pressBack()
    }

    private fun scroll(plan: GuidedActionPlan.Scroll) {
        if (plan.bounds == null) {
            // 서버 계약은 대상 없는 scroll을 전역 제스처로 허용한다. 실제 사용자도 빈 화면 영역에서
            // 쓸 수 있으므로, 앱별 시나리오가 허용한 경우에만 전체 화면으로 재현한다.
            swipeWholeScreen(plan.fingerDirection)
        } else {
            swipe(plan.bounds, plan.fingerDirection)
        }
    }

    private fun swipeWholeScreen(direction: GuidedSwipeDirection) {
        val marginX = max(24, device.displayWidth / 8)
        val marginY = max(24, device.displayHeight / 6)
        swipe(
            GuidedBounds(
                marginX,
                marginY,
                device.displayWidth - marginX,
                device.displayHeight - marginY,
            ),
            direction,
        )
    }

    private fun swipe(rawBounds: GuidedBounds, direction: GuidedSwipeDirection) {
        val bounds = Rect(rawBounds.left, rawBounds.top, rawBounds.right, rawBounds.bottom).apply {
            intersect(0, 0, device.displayWidth, device.displayHeight)
        }
        val horizontalInset = min(max(12, bounds.width() / 6), max(12, bounds.width() / 2 - 1))
        val verticalInset = min(max(12, bounds.height() / 6), max(12, bounds.height() / 2 - 1))
        val left = bounds.left + horizontalInset
        val right = bounds.right - horizontalInset
        val top = bounds.top + verticalInset
        val bottom = bounds.bottom - verticalInset
        val centerX = bounds.centerX()
        val centerY = bounds.centerY()

        val points = when (direction) {
            GuidedSwipeDirection.UP -> intArrayOf(centerX, bottom, centerX, top)
            GuidedSwipeDirection.DOWN -> intArrayOf(centerX, top, centerX, bottom)
            GuidedSwipeDirection.LEFT -> intArrayOf(right, centerY, left, centerY)
            GuidedSwipeDirection.RIGHT -> intArrayOf(left, centerY, right, centerY)
        }
        device.swipe(points[0], points[1], points[2], points[3], SWIPE_STEPS)
    }

    private fun GuidedBounds.centerX(): Int = (left + right) / 2

    private fun GuidedBounds.centerY(): Int = (top + bottom) / 2

    private fun isImeVisible(): Boolean = runCatching {
        InstrumentationRegistry.getInstrumentation().uiAutomation.windows
            .any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
    }.getOrDefault(false)

    private companion object {
        const val SWIPE_STEPS = 24
        const val IDLE_TIMEOUT_MS = 1_500L
        const val FOCUS_TIMEOUT_MS = 3_000L
    }
}
