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

    /** 화면 요소에서 전송 사각형을 뽑아 판정부에 넣는 기본 경로. */
    private fun boxesOf(vararg elements: ScreenElement) =
        MessagingSendSafety.sendControlBoxes(elements.toList())

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
                    sendBoxes = boxesOf(sendElement()),
                sendControlRequired = true,
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
                sendBoxes = boxesOf(sendElement()),
                sendControlRequired = true,
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
                sendBoxes = boxesOf(sendElement()),
                sendControlRequired = true,
            ),
        )
    }

    /**
     * 빈 label 문제(#134 실측)의 회귀 — label로는 전송 버튼을 식별하지 못한다.
     *
     * **식별하지 못했다고 통과시키지 않는다.** 전송 컨트롤이 있어야 하는 단계라면 실패로 닫는다.
     * "확인이 가장 필요한 순간"을 안전하다고 판정하면 경계가 있으나 마나다(#211 리뷰).
     */
    @Test
    fun 전송_컨트롤을_식별하지_못하면_필요한_단계에서_막는다() {
        assertEquals(
            MessagingSendSafety.SEND_CONTROL_UNIDENTIFIED,
            MessagingSendSafety.tapFailureCode(
                targetBox = Box(600, 2080, 900, 2160),
                targetLabel = null,
                sendBoxes = boxesOf(sendElement(label = "")),
                sendControlRequired = true,
            ),
        )
    }

    /** 대화 목록처럼 전송 컨트롤이 아직 없는 화면까지 막으면 시나리오가 진행되지 않는다. */
    @Test
    fun 전송_컨트롤이_필요없는_단계에서는_식별_못해도_통과한다() {
        assertNull(
            MessagingSendSafety.tapFailureCode(
                targetBox = Box(600, 2080, 900, 2160),
                targetLabel = null,
                sendBoxes = boxesOf(sendElement(label = "")),
                sendControlRequired = false,
            ),
        )
    }

    /** 식별 못 한 단계여도 대상 label 자체가 전송이면 그건 먼저 막는다. */
    @Test
    fun 식별_못한_단계여도_전송_label은_막는다() {
        assertEquals(
            MessagingSendSafety.SEND_TARGET_BLOCKED,
            MessagingSendSafety.tapFailureCode(
                targetBox = Box(600, 2080, 900, 2160),
                targetLabel = "전송",
                sendBoxes = emptyList(),
                sendControlRequired = false,
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
                sendBoxes = boxesOf(sendElement()),
                sendControlRequired = true,
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
                sendBoxes = boxesOf(sendElement()),
                sendControlRequired = true,
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
                sendBoxes = boxesOf(sendElement()),
                sendControlRequired = true,
            ),
        )
    }

    @Test
    fun 전송_컨트롤이_없는_화면에서는_좌표를_몰라도_통과한다() {
        assertNull(
            MessagingSendSafety.tapFailureCode(
                targetBox = null,
                targetLabel = "촬영",
                sendBoxes = boxesOf(ScreenElement("n1", "촬영", Box(0, 0, 100, 100))),
                sendControlRequired = false,
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
                sendBoxes = MessagingSendSafety.sendControlBoxes(elements),
                sendControlRequired = true,
            ),
        )
        assertNull(
            MessagingSendSafety.tapFailureCode(
                targetBox = Box(60, 600, 400, 700),
                targetLabel = "첨부",
                sendBoxes = MessagingSendSafety.sendControlBoxes(elements),
                sendControlRequired = true,
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

    // ── 실제 탭을 주입하는 action 판정 (#211 리뷰 1) ─────────────────────────────

    private val messaging = setOf("com.samsung.android.messaging")

    private fun decision(
        actionType: GuidedActionType,
        targetBox: Box? = Box(600, 2080, 900, 2160),
        targetLabel: String? = "첨부",
        screenPackage: String = "com.samsung.android.messaging",
    ) = MessagingSendSafety.decisionFailureCode(
        actionType = actionType,
        screenPackage = screenPackage,
        messagingPackages = messaging,
        targetBox = targetBox,
        targetLabel = targetLabel,
        sendBoxes = boxesOf(sendElement()),
        sendControlRequired = true,
    )

    /**
     * 핵심 회귀 — `TYPE`도 좌표 경계를 통과해야 한다.
     *
     * `GuidedActionExecutor.type()`이 텍스트를 넣기 전에 `tap()`을 불러 대상 bounds 중심을 실제로
     * 누른다. `TAP`만 검사하면 잘못된 bounds의 `TYPE`이 전송 버튼을 먼저 눌러버린다.
     */
    @Test
    fun TYPE도_전송_영역이면_막는다() {
        assertEquals(
            MessagingSendSafety.SEND_PROXIMITY_BLOCKED,
            decision(GuidedActionType.TYPE),
        )
    }

    @Test
    fun TAP과_TYPE이_같은_판정을_받는다() {
        assertEquals(decision(GuidedActionType.TAP), decision(GuidedActionType.TYPE))

        val safe = Box(60, 300, 400, 400)
        assertNull(decision(GuidedActionType.TAP, targetBox = safe))
        assertNull(decision(GuidedActionType.TYPE, targetBox = safe))
    }

    @Test
    fun TYPE이_전송_컨트롤을_직접_가리켜도_막는다() {
        assertEquals(
            MessagingSendSafety.SEND_TARGET_BLOCKED,
            decision(GuidedActionType.TYPE, targetLabel = "전송"),
        )
    }

    @Test
    fun TYPE도_좌표를_모르면_통과하지_않는다() {
        assertEquals(
            MessagingSendSafety.SEND_TARGET_UNVERIFIABLE,
            decision(GuidedActionType.TYPE, targetBox = null),
        )
    }

    /** click을 주입하지 않는 action은 이 경계의 대상이 아니다. */
    @Test
    fun 탭을_주입하지_않는_action은_검사하지_않는다() {
        listOf(
            GuidedActionType.SCROLL,
            GuidedActionType.DONE,
            GuidedActionType.OPEN_APP_DRAWER,
            GuidedActionType.OPEN_QUICK_SETTINGS,
        ).forEach { actionType ->
            assertNull("actionType=$actionType", decision(actionType))
        }
        assertEquals(
            setOf(GuidedActionType.TAP, GuidedActionType.TYPE),
            MessagingSendSafety.TAP_INJECTING_ACTIONS,
        )
    }

    @Test
    fun 메시지_package가_아니면_검사하지_않는다() {
        assertNull(decision(GuidedActionType.TAP, screenPackage = "com.sec.android.app.camera"))
        assertNull(decision(GuidedActionType.TYPE, screenPackage = "com.sec.android.app.camera"))
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
                sendBoxes = boxesOf(sendElement()),
                sendControlRequired = true,
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
