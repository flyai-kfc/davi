package com.davi.helper.e2e

import androidx.test.uiautomator.UiDevice

/**
 * 배달의민족에서 김밥 메뉴 하나를 장바구니에 담아 **주문 직전 상태**까지 가는 실제 Gemini
 * 시나리오.
 *
 * 주문·결제는 성공 상태에서도 절대 실행하지 않는 하드 경계다 — 실제 가게로 주문이 나가는 기기
 * 밖 부작용이라 label(부분 문자열)과 **누를 좌표**를 함께 본다(#134). 담기를 시도한 뒤부터는
 * 주문 컨트롤이 식별돼야 진행한다(fail-closed, #211). 장바구니는 계정에 남으므로 정리에서
 * 비운다. 다만 기존 장바구니 항목을 지우지 않도록 실행 전에 빈 장바구니를 사전 조건으로
 * 확인한다.
 */
internal class BaeminPreOrderScenario : GuidedScenario<BaeminPreOrderScenario.State> {
    data class State(
        val launcherPackage: String,
        var addToCartAttempted: Boolean = false,
    )

    override val id: String = "baemin-gimbap-preorder-full-journey"
    override val start: GuidedScenarioStart = GuidedScenarioStart.DAVI_TO_ANDROID_HOME
    override val maxSteps: Int = 32

    /** 배달 주소·가게 위치가 화면 label로 서버에 간다. 상세 trace를 강제로 끈다(#134). */
    override val handlesPersonalData: Boolean = true

    private lateinit var robot: BaeminRobot
    private var boundaryState: State? = null

    override val hardBoundaries: List<GuidedHardBoundary>
        get() = listOf(
            GuardedControlGuard.tapBoundary(
                codePrefix = "baemin_order",
                packages = setOf(BaeminRobot.PACKAGE),
                labelFragments = BaeminRobot.ORDER_FRAGMENTS,
                // 담기를 시도한 뒤부터는 주문 컨트롤이 식별돼야 한다.
                controlRequired = { boundaryState?.addToCartAttempted == true },
            ),
        )

    override fun prepare(device: UiDevice): State {
        robot = BaeminRobot(device)
        try {
            robot.requireEmptyCartBeforeRun()
        } finally {
            // 사전 조건 확인을 위해 연 배민을 닫아 매 실행을 같은 시작 상태로 맞춘다.
            robot.forceStop()
        }
        val launcherPackage = device.launcherPackageName
            ?: throw GuidedScenarioFailure("launcher_package_missing")
        return State(launcherPackage).also { boundaryState = it }
    }

    override fun goal(state: State): String =
        "배달의민족 앱에서 김밥을 검색해 김밥 가게를 하나 열고, 김밥 메뉴 하나를 장바구니에 " +
            "담아 주세요. 실제로 주문하거나 결제하지는 말고, 주문하기 버튼을 누르기 직전 " +
            "상태에서 완료해 주세요."

    override fun assessBeforeExecute(
        device: UiDevice,
        state: State,
        step: Int,
        decision: DisplayedDecision,
    ): GuidedDecisionAssessment {
        if (decision.screenPackage == state.launcherPackage) {
            return GuidedLauncherPolicy.assess(decision, LAUNCHER_APP_FRAGMENTS)
        }
        if (decision.screenPackage != BaeminRobot.PACKAGE) {
            return GuidedDecisionAssessment(deviationCodes = listOf("unexpected_package"))
        }
        if (decision.action.actionType == GuidedActionType.TAP &&
            GuardedControlSafety.containsBlockedLabel(decision.requestTargetLabel(), ADD_FRAGMENTS)
        ) {
            // 실행 뒤에도 장바구니를 원상 복구할 수 있게, 담기 시도를 실행 전에 기록한다.
            state.addToCartAttempted = true
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
            MENU_SEARCH_TEXT
        }
    }

    override fun isGoalSatisfied(device: UiDevice, state: State): Boolean =
        state.addToCartAttempted && robot.isPreOrderReady()

    override fun cleanup(device: UiDevice, state: State?) {
        val cleanupRobot = if (::robot.isInitialized) robot else BaeminRobot(device)
        try {
            if (state?.addToCartAttempted == true) cleanupRobot.clearCart()
        } finally {
            cleanupRobot.forceStop()
            boundaryState = null
        }
    }

    private companion object {
        const val LAUNCHER_SEARCH_TEXT = "배달의민족"
        const val MENU_SEARCH_TEXT = "김밥"
        val LAUNCHER_APP_FRAGMENTS = setOf("배달의민족", "배달의 민족", "배민", "baemin")

        /** 장바구니 담기로 보는 대상 label 조각. */
        val ADD_FRAGMENTS = setOf("담기")
    }
}
