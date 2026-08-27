package com.davi.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 안내 낭독 그물의 상한(#450, PR #461 재리뷰 2).
 *
 * 지키려는 것은 **공식을 물려받은 자리에서 상한의 근거까지 물려받았다고 착각하지 않는 것**이다.
 * [speechGuardMillis]의 「상한이 무한이 아닌 근거는 계약에 있다」는 구체화 질문
 * ([SlotFillApiClient.MAX_QUESTION_PROMPT_LENGTH])의 이야기이고, 안내문에는 그 계약이 없다.
 */
class GuideSpeechGuardTest {

    /** 실사용 길이대(20~60자)에서는 공식 그대로다 — 상한이 정상 동작을 건드리지 않는다. */
    @Test
    fun `실사용 길이에서는 공식 그대로다`() {
        for (length in listOf(0, 21, 45, 60)) {
            assertEquals(speechGuardMillis(length), guideSpeechGuardMillis(length))
        }
    }

    /** 계약 길이가 곧 상한이다. 숫자를 새로 지어내지 않았다는 것이 이 대조다. */
    @Test
    fun `계약 길이를 넘으면 그 길이의 값에서 끊는다`() {
        val cap = speechGuardMillis(SlotFillApiClient.MAX_QUESTION_PROMPT_LENGTH)
        assertEquals(cap, guideSpeechGuardMillis(SlotFillApiClient.MAX_QUESTION_PROMPT_LENGTH))
        assertEquals(cap, guideSpeechGuardMillis(500))
        assertEquals(cap, guideSpeechGuardMillis(10_000))
    }

    /**
     * 상한이 없었다면 500자가 153초였다 — 그동안 상대 앱이 낮아진 채다. 그 자리를 못 박는다.
     */
    @Test
    fun `상한이 없을 때보다 짧다`() {
        assertTrue(guideSpeechGuardMillis(500) < speechGuardMillis(500))
    }
}
