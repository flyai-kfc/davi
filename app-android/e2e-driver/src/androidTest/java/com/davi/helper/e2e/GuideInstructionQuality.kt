package com.davi.helper.e2e

/** 특정 한국어 동사를 강제하지 않고 구조화 응답의 최소 실행 가능성만 검사한다. */
internal object GuideInstructionQuality {
    fun validate(decision: DisplayedDecision, step: Int) {
        val action = decision.action
        val instruction = action.instruction.trim()
        if (instruction.length !in 4..240) {
            throw GuidedScenarioFailure("instruction_length", step)
        }
        if (action.expectedResult.isBlank() || action.evidence.isBlank()) {
            throw GuidedScenarioFailure("reasoning_fields_empty", step)
        }
        if (action.actionType in TARGETED_ACTIONS) {
            if (decision.targetBounds?.size != 4) {
                throw GuidedScenarioFailure("target_bounds_missing", step)
            }
        }
        if (action.actionType == GuidedActionType.SCROLL && action.direction == null) {
            throw GuidedScenarioFailure("scroll_direction_missing", step)
        }
    }

    private val TARGETED_ACTIONS =
        // scroll은 계약상 target_node_id가 없는 전역 제스처일 수 있다. tap/type만 요소가 필수다.
        setOf(GuidedActionType.TAP, GuidedActionType.TYPE)
}
