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
        assertFalse(model.stopGuideVisible)
        assertFalse(model.logoVisible)
        assertNull(model.questionRes)
        assertNull(model.questionText)
        assertFalse(model.utteranceVisible)
        assertFalse(model.confirmVisible)
        assertFalse(model.closeVisible)
    }

    @Test
    fun `안내 세션 중 홈은 중지 버튼을 보이고 마이크를 잠근다`() {
        val model = renderModelOf(ConversationUiState.Home(), guideActive = true)
        assertTrue(model.stopGuideVisible)
        assertFalse(model.micEnabled)
        assertTrue(model.micVisible)
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
        assertEquals(R.dimen.davi_question_card_max_height, model.questionMaxHeightRes)
        assertNull(model.questionText)
        assertTrue(model.utteranceVisible)
        assertEquals("", model.utteranceText)
        assertTrue(model.micVisible)
        assertFalse(model.micEnabled) // 이미 듣는 중 — 누를 수 없는(deactive) 상태
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
    fun `질문 재생 중에는 마이크를 눌러 끊고 답할 수 있다`() {
        val model = renderModelOf(ConversationUiState.Speaking("누구에게 보내시겠어요?", generation = 1L))
        assertEquals("누구에게 보내시겠어요?", model.questionText)
        assertTrue(model.micVisible)
        assertTrue(model.micEnabled) // barge-in — 듣는 중(deactive)과 구분되는 지점
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
        // 승인 대상이라 발화 카드가 숨은 여유만큼 카드를 넓힌다
        assertEquals(R.dimen.davi_question_card_max_height_confirm, model.questionMaxHeightRes)
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
    fun `스스로 끝나지 않는 상태만 복귀 타이머를 갖는다`() {
        assertEquals(FAILED_RETURN_DELAY_MS, autoReturnDelayMs(ConversationUiState.Failed))
        // ×/○ 탭 말고 빠져나갈 길이 없어 화면이 계속 켜진 채 남는다
        assertEquals(CONFIRM_TIMEOUT_MS, autoReturnDelayMs(ConversationUiState.Confirm("카카오톡 열기")))
        assertNull(autoReturnDelayMs(ConversationUiState.Home()))
        assertNull(autoReturnDelayMs(ConversationUiState.Listening()))
        assertNull(autoReturnDelayMs(ConversationUiState.Speaking("누구에게?", generation = 1L)))
        assertNull(autoReturnDelayMs(ConversationUiState.Requesting("손자에게")))
    }

    @Test
    fun `Ask는 질문 재생 상태로 간다`() {
        val state = stateForOutcome(VoiceDialogueSession.Outcome.Ask(generation = 1L, message = "누구에게?"))
        assertEquals(ConversationUiState.Speaking("누구에게?", generation = 1L), state)
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

    // --- 음성 모션 (#132) --------------------------------------------------------

    @Test
    fun `파도는 사용자가 말할 때만 보인다`() {
        // 이 있음·없음 자체가 발화 주체 구분이다(시안 메인 2·4에는 없고 3·5에는 있다).
        assertTrue(renderModelOf(ConversationUiState.Listening()).waveVisible)
        assertFalse(renderModelOf(ConversationUiState.Speaking("질문", generation = 1L)).waveVisible)
        assertFalse(renderModelOf(ConversationUiState.Home()).waveVisible)
        assertFalse(renderModelOf(ConversationUiState.Requesting("발화")).waveVisible)
        assertFalse(renderModelOf(ConversationUiState.Confirm("목표")).waveVisible)
        assertFalse(renderModelOf(ConversationUiState.Failed).waveVisible)
    }

    @Test
    fun `듣는 중 글로우는 음량에 반응한다`() {
        val model = renderModelOf(ConversationUiState.Listening())
        assertTrue(model.glowVisible)
        assertTrue(model.glowReactsToVoice)
    }

    @Test
    fun `홈 유도 글로우는 음량에 반응하지 않는다`() {
        // 홈에는 들을 마이크가 없다 — 고정 주기로만 숨 쉰다.
        val model = renderModelOf(ConversationUiState.Home())
        assertTrue(model.glowVisible)
        assertFalse(model.glowReactsToVoice)
    }

    @Test
    fun `안내 세션 중 홈은 유도 글로우를 끈다`() {
        // 잠긴 마이크가 "누르세요" 하고 빛나면 거짓 유도다 — micEnabled와 같은 조건을 탄다.
        val model = renderModelOf(ConversationUiState.Home(), guideActive = true)
        assertFalse(model.micEnabled)
        assertFalse(model.glowVisible)
    }

    @Test
    fun `응답 대기에서만 로딩 점이 보인다`() {
        assertTrue(renderModelOf(ConversationUiState.Requesting("발화")).loadingDotsVisible)
        assertFalse(renderModelOf(ConversationUiState.Home()).loadingDotsVisible)
        assertFalse(renderModelOf(ConversationUiState.Listening()).loadingDotsVisible)
        assertFalse(renderModelOf(ConversationUiState.Speaking("질문", generation = 1L)).loadingDotsVisible)
        assertFalse(renderModelOf(ConversationUiState.Confirm("목표")).loadingDotsVisible)
        assertFalse(renderModelOf(ConversationUiState.Failed).loadingDotsVisible)
    }

    @Test
    fun `응답 대기는 잠시만요 문구와 점을 함께 보인다`() {
        // 시안(메인 6)은 문구를 점으로 대체하지 않는다.
        val model = renderModelOf(ConversationUiState.Requesting("발화"))
        assertEquals(R.string.conversation_requesting, model.questionRes)
        assertTrue(model.loadingDotsVisible)
    }

    @Test
    fun `다비가 말하는 중에는 파도도 글로우도 없다`() {
        val model = renderModelOf(ConversationUiState.Speaking("질문", generation = 1L))
        assertFalse(model.waveVisible)
        assertFalse(model.glowVisible)
    }
}
