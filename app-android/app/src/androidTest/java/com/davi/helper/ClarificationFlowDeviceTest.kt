package com.davi.helper

import android.content.Intent
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/** #316 선택·직접 발화 화면을 실제 Android ViewBinding과 기기 런타임에서 관통한다. */
@RunWith(AndroidJUnit4::class)
class ClarificationFlowDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var activity: ConversationActivity? = null

    @After
    fun tearDown() {
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
    }

    private fun launchConversation(): ConversationActivity {
        val intent = Intent(instrumentation.targetContext, ConversationActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return (instrumentation.startActivitySync(intent) as ConversationActivity).also {
            activity = it
            instrumentation.waitForIdleSync()
        }
    }

    private fun showClarification(current: ConversationActivity, questionCount: Int) {
        instrumentation.runOnMainSync {
            val sessionField = ConversationActivity::class.java.getDeclaredField("voiceSession")
            sessionField.isAccessible = true
            val session = sessionField.get(current) as VoiceDialogueSession
            session.start()
            val pending = requireNotNull(session.nextRequest("사진 보내기"))
            val questions = listOf(
                ClarificationQuestion(
                    "q1",
                    "어느 방식으로 보낼까요?",
                    listOf("카카오톡", "문자"),
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
