package com.davi.helper

import java.io.IOException
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
        val sessionAuthenticationRequired: Boolean = false,
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
    private val sessionToken: () -> String? = { null },
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
        SessionAuthentication.attach(requestBuilder, sessionToken)

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
                            SessionAuthentication.isRequired(response),
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
        require(state.message.isNotBlank() && state.message.length <= MAX_MESSAGE_LENGTH) {
            "slot-fill message is invalid"
        }
        when (state.status) {
            IntentDialogueStatus.NEEDS_CLARIFICATION -> {
                require(state.goal == null) { "clarification response includes goal" }
                val questions = requireNotNull(state.clarification?.questions) {
                    "clarification response has no questions"
                }
                require(questions.size in 1..MAX_CLARIFICATION_QUESTIONS) {
                    "clarification question count is out of range"
                }
                require(questions.map { it.id }.distinct().size == questions.size) {
                    "clarification question ids are duplicated"
                }
                questions.forEach { question ->
                    require(
                        question.id.isNotBlank() && question.id.length <= MAX_QUESTION_ID_LENGTH &&
                            question.prompt.isNotBlank() &&
                            question.prompt.length <= MAX_QUESTION_PROMPT_LENGTH,
                    ) {
                        "clarification question is blank"
                    }
                    require(question.options.size <= MAX_CLARIFICATION_OPTIONS) {
                        "clarification option count is out of range"
                    }
                    require(
                        question.options.all {
                            it.isNotBlank() && it.length <= MAX_OPTION_LENGTH
                        },
                    ) {
                        "clarification option is blank"
                    }
                    require(question.options.distinct().size == question.options.size) {
                        "clarification options are duplicated"
                    }
                    require(question.allowFreeform) { "freeform answer is disabled" }
                }
                require(state.message == questions.first().prompt) {
                    "message differs from first clarification question"
                }
            }
            IntentDialogueStatus.READY -> {
                require(!state.goal.isNullOrBlank() && state.goal.length <= MAX_GOAL_LENGTH) {
                    "ready response has no valid goal"
                }
                require(state.clarification == null) { "ready response includes clarification" }
            }
            IntentDialogueStatus.UNABLE_TO_FINALIZE -> {
                require(state.goal == null) { "unable response includes goal" }
                require(state.clarification == null) { "unable response includes clarification" }
            }
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val DEV_TOKEN_HEADER = "X-Davi-Dev-Token"
        val REQUEST_ID = Regex("[0-9a-f]{32}")
        const val MAX_CLARIFICATION_QUESTIONS = 2
        const val MAX_CLARIFICATION_OPTIONS = 3
        const val MAX_MESSAGE_LENGTH = 300
        const val MAX_GOAL_LENGTH = 500
        const val MAX_QUESTION_ID_LENGTH = 32
        const val MAX_QUESTION_PROMPT_LENGTH = 200
        const val MAX_OPTION_LENGTH = 80

        fun defaultHttpClient(): OkHttpClient = InferenceHttpClient.create()
    }
}
