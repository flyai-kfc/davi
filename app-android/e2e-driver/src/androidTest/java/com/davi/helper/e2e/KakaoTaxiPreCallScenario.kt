package com.davi.helper.e2e

import androidx.test.uiautomator.UiDevice

/**
 * 카카오 T에서 택시 목적지를 SK텔레콤 보라매사옥으로 잡고 **호출 직전 상태**까지 가는 실제
 * Gemini 시나리오.
 *
 * 호출·예약은 성공 상태에서도 절대 실행하지 않는 하드 경계다 — 배차는 기사에게 실시간으로
 * 나가는 기기 밖 부작용이라 label(부분 문자열)과 **누를 좌표**를 함께 본다(#134). 목적지를
 * 입력한 뒤부터는 호출 컨트롤이 식별돼야 진행한다(fail-closed, #211).
 */
internal class KakaoTaxiPreCallScenario : GuidedScenario<KakaoTaxiPreCallScenario.State> {
    data class State(
        val launcherPackage: String,
        var destinationEntered: Boolean = false,
    )

    override val id: String = "kakaot-taxi-precall-full-journey"
    override val start: GuidedScenarioStart = GuidedScenarioStart.DAVI_TO_ANDROID_HOME
    override val maxSteps: Int = 30

    /** 현재 위치·출발지 주소가 화면 label로 서버에 간다. 상세 trace를 강제로 끈다(#134). */
    override val handlesPersonalData: Boolean = true

    private lateinit var robot: KakaoTaxiRobot
    private var boundaryState: State? = null

    override val hardBoundaries: List<GuidedHardBoundary>
        get() = listOf(
            GuardedControlGuard.tapBoundary(
                codePrefix = "taxi_call",
                packages = setOf(KakaoTaxiRobot.PACKAGE),
                labelFragments = KakaoTaxiRobot.CALL_FRAGMENTS,
                // 목적지를 입력한 뒤부터는 호출 컨트롤이 식별돼야 한다.
                controlRequired = { boundaryState?.destinationEntered == true },
            ),
        )

    override fun prepare(device: UiDevice): State {
        robot = KakaoTaxiRobot(device)
        robot.forceStop()
        // 로그인 여부는 **시작 전에** 본다. 안내 도중 판정하면 모델이 로그인 화면에서 done을
        // 선언하는 경로(assessBeforeExecute를 거치지 않는다)를 놓쳐, 사전 조건 미충족이
        // done_before_goal이나 호출 컨트롤 미식별 같은 다른 코드로 둔갑한다(2026-08-21 실측).
        GuidedAppOps.launch(device, KakaoTaxiRobot.PACKAGE)
        val loggedOut = robot.isLoginScreen()
        robot.forceStop()
        if (loggedOut) throw GuidedScenarioFailure("taxi_login_required")
        val launcherPackage = device.launcherPackageName
            ?: throw GuidedScenarioFailure("launcher_package_missing")
        return State(launcherPackage).also { boundaryState = it }
    }

    override fun goal(state: State): String =
        "카카오 T 앱에서 택시 도착지를 SK텔레콤 보라매사옥으로 정해 주세요. " +
            "실제로 택시를 호출하지는 말고, 호출 버튼이 보이는 직전 화면에서 완료해 주세요."

    override fun assessBeforeExecute(
        device: UiDevice,
        state: State,
        step: Int,
        decision: DisplayedDecision,
    ): GuidedDecisionAssessment {
        if (decision.screenPackage == state.launcherPackage) {
            return GuidedLauncherPolicy.assess(decision, LAUNCHER_APP_FRAGMENTS)
        }
        if (decision.screenPackage != KakaoTaxiRobot.PACKAGE) {
            return GuidedDecisionAssessment(deviationCodes = listOf("unexpected_package"))
        }
        if (robot.isLoginScreen()) {
            // 로그인은 E2E가 대신 할 수 없는 사전 조건이다. 여기서 끝내지 않으면 로그인 화면 입력이
            // 도착지 입력으로 오인돼 호출 컨트롤을 요구하고, fail-closed가 selector 결함처럼 보인다.
            throw GuidedScenarioFailure("taxi_login_required", step)
        }
        if (decision.action.actionType == GuidedActionType.TYPE &&
            robot.isDestinationInputVisible()
        ) {
            state.destinationEntered = true
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
            DESTINATION_SEARCH_TEXT
        }
    }

    override fun isGoalSatisfied(device: UiDevice, state: State): Boolean =
        state.destinationEntered && robot.isPreCallReady()

    override fun cleanup(device: UiDevice, state: State?) {
        // 호출 전이라 만든 데이터가 없다. 앱을 내려 호출 준비 상태만 버린다.
        val cleanupRobot = if (::robot.isInitialized) robot else KakaoTaxiRobot(device)
        cleanupRobot.forceStop()
        boundaryState = null
    }

    private companion object {
        const val LAUNCHER_SEARCH_TEXT = "카카오 T"
        const val DESTINATION_SEARCH_TEXT = "SK텔레콤 보라매사옥"
        val LAUNCHER_APP_FRAGMENTS = setOf("카카오 t", "카카오t", "kakao t")
    }
}
