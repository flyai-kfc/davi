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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** #185의 기기 launcher 대조와 경고 확인쌍을 실제 Android 런타임에서 검증한다. */
@RunWith(AndroidJUnit4::class)
class AppAvailabilityPreflightDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var activity: ConversationActivity? = null

    @After
    fun tearDown() {
        GuideSession.stop()
        activity?.let { current ->
            instrumentation.runOnMainSync { current.finishAndRemoveTask() }
        }
        instrumentation.waitForIdleSync()
    }

    @Test
    fun launchableCandidateIsConfirmedOnDeviceIndex() {
        val context = instrumentation.targetContext
        val index = LaunchableAppIndex(AndroidLaunchableAppSource(context)) {}.also { it.start() }
        try {
            val plan = AppCandidatePlan(
                capability = "camera",
                candidates = listOf(
                    AppCandidate(
                        candidateId = "camera",
                        canonicalName = "카메라",
                        packageNames = listOf("com.sec.android.app.camera"),
                        labelAliases = listOf("카메라"),
                        confidence = 1.0,
                    )
                ),
            )

            val result = appAvailabilityPreflightOf(AppCandidateMatcher.match(plan, index.current()))

            assertEquals(AppAvailabilityPreflight.CONFIRMED, result)
        } finally {
            index.stop()
        }
    }

    @Test
    fun warningContinueShowsGoalBeforeStartingOriginalGoal() {
        val originalGoal = "사진을 찍어 손자에게 보내기"
        val current = launchConversation()
        showConfirm(current, originalGoal, ConfirmKind.APP_AVAILABILITY_WARNING)

        assertEquals(
            current.getString(R.string.conversation_app_availability_warning),
            current.findViewById<TextView>(R.id.card_question).text.toString(),
        )

        instrumentation.runOnMainSync {
            current.findViewById<View>(R.id.confirm_accept).performClick()
        }
        instrumentation.waitForIdleSync()

        assertFalse(GuideSession.isActive)
        assertEquals(
            originalGoal,
            current.findViewById<TextView>(R.id.card_question).text.toString(),
        )
        // 아이콘 전용이던 시절엔 contentDescription으로만 갈렸지만 이제 보이는 글자다(#196).
        assertEquals(
            current.getString(R.string.conversation_confirm_accept),
            current.findViewById<TextView>(R.id.confirm_accept).text.toString(),
        )

        instrumentation.runOnMainSync {
            current.findViewById<View>(R.id.confirm_accept).performClick()
        }
        instrumentation.waitForIdleSync()

        assertTrue(GuideSession.isActive)
        assertEquals(originalGoal, GuideSession.goal)
    }

    @Test
    fun warningCancelClearsGoalAndReturnsHome() {
        val current = launchConversation()
        showConfirm(current, "설치되지 않은 앱으로 문서 열기", ConfirmKind.APP_AVAILABILITY_WARNING)

        instrumentation.runOnMainSync {
            current.findViewById<View>(R.id.confirm_reject).performClick()
        }
        instrumentation.waitForIdleSync()

        assertFalse(GuideSession.isActive)
        assertEquals(View.VISIBLE, current.findViewById<View>(R.id.home_title).visibility)
    }

    @Test
    fun goalConfirmationAfterWarningCanStillBeCancelled() {
        val current = launchConversation()
        showConfirm(current, "설치되지 않은 앱으로 문서 열기", ConfirmKind.APP_AVAILABILITY_WARNING)

        instrumentation.runOnMainSync {
            current.findViewById<View>(R.id.confirm_accept).performClick()
            current.findViewById<View>(R.id.confirm_reject).performClick()
        }
        instrumentation.waitForIdleSync()

        assertFalse(GuideSession.isActive)
        assertEquals(View.VISIBLE, current.findViewById<View>(R.id.home_title).visibility)
    }

    private fun launchConversation(): ConversationActivity {
        val intent = Intent(instrumentation.targetContext, ConversationActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return (instrumentation.startActivitySync(intent) as ConversationActivity).also {
            activity = it
            instrumentation.waitForIdleSync()
        }
    }

    private fun showConfirm(
        current: ConversationActivity,
        goal: String,
        kind: ConfirmKind,
    ) {
        instrumentation.runOnMainSync {
            val method = ConversationActivity::class.java.getDeclaredMethod(
                "setState",
                ConversationUiState::class.java,
            )
            method.isAccessible = true
            method.invoke(current, ConversationUiState.Confirm(goal, kind))
        }
        instrumentation.waitForIdleSync()
    }
}
