package com.davi.helper

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.speech.SpeechRecognizer
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** #316 선택·직접 발화 화면을 실제 Android ViewBinding과 기기 런타임에서 관통한다. */
@RunWith(AndroidJUnit4::class)
class ClarificationFlowDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var activity: ConversationActivity? = null

    @After
    fun tearDown() {
        ConversationInstrumentationConfiguration.fontScale = null
        activity?.let { current ->
            instrumentation.runOnMainSync { current.finishAndRemoveTask() }
        }
        instrumentation.waitForIdleSync()
    }

    @Test
    fun optionSelectionMovesToNextQuestionKeepingCancel() {
        val current = launchConversation()
        showClarification(current, questionCount = 2)

        instrumentation.runOnMainSync {
            current.findViewById<View>(R.id.btn_option_1).performClick()
        }
        instrumentation.waitForIdleSync()

        assertEquals(
            "누구에게 보낼까요?",
            current.findViewById<TextView>(R.id.card_question).text.toString(),
        )
        assertEquals(
            current.getString(R.string.clarification_progress, 2, 2),
            current.findViewById<TextView>(R.id.question_progress).text.toString(),
        )
        // **보기가 있어도 나가는 길은 있다**(2026-08-23 · #374). 종전에는 「보기 있는 질문에서는
        // 문답 중 취소를 두지 않는다」였는데, 앞뒤 화면에서 ×가 있다 없다 하면 어르신이
        // 나가는 길을 매번 새로 찾는다.
        assertEquals(View.VISIBLE, current.findViewById<View>(R.id.btn_close).visibility)
    }

    @Test
    fun directSpeechKeepsQuestionAndCancelAction() {
        val current = launchConversation()
        showClarification(current, questionCount = 1)

        instrumentation.runOnMainSync {
            current.findViewById<View>(R.id.btn_direct_speak).performClick()
        }
        instrumentation.waitForIdleSync()

        assertEquals(
            "어느 방식으로 보낼까요?",
            current.findViewById<TextView>(R.id.card_question).text.toString(),
        )
        assertEquals(View.VISIBLE, current.findViewById<View>(R.id.btn_mic).visibility)
        // **「직접 말하기」로 넘어가도 ×가 남는다** — 여기서 사라지면 인식이 반복 실패할 때
        // 갇힌다. 바로 앞 선택 화면이 ×를 보이므로 뒤로 갈수록 출구가 줄어드는 셈이 된다.
        assertEquals(View.VISIBLE, current.findViewById<View>(R.id.btn_close).visibility)
        assertFalse(current.findViewById<View>(R.id.btn_mic).isEnabled)
        val micToneArmed = ConversationActivity::class.java.getDeclaredField("micToneArmed")
        micToneArmed.isAccessible = true
        assertTrue("직접 말하기도 공통 청취 진입점에서 차임을 무장한다", micToneArmed.getBoolean(current))
    }

    @Test
    fun threeOptionsAndDirectSpeechAreVisibleWithoutScrolling() {
        val current = launchConversation()
        showClarification(current, questionCount = 1, optionCount = 3)

        val viewport = current.findViewById<View>(R.id.clarification_choices)
        val viewportBounds = Rect()
        assertTrue(
            "viewport is outside screen: y=${viewport.y}, height=${viewport.height}, " +
                "parentHeight=${(viewport.parent as View).height}, visibility=${viewport.visibility}",
            viewport.getGlobalVisibleRect(viewportBounds),
        )
        listOf(
            R.id.btn_option_1,
            R.id.btn_option_2,
            R.id.btn_option_3,
            R.id.btn_direct_speak,
        ).forEach { id ->
            val action = current.findViewById<View>(id)
            assertFullyVisible(action, viewportBounds)
        }
    }

    @Test
    fun twoTimesFontScalePreservesQuestionAndKeepsEveryChoiceReachable() {
        val current = launchConversation(fontScale = 2f)
        showClarification(current, questionCount = 1, optionCount = 3)

        assertEquals(2f, current.resources.configuration.fontScale, 0.01f)
        val questionCard = current.findViewById<View>(R.id.card_question_container)
        val question = current.findViewById<TextView>(R.id.card_question)
        val minimumCardHeight = current.resources.getDimensionPixelSize(R.dimen.davi_card_min_height_davi)
        assertTrue("question card lost its minimum height", questionCard.height >= minimumCardHeight)
        assertTrue(
            "question card cannot show one line",
            questionCard.height - questionCard.paddingTop - questionCard.paddingBottom >= question.lineHeight,
        )
        val choices = current.findViewById<ScrollView>(R.id.clarification_choices)
        assertTrue("choices did not receive remaining height", choices.height > 0)
        val actionIds = listOf(
            R.id.btn_option_1,
            R.id.btn_option_2,
            R.id.btn_option_3,
            R.id.btn_direct_speak,
        )
        if (choices.canScrollVertically(1)) {
            instrumentation.runOnMainSync { choices.fullScroll(View.FOCUS_DOWN) }
            instrumentation.waitForIdleSync()
            val viewportBounds = Rect().also { choices.getGlobalVisibleRect(it) }
            assertFullyVisible(current.findViewById(R.id.btn_direct_speak), viewportBounds)
        } else {
            val viewportBounds = Rect().also { choices.getGlobalVisibleRect(it) }
            actionIds.forEach { id -> assertFullyVisible(current.findViewById(id), viewportBounds) }
        }
    }

    @Test
    fun directSpeechStartFailureEndsWithVisibleFeedback() {
        val current = launchConversation()
        showClarification(current, questionCount = 1)

        instrumentation.runOnMainSync {
            current.findViewById<View>(R.id.btn_direct_speak).performClick()
            invokeSpeechError(current, SpeechRecognizerHelper.ERROR_START_FAILED)
        }
        instrumentation.waitForIdleSync()

        assertEquals(View.VISIBLE, current.findViewById<View>(R.id.home_head).visibility)
        assertEquals(
            current.getString(R.string.voice_recognition_error),
            current.findViewById<TextView>(R.id.home_bubble_text).text.toString(),
        )
        assertEquals(VoiceDialogueSession.Stage.IDLE, voiceSession(current).stage)
    }

    @Test
    fun safeEndKeepsContinueActionAboveExitAction() {
        val current = launchConversation()
        setUiState(current, ConversationUiState.SafeEnd("목표를 정하지 못했어요."))

        val continueButton = current.findViewById<Button>(R.id.btn_stacked_secondary)
        val exitButton = current.findViewById<Button>(R.id.btn_stacked_primary)
        assertEquals(current.getString(R.string.clarification_restart), continueButton.text.toString())
        assertEquals(current.getString(R.string.clarification_go_home), exitButton.text.toString())

        instrumentation.runOnMainSync { continueButton.performClick() }
        instrumentation.waitForIdleSync()
        assertEquals(VoiceDialogueSession.Stage.LISTENING, voiceSession(current).stage)

        voiceSession(current).cancel()
        setUiState(current, ConversationUiState.SafeEnd("목표를 정하지 못했어요."))
        instrumentation.runOnMainSync { exitButton.performClick() }
        instrumentation.waitForIdleSync()
        assertEquals(View.VISIBLE, current.findViewById<View>(R.id.home_head).visibility)
    }

    private fun assertFullyVisible(action: View, viewportBounds: Rect) {
        val visibleBounds = Rect()
        assertTrue("action ${action.id} is not visible", action.getGlobalVisibleRect(visibleBounds))
        val location = IntArray(2)
        action.getLocationOnScreen(location)
        val fullBounds = Rect(
            location[0],
            location[1],
            location[0] + action.width,
            location[1] + action.height,
        )
        assertEquals("action ${action.id} is clipped", fullBounds, visibleBounds)
        assertTrue("action ${action.id} is outside the viewport", viewportBounds.contains(fullBounds))
    }

    @Test
    fun directSpeechTimeoutReturnsToSameQuestionWithoutCancellingSession() {
        val current = launchConversation()
        showClarification(current, questionCount = 2)

        instrumentation.runOnMainSync {
            current.findViewById<View>(R.id.btn_direct_speak).performClick()
            val handleSpeechError = ConversationActivity::class.java.getDeclaredMethod(
                "handleSpeechError",
                Int::class.javaPrimitiveType,
            )
            handleSpeechError.isAccessible = true
            handleSpeechError.invoke(current, SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
        }
        instrumentation.waitForIdleSync()

        assertEquals(View.VISIBLE, current.findViewById<View>(R.id.clarification_choices).visibility)
        assertEquals(
            current.getString(R.string.clarification_progress, 1, 2),
            current.findViewById<TextView>(R.id.question_progress).text.toString(),
        )
        assertEquals(VoiceDialogueSession.Stage.CLARIFYING, voiceSession(current).stage)

        instrumentation.runOnMainSync {
            current.findViewById<View>(R.id.btn_option_1).performClick()
        }
        instrumentation.waitForIdleSync()
        assertEquals(
            current.getString(R.string.clarification_progress, 2, 2),
            current.findViewById<TextView>(R.id.question_progress).text.toString(),
        )
    }

    @Test
    fun collapsedCardTellsWhatToDoNextInEveryBranch() {
        // #452. 접힌 카드의 문구는 **다음에 할 동작**을 말한다 — 두 갈래 모두 누르기다.
        // 보기가 0개일 때 「선택해 주세요」는 **없는 것을 고르라는 말**이고, 그렇다고
        // 「말씀해 주세요」로 가르면 **버튼을 안 누르고 그냥 말하려 한다**(그 화면도 먼저
        // 눌러야 마이크가 열린다). 그래서 한 문구다.
        val current = launchConversation()

        for (optionCount in intArrayOf(0, 2)) {
            showClarification(current, questionCount = 1, optionCount = optionCount)
            assertEquals(
                "보기 ${optionCount}개",
                current.getString(R.string.clarification_prompt_collapsed),
                current.findViewById<TextView>(R.id.card_question).text.toString(),
            )
        }
    }

    @Test
    fun emptyOptionsRepeatedNoMatchKeepsVisibleExitThatEndsTheDialogue() {
        val current = launchConversation()
        showClarification(current, questionCount = 1, optionCount = 0)
        val close = current.findViewById<View>(R.id.btn_close)
        assertEquals(View.VISIBLE, close.visibility)

        repeat(2) {
            instrumentation.runOnMainSync {
                current.findViewById<View>(R.id.btn_direct_speak).performClick()
                invokeSpeechError(current, SpeechRecognizer.ERROR_NO_MATCH)
            }
            instrumentation.waitForIdleSync()
            assertEquals(View.VISIBLE, close.visibility)
            assertEquals(VoiceDialogueSession.Stage.CLARIFYING, voiceSession(current).stage)
        }

        instrumentation.runOnMainSync { close.performClick() }
        instrumentation.waitForIdleSync()
        assertEquals(View.VISIBLE, current.findViewById<View>(R.id.home_head).visibility)
        assertEquals(VoiceDialogueSession.Stage.IDLE, voiceSession(current).stage)
    }

    /**
     * eval `e01`의 목표 조각을 실제 main 서버에 보내, 보기 선택이 두 번째 요청과 goal까지
     * 이어지는지 실기기에서 확인한다. 원문은 테스트 프로세스 메모리에만 있고 logcat에 쓰지 않는다.
     */
    @Test
    fun liveOptionSelectionFinalizesEvalE01Goal() {
        assumeLiveServer()
        val current = launchConversation()

        // 첫 계획은 모델의 보기 생성 여부에 흔들리지 않게 고정하고, 선택 이후 최종화는 실제
        // 서버로 보낸다. e01 goal 조각과 #316 wire 계약을 그대로 쓴다.
        showLiveClarification(
            current,
            utterance = "순자에게 사진 보내기",
            question = ClarificationQuestion(
                id = "e01-app",
                prompt = "어느 앱으로 사진을 보낼까요?",
                options = listOf("카카오톡", "문자"),
                allowFreeform = true,
            ),
        )

        instrumentation.runOnMainSync {
            current.findViewById<View>(R.id.btn_option_1).performClick()
        }
        val confirm = waitForState<ConversationUiState.Confirm>(current)
        assertTrue(confirm.goal.contains("카카오톡"))
        assertTrue(confirm.goal.contains("순자"))
        assertTrue(confirm.goal.contains("사진"))
    }

    /**
     * eval `h07`의 숨은 지역을 「직접 말하기」 경로로 보낸다. 음향 인식기의 비결정성은 섞지
     * 않고 성공한 STT callback부터 실제 앱·서버 최종화까지 관통한다.
     */
    @Test
    fun liveDirectSpeechFinalizesEvalH07Goal() {
        assumeLiveServer()
        val current = launchConversation()

        showLiveClarification(
            current,
            utterance = "네이버에서 비가 오는지 확인하기",
            question = ClarificationQuestion(
                id = "h07-region",
                prompt = "어느 지역의 비를 확인할까요?",
                options = emptyList(),
                allowFreeform = true,
            ),
        )
        instrumentation.runOnMainSync {
            current.findViewById<View>(R.id.btn_direct_speak).performClick()
            val listenerField = ConversationActivity::class.java.getDeclaredField(
                "voiceRecognitionListener",
            )
            listenerField.isAccessible = true
            val listener = listenerField.get(current) as SpeechRecognizerHelper.Listener
            listener.onFinalResult("인천 서구")
        }

        val confirm = waitForState<ConversationUiState.Confirm>(current)
        assertTrue(confirm.goal.contains("네이버"))
        assertTrue(confirm.goal.contains("인천 서구"))
        assertTrue(confirm.goal.contains("비") || confirm.goal.contains("강수"))
    }

    private fun launchConversation(fontScale: Float? = null): ConversationActivity {
        ConversationInstrumentationConfiguration.fontScale = fontScale
        val intent = Intent(instrumentation.targetContext, ConversationActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra(EXTRA_INSTRUMENTATION_SKIP_ACCESSIBILITY, true)
            .putExtra(EXTRA_INSTRUMENTATION_SKIP_SPEECH_RECOGNIZER, true)
        return (instrumentation.startActivitySync(intent) as ConversationActivity).also {
            activity = it
            instrumentation.waitForIdleSync()
        }
    }

    private fun assumeLiveServer() {
        assumeTrue(
            "역사적으로 이름 붙은 -e davi.liveGemini true에서만 실제 모델 서버를 호출한다",
            InstrumentationRegistry.getArguments().getString("davi.liveGemini") == "true",
        )
    }

    private fun showLiveClarification(
        current: ConversationActivity,
        utterance: String,
        question: ClarificationQuestion,
    ) {
        instrumentation.runOnMainSync {
            val session = voiceSession(current)
            session.start()
            val pending = requireNotNull(session.nextRequest(utterance))
            val outcome = session.applyResponse(
                pending,
                SlotFillState(
                    status = IntentDialogueStatus.NEEDS_CLARIFICATION,
                    message = question.prompt,
                    clarification = ClarificationPlan(listOf(question)),
                ),
            ) as VoiceDialogueSession.Outcome.Clarify
            val setState = ConversationActivity::class.java.getDeclaredMethod(
                "setState",
                ConversationUiState::class.java,
            )
            setState.isAccessible = true
            setState.invoke(current, ConversationUiState.Clarifying(outcome, speaking = false))
        }
        instrumentation.waitForIdleSync()
    }

    private inline fun <reified T : ConversationUiState> waitForState(
        current: ConversationActivity,
        timeoutMs: Long = 45_000,
    ): T {
        val stateField = ConversationActivity::class.java.getDeclaredField("uiState")
        stateField.isAccessible = true
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            val state = stateField.get(current)
            if (state is T) return state
            if (
                state is ConversationUiState.Home ||
                state is ConversationUiState.SafeEnd ||
                state is ConversationUiState.Failed
            ) break
            SystemClock.sleep(100)
        }
        // 원문 상태 내용은 실패 출력에 넣지 않는다 — 타입만으로 서버/화면 경로를 진단한다.
        throw AssertionError("${T::class.java.simpleName} 상태에 도달하지 못했다")
    }

    private fun voiceSession(current: ConversationActivity): VoiceDialogueSession {
        val sessionField = ConversationActivity::class.java.getDeclaredField("voiceSession")
        sessionField.isAccessible = true
        return sessionField.get(current) as VoiceDialogueSession
    }

    private fun invokeSpeechError(current: ConversationActivity, error: Int) {
        val handleSpeechError = ConversationActivity::class.java.getDeclaredMethod(
            "handleSpeechError",
            Int::class.javaPrimitiveType,
        )
        handleSpeechError.isAccessible = true
        handleSpeechError.invoke(current, error)
    }

    private fun setUiState(current: ConversationActivity, state: ConversationUiState) {
        instrumentation.runOnMainSync {
            val setState = ConversationActivity::class.java.getDeclaredMethod(
                "setState",
                ConversationUiState::class.java,
            )
            setState.isAccessible = true
            setState.invoke(current, state)
        }
        instrumentation.waitForIdleSync()
    }

    private fun showClarification(
        current: ConversationActivity,
        questionCount: Int,
        optionCount: Int = 2,
    ) {
        instrumentation.runOnMainSync {
            val session = voiceSession(current)
            session.start()
            val pending = requireNotNull(session.nextRequest("사진 보내기"))
            val questions = listOf(
                ClarificationQuestion(
                    "q1",
                    "어느 방식으로 보낼까요?",
                    listOf("카카오톡", "문자", "이메일").take(optionCount),
                    true,
                ),
                ClarificationQuestion(
                    "q2",
                    "누구에게 보낼까요?",
                    listOf("가족", "친구"),
                    true,
                ),
            ).take(questionCount)
            val outcome = session.applyResponse(
                pending,
                SlotFillState(
                    IntentDialogueStatus.NEEDS_CLARIFICATION,
                    questions.first().prompt,
                    clarification = ClarificationPlan(questions),
                ),
            ) as VoiceDialogueSession.Outcome.Clarify

            val setState = ConversationActivity::class.java.getDeclaredMethod(
                "setState",
                ConversationUiState::class.java,
            )
            setState.isAccessible = true
            setState.invoke(current, ConversationUiState.Clarifying(outcome, speaking = false))
        }
        instrumentation.waitForIdleSync()
    }
}
