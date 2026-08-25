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
            MockResponse()
                .setResponseCode(200)
                .addHeader(RequestTracingContract.REQUEST_ID_HEADER, SERVER_REQUEST_ID)
                .addHeader(RequestTracingContract.LOGICAL_REQUEST_ID_HEADER, TRACE_ID)
                .addHeader(RequestTracingContract.ATTEMPT_HEADER, "2")
                .setBody(
                    """{"action":{"expected_result":"채팅 목록이 보인다","evidence":"첫 클릭 요소","completion_evidence":{"node_id":"n1","kind":"failure"},"action_type":"tap","target_node_id":"n1","expected_target_state":{"attribute":"checked","value":true},"instruction":"카카오톡을 누르세요."},"model":"stub","verified":false,"completion_verdict":"contradicted","screen_signature":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}""",
                ),
        )

        val result = GuideApiClient(server.url("/").toString()).nextStep(sampleRequest(), trace(2))

        assertTrue(result is NextStepResult.Success)
        result as NextStepResult.Success
        assertEquals(ActionType.TAP, result.response.action.actionType)
        assertEquals("n1", result.response.action.targetNodeId)
        assertEquals("카카오톡을 누르세요.", result.response.action.instruction)
        assertEquals(CompletionVerdict.CONTRADICTED, result.response.completionVerdict)
        assertEquals(CompletionEvidenceKind.FAILURE, result.response.action.completionEvidence?.kind)
        assertEquals(ObservableState.CHECKED, result.response.action.expectedTargetState?.attribute)
        assertEquals("a".repeat(64), result.response.screenSignature)
        assertEquals(SERVER_REQUEST_ID, result.trace.requestId)
        assertEquals(TRACE_ID, result.trace.logicalRequestId)
        assertEquals(2, result.trace.attempt)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/next-step", request.path)
        assertEquals(TRACE_ID, request.getHeader(RequestTracingContract.LOGICAL_REQUEST_ID_HEADER))
        assertEquals("2", request.getHeader(RequestTracingContract.ATTEMPT_HEADER))
        val body = request.body.readUtf8()
        val payload = Json.parseToJsonElement(body).jsonObject
        assertEquals("카카오톡 열기", payload["goal"]?.jsonPrimitive?.content)
        assertTrue(body.contains("\"label\":\"•••\""))
        assertTrue(body.contains("\"editable\":true"))
        assertTrue(body.contains("\"focused\":true"))
        assertTrue(body.contains("\"check_state\":\"partial\""))
        assertTrue(body.contains("\"ime_visible\":true"))
        assertTrue(body.contains("\"steps\""))
        assertTrue(body.contains("\"structure_signature\":\"${"b".repeat(64)}\""))
        assertTrue(body.contains("\"screen_hint\":\"런처 홈 화면\""))
        assertTrue(body.contains("\"screen_changed\":true"))
        assertTrue(body.contains("\"performed\":true"))
        assertTrue(body.contains("\"target_node_id\":\"n7\""))
        assertTrue(body.contains("\"completion_feedback\""))
        assertTrue(body.contains("\"status\":\"user_rejected\""))
        assertTrue(body.contains("\"expected_target_state\":{\"attribute\":\"checked\",\"value\":true}"))
        // null인 performed(귀속 미확인)는 explicitNulls=false라 키째 생략된다 — 서버 기본값
        // None과 맞물리는 침묵 규약이다. 두 번째 스텝이 그 생략을 고정한다(값이 있는 스텝은
        // 하나뿐이므로 키도 한 번만 나타나야 한다).
        assertEquals(1, Regex("\"performed\"").findAll(body).count())
        // 내용 서명은 현재 화면과 이력 양쪽에 실린다(#410). 서버의 이력 간 대조가 두 자리를
        // 맞대는 것이라 한쪽만 실리면 판정이 성립하지 않는다.
        assertTrue(body.contains("\"content_signature\":\"${"d".repeat(64)}\""))
        assertTrue(body.contains("\"content_signature\":\"${"e".repeat(64)}\""))
        // 값이 없는 스텝은 performed와 같은 침묵 규약으로 키째 빠진다. 현재 화면·스텝·완료
        // 부정 feedback 세 자리가 각각 같은 wire 이름을 쓴다.
        assertEquals(3, Regex("\"content_signature\"").findAll(body).count())
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
        ).nextStep(sampleRequest(), trace())

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
        ).nextStep(sampleRequest(), trace())

        assertEquals(null, server.takeRequest().getHeader("X-Davi-Dev-Token"))
    }

    @Test
    fun `attaches the session token as a bearer authorization header`() {
        val token = "session-token-value"
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"action":{"expected_result":"목표 화면","evidence":"근거","action_type":"tap","target_node_id":"n1","instruction":"누르세요."},"model":"stub","verified":false,"screen_signature":"${"a".repeat(64)}"}""",
            ),
        )

        GuideApiClient(
            server.url("/").toString(),
            sessionToken = { token },
        ).nextStep(sampleRequest(), trace())

        assertEquals("Bearer $token", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `omits the authorization header when there is no valid session`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"action":{"expected_result":"목표 화면","evidence":"근거","action_type":"tap","target_node_id":"n1","instruction":"누르세요."},"model":"stub","verified":false,"screen_signature":"${"a".repeat(64)}"}""",
            ),
        )

        GuideApiClient(server.url("/").toString(), sessionToken = { null })
            .nextStep(sampleRequest(), trace())

        assertEquals(null, server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `only bearer challenge marks an auth failure as requiring login`() {
        server.enqueue(
            MockResponse().setResponseCode(401).setBody(
                """{"kind":"AUTH","message":"development access denied"}""",
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setHeader("WWW-Authenticate", "Bearer")
                .setBody("""{"kind":"AUTH","message":"session token required"}"""),
        )
        val client = GuideApiClient(server.url("/").toString())

        val developmentFailure = client.nextStep(sampleRequest(), trace())
        val sessionFailure = client.nextStep(sampleRequest(), trace(2))

        assertFalse((developmentFailure as NextStepResult.ApiFailure).sessionAuthenticationRequired)
        assertTrue((sessionFailure as NextStepResult.ApiFailure).sessionAuthenticationRequired)
    }

    /**
     * scroll은 서버가 "누를 게 없을 때" 실제로 돌려주는 액션이다.
     * 앱 enum에 이 값이 없던 시절 이 응답이 오면 파싱이 통째로 터졌다(이슈 #17).
     */
    @Test
    fun `parses a scroll action with direction`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"action":{"expected_result":"목록 아래가 보인다","evidence":"더 넘길 수 있는 목록","action_type":"scroll","direction":"up","target_node_id":"n3","instruction":"화면을 쓸어서 더 내려 보세요."},"model":"stub","verified":false,"screen_signature":"${"a".repeat(64)}"}""",
            ),
        )

        val result = GuideApiClient(server.url("/").toString()).nextStep(sampleRequest(), trace())

        assertTrue(result is NextStepResult.Success)
        result as NextStepResult.Success
        assertEquals(ActionType.SCROLL, result.response.action.actionType)
        assertEquals(SwipeDirection.UP, result.response.action.direction)
    }

    /** 도그푸딩 해시(#41)는 있을 때만 실린다 — 등록 안 된 기기는 필드 자체가 나가지 않는다. */
    @Test
    fun `sends device_id only when the dogfood hash is present`() {
        repeat(2) {
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"action":{"expected_result":"목표 화면","evidence":"근거","action_type":"tap","target_node_id":"n1","instruction":"누르세요."},"model":"stub","verified":false,"screen_signature":"${"a".repeat(64)}"}""",
                ),
            )
        }
        val client = GuideApiClient(server.url("/").toString())

        client.nextStep(sampleRequest().copy(deviceId = "ba7816bf8f01cfea"), trace())
        client.nextStep(sampleRequest(), trace(2))

        assertTrue(
            server.takeRequest().body.readUtf8().contains("\"device_id\":\"ba7816bf8f01cfea\""),
        )
        assertFalse(server.takeRequest().body.readUtf8().contains("device_id"))
    }

    /** kind 값은 서버 `ErrorKind`와 같아야 한다. 여기 쓰는 SERVER는 서버가 막혔을 때 주는 값이다. */
    @Test
    fun `parses server api errors by kind`() {
        server.enqueue(
            MockResponse().setResponseCode(502).setBody(
                """{"kind":"SERVER","message":"provider unavailable","request_id":"r1"}""",
            ),
        )

        val result = GuideApiClient(server.url("/").toString()).nextStep(sampleRequest(), trace())

        assertTrue(result is NextStepResult.ApiFailure)
        result as NextStepResult.ApiFailure
        assertEquals(502, result.statusCode)
        assertEquals(ApiErrorKind.SERVER, result.error.kind)
    }

    @Test
    fun `parses external daily rate limit scope`() {
        server.enqueue(
            MockResponse().setResponseCode(429).setBody(
                """{"kind":"QUOTA","message":"request limit exceeded","retry_after_seconds":86400,"rate_limit_scope":"daily"}""",
            ),
        )

        val result = GuideApiClient(server.url("/").toString()).nextStep(sampleRequest(), trace())

        assertTrue(result is NextStepResult.ApiFailure)
        result as NextStepResult.ApiFailure
        assertEquals(RateLimitScope.DAILY, result.error.rateLimitScope)
        assertEquals(86_400, result.error.retryAfterSeconds)
    }

    @Test
    fun `does not trust malformed or mismatched response trace headers`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader(RequestTracingContract.REQUEST_ID_HEADER, "private-untrusted-value")
                .addHeader(
                    RequestTracingContract.LOGICAL_REQUEST_ID_HEADER,
                    "00000000-0000-0000-0000-000000000000",
                )
                .addHeader(RequestTracingContract.ATTEMPT_HEADER, "01")
                .setBody(
                    """{"action":{"expected_result":"목표 화면","evidence":"근거","action_type":"tap","target_node_id":"n1","instruction":"누르세요."},"model":"stub","verified":false,"screen_signature":"${"a".repeat(64)}"}""",
                ),
        )

        val result = GuideApiClient(server.url("/").toString()).nextStep(sampleRequest(), trace())

        assertTrue(result is NextStepResult.Success)
        result as NextStepResult.Success
        assertEquals(null, result.trace.requestId)
        assertEquals(null, result.trace.logicalRequestId)
        assertEquals(null, result.trace.attempt)
    }

    @Test
    fun `returns a network failure without throwing`() {
        val client = OkHttpClient.Builder().callTimeout(1, TimeUnit.SECONDS).build()

        val result = GuideApiClient("http://127.0.0.1:1", client).nextStep(sampleRequest(), trace())

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
                    editable = true,
                    focused = true,
                    checkState = CheckState.PARTIAL,
                    bounds = listOf(1, 2, 3, 4),
                ),
            ),
            imeVisible = true,
            contentSignature = "d".repeat(64),
        ),
        steps = listOf(
            GuideStep(
                instruction = "'설정'을 눌러 주세요.",
                actionType = ActionType.TAP,
                targetLabel = "설정",
                targetNodeId = "n7",
                expectedTargetState = ExpectedTargetState(ObservableState.CHECKED, true),
                screenHint = "런처 홈 화면",
                structureSignature = "b".repeat(64),
                screenChanged = true,
                performed = true,
                contentSignature = "e".repeat(64),
            ),
            GuideStep(
                instruction = "'연결'을 눌러 주세요.",
                actionType = ActionType.TAP,
                targetLabel = "연결",
                screenHint = "설정 앱의 메뉴 목록 화면",
                structureSignature = "c".repeat(64),
            ),
        ),
        completionFeedback = CompletionFeedback(
            CompletionFeedbackStatus.USER_REJECTED,
            "f".repeat(64),
            "d".repeat(64),
        ),
        clientVersion = "0.1.0",
    )

    private fun trace(attempt: Int = 1) = LogicalRequestAttempt(TRACE_ID, attempt)

    /** C21(#405): type 안내의 input_text를 파싱한다. 필드가 없는 응답은 기본값 null이다. */
    @Test
    fun `parses input_text on a type action`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"action":{"expected_result":"검색어가 입력돼 있다","evidence":"검색","action_type":"type","target_node_id":"n1","input_text":"근처 약국","instruction":"여기에 입력하세요."},"model":"stub","verified":false,"screen_signature":"${"a".repeat(64)}"}""",
                ),
        )

        val result = GuideApiClient(server.url("/").toString()).nextStep(sampleRequest(), trace(1))

        result as NextStepResult.Success
        assertEquals("근처 약국", result.response.action.inputText)
    }

    private companion object {
        const val TRACE_ID = "123e4567-e89b-12d3-a456-426614174000"
        const val SERVER_REQUEST_ID = "0123456789abcdef0123456789abcdef"
    }
}
