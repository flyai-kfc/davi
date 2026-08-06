package com.davi.helper

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

sealed interface SlotFillResult {
    data class Success(
        val state: SlotFillState,
        val trace: ServerResponseTrace,
    ) : SlotFillResult

    data class ApiFailure(
        val statusCode: Int,
        val error: ApiError,
        val trace: ServerResponseTrace,
    ) : SlotFillResult

    data class HttpFailure(
        val statusCode: Int,
        val trace: ServerResponseTrace,
    ) : SlotFillResult

    data class NetworkFailure(val exception: IOException) : SlotFillResult
    data class ParseFailure(
        val exception: Exception,
        val trace: ServerResponseTrace? = null,
    ) : SlotFillResult
}

/** 음성 목표 한 턴을 전송한다. 발화·이력·응답 문장은 어떤 로그에도 넘기지 않는다. */
class SlotFillApiClient(
    serverUrl: String,
    private val httpClient: OkHttpClient = defaultHttpClient(),
    devAccessToken: String? = null,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    },
) {
    private val endpoint = "${serverUrl.trimEnd('/')}/v1/intent/slot-fill"
    private val devAccessToken = devAccessToken?.trim()?.takeIf { it.isNotEmpty() }

    fun resolve(
        request: SlotFillRequest,
        logicalRequest: LogicalRequestAttempt,
    ): SlotFillResult {
        val payload = try {
            json.encodeToString(request)
        } catch (exception: Exception) {
            return SlotFillResult.ParseFailure(exception)
        }
        val requestBuilder = Request.Builder()
            .url(endpoint)
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .header(
                RequestTracingContract.LOGICAL_REQUEST_ID_HEADER,
                logicalRequest.logicalRequestId,
            )
            .header(RequestTracingContract.ATTEMPT_HEADER, logicalRequest.attempt.toString())
        devAccessToken?.let { requestBuilder.header(DEV_TOKEN_HEADER, it) }

        return try {
            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                val trace = responseTrace(response, logicalRequest)
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    try {
                        val state = json.decodeFromString<SlotFillState>(body)
                        requireValidState(state)
                        SlotFillResult.Success(state, trace)
                    } catch (exception: Exception) {
                        SlotFillResult.ParseFailure(exception, trace)
                    }
                } else {
                    try {
                        SlotFillResult.ApiFailure(
                            response.code,
                            json.decodeFromString(body),
                            trace,
                        )
                    } catch (_: Exception) {
                        SlotFillResult.HttpFailure(response.code, trace)
                    }
                }
            }
        } catch (exception: IOException) {
            SlotFillResult.NetworkFailure(exception)
        }
    }

    private fun responseTrace(
        response: okhttp3.Response,
        logicalRequest: LogicalRequestAttempt,
    ): ServerResponseTrace = ServerResponseTrace(
        requestId = response.header(RequestTracingContract.REQUEST_ID_HEADER)
            ?.takeIf { REQUEST_ID.matches(it) },
        logicalRequestId = response.header(RequestTracingContract.LOGICAL_REQUEST_ID_HEADER)
            ?.takeIf { it == logicalRequest.logicalRequestId },
        attempt = response.header(RequestTracingContract.ATTEMPT_HEADER)
            ?.takeIf { it == logicalRequest.attempt.toString() }
            ?.toInt(),
    )

    private fun requireValidState(state: SlotFillState) {
        require(state.message.isNotBlank()) { "slot-fill message is blank" }
        when (state.status) {
            IntentDialogueStatus.NEEDS_CLARIFICATION ->
                require(state.goal == null) { "clarification response includes goal" }
            IntentDialogueStatus.READY ->
                require(!state.goal.isNullOrBlank()) { "ready response has no goal" }
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val DEV_TOKEN_HEADER = "X-Davi-Dev-Token"
        val REQUEST_ID = Regex("[0-9a-f]{32}")

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            // 서버 Gemini budget(20초)보다 먼저 끊지 않는다. #82의 공용 timeout 정렬과는 독립이다.
            .readTimeout(25, TimeUnit.SECONDS)
            .build()
    }
}
