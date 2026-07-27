package com.davi.helper

import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GuideApiClientTest {
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
    fun `posts the contract and parses a guide action`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"action":{"expected_result":"채팅 목록이 보인다","evidence":"첫 클릭 요소","action_type":"tap","target_node_id":"n1","instruction":"카카오톡을 누르세요."},"model":"stub","verified":false}""",
            ),
        )

        val result = GuideApiClient(server.url("/").toString()).nextStep(sampleRequest())

        assertTrue(result is NextStepResult.Success)
        result as NextStepResult.Success
        assertEquals(ActionType.TAP, result.response.action.actionType)
        assertEquals("n1", result.response.action.targetNodeId)
        assertEquals("카카오톡을 누르세요.", result.response.action.instruction)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/next-step", request.path)
        val body = request.body.readUtf8()
        val payload = Json.parseToJsonElement(body).jsonObject
        assertEquals("카카오톡 열기", payload["goal"]?.jsonPrimitive?.content)
        assertTrue(body.contains("\"label\":\"•••\""))
        assertFalse(body.contains("secret@example.com"))
    }

    @Test
    fun `parses server api errors by kind`() {
        server.enqueue(
            MockResponse().setResponseCode(502).setBody(
                """{"kind":"UPSTREAM","message":"provider unavailable","request_id":"r1"}""",
            ),
        )

        val result = GuideApiClient(server.url("/").toString()).nextStep(sampleRequest())

        assertTrue(result is NextStepResult.ApiFailure)
        result as NextStepResult.ApiFailure
        assertEquals(502, result.statusCode)
        assertEquals(ApiErrorKind.UPSTREAM, result.error.kind)
    }

    @Test
    fun `returns a network failure without throwing`() {
        val client = OkHttpClient.Builder().callTimeout(1, TimeUnit.SECONDS).build()

        val result = GuideApiClient("http://127.0.0.1:1", client).nextStep(sampleRequest())

        assertTrue(result is NextStepResult.NetworkFailure)
    }

    private fun sampleRequest() = NextStepRequest(
        goal = "카카오톡 열기",
        screen = Screen(
            packageName = "com.example",
            elements = listOf(
                UiElement(
                    nodeId = "n1",
                    label = "•••",
                    role = "Button",
                    clickable = true,
                    bounds = listOf(1, 2, 3, 4),
                ),
            ),
        ),
        clientVersion = "0.1.0",
    )
}
