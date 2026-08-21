package com.davi.helper.e2e

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import java.util.regex.Pattern

/**
 * [GuardedControlSafety] 순수 판정부를 실제 결정·실제 화면에 붙이는 기기 쪽 어댑터.
 *
 * [MessagingSendGuard]와 같은 구조다 — 판정 규칙은 여기 두지 않고 "무엇을 읽어서 넣을지"만
 * 정한다. 문자 전송 밖의 호출·주문·전송처럼 기기 밖 부작용이 있는 컨트롤에 쓴다.
 */
internal object GuardedControlGuard {
    /**
     * 모델 결정을 손동작으로 옮기기 전에 거는 하드 경계.
     *
     * @param labelFragments 차단 대상 컨트롤 label의 부분 문자열. 정답 selector로 모델의 다음
     *   행동을 보정하는 것이 아니라 위험 컨트롤을 식별해 차단하는 용도이므로 허용된다(#211 리뷰).
     * @param controlRequired 지금이 차단 대상 컨트롤을 볼 수 있어야 하는 단계인가. 시나리오가
     *   자기 진행 상태로 판단한다. 매 결정마다 평가한다.
     */
    fun tapBoundary(
        codePrefix: String,
        packages: Set<String>,
        labelFragments: Set<String>,
        device: UiDevice = defaultDevice(),
        safetyMarginPx: Int = MessagingSendSafety.DEFAULT_SAFETY_MARGIN_PX,
        controlRequired: () -> Boolean,
    ): GuidedHardBoundary {
        require(GuardedControlSafety.SAFE_CODE_PREFIX.matches(codePrefix)) { "unsafe code prefix" }
        require(packages.isNotEmpty()) { "guarded packages must not be empty" }
        require(packages.all(String::isNotBlank)) { "guarded packages must be non-blank" }
        require(labelFragments.isNotEmpty()) { "label fragments must not be empty" }
        require(labelFragments.all(String::isNotBlank)) { "label fragments must be non-blank" }

        return GuidedHardBoundary { decision ->
            if (decision.action.actionType !in MessagingSendSafety.TAP_INJECTING_ACTIONS ||
                decision.screenPackage !in packages
            ) {
                return@GuidedHardBoundary null
            }
            val elements = decision.requestScreenElements()
                ?: return@GuidedHardBoundary "${codePrefix}_target_unverifiable"
            // 모델이 본 화면(snapshot)과 지금 화면(스캔)을 합쳐서 본다. snapshot은 모델과 같은
            // 좌표계를 쓰고, 스캔은 label이 서버에 마스킹돼 갔어도 실제 문구를 잡는다.
            val blockedBoxes = (
                GuardedControlSafety.blockedControlBoxes(elements, labelFragments) +
                    liveBlockedControlBoxes(device, labelFragments)
                ).distinct()
            GuardedControlSafety.decisionFailureCode(
                codePrefix = codePrefix,
                actionType = decision.action.actionType,
                screenPackage = decision.screenPackage,
                guardedPackages = packages,
                targetBox = MessagingSendSafety.Box.fromValues(decision.targetBounds),
                targetLabel = decision.requestTargetLabel(),
                blockedBoxes = blockedBoxes,
                blockedLabelFragments = labelFragments,
                controlRequired = controlRequired(),
                safetyMarginPx = safetyMarginPx,
            )
        }
    }

    /** 현재 화면에서 차단 대상 컨트롤로 보이는 것들의 사각형. text·contentDescription을 훑는다. */
    fun liveBlockedControlBoxes(
        device: UiDevice,
        labelFragments: Set<String>,
    ): List<MessagingSendSafety.Box> {
        val found = LinkedHashSet<MessagingSendSafety.Box>()
        labelFragments.forEach { fragment ->
            // fragment를 정규식으로 해석하지 않도록 quote한다. 부분 문자열·대소문자만 무시한다.
            val pattern = Pattern.compile(
                ".*" + Pattern.quote(fragment) + ".*",
                Pattern.CASE_INSENSITIVE or Pattern.DOTALL,
            )
            listOf<BySelector>(By.text(pattern), By.desc(pattern)).forEach { selector ->
                runCatching { device.findObjects(selector) }.getOrNull().orEmpty().forEach { candidate ->
                    val bounds = runCatching { candidate.visibleBounds }.getOrNull() ?: return@forEach
                    MessagingSendSafety.Box.fromValues(
                        intArrayOf(bounds.left, bounds.top, bounds.right, bounds.bottom),
                    )?.let(found::add)
                }
            }
        }
        return found.toList()
    }

    private fun defaultDevice(): UiDevice =
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
}
