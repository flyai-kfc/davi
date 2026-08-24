package com.davi.helper

import java.io.IOException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

sealed interface NextStepResult {
    data class Success(
        val response: NextStepResponse,
        val trace: ServerResponseTrace,
    ) : NextStepResult

    data class ApiFailure(
        val statusCode: Int,
        val error: ApiError,
        val trace: ServerResponseTrace,
        val sessionAuthenticationRequired: Boolean = false,
    ) : NextStepResult

    data class HttpFailure(
        val statusCode: Int,
        val trace: ServerResponseTrace,
    ) : NextStepResult

    data class NetworkFailure(val exception: IOException) : NextStepResult
    data class ParseFailure(
        val exception: Exception,
        val trace: ServerResponseTrace? = null,
    ) : NextStepResult
}

class GuideApiClient(
    serverUrl: String,
    private val httpClient: OkHttpClient = defaultHttpClient(),
    devAccessToken: String? = null,
    // 로그인 세션 토큰은 빌드 시점 상수가 아니라 런타임에 바뀐다(로그인·만료) — 매 호출마다
    // 다시 읽는다. 로그인하지 않았거나 스킵했으면 null이라 헤더 자체를 안 붙인다(#166 "스킵은
    // 앱 사용을 막지 않는다"). development의 기존 dev 토큰 경로와 무관하게 항상 시도한다 —
    // 서버는 beta·production에서만 이 헤더를 요구한다(server `_external_access_error`).
    private val sessionToken: () -> String? = { null },
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    },
) {
    private val endpoint = "${serverUrl.trimEnd('/')}/v1/next-step"
    private val devAccessToken = devAccessToken?.trim()?.takeIf { it.isNotEmpty() }

    fun nextStep(
        request: NextStepRequest,
        logicalRequest: LogicalRequestAttempt,
    ): NextStepResult {
        val payload = try {
            json.encodeToString(request)
        } catch (exception: Exception) {
            return NextStepResult.ParseFailure(exception)
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
        val httpRequest = requestBuilder.build()

        return try {
            httpClient.newCall(httpRequest).execute().use { response ->
                val echoedLogicalRequestId =
                    response.header(RequestTracingContract.LOGICAL_REQUEST_ID_HEADER)
                        ?.takeIf { it == logicalRequest.logicalRequestId }
                val echoedAttempt = response.header(RequestTracingContract.ATTEMPT_HEADER)
                    ?.takeIf { it == logicalRequest.attempt.toString() }
                    ?.toInt()
                val responseTrace = ServerResponseTrace(
                    requestId = response.header(RequestTracingContract.REQUEST_ID_HEADER)
                        ?.takeIf { REQUEST_ID.matches(it) },
                    logicalRequestId = echoedLogicalRequestId,
                    attempt = echoedAttempt,
                )
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    try {
                        NextStepResult.Success(json.decodeFromString(body), responseTrace)
                    } catch (exception: Exception) {
                        NextStepResult.ParseFailure(exception, responseTrace)
                    }
                } else {
                    try {
                        NextStepResult.ApiFailure(
                            response.code,
                            json.decodeFromString(body),
                            responseTrace,
                            SessionAuthentication.isRequired(response),
                        )
                    } catch (_: Exception) {
                        NextStepResult.HttpFailure(response.code, responseTrace)
                    }
                }
            }
        } catch (exception: IOException) {
            NextStepResult.NetworkFailure(exception)
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val DEV_TOKEN_HEADER = "X-Davi-Dev-Token"
        val REQUEST_ID = Regex("[0-9a-f]{32}")

        fun defaultHttpClient(): OkHttpClient = InferenceHttpClient.create()
    }
}
