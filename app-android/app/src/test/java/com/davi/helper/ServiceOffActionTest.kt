package com.davi.helper

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 안내 중 접근성이 꺼졌을 때의 판정(#190).
 *
 * **축이 하나로 줄었다**(#212). 예전에는 오버레이 권한이 두 번째 축이었다 — 그 권한이 있으면
 * 앱을 앞으로 가져오고(`RETURN_TO_APP`), 없으면 알리기만 했다(`NOTICE_ONLY`). 권한을 걷어내며
 * 자동 복귀를 알림 탭으로 옮겼으므로 이제 "안내 중이었나"만 남는다.
 * 왜 그렇게 바꿨는지는 [ServiceOffAction] KDoc의 실측에 있다.
 */
class ServiceOffActionTest {

    /** 안내 중이 아닐 때 서비스를 끄는 건 정상 조작이다 — 알릴 것이 없다. */
    @Test
    fun `세션이 없으면 아무것도 하지 않는다`() {
        assertEquals(ServiceOffAction.SILENT, serviceOffAction(sessionActive = false))
    }

    /**
     * 안내가 도는 중에 꺼졌으면 사용자는 **왜 멈췄는지 알 길이 없다.** 알림이 그걸 말하고,
     * 그 알림을 누르면 온보딩으로 가서 다시 켤 수 있다 — 예전의 자동 복귀가 하던 일이다.
     */
    @Test
    fun `안내 중이었으면 알린다`() {
        assertEquals(ServiceOffAction.NOTIFY, serviceOffAction(sessionActive = true))
    }
}
