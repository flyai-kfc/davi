package com.davi.helper.e2e

import android.content.Context
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * 앱이 서버로 보낸 1차 마스킹 요청과 구조화 응답을 로컬 분석용 JSONL로 보존한다.
 *
 * 기본 활성화지만 test APK의 외부 files 경로에만 쓰고 logcat에는 값 내용을 남기지 않는다. 실행
 * 스크립트가 파일을 가져간 뒤 test APK를 제거하므로 기기에도 남지 않는다.
 */
internal class DetailedTraceWriter private constructor(private val file: File?) {
    fun recordDecision(step: Int, decision: DisplayedDecision) = append(
        JSONObject()
            .put("event", "decision")
            .put("step", step)
            .put("generation", decision.generation)
            // 시그니처 원문도 화면 label을 포함하므로 비교 가능한 단방향 digest만 남긴다.
            .put("request_signature_sha256", sha256(decision.requestSignature))
            // 도그푸딩 device_id는 분석에 필요 없고 가명 식별자이므로 로컬 trace에서도 제거한다.
            .put("request", JSONObject(decision.requestJson).apply { remove("device_id") })
            .put("response", JSONObject(decision.responseJson))
            .put(
                "resolved_target",
                JSONObject()
                    .put("bounds", decision.targetBounds?.let(::JSONArray) ?: JSONObject.NULL)
                    .put("role", decision.targetRole ?: JSONObject.NULL)
                    .put("clickable", decision.targetClickable)
                    .put("editable", decision.targetEditable),
            ),
    )

    fun recordStepOutcome(step: Int, outcome: String, failure: Throwable? = null) = append(
        JSONObject()
            .put("event", "step_outcome")
            .put("step", step)
            .put("outcome", outcome)
            .put("failure_code", (failure as? GuidedScenarioFailure)?.code ?: JSONObject.NULL),
    )

    fun recordDeviation(step: Int, code: String) = append(
        JSONObject()
            .put("event", "step_deviation")
            .put("step", step)
            .put("code", code),
    )

    fun recordDiscard(reason: String) = append(
        JSONObject()
            .put("event", "decision_discarded")
            .put("reason", reason),
    )

    fun recordCleanup(outcome: String) = append(
        JSONObject()
            .put("event", "cleanup")
            .put("outcome", outcome),
    )

    private fun append(value: JSONObject) {
        val destination = file ?: return
        runCatching { destination.appendText(value.toString() + "\n", Charsets.UTF_8) }
            .onFailure { Log.i(TRACE_TAG, "private_trace=write_failed") }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        const val FILE_NAME = "davi-e2e-private-trace.jsonl"
        private const val TRACE_TAG = "DaviE2e"

        /**
         * @param handlesPersonalData 시나리오가 화면에 개인정보를 띄우면 인자와 무관하게 끈다(#134).
         *   상세 trace는 화면 label을 그대로 담으므로, 입력한 수신자 번호가 이후 모든 snapshot에
         *   남는다. 켜는 방향으로는 작동하지 않는다.
         */
        fun create(context: Context, handlesPersonalData: Boolean = false): DetailedTraceWriter {
            if (handlesPersonalData) {
                Log.i(TRACE_TAG, "private_trace=disabled_personal_data")
                return DetailedTraceWriter(null)
            }
            val enabled = InstrumentationRegistry.getArguments()
                .getString("davi.detailedReport")
                ?.toBooleanStrictOrNull() ?: true
            if (!enabled) return DetailedTraceWriter(null)
            val directory = context.getExternalFilesDir(null)
            if (directory == null) {
                Log.i(TRACE_TAG, "private_trace=unavailable")
                return DetailedTraceWriter(null)
            }
            val destination = File(directory, FILE_NAME)
            runCatching {
                directory.mkdirs()
                destination.writeText("", Charsets.UTF_8)
            }.onFailure {
                Log.i(TRACE_TAG, "private_trace=init_failed")
                return DetailedTraceWriter(null)
            }
            return DetailedTraceWriter(destination)
        }
    }
}
