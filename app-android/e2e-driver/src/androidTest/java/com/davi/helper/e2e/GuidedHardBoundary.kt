package com.davi.helper.e2e

import java.util.Locale

/** Gemini 결정을 실제 손동작으로 주입하기 전에 종료해야 하는 안전 경계. */
fun interface GuidedHardBoundary {
    fun failureCode(decision: DisplayedDecision): String?
}

/** 앱별 시나리오가 runner 변경 없이 조합하는 공통 하드 경계 모음. */
internal object GuidedHardBoundaries {
    val settingsApp: GuidedHardBoundary = GuidedHardBoundary { decision ->
        if (isSettingsPackage(decision.screenPackage) ||
            (decision.action.actionType == GuidedActionType.TAP &&
                normalize(decision.requestTargetLabel()) in SETTINGS_LABELS)
        ) {
            SETTINGS_FAILURE_CODE
        } else {
            null
        }
    }

    /**
     * 특정 package에서 정확히 일치하는 target label의 탭을 막는다.
     *
     * 메시지 전송처럼 기기 밖 부작용이 있는 최종 동작에 사용한다. package와 label을 모두 요구해
     * 다른 앱의 동명 버튼을 과하게 차단하지 않는다.
     */
    fun blockedTapTargets(
        failureCode: String,
        packages: Set<String>,
        labels: Set<String>,
    ): GuidedHardBoundary {
        require(SAFE_FAILURE_CODE.matches(failureCode)) { "unsafe failure code" }
        require(packages.isNotEmpty()) { "blocked packages must not be empty" }
        require(labels.isNotEmpty()) { "blocked labels must not be empty" }
        val normalizedPackages = packages.map(String::trim).filter(String::isNotEmpty).toSet()
        val normalizedLabels = labels.map(::normalize).filter(String::isNotEmpty).toSet()
        require(normalizedPackages.size == packages.size) { "blocked packages must be non-blank" }
        require(normalizedLabels.isNotEmpty()) { "blocked labels must be non-blank" }

        return GuidedHardBoundary { decision ->
            if (decision.action.actionType == GuidedActionType.TAP &&
                decision.screenPackage in normalizedPackages &&
                normalize(decision.requestTargetLabel()) in normalizedLabels
            ) {
                failureCode
            } else {
                null
            }
        }
    }

    fun firstFailure(
        boundaries: List<GuidedHardBoundary>,
        decision: DisplayedDecision,
    ): String? = boundaries.firstNotNullOfOrNull { it.failureCode(decision) }

    private fun isSettingsPackage(packageName: String): Boolean =
        packageName == ANDROID_SETTINGS_PACKAGE ||
            packageName.startsWith("$ANDROID_SETTINGS_PACKAGE.") ||
            packageName == SAMSUNG_SETTINGS_PACKAGE ||
            packageName.startsWith("$SAMSUNG_SETTINGS_PACKAGE.")

    private fun normalize(label: String?): String = label?.trim()?.lowercase(Locale.ROOT).orEmpty()

    private const val SETTINGS_FAILURE_CODE = "settings_app_blocked"
    private const val ANDROID_SETTINGS_PACKAGE = "com.android.settings"
    private const val SAMSUNG_SETTINGS_PACKAGE = "com.samsung.android.settings"
    private val SETTINGS_LABELS = setOf("설정", "settings")
    private val SAFE_FAILURE_CODE = Regex("^[a-z][a-z0-9_]*$")
}
