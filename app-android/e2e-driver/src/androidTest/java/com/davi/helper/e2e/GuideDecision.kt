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
    const val DAVI_PACKAGE = "com.davi.helper"
    const val ACTION_START = "com.davi.helper.e2e.START"
    const val ACTION_STOP = "com.davi.helper.e2e.STOP"
    const val ACTION_DECISION = "com.davi.helper.e2e.DECISION"
    const val EXTRA_GOAL = "goal"
    const val EXTRA_GENERATION = "generation"
    const val EXTRA_SIGNATURE = "signature"
    const val EXTRA_SCREEN_PACKAGE = "screen_package"
    const val EXTRA_ACTION_TYPE = "action_type"
    const val EXTRA_DIRECTION = "direction"
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
