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

internal sealed interface AppResolutionResult {
    data class Success(
        val state: AppResolutionState,
        val trace: ServerResponseTrace,
    ) : AppResolutionResult

    data class ApiFailure(
        val statusCode: Int,
        val error: ApiError,
        val trace: ServerResponseTrace,
        val sessionAuthenticationRequired: Boolean = false,
    ) : AppResolutionResult

    data class HttpFailure(val statusCode: Int, val trace: ServerResponseTrace) : AppResolutionResult
    data class NetworkFailure(val exception: IOException) : AppResolutionResult
    data class ParseFailure(
        val exception: Exception,
        val trace: ServerResponseTrace? = null,
    ) : AppResolutionResult
}

internal fun interface AppResolutionGateway {
    fun resolve(request: AppResolutionRequest): AppResolutionResult
}

internal class AppResolutionApiClient(
    serverUrl: String,
    private val httpClient: OkHttpClient = defaultHttpClient(),
    devAccessToken: String? = null,
    private val sessionToken: () -> String? = { null },
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    },
) {
    private val endpoint = "${serverUrl.trimEnd('/')}/v1/intent/app-resolution"
    private val devAccessToken = devAccessToken?.trim()?.takeIf(String::isNotEmpty)

    fun resolve(
        request: AppResolutionRequest,
        logicalRequest: LogicalRequestAttempt,
    ): AppResolutionResult {
        val payload = runCatching { json.encodeToString(request) }.getOrElse {
            return AppResolutionResult.ParseFailure(it as Exception)
        }
        val builder = Request.Builder()
            .url(endpoint)
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .header(RequestTracingContract.LOGICAL_REQUEST_ID_HEADER, logicalRequest.logicalRequestId)
            .header(RequestTracingContract.ATTEMPT_HEADER, logicalRequest.attempt.toString())
        devAccessToken?.let { builder.header(DEV_TOKEN_HEADER, it) }
        SessionAuthentication.attach(builder, sessionToken)
        return try {
            httpClient.newCall(builder.build()).execute().use { response ->
                val trace = ServerResponseTrace(
                    requestId = response.header(RequestTracingContract.REQUEST_ID_HEADER)
                        ?.takeIf { REQUEST_ID.matches(it) },
                    logicalRequestId = response.header(RequestTracingContract.LOGICAL_REQUEST_ID_HEADER)
                        ?.takeIf { it == logicalRequest.logicalRequestId },
                    attempt = response.header(RequestTracingContract.ATTEMPT_HEADER)
                        ?.takeIf { it == logicalRequest.attempt.toString() }
                        ?.toInt(),
                )
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    try {
                        val state = json.decodeFromString<AppResolutionState>(body)
                        requireValidState(state)
                        AppResolutionResult.Success(state, trace)
                    } catch (exception: Exception) {
                        AppResolutionResult.ParseFailure(exception, trace)
                    }
                } else {
                    try {
                        AppResolutionResult.ApiFailure(
                            response.code,
                            json.decodeFromString(body),
                            trace,
                            SessionAuthentication.isRequired(response),
                        )
                    } catch (_: Exception) {
                        AppResolutionResult.HttpFailure(response.code, trace)
                    }
                }
            }
        } catch (exception: IOException) {
            AppResolutionResult.NetworkFailure(exception)
        }
    }

    private fun requireValidState(state: AppResolutionState) {
        require(state.message.isNotBlank() && state.message.length <= MAX_MESSAGE_LENGTH)
        when (state.status) {
            AppResolutionStatus.CANDIDATES -> {
                require(state.plan != null && state.plan.candidates.size <= MAX_APP_CANDIDATES)
                require(state.selectedCandidateId == null && state.goal == null)
                require(
                    state.plan.capability.isNotBlank() &&
                        state.plan.capability.length <= MAX_CAPABILITY_LENGTH,
                )
                val ids = state.plan.candidates.map { candidate ->
                    require(CANDIDATE_ID.matches(candidate.candidateId))
                    require(
                        candidate.canonicalName.isNotBlank() &&
                            candidate.canonicalName.length <= MAX_CANDIDATE_VALUE_LENGTH,
                    )
                    require(candidate.packageNames.size <= MAX_CANDIDATE_VALUES)
                    require(candidate.labelAliases.size <= MAX_CANDIDATE_VALUES)
                    require(
                        candidate.packageNames.all {
                            it.length <= MAX_CANDIDATE_VALUE_LENGTH && PACKAGE_NAME.matches(it)
                        },
                    )
                    require(
                        candidate.labelAliases.all {
                            it.isNotBlank() && it.length <= MAX_CANDIDATE_VALUE_LENGTH
                        },
                    )
                    require(candidate.packageNames.distinct().size == candidate.packageNames.size)
                    require(candidate.labelAliases.distinct().size == candidate.labelAliases.size)
                    require(candidate.confidence in 0.0..1.0)
                    candidate.candidateId
                }
                require(ids.distinct().size == ids.size)
            }
            AppResolutionStatus.RESOLVED -> {
                require(state.plan == null)
                require(
                    state.selectedCandidateId?.let(CANDIDATE_ID::matches) == true &&
                        state.goal?.isNotBlank() == true &&
                        state.goal.length <= MAX_GOAL_LENGTH,
                )
            }
            AppResolutionStatus.NEEDS_USER_CHOICE,
            AppResolutionStatus.NEEDS_APP_NAME,
            AppResolutionStatus.UNAVAILABLE_OR_UNKNOWN,
            -> require(state.plan == null && state.selectedCandidateId == null && state.goal == null)
        }
    }

    private companion object {
        const val MAX_APP_CANDIDATES = 5
        const val MAX_CANDIDATE_VALUES = 5
        const val MAX_CANDIDATE_VALUE_LENGTH = 100
        const val MAX_CAPABILITY_LENGTH = 80
        const val MAX_MESSAGE_LENGTH = 300
        const val MAX_GOAL_LENGTH = 500
        const val DEV_TOKEN_HEADER = "X-Davi-Dev-Token"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val REQUEST_ID = Regex("[0-9a-f]{32}")
        val CANDIDATE_ID = Regex("candidate-[1-5]")
        val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            // 후보 생성은 계약 교정으로 provider를 최대 두 번 호출할 수 있다. 서버 작업이 끝나기
            // 전에 앱만 끊겨 같은 음성 턴이 실패하고 Gemini 쿼터만 소비되지 않게 전체 상한을 둔다.
            .readTimeout(45, TimeUnit.SECONDS)
            .build()
    }
}
