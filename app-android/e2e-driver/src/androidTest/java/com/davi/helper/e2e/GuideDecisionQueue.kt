package com.davi.helper.e2e

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import java.io.Closeable
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

internal class GuideDecisionQueue(private val context: Context) : Closeable {
    private val decisions = LinkedBlockingQueue<Result<DisplayedDecision>>()
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != GuideE2eContract.ACTION_DECISION) return
            decisions.offer(runCatching { intent.toDecision() })
        }
    }

    init {
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(GuideE2eContract.ACTION_DECISION),
            GuideE2eContract.PERMISSION,
            null,
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    fun await(timeoutMs: Long, step: Int): DisplayedDecision {
        val result = decisions.poll(timeoutMs, TimeUnit.MILLISECONDS)
            ?: throw GuidedScenarioFailure("decision_timeout", step)
        return result.getOrElse { throw GuidedScenarioFailure("decision_contract_invalid", step) }
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
            instruction = getStringExtra(GuideE2eContract.EXTRA_INSTRUCTION).orEmpty(),
            expectedResult = getStringExtra(GuideE2eContract.EXTRA_EXPECTED_RESULT).orEmpty(),
            evidence = getStringExtra(GuideE2eContract.EXTRA_EVIDENCE).orEmpty(),
        ),
        targetBounds = getIntArrayExtra(GuideE2eContract.EXTRA_TARGET_BOUNDS),
        targetRole = getStringExtra(GuideE2eContract.EXTRA_TARGET_ROLE),
        targetClickable = getBooleanExtra(GuideE2eContract.EXTRA_TARGET_CLICKABLE, false),
        targetEditable = getBooleanExtra(GuideE2eContract.EXTRA_TARGET_EDITABLE, false),
        verified = getBooleanExtra(GuideE2eContract.EXTRA_VERIFIED, false),
    )
}
