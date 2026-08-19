package com.davi.helper.e2e

import androidx.test.uiautomator.UiDevice

/**
 * 에이닷 캘린더 일정 추가 시나리오의 사전 조건·독립 검증·정리만 담당한다.
 *
 * 이 시나리오는 일정을 실제로 저장하므로 성공·실패와 무관하게 테스트 제목의 일정만 지운다.
 * selector는 2026-08-19 기준 실기기(에이닷 4.4.3) 미검증이라 테스트 제목 문구 기반으로만
 * 움직인다.
 */
internal class AdotCalendarRobot(private val device: UiDevice) {
    fun forceStop() = GuidedAppOps.forceStop(device, PACKAGE)

    fun isForeground(): Boolean = GuidedAppOps.isForeground(device, PACKAGE)

    /** 저장된 테스트 일정이 화면에 보이는가. done은 서버 완료 검증(`verified`)과 함께 판정된다. */
    fun isTestEventVisible(): Boolean =
        isForeground() && GuidedAppOps.hasVisibleLabelLike(device, TEST_MARKER)

    /**
     * 화면에 보이는 테스트 일정을 지운다. 지웠음을 확인하지 못하면 구조화 코드로 실패를 남긴다 —
     * "확인하지 못했다"를 "지웠다"로 읽지 않는다.
     *
     * @return 테스트 일정을 화면에서 찾았는가. 못 찾았으면 지울 것도 없다고 보고 넘어간다.
     */
    fun deleteVisibleTestEvent(): Boolean {
        val event = GuidedAppOps.findVisibleLabelLike(device, TEST_MARKER) ?: return false
        event.click()
        device.waitForIdle(IDLE_TIMEOUT_MS)
        val delete = GuidedAppOps.findVisibleLabelLike(device, "삭제")
            ?: throw GuidedScenarioFailure("event_delete_control_missing")
        delete.click()
        device.waitForIdle(IDLE_TIMEOUT_MS)
        // 확인 대화상자가 뜨면 삭제를 확정한다. 없으면 이미 지워진 것일 수 있어 상태로 판정한다.
        GuidedAppOps.findVisibleLabelLike(device, "삭제")?.click()
        device.waitForIdle(IDLE_TIMEOUT_MS)

        if (GuidedAppOps.hasVisibleLabelLike(device, TEST_MARKER)) {
            throw GuidedScenarioFailure("event_cleanup_unverified")
        }
        return true
    }

    companion object {
        const val PACKAGE = "com.skt.nugu.apollo"

        /** 테스트 일정 제목. 개인 일정과 겹치지 않는 고정 marker로 시작한다. */
        const val TEST_MARKER = "DAVI_E2E"
        const val TEST_TITLE = "DAVI_E2E_설문조사"

        private const val IDLE_TIMEOUT_MS = 1_500L
    }
}
