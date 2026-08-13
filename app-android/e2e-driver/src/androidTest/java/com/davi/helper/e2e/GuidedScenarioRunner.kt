package com.davi.helper.e2e

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice

/** 실제 Gemini 결정과 외부 앱 화면을 닫힌 루프로 잇는 별도 프로세스 실기기 러너. */
class GuidedScenarioRunner(private val device: UiDevice) {
    fun <State> run(scenario: GuidedScenario<State>) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        GuideControl.stop(context)
        var state: State? = null
        var primaryFailure: Throwable? = null
        val queue = GuideDecisionQueue(context)
        try {
            val prepared = scenario.prepare(device)
            state = prepared
            GuideControl.start(context, scenario.goal(prepared))
            val executor = GuidedActionExecutor(device)

            for (step in 1..scenario.maxSteps) {
                val decision = queue.await(scenario.decisionTimeoutMs, step)
                GuideInstructionQuality.validate(decision, step)
                Log.i(
                    TRACE_TAG,
                    "step=$step action=${decision.action.actionType.wire} " +
                        "target=${decision.targetBounds?.size == 4} verified=${decision.verified}",
                )

                if (decision.action.actionType == GuidedActionType.DONE) {
                    if (!scenario.isGoalSatisfied(device, prepared)) {
                        throw GuidedScenarioFailure("done_before_goal", step)
                    }
                    if (!decision.verified) {
                        throw GuidedScenarioFailure("done_unverified", step)
                    }
                    return
                }

                scenario.validateBeforeExecute(device, prepared, step, decision)
                executor.execute(decision, scenario.textFor(prepared, step, decision), step)
            }
            throw GuidedScenarioFailure("step_limit")
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            GuideControl.stop(context)
            queue.close()
            val cleanup = runCatching { scenario.cleanup(device, state) }
            cleanup.exceptionOrNull()?.let { cleanupFailure ->
                if (primaryFailure != null) {
                    primaryFailure?.addSuppressed(cleanupFailure)
                } else {
                    throw cleanupFailure
                }
            }
        }
    }

    private companion object {
        const val TRACE_TAG = "DaviE2e"
    }
}

private object GuideControl {
    fun start(context: Context, goal: String) {
        context.sendBroadcast(
            Intent(GuideE2eContract.ACTION_START)
                .setComponent(
                    ComponentName(
                        GuideE2eContract.DAVI_PACKAGE,
                        "${GuideE2eContract.DAVI_PACKAGE}.GuideE2eControlReceiver",
                    ),
                )
                .putExtra(GuideE2eContract.EXTRA_GOAL, goal),
            GuideE2eContract.PERMISSION,
        )
    }

    fun stop(context: Context) {
        context.sendBroadcast(
            Intent(GuideE2eContract.ACTION_STOP).setComponent(
                ComponentName(
                    GuideE2eContract.DAVI_PACKAGE,
                    "${GuideE2eContract.DAVI_PACKAGE}.GuideE2eControlReceiver",
                ),
            ),
            GuideE2eContract.PERMISSION,
        )
    }
}
