package com.davi.helper.e2e

import androidx.test.platform.app.InstrumentationRegistry
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
     * @param sendControlRequired 지금이 전송 컨트롤을 볼 수 있어야 하는 단계인가. 시나리오가
     *   자기 진행 상태로 판단한다(예: composer에 들어온 뒤부터 `true`). 매 결정마다 평가한다.
     */
    fun sendControlBoundary(
        device: UiDevice = defaultDevice(),
        packages: Set<String> = MessagingSendSafety.MESSAGING_PACKAGES,
        safetyMarginPx: Int = MessagingSendSafety.DEFAULT_SAFETY_MARGIN_PX,
        sendControlRequired: () -> Boolean,
    ): GuidedHardBoundary {
        require(packages.isNotEmpty()) { "messaging packages must not be empty" }
        require(packages.all(String::isNotBlank)) { "messaging packages must be non-blank" }

        return GuidedHardBoundary { decision ->
            if (decision.action.actionType !in MessagingSendSafety.TAP_INJECTING_ACTIONS ||
                decision.screenPackage !in packages
            ) {
                return@GuidedHardBoundary null
            }
            val elements = decision.requestScreenElements()
                ?: return@GuidedHardBoundary MessagingSendSafety.SEND_TARGET_UNVERIFIABLE
            // 모델이 본 화면(snapshot)과 지금 화면(스캔)을 합쳐서 본다. snapshot에는
            // resource-id가 없고, 스캔은 label이 빈 ImageButton도 id로 잡는다.
            val sendBoxes = (
                MessagingSendSafety.sendControlBoxes(elements) + liveSendControlBoxes(device)
                ).distinct()
            MessagingSendSafety.decisionFailureCode(
                actionType = decision.action.actionType,
                screenPackage = decision.screenPackage,
                messagingPackages = packages,
                targetBox = MessagingSendSafety.Box.fromValues(decision.targetBounds),
                targetLabel = decision.requestTargetLabel(),
                sendBoxes = sendBoxes,
                sendControlRequired = sendControlRequired(),
                safetyMarginPx = safetyMarginPx,
            )
        }
    }

    /**
     * 시나리오가 **직접 좌표를 누를 때**(준비·정리 단계) 거는 같은 규칙의 가드.
     *
     * 모델 결정이 아니라 시나리오가 하드코딩한 좌표가 문제였다 — 레이아웃이 조금만 바뀌면 옆 칸의
     * 전송 버튼을 누른다. 여기서는 snapshot이 없으므로 **살아 있는 화면**을 직접 스캔한다.
     *
     * @param sendControlRequired 기본 `true`. 전송 컨트롤을 하나도 식별하지 못하면 누르지 않고
     *   실패한다 — 못 찾은 것과 없는 것을 구분할 수 없기 때문이다(#211 리뷰). 전송 컨트롤이
     *   아직 없는 화면(대화 목록 등)에서 쓸 때만 `false`로 내린다.
     */
    fun tapOrFail(
        device: UiDevice,
        x: Int,
        y: Int,
        step: Int? = null,
        sendControlRequired: Boolean = true,
    ) {
        val sendBoxes = liveSendControlBoxes(device)
        if (sendBoxes.isEmpty()) {
            if (sendControlRequired) {
                throw GuidedScenarioFailure(MessagingSendSafety.SEND_CONTROL_UNIDENTIFIED, step)
            }
        } else if (MessagingSendSafety.blocksTapPoint(x, y, sendBoxes)) {
            throw GuidedScenarioFailure(MessagingSendSafety.SEND_PROXIMITY_BLOCKED, step)
        }
        if (!device.click(x, y)) {
            throw GuidedScenarioFailure("tap_injection_failed", step)
        }
    }

    /**
     * 현재 화면에서 전송 컨트롤로 보이는 것들의 사각형.
     *
     * text·contentDescription과 **resource-id**를 함께 훑는다. 삼성 메시지의 전송 버튼은
     * ImageButton이라 text가 비고 contentDescription만 있거나 둘 다 빌 수 있는데, 그때도 id는
     * 남는다. 정답 selector로 모델의 다음 행동을 보정하는 것이 아니라 **위험 컨트롤을 식별해
     * 차단**하는 용도이므로 안전 가드에서는 허용된다(#211 리뷰).
     */
    fun liveSendControlBoxes(device: UiDevice): List<MessagingSendSafety.Box> {
        val found = LinkedHashSet<MessagingSendSafety.Box>()
        val selectors = mutableListOf<BySelector>()
        MessagingSendSafety.SEND_LABELS.forEach { label ->
            // label을 정규식으로 해석하지 않도록 quote한다. 대소문자만 무시한다.
            val pattern = Pattern.compile(Pattern.quote(label), Pattern.CASE_INSENSITIVE)
            selectors += By.text(pattern)
            selectors += By.desc(pattern)
        }
        // id로 찾을 때는 누를 수 있는 것만 본다. "sender" 같은 표시용 뷰까지 전송으로 보면
        // 정상 조작이 과하게 막힌다.
        selectors += By.res(SEND_RESOURCE_ID).clickable(true)

        selectors.forEach { selector ->
            runCatching { device.findObjects(selector) }.getOrNull().orEmpty().forEach { candidate ->
                val bounds = runCatching { candidate.visibleBounds }.getOrNull() ?: return@forEach
                MessagingSendSafety.Box.fromValues(
                    intArrayOf(bounds.left, bounds.top, bounds.right, bounds.bottom),
                )?.let(found::add)
            }
        }
        return found.toList()
    }

    private fun defaultDevice(): UiDevice =
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    /** `...:id/send_button`, `...:id/sendButton` 등 전송 버튼이 흔히 쓰는 id. */
    private val SEND_RESOURCE_ID: Pattern =
        Pattern.compile(".*:id/[a-z_]*send[a-z_]*", Pattern.CASE_INSENSITIVE)
}
