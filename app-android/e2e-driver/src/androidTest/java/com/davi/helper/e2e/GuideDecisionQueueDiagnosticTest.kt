package com.davi.helper.e2e

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

/** 서명 보호된 debug 진단 broadcast가 timeout 실패 코드까지 이어지는지 실기기에서 고정한다. */
@RunWith(AndroidJUnit4::class)
class GuideDecisionQueueDiagnosticTest {
    @Test
    fun unresolvedTargetFlagSurvivesTheSignedDecisionChannel() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        GuideDecisionQueue(context).use { queue ->
            sendAndAwait(
                context,
                Intent(GuideE2eContract.ACTION_DECISION)
                    .setPackage(context.packageName)
                    .putExtra(GuideE2eContract.EXTRA_GENERATION, 1L)
                    .putExtra(GuideE2eContract.EXTRA_SIGNATURE, "signature")
                    .putExtra(GuideE2eContract.EXTRA_SCREEN_PACKAGE, "com.example")
                    .putExtra(GuideE2eContract.EXTRA_ACTION_TYPE, "tap")
                    .putExtra(GuideE2eContract.EXTRA_TARGET_NODE_ID, "missing-node")
                    .putExtra(GuideE2eContract.EXTRA_INSTRUCTION, "이 대상을 눌러주세요.")
                    .putExtra(GuideE2eContract.EXTRA_EXPECTED_RESULT, "다음 화면이 보입니다.")
                    .putExtra(GuideE2eContract.EXTRA_EVIDENCE, "대상이 화면에 보입니다.")
                    .putExtra(GuideE2eContract.EXTRA_TARGET_RESOLVED, false)
                    .putExtra(GuideE2eContract.EXTRA_VERIFIED, false)
                    .putExtra(GuideE2eContract.EXTRA_COMPLETION_VERDICT, "inconclusive")
                    .putExtra(GuideE2eContract.EXTRA_REQUEST_GZIP, gzip("{}"))
                    .putExtra(GuideE2eContract.EXTRA_RESPONSE_GZIP, gzip("{}")),
            )

            val decision = queue.await(
                timeoutMs = 1_000,
                step = 1,
                expectedGeneration = null,
                previousSignature = null,
                onDiscard = {},
            )
            val failure = assertThrows(GuidedScenarioFailure::class.java) {
                GuideInstructionQuality.validate(decision, step = 1)
            }
            assertEquals("target_node_id_mismatch", failure.code)
        }
    }

    @Test
    fun rootServerAndScreenDiagnosticsRemainDistinctAtDecisionTimeout() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        GuideDecisionQueue(context).use { queue ->
            sendDiagnosticAndAwait(context, "root_unavailable")
            assertEquals("root_unavailable", timeoutCode(queue, step = 1))

            sendDiagnosticAndAwait(context, "root_recovered")
            sendDiagnosticAndAwait(context, "server_error")
            assertEquals("server_error", timeoutCode(queue, step = 2))

            sendDiagnosticAndAwait(context, "request_started")
            assertEquals("decision_timeout", timeoutCode(queue, step = 3))

            sendDiagnosticAndAwait(context, "screen_stuck")
            assertEquals("screen_stuck", timeoutCode(queue, step = 4))

            sendDiagnosticAndAwait(context, "request_started")
            sendDiagnosticAndAwait(context, "screen_unreadable")
            assertEquals("screen_unreadable", timeoutCode(queue, step = 5))
        }
    }

    private fun sendDiagnosticAndAwait(context: Context, value: String) {
        sendAndAwait(
            context,
            Intent(GuideE2eContract.ACTION_DIAGNOSTIC)
                .setPackage(context.packageName)
                .putExtra(GuideE2eContract.EXTRA_DIAGNOSTIC, value),
        )
    }

    private fun sendAndAwait(context: Context, intent: Intent) {
        val delivered = CountDownLatch(1)
        context.sendOrderedBroadcast(
            intent,
            GuideE2eContract.PERMISSION,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    delivered.countDown()
                }
            },
            null,
            0,
            null,
            null,
        )
        assertTrue("E2E broadcast timed out", delivered.await(5, TimeUnit.SECONDS))
    }

    private fun timeoutCode(queue: GuideDecisionQueue, step: Int): String {
        val failure = assertThrows(GuidedScenarioFailure::class.java) {
            queue.await(
                timeoutMs = 20,
                step = step,
                expectedGeneration = null,
                previousSignature = null,
                onDiscard = {},
            )
        }
        return failure.code
    }

    private fun gzip(value: String): ByteArray = ByteArrayOutputStream().use { output ->
        GZIPOutputStream(output).bufferedWriter(Charsets.UTF_8).use { it.write(value) }
        output.toByteArray()
    }
}
