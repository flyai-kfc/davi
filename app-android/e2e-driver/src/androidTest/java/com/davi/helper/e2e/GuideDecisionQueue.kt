package com.davi.helper.e2e

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

internal class GuideDecisionQueue(private val context: Context) : Closeable {
    private val decisions = LinkedBlockingQueue<Result<DisplayedDecision>>()
    private val diagnostics = GuideE2eDiagnosticState()
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                GuideE2eContract.ACTION_DECISION -> {
                    diagnostics.reset()
                    decisions.offer(runCatching { intent.toDecision() })
                }
                GuideE2eContract.ACTION_DIAGNOSTIC ->
                    GuideE2eDiagnostic.fromWire(
                        intent.getStringExtra(GuideE2eContract.EXTRA_DIAGNOSTIC),
                    )?.let(diagnostics::observe)
            }
        }
    }

    init {
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter().apply {
                addAction(GuideE2eContract.ACTION_DECISION)
                addAction(GuideE2eContract.ACTION_DIAGNOSTIC)
            },
            GuideE2eContract.PERMISSION,
            null,
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    fun await(
        timeoutMs: Long,
        step: Int,
        expectedGeneration: Long?,
        previousSignature: String?,
        onDiscard: (String) -> Unit,
    ): DisplayedDecision {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        var candidate: DisplayedDecision? = null
        while (candidate == null) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) throw timeoutFailure(step)
            val result = decisions.poll(remaining, TimeUnit.NANOSECONDS)
                ?: throw timeoutFailure(step)
            val decision = result.getOrElse {
                throw GuidedScenarioFailure("decision_contract_invalid", step)
            }
            if (expectedGeneration != null && decision.generation != expectedGeneration) {
                onDiscard("session_changed")
            } else if (previousSignature != null && decision.requestSignature == previousSignature) {
                onDiscard("previous_screen")
            } else {
                candidate = decision
            }
        }

        // 검증·조작 중 쌓인 결정이 있으면 가장 최근의 유효 결정만 사용한다.
        while (true) {
            val next = decisions.poll() ?: break
            val decision = next.getOrElse {
                throw GuidedScenarioFailure("decision_contract_invalid", step)
            }
            if (expectedGeneration != null && decision.generation != expectedGeneration) {
                onDiscard("session_changed")
            } else if (previousSignature != null && decision.requestSignature == previousSignature) {
                onDiscard("previous_screen")
            } else {
                onDiscard("superseded")
                candidate = decision
            }
        }
        return checkNotNull(candidate)
    }

    fun discardPending(onDiscard: (String) -> Unit) {
        while (decisions.poll() != null) onDiscard("before_execute")
    }

    fun resetDiagnostics() {
        diagnostics.reset()
    }

    override fun close() {
        runCatching { context.unregisterReceiver(receiver) }
    }

    private fun Intent.toDecision(): DisplayedDecision = DisplayedDecision(
        generation = getLongExtra(GuideE2eContract.EXTRA_GENERATION, -1L)
            .takeIf { it >= 0 } ?: throw IllegalArgumentException("generation"),
        requestSignature = getStringExtra(GuideE2eContract.EXTRA_SIGNATURE)
            ?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("signature"),
        screenPackage = getStringExtra(GuideE2eContract.EXTRA_SCREEN_PACKAGE)
            ?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("package"),
        action = ObservedGuideAction(
            actionType = GuidedActionType.fromWire(
                getStringExtra(GuideE2eContract.EXTRA_ACTION_TYPE),
            ),
            direction = GuidedSwipeDirection.fromWire(
                getStringExtra(GuideE2eContract.EXTRA_DIRECTION),
            ),
            targetNodeId = getStringExtra(GuideE2eContract.EXTRA_TARGET_NODE_ID),
            instruction = getStringExtra(GuideE2eContract.EXTRA_INSTRUCTION).orEmpty(),
            expectedResult = getStringExtra(GuideE2eContract.EXTRA_EXPECTED_RESULT).orEmpty(),
            evidence = getStringExtra(GuideE2eContract.EXTRA_EVIDENCE).orEmpty(),
        ),
        targetBounds = getIntArrayExtra(GuideE2eContract.EXTRA_TARGET_BOUNDS),
        targetResolved = getBooleanExtra(GuideE2eContract.EXTRA_TARGET_RESOLVED, false),
        targetRole = getStringExtra(GuideE2eContract.EXTRA_TARGET_ROLE),
        targetClickable = getBooleanExtra(GuideE2eContract.EXTRA_TARGET_CLICKABLE, false),
        targetEditable = getBooleanExtra(GuideE2eContract.EXTRA_TARGET_EDITABLE, false),
        verified = getBooleanExtra(GuideE2eContract.EXTRA_VERIFIED, false),
        completionVerdict = GuidedCompletionVerdict.fromWire(
            getStringExtra(GuideE2eContract.EXTRA_COMPLETION_VERDICT),
        ),
        requestJson = unzip(GuideE2eContract.EXTRA_REQUEST_GZIP),
        responseJson = unzip(GuideE2eContract.EXTRA_RESPONSE_GZIP),
    )

    private fun timeoutFailure(step: Int) =
        GuidedScenarioFailure(diagnostics.timeoutFailureCode(), step)

    private fun Intent.unzip(name: String): String {
        val compressed = getByteArrayExtra(name) ?: throw IllegalArgumentException(name)
        return GZIPInputStream(ByteArrayInputStream(compressed)).bufferedReader(Charsets.UTF_8).use {
            it.readText()
        }
    }
}
