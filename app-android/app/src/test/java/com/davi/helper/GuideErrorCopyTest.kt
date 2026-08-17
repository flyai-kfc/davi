package com.davi.helper

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 안내 중 실패 → 문구 대응(#29).
 *
 * 지키려는 것은 **"같은 원인은 어느 화면에서도 같은 말"** 이다. 버킷을 [bucketOf] 하나로
 * 모은 이유가 그것이고, 여기서는 6종 → 3버킷과 실패 variant 4종의 대응을 고정한다.
 */
class GuideErrorCopyTest {

    private val trace = ServerResponseTrace(requestId = null, logicalRequestId = null, attempt = null)

    private fun apiFailure(kind: ApiErrorKind) = NextStepResult.ApiFailure(
        statusCode = 500,
        error = ApiError(kind = kind, message = "디버그용 상세"),
        trace = trace,
    )

    @Test
    fun `사용자가 볼 수 있는 원인만 갈라 낸다`() {
        assertEquals(ApiErrorBucket.NETWORK, bucketOf(ApiErrorKind.NETWORK))
        assertEquals(ApiErrorBucket.BUSY, bucketOf(ApiErrorKind.QUOTA))
    }

    /** 원인은 넷이지만 어르신이 할 일은 "기다린다" 하나라 한 문구로 묶는다. */
    @Test
    fun `서버 문제 네 종류는 한 버킷이다`() {
        listOf(ApiErrorKind.AUTH, ApiErrorKind.SERVER, ApiErrorKind.PARSE, ApiErrorKind.BAD_REQUEST)
            .forEach { assertEquals(ApiErrorBucket.SERVER, bucketOf(it)) }
    }

    @Test
    fun `버킷마다 다른 문구가 나온다`() {
        assertEquals(R.string.guide_error_network, GuideErrorCopy.messageRes(ApiErrorBucket.NETWORK))
        assertEquals(R.string.guide_error_busy, GuideErrorCopy.messageRes(ApiErrorBucket.BUSY))
        assertEquals(R.string.guide_error_server, GuideErrorCopy.messageRes(ApiErrorBucket.SERVER))
    }

    @Test
    fun `ApiFailure는 kind를 따라간다`() {
        assertEquals(R.string.guide_error_network, GuideErrorCopy.messageRes(apiFailure(ApiErrorKind.NETWORK)))
        assertEquals(R.string.guide_error_busy, GuideErrorCopy.messageRes(apiFailure(ApiErrorKind.QUOTA)))
        assertEquals(R.string.guide_error_server, GuideErrorCopy.messageRes(apiFailure(ApiErrorKind.AUTH)))
    }

    /** 연결이 끊긴 것은 사용자가 볼 수 있는 유일한 원인이라 따로 말한다. */
    @Test
    fun `NetworkFailure는 인터넷 문구다`() {
        assertEquals(
            R.string.guide_error_network,
            GuideErrorCopy.messageRes(NextStepResult.NetworkFailure(IOException("끊김"))),
        )
    }

    /** 200 밖의 코드도, 계약과 다른 body도 사용자가 할 수 있는 일은 없다. */
    @Test
    fun `HttpFailure와 ParseFailure는 서버 문구다`() {
        assertEquals(
            R.string.guide_error_server,
            GuideErrorCopy.messageRes(NextStepResult.HttpFailure(statusCode = 503, trace = trace)),
        )
        assertEquals(
            R.string.guide_error_server,
            GuideErrorCopy.messageRes(NextStepResult.ParseFailure(IllegalStateException("계약 불일치"))),
        )
    }

    /** 성공에는 알릴 것이 없다 — 호출부가 이 null로 "띄우지 않음"을 판단한다. */
    @Test
    fun `성공이면 문구가 없다`() {
        val response = NextStepResponse(
            action = GuideAction(
                expectedResult = "시계 앱이 열려 있다",
                evidence = "시계",
                actionType = ActionType.DONE,
                instruction = "다 되었어요",
            ),
            model = "stub",
            screenSignature = "sig",
        )
        assertNull(GuideErrorCopy.messageRes(NextStepResult.Success(response, trace)))
    }
}
