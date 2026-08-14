package com.davi.helper.e2e

import androidx.test.uiautomator.UiDevice
import java.util.Locale

/** SM-S921N의 한국어 삼성 시계에서 테스트 전용 알람 하나를 만드는 실제 Gemini 시나리오. */
internal class SamsungClockAlarmScenario(
    override val start: GuidedScenarioStart = GuidedScenarioStart.PREPARED_SCREEN,
) : GuidedScenario<SamsungClockAlarmScenario.State> {
    data class State(
        val target: SamsungClockRobot.PickerTime,
        val launcherPackage: String,
    )

    override val id: String = when (start) {
        GuidedScenarioStart.PREPARED_SCREEN -> "samsung-clock-create-alarm-focused"
        GuidedScenarioStart.DAVI_TO_ANDROID_HOME -> "samsung-clock-create-alarm-full-journey"
    }
    override val maxSteps: Int = when (start) {
        GuidedScenarioStart.PREPARED_SCREEN -> 18
        GuidedScenarioStart.DAVI_TO_ANDROID_HOME -> 28
    }

    private lateinit var robot: SamsungClockRobot

    override fun prepare(device: UiDevice): State {
        robot = SamsungClockRobot(device)
        robot.deleteAlarmNamed(SamsungClockRobot.TEST_LABEL)
        val initial = robot.readDefaultPickerTime()
        val targetMinute = if (initial.minute <= 54) initial.minute + 5 else initial.minute - 5
        val launcherPackage = device.launcherPackageName
            ?: throw GuidedScenarioFailure("launcher_package_missing")
        return State(initial.copy(minute = targetMinute), launcherPackage)
    }

    override fun goal(state: State): String =
        "삼성 시계에서 ${state.target.period} ${state.target.hour}시 " +
            "${state.target.minute.toString().padStart(2, '0')}분에 " +
            "이름이 ${SamsungClockRobot.TEST_LABEL}인 알람을 만들고 저장해 주세요."

    override fun assessBeforeExecute(
        device: UiDevice,
        state: State,
        step: Int,
        decision: DisplayedDecision,
    ): GuidedDecisionAssessment {
        SamsungClockDecisionPolicy.settingsHardFailure(decision)?.let { return it }
        if (decision.screenPackage == state.launcherPackage) {
            return SamsungClockDecisionPolicy.assessLauncher(decision)
        }
        if (decision.screenPackage != SamsungClockRobot.PACKAGE) {
            return GuidedDecisionAssessment(deviationCodes = listOf("unexpected_package"))
        }

        val targetKind = robot.targetKind(decision.targetBounds)
        return SamsungClockDecisionPolicy.assessClock(
            screenKind = robot.screenKind(),
            targetKind = targetKind,
            actionType = decision.action.actionType,
            hasSavedTestAlarm = robot.hasSavedTestAlarm(),
            hasEnteredTestLabel = robot.hasEnteredTestLabel(),
        )
    }

    override fun textFor(
        state: State,
        step: Int,
        decision: DisplayedDecision,
    ): String? {
        if (decision.action.actionType != GuidedActionType.TYPE) return null
        if (decision.screenPackage == state.launcherPackage) {
            // 전용 기기에서는 검색창을 잘못 짚은 사실을 이탈로 남기고도 목표 앱 검색값을 실행한다.
            return CLOCK_SEARCH_TEXT
        }
        return when (robot.targetKind(decision.targetBounds)) {
            SamsungClockRobot.TargetKind.NAME -> SamsungClockRobot.TEST_LABEL
            SamsungClockRobot.TargetKind.HOUR -> state.target.hour.toString()
            SamsungClockRobot.TargetKind.MINUTE -> state.target.minute.toString().padStart(2, '0')
            else -> throw GuidedScenarioFailure("unknown_type_target", step)
        }
    }

    override fun isGoalSatisfied(device: UiDevice, state: State): Boolean =
        robot.alarmMatches(SamsungClockRobot.TEST_LABEL, state.target)

    override fun cleanup(device: UiDevice, state: State?) {
        val cleanupRobot = if (::robot.isInitialized) robot else SamsungClockRobot(device)
        cleanupRobot.deleteAlarmNamed(SamsungClockRobot.TEST_LABEL)
    }

    private companion object {
        const val CLOCK_SEARCH_TEXT = "시계"
    }
}

