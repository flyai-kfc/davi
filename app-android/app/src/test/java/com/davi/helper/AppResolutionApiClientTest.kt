package com.davi.helper

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AppResolutionApiClientTest {
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
    fun `parses candidate plan and sends trace without device app list`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader(RequestTracingContract.REQUEST_ID_HEADER, SERVER_REQUEST_ID)
                .setHeader(RequestTracingContract.LOGICAL_REQUEST_ID_HEADER, TRACE_ID)
                .setHeader(RequestTracingContract.ATTEMPT_HEADER, "1")
                .setBody(
                    """{"status":"candidates","message":"후보 확인","plan":{"capability":"camera","candidates":[{"candidate_id":"candidate-1","canonical_name":"카메라","package_names":["com.example.camera"],"label_aliases":["카메라"],"confidence":0.9}]}}""",
                ),
        )

        val result = client().resolve(AppResolutionRequest("사진 찍기"), trace())

        assertTrue(result is AppResolutionResult.Success)
        result as AppResolutionResult.Success
        assertEquals(AppResolutionStatus.CANDIDATES, result.state.status)
        assertEquals(1, result.state.plan?.candidates?.size)
        val recorded = server.takeRequest()
        assertEquals("/v1/intent/app-resolution", recorded.path)
        assertEquals(TRACE_ID, recorded.getHeader(RequestTracingContract.LOGICAL_REQUEST_ID_HEADER))
        val body = recorded.body.readUtf8()
        assertTrue("profile" !in body)
        assertTrue("component" !in body)
    }

    @Test
    fun `rejects resolved response without goal`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"status":"resolved","message":"정리 완료","selected_candidate_id":"candidate-1"}""",
            ),
        )
        assertTrue(
            client().resolve(AppResolutionRequest("목표"), trace()) is AppResolutionResult.ParseFailure,
        )
    }

    @Test
    fun `parses existing api error`() {
        server.enqueue(
            MockResponse().setResponseCode(429).setBody(
                """{"kind":"QUOTA","message":"busy","retry_after_seconds":12}""",
            ),
        )
        val result = client().resolve(AppResolutionRequest("목표"), trace())
        assertTrue(result is AppResolutionResult.ApiFailure)
        result as AppResolutionResult.ApiFailure
        assertEquals(ApiErrorKind.QUOTA, result.error.kind)
    }

    @Test
    fun `attaches session token and recognizes a bearer challenge`() {
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setHeader("WWW-Authenticate", "Bearer")
                .setBody("""{"kind":"AUTH","message":"session token required"}"""),
        )

        val result = AppResolutionApiClient(
            server.url("/").toString(),
            sessionToken = { "session-token" },
        ).resolve(AppResolutionRequest("목표"), trace()) as AppResolutionResult.ApiFailure

        assertEquals("Bearer session-token", server.takeRequest().getHeader("Authorization"))
        assertTrue(result.sessionAuthenticationRequired)
    }

    @Test
    fun `returns network failure`() {
        val http = OkHttpClient.Builder().callTimeout(1, TimeUnit.SECONDS).build()
        val result = AppResolutionApiClient("http://127.0.0.1:1", http)
            .resolve(AppResolutionRequest("목표"), trace())
        assertTrue(result is AppResolutionResult.NetworkFailure)
    }

    private fun client() = AppResolutionApiClient(server.url("/").toString())
    private fun trace() = LogicalRequestAttempt(TRACE_ID, 1)

    private companion object {
        const val TRACE_ID = "123e4567-e89b-12d3-a456-426614174000"
        const val SERVER_REQUEST_ID = "0123456789abcdef0123456789abcdef"
    }
}
