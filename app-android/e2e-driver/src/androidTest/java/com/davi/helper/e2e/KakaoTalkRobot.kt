package com.davi.helper.e2e

import androidx.test.uiautomator.UiDevice

/**
 * 카카오톡 pre-send 시나리오의 사전 조건·독립 검증·정리만 담당한다.
 *
 * 시나리오는 아무것도 전송하지 않고 끝나므로 지울 데이터가 없다 — 정리는 앱 종료로 선택·입력
 * 상태를 버리는 것까지다. selector는 2026-08-19 기준 실기기(카카오톡 26.7.1) 미검증이라 화면
 * 문구 기반의 성긴 판정만 둔다.
 */
internal class KakaoTalkRobot(private val device: UiDevice) {
    fun forceStop() = GuidedAppOps.forceStop(device, PACKAGE)

    fun isForeground(): Boolean = GuidedAppOps.isForeground(device, PACKAGE)

    /**
     * 사진을 고른 채 전송 직전에 서 있는가.
     *
     * 전송 컨트롤이 식별되고("전송"류 label) 선택 표시("선택"이 들어간 문구 — 선택됨·N장 선택)가
     * 함께 보일 때만 참으로 본다. 둘 다 문구 기반이라 성긴 판정이다 — done은 서버 완료 검증
     * (`verified`)을 함께 통과해야 성공으로 남는다.
     */
    fun isPreSendReady(): Boolean =
        isForeground() &&
            GuardedControlGuard.liveBlockedControlBoxes(device, SEND_FRAGMENTS).isNotEmpty() &&
            GuidedAppOps.hasVisibleLabelLike(device, "선택")

    companion object {
        const val PACKAGE = "com.kakao.talk"

        /** 채팅 전송·사진 전송 버튼이 쓰는 label 조각. 차단과 pre-send 판정이 같은 집합을 쓴다. */
        val SEND_FRAGMENTS = setOf("전송", "보내기", "send")
    }
}
