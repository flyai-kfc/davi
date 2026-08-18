package com.davi.helper.e2e

import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import java.util.regex.Pattern

/**
 * [MessagingSendSafety] 순수 판정부를 실제 결정·실제 화면에 붙이는 기기 쪽 어댑터.
 *
 * 판정 규칙은 여기 두지 않는다. 여기는 "무엇을 읽어서 넣을지"만 정한다.
 */
internal object MessagingSendGuard {
    /**
     * 모델 결정을 손동작으로 옮기기 전에 거는 하드 경계.
     *
     * snapshot을 읽지 못하면 통과시키지 않는다. 문자 앱에서 "확인하지 못했다"를 "안전하다"로
     * 읽으면 이 경계는 있으나 마나다(#134).
     */
    fun sendControlBoundary(
        packages: Set<String> = MessagingSendSafety.MESSAGING_PACKAGES,
        safetyMarginPx: Int = MessagingSendSafety.DEFAULT_SAFETY_MARGIN_PX,
    ): GuidedHardBoundary {
        require(packages.isNotEmpty()) { "messaging packages must not be empty" }
        require(packages.all(String::isNotBlank)) { "messaging packages must be non-blank" }

        return GuidedHardBoundary { decision ->
            when {
                decision.action.actionType != GuidedActionType.TAP -> null
                decision.screenPackage !in packages -> null
                else -> {
                    val elements = decision.requestScreenElements()
                        ?: return@GuidedHardBoundary MessagingSendSafety.SEND_TARGET_UNVERIFIABLE
                    MessagingSendSafety.tapFailureCode(
                        targetBox = MessagingSendSafety.Box.fromValues(decision.targetBounds),
                        targetLabel = decision.requestTargetLabel(),
                        elements = elements,
                        safetyMarginPx = safetyMarginPx,
                    )
                }
            }
        }
    }

    /**
     * 시나리오가 **직접 좌표를 누를 때**(준비·정리 단계) 거는 같은 규칙의 가드.
     *
     * 모델 결정이 아니라 시나리오가 하드코딩한 좌표가 문제였다 — 레이아웃이 조금만 바뀌면 옆 칸의
     * 전송 버튼을 누른다. 여기서는 snapshot이 없으므로 **살아 있는 화면**을 직접 스캔한다.
     *
     * 누르지 않고 [GuidedScenarioFailure]를 던진다. 시나리오는 이 예외를 삼키지 않는다.
     */
    fun tapOrFail(device: UiDevice, x: Int, y: Int, step: Int? = null) {
        val sendBoxes = liveSendControlBoxes(device)
        if (MessagingSendSafety.blocksTapPoint(x, y, sendBoxes)) {
            throw GuidedScenarioFailure(MessagingSendSafety.SEND_PROXIMITY_BLOCKED, step)
        }
        if (!device.click(x, y)) {
            throw GuidedScenarioFailure("tap_injection_failed", step)
        }
    }

    /**
     * 현재 화면에서 전송 컨트롤로 보이는 것들의 사각형.
     *
     * text와 contentDescription을 모두 훑는다. 삼성 메시지의 전송 버튼은 ImageButton이라 text가
     * 비고 contentDescription만 있는 경우가 있다.
     */
    fun liveSendControlBoxes(device: UiDevice): List<MessagingSendSafety.Box> {
        val found = LinkedHashSet<MessagingSendSafety.Box>()
        MessagingSendSafety.SEND_LABELS.forEach { label ->
            // label을 정규식으로 해석하지 않도록 quote한다. 대소문자만 무시한다.
            val pattern = Pattern.compile(Pattern.quote(label), Pattern.CASE_INSENSITIVE)
            listOf<BySelector>(By.text(pattern), By.desc(pattern)).forEach { selector ->
                runCatching { device.findObjects(selector) }.getOrNull().orEmpty()
                    .forEach { candidate ->
                        val bounds = runCatching { candidate.visibleBounds }.getOrNull()
                            ?: return@forEach
                        MessagingSendSafety.Box.fromValues(
                            intArrayOf(bounds.left, bounds.top, bounds.right, bounds.bottom),
                        )?.let(found::add)
                    }
            }
        }
        return found.toList()
    }
}
