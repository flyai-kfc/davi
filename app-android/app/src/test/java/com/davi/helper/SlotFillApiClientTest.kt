package com.davi.helper

import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SlotFillApiClientTest {
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
    fun `posts current utterance and completed history then parses question`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader(RequestTracingContract.REQUEST_ID_HEADER, SERVER_REQUEST_ID)
                .addHeader(RequestTracingContract.LOGICAL_REQUEST_ID_HEADER, TRACE_ID)
                .addHeader(RequestTracingContract.ATTEMPT_HEADER, "1")
                .setBody(
                    """{"status":"needs_clarification","message":"어느 앱에서 할까요?","goal":null,"clarification":{"questions":[{"id":"q1","prompt":"어느 앱에서 할까요?","options":["카카오톡","문자"],"allow_freeform":true}]}}""",
                ),
        )

        val result = SlotFillApiClient(server.url("/").toString()).resolve(sampleRequest(), trace())

        assertTrue(result is SlotFillResult.Success)
        result as SlotFillResult.Success
        assertEquals(IntentDialogueStatus.NEEDS_CLARIFICATION, result.state.status)
        assertNull(result.state.goal)
        assertEquals(SERVER_REQUEST_ID, result.trace.requestId)
        assertEquals(TRACE_ID, result.trace.logicalRequestId)

        val recorded = server.takeRequest()
        assertEquals("/v1/intent/slot-fill", recorded.path)
        assertEquals(TRACE_ID, recorded.getHeader(RequestTracingContract.LOGICAL_REQUEST_ID_HEADER))
        val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals("보호자", body["utterance"]?.jsonPrimitive?.content)
        assertEquals(SESSION_ID, body["session_id"]?.jsonPrimitive?.content)
        assertEquals(2, body["conversation_history"]?.jsonArray?.size)
        assertEquals(
            "사진을 보내고 싶어",
            body["conversation_history"]
                ?.jsonArray
                ?.first()
                ?.jsonObject
                ?.get("content")
                ?.jsonPrimitive
                ?.content,
        )
    }

    @Test
    fun `parses ready goal without rewriting it`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"status":"ready","message":"목표를 정리했어요.","goal":"메신저에서 보호자에게 사진 보내기"}""",
            ),
        )

        val result = SlotFillApiClient(server.url("/").toString()).resolve(sampleRequest(), trace())

        assertTrue(result is SlotFillResult.Success)
        result as SlotFillResult.Success
        assertEquals("메신저에서 보호자에게 사진 보내기", result.state.goal)
    }

    @Test
    fun `accepts clarification question without suggested options`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"status":"needs_clarification","message":"어떻게 하고 싶으세요?","clarification":{"questions":[{"id":"q1","prompt":"어떻게 하고 싶으세요?","options":[],"allow_freeform":true}]}}""",
            ),
        )

        val result = SlotFillApiClient(server.url("/").toString()).resolve(sampleRequest(), trace())

        assertTrue(result is SlotFillResult.Success)
        result as SlotFillResult.Success
        assertTrue(result.state.clarification?.questions?.single()?.options?.isEmpty() == true)
    }

    @Test
    fun `rejects status and goal combinations that drift from server contract`() {
        val invalidBodies = listOf(
            """{"status":"needs_clarification","message":"질문","goal":"잘못된 목표"}""",
            """{"status":"needs_clarification","message":"질문","goal":null}""",
            """{"status":"ready","message":"완료","goal":null}""",
            """{"status":"ready","message":"   ","goal":"목표"}""",
            """{"status":"unable_to_finalize","message":"다시 말해 주세요","goal":"추측"}""",
            """{"status":"needs_clarification","message":"다른 질문","clarification":{"questions":[{"id":"q1","prompt":"첫 질문","options":["보기"],"allow_freeform":true}]}}""",
            """{"status":"needs_clarification","message":"질문","clarification":{"questions":[{"id":"q1","prompt":"질문","options":["1","2","3","4"],"allow_freeform":true}]}}""",
        )
        invalidBodies.forEach {
            server.enqueue(MockResponse().setResponseCode(200).setBody(it))
        }
        val client = SlotFillApiClient(server.url("/").toString())

        repeat(invalidBodies.size) {
            assertTrue(client.resolve(sampleRequest(), trace()) is SlotFillResult.ParseFailure)
        }
    }

    @Test
    fun `parses existing api error and development token`() {
        val token = "t".repeat(32)
        server.enqueue(
            MockResponse().setResponseCode(429).setBody(
                """{"kind":"QUOTA","message":"rate limited","retry_after_seconds":17,"request_id":"r1"}""",
            ),
        )

        val result = SlotFillApiClient(
            server.url("/").toString(),
            devAccessToken = token,
        ).resolve(sampleRequest(), trace(2))

        assertTrue(result is SlotFillResult.ApiFailure)
        result as SlotFillResult.ApiFailure
        assertEquals(ApiErrorKind.QUOTA, result.error.kind)
        assertEquals(17, result.error.retryAfterSeconds)
        val recorded = server.takeRequest()
        assertEquals(token, recorded.getHeader("X-Davi-Dev-Token"))
        assertEquals("2", recorded.getHeader(RequestTracingContract.ATTEMPT_HEADER))
    }

    @Test
    fun `reads and attaches the current session token on every request`() {
        repeat(2) {
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"status":"unable_to_finalize","message":"처음부터 다시 말씀해 주세요."}""",
                ),
            )
        }
        var token = "first-session"
        val client = SlotFillApiClient(
            server.url("/").toString(),
            sessionToken = { token },
        )

        client.resolve(sampleRequest(), trace())
        token = "refreshed-session"
        client.resolve(sampleRequest(), trace(2))

        assertEquals("Bearer first-session", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer refreshed-session", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `recognizes the server session challenge`() {
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setHeader("WWW-Authenticate", "Bearer")
                .setBody("""{"kind":"AUTH","message":"session token required"}"""),
        )

        val result = SlotFillApiClient(server.url("/").toString())
            .resolve(sampleRequest(), trace()) as SlotFillResult.ApiFailure

        assertTrue(result.sessionAuthenticationRequired)
    }

    @Test
    fun `serializes locally collected clarification answers on final request`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"status":"unable_to_finalize","message":"처음부터 다시 말씀해 주세요."}""",
            ),
        )
        val request = sampleRequest().copy(
            clarificationAnswers = listOf(
                ClarificationAnswer(
                    questionId = "q1",
                    prompt = "어느 앱에서 할까요?",
                    answer = "문자",
                    source = ClarificationAnswerSource.OPTION,
                ),
            ),
        )

        val result = SlotFillApiClient(server.url("/").toString()).resolve(request, trace())

        assertTrue(result is SlotFillResult.Success)
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val answer = body["clarification_answers"]?.jsonArray?.single()?.jsonObject
        assertEquals("q1", answer?.get("question_id")?.jsonPrimitive?.content)
        assertEquals("option", answer?.get("source")?.jsonPrimitive?.content)
    }

    @Test
    fun `returns network failure without logging or throwing`() {
        val httpClient = OkHttpClient.Builder().callTimeout(1, TimeUnit.SECONDS).build()

        val result = SlotFillApiClient(
            "http://127.0.0.1:1",
            httpClient,
        ).resolve(sampleRequest(), trace())

        assertTrue(result is SlotFillResult.NetworkFailure)
    }

    private fun sampleRequest() = SlotFillRequest(
        utterance = "보호자",
        sessionId = SESSION_ID,
        conversationHistory = listOf(
            ConversationTurn(ConversationRole.USER, "사진을 보내고 싶어"),
            ConversationTurn(ConversationRole.ASSISTANT, "누구에게 보낼까요?"),
        ),
    )

    private fun trace(attempt: Int = 1) = LogicalRequestAttempt(TRACE_ID, attempt)

    private companion object {
        const val TRACE_ID = "123e4567-e89b-12d3-a456-426614174000"
        const val SERVER_REQUEST_ID = "0123456789abcdef0123456789abcdef"
        const val SESSION_ID = "00000000-0000-4000-8000-000000000001"
    }
}
