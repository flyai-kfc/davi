package com.davi.helper.e2e

import java.util.Locale

/**
 * 택시 호출·주문·결제·전송처럼 **기기 밖으로 나가면 되돌릴 수 없는** 최종 컨트롤의 탭을 막는
 * 순수 판정부. [MessagingSendSafety]의 좌표 규칙(#134·#211)을 문자 전송 밖 앱으로 일반화했다.
 *
 * 문자 전송과 다른 점은 label 판정 하나다 — 정확 일치가 아니라 **부분 문자열**로 본다.
 * "4,800원 호출하기"·"배달 주문하기"처럼 금액·수량이 섞인 동적 label이 흔해서 정확 일치는
 * 그대로 통과시키기 때문이다. 넓게 잡혀 정상 진행이 막히면 테스트가 실패로 끝날 뿐 기기 밖
 * 부작용은 없다 — 두 방향의 오차 중 안전한 쪽으로 넓다.
 *
 * Android API를 쓰지 않는다 — CI의 JVM 단위 테스트에서 매번 돌리기 위해서다. 화면을 읽어 이
 * 판정부에 넣는 어댑터([GuardedControlGuard])만 기기 쪽에 둔다.
 */
object GuardedControlSafety {
    /** 실패 코드가 `<prefix>_target_blocked` 꼴로 안전하게 조립되도록 접두어를 제한한다. */
    val SAFE_CODE_PREFIX = Regex("^[a-z][a-z0-9_]*$")

    fun containsBlockedLabel(label: String?, fragments: Set<String>): Boolean {
        val normalized = normalize(label)
        if (normalized.isEmpty()) return false
        return fragments.any { normalized.contains(normalize(it)) }
    }

    /** 화면 요소 중 차단 대상 컨트롤로 판정되는 것들의 사각형. */
    fun blockedControlBoxes(
        elements: List<MessagingSendSafety.ScreenElement>,
        fragments: Set<String>,
    ): List<MessagingSendSafety.Box> =
        elements.filter { containsBlockedLabel(it.label, fragments) }.mapNotNull { it.bounds }

    /**
     * 결정 하나를 실행해도 되는가. 막아야 하면 실패 코드를, 안전하면 `null`을 돌려준다.
     *
     * 규칙은 [MessagingSendSafety.tapFailureCode]와 같다 — label로 못 잡는 경로는 좌표가 잡고,
     * "확인하지 못했다"를 "안전하다"로 읽지 않는다.
     *
     * @param controlRequired 지금이 차단 대상 컨트롤을 **볼 수 있어야 하는 단계**인가.
     *   `true`인데 [blockedBoxes]가 비면 통과시키지 않는다(#211 리뷰). 컨트롤이 아직 없는
     *   초기 화면에서는 `false`를 준다 — 시나리오가 자기 진행 단계로 판단한다.
     */
    fun decisionFailureCode(
        codePrefix: String,
        actionType: GuidedActionType,
        screenPackage: String,
        guardedPackages: Set<String>,
        targetBox: MessagingSendSafety.Box?,
        targetLabel: String?,
        blockedBoxes: List<MessagingSendSafety.Box>,
        blockedLabelFragments: Set<String>,
        controlRequired: Boolean,
        safetyMarginPx: Int = MessagingSendSafety.DEFAULT_SAFETY_MARGIN_PX,
    ): String? {
        require(SAFE_CODE_PREFIX.matches(codePrefix)) { "unsafe code prefix" }
        require(safetyMarginPx >= 0) { "safety margin must not be negative" }
        if (actionType !in MessagingSendSafety.TAP_INJECTING_ACTIONS) return null
        if (screenPackage !in guardedPackages) return null

        if (containsBlockedLabel(targetLabel, blockedLabelFragments)) {
            return "${codePrefix}_target_blocked"
        }
        if (blockedBoxes.isEmpty()) {
            return if (controlRequired) "${codePrefix}_control_unidentified" else null
        }
        // 컨트롤이 화면에 있는데 어디를 누를지 모르면 안전을 증명할 수 없다. 통과시키지 않는다.
        if (targetBox == null) return "${codePrefix}_target_unverifiable"

        return if (
            MessagingSendSafety.blocksTapPoint(
                targetBox.centerX,
                targetBox.centerY,
                blockedBoxes,
                safetyMarginPx,
            )
        ) {
            "${codePrefix}_proximity_blocked"
        } else {
            null
        }
    }

    private fun normalize(label: String?): String = label?.trim()?.lowercase(Locale.ROOT).orEmpty()
}
