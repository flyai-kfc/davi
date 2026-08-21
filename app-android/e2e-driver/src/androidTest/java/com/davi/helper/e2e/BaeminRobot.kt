package com.davi.helper.e2e

import androidx.test.uiautomator.UiDevice

/**
 * 배달의민족 pre-order 시나리오의 사전 조건·독립 검증·정리만 담당한다.
 *
 * 주문 전에 끝나지만 **장바구니는 계정에 남는다** — 담긴 메뉴가 다음 실행과 실제 사용자를
 * 오염시키므로 성공·실패와 무관하게 비운다. 목표 판정·정리 selector는 2026-08-21 실기기
 * (배달의민족 16.19.0) 실측으로 맞췄다.
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

        val cart = GuidedAppOps.waitForVisibleLabelLike(device, "장바구니", ENTRY_TIMEOUT_MS)
            ?: throw GuidedScenarioFailure("cart_entry_missing")
        cart.click()
        device.waitForIdle(IDLE_TIMEOUT_MS)
        if (isCartEmpty()) return

        // 2026-08-21 실측(16.19.0) — 장바구니에 "전체삭제"가 없고 항목별 삭제(contentDescription
        // "삭제")만 있다. 전체삭제만 찾으면 담긴 상태의 첫 정리가 cart_clear_control_missing으로
        // 실패한다. 전체삭제가 있는 구성도 같은 문구로 잡히므로 항목별 반복으로 통일한다.
        repeat(MAX_CART_CLEAR_ATTEMPTS) {
            val delete = GuidedAppOps.findVisibleLabelLike(device, "삭제")
                ?: throw GuidedScenarioFailure("cart_clear_control_missing")
            delete.click()
            device.waitForIdle(IDLE_TIMEOUT_MS)
            // 확인 대화상자가 뜨면 삭제를 확정한다. 없으면 이미 지워진 것일 수 있어 상태로 판정한다.
            GuidedAppOps.findVisibleLabelLike(device, "삭제")?.click()
            device.waitForIdle(IDLE_TIMEOUT_MS)
            if (isCartEmpty()) return
        }

        throw GuidedScenarioFailure("cart_cleanup_unverified")
    }

    /**
     * 장바구니가 비었는가.
     *
     * **한계** — 2026-08-21 실측(16.19.0)에서 빈 장바구니에도 "비어" 문구가 잡히지 않았다.
     * 그래서 실질 판정은 "주문 컨트롤이 없다" 하나에 기대고, 이는 화면이 아직 그려지지 않은
     * 동안에도 참이 된다. 아직 이 앱에서 확인된 '비었음' 표식이 없어 남겨 둔 약점이다.
     */
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

        /** 항목별 삭제를 반복할 상한. 장바구니 한 건에 메뉴가 이만큼 담길 일은 없다. */
        private const val MAX_CART_CLEAR_ATTEMPTS = 12

        /** 콜드 스타트 직후 홈 화면이 그려질 때까지의 여유. */
        private const val ENTRY_TIMEOUT_MS = 10_000L

        private const val IDLE_TIMEOUT_MS = 1_500L
    }
}
