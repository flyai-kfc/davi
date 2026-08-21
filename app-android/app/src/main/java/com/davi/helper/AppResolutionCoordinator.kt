package com.davi.helper

/** 앱 하나를 고르지 않고, 안내 시작 전에 실행 가능한 후보가 보였는지만 나타낸다. */
internal enum class AppAvailabilityPreflight {
    CONFIRMED,
    UNCONFIRMED,
}

internal fun appAvailabilityPreflightOf(
    results: List<AppCandidateResult>,
): AppAvailabilityPreflight = if (
    results.any { it.status == AppCandidateAvailability.LAUNCHABLE }
) {
    AppAvailabilityPreflight.CONFIRMED
} else {
    AppAvailabilityPreflight.UNCONFIRMED
}

/**
 * 사전 점검이 통과했을 때 **그 통과를 만든 앱**(#348 바로가기 행). 아니면 null.
 *
 * **왜 이제 앱을 꺼내는가.** 이 클래스는 원래 "앱 하나를 고르지 않는다"였고, 그것은 구현
 * 편의가 아니라 **「다비는 대신 조작하지 않는다」의 파생**이었다 — 앱을 안 고르면 다비가
 * 사용자 대신 결정할 일이 없다. MA-4의 앱 바로가기 행은 그 원칙의 **명시적 예외**로
 * 팀 전원이 합의했고(2026-08-21), 행에 앱 이름과 아이콘을 보이려면 어느 앱인지가 필요하다.
 * **원칙을 근거로 되돌리지 말 것** — 예외라는 것을 알고 연 문이다.
 *
 * **새로 고르는 것이 아니다.** `LAUNCHABLE`은 프로필+패키지가 정확히 하나로 좁혀졌을 때만
 * 나오므로(둘 이상이면 `AMBIGUOUS`) 통과한 후보의 앱은 이미 특정돼 있다. 후보가 여럿
 * 통과하면 **서버가 준 순서의 첫째**를 쓴다 — 후보 목록은 confidence 순이다.
 */
internal fun launchableAppOf(matches: List<LaunchableAppMatch>): LaunchableAppRecord? =
    matches.firstOrNull { it.result.status == AppCandidateAvailability.LAUNCHABLE }?.record

/** 서버 후보와 기기 index로 실행 가능성만 사전 점검하고, 앱이나 goal은 확정하지 않는다. */
internal class AppResolutionCoordinator(
    private val index: LaunchableAppIndex,
    private val gateway: AppResolutionGateway,
) {
    sealed interface Outcome {
        data class Completed(
            val availability: AppAvailabilityPreflight,
            /** 통과를 만든 앱. [AppAvailabilityPreflight.UNCONFIRMED]에서는 항상 null이다. */
            val launchableApp: LaunchableAppRecord? = null,
        ) : Outcome

        data object Dropped : Outcome
    }

    fun resolve(
        goal: String,
        generation: Long,
        isCurrent: (Long) -> Boolean,
    ): Outcome {
        if (!isCurrent(generation)) return Outcome.Dropped
        val initial = gateway.resolve(AppResolutionRequest(goal))
        if (!isCurrent(generation)) return Outcome.Dropped
        // goal은 slot-fill에서 이미 완성됐다. 후보 요청 실패는 그 goal을 버릴 치명 오류가 아니라
        // 실행 가능성을 확인하지 못한 advisory 결과다(#185).
        if (initial !is AppResolutionResult.Success) {
            return Outcome.Completed(AppAvailabilityPreflight.UNCONFIRMED)
        }
        val plan = initial.state.plan
        if (initial.state.status != AppResolutionStatus.CANDIDATES || plan == null) {
            return Outcome.Completed(AppAvailabilityPreflight.UNCONFIRMED)
        }

        repeat(MAX_INDEX_ATTEMPTS) {
            if (!isCurrent(generation)) return Outcome.Dropped
            val snapshot = index.current()
            if (!snapshot.isStable) return@repeat
            val matches = AppCandidateMatcher.matchDetailed(plan, snapshot)
            if (!isCurrent(generation)) return Outcome.Dropped
            return Outcome.Completed(
                appAvailabilityPreflightOf(matches.map { it.result }),
                launchableAppOf(matches),
            )
        }
        // 설치 상태를 확정하지 않는 advisory 점검이다. 반복 invalidation도 경고 후 진행할 수 있다.
        return Outcome.Completed(AppAvailabilityPreflight.UNCONFIRMED)
    }

    private companion object {
        const val MAX_INDEX_ATTEMPTS = 2
    }
}

/** 인덱스 계층의 record를 화면이 쓰는 최소 형태로 옮긴다(#348). */
internal fun LaunchableAppRecord.toShortcutApp(): ShortcutApp =
    ShortcutApp(packageName = packageName, componentName = componentName, label = label)
