package com.davi.helper.e2e

import android.content.ComponentName
import android.content.Intent
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 현재 화면 자체가 목표인 최소 실제 모델 smoke. 조작 없이 verified wire·앱 bridge를 확인한다. */
@RunWith(AndroidJUnit4::class)
@LargeTest
class CompletionVerdictDeviceSmokeTest {
    private lateinit var device: UiDevice

    @Before
    fun prepareEnvironment() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        LiveGeminiTestEnvironment.requireReady()
    }

    @Test
    fun recognizesVerifiedThenBindsUserRejectionAsContradicted() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val robot = SamsungClockRobot(device)
        robot.openAlarmList()
        val queue = GuideDecisionQueue(context)
        try {
            context.sendBroadcast(
                Intent(GuideE2eContract.ACTION_START)
                    .setComponent(
                        ComponentName(
                            GuideE2eContract.DAVI_PACKAGE,
                            "${GuideE2eContract.DAVI_CLASS_ROOT}.GuideE2eControlReceiver",
                        ),
                    )
                    .putExtra(GuideE2eContract.EXTRA_GOAL, GOAL),
                GuideE2eContract.PERMISSION,
            )
            val verified = queue.await(
                timeoutMs = DECISION_TIMEOUT_MS,
                step = 1,
                expectedGeneration = null,
                previousSignature = null,
                onDiscard = { reason -> Log.i(TRACE_TAG, "decision_discarded reason=$reason") },
            )
            Log.i(
                TRACE_TAG,
                "step=1 action=${verified.action.actionType.wire} " +
                    "package=${verified.screenPackage} " +
                    "target=${verified.targetBounds?.size == 4} verified=${verified.verified} " +
                    "verdict=${verified.completionVerdict.wire}",
            )

            assertEquals(GuidedActionType.DONE, verified.action.actionType)
            assertEquals(GuidedCompletionVerdict.VERIFIED, verified.completionVerdict)

            val reject = device.wait(
                Until.findObject(By.res(GuideE2eContract.DAVI_PACKAGE, REJECT_BUTTON_RESOURCE)),
                OVERLAY_TIMEOUT_MS,
            ) ?: throw GuidedScenarioFailure("completion_reject_not_visible", 2)
            reject.click()

            val contradicted = queue.await(
                timeoutMs = DECISION_TIMEOUT_MS,
                step = 2,
                expectedGeneration = verified.generation,
                previousSignature = null,
                onDiscard = { reason -> Log.i(TRACE_TAG, "decision_discarded reason=$reason") },
            )
            Log.i(
                TRACE_TAG,
                "step=2 action=${contradicted.action.actionType.wire} " +
                    "package=${contradicted.screenPackage} " +
                    "target=${contradicted.targetBounds?.size == 4} " +
                    "verified=${contradicted.verified} " +
                    "verdict=${contradicted.completionVerdict.wire}",
            )
            assertEquals(GuidedCompletionVerdict.CONTRADICTED, contradicted.completionVerdict)
        } finally {
            context.sendBroadcast(
                Intent(GuideE2eContract.ACTION_STOP).setComponent(
                    ComponentName(
                        GuideE2eContract.DAVI_PACKAGE,
                        "${GuideE2eContract.DAVI_CLASS_ROOT}.GuideE2eControlReceiver",
                    ),
                ),
                GuideE2eContract.PERMISSION,
            )
            queue.close()
            Log.i(TRACE_TAG, "cleanup=success")
        }
    }

    private companion object {
        const val GOAL = "현재 삼성 시계의 알람 목록 화면에 도착했는지 확인해 주세요."
        const val DECISION_TIMEOUT_MS = 60_000L
        const val OVERLAY_TIMEOUT_MS = 5_000L
        const val REJECT_BUTTON_RESOURCE = "btn_confirm_reject"
        const val TRACE_TAG = "DaviE2e"
    }
}
