package com.davi.helper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** debug 앱과 별도 UIAutomator driver APK 사이의 서명 보호된 테스트 전용 채널(#124). */
class GuideE2ePublisher(private val context: Context) {
    val suppressDetailedScreenLogs: Boolean
        get() = GuideE2eRunState.active

    fun displayed(
        generation: Long,
        requestSignature: String,
        request: NextStepRequest,
        response: NextStepResponse,
        target: UiElement?,
    ) {
        if (!GuideE2eRunState.active) return
        val action = response.action
        val intent = Intent(GuideE2eContract.ACTION_DECISION)
            .setPackage(GuideE2eContract.DRIVER_PACKAGE)
            .putExtra(GuideE2eContract.EXTRA_GENERATION, generation)
            .putExtra(GuideE2eContract.EXTRA_SIGNATURE, requestSignature)
            .putExtra(GuideE2eContract.EXTRA_SCREEN_PACKAGE, request.screen.packageName)
            .putExtra(GuideE2eContract.EXTRA_ACTION_TYPE, action.actionType.name.lowercase())
            .putExtra(GuideE2eContract.EXTRA_DIRECTION, action.direction?.name?.lowercase())
            .putExtra(GuideE2eContract.EXTRA_TARGET_NODE_ID, action.targetNodeId)
            .putExtra(GuideE2eContract.EXTRA_INSTRUCTION, action.instruction)
            .putExtra(GuideE2eContract.EXTRA_EXPECTED_RESULT, action.expectedResult)
            .putExtra(GuideE2eContract.EXTRA_EVIDENCE, action.evidence)
            .putExtra(GuideE2eContract.EXTRA_TARGET_BOUNDS, target?.bounds?.toIntArray())
            .putExtra(GuideE2eContract.EXTRA_TARGET_RESOLVED, target != null)
            .putExtra(GuideE2eContract.EXTRA_TARGET_ROLE, target?.role)
            .putExtra(GuideE2eContract.EXTRA_TARGET_CLICKABLE, target?.clickable ?: false)
            .putExtra(GuideE2eContract.EXTRA_TARGET_EDITABLE, target?.editable ?: false)
            .putExtra(GuideE2eContract.EXTRA_VERIFIED, response.verified)
            .putExtra(
                GuideE2eContract.EXTRA_COMPLETION_VERDICT,
                response.completionVerdict?.name?.lowercase()
                    ?: if (response.verified) "verified" else "inconclusive",
            )
            // 실기기 E2E test process만 파일로 남긴다. Binder 상한을 피하려고 JSON을 압축한다.
            .putExtra(GuideE2eContract.EXTRA_REQUEST_GZIP, gzip(JSON.encodeToString(request)))
            .putExtra(GuideE2eContract.EXTRA_RESPONSE_GZIP, gzip(JSON.encodeToString(response)))
        // 수신 앱도 signature 권한을 가져야 한다. 화면·안내 원문은 logcat에 출력하지 않는다.
        context.sendBroadcast(intent, GuideE2eContract.PERMISSION)
    }

    /** transient 상태는 즉시 종료하지 않고 driver가 timeout 원인을 고를 수 있게만 전달한다. */
    fun rootState(available: Boolean) {
        diagnostic(
            if (available) {
                GuideE2eContract.DIAGNOSTIC_ROOT_RECOVERED
            } else {
                GuideE2eContract.DIAGNOSTIC_ROOT_UNAVAILABLE
            },
        )
    }

    fun requestStarted() {
        diagnostic(GuideE2eContract.DIAGNOSTIC_REQUEST_STARTED)
    }

    fun serverError() {
        diagnostic(GuideE2eContract.DIAGNOSTIC_SERVER_ERROR)
    }

    fun clientConfigurationError() {
        diagnostic(GuideE2eContract.DIAGNOSTIC_CLIENT_CONFIGURATION_ERROR)
    }

    fun responseParseError() {
        diagnostic(GuideE2eContract.DIAGNOSTIC_RESPONSE_PARSE_ERROR)
    }

    fun screenStuck() {
        diagnostic(GuideE2eContract.DIAGNOSTIC_SCREEN_STUCK)
    }

    fun screenUnreadable() {
        diagnostic(GuideE2eContract.DIAGNOSTIC_SCREEN_UNREADABLE)
    }

    private fun diagnostic(value: String) {
        if (!GuideE2eRunState.active) return
        context.sendBroadcast(
            Intent(GuideE2eContract.ACTION_DIAGNOSTIC)
                .setPackage(GuideE2eContract.DRIVER_PACKAGE)
                .putExtra(GuideE2eContract.EXTRA_DIAGNOSTIC, value),
            GuideE2eContract.PERMISSION,
        )
    }

    private fun gzip(value: String): ByteArray = ByteArrayOutputStream().use { output ->
        GZIPOutputStream(output).bufferedWriter(Charsets.UTF_8).use { it.write(value) }
        output.toByteArray()
    }

    private companion object {
        val JSON = Json {
            encodeDefaults = true
            explicitNulls = false
        }
    }
}

/** 별도 test driver가 세션을 시작·정지하는 debug 전용 진입점. 제품 앱 UI를 우회하지 않는다. */
class GuideE2eControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        when (intent?.action) {
            GuideE2eContract.ACTION_START -> {
                val goal = intent.getStringExtra(GuideE2eContract.EXTRA_GOAL)?.trim().orEmpty()
                if (goal.isNotEmpty()) {
                    GuideE2eRunState.active = true
                    GuideSession.start(goal)
                    Log.i(GuideAccessibilityService.TAG, "e2e control=start")
                }
            }

            GuideE2eContract.ACTION_START_JOURNEY -> {
                val goal = intent.getStringExtra(GuideE2eContract.EXTRA_GOAL)?.trim().orEmpty()
                if (goal.isNotEmpty() && context != null) {
                    GuideE2eRunState.active = true
                    GuideJourneyStarter.start(context, goal)
                    Log.i(GuideAccessibilityService.TAG, "e2e control=start_journey")
                }
            }

            GuideE2eContract.ACTION_STOP -> {
                GuideSession.stop()
                GuideE2eRunState.active = false
                Log.i(GuideAccessibilityService.TAG, "e2e control=stop")
            }
        }
    }
}

