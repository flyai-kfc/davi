package com.davi.helper.e2e

import androidx.test.uiautomator.UiDevice

/**
 * T월드 앱에서 이번 달 요금안내서를 열어 보는 실제 Gemini 시나리오.
 *
 * 열람은 읽기 전용이지만 같은 앱에 요금 납부가 있다 — 납부·결제 컨트롤은 하드 경계로 막는다.
 * 납부 컨트롤은 요금안내서 화면에 항상 있는 것이 아니라서 단계 요구는 두지 않는다
 * (`controlRequired=false` — 식별된 경우에만 label·좌표로 차단).
 */
internal class TworldBillScenario : GuidedScenario<TworldBillScenario.State> {
    data class State(val launcherPackage: String)

    override val id: String = "tworld-bill-view-full-journey"
    override val start: GuidedScenarioStart = GuidedScenarioStart.DAVI_TO_ANDROID_HOME
    override val maxSteps: Int = 26

    /** 회선 번호·이름·청구 금액이 화면 label로 서버에 간다. 상세 trace를 강제로 끈다(#134). */
    override val handlesPersonalData: Boolean = true

    private lateinit var robot: TworldRobot

    override val hardBoundaries: List<GuidedHardBoundary>
        get() = listOf(
            GuardedControlGuard.tapBoundary(
                codePrefix = "tworld_payment",
                packages = setOf(TworldRobot.PACKAGE),
                labelFragments = TworldRobot.PAYMENT_FRAGMENTS,
                controlRequired = { false },
            ),
        )

    override fun prepare(device: UiDevice): State {
        robot = TworldRobot(device)
        robot.forceStop()
        val launcherPackage = device.launcherPackageName
            ?: throw GuidedScenarioFailure("launcher_package_missing")
        return State(launcherPackage)
    }

    override fun goal(state: State): String =
        "T월드 앱에서 이번 달 요금안내서를 열어 청구 요금이 보이는 화면까지 가 주세요."

    override fun assessBeforeExecute(
        device: UiDevice,
        state: State,
        step: Int,
        decision: DisplayedDecision,
    ): GuidedDecisionAssessment {
        if (decision.screenPackage == state.launcherPackage) {
            return GuidedLauncherPolicy.assess(decision, LAUNCHER_APP_FRAGMENTS)
        }
        if (decision.screenPackage != TworldRobot.PACKAGE) {
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
            APP_SEARCH_TEXT
        }
    }

    override fun isGoalSatisfied(device: UiDevice, state: State): Boolean = robot.isBillVisible()

    override fun cleanup(device: UiDevice, state: State?) {
        // 읽기 전용 목표라 만든 데이터가 없다. 앱만 내린다.
        val cleanupRobot = if (::robot.isInitialized) robot else TworldRobot(device)
        cleanupRobot.forceStop()
    }

    private companion object {
        const val LAUNCHER_SEARCH_TEXT = "T월드"
        const val APP_SEARCH_TEXT = "요금안내서"
        val LAUNCHER_APP_FRAGMENTS = setOf("t월드", "티월드", "tworld", "t 월드")
    }
}
