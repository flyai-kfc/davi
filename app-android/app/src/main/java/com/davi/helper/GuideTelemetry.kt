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

    /**
     * 서버 응답이 **무엇을 담고 있었는가**(#470). 지금까지 [voiceRequestFinished]가
     * `outcome=success`까지만 말해서, 실사용에서 「질문이 이상했다」를 잡아도 **질문이 몇
     * 개였고 보기가 몇 개 왔는지**를 나중에 알 방법이 없었다.
     *
     * **문장은 인자로 오지 않는다** — 길이·개수·상태 코드뿐이다. 음성 원문은 패턴 마스킹을
     * 일부러 안 거는 대신 로그에 안 남기기로 한 자리다(`SPEC.md` §4). 전문이 필요하면
     * debug 전용 [ConversationLog]가 스위치 뒤에서 따로 낸다.
     *
     * [optionCounts]는 질문 순서대로 쉼표로 잇는다("3,0" = 첫 질문 보기 3개, 둘째는 자유 발화).
     */
    fun voiceResponseShape(
        request: LogicalRequestAttempt,
        status: String,
        messageLength: Int,
        questionCount: Int? = null,
        optionCounts: String? = null,
        hasGoal: Boolean? = null,
    ) {
        Log.i(
            GuideAccessibilityService.TAG,
            event(
                "voice_response_shape",
                "logical_request_id" to request.logicalRequestId,
                "attempt" to request.attempt,
                "status" to status,
                "message_length" to messageLength,
                "question_count" to questionCount,
                "option_counts" to optionCounts,
                "has_goal" to hasGoal,
            ),
        )
    }

    /**
     * 사용자가 구체화 질문에 **무엇으로 답했는가**(#470). 보기를 눌렀는지 직접 말했는지가
     * 안 남아서, 「따라 했는데 같은 말을 듣는다」류의 관측(#411 5번)을 이 화면에서는 재구성할
     * 수 없었다.
     *
     * **답의 내용은 안 온다** — 보기면 몇 번째인지, 자유 발화면 길이만.
     */
    fun clarificationAnswer(
        questionIndex: Int,
        questionCount: Int,
        source: String,
        optionIndex: Int? = null,
        answerLength: Int? = null,
    ) {
        Log.i(
            GuideAccessibilityService.TAG,
            event(
                "clarification_answer",
                "question_index" to questionIndex,
                "question_count" to questionCount,
                "source" to source,
                "option_index" to optionIndex,
                "answer_length" to answerLength,
            ),
        )
    }

    /**
     * 질문 카드가 접히거나 펼쳐졌다(#470). #465가 이 판정을 [QuestionFoldGate]로 빼면서
     * **「이것을 잡는 그물이 실기기 확인뿐」**이라고 적었고, 그 사이 같은 자리가 여러 번
     * 깨졌다. 로그가 그 그물의 두 번째 겹이다.
     *
     * **바뀔 때만 부른다** — render는 부분 인식마다 돌아서 매번 찍으면 로그가 화면을 덮는다.
     */
    fun clarificationFold(expanded: Boolean, speaking: Boolean, suppressed: Boolean) {
        Log.i(
            GuideAccessibilityService.TAG,
            event(
                "clarification_fold",
                "expanded" to expanded,
                "speaking" to speaking,
                "suppressed" to suppressed,
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
