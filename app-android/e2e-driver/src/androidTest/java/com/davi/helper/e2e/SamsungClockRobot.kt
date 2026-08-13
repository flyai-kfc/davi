package com.davi.helper.e2e

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

/** 삼성 시계의 사전 조건·독립 검증·정리만 담당한다. 안내 본 흐름에는 사용하지 않는다. */
internal class SamsungClockRobot(private val device: UiDevice) {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    data class PickerTime(
        val period: String,
        val hour: Int,
        val minute: Int,
    )

    enum class ScreenKind { ALARM_LIST, ALARM_EDITOR, UNKNOWN }

    enum class TargetKind { ADD, PERIOD, HOUR, MINUTE, NAME, SAVE, OTHER }

    fun screenKind(): ScreenKind = when {
        device.hasObject(By.res(PACKAGE, ID_SAVE)) && device.hasObject(By.res(PACKAGE, ID_NAME)) ->
            ScreenKind.ALARM_EDITOR
        device.hasObject(By.res(PACKAGE, ID_ADD)) -> ScreenKind.ALARM_LIST
        else -> ScreenKind.UNKNOWN
    }

    fun hasTestAlarm(): Boolean = device.hasObject(By.text(TEST_LABEL))

    /** Gemini target bounds 중심에 있는 삼성 시계 노드를 찾아 안전 허용 종류로만 축약한다. */
    fun targetKind(boundsValues: IntArray?): TargetKind {
        if (boundsValues == null || boundsValues.size != 4) return TargetKind.OTHER
        val target = Rect(boundsValues[0], boundsValues[1], boundsValues[2], boundsValues[3])
        val centerX = target.centerX()
        val centerY = target.centerY()
        val candidates = device.findObjects(By.pkg(PACKAGE))
            .filter { it.visibleBounds.contains(centerX, centerY) }
            .sortedBy { it.visibleBounds.width().toLong() * it.visibleBounds.height().toLong() }
        candidates.forEach { node ->
            when (node.resourceName) {
                "$PACKAGE:id/$ID_ADD" -> return TargetKind.ADD
                "$PACKAGE:id/$ID_NAME" -> return TargetKind.NAME
                "$PACKAGE:id/$ID_SAVE" -> return TargetKind.SAVE
                "$PACKAGE:id/$ID_PICKER_INPUT" -> {
                    val value = node.text.orEmpty()
                    return when {
                        value == TEXT_AM || value == TEXT_PM -> TargetKind.PERIOD
                        HOUR.containsMatchIn(value) -> TargetKind.HOUR
                        MINUTE.containsMatchIn(value) -> TargetKind.MINUTE
                        else -> TargetKind.OTHER
                    }
                }
            }
        }
        return TargetKind.OTHER
    }

    fun openAlarmList() {
        // Android 11+ package visibility 조회에 기대지 않는다. 이 기기 시나리오에서 검증한 launcher
        // component를 명시해 제품 manifest에 불필요한 <queries>를 추가하지 않는다.
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(PACKAGE, "$PACKAGE.ClockPackage")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        context.startActivity(intent)
        if (!device.wait(Until.hasObject(By.pkg(PACKAGE).depth(0)), WINDOW_TIMEOUT_MS)) {
            throw GuidedScenarioFailure("clock_launch_failed")
        }

        // 실패가 편집/선택 화면에서 났어도 테스트 데이터만 정리할 수 있게 목록으로 복귀한다.
        device.findObject(By.res(PACKAGE, ID_CANCEL))?.let {
            it.click()
            device.waitForIdle(IDLE_TIMEOUT_MS)
        }
        device.findObject(By.res(PACKAGE, ID_SELECTION_CANCEL))?.let {
            it.click()
            device.waitForIdle(IDLE_TIMEOUT_MS)
        }

        val alarmTab = device.findObjects(By.res(PACKAGE, ID_TAB_TITLE))
            .firstOrNull { it.text == TEXT_ALARM_TAB }
            ?: throw GuidedScenarioFailure("alarm_tab_missing")
        if (!alarmTab.isSelected) {
            alarmTab.click()
            device.waitForIdle(IDLE_TIMEOUT_MS)
        }
        if (!device.wait(Until.hasObject(By.res(PACKAGE, ID_ADD)), WINDOW_TIMEOUT_MS)) {
            throw GuidedScenarioFailure("alarm_list_missing")
        }
    }

