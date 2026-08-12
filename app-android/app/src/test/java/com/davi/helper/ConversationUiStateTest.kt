package com.davi.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 대화 화면 렌더 규칙의 고정 — 상태별로 무엇이 보이는지와 Outcome→상태 매핑(#123).
 * 특히 LimitReached가 텍스트 폴백이 아니라 실패 화면으로 가는 결정을 여기서 지킨다.
 */
class ConversationUiStateTest {

    @Test
    fun `홈은 제목과 활성 마이크만 보인다`() {
        val model = renderModelOf(ConversationUiState.Home())
        assertTrue(model.homeVisible)
        assertTrue(model.micVisible)
        assertTrue(model.micEnabled)
        assertNull(model.statusRes)
        assertFalse(model.logoVisible)
        assertNull(model.questionRes)
        assertNull(model.questionText)
        assertFalse(model.utteranceVisible)
        assertFalse(model.confirmVisible)
        assertFalse(model.closeVisible)
    }

    @Test
    fun `홈의 에러 문구는 그대로 전달된다`() {
        val model = renderModelOf(ConversationUiState.Home(statusRes = R.string.voice_no_speech))
        assertEquals(R.string.voice_no_speech, model.statusRes)
    }

    @Test
    fun `첫 청취는 초기 프롬프트를 띄운다`() {
        val model = renderModelOf(ConversationUiState.Listening())
        assertEquals(R.string.conversation_prompt_initial, model.questionRes)
        assertNull(model.questionText)
        assertTrue(model.utteranceVisible)
        assertEquals("", model.utteranceText)
        assertTrue(model.micVisible)
        assertFalse(model.micEnabled) // 이미 듣는 중 — 조작 불필요
        assertTrue(model.closeVisible)
    }

    @Test
    fun `핑퐁 청취는 서버 질문을 유지하고 부분 인식을 흘린다`() {
        val model = renderModelOf(
            ConversationUiState.Listening(question = "누구에게 보내시겠어요?", partial = "손자에게"),
        )
        assertNull(model.questionRes)
        assertEquals("누구에게 보내시겠어요?", model.questionText)
        assertEquals("손자에게", model.utteranceText)
    }

    @Test
    fun `질문 재생 중에는 마이크가 비활성이다`() {
        val model = renderModelOf(ConversationUiState.Speaking("누구에게 보내시겠어요?"))
        assertEquals("누구에게 보내시겠어요?", model.questionText)
        assertTrue(model.micVisible)
        assertFalse(model.micEnabled)
        assertTrue(model.utteranceVisible)
    }

    @Test
    fun `응답 대기는 잠시만요와 방금 발화를 함께 보여준다`() {
        val model = renderModelOf(ConversationUiState.Requesting("손자에게 보내줘"))
        assertEquals(R.string.conversation_requesting, model.questionRes)
        assertEquals("손자에게 보내줘", model.utteranceText)
        assertTrue(model.utteranceVisible)
        assertFalse(model.micEnabled)
    }

    @Test
    fun `최종 확인은 goal과 확인쌍만 보이고 마이크와 닫기는 숨긴다`() {
        val model = renderModelOf(ConversationUiState.Confirm("카카오톡으로 손자에게 사진 보내기"))
        assertEquals("카카오톡으로 손자에게 사진 보내기", model.questionText)
        assertTrue(model.confirmVisible)
        assertFalse(model.micVisible)
        assertFalse(model.closeVisible)
        assertFalse(model.utteranceVisible)
    }

    @Test
    fun `실패 화면은 실패 문구 카드만 보인다`() {
        val model = renderModelOf(ConversationUiState.Failed)
        assertEquals(R.string.conversation_failed, model.questionRes)
        assertTrue(model.logoVisible)
        assertFalse(model.micVisible)
        assertFalse(model.confirmVisible)
        assertFalse(model.closeVisible)
        assertFalse(model.utteranceVisible)
    }

    @Test
    fun `Ask는 질문 재생 상태로 간다`() {
        val state = stateForOutcome(VoiceDialogueSession.Outcome.Ask(generation = 1L, message = "누구에게?"))
        assertEquals(ConversationUiState.Speaking("누구에게?"), state)
    }

    @Test
    fun `Ready는 최종 확인으로 간다`() {
        val state = stateForOutcome(
            VoiceDialogueSession.Outcome.Ready(generation = 1L, message = "정리했어요", goal = "카카오톡 열기"),
        )
        assertEquals(ConversationUiState.Confirm("카카오톡 열기"), state)
    }

    @Test
    fun `상한 초과와 잘못된 응답은 실패 화면으로 간다`() {
        assertEquals(
            ConversationUiState.Failed,
            stateForOutcome(VoiceDialogueSession.Outcome.LimitReached),
        )
        assertEquals(
            ConversationUiState.Failed,
            stateForOutcome(VoiceDialogueSession.Outcome.InvalidResponse),
        )
    }
}
