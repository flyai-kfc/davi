package com.davi.helper.e2e

import androidx.test.uiautomator.UiDevice

/** SM-S921N의 한국어 삼성 시계에서 테스트 전용 알람 하나를 만드는 실제 Gemini 시나리오. */
internal class SamsungClockAlarmScenario : GuidedScenario<SamsungClockAlarmScenario.State> {
    data class State(
        val target: SamsungClockRobot.PickerTime,
    )

    override val id: String = "samsung-clock-create-alarm"
    override val maxSteps: Int = 18

    private lateinit var robot: SamsungClockRobot

    override fun prepare(device: UiDevice): State {
        robot = SamsungClockRobot(device)
        robot.deleteAlarmNamed(SamsungClockRobot.TEST_LABEL)
        val initial = robot.readDefaultPickerTime()
        val targetMinute = if (initial.minute <= 54) initial.minute + 5 else initial.minute - 5
        return State(initial.copy(minute = targetMinute))
    }

    override fun goal(state: State): String =
        "삼성 시계에서 ${state.target.period} ${state.target.hour}시 " +
            "${state.target.minute.toString().padStart(2, '0')}분에 " +
            "이름이 ${SamsungClockRobot.TEST_LABEL}인 알람을 만들고 저장해 주세요."

    override fun validateBeforeExecute(
        device: UiDevice,
        state: State,
        step: Int,
        decision: DisplayedDecision,
    ) {
        if (decision.screenPackage != SamsungClockRobot.PACKAGE) {
            throw GuidedScenarioFailure("unexpected_package", step)
        }
        if (robot.hasSavedTestAlarm()) throw GuidedScenarioFailure("action_after_goal", step)

        val targetKind = robot.targetKind(decision.targetBounds)
        when (robot.screenKind()) {
            SamsungClockRobot.ScreenKind.ALARM_LIST -> {
                if (decision.action.actionType != GuidedActionType.TAP ||
                    targetKind != SamsungClockRobot.TargetKind.ADD
                ) {
                    // 기존 개인 알람을 Gemini가 선택해도 테스트가 대신 수정하지 않는다.
                    throw GuidedScenarioFailure("unsafe_alarm_list_target", step)
                }
            }

            SamsungClockRobot.ScreenKind.ALARM_EDITOR -> {
                val timeTarget = targetKind in TIME_TARGETS
                val safe = when (decision.action.actionType) {
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
                if (!safe) throw GuidedScenarioFailure("unsafe_alarm_editor_target", step)
                if (targetKind == SamsungClockRobot.TargetKind.SAVE && !robot.hasEnteredTestLabel()) {
                    // 시도 플래그가 아니라 현재 편집창의 실제 값을 읽어 이름 없는 알람 저장을 차단한다.
                    throw GuidedScenarioFailure("save_before_test_label", step)
                }
            }

            SamsungClockRobot.ScreenKind.UNKNOWN ->
                throw GuidedScenarioFailure("unknown_clock_screen", step)
        }
    }

    override fun textFor(
        state: State,
        step: Int,
        decision: DisplayedDecision,
    ): String? {
        if (decision.action.actionType != GuidedActionType.TYPE) return null
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
        val TIME_TARGETS = setOf(
            SamsungClockRobot.TargetKind.PERIOD,
            SamsungClockRobot.TargetKind.HOUR,
            SamsungClockRobot.TargetKind.MINUTE,
        )
    }
}
