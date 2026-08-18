package com.davi.helper

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 안내 중 접근성이 꺼졌을 때의 판정(#190).
 *
 * 셋의 근거가 다르다는 것이 이 클래스가 따로 있는 이유다 — 특히
 * `오버레이 권한이 없으면 복귀를 시도하지 않는다`는 플랫폼 제약을 코드로 고정한다.
 */
class ServiceOffActionTest {

    /** 안내 중이 아닐 때 서비스를 끄는 건 정상 조작이다 — 알릴 것이 없다. */
    @Test
    fun `세션이 없으면 아무것도 하지 않는다`() {
        assertEquals(ServiceOffAction.SILENT, serviceOffAction(sessionActive = false, canDrawOverlays = true))
        assertEquals(ServiceOffAction.SILENT, serviceOffAction(sessionActive = false, canDrawOverlays = false))
    }

    /** 다시 켜려면 어차피 앱으로 돌아와야 한다 — 사용자가 설정 앱에서 길을 잃지 않게 한다. */
    @Test
    fun `안내 중이었고 오버레이 권한이 있으면 앱을 앞으로 가져온다`() {
        assertEquals(
            ServiceOffAction.RETURN_TO_APP,
            serviceOffAction(sessionActive = true, canDrawOverlays = true),
        )
    }

    /**
     * **오버레이 권한이 복귀의 성립 조건이다.** Android 10+의 백그라운드 activity 실행 금지에서
     * 우리가 쓰는 면제 둘(`BAL_ALLOW_SAW_PERMISSION` · `BAL_ALLOW_NON_APP_VISIBLE_WINDOW`)이
     * 모두 그 권한에서 나오므로, 권한이 없으면 `startActivity`가 조용히 막힌다.
     * 그 경우 토스트라도 남겨야 사용자가 이유를 안다(다만 알림 권한 전까지는 그 토스트도
     * 억제된다 — [ServiceOffAction.NOTICE_ONLY]).
     */
    @Test
    fun `오버레이 권한이 없으면 복귀를 시도하지 않고 알리기만 한다`() {
        assertEquals(
            ServiceOffAction.NOTICE_ONLY,
            serviceOffAction(sessionActive = true, canDrawOverlays = false),
        )
    }
}
