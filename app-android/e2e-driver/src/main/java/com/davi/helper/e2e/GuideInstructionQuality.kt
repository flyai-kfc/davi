package com.davi.helper.e2e

/** 특정 한국어 동사를 강제하지 않고 구조화 응답의 최소 실행 가능성만 검사한다. */
internal object GuideInstructionQuality {
    fun validate(decision: DisplayedDecision, step: Int) {
        val action = decision.action
        val instruction = action.instruction.trim()
        // 서버 wire 계약이 아니라, 비정상적으로 짧거나 긴 안내를 드러내는 dev-only E2E 관측 상한이다.
        if (instruction.length !in 4..240) {
            throw GuidedScenarioFailure("instruction_length", step)
        }
        if (action.expectedResult.isBlank() || action.evidence.isBlank()) {
            throw GuidedScenarioFailure("reasoning_fields_empty", step)
        }
        val hasTargetNodeId = !action.targetNodeId.isNullOrBlank()
        // bounds를 실제로 소비하는 action만 검사한다. 대상 없는 scroll은 전역 제스처지만, id가
        // 있었는데 같은 요청 tree에서 못 푼 scroll을 전역으로 바꾸면 서버 결정을 보정하는 셈이다.
        // done·가장자리 제스처는 앱도 target을 해석하지 않으므로 stray id를 mismatch로 읽지 않는다.
        if (action.actionType in BOUNDS_ACTIONS && hasTargetNodeId && !decision.targetResolved) {
            throw GuidedScenarioFailure("target_node_id_mismatch", step)
        }
        if (action.actionType in TARGET_REQUIRED_ACTIONS) {
            if (!hasTargetNodeId) {
                throw GuidedScenarioFailure("target_node_id_missing", step)
            }
            if (decision.targetBounds?.size != 4) {
                throw GuidedScenarioFailure("target_bounds_missing", step)
            }
        }
        if (action.actionType == GuidedActionType.SCROLL && action.direction == null) {
            throw GuidedScenarioFailure("scroll_direction_missing", step)
        }
    }

    private val BOUNDS_ACTIONS =
        setOf(GuidedActionType.TAP, GuidedActionType.TYPE, GuidedActionType.SCROLL)

    private val TARGET_REQUIRED_ACTIONS =
        // scroll은 계약상 target_node_id가 없는 전역 제스처일 수 있다. tap/type만 요소가 필수다.
        setOf(GuidedActionType.TAP, GuidedActionType.TYPE)
}
