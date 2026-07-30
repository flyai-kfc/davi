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
                """{"action":{"expected_result":"채팅 목록이 보인다","evidence":"첫 클릭 요소","action_type":"tap","target_node_id":"n1","instruction":"카카오톡을 누르세요."},"model":"stub","verified":false,"screen_signature":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}""",
            ),
        )

        val result = GuideApiClient(server.url("/").toString()).nextStep(sampleRequest())

        assertTrue(result is NextStepResult.Success)
        result as NextStepResult.Success
        assertEquals(ActionType.TAP, result.response.action.actionType)
        assertEquals("n1", result.response.action.targetNodeId)
        assertEquals("카카오톡을 누르세요.", result.response.action.instruction)
        assertEquals("a".repeat(64), result.response.screenSignature)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/next-step", request.path)
        val body = request.body.readUtf8()
        val payload = Json.parseToJsonElement(body).jsonObject
        assertEquals("카카오톡 열기", payload["goal"]?.jsonPrimitive?.content)
        assertTrue(body.contains("\"label\":\"•••\""))
        assertTrue(body.contains("\"recent_actions\""))
        assertTrue(body.contains("\"screen_signature\":\"${"b".repeat(64)}\""))
        assertFalse(body.contains("secret@example.com"))
    }

    @Test
    fun `adds the development access token header when configured`() {
        val token = "t".repeat(32)
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"action":{"expected_result":"목표 화면","evidence":"근거","action_type":"tap","target_node_id":"n1","instruction":"누르세요."},"model":"stub","verified":false,"screen_signature":"${"a".repeat(64)}"}""",
            ),
        )

        GuideApiClient(
            server.url("/").toString(),
            devAccessToken = token,
        ).nextStep(sampleRequest())

        assertEquals(token, server.takeRequest().getHeader("X-Davi-Dev-Token"))
    }

    @Test
    fun `omits the development access token header when empty`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"action":{"expected_result":"목표 화면","evidence":"근거","action_type":"tap","target_node_id":"n1","instruction":"누르세요."},"model":"stub","verified":false,"screen_signature":"${"a".repeat(64)}"}""",
            ),
        )

        GuideApiClient(
            server.url("/").toString(),
            devAccessToken = "   ",
        ).nextStep(sampleRequest())

        assertEquals(null, server.takeRequest().getHeader("X-Davi-Dev-Token"))
    }

    /**
     * scroll은 서버가 "누를 게 없을 때" 실제로 돌려주는 액션이다.
     * 앱 enum에 이 값이 없던 시절 이 응답이 오면 파싱이 통째로 터졌다(이슈 #17).
     */
    @Test
    fun `parses a scroll action with direction`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"action":{"expected_result":"목록 아래가 보인다","evidence":"더 넘길 수 있는 목록","action_type":"scroll","direction":"up","target_node_id":"n3","instruction":"화면을 쓸어서 더 내려 보세요."},"model":"stub","verified":false}""",
            ),
        )

        val result = GuideApiClient(server.url("/").toString()).nextStep(sampleRequest())

        assertTrue(result is NextStepResult.Success)
        result as NextStepResult.Success
        assertEquals(ActionType.SCROLL, result.response.action.actionType)
        assertEquals(SwipeDirection.UP, result.response.action.direction)
    }

    /** kind 값은 서버 `ErrorKind`와 같아야 한다. 여기 쓰는 SERVER는 서버가 막혔을 때 주는 값이다. */
    @Test
    fun `parses server api errors by kind`() {
        server.enqueue(
            MockResponse().setResponseCode(502).setBody(
                """{"kind":"SERVER","message":"provider unavailable","request_id":"r1"}""",
            ),
        )

        val result = GuideApiClient(server.url("/").toString()).nextStep(sampleRequest())

        assertTrue(result is NextStepResult.ApiFailure)
        result as NextStepResult.ApiFailure
        assertEquals(502, result.statusCode)
        assertEquals(ApiErrorKind.SERVER, result.error.kind)
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
        recentActions = listOf(
            RecentAction(
                screenSignature = "b".repeat(64),
                actionType = ActionType.TAP,
                targetNodeId = "n1",
            ),
        ),
        clientVersion = "0.1.0",
    )
}
