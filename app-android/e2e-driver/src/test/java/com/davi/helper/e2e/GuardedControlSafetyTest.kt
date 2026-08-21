package com.davi.helper.e2e

import com.davi.helper.e2e.MessagingSendSafety.Box
import com.davi.helper.e2e.MessagingSendSafety.ScreenElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 일반화된 최종 컨트롤 차단 경계 회귀.
 *
 * 이 파일이 지키는 성질은 [MessagingSendSafetyTest]와 같다 — **호출·주문 버튼이 눌리는 경로가
 * 없다.** 다른 점 하나(부분 문자열 label 판정)와, 못 잡은 경로를 좌표·fail-closed가 잡는지를
 * 고정한다.
 */
class GuardedControlSafetyTest {
    private val callButton = Box(90, 2000, 990, 2160)

    private fun code(
        actionType: GuidedActionType = GuidedActionType.TAP,
        screenPackage: String = "com.example.taxi",
        targetBox: Box? = Box(100, 300, 400, 400),
        targetLabel: String? = "다음",
        blockedBoxes: List<Box> = listOf(callButton),
        controlRequired: Boolean = true,
    ): String? = GuardedControlSafety.decisionFailureCode(
        codePrefix = "taxi_call",
        actionType = actionType,
        screenPackage = screenPackage,
        guardedPackages = setOf("com.example.taxi"),
        targetBox = targetBox,
        targetLabel = targetLabel,
        blockedBoxes = blockedBoxes,
        blockedLabelFragments = setOf("호출하기", "예약하기"),
        controlRequired = controlRequired,
    )

    @Test
    fun 동적_금액이_섞인_label도_부분_문자열로_막는다() {
        listOf("호출하기", "일반 호출하기", "4,800원 호출하기", "지금 예약하기").forEach { label ->
            assertEquals("label=$label", "taxi_call_target_blocked", code(targetLabel = label))
        }
    }

    @Test
    fun label이_안전해도_누를_좌표가_컨트롤_위면_막는다() {
        assertEquals(
            "taxi_call_proximity_blocked",
            code(targetLabel = "빈 label", targetBox = Box(400, 1990, 700, 2100)),
        )
    }

    @Test
    fun 컨트롤이_있어야_하는_단계에서_하나도_못_찾으면_통과시키지_않는다() {
        assertEquals(
            "taxi_call_control_unidentified",
            code(blockedBoxes = emptyList(), controlRequired = true),
        )
        assertNull(code(blockedBoxes = emptyList(), controlRequired = false))
    }

    @Test
    fun 컨트롤이_보이는데_대상_좌표를_모르면_안전을_증명하지_못한다() {
        assertEquals("taxi_call_target_unverifiable", code(targetBox = null))
    }

    @Test
    fun 컨트롤에서_먼_대상과_다른_package와_스크롤은_막지_않는다() {
        assertNull(code())
        assertNull(code(screenPackage = "com.example.other", targetLabel = "호출하기"))
        assertNull(code(actionType = GuidedActionType.SCROLL, targetLabel = "호출하기"))
    }

    @Test
    fun TYPE도_탭을_주입하므로_같은_규칙으로_막는다() {
        assertEquals(
            "taxi_call_target_blocked",
            code(actionType = GuidedActionType.TYPE, targetLabel = "호출하기"),
        )
    }

    @Test
    fun 빈_label은_어떤_fragment에도_걸리지_않는다() {
        assertFalse(GuardedControlSafety.containsBlockedLabel(null, setOf("호출하기")))
        assertFalse(GuardedControlSafety.containsBlockedLabel("  ", setOf("호출하기")))
        assertTrue(GuardedControlSafety.containsBlockedLabel(" 배달 주문하기 ", setOf("주문하기")))
    }

    @Test
    fun 화면_snapshot에서_차단_컨트롤_사각형만_뽑는다() {
        val boxes = GuardedControlSafety.blockedControlBoxes(
            listOf(
                ScreenElement(nodeId = "n1", label = "메뉴 담기", bounds = Box(0, 0, 100, 100)),
                ScreenElement(nodeId = "n2", label = "배달 주문하기", bounds = callButton),
                ScreenElement(nodeId = "n3", label = "주문하기", bounds = null),
            ),
            setOf("주문하기"),
        )
        assertEquals(listOf(callButton), boxes)
    }

    @Test
    fun 안전하지_않은_코드_접두어는_거부한다() {
        assertThrows(IllegalArgumentException::class.java) {
            GuardedControlSafety.decisionFailureCode(
                codePrefix = "Taxi Call!",
                actionType = GuidedActionType.TAP,
                screenPackage = "com.example.taxi",
                guardedPackages = setOf("com.example.taxi"),
                targetBox = null,
                targetLabel = null,
                blockedBoxes = emptyList(),
                blockedLabelFragments = setOf("호출하기"),
                controlRequired = false,
            )
        }
    }
}
