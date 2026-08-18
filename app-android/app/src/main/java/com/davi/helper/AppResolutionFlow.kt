package com.davi.helper

import kotlin.system.measureTimeMillis

/** release 대화 화면과 debug 백업 화면이 같은 후보·대조·추적 정책을 쓰는 공통 흐름. */
internal class AppResolutionFlow(
    private val index: LaunchableAppIndex,
    private val apiClient: AppResolutionApiClient,
    private val requestTracker: LogicalRequestTracker,
) {
    fun resolve(
        goal: String,
        generation: Long,
        turnIndex: Int,
        isCurrent: (Long) -> Boolean,
    ): AppResolutionCoordinator.Outcome {
        val gateway = AppResolutionGateway { request ->
            val phase = if (request.feedback == null) "candidates" else "feedback"
            val logicalRequest = requestTracker.next(
                signature = "app-resolution-$turnIndex-$phase",
                generation = generation,
            )
            GuideTelemetry.appResolutionRequestStarted(logicalRequest, phase)
            lateinit var result: AppResolutionResult
            val durationMs = measureTimeMillis {
                result = apiClient.resolve(request, logicalRequest)
            }
            record(logicalRequest, phase, result, durationMs)
            if (result is AppResolutionResult.Success) {
                GuideTelemetry.appResolution(
                    phase = phase,
                    candidateCount = result.state.plan?.candidates?.size
                        ?: request.feedback?.plan?.candidates?.size,
                    status = result.state.status.name.lowercase(),
                )
            }
            result
        }
        return AppResolutionCoordinator(index, gateway).resolve(goal, generation, isCurrent)
    }

    fun reset() = requestTracker.reset()

    private fun record(
        request: LogicalRequestAttempt,
        phase: String,
        result: AppResolutionResult,
        durationMs: Long,
    ) {
        when (result) {
            is AppResolutionResult.Success -> {
                requestTracker.markHttpResponse(request, 200)
                GuideTelemetry.appResolutionRequestFinished(
                    request,
                    phase,
                    "success",
                    durationMs,
                    statusCode = 200,
                    serverRequestId = result.trace.requestId,
                )
            }
            is AppResolutionResult.ApiFailure -> {
                requestTracker.markHttpResponse(request, result.statusCode)
                GuideTelemetry.appResolutionRequestFinished(
                    request,
                    phase,
                    "api_error",
                    durationMs,
                    statusCode = result.statusCode,
                    errorKind = result.error.kind.name,
                    serverRequestId = result.trace.requestId,
                )
            }
            is AppResolutionResult.HttpFailure -> {
                requestTracker.markHttpResponse(request, result.statusCode)
                GuideTelemetry.appResolutionRequestFinished(
                    request,
                    phase,
                    "http_error",
                    durationMs,
                    statusCode = result.statusCode,
                    serverRequestId = result.trace.requestId,
                )
            }
            is AppResolutionResult.NetworkFailure -> GuideTelemetry.appResolutionRequestFinished(
                request,
                phase,
                "network_error",
                durationMs,
                exceptionClass = result.exception.javaClass.simpleName,
            )
            is AppResolutionResult.ParseFailure -> GuideTelemetry.appResolutionRequestFinished(
                request,
                phase,
                "response_parse_error",
                durationMs,
                serverRequestId = result.trace?.requestId,
                exceptionClass = result.exception.javaClass.simpleName,
            )
        }
    }
}
