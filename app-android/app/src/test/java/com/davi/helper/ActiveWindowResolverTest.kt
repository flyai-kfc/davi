package com.davi.helper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 다이얼로그 판정. 창 선택 자체는 접근성 타입에 묶여 있어 JVM에서 못 돌리지만,
 * "화면 전체인가"라는 판단만은 숫자라서 고정할 수 있다.
 *
 * 값은 2026-08-12 SM-S921N 실측이다(디스플레이 1080×2340).
 */
class ActiveWindowResolverTest {

    @Test
    fun `화면을 다 덮는 앱 창은 다이얼로그가 아니다`() {
        assertFalse(ActiveWindowResolver.isPartial(windowArea = area(2340), displayArea = DISPLAY))
    }

    @Test
    fun `하단 밴드로 뜬 확인 창은 다이얼로그다`() {
        // "강제 중지" 확인 창: Rect(0, 1470 - 1080, 2178) = 높이 708.
        assertTrue(ActiveWindowResolver.isPartial(windowArea = area(708), displayArea = DISPLAY))
    }

    @Test
    fun `상태표시줄만큼 작은 앱 창은 다이얼로그로 오인하지 않는다`() {
        // 창이 상태표시줄(103) 아래에서 시작해도 앱 화면은 앱 화면이다.
        assertFalse(ActiveWindowResolver.isPartial(windowArea = area(2340 - 103), displayArea = DISPLAY))
    }

    private fun area(height: Int) = WIDTH * height.toLong()

    private companion object {
        const val WIDTH = 1080L
        const val DISPLAY = WIDTH * 2340L
    }
}
