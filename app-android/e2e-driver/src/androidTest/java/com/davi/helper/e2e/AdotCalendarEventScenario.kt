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
        /**
         * 저장이 **일어날 수 있었는가**. 실행 전에 기록한다.
         *
         * 저장 관측(`eventSaved`)은 모델이 DONE을 냈을 때만 갱신되므로, timeout·step_limit으로
         * 끝나면 저장해 놓고도 거짓으로 남는다. 그 값을 정리의 판정 기준으로 쓰면 계정에 일정을
         * 남긴 채 성공으로 끝난다(2026-08-21 리뷰). 제목이 입력된 순간부터는 저장 가능성이 있다.
         */
        var eventSaveAttempted: Boolean = false,
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
        // 앞선 실행이 남긴 같은 제목의 일정을 먼저 걷어낸다. 남아 있으면 이번 실행의 목표
        // 판정이 시작부터 참이 되고, 정리도 무엇을 지웠는지 알 수 없다.
        robot.purgeTestEvents()
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
        // 앱 안의 TYPE에는 테스트 제목이 들어간다([textFor]). 그 순간부터 저장이 가능하므로,
        // 실행 **전에** 기록해 정리가 이 실행을 자기 책임으로 보게 한다.
        if (decision.action.actionType == GuidedActionType.TYPE) {
            state.eventSaveAttempted = true
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
            // 정리 시점의 화면은 안내가 끝난 자리라 캘린더가 아닐 수 있다. 앱을 새로 띄워 찾는다.
            val deleted = cleanupRobot.purgeTestEvents()
            // 저장이 가능했던 실행에서 일정을 못 찾았다면, 정말 없는 것인지 캘린더를 못 본 것인지
            // 구분할 수 없다(캘린더 화면의 양성 표식이 아직 실측되지 않았다). 계정에 남기느니
            // 확인 불가로 끝낸다.
            if (!deleted && (state?.eventSaveAttempted == true || state?.eventSaved == true)) {
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
