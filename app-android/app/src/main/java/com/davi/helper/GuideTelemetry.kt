package com.davi.helper

import android.util.Log

/** 원문 화면·목표·안내를 인자로 받지 않는 비식별 앱 이벤트 경계(#81). */
object GuideTelemetry {
    fun appIndexRefreshed(count: Int, complete: Boolean, revision: Long) {
        Log.i(
            GuideAccessibilityService.TAG,
            event(
                "app_index_refreshed",
                "launchable_count" to count,
                "complete" to complete,
                "revision" to revision,
            ),
        )
    }

    fun appResolution(phase: String, candidateCount: Int? = null, status: String? = null) {
        Log.i(
            GuideAccessibilityService.TAG,
            event(
                "app_resolution",
                "phase" to phase,
                "candidate_count" to candidateCount,
                "status" to status,
            ),
        )
    }

    fun appResolutionRequestStarted(request: LogicalRequestAttempt, phase: String) {
        Log.i(
            GuideAccessibilityService.TAG,
            event(
                "app_resolution_request_started",
                "logical_request_id" to request.logicalRequestId,
                "attempt" to request.attempt,
                "phase" to phase,
            ),
        )
    }

    fun appResolutionRequestFinished(
        request: LogicalRequestAttempt,
        phase: String,
        outcome: String,
        durationMs: Long,
        statusCode: Int? = null,
        errorKind: String? = null,
        serverRequestId: String? = null,
        exceptionClass: String? = null,
    ) {
        Log.i(
            GuideAccessibilityService.TAG,
            event(
                "app_resolution_request_finished",
                "logical_request_id" to request.logicalRequestId,
                "attempt" to request.attempt,
                "phase" to phase,
                "outcome" to outcome,
                "duration_ms" to durationMs,
                "status_code" to statusCode,
                "error_kind" to errorKind,
                "server_request_id" to serverRequestId,
                "exception_class" to exceptionClass,
            ),
        )
    }

    fun screenStable(result: UiTreeSerializer.Result) {
        val screen = result.screen
        Log.i(
            GuideAccessibilityService.TAG,
            event(
                "screen_stable",
                "package" to screen.packageName,
                "element_count" to screen.elements.size,
                "clickable_count" to screen.elements.count { it.clickable },
                "editable_count" to screen.elements.count { it.editable },
                "focused_count" to screen.elements.count { it.focused },
                // 행동 상태의 관측 빈도(C18 · #271). **개수만 남긴다** — 어느 요소인지는
                // label이 있어야 알 수 있고 그건 비식별 로그에 넣지 않는다.
                // 비활성 tap 가드레일을 넣을지는 이 수치를 실측한 뒤에 정한다.
                "disabled_count" to screen.elements.count { it.enabled == false },
                "checkable_count" to screen.elements.count { it.checkState != CheckState.UNAVAILABLE },
                "checked_count" to screen.elements.count { it.checkState == CheckState.CHECKED },
                "selected_count" to screen.elements.count { it.selected == true },
                "empty_input_count" to screen.elements.count { it.showingHintText == true },
                // 모달 뒤에 깔려 사용자가 조작할 수 없는 요소 수. 개수만 남기는 이유는 위와 같다.
                "obscured_count" to screen.elements.count { it.obscured == true },
                // 제목 원문은 label과 같은 등급이라 로그에 쓰지 않는다. 있고 없고만 남긴다.
                "window_title" to if (screen.windowTitle != null) "present" else "absent",
                "ime_visible" to (screen.imeVisible ?: "unknown"),
                "scrollable" to (screen.hasScrollableElement ?: "unknown"),
                "current_page" to screen.currentPage,
                "total_pages" to screen.totalPages,
                "truncated" to result.truncated,
            ),
        )
    }

    fun requestStarted(request: LogicalRequestAttempt) {
        Log.i(
            GuideAccessibilityService.TAG,
            event(
                "request_started",
                "logical_request_id" to request.logicalRequestId,
                "attempt" to request.attempt,
            ),
        )
    }

    fun requestFinished(
        request: LogicalRequestAttempt,
        outcome: String,
        durationMs: Long,
        statusCode: Int? = null,
        errorKind: String? = null,
        serverRequestId: String? = null,
        exceptionClass: String? = null,
    ) {
        Log.i(
            GuideAccessibilityService.TAG,
            event(
                "request_finished",
                "logical_request_id" to request.logicalRequestId,
                "attempt" to request.attempt,
                "outcome" to outcome,
                "duration_ms" to durationMs,
                "status_code" to statusCode,
                "error_kind" to errorKind,
                "server_request_id" to serverRequestId,
                "exception_class" to exceptionClass,
            ),
        )
    }

    fun responseDropped(request: LogicalRequestAttempt, reason: String) {
        Log.i(
            GuideAccessibilityService.TAG,
            event(
                "response_dropped",
                "logical_request_id" to request.logicalRequestId,
                "attempt" to request.attempt,
                "reason" to reason,
            ),
        )
    }

    fun rootState(state: String) {
        Log.i(GuideAccessibilityService.TAG, event("root_state", "state" to state))
    }

    /**
     * 대화 화면의 상태 전이(#197). **이름과 안내 세션 유무만 남긴다** — 안내 중(메인 9)과
     * 종료 확인(메인 10)은 눈으로만 확인할 수 있어 회귀가 나도 짚을 근거가 없었다.
     *
     * 발화·질문·goal·대화 UUID는 여기 오지 않는다(app-android/CLAUDE.md).
     */
    fun screenState(state: String, guideActive: Boolean) {
        Log.i(
            GuideAccessibilityService.TAG,
            event("screen_state", "state" to state, "guide_active" to guideActive),
        )
    }

    fun voiceState(state: String, errorCode: Int? = null) {
        Log.i(
            GuideAccessibilityService.TAG,
            event("voice_state", "state" to state, "error_code" to errorCode),
        )
    }

    fun voiceRequestStarted(request: LogicalRequestAttempt, turnIndex: Int) {
        Log.i(
            GuideAccessibilityService.TAG,
            event(
                "voice_request_started",
                "logical_request_id" to request.logicalRequestId,
                "attempt" to request.attempt,
                "turn_index" to turnIndex,
            ),
        )
    }

    fun voiceRequestFinished(
        request: LogicalRequestAttempt,
        outcome: String,
        durationMs: Long,
        statusCode: Int? = null,
        errorKind: String? = null,
        serverRequestId: String? = null,
        exceptionClass: String? = null,
    ) {
        Log.i(
            GuideAccessibilityService.TAG,
            event(
                "voice_request_finished",
                "logical_request_id" to request.logicalRequestId,
                "attempt" to request.attempt,
                "outcome" to outcome,
                "duration_ms" to durationMs,
                "status_code" to statusCode,
                "error_kind" to errorKind,
                "server_request_id" to serverRequestId,
                "exception_class" to exceptionClass,
            ),
        )
    }

    fun voiceResponseDropped(request: LogicalRequestAttempt, reason: String) {
        Log.i(
            GuideAccessibilityService.TAG,
            event(
                "voice_response_dropped",
                "logical_request_id" to request.logicalRequestId,
                "attempt" to request.attempt,
                "reason" to reason,
            ),
        )
    }

    internal fun event(name: String, vararg fields: Pair<String, Any?>): String = buildString {
        append("event=").append(name)
        fields.forEach { (key, value) ->
            if (value != null) append(' ').append(key).append('=').append(value)
        }
    }
}
