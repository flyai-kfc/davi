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

sealed interface NextStepResult {
    data class Success(
        val response: NextStepResponse,
        val trace: ServerResponseTrace,
    ) : NextStepResult

    data class ApiFailure(
        val statusCode: Int,
        val error: ApiError,
        val trace: ServerResponseTrace,
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

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}
