package com.davi.helper

import android.view.accessibility.AccessibilityWindowInfo
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
    fun `스와이프 방향은 화면이 이동하는 방향이다`() {
        // 계약(C17)은 손가락이 아니라 "보러 가는 쪽"이다. 목록 아래를 더 보려면 next 는 down,
        // 다음 페이지로 넘어가려면 right다. 손가락은 그 반대이며 그리는 경계에서만 뒤집는다.
        assertEquals(SwipeDirection.DOWN, UiTreeSerializer.Axis.VERTICAL.next)
        assertEquals(SwipeDirection.UP, UiTreeSerializer.Axis.VERTICAL.prev)
        assertEquals(SwipeDirection.RIGHT, UiTreeSerializer.Axis.HORIZONTAL.next)
        assertEquals(SwipeDirection.LEFT, UiTreeSerializer.Axis.HORIZONTAL.prev)
    }

    @Test
    fun `입력 메타를 손가락으로 바꾸면 실제로 해야 하는 동작이 된다`() {
        // 입력 메타(여기)와 손 모션(GestureMotion)이 서로 다른 기준을 쓰면 안내가 조용히
        // 반대로 나간다. 두 자리를 한 테스트로 묶어 고정한다.
        assertEquals(
            SwipeDirection.UP,
            GestureMotion.fingerDirectionFor(UiTreeSerializer.Axis.VERTICAL.next),
        )
        assertEquals(
            SwipeDirection.LEFT,
            GestureMotion.fingerDirectionFor(UiTreeSerializer.Axis.HORIZONTAL.next),
        )
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

    @Test
    fun `IME는 창 type 존재 여부만 관측한다`() {
        assertEquals(
            true,
            UiTreeSerializer.imeVisibility(
                listOf(
                    AccessibilityWindowInfo.TYPE_APPLICATION,
                    AccessibilityWindowInfo.TYPE_INPUT_METHOD,
                ),
            ),
        )
        assertEquals(
            false,
            UiTreeSerializer.imeVisibility(listOf(AccessibilityWindowInfo.TYPE_APPLICATION)),
        )
        assertNull(UiTreeSerializer.imeVisibility(emptyList()))
        assertNull(UiTreeSerializer.imeVisibility(null))
    }

    @Test
    fun `편집 요소 라벨은 입력 원문 대신 hint만 쓴다`() {
        assertEquals("검색어 입력", UiTreeSerializer.editableLabel(" 검색어 입력 "))
        assertNull(UiTreeSerializer.editableLabel(null))
        assertEquals(false, UiTreeSerializer.shouldInspectDescendants(editable = true))
        assertEquals(true, UiTreeSerializer.shouldInspectDescendants(editable = false))
    }

    @Test
    fun `직접 조작 가능한 NumberPicker의 짧은 숫자 값은 보존한다`() {
        assertEquals("6, 시간", adjustableNumericLabel("6, 시간"))
        assertEquals("00, 분", adjustableNumericLabel(" 00,   분 "))
        assertEquals("2026년", adjustableNumericLabel("2026년"))
    }

    @Test
    fun `NumberPicker여도 PII 형태와 긴 숫자는 보존하지 않는다`() {
        assertNull(adjustableNumericLabel("010-1234-5678"))
        assertNull(adjustableNumericLabel("1234-5678-9012-3456"))
        assertNull(adjustableNumericLabel("123-456-789012"))
        assertNull(adjustableNumericLabel("12345"))
    }

    @Test
    fun `NumberPicker 숫자 공개에는 모든 구조적 안전 조건이 필요하다`() {
        assertNull(adjustableNumericLabel("6", visibleToUser = false))
        assertNull(adjustableNumericLabel("6", enabled = false))
        assertNull(adjustableNumericLabel("6", password = true))
        assertNull(adjustableNumericLabel("6", editable = false))
        assertNull(adjustableNumericLabel("6", hasDirectAction = false))
        assertNull(adjustableNumericLabel("6", hasNearbyAdjustableAncestor = false))
    }

    @Test
    fun `가까운 조상은 활성 NumberPicker이며 조정 가능해야 한다`() {
        assertEquals(
            true,
            UiTreeSerializer.isAdjustableControlAncestor(
                visibleToUser = true,
                enabled = true,
                role = "NumberPicker",
                scrollable = true,
                hasAdjustmentAction = false,
            ),
        )
        assertEquals(
            true,
            UiTreeSerializer.isAdjustableControlAncestor(
                visibleToUser = true,
                enabled = true,
                role = "SeslNumberPicker",
                scrollable = false,
                hasAdjustmentAction = true,
            ),
        )
        assertEquals(
            false,
            UiTreeSerializer.isAdjustableControlAncestor(
                visibleToUser = true,
                enabled = true,
                role = "EditText",
                scrollable = true,
                hasAdjustmentAction = true,
            ),
        )
        assertEquals(
            false,
            UiTreeSerializer.isAdjustableControlAncestor(
                visibleToUser = false,
                enabled = true,
                role = "NumberPicker",
                scrollable = true,
                hasAdjustmentAction = true,
            ),
        )
    }

    private fun adjustableNumericLabel(
        rawLabel: CharSequence?,
        editable: Boolean = true,
        visibleToUser: Boolean = true,
        enabled: Boolean = true,
        password: Boolean = false,
        hasDirectAction: Boolean = true,
        hasNearbyAdjustableAncestor: Boolean = true,
    ): String? = UiTreeSerializer.adjustableNumericLabel(
        rawLabel = rawLabel,
        editable = editable,
        visibleToUser = visibleToUser,
        enabled = enabled,
        password = password,
        hasDirectAction = hasDirectAction,
        hasNearbyAdjustableAncestor = hasNearbyAdjustableAncestor,
    )
}
