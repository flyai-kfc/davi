package com.davi.helper.e2e

import androidx.test.uiautomator.UiDevice

/**
 * T월드 요금안내서 시나리오의 사전 조건·독립 검증·정리만 담당한다.
 *
 * 요금안내서 열람은 읽기 전용이라 지울 데이터가 없다. selector는 2026-08-19 기준 실기기
 * (T월드 6.1.13) 미검증이라 화면 문구 기반의 성긴 판정만 둔다.
 */
internal class TworldRobot(private val device: UiDevice) {
    fun forceStop() = GuidedAppOps.forceStop(device, PACKAGE)

    fun isForeground(): Boolean = GuidedAppOps.isForeground(device, PACKAGE)

    /** 요금안내서(청구 요금) 화면이 떠 있는가. 문구 기반이라 done의 `verified`와 함께 판정된다. */
    fun isBillVisible(): Boolean =
        isForeground() && BILL_FRAGMENTS.any { GuidedAppOps.hasVisibleLabelLike(device, it) }

    companion object {
        const val PACKAGE = "com.sktelecom.minit"

        /** 요금안내서 화면에서 하나는 보일 것으로 기대하는 문구 조각. */
        val BILL_FRAGMENTS = setOf("요금안내서", "납부하실", "청구요금", "청구 요금", "청구금액")

        /** 요금 납부·결제로 이어지는 최종 컨트롤 label 조각. 열람 목표에서는 눌릴 이유가 없다. */
        val PAYMENT_FRAGMENTS = setOf("납부하기", "결제하기", "자동납부 신청")
    }
}
