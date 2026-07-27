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
    data class Success(val response: NextStepResponse) : NextStepResult
    data class ApiFailure(val statusCode: Int, val error: ApiError) : NextStepResult
    data class HttpFailure(val statusCode: Int) : NextStepResult
    data class NetworkFailure(val exception: IOException) : NextStepResult
    data class ParseFailure(val exception: Exception) : NextStepResult
}

class GuideApiClient(
    serverUrl: String,
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    },
) {
    private val endpoint = "${serverUrl.trimEnd('/')}/v1/next-step"

    fun nextStep(request: NextStepRequest): NextStepResult {
        val payload = try {
            json.encodeToString(request)
        } catch (exception: Exception) {
            return NextStepResult.ParseFailure(exception)
        }
        val httpRequest = Request.Builder()
            .url(endpoint)
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return try {
            httpClient.newCall(httpRequest).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    try {
                        NextStepResult.Success(json.decodeFromString(body))
                    } catch (exception: Exception) {
                        NextStepResult.ParseFailure(exception)
                    }
                } else {
                    try {
                        NextStepResult.ApiFailure(response.code, json.decodeFromString(body))
                    } catch (_: Exception) {
                        NextStepResult.HttpFailure(response.code)
                    }
                }
            }
        } catch (exception: IOException) {
            NextStepResult.NetworkFailure(exception)
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}
