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
 * 전송 차단 경계 회귀.
 *
 * 이 파일이 지키는 성질은 하나다 — **전송 버튼이 눌리는 경로가 없다.** label로 못 잡는 경로를
 * 좌표가 잡는지, 좌표를 모를 때 통과하지 않는지를 각각 고정한다.
 */
class MessagingSendSafetyTest {
    private val sendButton = Box(540, 2040, 1008, 2200)

    private fun screen(vararg elements: ScreenElement) = elements.toList()

    private fun sendElement(label: String = "전송", bounds: Box? = sendButton) =
        ScreenElement(nodeId = "n_send", label = label, bounds = bounds)

    @Test
    fun 전송_label을_직접_고르면_막는다() {
        listOf("전송", " 전송 ", "보내기", "Send", "SEND", "send message").forEach { label ->
            assertEquals(
                "label=$label",
                MessagingSendSafety.SEND_TARGET_BLOCKED,
                MessagingSendSafety.tapFailureCode(
                    targetBox = Box(0, 0, 100, 100),
                    targetLabel = label,
                    elements = screen(sendElement()),
                ),
            )
        }
    }

    @Test
    fun 전송_옵션처럼_설정_항목은_막지_않는다() {
        // 과차단하면 시나리오가 진행되지 않는다. 넓은 그물은 label이 아니라 좌표가 맡는다.
        assertNull(
            MessagingSendSafety.tapFailureCode(
                targetBox = Box(60, 300, 400, 400),
                targetLabel = "전송 옵션",
                elements = screen(sendElement()),
            ),
        )
    }

    /** 핵심 회귀 — label이 안전해도 **누를 좌표**가 전송 버튼 위면 막는다. */
    @Test
    fun label이_다르더라도_좌표가_전송_버튼_위면_막는다() {
        val overlapping = Box(600, 2080, 900, 2160)

        assertEquals(
            MessagingSendSafety.SEND_PROXIMITY_BLOCKED,
            MessagingSendSafety.tapFailureCode(
                targetBox = overlapping,
                targetLabel = "첨부",
                elements = screen(sendElement()),
            ),
        )
    }

    /**
     * 오늘 실측한 빈 label 문제(#134)의 회귀 — 전송 버튼 label이 비어도 좌표로 막는다.
     *
     * 다만 이 경우 전송 컨트롤을 label로 식별하지 못하므로 전송 사각형 자체를 모른다.
     * 그래서 "빈 label 요소를 전송으로 볼 수는 없다"는 한계를 명시적으로 고정한다 —
     * 이 경로의 방어는 [MessagingSendSafety] 밖(수신자 격리·기기 스캔)이 맡는다.
     */
    @Test
    fun 전송_버튼_label이_비면_이_판정부는_알_수_없다() {
        assertNull(
            MessagingSendSafety.tapFailureCode(
                targetBox = Box(600, 2080, 900, 2160),
                targetLabel = null,
                elements = screen(sendElement(label = "")),
            ),
        )
    }

    @Test
    fun 여백_안이면_겹치지_않아도_막는다() {
        // 전송 버튼 바로 위 32px 지점 — 레이아웃이 한 줄 밀리면 넘어가는 거리다.
        val justAbove = Box(700, sendButton.top - 40, 800, sendButton.top - 24)

        assertEquals(
            MessagingSendSafety.SEND_PROXIMITY_BLOCKED,
            MessagingSendSafety.tapFailureCode(
                targetBox = justAbove,
                targetLabel = "첨부",
                elements = screen(sendElement()),
            ),
        )
    }

    @Test
    fun 여백_밖으로_충분히_떨어지면_통과한다() {
        val farAway = Box(60, 300, 400, 400)

        assertNull(
            MessagingSendSafety.tapFailureCode(
                targetBox = farAway,
                targetLabel = "첨부",
                elements = screen(sendElement()),
            ),
        )
    }

    /** 전송 컨트롤이 있는데 대상 좌표를 모르면 안전을 증명할 수 없다 — 통과시키지 않는다. */
    @Test
    fun 전송_컨트롤이_있는데_좌표를_모르면_막는다() {
        assertEquals(
            MessagingSendSafety.SEND_TARGET_UNVERIFIABLE,
            MessagingSendSafety.tapFailureCode(
                targetBox = null,
                targetLabel = "첨부",
                elements = screen(sendElement()),
            ),
        )
    }

    @Test
    fun 전송_컨트롤이_없는_화면에서는_좌표를_몰라도_통과한다() {
        assertNull(
            MessagingSendSafety.tapFailureCode(
                targetBox = null,
                targetLabel = "촬영",
                elements = screen(ScreenElement("n1", "촬영", Box(0, 0, 100, 100))),
            ),
        )
    }

    @Test
    fun 전송_버튼이_여러_개면_모두_피해야_통과한다() {
        val second = Box(60, 100, 300, 260)
        val elements = screen(
            sendElement(),
            ScreenElement("n_send2", "보내기", second),
        )

        assertEquals(
            MessagingSendSafety.SEND_PROXIMITY_BLOCKED,
            MessagingSendSafety.tapFailureCode(
                targetBox = Box(100, 150, 200, 200),
                targetLabel = "첨부",
                elements = elements,
            ),
        )
        assertNull(
            MessagingSendSafety.tapFailureCode(
                targetBox = Box(60, 600, 400, 700),
                targetLabel = "첨부",
                elements = elements,
            ),
        )
    }

    /** 시나리오가 직접 좌표를 누를 때(준비·정리)도 같은 규칙을 쓴다. */
    @Test
    fun 하드코딩_좌표도_같은_규칙으로_검사한다() {
        val boxes = listOf(sendButton)

        // 이슈에 적힌 첨부 버튼 좌표. 지금 레이아웃에서는 전송 사각형 밖이다.
        assertFalse(MessagingSendSafety.blocksTapPoint(774, 2112 - 200, boxes))
        // 같은 좌표인데 레이아웃이 밀려 전송 버튼이 올라온 경우.
        assertTrue(
            MessagingSendSafety.blocksTapPoint(
                x = 774,
                y = 2112,
                sendBoxes = listOf(Box(540, 2040, 1008, 2200)),
            ),
        )
    }

    @Test
    fun 여백은_음수를_받지_않는다() {
        assertThrows(IllegalArgumentException::class.java) {
            MessagingSendSafety.blocksTapPoint(0, 0, listOf(sendButton), safetyMarginPx = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MessagingSendSafety.tapFailureCode(
                targetBox = Box(0, 0, 10, 10),
                targetLabel = "첨부",
                elements = screen(sendElement()),
                safetyMarginPx = -1,
            )
        }
    }

    @Test
    fun bounds는_네_값이고_비어있지_않을_때만_사각형이다() {
        assertNull(Box.fromValues(null))
        assertNull(Box.fromValues(intArrayOf(1, 2, 3)))
        assertNull(Box.fromValues(intArrayOf(10, 10, 10, 20)))
        assertNull(Box.fromValues(intArrayOf(10, 10, 20, 10)))
        assertEquals(Box(1, 2, 3, 4), Box.fromValues(intArrayOf(1, 2, 3, 4)))
    }

    @Test
    fun 전송_사각형은_전송_label_요소에서만_모은다() {
        val elements = screen(
            sendElement(),
            ScreenElement("n2", "첨부", Box(0, 0, 100, 100)),
            ScreenElement("n3", "보내기", bounds = null),
        )

        assertEquals(listOf(sendButton), MessagingSendSafety.sendControlBoxes(elements))
    }
}
