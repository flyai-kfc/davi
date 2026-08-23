package com.davi.helper

import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 대화 화면 렌더 규칙의 고정 — 상태별로 무엇이 보이는지와 Outcome→상태 매핑(#123·#316).
 */
class ConversationUiStateTest {

    private fun clarificationOutcome(
        questionIndex: Int = 0,
        questionCount: Int = 1,
        options: List<String> = listOf("가족"),
    ) =
        VoiceDialogueSession.Outcome.Clarify(
            generation = 1L,
            question = ClarificationQuestion("q${questionIndex + 1}", "누구에게?", options, true),
            questionIndex = questionIndex,
            questionCount = questionCount,
        )

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

    /**
     * 안내를 받다가 실수로 앱에 들어온 상황(메인 9, #197). 세 갈래 중 무엇을 하려는지
     * 물어야 한다 — 기존 안내 계속 · 종료 · 새로 물어보기.
     */
    @Test
    fun `안내 중이면 홈이 아니라 안내 중 화면이다`() {
        val model = renderModelOf(ConversationUiState.Home(), guideActive = true)
        assertTrue(model.guidingVisible)
        assertEquals(R.string.conversation_guiding_title, model.questionRes)
        assertFalse("홈 제목이 뜨면 안 된다", model.homeVisible)
        assertFalse(model.stopConfirmVisible)
    }

    @Test
    fun `안내 중에는 마이크와 하단 모션이 없다`() {
        // 안내가 도는 동안은 새 목표를 받지 않는다. 카드 안 지시문이 그 이유를 말하므로
        // 잠긴 마이크를 보여 줄 필요가 없다 — 예전에는 눌리지 않는 마이크가 남아 있었다.
        val model = renderModelOf(ConversationUiState.Home(), guideActive = true)
        assertFalse(model.micVisible)
        assertFalse(model.waveVisible)
        assertFalse(model.glowVisible)
    }

    /** 안내 중 화면은 직전 시도의 오류를 끌고 오지 않는다 — 지금 할 일이 완전히 다르다. */
    @Test
    fun `안내 중이면 직전 오류 문구를 남기지 않는다`() {
        val model = renderModelOf(
            ConversationUiState.Home(statusRes = R.string.voice_no_speech),
            guideActive = true,
        )
        assertNull(model.statusRes)
    }

    @Test
    fun `종료 확인은 안내 중 화면 위에 모달로 덮인다`() {
        // 시안 메인 10 — 뒤 화면이 그대로 보이고 그 위에 스크림과 다이얼로그가 얹힌다.
        val model = renderModelOf(ConversationUiState.StopConfirm, guideActive = true)
        assertTrue(model.stopConfirmVisible)
        assertTrue("뒤 화면은 메인 9 그대로다", model.guidingVisible)
        assertEquals(R.string.conversation_guiding_title, model.questionRes)
    }

    @Test
    fun `세션이 밖에서 끝나면 종료 확인 모달이 남지 않는다`() {
        // 안내는 밖에서 끝난다(사용자가 대상 앱에서 완료 · 권한 소실 · 다른 종료 경로).
        // 끝낼 안내가 없는데 "정말 종료할까요?"가 남으면, "안내 중"을 상태로 두지 않은
        // 이유(유령 화면)를 스스로 어긴다.
        val model = renderModelOf(ConversationUiState.StopConfirm, guideActive = false)
        assertFalse(model.stopConfirmVisible)
        assertFalse(model.guidingVisible)
        assertTrue("끝났으면 평범한 홈이다", model.homeVisible)
        assertTrue(model.micEnabled)
    }

    @Test
    fun `종료 확인에는 자동 복귀 타이머가 없다`() {
        // 뒤에서 안내가 계속 돌고 있어 방치돼도 갇히지 않는다 — 최종 확인과 다른 점이다.
        assertNull(autoReturnDelayMs(ConversationUiState.StopConfirm))
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
    fun `구체화 직접 발화는 서버 질문을 유지하고 부분 인식을 흘린다`() {
        val model = renderModelOf(
            ConversationUiState.Listening(
                question = "누구에게 보내시겠어요?",
                partial = "손자에게",
                clarification = clarificationOutcome(),
            ),
        )
        assertNull(model.questionRes)
        assertEquals("누구에게 보내시겠어요?", model.questionText)
        assertEquals("손자에게", model.utteranceText)
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
    fun `앱 실행 가능성 미확인은 원래 goal을 보존하고 경고와 확인쌍을 보인다`() {
        val state = ConversationUiState.Confirm(
            goal = "손자에게 사진 보내기",
            kind = ConfirmKind.APP_AVAILABILITY_WARNING,
        )
        val model = renderModelOf(state)

        assertEquals("손자에게 사진 보내기", state.goal)
        assertEquals(R.string.conversation_app_availability_warning, model.questionRes)
        assertNull(model.questionText)
        assertTrue(model.confirmVisible)
        assertEquals(R.string.conversation_confirm_continue, model.confirmAcceptTextRes)
    }

    @Test
    fun `앱 가능성 경고 계속은 원래 goal 최종 확인을 필수로 거친다`() {
        val warning = ConversationUiState.Confirm(
            goal = "손자에게 사진 보내기",
            kind = ConfirmKind.APP_AVAILABILITY_WARNING,
        )

        val goalConfirmation = requireNotNull(followUpConfirmationAfterAccept(warning))

        assertEquals(ConversationUiState.Confirm("손자에게 사진 보내기"), goalConfirmation)
        assertNull(followUpConfirmationAfterAccept(goalConfirmation))
        assertEquals(
            R.string.conversation_confirm_accept,
            renderModelOf(goalConfirmation).confirmAcceptTextRes,
        )
    }

    @Test
    fun `실패 화면은 캐릭터와 문구 카드만 보인다`() {
        val model = renderModelOf(ConversationUiState.Failed)
        assertEquals(R.string.conversation_failed, model.questionRes)
        assertFalse(model.micVisible)
        assertFalse(model.confirmVisible)
        assertFalse(model.closeVisible)
        assertFalse(model.utteranceVisible)
    }

    @Test
    fun `실패 화면만 배지 대신 전신 캐릭터가 선다`() {
        // 메인 8은 이 화면에서만 구도가 다르다 — 캐릭터가 위를 차지하고 카드가 그 하단을
        // 파고든다. 둘이 함께 뜨면 배지가 캐릭터 위에 겹쳐 그려진다.
        val 실패 = renderModelOf(ConversationUiState.Failed)
        assertTrue(실패.standVisible)
        assertFalse(실패.logoVisible)

        for (state in listOf(
            ConversationUiState.Home(),
            ConversationUiState.Listening(),
            ConversationUiState.Clarifying(clarificationOutcome()),
            ConversationUiState.Requesting("발화"),
            ConversationUiState.Confirm("목표"),
        )) {
            assertFalse("$state 에는 캐릭터가 없다", renderModelOf(state).standVisible)
        }
    }

    @Test
    fun `실패 문구도 다비가 하는 말이라 카드가 빛난다`() {
        // 시안이 이 카드를 state=speaking으로 뒀다 — "글로우가 화자를 가리킨다"(#176)와
        // 같은 결이다. 대기로 착각해 빼면 안 된다.
        assertTrue(renderModelOf(ConversationUiState.Failed).cardGlowVisible)
    }

    @Test
    fun `스스로 끝나지 않는 상태만 복귀 타이머를 갖는다`() {
        assertEquals(FAILED_RETURN_DELAY_MS, autoReturnDelayMs(ConversationUiState.Failed))
        // ×/○ 탭 말고 빠져나갈 길이 없어 화면이 계속 켜진 채 남는다
        assertEquals(CONFIRM_TIMEOUT_MS, autoReturnDelayMs(ConversationUiState.Confirm("카카오톡 열기")))
        assertNull(autoReturnDelayMs(ConversationUiState.Home()))
        assertNull(autoReturnDelayMs(ConversationUiState.Listening()))
        assertNull(autoReturnDelayMs(ConversationUiState.Clarifying(clarificationOutcome())))
        assertNull(autoReturnDelayMs(ConversationUiState.Requesting("손자에게")))
    }

    @Test
    fun `Clarify는 보기 선택 상태로 간다`() {
        val outcome = clarificationOutcome()
        val state = stateForOutcome(outcome)
        assertEquals(ConversationUiState.Clarifying(outcome), state)
        val model = renderModelOf(state)
        assertTrue(model.clarificationVisible)
        // **보기가 있어도 나가는 길을 둔다**(2026-08-23 시안 재작업 · #374). 종전 근거는
        // 「보기 있는 질문에서는 문답 중 취소를 두지 않는다」였는데, 시안이 이 화면에도
        // 종료를 그렸다 — 나가는 길이 화면마다 있고 없고가 갈리면 어르신이 매번 찾는다.
        assertTrue("보기가 있어도 나가는 길은 있다", model.closeVisible)
    }

    @Test
    fun `빈 보기 구체화는 음성 실패 반복을 끝낼 닫기 버튼을 보인다`() {
        val outcome = clarificationOutcome(options = emptyList())

        assertTrue(renderModelOf(ConversationUiState.Clarifying(outcome)).closeVisible)
        assertTrue(
            renderModelOf(
                ConversationUiState.Listening(
                    question = outcome.question.prompt,
                    clarification = outcome,
                ),
            ).closeVisible,
        )
    }

    @Test
    fun `Ready는 최종 확인으로 간다`() {
        val state = stateForOutcome(
            VoiceDialogueSession.Outcome.Ready(generation = 1L, message = "정리했어요", goal = "카카오톡 열기"),
        )
        assertEquals(ConversationUiState.Confirm("카카오톡 열기"), state)
    }

    @Test
    fun `안전 종료와 잘못된 응답은 서로 다른 종료 화면으로 간다`() {
        assertEquals(
            ConversationUiState.SafeEnd("처음부터 다시 말씀해 주세요."),
            stateForOutcome(
                VoiceDialogueSession.Outcome.UnableToFinalize("처음부터 다시 말씀해 주세요."),
            ),
        )
        assertEquals(
            ConversationUiState.Failed,
            stateForOutcome(VoiceDialogueSession.Outcome.InvalidResponse),
        )
    }

    @Test
    fun `최종 요청에는 취소 버튼이 없다`() {
        // **청취에는 보기 유무와 무관하게 ×를 둔다**(2026-08-23 · #374). 종전에는 닫힌 선택
        // 질문에서 넘어온 청취에만 걷었는데, 그러면 「직접 말하기」를 누르는 순간 ×가 사라져
        // **어르신 눈에는 나가는 길이 없어진다** — 인식이 반복 실패하면 갇힌다.
        assertTrue(
            renderModelOf(
                ConversationUiState.Listening(
                    question = "누구에게?",
                    clarification = clarificationOutcome(),
                ),
            ).closeVisible,
        )
        // 최종 요청은 다르다 — 서버로 이미 보낸 뒤라 취소할 것이 없다.
        val finalizing = renderModelOf(ConversationUiState.Requesting("", finalizing = true))
        assertFalse(finalizing.closeVisible)
        assertFalse("빈 시니어 카드를 남기지 않는다", finalizing.utteranceVisible)
        assertFalse(finalizing.micVisible)
    }

    @Test
    fun `구체화 질문은 첫 문항만 읽는다`() {
        assertTrue(shouldSpeakClarification(clarificationOutcome(questionIndex = 0, questionCount = 2)))
        assertFalse(shouldSpeakClarification(clarificationOutcome(questionIndex = 1, questionCount = 2)))
    }

    @Test
    fun `구체화 직접 발화는 무발화 계열 오류에서만 질문으로 돌아간다`() {
        assertTrue(canResumeClarificationAfterSpeechError(SpeechRecognizer.ERROR_NO_MATCH))
        assertTrue(canResumeClarificationAfterSpeechError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT))
        assertFalse(
            canResumeClarificationAfterSpeechError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS),
        )
        assertFalse(canResumeClarificationAfterSpeechError(SpeechRecognizerHelper.ERROR_UNAVAILABLE))
        assertFalse(canResumeClarificationAfterSpeechError(SpeechRecognizerHelper.ERROR_START_FAILED))
        assertFalse(canResumeClarificationAfterSpeechError(SpeechRecognizer.ERROR_NETWORK))
    }

    // --- 음성 모션 (#132) --------------------------------------------------------

    @Test
    fun `파도는 사용자가 말할 때만 보인다`() {
        // 이 있음·없음 자체가 발화 주체 구분이다(시안 메인 2·4에는 없고 3·5에는 있다).
        assertTrue(renderModelOf(ConversationUiState.Listening(micReady = true)).waveVisible)
        assertFalse(renderModelOf(ConversationUiState.Clarifying(clarificationOutcome())).waveVisible)
        assertFalse(renderModelOf(ConversationUiState.Home()).waveVisible)
        assertFalse(renderModelOf(ConversationUiState.Requesting("발화")).waveVisible)
        assertFalse(renderModelOf(ConversationUiState.Confirm("목표")).waveVisible)
        assertFalse(renderModelOf(ConversationUiState.Failed).waveVisible)
    }

    @Test
    fun `듣는 중 글로우는 음량에 반응한다`() {
        val model = renderModelOf(ConversationUiState.Listening(micReady = true))
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
        assertFalse(renderModelOf(ConversationUiState.Clarifying(clarificationOutcome())).loadingDotsVisible)
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
    fun `첫 구체화 질문을 읽는 중에는 하단 파도와 마이크 글로우가 없다`() {
        val model = renderModelOf(ConversationUiState.Clarifying(clarificationOutcome(), speaking = true))
        assertFalse(model.waveVisible)
        assertFalse(model.glowVisible)
        assertTrue(model.cardGlowVisible)
    }

    @Test
    fun `듣고 있어요 레이블은 듣는 중에만 보인다`() {
        // 시안(메인 2~5)에서 이 레이블은 **사용자 차례라는 신호**다. 다비가 말하거나
        // 서버를 기다리는 동안 남아 있으면 "아직 내 차례"로 잘못 읽힌다.
        assertTrue(renderModelOf(ConversationUiState.Listening(micReady = true)).utteranceLabelVisible)
        assertFalse(renderModelOf(ConversationUiState.Home()).utteranceLabelVisible)
        assertFalse(
            renderModelOf(ConversationUiState.Clarifying(clarificationOutcome())).utteranceLabelVisible,
        )
        assertFalse(renderModelOf(ConversationUiState.Requesting("발화")).utteranceLabelVisible)
        assertFalse(renderModelOf(ConversationUiState.Confirm("목표")).utteranceLabelVisible)
        assertFalse(renderModelOf(ConversationUiState.Failed).utteranceLabelVisible)
    }

    @Test
    fun `마이크가 열리기 전에는 말하라는 신호 셋이 함께 꺼져 있다`() {
        // 인식기가 붙는 동안(실기기 약 1초) 화면이 먼저 "듣고 있어요"라고 하면 그 말을 믿고
        // 바로 말한 첫 음절이 잘린다(#273). 셋은 하나의 지시라 따로 놀면 안 된다 —
        // 파도만 먼저 돌아도 사용자는 말하기 시작한다.
        val 준비중 = renderModelOf(ConversationUiState.Listening())
        assertFalse(준비중.utteranceLabelVisible)
        assertFalse(준비중.waveVisible)
        assertFalse(준비중.glowVisible)
        assertFalse(준비중.glowReactsToVoice)
    }

    @Test
    fun `준비 중 흐림은 신호 셋과 정확히 반대로 움직인다`() {
        // #277의 빈 0.9초를 채우는 흐림이다. 셋과 **같은 축이 아니다** — 셋은 "이제
        // 말하세요"이고 이것은 "아직 아니다"라, 같이 켜지면 화면이 두 말을 한꺼번에 한다.
        // 셋 중 하나라도 흐림과 같은 방향으로 붙으면 위 #273 계약이 소리 없이 깨진다.
        val 준비중 = renderModelOf(ConversationUiState.Listening())
        assertTrue(준비중.signalsDimmed)

        val 듣는중 = renderModelOf(ConversationUiState.Listening(micReady = true))
        assertFalse(듣는중.signalsDimmed)
        assertTrue(듣는중.waveVisible)

        // 듣는 상태가 아니면 걷혀 있다 — 다비가 말하는 동안 화면이 흐리면 안 된다.
        assertFalse(renderModelOf(ConversationUiState.Home()).signalsDimmed)
        assertFalse(
            renderModelOf(ConversationUiState.Clarifying(clarificationOutcome())).signalsDimmed,
        )
        assertFalse(renderModelOf(ConversationUiState.Requesting("발화")).signalsDimmed)
        assertFalse(renderModelOf(ConversationUiState.Confirm("목표")).signalsDimmed)
        assertFalse(renderModelOf(ConversationUiState.Failed).signalsDimmed)
    }

    @Test
    fun `마이크가 열려도 카드와 배지는 그대로다`() {
        // 준비 구간에서 바뀌는 것은 신호 셋뿐이다. 레이아웃까지 흔들리면 어긋남을 고치려다
        // 화면이 덜컹거린다 — 어르신에게는 그 흔들림이 더 큰 방해다.
        val 준비중 = renderModelOf(ConversationUiState.Listening(question = "누구에게 보내시겠어요?"))
        val 듣는중 = renderModelOf(
            ConversationUiState.Listening(question = "누구에게 보내시겠어요?", micReady = true),
        )
        assertEquals(듣는중.questionText, 준비중.questionText)
        assertEquals(듣는중.logoVisible, 준비중.logoVisible)
        assertEquals(듣는중.utteranceVisible, 준비중.utteranceVisible)
        assertEquals(듣는중.cardQuestionFlexible, 준비중.cardQuestionFlexible)
        assertEquals(듣는중.closeVisible, 준비중.closeVisible)
        assertEquals(듣는중.micVisible, 준비중.micVisible)
        assertEquals(듣는중.micEnabled, 준비중.micEnabled)
        // 카드 그림자도 유지한다 — 이것까지 켜고 끄면 같은 덜컹임이 된다.
        assertEquals(듣는중.utteranceSpeaking, 준비중.utteranceSpeaking)
    }

    @Test
    fun `받아쓰기 전에도 발화 카드는 자리를 지킨다`() {
        // 문장이 비어 있는 구간(메인 2·4)이다. 카드가 접히면 마이크를 누른 직후 화면이
        // 크게 흔들린다. **레이블은 여기서 판단하지 않는다** — 시안 상태는 micReady=true
        // 쪽이고, 기본값(준비 중)에서 레이블이 꺼지는 것은 위 #273 테스트가 따로 고정한다.
        val model = renderModelOf(ConversationUiState.Listening(micReady = true))
        assertTrue(model.utteranceVisible)
        assertEquals("", model.utteranceText)
    }

    @Test
    fun `시니어 카드 발화 표시는 듣는 중에만 켜진다`() {
        // 이 값 하나가 배경 drawable과 카드 뒤 글로우를 **함께** 제어한다. 다른 상태로
        // 새면 사용자 차례가 아닌데 아래 카드가 빛나 화자 표시가 뒤집힌다.
        assertTrue(renderModelOf(ConversationUiState.Listening()).utteranceSpeaking)
        assertFalse(renderModelOf(ConversationUiState.Home()).utteranceSpeaking)
        assertFalse(
            renderModelOf(ConversationUiState.Clarifying(clarificationOutcome())).utteranceSpeaking,
        )
        assertFalse(renderModelOf(ConversationUiState.Requesting("발화")).utteranceSpeaking)
        assertFalse(renderModelOf(ConversationUiState.Confirm("목표")).utteranceSpeaking)
        assertFalse(renderModelOf(ConversationUiState.Failed).utteranceSpeaking)
    }

    @Test
    fun `두 카드의 글로우는 동시에 켜지지 않는다`() {
        // 글로우는 켜고 끄는 신호가 아니라 **자리를 옮기는** 신호다(2026-08-17 결정).
        // 둘이 함께 켜지면 누가 말하는지가 사라진다.
        for (state in listOf(
            ConversationUiState.Home(),
            ConversationUiState.Listening(),
            ConversationUiState.Clarifying(clarificationOutcome(), speaking = true),
            ConversationUiState.Requesting("발화"),
            ConversationUiState.Confirm("목표"),
            ConversationUiState.Failed,
        )) {
            val model = renderModelOf(state)
            assertFalse(
                "$state 에서 두 카드가 함께 빛난다",
                model.cardGlowVisible && model.utteranceSpeaking,
            )
        }
    }

    @Test
    fun `카드가 남는 공간을 먹는 화면과 목표 확인만의 예외`() {
        // 카드가 wrap_content면 긴 문구에서 아래 조작을 화면 밖으로 밀어낸다 — 그 화면에서
        // 버튼을 못 누르면 빠져나갈 길이 없다.
        //
        // **최종 확인(GOAL)만 예외다**(2026-08-21). 거기서는 카드 최대 높이
        // (`davi_question_card_max_height_confirm`)가 그 역할을 대신 지므로 카드가
        // 내용대로 자라도 버튼 셋이 밀리지 않는다 — 상자가 문장만큼만 커지고 버튼이 따라
        // 내려가는 것이 시안이다. 경고 쪽은 문구가 고정이라 종전대로 둔다.
        assertFalse(renderModelOf(ConversationUiState.Confirm("목표")).cardQuestionFlexible)
        for (state in listOf(
            ConversationUiState.Confirm("목표", ConfirmKind.APP_AVAILABILITY_WARNING),
            ConversationUiState.Listening(),
            ConversationUiState.Clarifying(clarificationOutcome()),
            ConversationUiState.Requesting("발화"),
        )) {
            assertTrue("$state 에서 카드가 고정 높이다", renderModelOf(state).cardQuestionFlexible)
        }
        // 카드가 혼자 남는 화면은 내용 높이로 선다.
        assertFalse(renderModelOf(ConversationUiState.Home()).cardQuestionFlexible)
        assertFalse(renderModelOf(ConversationUiState.Failed).cardQuestionFlexible)
    }

    @Test
    fun `바로가기 행은 목표 확인에서 앱이 있을 때만 선다`() {
        val app = ShortcutApp("com.kakao.talk", "카카오톡")

        val shown = renderModelOf(ConversationUiState.Confirm("사진 보내기", shortcutApp = app))
        assertTrue(shown.shortcutVisible)
        assertEquals("카카오톡", shown.shortcutAppName)

        // 앱이 안 좁혀졌으면 행을 세울 이름·아이콘이 없다.
        assertFalse(renderModelOf(ConversationUiState.Confirm("사진 보내기")).shortcutVisible)

        // **이름이 비면 「바로 가기 · 」가 뜬다.** 패키지명 매칭은 label 없이도 통과하므로
        // 실제로 올 수 있는 값이다.
        assertFalse(
            renderModelOf(
                ConversationUiState.Confirm("사진 보내기", shortcutApp = app.copy(label = "")),
            ).shortcutVisible,
        )
    }

    @Test
    fun `앱 미확인 경고에는 바로가기 행이 없다`() {
        // 사전 점검이 미확인이면 **열 수 있는지 모르는 앱**이다(#185). 여기서 행을 세우면
        // 눌렀을 때 열릴지 안 열릴지 모르는 곳으로 어르신을 보낸다.
        val model = renderModelOf(
            ConversationUiState.Confirm(
                "사진 보내기",
                ConfirmKind.APP_AVAILABILITY_WARNING,
                shortcutApp = ShortcutApp("com.kakao.talk", "카카오톡"),
            ),
        )
        assertFalse(model.shortcutVisible)
    }
}
