package com.davi.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 가장자리 제스처 사양의 고정(#222).
 *
 * **이 파일이 지키는 것은 "제조사·버전 지식이 한 표에만 있다"이다.** 값 자체가 실측(One UI 8.5,
 * HANDOFF §3-12)에서 왔으므로 다른 기기가 오면 [EdgeGesture.specFor]와 이 파일이 함께 움직인다 —
 * 그 밖의 어디도 손대지 않아야 격리가 유지된 것이다.
 */
class EdgeGestureTest {

    @Test
    fun `퀵세팅은 상단 오른쪽 절반에서 아래로 끌어내린다`() {
        // One UI 6+는 오른쪽 위가 빠른 설정, 왼쪽 위가 알림창이다. 전체 폭을 강조하면 왼쪽에서
        // 끌어내린 시니어가 알림창을 열고, 그 화면에는 우리가 안내한 것이 없다.
        val spec = requireNotNull(EdgeGesture.specFor(ActionType.OPEN_QUICK_SETTINGS))
        assertEquals(EdgeGesture.Edge.TOP, spec.edge)
        assertEquals(SwipeDirection.DOWN, spec.fingerDirection)
        assertEquals(0.5f, spec.startFraction, TOLERANCE)
        assertEquals(1f, spec.endFraction, TOLERANCE)
    }

    @Test
    fun `앱 서랍은 하단 전체 폭에서 위로 끌어올린다`() {
        // 홈에서 위로 쓸어올리는 동작은 가로 위치를 가리지 않는다 — 근거 없이 좁히면
        // "거기서만 된다"는 틀린 지식을 가르친다.
        val spec = requireNotNull(EdgeGesture.specFor(ActionType.OPEN_APP_DRAWER))
        assertEquals(EdgeGesture.Edge.BOTTOM, spec.edge)
        assertEquals(SwipeDirection.UP, spec.fingerDirection)
        assertEquals(0f, spec.startFraction, TOLERANCE)
        assertEquals(1f, spec.endFraction, TOLERANCE)
    }

    @Test
    fun `나머지 액션은 가장자리 제스처가 아니다`() {
        // 표가 넓어지면 tap·scroll 안내에까지 없는 시작 지점이 그려진다.
        listOf(ActionType.TAP, ActionType.SCROLL, ActionType.TYPE, ActionType.DONE).forEach {
            assertNull("$it 이 가장자리 제스처로 잡혔다", EdgeGesture.specFor(it))
        }
        assertNull(EdgeGesture.specFor(null))
    }

    @Test
    fun `가장자리 제스처는 둘뿐이다`() {
        // 새 ActionType이 들어올 때 이 표를 볼지 말지를 한 번 묻게 만드는 자리다.
        val edges = ActionType.entries.filter { EdgeGesture.specFor(it) != null }
        assertEquals(
            listOf(ActionType.OPEN_QUICK_SETTINGS, ActionType.OPEN_APP_DRAWER).toSet(),
            edges.toSet(),
        )
    }

    @Test
    fun `상단 선은 화면 맨 위에 딱 붙는다`() {
        // 안으로 물리면 "여기서 시작하라"가 아니라 "이 근처를 눌러라"로 읽힌다.
        val spec = requireNotNull(EdgeGesture.specFor(ActionType.OPEN_QUICK_SETTINGS))
        val band = EdgeGesture.bandFor(spec, 0f, 0f, WIDTH, HEIGHT, THICKNESS)
        assertEquals(0f, band.top, TOLERANCE)
        assertEquals(THICKNESS, band.bottom, TOLERANCE)
        assertEquals(WIDTH / 2f, band.left, TOLERANCE)
        assertEquals(WIDTH, band.right, TOLERANCE)
    }

    @Test
    fun `하단 선은 화면 맨 아래에 딱 붙는다`() {
        val spec = requireNotNull(EdgeGesture.specFor(ActionType.OPEN_APP_DRAWER))
        val band = EdgeGesture.bandFor(spec, 0f, 0f, WIDTH, HEIGHT, THICKNESS)
        assertEquals(HEIGHT - THICKNESS, band.top, TOLERANCE)
        assertEquals(HEIGHT, band.bottom, TOLERANCE)
        assertEquals(0f, band.left, TOLERANCE)
        assertEquals(WIDTH, band.right, TOLERANCE)
    }

    @Test
    fun `캔버스가 원점에 없어도 선은 그 캔버스의 변에 붙는다`() {
        // 컷아웃 게이트 밖(API 28~29, OverlayWindowParams의 R 게이트)에서는 오버레이 창이
        // 상태표시줄 아래(frame=[0,103] 실측)에서 시작한다 — 좌표계를 0으로 가정하면 선이
        // 그만큼 위로 새거나 아래로 밀린다. API 30+는 #222가 창을 올려 [0,0]이다.
        val spec = requireNotNull(EdgeGesture.specFor(ActionType.OPEN_QUICK_SETTINGS))
        val band = EdgeGesture.bandFor(spec, 0f, OFFSET, WIDTH, HEIGHT, THICKNESS)
        assertEquals(OFFSET, band.top, TOLERANCE)
        assertEquals(OFFSET + THICKNESS, band.bottom, TOLERANCE)
    }

    @Test
    fun `손은 선과 같은 변에서 출발한다`() {
        // 선과 손이 다른 변을 가리키면 어디를 잡으라는 것인지 알 수 없다.
        val quick = requireNotNull(EdgeGesture.specFor(ActionType.OPEN_QUICK_SETTINGS))
        val drawer = requireNotNull(EdgeGesture.specFor(ActionType.OPEN_APP_DRAWER))
        assertEquals(OFFSET, EdgeGesture.startEdgeY(quick, OFFSET, HEIGHT), TOLERANCE)
        assertEquals(HEIGHT, EdgeGesture.startEdgeY(drawer, OFFSET, HEIGHT), TOLERANCE)
    }

    @Test
    fun `선이 있는 액션은 손 모션도 함께 뜬다`() {
        // 강조만 뜨고 손이 없으면 "여기를 눌러라"로 읽혀 정반대를 가르친다.
        ActionType.entries.filter { EdgeGesture.specFor(it) != null }.forEach {
            assertNotNull(EdgeGesture.specFor(it)?.fingerDirection)
            org.junit.Assert.assertTrue(
                "$it 에 손 모션이 안 뜬다",
                GestureMotion.isDemonstrated(it, null),
            )
        }
    }

    private companion object {
        const val WIDTH = 1080f
        const val HEIGHT = 2340f

        /** 가장자리 선 8dp를 3x로 친 값. */
        const val THICKNESS = 24f

        /** 컷아웃 게이트 밖에서 오버레이 창이 상태표시줄만큼 아래에서 시작하는 실측값(SM-S921N). */
        const val OFFSET = 103f
        const val TOLERANCE = 0.001f
    }
}
