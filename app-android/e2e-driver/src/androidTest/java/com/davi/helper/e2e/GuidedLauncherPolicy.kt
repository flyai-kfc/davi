package com.davi.helper.e2e

import java.util.Locale

/**
 * 홈·앱서랍에서 목표 앱을 찾아가는 구간의 회복 가능한 이탈만 분류한다.
 *
 * 시나리오 공통 — 목표 앱 이름 조각만 다르다. 전용 테스트 기기의 이탈은 비종료 코드로 남기고
 * 실제 결정을 계속 수행한다(설정 앱은 runner의 공통 하드 경계가 이미 막는다).
 */
internal object GuidedLauncherPolicy {
    fun assess(
        decision: DisplayedDecision,
        appLabelFragments: Set<String>,
    ): GuidedDecisionAssessment {
        val label = normalize(decision.requestTargetLabel())
        val expected = when (decision.action.actionType) {
            GuidedActionType.OPEN_APP_DRAWER -> true
            GuidedActionType.SCROLL -> decision.action.direction != null &&
                (decision.targetBounds == null || decision.targetBounds.size == 4)
            GuidedActionType.TAP -> decision.targetClickable &&
                (
                    appLabelFragments.any { label.contains(normalize(it)) } ||
                        isAppDrawerLabel(label) || isSearchLabel(label)
                    )
            GuidedActionType.TYPE -> decision.targetEditable && isSearchLabel(label)
            else -> false
        }
        return if (expected) {
            GuidedDecisionAssessment()
        } else {
            GuidedDecisionAssessment(deviationCodes = listOf("unexpected_launcher_target"))
        }
    }

    private fun isAppDrawerLabel(label: String): Boolean =
        label == "앱" || label.contains("앱스") || label.contains("앱 서랍") || label.contains("apps")

    private fun isSearchLabel(label: String): Boolean =
        label.contains("검색") || label.contains("search")

    private fun normalize(label: String?): String = label?.trim()?.lowercase(Locale.ROOT).orEmpty()
}
