package com.davi.helper.e2e

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until

/** 실제 Gemini 결정과 외부 앱 화면을 닫힌 루프로 잇는 별도 프로세스 실기기 러너. */
class GuidedScenarioRunner(private val device: UiDevice) {
    fun <State> run(scenario: GuidedScenario<State>) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        GuideControl.stop(context)
        var state: State? = null
        var primaryFailure: Throwable? = null
        val queue = GuideDecisionQueue(context)
        val detailedTrace = DetailedTraceWriter.create(context, scenario.handlesPersonalData)
        try {
            val prepared = scenario.prepare(device)
            state = prepared
            GuideControl.start(context, device, scenario.goal(prepared), scenario.start)
            val executor = GuidedActionExecutor(device)
            var activeGeneration: Long? = null
            var previousSignature: String? = null

            for (step in 1..scenario.maxSteps) {
                var decision: DisplayedDecision? = null
                try {
                    val discard: (String) -> Unit = { reason ->
                        Log.i(TRACE_TAG, "decision_discarded reason=$reason")
                        detailedTrace.recordDiscard(reason)
                    }
                    decision = queue.await(
                        timeoutMs = scenario.decisionTimeoutMs,
                        step = step,
                        expectedGeneration = activeGeneration,
                        previousSignature = previousSignature,
                        onDiscard = discard,
                    )
                    if (activeGeneration == null) activeGeneration = decision.generation
                    detailedTrace.recordDecision(step, decision)
                    GuideInstructionQuality.validate(decision, step)
                    Log.i(
                        TRACE_TAG,
                        "step=$step action=${decision.action.actionType.wire} " +
                            "package=${decision.screenPackage} " +
                            "target=${decision.targetBounds?.size == 4} verified=${decision.verified} " +
                            "verdict=${decision.completionVerdict.wire}",
                    )

                    if (decision.action.actionType == GuidedActionType.DONE) {
                        if (decision.completionVerdict != GuidedCompletionVerdict.VERIFIED) {
                            val code = "done_${decision.completionVerdict.wire}"
                            Log.i(TRACE_TAG, "step=$step deviation=$code")
                            detailedTrace.recordDeviation(step, code)
                            detailedTrace.recordStepOutcome(step, code)
                            // 앱도 이 두 verdict에서 세션을 끝내지 않는다. 같은 화면 재질의를
                            // 기다려 recovery가 실제로 성공하는지 남은 step 예산 안에서 본다.
                            continue
                        }
                        if (!scenario.isGoalSatisfied(device, prepared)) {
                            throw GuidedScenarioFailure("done_before_goal", step)
                        }
                        detailedTrace.recordStepOutcome(step, "done_verified")
                        return
                    }

                    val hardBoundaries =
                        listOf(GuidedHardBoundaries.settingsApp) + scenario.hardBoundaries
                    GuidedHardBoundaries.firstFailure(hardBoundaries, decision)?.let { code ->
                        throw GuidedScenarioFailure(code, step)
                    }
                    val assessment = scenario.assessBeforeExecute(device, prepared, step, decision)
                    assessment.deviationCodes.distinct().forEach { code ->
                        Log.i(TRACE_TAG, "step=$step deviation=$code")
                        detailedTrace.recordDeviation(step, code)
                    }
                    // 현재 결정 검증 중 같은 화면에서 중복 방송된 값은 실제 조작 뒤 실행하지 않는다.
                    queue.discardPending(discard)
                    val plan = GuidedActionPlanner.plan(
                        decision,
                        scenario.textFor(prepared, step, decision),
                        step,
                    )
                    executor.execute(plan, step)
                    previousSignature = decision.requestSignature
                    detailedTrace.recordStepOutcome(step, "executed")
                } catch (failure: Throwable) {
                    detailedTrace.recordStepOutcome(step, "failed", failure)
                    throw failure
                }
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
                Log.i(TRACE_TAG, "cleanup=failed")
                detailedTrace.recordCleanup("failed")
                if (primaryFailure != null) {
                    primaryFailure?.addSuppressed(cleanupFailure)
                } else {
                    throw cleanupFailure
                }
            } ?: run {
                Log.i(TRACE_TAG, "cleanup=success")
                detailedTrace.recordCleanup("success")
            }
        }
    }

    private companion object {
        const val TRACE_TAG = "DaviE2e"
    }
}

