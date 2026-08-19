package com.davi.helper.e2e

import androidx.test.uiautomator.UiDevice

/**
 * 카카오 T 택시 pre-call 시나리오의 사전 조건·독립 검증·정리만 담당한다.
 *
 * 호출 전에 끝나므로 지울 데이터가 없다 — 정리는 앱 종료로 진행 중인 호출 준비 상태를 버리는
 * 것까지다. 2026-08-19 기준 두 검증 기기 모두 카카오 T 미설치라 selector는 실기기 미검증이다.
 */
internal class KakaoTaxiRobot(private val device: UiDevice) {
    fun forceStop() = GuidedAppOps.forceStop(device, PACKAGE)

    fun isForeground(): Boolean = GuidedAppOps.isForeground(device, PACKAGE)

    /**
     * 목적지가 보라매사옥으로 잡힌 채 호출 직전에 서 있는가.
     *
     * 목적지 문구와 호출 컨트롤이 함께 보일 때만 참으로 본다. done은 서버 완료 검증(`verified`)을
     * 함께 통과해야 성공으로 남는다.
     */
    fun isPreCallReady(): Boolean =
        isForeground() &&
            GuidedAppOps.hasVisibleLabelLike(device, DESTINATION_FRAGMENT) &&
            GuardedControlGuard.liveBlockedControlBoxes(device, CALL_FRAGMENTS).isNotEmpty()

    companion object {
        const val PACKAGE = "com.kakao.taxi"

        /** 목적지 확인에 쓰는 문구 조각. 정확한 건물명 표기가 흔들려도 잡히도록 짧게 둔다. */
        const val DESTINATION_FRAGMENT = "보라매"

        /** 실제 배차로 이어지는 최종 컨트롤 label 조각. 부분 문자열로 동적 금액 label까지 잡는다. */
        val CALL_FRAGMENTS = setOf("호출하기", "예약하기", "택시 호출", "바로 호출")
    }
}
