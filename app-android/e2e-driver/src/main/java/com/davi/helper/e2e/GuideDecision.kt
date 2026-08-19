package com.davi.helper.e2e

enum class GuidedActionType(val wire: String) {
    TAP("tap"),
    SCROLL("scroll"),
    TYPE("type"),
    OPEN_QUICK_SETTINGS("open_quick_settings"),
    OPEN_APP_DRAWER("open_app_drawer"),
    DONE("done"),
    ;

    companion object {
        fun fromWire(value: String?): GuidedActionType = entries.firstOrNull { it.wire == value }
            ?: throw IllegalArgumentException("unknown action")
    }
}

enum class GuidedSwipeDirection(val wire: String) {
    UP("up"),
    DOWN("down"),
    LEFT("left"),
    RIGHT("right"),
    ;

    companion object {
        fun fromWire(value: String?): GuidedSwipeDirection? = value?.let { wire ->
            entries.firstOrNull { it.wire == wire } ?: throw IllegalArgumentException("unknown direction")
        }
    }
}

data class ObservedGuideAction(
    val actionType: GuidedActionType,
    val direction: GuidedSwipeDirection?,
    val targetNodeId: String?,
    val instruction: String,
    val expectedResult: String,
    val evidence: String,
)

data class DisplayedDecision(
    val generation: Long,
    val requestSignature: String,
    val screenPackage: String,
    val action: ObservedGuideAction,
    val targetBounds: IntArray?,
    val targetRole: String?,
    val targetClickable: Boolean,
    val targetEditable: Boolean,
    val verified: Boolean,
    /** 앱이 서버로 보낸 1차 마스킹 완료 요청과 파싱한 구조화 응답. 로컬 상세 보고서 전용이다. */
    val requestJson: String,
    val responseJson: String,
)

/** Davi debug manifest와 driver 사이의 서명 보호된 IPC 계약. 값 변경 시 양쪽을 함께 고친다. */
internal object GuideE2eContract {
    const val PERMISSION = "com.davi.helper.permission.CONTROL_E2E"

    /**
     * 드라이버가 모는 앱의 **applicationId**. E2E는 debug 빌드만 몰고, debug는
     * `applicationIdSuffix`로 `.debug`가 붙는다(#58).
     *
     * **[DAVI_CLASS_ROOT]와 붙여 쓰지 않는다.** 컴포넌트를 지목할 때 앞(패키지)은 이 값이고
     * 뒤(클래스 FQN)는 namespace 기준이라 접미어가 없다. 한 상수로 겸하면 debug만 깔린
     * 기기에서는 ActivityNotFoundException이고, 둘 다 깔린 기기에서는 **release 앱이 떠서**
     * 시나리오가 엉뚱한 앱을 조용히 몬다.
     *
     * 리소스 id 접두어(`By.res`)도 namespace가 아니라 이 applicationId를 따른다.
     */
    const val DAVI_PACKAGE = "com.davi.helper.debug"

    /** 클래스 FQN의 뿌리. **namespace라 접미어가 붙지 않는다**([DAVI_PACKAGE] 참고). */
    const val DAVI_CLASS_ROOT = "com.davi.helper"
    const val ACTION_START = "com.davi.helper.e2e.START"
    const val ACTION_START_JOURNEY = "com.davi.helper.e2e.START_JOURNEY"
    const val ACTION_STOP = "com.davi.helper.e2e.STOP"
    const val ACTION_DECISION = "com.davi.helper.e2e.DECISION"
    const val EXTRA_GOAL = "goal"
    const val EXTRA_GENERATION = "generation"
    const val EXTRA_SIGNATURE = "signature"
    const val EXTRA_SCREEN_PACKAGE = "screen_package"
    const val EXTRA_ACTION_TYPE = "action_type"
    const val EXTRA_DIRECTION = "direction"
    const val EXTRA_TARGET_NODE_ID = "target_node_id"
    const val EXTRA_INSTRUCTION = "instruction"
    const val EXTRA_EXPECTED_RESULT = "expected_result"
    const val EXTRA_EVIDENCE = "evidence"
    const val EXTRA_TARGET_BOUNDS = "target_bounds"
    const val EXTRA_TARGET_ROLE = "target_role"
    const val EXTRA_TARGET_CLICKABLE = "target_clickable"
    const val EXTRA_TARGET_EDITABLE = "target_editable"
    const val EXTRA_VERIFIED = "verified"
    const val EXTRA_REQUEST_GZIP = "request_gzip"
    const val EXTRA_RESPONSE_GZIP = "response_gzip"
}
