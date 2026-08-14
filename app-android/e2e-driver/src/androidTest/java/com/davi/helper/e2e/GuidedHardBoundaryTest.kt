package com.davi.helper.e2e

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class GuidedHardBoundaryTest {
    @Test
    fun settingsPackageAndTargetAreBlockedByDefaultBoundary() {
        assertEquals(
            "settings_app_blocked",
            GuidedHardBoundaries.settingsApp.failureCode(
                decision(label = "다른 항목", screenPackage = "com.android.settings"),
            ),
        )
        assertEquals(
            "settings_app_blocked",
            GuidedHardBoundaries.settingsApp.failureCode(
                decision(label = "설정", screenPackage = "com.android.systemui"),
            ),
        )
        assertNull(
            GuidedHardBoundaries.settingsApp.failureCode(
                decision(label = "다른 앱", screenPackage = "com.sec.android.app.launcher"),
            ),
        )
    }

    @Test
    fun sideEffectBoundaryRequiresExactPackageAndLabelBeforeBlockingTap() {
        val boundary = GuidedHardBoundaries.blockedTapTargets(
            failureCode = "presend_send_blocked",
            packages = setOf("com.example.messages"),
            labels = setOf("전송", "Send"),
        )

        assertEquals(
            "presend_send_blocked",
            boundary.failureCode(decision(label = " 전송 ", screenPackage = "com.example.messages")),
        )
        assertEquals(
            "presend_send_blocked",
            boundary.failureCode(decision(label = "SEND", screenPackage = "com.example.messages")),
        )
        assertNull(boundary.failureCode(decision(label = "전송", screenPackage = "com.example.camera")))
        assertNull(boundary.failureCode(decision(label = "전송 옵션", screenPackage = "com.example.messages")))
        assertNull(
            boundary.failureCode(
                decision(
                    label = "전송",
                    screenPackage = "com.example.messages",
                    actionType = GuidedActionType.TYPE,
                ),
            ),
        )
    }

    @Test
    fun firstRegisteredBoundaryWinsWithoutInspectingLaterOnes() {
        val boundaries = listOf(
            GuidedHardBoundary { "first_blocked" },
            GuidedHardBoundary { error("첫 경계 뒤를 평가하면 안 된다") },
        )

        assertEquals("first_blocked", GuidedHardBoundaries.firstFailure(boundaries, decision("설정")))
    }

    @Test
    fun blockedTapTargetsRejectsBlankEntriesInEitherSet() {
        assertThrows(IllegalArgumentException::class.java) {
            GuidedHardBoundaries.blockedTapTargets(
                failureCode = "send_blocked",
                packages = setOf("com.example.messages", " "),
                labels = setOf("전송"),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            GuidedHardBoundaries.blockedTapTargets(
                failureCode = "send_blocked",
                packages = setOf("com.example.messages"),
                labels = setOf("전송", " "),
            )
        }
    }

    private fun decision(
        label: String,
        screenPackage: String = "com.sec.android.app.launcher",
        actionType: GuidedActionType = GuidedActionType.TAP,
    ): DisplayedDecision = DisplayedDecision(
        generation = 1,
        requestSignature = "signature",
        screenPackage = screenPackage,
        action = ObservedGuideAction(
            actionType = actionType,
            direction = null,
            targetNodeId = "n1",
            instruction = "대상을 선택하세요.",
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
