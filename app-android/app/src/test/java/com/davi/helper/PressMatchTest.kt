package com.davi.helper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 눌림 판정의 두 축(#238).
 *
 * 좌표 축이 주(主)이고 텍스트 축은 `AccessibilityEvent.source`가 회수됐을 때의 폴백이다.
 * 여기서 지키는 것은 **오탐을 만들지 않는 것** — 틀리면 파동이 빠질 뿐이어야 하고,
 * 엉뚱한 대상을 눌렀는데 "맞다"고 하면 안 된다.
 */
class PressMatchTest {

    // --- 좌표 축 -----------------------------------------------------------------

    @Test
    fun `중심이 대상 안이면 맞다`() {
        assertTrue(PressMatch.byBounds(BOUNDS, 60, 120))
    }

    /** 컨테이너를 지목했는데 안쪽 뷰가 눌리는 경우를 받으려고 중심 포함으로 본다. */
    @Test
    fun `모서리도 대상 안이다`() {
        assertTrue(PressMatch.byBounds(BOUNDS, 10, 20))
        assertTrue(PressMatch.byBounds(BOUNDS, 110, 220))
    }

    @Test
    fun `중심이 대상 밖이면 아니다`() {
        assertFalse(PressMatch.byBounds(BOUNDS, 9, 120))
        assertFalse(PressMatch.byBounds(BOUNDS, 60, 221))
    }

    @Test
    fun `망가진 좌표는 맞다고 하지 않는다`() {
        assertFalse(PressMatch.byBounds(listOf(10, 20, 110), 60, 120))
        assertFalse(PressMatch.byBounds(emptyList(), 60, 120))
    }

    // --- 텍스트 축 ---------------------------------------------------------------

    @Test
    fun `label과 같은 텍스트면 맞다`() {
        assertTrue(PressMatch.byLabel("카메라", listOf("카메라")))
    }

    /**
     * label이 자손 텍스트를 합친 긴 값일 때 포함까지 받으면 **옆 대상의 짧은 텍스트가 통과한다** —
     * "소리 및 진동"을 안내하는 중에 옆 행 "진동"을 누르면 맞다고 한다(PR #264 리뷰 6).
     * 이 축은 폴백이라 못 맞추는 쪽(미탐)이 잘못 맞추는 쪽(오탐)보다 낫다.
     */
    @Test
    fun `label의 일부만 같은 텍스트는 맞지 않는다`() {
        assertFalse(PressMatch.byLabel("소리 및 진동", listOf("진동")))
        assertFalse(PressMatch.byLabel("카메라 앱 열기", listOf("카메라")))
    }

    @Test
    fun `다른 텍스트는 맞지 않는다`() {
        assertFalse(PressMatch.byLabel("카메라", listOf("전화")))
    }

    /**
     * 한 글자짜리 label은 다른 대상에도 흔해서 오탐이 된다 — ×·＋ 같은 버튼 둘이 서로 맞는다
     * (서버 완료 근거 판정의 같은 함정, #71).
     *
     * 완전 일치로 좁힌 뒤에는 **label 쪽이 짧을 때만** 이 하한이 일한다. 대상 label이 길고
     * 이벤트 텍스트가 짧은 경우는 애초에 일치하지 않아 하한과 무관하다(PR #264 재리뷰 4).
     */
    @Test
    fun `한 글자짜리는 서로 같아도 맞추지 않는다`() {
        assertFalse(PressMatch.byLabel("카", listOf("카")))
    }

    @Test
    fun `label이 없으면 맞출 수 없다`() {
        assertFalse(PressMatch.byLabel(null, listOf("카메라")))
        assertFalse(PressMatch.byLabel("  ", listOf("카메라")))
    }

    @Test
    fun `텍스트가 없으면 맞출 수 없다`() {
        assertFalse(PressMatch.byLabel("카메라", emptyList()))
        assertFalse(PressMatch.byLabel("카메라", listOf("  ")))
    }

    /** 이벤트는 텍스트를 여러 개 실어 올 수 있다. 하나만 맞아도 된다. */
    @Test
    fun `여러 텍스트 중 하나만 맞아도 된다`() {
        assertTrue(PressMatch.byLabel("카메라", listOf("사진", "카메라")))
    }

    private companion object {
        val BOUNDS = listOf(10, 20, 110, 220)
    }
}
