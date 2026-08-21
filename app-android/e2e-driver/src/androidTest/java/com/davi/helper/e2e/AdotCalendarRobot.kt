package com.davi.helper.e2e

import androidx.test.uiautomator.UiDevice

/**
 * 에이닷 캘린더 일정 추가 시나리오의 사전 조건·독립 검증·정리만 담당한다.
 *
 * 이 시나리오는 일정을 실제로 저장하므로 성공·실패와 무관하게 테스트 제목의 일정만 지운다.
 * selector는 2026-08-21 기준 실기기 미검증이다 — 검증 기기가 회선·계정 없는 공기계라
 * 캘린더 화면을 만들 수 없었다. 그래서 판정은 테스트 제목 문구 하나에만 기대고, **확인하지
 * 못한 상태는 성공이 아니라 실패로 흘린다.**
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
        val event = GuidedAppOps.waitForVisibleLabelLike(device, TEST_MARKER, MARKER_TIMEOUT_MS)
            ?: return false
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

    /**
     * 앱을 다시 띄워 테스트 제목의 일정을 **보이는 대로 모두** 지운다.
     *
     * 정리 시점의 화면은 안내가 끝난 자리라 캘린더가 아닐 수 있다. 그 화면만 보고 "없다"고
     * 읽으면 저장된 일정이 계정에 남는다 — 그래서 앱을 새로 띄워 놓고 찾는다.
     *
     * **한계**: 캘린더 화면이 떠 있다는 양성 표식이 아직 실측되지 않았다. 그래서 "일정이
     * 없다"와 "캘린더를 못 봤다"를 구분하지 못한다. 구분이 필요한 판단(저장 시도가 있었는데
     * 못 찾은 경우)은 호출부가 실패로 끝낸다.
     *
     * @return 테스트 일정을 하나라도 찾아 지웠는가.
     */
    fun purgeTestEvents(): Boolean {
        forceStop()
        GuidedAppOps.launch(device, PACKAGE)
        var deletedAny = false
        repeat(MAX_PURGE_ATTEMPTS) {
            if (!deleteVisibleTestEvent()) return deletedAny
            deletedAny = true
        }
        // 상한까지 지웠는데도 계속 보이면 지워지지 않는 것이다. 성공으로 읽지 않는다.
        throw GuidedScenarioFailure("event_cleanup_unverified")
    }

    companion object {
        const val PACKAGE = "com.skt.nugu.apollo"

        /** 테스트 일정 제목. 개인 일정과 겹치지 않는 고정 marker로 시작한다. */
        const val TEST_MARKER = "DAVI_E2E"
        const val TEST_TITLE = "DAVI_E2E_설문조사"

        /** 앱을 새로 띄운 뒤 캘린더가 그려질 때까지의 여유. */
        private const val MARKER_TIMEOUT_MS = 10_000L

        /** 같은 제목의 일정이 여러 번 저장됐을 수 있다. 반복 상한만 둔다. */
        private const val MAX_PURGE_ATTEMPTS = 8

        private const val IDLE_TIMEOUT_MS = 1_500L
    }
}
