package com.davi.helper

import android.content.Intent
import android.graphics.Rect
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
    fun optionSelectionMovesToNextQuestionWithoutShowingCancel() {
        val current = launchConversation()
        showClarification(current, questionCount = 2)

        instrumentation.runOnMainSync {
            current.findViewById<Button>(R.id.btn_option_1).performClick()
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
        assertEquals(View.GONE, current.findViewById<View>(R.id.btn_close).visibility)
    }

    @Test
    fun directSpeechKeepsQuestionAndHasNoCancelAction() {
        val current = launchConversation()
        showClarification(current, questionCount = 1)

        instrumentation.runOnMainSync {
            current.findViewById<Button>(R.id.btn_direct_speak).performClick()
        }
        instrumentation.waitForIdleSync()

        assertEquals(
            "어느 방식으로 보낼까요?",
            current.findViewById<TextView>(R.id.card_question).text.toString(),
        )
        assertEquals(View.VISIBLE, current.findViewById<View>(R.id.btn_mic).visibility)
        assertEquals(View.GONE, current.findViewById<View>(R.id.btn_close).visibility)
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
            current.findViewById<Button>(R.id.btn_direct_speak).performClick()
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
            current.findViewById<Button>(R.id.btn_direct_speak).performClick()
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
            current.findViewById<Button>(R.id.btn_option_1).performClick()
        }
        instrumentation.waitForIdleSync()
        assertEquals(
            current.getString(R.string.clarification_progress, 2, 2),
            current.findViewById<TextView>(R.id.question_progress).text.toString(),
        )
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
