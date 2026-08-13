package com.davi.helper.e2e

/** 문장 exact-match 없이 한 단계 안내의 최소 실행 가능성을 검사한다. */
internal object GuideInstructionQuality {
    private val actionWords = mapOf(
        GuidedActionType.TAP to Regex("누르|터치|탭|선택|클릭"),
        GuidedActionType.SCROLL to Regex("쓸|스크롤|스와이프|올리|내리|넘기"),
        GuidedActionType.TYPE to Regex("입력|작성|기입|적|써"),
        GuidedActionType.OPEN_APP_DRAWER to Regex("쓸|앱.{0,5}(목록|서랍)"),
        GuidedActionType.OPEN_QUICK_SETTINGS to Regex("빠른.{0,5}설정|알림.{0,3}창|쓸"),
        GuidedActionType.DONE to Regex("완료|끝|되었|됐"),
    )

    fun validate(decision: DisplayedDecision, step: Int) {
        val action = decision.action
        val instruction = action.instruction.trim()
        if (instruction.length !in 4..240) {
            throw GuidedScenarioFailure("instruction_length", step)
        }
        if (action.expectedResult.isBlank() || action.evidence.isBlank()) {
            throw GuidedScenarioFailure("reasoning_fields_empty", step)
        }
        val vocabulary = actionWords.getValue(action.actionType)
        if (!vocabulary.containsMatchIn(instruction)) {
            // 원문 없이도 어느 구조화 action의 문장 품질이 어긋났는지는 회귀 결과에서 구분한다.
            throw GuidedScenarioFailure("instruction_action_mismatch_${action.actionType.wire}", step)
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
        setOf(GuidedActionType.TAP, GuidedActionType.SCROLL, GuidedActionType.TYPE)
}
