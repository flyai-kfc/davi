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
    fun `켤 수 없는 요소의 꺼짐은 상태가 아니다`() {
        // isChecked는 checkable이 아닌 노드에서도 false로 온다. 그걸 "꺼짐"으로 실으면
        // 모델이 화면의 모든 요소를 꺼진 토글로 읽는다.
        assertNull(UiTreeSerializer.checkedState(checkable = false, checked = false))
        assertEquals(false, UiTreeSerializer.checkedState(checkable = true, checked = false))
        assertEquals(true, UiTreeSerializer.checkedState(checkable = true, checked = true))
    }

    @Test
    fun `라벨을 흡수하면 자손의 켬끔도 함께 올린다`() {
        // 행 전체가 clickable인 설정 목록에서 스위치 자식이 clickable이 아니면 그 자식은 흡수돼
        // 별도 요소로 나가지 않는다. 상태를 행으로 올리지 않으면 화면에서 켬/끔이 사라진다(#288 리뷰).
        assertEquals(true, UiTreeSerializer.hoistedCheckedState(own = null, descendant = true))
        assertEquals(false, UiTreeSerializer.hoistedCheckedState(own = null, descendant = false))
        assertNull(UiTreeSerializer.hoistedCheckedState(own = null, descendant = null))
    }

    @Test
    fun `자기 상태가 있으면 자손보다 우선한다`() {
        assertEquals(false, UiTreeSerializer.hoistedCheckedState(own = false, descendant = true))
        assertEquals(true, UiTreeSerializer.hoistedCheckedState(own = true, descendant = false))
    }

    @Test
    fun `이름 없는 클릭 노드는 상태를 조상에 맡긴다`() {
        // 실측(One UI 8.5 SwitchPreference): Switch가 clickable인데 text·desc가 둘 다 없어
        // 자기 요소로 못 나간다. 조상이 안 올리면 켬/끔이 화면에서 사라진다(#288 리뷰).
        assertEquals(false, UiTreeSerializer.emitsOwnElement(label = "", editable = false))
        assertEquals(false, UiTreeSerializer.emitsOwnElement(label = "   ", editable = false))
    }

    @Test
    fun `이름이 있거나 입력칸이면 자기 상태를 자기가 들고 간다`() {
        assertEquals(true, UiTreeSerializer.emitsOwnElement(label = "Wi-Fi", editable = false))
        assertEquals(true, UiTreeSerializer.emitsOwnElement(label = "", editable = true))
    }

    @Test
    fun `hint 여부는 입력칸에만 있다`() {
        assertNull(UiTreeSerializer.hintTextState(editable = false, showingHintText = false))
        assertEquals(true, UiTreeSerializer.hintTextState(editable = true, showingHintText = true))
        assertEquals(false, UiTreeSerializer.hintTextState(editable = true, showingHintText = false))
    }

    @Test
    fun `창 제목은 label과 같은 1차 마스킹을 거친다`() {
        // 대화방 제목은 사람 이름이고 문서 뷰어는 파일명을 싣는다(#269).
        assertEquals(
            "••• 님",
            UiTreeSerializer.windowTitleOf("010-1234-5678 님", PrivacyMaskingLabelMasker),
        )
    }

    @Test
    fun `창 제목도 label과 같은 정규화를 거친다`() {
        assertEquals(
            "설정 배터리",
            UiTreeSerializer.windowTitleOf("  설정\n  배터리 ", PrivacyMaskingLabelMasker),
        )
    }

    @Test
    fun `제목이 없는 창은 null이다`() {
        // 11번가·TMAP·플레이스토어는 제목이 없었다(#269 실측). null이 정상 값이다.
        assertNull(UiTreeSerializer.windowTitleOf(null, PrivacyMaskingLabelMasker))
        assertNull(UiTreeSerializer.windowTitleOf("   ", PrivacyMaskingLabelMasker))
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
