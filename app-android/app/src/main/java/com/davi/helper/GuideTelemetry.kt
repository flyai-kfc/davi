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