private object GuideE2eRunState {
    @Volatile
    var active: Boolean = false
}

internal object GuideE2eContract {
    const val PERMISSION = "com.davi.helper.permission.CONTROL_E2E"
    const val DRIVER_PACKAGE = "com.davi.helper.e2edriver"
    const val ACTION_START = "com.davi.helper.e2e.START"
    const val ACTION_START_JOURNEY = "com.davi.helper.e2e.START_JOURNEY"
    const val ACTION_STOP = "com.davi.helper.e2e.STOP"
    const val ACTION_DECISION = "com.davi.helper.e2e.DECISION"
    const val ACTION_DIAGNOSTIC = "com.davi.helper.e2e.DIAGNOSTIC"
    const val EXTRA_GOAL = "goal"
    const val EXTRA_GENERATION = "generation"
    const val EXTRA_SIGNATURE = "signature"
    const val EXTRA_SCREEN_PACKAGE = "screen_package"
    const val EXTRA_ACTION_TYPE = "action_type"
    const val EXTRA_DIRECTION = "direction"
    const val EXTRA_TARGET_NODE_ID = "target_node_id"
    const val EXTRA_INSTRUCTION = "instruction"
    const val EXTRA_EXPECTED_RESULT = "expected_result"
    const val EXTRA_EVIDENCE = "evidence"
    const val EXTRA_TARGET_BOUNDS = "target_bounds"
    const val EXTRA_TARGET_RESOLVED = "target_resolved"
    const val EXTRA_TARGET_ROLE = "target_role"
    const val EXTRA_TARGET_CLICKABLE = "target_clickable"
    const val EXTRA_TARGET_EDITABLE = "target_editable"
    const val EXTRA_VERIFIED = "verified"
    const val EXTRA_COMPLETION_VERDICT = "completion_verdict"
    const val EXTRA_REQUEST_GZIP = "request_gzip"
    const val EXTRA_RESPONSE_GZIP = "response_gzip"
    const val EXTRA_DIAGNOSTIC = "diagnostic"
    const val DIAGNOSTIC_ROOT_UNAVAILABLE = "root_unavailable"
    const val DIAGNOSTIC_ROOT_RECOVERED = "root_recovered"
    const val DIAGNOSTIC_REQUEST_STARTED = "request_started"
    const val DIAGNOSTIC_SERVER_ERROR = "server_error"
    const val DIAGNOSTIC_CLIENT_CONFIGURATION_ERROR = "client_configuration_error"
    const val DIAGNOSTIC_RESPONSE_PARSE_ERROR = "response_parse_error"
    const val DIAGNOSTIC_SCREEN_STUCK = "screen_stuck"
    const val DIAGNOSTIC_SCREEN_UNREADABLE = "screen_unreadable"
}
