package com.davi.helper.e2e

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** #209: 제품 tree/action 외 값으로 다음 행동을 보정하지 않는 공통 E2E 경계. */
class GuidedObservationBoundaryTest {
    @Test
    fun tapPlanUsesOnlyTheBoundsResolvedFromTheDisplayedDecision() {
        val plan = GuidedActionPlanner.plan(
            decision(bounds = intArrayOf(11, 22, 333, 444)),
            textInput = null,
            step = 3,
        )

        assertEquals(
            GuidedActionPlan.Tap(GuidedBounds(11, 22, 333, 444)),
            plan,
        )
    }

    @Test
    fun missingBoundsFailsInsteadOfLookingUpASelectorOrGuessingCoordinates() {
        val failure = assertThrows(GuidedScenarioFailure::class.java) {
            GuidedActionPlanner.plan(
                decision(bounds = null, targetResolved = false),
                textInput = null,
                step = 4,
            )
        }

        assertEquals("target_bounds_missing", failure.code)
        assertEquals(4, failure.step)
    }

    @Test
    fun scrollKeepsTheObservedBoundsAndOnlyConvertsContractDirectionToFingerMotion() {
        val plan = GuidedActionPlanner.plan(
            decision(
                actionType = GuidedActionType.SCROLL,
                direction = GuidedSwipeDirection.DOWN,
                bounds = intArrayOf(10, 20, 300, 600),
            ),
            textInput = null,
            step = 2,
        )

        assertEquals(
            GuidedActionPlan.Scroll(
                bounds = GuidedBounds(10, 20, 300, 600),
                fingerDirection = GuidedSwipeDirection.UP,
            ),
            plan,
        )
    }

    @Test
    fun unresolvedTargetIdIsReportedSeparatelyFromMalformedBounds() {
        val mismatch = assertThrows(GuidedScenarioFailure::class.java) {
            GuideInstructionQuality.validate(
                decision(bounds = null, targetResolved = false),
                step = 5,
            )
        }

        assertEquals("target_node_id_mismatch", mismatch.code)
    }

    @Test
    fun unresolvedScrollTargetIsNotCorrectedIntoAGlobalSwipe() {
        val mismatch = assertThrows(GuidedScenarioFailure::class.java) {
            GuideInstructionQuality.validate(
                decision(
                    actionType = GuidedActionType.SCROLL,
                    direction = GuidedSwipeDirection.DOWN,
                    bounds = null,
                    targetResolved = false,
                ),
                step = 6,
            )
        }

        assertEquals("target_node_id_mismatch", mismatch.code)
    }

    @Test
    fun genuinelyTargetlessScrollRemainsAnAllowedGlobalSwipe() {
        val decision = decision(
            actionType = GuidedActionType.SCROLL,
            direction = GuidedSwipeDirection.DOWN,
            bounds = null,
            targetResolved = false,
            targetNodeId = null,
        )

        GuideInstructionQuality.validate(decision, step = 7)
        assertEquals(
            GuidedActionPlan.Scroll(
                bounds = null,
                fingerDirection = GuidedSwipeDirection.UP,
            ),
            GuidedActionPlanner.plan(decision, textInput = null, step = 7),
        )
    }

    @Test
    fun actionsThatNeverConsumeBoundsIgnoreAStrayTargetId() {
        listOf(
            GuidedActionType.DONE,
            GuidedActionType.OPEN_APP_DRAWER,
            GuidedActionType.OPEN_QUICK_SETTINGS,
        ).forEach { actionType ->
            GuideInstructionQuality.validate(
                decision(
                    actionType = actionType,
                    bounds = null,
                    targetResolved = false,
                    targetNodeId = "stray-node",
                ),
                step = 8,
            )
        }
    }

    @Test
    fun timeoutUsesCurrentRootAndServerDiagnosticsWithoutKeepingRecoveredFailures() {
        val state = GuideE2eDiagnosticState()

        state.observe(GuideE2eDiagnostic.SERVER_ERROR)
        assertEquals("server_error", state.timeoutFailureCode())
        state.observe(GuideE2eDiagnostic.REQUEST_STARTED)
        assertEquals("decision_timeout", state.timeoutFailureCode())

        state.observe(GuideE2eDiagnostic.CLIENT_CONFIGURATION_ERROR)
        assertEquals("client_configuration_error", state.timeoutFailureCode())
        state.observe(GuideE2eDiagnostic.REQUEST_STARTED)
        state.observe(GuideE2eDiagnostic.RESPONSE_PARSE_ERROR)
        assertEquals("response_parse_error", state.timeoutFailureCode())

        state.observe(GuideE2eDiagnostic.ROOT_UNAVAILABLE)
        assertEquals("root_unavailable", state.timeoutFailureCode())
        state.observe(GuideE2eDiagnostic.ROOT_RECOVERED)
        assertEquals("response_parse_error", state.timeoutFailureCode())
        state.observe(GuideE2eDiagnostic.REQUEST_STARTED)
        assertEquals("decision_timeout", state.timeoutFailureCode())
    }

    private fun decision(
        actionType: GuidedActionType = GuidedActionType.TAP,
        direction: GuidedSwipeDirection? = null,
        bounds: IntArray?,
        targetResolved: Boolean = bounds != null,
        targetNodeId: String? = "n1",
    ): DisplayedDecision = DisplayedDecision(
        generation = 1,
        requestSignature = "request-signature",
        screenPackage = "com.example",
        action = ObservedGuideAction(
            actionType = actionType,
            direction = direction,
            targetNodeId = targetNodeId,
            instruction = "이 대상을 눌러주세요.",
            expectedResult = "다음 화면이 보입니다.",
            evidence = "대상이 화면에 보입니다.",
        ),
        targetBounds = bounds,
        targetResolved = targetResolved,
        targetRole = "Button",
        targetClickable = true,
        targetEditable = false,
        verified = false,
        requestJson = "{}",
        responseJson = "{}",
    )
}
