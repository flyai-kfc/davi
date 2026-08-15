package com.davi.helper

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class KakaoAuthApiClientTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `posts kakao token and parses session token`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader(RequestTracingContract.REQUEST_ID_HEADER, SERVER_REQUEST_ID)
                .addHeader(RequestTracingContract.LOGICAL_REQUEST_ID_HEADER, TRACE_ID)
                .addHeader(RequestTracingContract.ATTEMPT_HEADER, "1")
                .setBody("""{"session_token":"jwt-value","expires_in_seconds":2592000}"""),
        )

        val result = KakaoAuthApiClient(server.url("/").toString())
            .login(sampleRequest(), trace())

        assertTrue(result is KakaoLoginResult.Success)
        result as KakaoLoginResult.Success
        assertEquals("jwt-value", result.response.sessionToken)
        assertEquals(2_592_000, result.response.expiresInSeconds)
        assertEquals(SERVER_REQUEST_ID, result.trace.requestId)
        assertEquals(TRACE_ID, result.trace.logicalRequestId)

        val recorded = server.takeRequest()
        assertEquals("/v1/auth/kakao", recorded.path)
        assertEquals(TRACE_ID, recorded.getHeader(RequestTracingContract.LOGICAL_REQUEST_ID_HEADER))
        assertEquals("1", recorded.getHeader(RequestTracingContract.ATTEMPT_HEADER))
        val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals("kakao-access-token", body["kakao_access_token"]?.jsonPrimitive?.content)
        assertEquals("0.1.0", body["client_version"]?.jsonPrimitive?.content)
    }

    @Test
    fun `sends development token header when configured`() {
        val token = "t".repeat(32)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"session_token":"jwt-value","expires_in_seconds":100}"""),
        )

        KakaoAuthApiClient(server.url("/").toString(), devAccessToken = token)
            .login(sampleRequest(), trace())

        assertEquals(token, server.takeRequest().getHeader("X-Davi-Dev-Token"))
    }

    @Test
    fun `maps auth rejection to api failure with kind`() {
        server.enqueue(
            MockResponse().setResponseCode(401).setBody(
                """{"kind":"AUTH","message":"kakao access token rejected","request_id":"r1"}""",
            ),
        )

        val result = KakaoAuthApiClient(server.url("/").toString())
            .login(sampleRequest(), trace())

        assertTrue(result is KakaoLoginResult.ApiFailure)
        result as KakaoLoginResult.ApiFailure
        assertEquals(401, result.statusCode)
        assertEquals(ApiErrorKind.AUTH, result.error.kind)
        assertEquals("r1", result.error.requestId)
    }

    @Test
    fun `maps server and network error kinds`() {
        val bodies = listOf(
            503 to """{"kind":"SERVER","message":"kakao auth is not configured","request_id":"r2"}""",
            502 to """{"kind":"NETWORK","message":"kakao api unreachable","request_id":"r3"}""",
        )
        bodies.forEach { (code, body) ->
            server.enqueue(MockResponse().setResponseCode(code).setBody(body))
        }
        val client = KakaoAuthApiClient(server.url("/").toString())

        val first = client.login(sampleRequest(), trace())
        val second = client.login(sampleRequest(), trace())

        assertTrue(first is KakaoLoginResult.ApiFailure)
        assertEquals(ApiErrorKind.SERVER, (first as KakaoLoginResult.ApiFailure).error.kind)
        assertTrue(second is KakaoLoginResult.ApiFailure)
        assertEquals(ApiErrorKind.NETWORK, (second as KakaoLoginResult.ApiFailure).error.kind)
    }

    @Test
    fun `non json error body degrades to http failure`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))

        val result = KakaoAuthApiClient(server.url("/").toString())
            .login(sampleRequest(), trace())

        assertTrue(result is KakaoLoginResult.HttpFailure)
        assertEquals(500, (result as KakaoLoginResult.HttpFailure).statusCode)
    }

    @Test
    fun `disconnect maps to network failure`() {
        server.enqueue(
            MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START),
        )

        val result = KakaoAuthApiClient(server.url("/").toString())
            .login(sampleRequest(), trace())

        assertTrue(result is KakaoLoginResult.NetworkFailure)
    }

    @Test
    fun `rejects sessions that drift from server contract`() {
        val invalidBodies = listOf(
            """{"session_token":"","expires_in_seconds":100}""",
            """{"session_token":"jwt-value","expires_in_seconds":0}""",
            """{"expires_in_seconds":100}""",
        )
        invalidBodies.forEach {
            server.enqueue(MockResponse().setResponseCode(200).setBody(it))
        }
        val client = KakaoAuthApiClient(server.url("/").toString())

        repeat(invalidBodies.size) {
            assertTrue(client.login(sampleRequest(), trace()) is KakaoLoginResult.ParseFailure)
        }
    }

    private fun sampleRequest() = KakaoLoginRequest(
        kakaoAccessToken = "kakao-access-token",
        clientVersion = "0.1.0",
    )

    private fun trace(attempt: Int = 1) = LogicalRequestAttempt(TRACE_ID, attempt)

    private companion object {
        const val TRACE_ID = "3fa85f64-5717-4562-b3fc-2c963f66afa6"
        const val SERVER_REQUEST_ID = "0123456789abcdef0123456789abcdef"
    }
}
