package com.davi.helper

/** 서버 후보와 기기 index를 잇되, 세션 generation·index revision이 바뀐 결과는 적용하지 않는다. */
internal class AppResolutionCoordinator(
    private val index: LaunchableAppIndex,
    private val gateway: AppResolutionGateway,
) {
    sealed interface Outcome {
        data class Completed(
            val state: SlotFillState,
            val indexRevision: Long,
        ) : Outcome

        data class Failure(val result: AppResolutionResult) : Outcome
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
        if (initial !is AppResolutionResult.Success) return Outcome.Failure(initial)
        val plan = initial.state.plan
        if (initial.state.status != AppResolutionStatus.CANDIDATES || plan == null) {
            return initial.state.toDialogueState()?.let {
                Outcome.Completed(it, index.revision())
            } ?: Outcome.Failure(invalidState())
        }

        repeat(MAX_INDEX_ATTEMPTS) {
            if (!isCurrent(generation)) return Outcome.Dropped
            val snapshot = index.current()
            val candidateResults = AppCandidateMatcher.match(plan, snapshot)
            val feedback = AppResolutionFeedback(plan, candidateResults)
            val final = gateway.resolve(AppResolutionRequest(goal, feedback))
            if (!isCurrent(generation)) return Outcome.Dropped
            if (index.current().revision != snapshot.revision) return@repeat
            if (final !is AppResolutionResult.Success) return Outcome.Failure(final)
            return final.state.toDialogueState()?.let {
                Outcome.Completed(it, snapshot.revision)
            } ?: Outcome.Failure(invalidState())
        }
        return Outcome.Failure(
            AppResolutionResult.ParseFailure(
                IllegalStateException("launchable app index changed repeatedly"),
            )
        )
    }

    private fun AppResolutionState.toDialogueState(): SlotFillState? = when (status) {
        AppResolutionStatus.RESOLVED -> goal?.let {
            SlotFillState(IntentDialogueStatus.READY, message, it)
        }
        AppResolutionStatus.NEEDS_USER_CHOICE,
        AppResolutionStatus.NEEDS_APP_NAME,
        AppResolutionStatus.UNAVAILABLE_OR_UNKNOWN,
        -> SlotFillState(IntentDialogueStatus.NEEDS_CLARIFICATION, message)
        AppResolutionStatus.CANDIDATES -> null
    }

    private fun invalidState() =
        AppResolutionResult.ParseFailure(IllegalArgumentException("invalid app resolution state"))

    private companion object {
        const val MAX_INDEX_ATTEMPTS = 2
    }
}
