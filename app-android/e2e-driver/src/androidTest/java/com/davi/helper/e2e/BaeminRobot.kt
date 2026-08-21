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
     * 정리가 사용자 데이터를 지우지 않도록 장바구니가 비어 있을 때만 시나리오 실행을 허용한다.
     * 기존 항목이 있거나 상태를 확정하지 못하면 아무것도 지우지 않고 실패한다.
     */
    fun requireEmptyCartBeforeRun() {
        forceStop()
        GuidedAppOps.launch(device, PACKAGE)
        dismissPopupIfAny()
        openCart()

        when (cartState()) {
            CartState.EMPTY -> return
            CartState.HAS_ITEMS -> throw GuidedScenarioFailure("cart_not_empty_precondition")
            CartState.UNKNOWN -> throw GuidedScenarioFailure("cart_screen_unverified")
        }
    }

    /**
     * 장바구니를 비운다. UI를 통해서만 지울 수 있어 문구 기반으로 진행하고, 비웠음을 확인하지
     * 못하면 구조화 코드로 실패를 남긴다 — "확인하지 못했다"를 "비웠다"로 읽지 않는다.
     */
    fun clearCart() {
        forceStop()
        GuidedAppOps.launch(device, PACKAGE)
        dismissPopupIfAny()
        openCart()
        when (cartState()) {
            CartState.EMPTY -> return
            CartState.UNKNOWN -> throw GuidedScenarioFailure("cart_screen_unverified")
            CartState.HAS_ITEMS -> Unit
        }

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
            if (cartState() == CartState.EMPTY) return
        }

        throw GuidedScenarioFailure("cart_cleanup_unverified")
    }

    private fun openCart() {
        val cart = GuidedAppOps.waitForVisibleLabelLike(device, CART_SCREEN_FRAGMENT, ENTRY_TIMEOUT_MS)
            ?: throw GuidedScenarioFailure("cart_entry_missing")
        cart.click()
        device.waitForIdle(IDLE_TIMEOUT_MS)
        // 진입 버튼이 떴다고 장바구니 화면이 그려진 것은 아니다. 화면 표식을 기다리지 않으면
        // 아직 비지 않은 장바구니를 "주문 컨트롤이 없다"는 이유로 비었다고 읽는다.
        GuidedAppOps.waitForVisibleLabelLike(device, CART_SCREEN_FRAGMENT, ENTRY_TIMEOUT_MS)
            ?: throw GuidedScenarioFailure("cart_screen_unverified")
    }

    /** 장바구니 판정. "비었다"를 단정할 수 없는 상태를 따로 둔다. */
    private enum class CartState { EMPTY, HAS_ITEMS, UNKNOWN }

    /**
     * 장바구니 상태를 읽는다.
     *
     * "주문 컨트롤이 없다"만으로 비었다고 읽으면 **화면이 아직 그려지지 않은 동안에도 참**이
     * 되어, 상품이 남았는데 정리가 성공으로 끝난다(fail-open). 그래서 장바구니 화면이 실제로
     * 떠 있다는 양성 표식을 함께 요구하고, 그것마저 없으면 판정 불가로 돌려 호출부가 실패로
     * 끝내게 한다.
     *
     * 2026-08-21 실측(16.19.0) — 빈 장바구니에도 "비어"류 문구는 없었다. 확인된 빈 상태
     * 표식이 없어 "화면은 떴는데 주문 컨트롤이 없다"를 빈 상태로 본다.
     */
    private fun cartState(): CartState {
        if (!isForeground()) return CartState.UNKNOWN
        if (GuardedControlGuard.liveBlockedControlBoxes(device, ORDER_FRAGMENTS).isNotEmpty()) {
            return CartState.HAS_ITEMS
        }
        // 주소·주문 가능 여부와 무관하게 항목별 삭제 컨트롤은 기존 항목의 양성 증거다.
        if (GuidedAppOps.hasVisibleLabelLike(device, DELETE_FRAGMENT)) return CartState.HAS_ITEMS
        if (GuidedAppOps.hasVisibleLabelLike(device, "비어")) return CartState.EMPTY
        if (!GuidedAppOps.hasVisibleLabelLike(device, CART_SCREEN_FRAGMENT)) return CartState.UNKNOWN
        return CartState.EMPTY
    }

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

        /**
         * 장바구니 화면이 떠 있다는 양성 표식.
         *
         * 홈의 하단 내비게이션에도 같은 문구가 있어 단독으로는 화면을 특정하지 못한다 —
         * 진입을 누른 뒤에만 쓴다. 2026-08-21 실측에서 담긴 장바구니·빈 장바구니 양쪽에서
         * 잡혔다.
         */
        private const val CART_SCREEN_FRAGMENT = "장바구니"

        private const val DELETE_FRAGMENT = "삭제"

        /** 콜드 스타트 직후 홈 화면이 그려질 때까지의 여유. */
        private const val ENTRY_TIMEOUT_MS = 10_000L

        private const val IDLE_TIMEOUT_MS = 1_500L
    }
}