    fun readDefaultPickerTime(): PickerTime {
        val add = device.wait(Until.findObject(By.res(PACKAGE, ID_ADD)), WINDOW_TIMEOUT_MS)
            ?: throw GuidedScenarioFailure("alarm_add_missing")
        add.click()
        if (!device.wait(Until.hasObject(By.res(PACKAGE, ID_SAVE)), WINDOW_TIMEOUT_MS)) {
            throw GuidedScenarioFailure("alarm_editor_missing")
        }

        val pickerTexts = device.findObjects(By.res(PACKAGE, ID_PICKER_INPUT)).mapNotNull { it.text }
        val period = pickerTexts.firstOrNull { it == TEXT_AM || it == TEXT_PM }
            ?: throw GuidedScenarioFailure("alarm_period_missing")
        val hour = pickerTexts.firstNotNullOfOrNull { HOUR.find(it)?.groupValues?.get(1)?.toIntOrNull() }
            ?: throw GuidedScenarioFailure("alarm_hour_missing")
        val minute = pickerTexts.firstNotNullOfOrNull { MINUTE.find(it)?.groupValues?.get(1)?.toIntOrNull() }
            ?: throw GuidedScenarioFailure("alarm_minute_missing")

        device.findObject(By.res(PACKAGE, ID_CANCEL))?.click()
            ?: throw GuidedScenarioFailure("alarm_cancel_missing")
        device.wait(Until.hasObject(By.res(PACKAGE, ID_ADD)), WINDOW_TIMEOUT_MS)
        return PickerTime(period, hour, minute)
    }

    fun alarmMatches(label: String, time: PickerTime): Boolean {
        openAlarmList()
        val labelNode = findAlarmLabel(label) ?: return false
        val card = ancestorWithResource(labelNode, ID_CARD) ?: return false
        val shownTime = card.findObject(By.res(PACKAGE, ID_ITEM_TIME))?.text ?: return false
        val shownPeriod = card.findObject(By.res(PACKAGE, ID_ITEM_PERIOD))?.text ?: return false
        return shownPeriod == time.period && normalizeTime(shownTime) == "${time.hour}:${time.minute}"
    }

    fun deleteAlarmNamed(label: String) {
        openAlarmList()
        val labelNode = findAlarmLabel(label) ?: return
        val card = ancestorWithResource(labelNode, ID_CARD)
            ?: throw GuidedScenarioFailure("cleanup_card_missing")
        card.longClick()
        val delete = device.wait(Until.findObject(By.res(PACKAGE, ID_DELETE)), WINDOW_TIMEOUT_MS)
            ?: throw GuidedScenarioFailure("cleanup_delete_missing")
        delete.click()
        if (!device.wait(Until.gone(By.text(label)), WINDOW_TIMEOUT_MS)) {
            throw GuidedScenarioFailure("cleanup_delete_failed")
        }
    }

    private fun findAlarmLabel(label: String): UiObject2? {
        device.findObject(By.text(label))?.let { return it }
        val list = device.findObject(By.res(PACKAGE, ID_LIST)) ?: return null
        val initialBounds = list.visibleBounds
        repeat(MAX_LIST_SWIPES) {
            device.swipe(
                initialBounds.centerX(),
                initialBounds.top + 40,
                initialBounds.centerX(),
                initialBounds.bottom - 40,
                20,
            )
        }
        repeat(MAX_LIST_SWIPES) {
            device.findObject(By.text(label))?.let { return it }
            val bounds = list.visibleBounds
            device.swipe(bounds.centerX(), bounds.bottom - 40, bounds.centerX(), bounds.top + 40, 20)
            device.waitForIdle(500)
        }
        return device.findObject(By.text(label))
    }

    private fun ancestorWithResource(node: UiObject2, resourceName: String): UiObject2? {
        var current: UiObject2? = node
        repeat(MAX_ANCESTORS) {
            val candidate = current ?: return null
            if (candidate.resourceName == "$PACKAGE:id/$resourceName") return candidate
            current = candidate.parent
        }
        return null
    }

    private fun normalizeTime(value: String): String = value
        .replace(" ", "")
        .split(Regex("[^0-9:]")).firstOrNull { it.contains(':') }
        .orEmpty()
        .split(':')
        .mapNotNull { it.toIntOrNull() }
        .takeIf { it.size == 2 }
        ?.let { "${it[0]}:${it[1]}" }
        .orEmpty()

    companion object {
        const val PACKAGE = "com.sec.android.app.clockpackage"
        const val TEST_LABEL = "DAVI_E2E_TEST"

        private const val ID_TAB_TITLE = "title"
        private const val ID_ADD = "menu_alarm_add"
        private const val ID_SAVE = "menu_done"
        private const val ID_NAME = "alarm_name"
        private const val ID_CANCEL = "menu_cancel"
        private const val ID_SELECTION_CANCEL = "selection_cancel"
        private const val ID_PICKER_INPUT = "numberpicker_input"
        private const val ID_LIST = "alarm_list"
        private const val ID_CARD = "alarm_list_cardView"
        private const val ID_ITEM_TIME = "alarm_item_time"
        private const val ID_ITEM_PERIOD = "alarm_item_ampm"
        private const val ID_DELETE = "delete"
        private const val TEXT_ALARM_TAB = "알람"
        private const val TEXT_AM = "오전"
        private const val TEXT_PM = "오후"
        private val HOUR = Regex("(\\d{1,2}),\\s*시간")
        private val MINUTE = Regex("(\\d{1,2}),\\s*분")
        private const val MAX_LIST_SWIPES = 10
        private const val MAX_ANCESTORS = 8
        private const val WINDOW_TIMEOUT_MS = 5_000L
        private const val IDLE_TIMEOUT_MS = 1_500L
    }
}