private object GuideControl {
    fun start(
        context: Context,
        device: UiDevice,
        goal: String,
        start: GuidedScenarioStart,
    ) {
        if (start == GuidedScenarioStart.DAVI_TO_ANDROID_HOME) {
            // 진입점이 로그인(ON-1)으로 바뀌며 Onboarding은 exported=false가 됐다.
            // 외부 앱인 드라이버는 공개 런처인 LoginActivity로만 들어갈 수 있다.
            context.startActivity(
                Intent()
                    .setComponent(
                        ComponentName(
                            GuideE2eContract.DAVI_PACKAGE,
                            "${GuideE2eContract.DAVI_CLASS_ROOT}.LoginActivity",
                        ),
                    )
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
            // 로그인은 E2E 관심사가 아니다 — 화면이 뜨면 "로그인 없이 시작"으로 지나간다.
            // 기기에 이미 결정(세션·스킵)이 있으면 화면 없이 통과하므로 버튼이 안 뜰 수 있다.
            device.wait(
                Until.findObject(By.res(GuideE2eContract.DAVI_PACKAGE, LOGIN_SKIP_RESOURCE)),
                LOGIN_SCREEN_TIMEOUT_MS,
            )?.click()
            // 권한이 준비된 실제 앱 진입점은 로그인·Onboarding을 지나 최신 Conversation 화면으로 온다.
            if (!device.wait(
                    Until.hasObject(By.res(GuideE2eContract.DAVI_PACKAGE, DAVI_MIC_RESOURCE)),
                    APP_START_TIMEOUT_MS,
                )
            ) {
                throw GuidedScenarioFailure("davi_conversation_not_ready")
            }
        }

        val action = when (start) {
            GuidedScenarioStart.PREPARED_SCREEN -> GuideE2eContract.ACTION_START
            GuidedScenarioStart.DAVI_TO_ANDROID_HOME -> GuideE2eContract.ACTION_START_JOURNEY
        }
        context.sendBroadcast(
            Intent(action)
                .setComponent(
                    ComponentName(
                        GuideE2eContract.DAVI_PACKAGE,
                        "${GuideE2eContract.DAVI_CLASS_ROOT}.GuideE2eControlReceiver",
                    ),
                )
                .putExtra(GuideE2eContract.EXTRA_GOAL, goal),
            GuideE2eContract.PERMISSION,
        )

        if (start == GuidedScenarioStart.DAVI_TO_ANDROID_HOME) {
            val launcher = device.launcherPackageName
                ?: throw GuidedScenarioFailure("launcher_package_missing")
            if (!device.wait(Until.hasObject(By.pkg(launcher).depth(0)), HOME_TIMEOUT_MS)) {
                throw GuidedScenarioFailure("android_home_not_visible")
            }
            Log.i("DaviE2e", "journey=start package=$launcher")
        }
    }

    fun stop(context: Context) {
        context.sendBroadcast(
            Intent(GuideE2eContract.ACTION_STOP).setComponent(
                ComponentName(
                    GuideE2eContract.DAVI_PACKAGE,
                    "${GuideE2eContract.DAVI_CLASS_ROOT}.GuideE2eControlReceiver",
                ),
            ),
            GuideE2eContract.PERMISSION,
        )
    }

    private const val DAVI_MIC_RESOURCE = "btn_mic"
    private const val LOGIN_SKIP_RESOURCE = "btn_login_skip"
    private const val APP_START_TIMEOUT_MS = 10_000L
    private const val LOGIN_SCREEN_TIMEOUT_MS = 3_000L
    private const val HOME_TIMEOUT_MS = 10_000L
}
