package com.davi.helper.e2e

import org.junit.Assert.assertEquals
import org.junit.Test

class GuidedLauncherPolicyTest {
    private val fragments = setOf("카카오톡", "kakaotalk")

    @Test
    fun appNameIsMatchedAsFragmentInsideLongerLabel() {
        val assessment = GuidedLauncherPolicy.assess(
            decision(label = "카카오톡, 알림 3개"),
            fragments,
        )

        assertEquals(emptyList<String>(), assessment.deviationCodes)
    }

    @Test
    fun searchAndAppDrawerTargetsAreExpected() {
        listOf("검색", "Search apps", "앱스").forEach { label ->
            assertEquals(
                "label=$label",
                emptyList<String>(),
                GuidedLauncherPolicy.assess(decision(label = label), fragments).deviationCodes,
            )
        }
    }

    @Test
    fun unrelatedLauncherTargetIsObservedAndExecuted() {
        val assessment = GuidedLauncherPolicy.assess(decision(label = "다른 앱"), fragments)

        assertEquals(listOf("unexpected_launcher_target"), assessment.deviationCodes)
    }

    @Test
    fun typeOutsideSearchFieldIsRecordedAsDeviation() {
        val assessment = GuidedLauncherPolicy.assess(
            decision(label = "카카오톡", actionType = GuidedActionType.TYPE),
            fragments,
        )

        assertEquals(listOf("unexpected_launcher_target"), assessment.deviationCodes)
    }

    private fun decision(
        label: String,
        actionType: GuidedActionType = GuidedActionType.TAP,
    ): DisplayedDecision = DisplayedDecision(
        generation = 1,
        requestSignature = "signature",
        screenPackage = "com.sec.android.app.launcher",
        action = ObservedGuideAction(
            actionType = actionType,
            direction = null,
            targetNodeId = "n1",
            instruction = "대상을 눌러주세요.",
            expectedResult = "다음 화면",
            evidence = "대상이 보입니다.",
        ),
        targetBounds = intArrayOf(0, 0, 100, 100),
        targetRole = "TextView",
        targetClickable = actionType == GuidedActionType.TAP,
        targetEditable = actionType == GuidedActionType.TYPE,
        verified = false,
        requestJson = """{"screen":{"elements":[{"node_id":"n1","label":"$label"}]}}""",
        responseJson = "{}",
    )
}
