package com.davi.helper.e2e

import androidx.test.uiautomator.UiDevice

/**
 * 에이닷 캘린더 일정 추가 시나리오의 사전 조건·독립 검증·정리만 담당한다.
 *
 * 이 시나리오는 일정을 실제로 저장하므로 성공·실패와 무관하게 테스트 제목의 일정만 지운다.
 *
 * 진입·삭제 경로는 2026-08-21 실기기(에이닷 4.4.3, 로그인 상태)에서 실측했다 — 홈의 "일정 메뉴"
 * 진입, 매 진입마다 뜨는 외부 캘린더 연동 팝업, 월간이 아니라 일간 화면에서만 보이는 일정 제목,
 * "일정 삭제"와 "일정을 삭제할까요?" 확인 대화상자까지. **확인하지 못한 상태는 성공이 아니라
 * 실패로 흘린다.**
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
        if (GuidedAppOps.waitForVisibleLabelLike(device, TEST_MARKER, MARKER_TIMEOUT_MS) == null) {
            return false
        }
        // 아래 누르기는 모두 stale에 안전한 헬퍼를 쓴다 — 달력·목록은 계속 다시 그려진다.
        if (!GuidedAppOps.clickVisibleLabelLike(device, TEST_MARKER, MARKER_TIMEOUT_MS)) return false
        device.waitForIdle(IDLE_TIMEOUT_MS)
        if (!GuidedAppOps.clickVisibleLabelLike(device, DELETE_FRAGMENT, SURFACE_TIMEOUT_MS)) {
            throw GuidedScenarioFailure("event_delete_control_missing")
        }
        device.waitForIdle(IDLE_TIMEOUT_MS)
        // 확인 대화상자가 뜨면 삭제를 확정한다. 대화상자 뒤에 상세 화면의 "일정 삭제"가 그대로
        // 남아 있어 부분 문자열로 누르면 대화상자가 아니라 그 버튼을 다시 누른다 — 질문 문구를
        // 기다렸다가 **정확히** "삭제"인 노드를 누른다(2026-08-21 실측).
        if (GuidedAppOps.waitForVisibleLabelLike(device, CONFIRM_QUESTION_FRAGMENT, CONFIRM_TIMEOUT_MS) != null &&
            !GuidedAppOps.clickExactLabel(device, CONFIRM_DELETE_LABEL, CONFIRM_TIMEOUT_MS)
        ) {
            throw GuidedScenarioFailure("event_delete_confirm_missing")
        }
        device.waitForIdle(IDLE_TIMEOUT_MS)

        if (GuidedAppOps.hasVisibleLabelLike(device, TEST_MARKER)) {
            throw GuidedScenarioFailure("event_cleanup_unverified")
        }
        return true
    }

    /**
     * 목표 날짜의 일간 화면까지 들어간 뒤, 테스트 제목의 일정을 **보이는 대로 모두** 지운다.
     *
     * 정리 시점의 화면은 안내가 끝난 자리라 캘린더가 아니다. 앱만 새로 띄워도 홈으로 돌아올 뿐이라
     * 일정을 못 찾고 "지울 게 없다"로 끝난다 — 2026-08-21 실측에서 실제로 그렇게 새어나갔다.
     * 그래서 홈 → 일정 탭 → 목표 날짜 일간 화면까지 직접 들어간다.
     *
     * @return 테스트 일정을 하나라도 찾아 지웠는가. 캘린더에 닿지 못하면 구조화 코드로 실패한다.
     */
    fun purgeTestEvents(): Boolean {
        var deletedAny = false
        repeat(MAX_PURGE_ATTEMPTS) {
            openTargetDay()
            if (!deleteVisibleTestEvent()) return deletedAny
            deletedAny = true
        }
        // 상한까지 지웠는데도 계속 보이면 지워지지 않는 것이다. 성공으로 읽지 않는다.
        throw GuidedScenarioFailure("event_cleanup_unverified")
    }

    /**
     * 앱을 새로 띄워 목표 날짜의 **일간** 화면까지 들어간다.
     *
     * 월간 화면은 일정 제목을 노출하지 않는다 — 일 셀의 label은 "8월 20일 목요일, 일정 없음"처럼
     * 요약뿐이고, 일정이 있어도 제목이 나오지 않는다(2026-08-21 실측). 제목으로 찾으려면 그 날의
     * 일간 화면까지 들어가야 한다.
     */
    private fun openTargetDay() {
        forceStop()
        GuidedAppOps.launch(device, PACKAGE)
        if (!GuidedAppOps.clickVisibleLabelLike(device, CALENDAR_TAB_FRAGMENT, SURFACE_TIMEOUT_MS)) {
            throw GuidedScenarioFailure("calendar_entry_missing")
        }
        device.waitForIdle(IDLE_TIMEOUT_MS)
        // 캘린더에 들어갈 때마다 외부 캘린더 연동 팝업이 덮는다("첫 진입에만"이 아니다 —
        // 나중에로 닫아도 다시 뜬다). 뜨는 데 시간이 걸려 한 번만 확인하면 놓치고, 그러면 팝업이
        // 캘린더를 가린 채 날짜를 못 찾는다(2026-08-21 실측). 계정을 연결하지 않고 "나중에"를 누른다.
        GuidedAppOps.clickVisibleLabelLike(device, "나중에", POPUP_TIMEOUT_MS)
        device.waitForIdle(IDLE_TIMEOUT_MS)
        if (!GuidedAppOps.clickVisibleLabelLike(device, TARGET_DAY_FRAGMENT, SURFACE_TIMEOUT_MS)) {
            throw GuidedScenarioFailure("calendar_screen_unverified")
        }
        device.waitForIdle(IDLE_TIMEOUT_MS)
    }

    companion object {
        const val PACKAGE = "com.skt.nugu.apollo"

        /** 테스트 일정 제목. 개인 일정과 겹치지 않는 고정 marker로 시작한다. */
        const val TEST_MARKER = "DAVI_E2E"
        const val TEST_TITLE = "DAVI_E2E_설문조사"

        /** 홈 하단의 캘린더 진입 label 조각. 2026-08-21 실측 — contentDescription "일정 메뉴 버튼". */
        private const val CALENDAR_TAB_FRAGMENT = "일정 메뉴"

        /**
         * 목표 날짜. 시나리오 목표문이 8월 20일로 고정돼 있어 정리도 그 날에서 찾는다.
         * 목표문의 날짜를 바꾸면 여기도 함께 바꾼다.
         */
        private const val TARGET_DAY_FRAGMENT = "8월 20일"

        /** 앱을 새로 띄운 뒤 화면이 그려질 때까지의 여유. */
        private const val SURFACE_TIMEOUT_MS = 10_000L

        /** 일정 상세의 삭제 컨트롤. 2026-08-21 실측 — 문구가 "일정 삭제"라 부분 문자열로 잡는다. */
        private const val DELETE_FRAGMENT = "삭제"

        /** 삭제 확인 대화상자의 질문 문구. 2026-08-21 실측 — "일정을 삭제할까요?". */
        private const val CONFIRM_QUESTION_FRAGMENT = "삭제할까요"

        /** 확인 대화상자의 확정 버튼 label. 정확히 이 문구다. */
        private const val CONFIRM_DELETE_LABEL = "삭제"

        /** 삭제 확인 대화상자를 기다리는 시간. 없을 수도 있어 짧게 둔다. */
        private const val CONFIRM_TIMEOUT_MS = 5_000L

        /** 캘린더 진입 팝업이 뜨기까지의 여유. 안 뜨면 그만큼만 기다리고 지나간다. */
        private const val POPUP_TIMEOUT_MS = 5_000L

        /** 일간 화면에서 테스트 일정이 그려질 때까지의 여유. */
        private const val MARKER_TIMEOUT_MS = 5_000L

        /** 같은 제목의 일정이 여러 번 저장됐을 수 있다. 반복 상한만 둔다. */
        private const val MAX_PURGE_ATTEMPTS = 8

        private const val IDLE_TIMEOUT_MS = 1_500L
    }
}
