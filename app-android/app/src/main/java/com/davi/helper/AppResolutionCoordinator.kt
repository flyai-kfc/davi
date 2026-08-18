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

/** 서버 후보와 기기 index로 실행 가능성만 사전 점검하고, 앱이나 goal은 확정하지 않는다. */
internal class AppResolutionCoordinator(
    private val index: LaunchableAppIndex,
    private val gateway: AppResolutionGateway,
) {
    sealed interface Outcome {
        data class Completed(
            val availability: AppAvailabilityPreflight,
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
            val candidateResults = AppCandidateMatcher.match(plan, snapshot)
            if (!isCurrent(generation)) return Outcome.Dropped
            return Outcome.Completed(appAvailabilityPreflightOf(candidateResults))
        }
        // 설치 상태를 확정하지 않는 advisory 점검이다. 반복 invalidation도 경고 후 진행할 수 있다.
        return Outcome.Completed(AppAvailabilityPreflight.UNCONFIRMED)
    }

    private companion object {
        const val MAX_INDEX_ATTEMPTS = 2
    }
}