/** 전용 기기 알람 여정의 이탈은 관찰하고, 설정 앱 진입만 이 시나리오의 하드 경계로 둔다. */
internal object SamsungClockDecisionPolicy {
    fun assessLauncher(decision: DisplayedDecision): GuidedDecisionAssessment {
        settingsHardFailure(decision)?.let { return it }
        val label = decision.requestTargetLabel()
        val expected = when (decision.action.actionType) {
            GuidedActionType.OPEN_APP_DRAWER -> true
            GuidedActionType.SCROLL -> decision.action.direction != null &&
                (decision.targetBounds == null || decision.targetBounds.size == 4)
            GuidedActionType.TAP -> decision.targetClickable &&
                (isClockLabel(label) || isAppDrawerLabel(label) || isSearchLabel(label))
            GuidedActionType.TYPE -> decision.targetEditable && isSearchLabel(label)
            else -> false
        }
        return if (expected) GuidedDecisionAssessment() else {
            GuidedDecisionAssessment(deviationCodes = listOf("unexpected_launcher_target"))
        }
    }

    fun assessClock(
        screenKind: SamsungClockRobot.ScreenKind,
        targetKind: SamsungClockRobot.TargetKind,
        actionType: GuidedActionType,
        hasSavedTestAlarm: Boolean,
        hasEnteredTestLabel: Boolean,
    ): GuidedDecisionAssessment {
        val deviations = mutableListOf<String>()
        if (hasSavedTestAlarm) deviations += "action_after_goal"
        when (screenKind) {
            SamsungClockRobot.ScreenKind.ALARM_LIST -> {
                if (actionType != GuidedActionType.TAP || targetKind != SamsungClockRobot.TargetKind.ADD) {
                    deviations += "unexpected_alarm_list_target"
                }
            }

            SamsungClockRobot.ScreenKind.ALARM_EDITOR -> {
                val timeTarget = targetKind in TIME_TARGETS
                val expected = when (actionType) {
                    GuidedActionType.TAP -> timeTarget ||
                        targetKind == SamsungClockRobot.TargetKind.NAME ||
                        targetKind == SamsungClockRobot.TargetKind.SAVE
                    GuidedActionType.SCROLL -> timeTarget
                    GuidedActionType.TYPE -> targetKind in setOf(
                        SamsungClockRobot.TargetKind.HOUR,
                        SamsungClockRobot.TargetKind.MINUTE,
                        SamsungClockRobot.TargetKind.NAME,
                    )
                    else -> false
                }
                if (!expected) deviations += "unexpected_alarm_editor_target"
                if (targetKind == SamsungClockRobot.TargetKind.SAVE && !hasEnteredTestLabel) {
                    deviations += "save_before_test_label"
                }
            }

            SamsungClockRobot.ScreenKind.UNKNOWN -> deviations += "unknown_clock_screen"
        }
        return GuidedDecisionAssessment(deviationCodes = deviations)
    }

    fun isSettingsPackage(packageName: String): Boolean =
        packageName == ANDROID_SETTINGS_PACKAGE ||
            packageName.startsWith("$ANDROID_SETTINGS_PACKAGE.") ||
            packageName == SAMSUNG_SETTINGS_PACKAGE ||
            packageName.startsWith("$SAMSUNG_SETTINGS_PACKAGE.")

    /** 런처뿐 아니라 System UI 등 어느 표면에서도 설정 앱 진입 target은 실행 전에 닫는다. */
    fun settingsHardFailure(decision: DisplayedDecision): GuidedDecisionAssessment? =
        if (isSettingsPackage(decision.screenPackage) ||
            (decision.action.actionType == GuidedActionType.TAP && isSettingsLabel(decision.requestTargetLabel()))
        ) {
            GuidedDecisionAssessment(hardFailureCode = "settings_app_blocked")
        } else {
            null
        }

    private fun isClockLabel(label: String?): Boolean {
        val normalized = normalize(label)
        return normalized.contains("시계") || normalized.contains("clock")
    }

    private fun isAppDrawerLabel(label: String?): Boolean {
        val normalized = normalize(label)
        return normalized == "앱" || normalized.contains("앱스") ||
            normalized.contains("앱 서랍") || normalized.contains("apps")
    }

    private fun isSearchLabel(label: String?): Boolean {
        val normalized = normalize(label)
        return normalized.contains("검색") || normalized.contains("search")
    }

    private fun isSettingsLabel(label: String?): Boolean {
        val normalized = normalize(label)
        return normalized == "설정" || normalized == "settings"
    }

    private fun normalize(label: String?): String = label?.trim()?.lowercase(Locale.ROOT).orEmpty()

    private const val ANDROID_SETTINGS_PACKAGE = "com.android.settings"
    private const val SAMSUNG_SETTINGS_PACKAGE = "com.samsung.android.settings"
    private val TIME_TARGETS = setOf(
        SamsungClockRobot.TargetKind.PERIOD,
        SamsungClockRobot.TargetKind.HOUR,
        SamsungClockRobot.TargetKind.MINUTE,
    )
}
