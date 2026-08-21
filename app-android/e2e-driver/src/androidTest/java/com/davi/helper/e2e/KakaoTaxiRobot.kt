package com.davi.helper.e2e

import androidx.test.uiautomator.UiDevice

/**
 * 카카오 T 택시 pre-call 시나리오의 사전 조건·독립 검증·정리만 담당한다.
 *
 * 호출 전에 끝나므로 지울 데이터가 없다 — 정리는 앱 종료로 진행 중인 호출 준비 상태를 버리는
 * 것까지다. 2026-08-21 실측(7.12.1) 기준 로그인 전 화면까지만 확인했고, 도착지·호출 화면의
 * selector는 로그인된 기기에서 아직 미검증이다.
 */
internal class KakaoTaxiRobot(private val device: UiDevice) {
    fun forceStop() = GuidedAppOps.forceStop(device, PACKAGE)

    fun isForeground(): Boolean = GuidedAppOps.isForeground(device, PACKAGE)

    /**
     * 로그인 전 화면에 서 있는가.
     *
     * 이 상태면 도착지 흐름이 시작되지도 않으므로 시나리오는 여기서 사전 조건 실패로 끝낸다.
     * 로그인 화면에서 헤매다 엉뚱한 코드로 실패하면 원인이 selector 문제로 잘못 읽힌다.
     */
    fun isLoginScreen(): Boolean =
        isForeground() && LOGIN_FRAGMENTS.any { GuidedAppOps.hasVisibleLabelLike(device, it) }

    /** 도착지를 입력하는 화면인가. TYPE 하나로 목적지 입력을 단정하지 않기 위한 판정이다. */
    fun isDestinationInputVisible(): Boolean =
        isForeground() &&
            DESTINATION_INPUT_FRAGMENTS.any { GuidedAppOps.hasVisibleLabelLike(device, it) }

    /**
     * 목적지가 보라매사옥으로 잡힌 채 호출 직전에 서 있는가.
     *
     * 목적지 문구와 호출 컨트롤이 함께 보일 때만 참으로 본다. done은 서버 완료 검증(`verified`)을
     * 함께 통과해야 성공으로 남는다.
     */
    fun isPreCallReady(): Boolean =
        isForeground() &&
            GuidedAppOps.hasVisibleLabelLike(device, DESTINATION_FRAGMENT) &&
            GuardedControlGuard.liveBlockedControlBoxes(device, FINAL_CALL_FRAGMENTS).isNotEmpty()

    companion object {
        const val PACKAGE = "com.kakao.taxi"

        /**
         * 로그인 전 화면으로 보는 문구 조각. 2026-08-21 실측 — 미로그인 첫 화면은
         * "카카오계정으로 시작하기" 버튼 하나다.
         */
        val LOGIN_FRAGMENTS = setOf("카카오계정으로 시작", "카카오 계정으로 시작")

        /** 도착지 입력 화면으로 보는 문구 조각. 실기기 미검증이라 성기게 둔다. */
        val DESTINATION_INPUT_FRAGMENTS = setOf("어디로", "도착지", "목적지")

        /** 목적지 확인에 쓰는 문구 조각. 정확한 건물명 표기가 흔들려도 잡히도록 짧게 둔다. */
        const val DESTINATION_FRAGMENT = "보라매"

        /** 실제 배차로 이어지는 최종 컨트롤 label 조각. 부분 문자열로 동적 금액 label까지 잡는다. */
        val CALL_FRAGMENTS = setOf("호출하기", "예약하기", "택시 호출", "바로 호출")

        /**
         * 목표 도달 증거로 쓰는 최종 호출 컨트롤. 차단 대상보다 좁다.
         *
         * "예약하기"는 도착지를 정하기 전 지도 홈에도 상시 떠 있는 플로팅 버튼이라, 증거로 쓰면
         * 도착지 선택 전 화면도 호출 직전으로 통과한다 — 2026-08-21 실측에서 지도 홈이
         * `isPreCallReady()`를 통과했다. 차단은 넓게(예약 포함), 도달 판정은 좁게 본다.
         */
        val FINAL_CALL_FRAGMENTS = setOf("호출하기", "택시 호출", "바로 호출")
    }
}
