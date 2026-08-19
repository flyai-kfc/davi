package com.davi.helper.e2e

import androidx.test.uiautomator.UiDevice

/**
 * 배달의민족 pre-order 시나리오의 사전 조건·독립 검증·정리만 담당한다.
 *
 * 주문 전에 끝나지만 **장바구니는 계정에 남는다** — 담긴 메뉴가 다음 실행과 실제 사용자를
 * 오염시키므로 성공·실패와 무관하게 비운다. selector는 2026-08-19 기준 실기기(배달의민족
 * 16.18.0) 미검증이라 화면 문구 기반으로만 움직인다.
 */
internal class BaeminRobot(private val device: UiDevice) {
    fun forceStop() = GuidedAppOps.forceStop(device, PACKAGE)

    fun isForeground(): Boolean = GuidedAppOps.isForeground(device, PACKAGE)

    /** 메뉴가 담긴 채 주문 직전에 서 있는가. done은 서버 완료 검증(`verified`)과 함께 판정된다. */
    fun isPreOrderReady(): Boolean =
        isForeground() &&
            GuardedControlGuard.liveBlockedControlBoxes(device, ORDER_FRAGMENTS).isNotEmpty()

    /**
     * 장바구니를 비운다. UI를 통해서만 지울 수 있어 문구 기반으로 진행하고, 비웠음을 확인하지
     * 못하면 구조화 코드로 실패를 남긴다 — "확인하지 못했다"를 "비웠다"로 읽지 않는다.
     */
    fun clearCart() {
        forceStop()
        GuidedAppOps.launch(device, PACKAGE)
        dismissPopupIfAny()

        val cart = GuidedAppOps.findVisibleLabelLike(device, "장바구니")
            ?: throw GuidedScenarioFailure("cart_entry_missing")
        cart.click()
        device.waitForIdle(IDLE_TIMEOUT_MS)
        if (isCartEmpty()) return

        val clearAll = GuidedAppOps.findVisibleLabelLike(device, "전체삭제")
            ?: GuidedAppOps.findVisibleLabelLike(device, "전체 삭제")
            ?: throw GuidedScenarioFailure("cart_clear_control_missing")
        clearAll.click()
        device.waitForIdle(IDLE_TIMEOUT_MS)
        // 확인 대화상자가 뜨면 삭제를 확정한다. 없으면 이미 지워진 것일 수 있어 상태로 판정한다.
        GuidedAppOps.findVisibleLabelLike(device, "삭제")?.click()
        device.waitForIdle(IDLE_TIMEOUT_MS)

        if (!isCartEmpty()) throw GuidedScenarioFailure("cart_cleanup_unverified")
    }

    private fun isCartEmpty(): Boolean =
        GuidedAppOps.hasVisibleLabelLike(device, "비어") ||
            GuardedControlGuard.liveBlockedControlBoxes(device, ORDER_FRAGMENTS).isEmpty()

    /** 실행 시 전면 광고·공지 팝업이 덮을 수 있다. 닫기류가 보이면 닫고, 아니면 그대로 둔다. */
    private fun dismissPopupIfAny() {
        GuidedAppOps.findVisibleLabelLike(device, "닫기")?.click()
        device.waitForIdle(IDLE_TIMEOUT_MS)
    }

    companion object {
        const val PACKAGE = "com.sampleapp"

        /** 실제 주문·결제로 이어지는 최종 컨트롤 label 조각. 부분 문자열로 동적 금액까지 잡는다. */
        val ORDER_FRAGMENTS = setOf("주문하기", "결제하기")

        private const val IDLE_TIMEOUT_MS = 1_500L
    }
}
