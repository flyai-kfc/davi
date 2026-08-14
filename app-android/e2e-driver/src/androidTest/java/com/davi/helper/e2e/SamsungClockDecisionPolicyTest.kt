package com.davi.helper.e2e

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SamsungClockDecisionPolicyTest {
    @Test
    fun unknownClockScreenIsRecordedWithoutStoppingTheJourney() {
        val assessment = SamsungClockDecisionPolicy.assessClock(
            screenKind = SamsungClockRobot.ScreenKind.UNKNOWN,
            targetKind = SamsungClockRobot.TargetKind.PERIOD,
            actionType = GuidedActionType.TAP,
            hasSavedTestAlarm = false,
            hasEnteredTestLabel = false,
        )

        assertEquals(listOf("unknown_clock_screen"), assessment.deviationCodes)
        assertNull(assessment.hardFailureCode)
    }

    @Test
    fun unexpectedAlarmTargetAndActionAfterGoalRemainNonTerminal() {
        val assessment = SamsungClockDecisionPolicy.assessClock(
            screenKind = SamsungClockRobot.ScreenKind.ALARM_LIST,
            targetKind = SamsungClockRobot.TargetKind.OTHER,
            actionType = GuidedActionType.TAP,
            hasSavedTestAlarm = true,
            hasEnteredTestLabel = true,
        )

        assertEquals(
            listOf("action_after_goal", "unexpected_alarm_list_target"),
            assessment.deviationCodes,
        )
        assertNull(assessment.hardFailureCode)
    }

    @Test
    fun settingsPackageAndLauncherTargetStayHardBlocked() {
        assertEquals(true, SamsungClockDecisionPolicy.isSettingsPackage("com.android.settings"))
        assertEquals(true, SamsungClockDecisionPolicy.isSettingsPackage("com.samsung.android.settings"))
        assertEquals(false, SamsungClockDecisionPolicy.isSettingsPackage(SamsungClockRobot.PACKAGE))

        val assessment = SamsungClockDecisionPolicy.assessLauncher(decision(label = "설정"))

        assertEquals("settings_app_blocked", assessment.hardFailureCode)
        assertEquals(
            "settings_app_blocked",
            SamsungClockDecisionPolicy.settingsHardFailure(
                decision(label = "설정", screenPackage = "com.android.systemui"),
            )?.hardFailureCode,
        )
    }

    @Test
    fun unrelatedLauncherTargetIsObservedAndExecuted() {
        val assessment = SamsungClockDecisionPolicy.assessLauncher(decision(label = "다른 앱"))

        assertEquals(listOf("unexpected_launcher_target"), assessment.deviationCodes)
        assertNull(assessment.hardFailureCode)
    }

    @Test
    fun prematureDoneIsObservedUntilGoalAndVerificationAgree() {
        assertEquals(
            listOf("done_before_goal", "done_unverified"),
            GuidedCompletionPolicy.deviations(goalSatisfied = false, verified = false),
        )
        assertEquals(
            listOf("done_before_goal"),
            GuidedCompletionPolicy.deviations(goalSatisfied = false, verified = true),
        )
        assertEquals(emptyList<String>(), GuidedCompletionPolicy.deviations(true, true))
    }

    private fun decision(
        label: String,
        screenPackage: String = "com.sec.android.app.launcher",
    ): DisplayedDecision = DisplayedDecision(
        generation = 1,
        requestSignature = "signature",
        screenPackage = screenPackage,
        action = ObservedGuideAction(
            actionType = GuidedActionType.TAP,
            direction = null,
            targetNodeId = "n1",
            instruction = "대상을 눌러주세요.",
            expectedResult = "다음 화면",
            evidence = "대상이 보입니다.",
        ),
        targetBounds = intArrayOf(0, 0, 100, 100),
        targetRole = "TextView",
        targetClickable = true,
        targetEditable = false,
        verified = false,
        requestJson = """{"screen":{"elements":[{"node_id":"n1","label":"$label"}]}}""",
        responseJson = "{}",
    )
}
