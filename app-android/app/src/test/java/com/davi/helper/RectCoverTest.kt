package com.davi.helper

import com.davi.helper.RectCover.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 가림 판정의 기하 부분을 고정한다(PR #336 리뷰).
 *
 * 이 로직은 실기기 관측이 유일한 근거였고 분기가 적지 않다 — 스크림+패널 두 조각 합집합,
 * 부분만 덮는 형제, 빈 사각형, 화면 밖 bounds, 조상에게 물려받은 덮개. `Region`에 묶여
 * 있으면 어느 것도 JVM에서 못 고정한다.
 */
class RectCoverTest {

    private val screen = Box(0, 0, 1080, 2340)

    @Test
    fun `덮개가 없으면 가려지지 않는다`() {
        assertFalse(RectCover.isCovered(Box(0, 100, 500, 200), emptyList(), screen))
    }

    @Test
    fun `한 조각이 통째로 덮으면 가려진다`() {
        val covered = RectCover.isCovered(
            Box(100, 1000, 500, 1200),
            listOf(Box(0, 0, 1080, 2340)),
            screen,
        )
        assertTrue(covered)
    }

    @Test
    fun `스크림과 패널 두 조각의 합집합으로도 덮인다`() {
        // 실측한 바텀시트의 모양이다. 사각형 하나씩만 보면 어느 쪽도 이 요소를 덮지 못한다.
        val scrimAndPanel = listOf(Box(0, 0, 1080, 234), Box(0, 234, 1080, 2340))
        assertFalse(RectCover.isCovered(Box(0, 200, 1080, 300), listOf(scrimAndPanel[0]), screen))
        assertTrue(RectCover.isCovered(Box(0, 200, 1080, 300), scrimAndPanel, screen))
    }

    @Test
    fun `일부만 덮는 형제는 가림이 아니다`() {
        val covered = RectCover.isCovered(
            Box(0, 1000, 1080, 1400),
            listOf(Box(0, 1000, 1080, 1200)),
            screen,
        )
        assertFalse(covered)
    }

    @Test
    fun `화면 밖으로 걸친 요소는 보이는 부분만 따진다`() {
        // getBoundsInScreen은 화면 밖을 잘라 주지 않는다. 자르지 않으면 화면 위로 삐져나간
        // 조각이 차집합에 남아 "안 덮였다"가 되고, 모달 뒤 목록의 첫 행이 안전망을 빠져나간다.
        val firstRow = Box(0, -80, 1080, 120)
        assertTrue(RectCover.isCovered(firstRow, listOf(screen), screen))

        val lastRow = Box(0, 2300, 1080, 2600)
        assertTrue(RectCover.isCovered(lastRow, listOf(screen), screen))
    }

    @Test
    fun `완전히 화면 밖인 요소는 가려진 것이 아니다`() {
        assertFalse(RectCover.isCovered(Box(0, 3000, 1080, 3200), listOf(screen), screen))
    }

    @Test
    fun `빈 사각형은 가려진 것이 아니다`() {
        // 빈 영역은 무엇을 빼도 비어 있어서, 거르지 않으면 아무것도 안 덮였는데 "덮였다"가 된다.
        assertFalse(RectCover.isCovered(Box(500, 500, 500, 900), listOf(screen), screen))
        assertFalse(RectCover.isCovered(Box(500, 500, 900, 500), emptyList(), screen))
    }

    @Test
    fun `화면 밖에만 있는 덮개는 아무것도 덮지 못한다`() {
        val target = Box(0, 100, 1080, 300)
        assertFalse(RectCover.isCovered(target, listOf(Box(0, -500, 1080, -100)), screen))
    }

    @Test
    fun `여러 조각이 모여야 덮이는 경우도 판정한다`() {
        val target = Box(100, 100, 500, 500)
        val halves = listOf(Box(100, 100, 300, 500), Box(300, 100, 500, 500))
        assertFalse(RectCover.isCovered(target, listOf(halves[0]), screen))
        assertTrue(RectCover.isCovered(target, halves, screen))
    }

    @Test
    fun `가운데를 파내면 네 조각이 남는다`() {
        val pieces = RectCover.subtract(Box(0, 0, 100, 100), Box(40, 40, 60, 60))
        assertEquals(4, pieces.size)
        assertTrue(pieces.none { it.isEmpty })
        // 파낸 구멍은 어느 조각에도 들어 있지 않다.
        assertTrue(pieces.none { it.intersect(Box(45, 45, 55, 55)).isEmpty.not() })
    }

    @Test
    fun `겹치지 않는 것을 빼면 그대로 남는다`() {
        val box = Box(0, 0, 100, 100)
        assertEquals(listOf(box), RectCover.subtract(box, Box(200, 200, 300, 300)))
    }
}
