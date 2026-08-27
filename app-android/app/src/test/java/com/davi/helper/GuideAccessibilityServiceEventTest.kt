package com.davi.helper

import android.view.accessibility.AccessibilityEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 이벤트 분류 두 축을 고정한다 — 「샘플을 예약할 만한가」(`isInterestingEvent`)와
 * 「사용자가 방금 조작했다는 근거인가」(`isCausalEvent`)다. 후자가 더 좁고, 그 경계가
 * 어긋나면 스스로 도는 화면이 재질의를 무한히 연다(#380).
 */
class GuideAccessibilityServiceEventTest {

    @Test
    fun `클릭과 선택은 인과 근거다`() {
        // `TYPE_VIEW_SELECTED`가 여기 있는 근거는 실측이다(#482) — 상세는
        // [GuideAccessibilityService.isCausalEvent]의 KDoc. 삼성 NumberPicker가 값을 바꿔도
        // 클릭을 안 쏘아서, 이것이 없으면 시계 알람 화면에서 문 셋이 전부 닫힌다.
        assertTrue(GuideAccessibilityService.isCausalEvent(AccessibilityEvent.TYPE_VIEW_CLICKED))
        assertTrue(GuideAccessibilityService.isCausalEvent(AccessibilityEvent.TYPE_VIEW_SELECTED))
    }

    @Test
    fun `스크롤은 볼 만한 신호이지 인과 근거가 아니다`() {
        // 자동으로 도는 목록이 무조작 50초에 92건을 쐈다(#380). 여기가 뒤집히면 그 폭주가
        // 그대로 돌아온다 — 두 축이 갈리는 자리라 이 단언 하나가 경계를 통째로 지킨다.
        assertTrue(
            GuideAccessibilityService.isInterestingEvent(AccessibilityEvent.TYPE_VIEW_SCROLLED),
        )
        assertFalse(
            GuideAccessibilityService.isCausalEvent(AccessibilityEvent.TYPE_VIEW_SCROLLED),
        )
    }

    @Test
    fun `내용 변화와 포커스도 인과 근거가 아니다`() {
        // 스스로 도는 화면이 가장 많이 쏘는 것이 내용 변화다 — 2026-08-27 SM-S921N 실측에서
        // 스톱워치 실행 화면이 70초에 2157건을 쐈다(같은 구간의 `VIEW_SELECTED`는 0건).
        listOf(
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
        ).forEach { type ->
            assertTrue(GuideAccessibilityService.isInterestingEvent(type))
            assertFalse(GuideAccessibilityService.isCausalEvent(type))
        }
    }

    @Test
    fun `인과 근거는 전부 볼 만한 신호이기도 하다`() {
        // 인과 이벤트가 `isInterestingEvent`에서 빠지면 게이트는 열리는데 그 열림을 쓸
        // 샘플이 예약되지 않는다 — 조용히 반 칸 어긋나는 조합이라 못박아 둔다.
        listOf(
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_SELECTED,
        ).forEach { type ->
            assertTrue(GuideAccessibilityService.isInterestingEvent(type))
        }
    }
}
