package com.davi.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 목킹 프레임워크가 없어 `AccessibilityNodeInfo`는 다루지 못한다.
 * 대신 위험한 로직(페이지 dot 파싱, 축 추정)만 순수 함수로 분리해 여기서 고정한다.
 */
class UiTreeSerializerTest {

    @Test
    fun `실기기에서 관찰된 문구를 파싱한다`() {
        // 실측(SM-S921N, 앱서랍): content-desc가 이 형태로 온다.
        val dot = UiTreeSerializer.parsePageDot("2 페이지 중 1 페이지입니다.")
        assertEquals(UiTreeSerializer.PageDot(total = 2, current = 1), dot)
    }

    @Test
    fun `자릿수가 늘어도 파싱한다`() {
        assertEquals(
            UiTreeSerializer.PageDot(total = 12, current = 10),
            UiTreeSerializer.parsePageDot("12 페이지 중 10 페이지입니다."),
        )
    }

    @Test
    fun `페이지 문구가 아니면 null`() {
        assertNull(UiTreeSerializer.parsePageDot("카카오톡"))
        assertNull(UiTreeSerializer.parsePageDot("페이지"))
        assertNull(UiTreeSerializer.parsePageDot(""))
    }

    @Test
    fun `방향 액션이 있으면 그것으로 축을 정한다`() {
        // 모양과 반대여도 액션이 이긴다. 가로로 넓은 세로 스크롤러가 실제로 있다.
        assertEquals(
            UiTreeSerializer.Axis.VERTICAL,
            UiTreeSerializer.resolveAxis(horizontal = false, vertical = true, width = 900, height = 100),
        )
        assertEquals(
            UiTreeSerializer.Axis.HORIZONTAL,
            UiTreeSerializer.resolveAxis(horizontal = true, vertical = false, width = 100, height = 900),
        )
    }

    @Test
    fun `방향 액션이 없으면 모양으로 추정한다`() {
        assertEquals(
            UiTreeSerializer.Axis.VERTICAL,
            UiTreeSerializer.resolveAxis(horizontal = false, vertical = false, width = 100, height = 900),
        )
        assertEquals(
            UiTreeSerializer.Axis.HORIZONTAL,
            UiTreeSerializer.resolveAxis(horizontal = false, vertical = false, width = 900, height = 100),
        )
    }

    @Test
    fun `스와이프 방향은 손가락이 움직이는 방향이다`() {
        // 목록 아래를 더 보려면 손가락은 위로 올린다.
        assertEquals(SwipeDirection.UP, UiTreeSerializer.Axis.VERTICAL.next)
        assertEquals(SwipeDirection.DOWN, UiTreeSerializer.Axis.VERTICAL.prev)
        assertEquals(SwipeDirection.LEFT, UiTreeSerializer.Axis.HORIZONTAL.next)
        assertEquals(SwipeDirection.RIGHT, UiTreeSerializer.Axis.HORIZONTAL.prev)
    }

    @Test
    fun `스크롤 가능 요소 관측은 트리 전체에서 누적한다`() {
        assertEquals(
            false,
            UiTreeSerializer.observeScrollable(
                seen = false,
                isScrollable = false,
                hasScrollActions = false,
            ),
        )
        assertEquals(
            true,
            UiTreeSerializer.observeScrollable(
                seen = false,
                isScrollable = true,
                hasScrollActions = false,
            ),
        )
        assertEquals(
            true,
            UiTreeSerializer.observeScrollable(
                seen = false,
                isScrollable = false,
                hasScrollActions = true,
            ),
        )
        assertEquals(
            true,
            UiTreeSerializer.observeScrollable(
                seen = true,
                isScrollable = false,
                hasScrollActions = false,
            ),
        )
    }
}
