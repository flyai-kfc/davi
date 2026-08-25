package com.davi.helper.e2e

/** UIAutomator에 넘길 수 있는 값은 서버 action과 같은 관측 tree의 bounds에서만 만든다. */
internal sealed interface GuidedActionPlan {
    data class Tap(val bounds: GuidedBounds) : GuidedActionPlan

    data class Type(val bounds: GuidedBounds, val value: String) : GuidedActionPlan

    data class Scroll(
        val bounds: GuidedBounds?,
        val fingerDirection: GuidedSwipeDirection,
    ) : GuidedActionPlan

    data class SwipeWholeScreen(val fingerDirection: GuidedSwipeDirection) : GuidedActionPlan

    data object OpenQuickSettings : GuidedActionPlan
}

internal data class GuidedBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val isEmpty: Boolean
        get() = left >= right || top >= bottom
}

/**
 * 표시된 결정을 손동작 계획으로 바꾸는 유일한 경계.
 *
 * 앱별 selector나 UIAutomator 조회를 받을 인자가 아예 없다. 따라서 다음 행동의 대상은
 * [DisplayedDecision.targetBounds] 이외의 값으로 보정할 수 없다.
 */
internal object GuidedActionPlanner {
    fun plan(
        decision: DisplayedDecision,
        textInput: String?,
        step: Int,
    ): GuidedActionPlan = when (decision.action.actionType) {
        GuidedActionType.TAP -> GuidedActionPlan.Tap(targetBounds(decision, step))
        GuidedActionType.TYPE -> GuidedActionPlan.Type(
            targetBounds(decision, step),
            textInput ?: throw GuidedScenarioFailure("type_value_missing", step),
        )
        GuidedActionType.SCROLL -> GuidedActionPlan.Scroll(
            bounds = decision.targetBounds?.let { targetBounds(decision, step) },
            fingerDirection = fingerDirectionFor(
                decision.action.direction
                    ?: throw GuidedScenarioFailure("scroll_direction_missing", step),
            ),
        )
        GuidedActionType.OPEN_APP_DRAWER ->
            GuidedActionPlan.SwipeWholeScreen(GuidedSwipeDirection.UP)
        GuidedActionType.OPEN_QUICK_SETTINGS -> GuidedActionPlan.OpenQuickSettings
        GuidedActionType.DONE -> throw GuidedScenarioFailure("done_was_executed", step)
    }

    private fun targetBounds(decision: DisplayedDecision, step: Int): GuidedBounds {
        val values = decision.targetBounds
        if (values == null || values.size != 4) {
            throw GuidedScenarioFailure("target_bounds_missing", step)
        }
        val bounds = GuidedBounds(values[0], values[1], values[2], values[3])
        if (bounds.isEmpty) throw GuidedScenarioFailure("target_bounds_empty", step)
        return bounds
    }

    /** 서버의 "보러 가는 방향"을 사용자가 실제로 움직일 손가락 방향으로 뒤집는다(C17). */
    private fun fingerDirectionFor(direction: GuidedSwipeDirection): GuidedSwipeDirection =
        when (direction) {
            GuidedSwipeDirection.UP -> GuidedSwipeDirection.DOWN
            GuidedSwipeDirection.DOWN -> GuidedSwipeDirection.UP
            GuidedSwipeDirection.LEFT -> GuidedSwipeDirection.RIGHT
            GuidedSwipeDirection.RIGHT -> GuidedSwipeDirection.LEFT
        }
}
