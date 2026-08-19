package com.davi.helper.e2e

import androidx.test.uiautomator.UiDevice

/**
 * 에이닷 캘린더에서 8월 20일에 테스트 일정을 추가하고 저장까지 하는 실제 Gemini 시나리오.
 *
 * 다섯 신규 시나리오 중 유일하게 목표 동작을 끝까지 실행한다 — 일정 저장은 계정 안에서
 * 되돌릴 수 있는 조작이라 pre-단계에서 멈출 이유가 없다. 대신 테스트 제목의 일정만 만들고
 * 정리에서 지운다. 기기 밖 부작용 컨트롤이 없어 시나리오 전용 하드 경계도 없다(설정 앱은
 * runner 공통 경계가 막는다).
 */
internal class AdotCalendarEventScenario : GuidedScenario<AdotCalendarEventScenario.State> {
    data class State(
        val launcherPackage: String,
        var eventSaved: Boolean = false,
    )

    override val id: String = "adot-calendar-add-event-full-journey"
    override val start: GuidedScenarioStart = GuidedScenarioStart.DAVI_TO_ANDROID_HOME
    override val maxSteps: Int = 32

    /** 개인 일정이 화면 label로 서버에 간다. 상세 trace를 강제로 끈다(#134). */
    override val handlesPersonalData: Boolean = true

    private lateinit var robot: AdotCalendarRobot

    override fun prepare(device: UiDevice): State {
        robot = AdotCalendarRobot(device)
        robot.forceStop()
        val launcherPackage = device.launcherPackageName
            ?: throw GuidedScenarioFailure("launcher_package_missing")
        return State(launcherPackage)
    }

    override fun goal(state: State): String =
        "에이닷 앱의 캘린더에서 8월 20일에 제목이 ${AdotCalendarRobot.TEST_TITLE}인 일정을 " +
            "추가하고 저장해 주세요."

    override fun assessBeforeExecute(
        device: UiDevice,
        state: State,
        step: Int,
        decision: DisplayedDecision,
    ): GuidedDecisionAssessment {
        if (decision.screenPackage == state.launcherPackage) {
            return GuidedLauncherPolicy.assess(decision, LAUNCHER_APP_FRAGMENTS)
        }
        if (decision.screenPackage != AdotCalendarRobot.PACKAGE) {
            return GuidedDecisionAssessment(deviationCodes = listOf("unexpected_package"))
        }
        return GuidedDecisionAssessment()
    }

    override fun textFor(
        state: State,
        step: Int,
        decision: DisplayedDecision,
    ): String? {
        if (decision.action.actionType != GuidedActionType.TYPE) return null
        return if (decision.screenPackage == state.launcherPackage) {
            LAUNCHER_SEARCH_TEXT
        } else {
            AdotCalendarRobot.TEST_TITLE
        }
    }

    override fun isGoalSatisfied(device: UiDevice, state: State): Boolean {
        val visible = robot.isTestEventVisible()
        if (visible) state.eventSaved = true
        return visible
    }

    override fun cleanup(device: UiDevice, state: State?) {
        val cleanupRobot = if (::robot.isInitialized) robot else AdotCalendarRobot(device)
        try {
            val deleted = cleanupRobot.deleteVisibleTestEvent()
            // 저장을 관측했는데 지금 화면에서 일정을 못 찾으면, 지워지지 않은 채 남는다.
            if (!deleted && state?.eventSaved == true) {
                throw GuidedScenarioFailure("event_cleanup_unverified")
            }
        } finally {
            cleanupRobot.forceStop()
        }
    }

    private companion object {
        const val LAUNCHER_SEARCH_TEXT = "에이닷"
        val LAUNCHER_APP_FRAGMENTS = setOf("에이닷", "adot", "a.")
    }
}
