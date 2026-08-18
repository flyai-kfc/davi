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

sealed interface KakaoLoginResult {
    data class Success(
        val response: SessionTokenResponse,
        val trace: ServerResponseTrace,
    ) : KakaoLoginResult

    data class ApiFailure(
        val statusCode: Int,
        val error: ApiError,
        val trace: ServerResponseTrace,
    ) : KakaoLoginResult

    data class HttpFailure(
        val statusCode: Int,
        val trace: ServerResponseTrace,
    ) : KakaoLoginResult

    data class NetworkFailure(val exception: IOException) : KakaoLoginResult
    data class ParseFailure(
        val exception: Exception,
        val trace: ServerResponseTrace? = null,
    ) : KakaoLoginResult
}

/** 카카오 access token을 세션 토큰으로 교환한다. 두 토큰 모두 어떤 로그에도 넘기지 않는다. */
class KakaoAuthApiClient(
    serverUrl: String,
    private val httpClient: OkHttpClient = defaultHttpClient(),
    devAccessToken: String? = null,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    },
) {
    private val endpoint = "${serverUrl.trimEnd('/')}/v1/auth/kakao"
    private val devAccessToken = devAccessToken?.trim()?.takeIf { it.isNotEmpty() }

    fun login(
        request: KakaoLoginRequest,
        logicalRequest: LogicalRequestAttempt,
    ): KakaoLoginResult {
        val payload = try {
            json.encodeToString(request)
        } catch (exception: Exception) {
            return KakaoLoginResult.ParseFailure(exception)
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
                        val session = json.decodeFromString<SessionTokenResponse>(body)
                        requireValidSession(session)
                        KakaoLoginResult.Success(session, trace)
                    } catch (exception: Exception) {
                        KakaoLoginResult.ParseFailure(exception, trace)
                    }
                } else {
                    try {
                        KakaoLoginResult.ApiFailure(
                            response.code,
                            json.decodeFromString(body),
                            trace,
                        )
                    } catch (_: Exception) {
                        KakaoLoginResult.HttpFailure(response.code, trace)
                    }
                }
            }
        } catch (exception: IOException) {
            KakaoLoginResult.NetworkFailure(exception)
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

    private fun requireValidSession(session: SessionTokenResponse) {
        require(session.sessionToken.isNotBlank()) { "session token is blank" }
        require(session.expiresInSeconds > 0) { "session ttl is not positive" }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val DEV_TOKEN_HEADER = "X-Davi-Dev-Token"
        val REQUEST_ID = Regex("[0-9a-f]{32}")

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            // 서버가 카카오 API 검증에 10초 budget을 쓴다 — 그보다 먼저 끊지 않는다.
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
