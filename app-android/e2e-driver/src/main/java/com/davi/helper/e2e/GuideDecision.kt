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

enum class GuidedCompletionVerdict(val wire: String) {
    VERIFIED("verified"),
    INCONCLUSIVE("inconclusive"),
    CONTRADICTED("contradicted"),
    ;

    companion object {
        fun fromWire(value: String?): GuidedCompletionVerdict =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("unknown completion verdict")
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
    /** 응답의 target_node_id가 앱이 서버로 보낸 바로 그 screen tree에서 해석됐는가. */
    val targetResolved: Boolean = targetBounds != null,
    val targetRole: String?,
    val targetClickable: Boolean,
    val targetEditable: Boolean,
    val verified: Boolean,
    val completionVerdict: GuidedCompletionVerdict =
        if (verified) GuidedCompletionVerdict.VERIFIED else GuidedCompletionVerdict.INCONCLUSIVE,
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
    const val ACTION_DIAGNOSTIC = "com.davi.helper.e2e.DIAGNOSTIC"
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
    const val EXTRA_TARGET_RESOLVED = "target_resolved"
    const val EXTRA_TARGET_ROLE = "target_role"
    const val EXTRA_TARGET_CLICKABLE = "target_clickable"
    const val EXTRA_TARGET_EDITABLE = "target_editable"
    const val EXTRA_VERIFIED = "verified"
    const val EXTRA_COMPLETION_VERDICT = "completion_verdict"
    const val EXTRA_REQUEST_GZIP = "request_gzip"
    const val EXTRA_RESPONSE_GZIP = "response_gzip"
    const val EXTRA_DIAGNOSTIC = "diagnostic"
    const val DIAGNOSTIC_ROOT_UNAVAILABLE = "root_unavailable"
    const val DIAGNOSTIC_ROOT_RECOVERED = "root_recovered"
    const val DIAGNOSTIC_REQUEST_STARTED = "request_started"
    const val DIAGNOSTIC_SERVER_ERROR = "server_error"
    const val DIAGNOSTIC_CLIENT_CONFIGURATION_ERROR = "client_configuration_error"
    const val DIAGNOSTIC_RESPONSE_PARSE_ERROR = "response_parse_error"
    const val DIAGNOSTIC_SCREEN_STUCK = "screen_stuck"
    const val DIAGNOSTIC_SCREEN_UNREADABLE = "screen_unreadable"
}

/** 화면 원문 없이 결정 대기 실패 원인을 구분하는 debug 전용 관측 이벤트. */
internal enum class GuideE2eDiagnostic(val wire: String) {
    ROOT_UNAVAILABLE(GuideE2eContract.DIAGNOSTIC_ROOT_UNAVAILABLE),
    ROOT_RECOVERED(GuideE2eContract.DIAGNOSTIC_ROOT_RECOVERED),
    REQUEST_STARTED(GuideE2eContract.DIAGNOSTIC_REQUEST_STARTED),
    SERVER_ERROR(GuideE2eContract.DIAGNOSTIC_SERVER_ERROR),
    CLIENT_CONFIGURATION_ERROR(GuideE2eContract.DIAGNOSTIC_CLIENT_CONFIGURATION_ERROR),
    RESPONSE_PARSE_ERROR(GuideE2eContract.DIAGNOSTIC_RESPONSE_PARSE_ERROR),
    SCREEN_STUCK(GuideE2eContract.DIAGNOSTIC_SCREEN_STUCK),
    SCREEN_UNREADABLE(GuideE2eContract.DIAGNOSTIC_SCREEN_UNREADABLE),
    ;

    companion object {
        fun fromWire(value: String?): GuideE2eDiagnostic? = entries.firstOrNull { it.wire == value }
    }
}

/** transient 오류를 즉시 중단하지 않고, 결정 timeout 시점의 실제 원인만 남긴다. */
internal class GuideE2eDiagnosticState {
    private var rootUnavailable = false
    private var requestFailureCode: String? = null
    private var screenFailureCode: String? = null

    @Synchronized
    fun observe(event: GuideE2eDiagnostic) {
        when (event) {
            GuideE2eDiagnostic.ROOT_UNAVAILABLE -> rootUnavailable = true
            GuideE2eDiagnostic.ROOT_RECOVERED -> rootUnavailable = false
            GuideE2eDiagnostic.REQUEST_STARTED -> {
                requestFailureCode = null
                screenFailureCode = null
            }
            GuideE2eDiagnostic.SERVER_ERROR -> requestFailureCode = "server_error"
            GuideE2eDiagnostic.CLIENT_CONFIGURATION_ERROR ->
                requestFailureCode = "client_configuration_error"
            GuideE2eDiagnostic.RESPONSE_PARSE_ERROR -> requestFailureCode = "response_parse_error"
            GuideE2eDiagnostic.SCREEN_STUCK -> screenFailureCode = "screen_stuck"
            GuideE2eDiagnostic.SCREEN_UNREADABLE -> screenFailureCode = "screen_unreadable"
        }
    }

    @Synchronized
    fun reset() {
        rootUnavailable = false
        requestFailureCode = null
        screenFailureCode = null
    }

    @Synchronized
    fun timeoutFailureCode(): String = when {
        rootUnavailable -> "root_unavailable"
        requestFailureCode != null -> checkNotNull(requestFailureCode)
        screenFailureCode != null -> checkNotNull(screenFailureCode)
        else -> "decision_timeout"
    }
}

class GuidedScenarioFailure(
    val code: String,
    val step: Int? = null,
) : AssertionError(
    buildString {
        append("guided scenario failed code=").append(code)
        if (step != null) append(" step=").append(step)
        append(" (화면/안내 원문 생략)")
    },
)
