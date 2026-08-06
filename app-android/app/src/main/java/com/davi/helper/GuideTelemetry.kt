package com.davi.helper

import android.util.Log

/** 원문 화면·목표·안내를 인자로 받지 않는 비식별 앱 이벤트 경계(#81). */
object GuideTelemetry {
    fun screenStable(result: UiTreeSerializer.Result) {
        val screen = result.screen
        Log.i(
            GuideAccessibilityService.TAG,
            event(
                "screen_stable",
                "package" to screen.packageName,
                "element_count" to screen.elements.size,
                "clickable_count" to screen.elements.count { it.clickable },
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

    internal fun event(name: String, vararg fields: Pair<String, Any?>): String = buildString {
        append("event=").append(name)
        fields.forEach { (key, value) ->
            if (value != null) append(' ').append(key).append('=').append(value)
        }
    }
}
