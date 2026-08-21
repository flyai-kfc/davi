package com.davi.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 방향 제스처 시연 계산의 고정(#152).
 *
 * **이 파일의 1순위는 손이 사용자가 실제로 해야 하는 방향으로 움직이는 것이다.**
 * 눈으로는 "그럴듯하게 움직이니까" 지나치기 쉬운 종류의 결함이라 값으로 못박는다.
 *
 * 계약(C17)의 `direction`은 **보러 가는 방향**이라 손 모션은 그 반대를 그린다 —
 * [GestureMotion.fingerDirectionFor]가 한 자리에서 뒤집는다. 그래서 이 파일에는 성격이 다른
 * 두 묶음이 있다 — **변환**이 제대로 도는지와, 변환 뒤의 **변위 계산**이 맞는지.
 */
class GestureMotionTest {

    // --- 서버 값 → 손가락 방향 (C17 — direction은 보러 가는 방향이다) -------------

    @Test
    fun `서버가 준 방향을 넷 모두 뒤집는다`() {
        // 계약이 "보러 가는 쪽"이므로 실제 손가락 동작은 예외 없이 그 반대다(C17, SPEC §4).
        // 화면 상태와 대조해 조건부로 가르는 안은 원리적으로 불가능해서 쓰지 않는다 —
        // 세로 목록에서 up·down이 둘 다 적법하고, scrollable=false 화면엔 대조할 메타가 없다.
        assertEquals(SwipeDirection.DOWN, GestureMotion.fingerDirectionFor(SwipeDirection.UP))
        assertEquals(SwipeDirection.UP, GestureMotion.fingerDirectionFor(SwipeDirection.DOWN))
        assertEquals(SwipeDirection.RIGHT, GestureMotion.fingerDirectionFor(SwipeDirection.LEFT))
        assertEquals(SwipeDirection.LEFT, GestureMotion.fingerDirectionFor(SwipeDirection.RIGHT))
    }

    @Test
    fun `두 번 보정하면 제자리로 돌아온다`() {
        // 보정이 어딘가에서 두 번 걸리면 아무 일도 안 한 것과 같아진다 — 서비스가 한 번만 부르고
        // 뷰는 받은 값을 그대로 그리는지 확인하는 근거가 된다.
        SwipeDirection.entries.forEach {
            assertEquals(it, GestureMotion.fingerDirectionFor(GestureMotion.fingerDirectionFor(it)))
        }
    }

    @Test
    fun `보정된 방향은 서버 값과 정확히 반대다`() {
        SwipeDirection.entries.forEach {
            assertTrue(
                "$it 의 보정이 반대가 아니다",
                GestureMotion.isOpposite(it, GestureMotion.fingerDirectionFor(it)),
            )
        }
    }

    @Test
    fun `아래 내용을 보라는 안내에서 손은 위로 올라간다`() {
        // 실측 사례 그대로 — 설정 목록에서 아래쪽 항목을 찾아야 할 때 서버는 down을 보낸다.
        // 사용자가 실제로 해야 하는 동작은 손가락을 위로 올리는 것이다.
        val travel = GestureMotion.travelFor(
            GestureMotion.fingerDirectionFor(SwipeDirection.DOWN),
            DISTANCE,
        )
        assertTrue("손이 위로 가야 한다(음수) — 실제 ${travel.dy}", travel.dy < 0f)
    }

    // --- 방향 (뒤집힘 회귀) --------------------------------------------------------

    @Test
    fun `UP은 손가락이 위로 간다`() {
        // 화면 좌표계라 위가 음수다. **인자는 보정을 마친 손가락 방향이다** — 서버 원본이 아니다.
        val travel = GestureMotion.travelFor(SwipeDirection.UP, DISTANCE)
        assertEquals(0f, travel.dx, TOLERANCE)
        assertTrue("위로 가야 한다(음수) — 실제 ${travel.dy}", travel.dy < 0f)
    }

    @Test
    fun `DOWN은 손가락이 아래로 간다`() {
        val travel = GestureMotion.travelFor(SwipeDirection.DOWN, DISTANCE)
        assertEquals(0f, travel.dx, TOLERANCE)
        assertTrue("아래로 가야 한다(양수) — 실제 ${travel.dy}", travel.dy > 0f)
    }

    @Test
    fun `LEFT는 손가락이 왼쪽으로 간다`() {
        val travel = GestureMotion.travelFor(SwipeDirection.LEFT, DISTANCE)
        assertEquals(0f, travel.dy, TOLERANCE)
        assertTrue("왼쪽이어야 한다(음수) — 실제 ${travel.dx}", travel.dx < 0f)
    }

    @Test
    fun `RIGHT는 손가락이 오른쪽으로 간다`() {
        val travel = GestureMotion.travelFor(SwipeDirection.RIGHT, DISTANCE)
        assertEquals(0f, travel.dy, TOLERANCE)
        assertTrue("오른쪽이어야 한다(양수) — 실제 ${travel.dx}", travel.dx > 0f)
    }

    @Test
    fun `마주 보는 방향은 정확히 반대다`() {
        // 한 쪽만 뒤집혀도 여기서 걸린다.
        assertTrue(GestureMotion.isOpposite(SwipeDirection.UP, SwipeDirection.DOWN))
        assertTrue(GestureMotion.isOpposite(SwipeDirection.LEFT, SwipeDirection.RIGHT))
    }

    @Test
    fun `직각인 방향은 반대가 아니다`() {
        assertFalse(GestureMotion.isOpposite(SwipeDirection.UP, SwipeDirection.LEFT))
        assertFalse(GestureMotion.isOpposite(SwipeDirection.UP, SwipeDirection.UP))
    }

    @Test
    fun `네 방향이 모두 서로 다른 변위를 준다`() {
        val travels = SwipeDirection.entries.map { GestureMotion.travelFor(it, DISTANCE) }
        assertEquals(travels.size, travels.distinct().size)
    }

    @Test
    fun `세로 방향은 가로로 새지 않고 가로 방향은 세로로 새지 않는다`() {
        listOf(SwipeDirection.UP, SwipeDirection.DOWN).forEach {
            assertEquals("세로 제스처에 가로 성분이 있다", 0f, GestureMotion.travelFor(it, DISTANCE).dx, TOLERANCE)
        }
        listOf(SwipeDirection.LEFT, SwipeDirection.RIGHT).forEach {
            assertEquals("가로 제스처에 세로 성분이 있다", 0f, GestureMotion.travelFor(it, DISTANCE).dy, TOLERANCE)
        }
    }

    @Test
    fun `이동 거리에 비례한다`() {
        val near = GestureMotion.travelFor(SwipeDirection.DOWN, 10f)
        val far = GestureMotion.travelFor(SwipeDirection.DOWN, 100f)
        assertEquals(10f, near.dy, TOLERANCE)
        assertEquals(100f, far.dy, TOLERANCE)
    }

    // --- 무엇에 그리는가 -----------------------------------------------------------

    @Test
    fun `방향이 있는 스크롤을 시연한다`() {
        assertTrue(GestureMotion.isDemonstrated(ActionType.SCROLL, SwipeDirection.UP))
        assertTrue(GestureMotion.isDemonstrated(ActionType.SCROLL, SwipeDirection.RIGHT))
    }

    @Test
    fun `가장자리 제스처는 방향 없이도 시연한다`() {
        // #222 — 이 둘은 서버가 direction을 보내지 않는다. 방향은 액션 종류가 갖고 있다.
        assertTrue(GestureMotion.isDemonstrated(ActionType.OPEN_QUICK_SETTINGS, null))
        assertTrue(GestureMotion.isDemonstrated(ActionType.OPEN_APP_DRAWER, null))
    }

    @Test
    fun `탭은 시연하지 않는다`() {
        // 후광(#147)만으로 충분하고, 대상 위에 손을 겹치면 그 후광을 가린다(2026-08-14 결정).
        assertFalse(GestureMotion.isDemonstrated(ActionType.TAP, SwipeDirection.UP))
        assertFalse(GestureMotion.isDemonstrated(ActionType.TAP, null))
    }

    @Test
    fun `방향 없는 액션은 시연하지 않는다`() {
        // 방향이 없으면 어디로 움직일지 알 수 없다 — 서버가 scroll에 방향을 빠뜨릴 수도 있다.
        assertFalse(GestureMotion.isDemonstrated(ActionType.SCROLL, null))
        assertFalse(GestureMotion.isDemonstrated(ActionType.DONE, null))
        assertFalse(GestureMotion.isDemonstrated(ActionType.TYPE, null))
        assertFalse(GestureMotion.isDemonstrated(null, SwipeDirection.UP))
    }

    // --- 한 주기의 모양 -------------------------------------------------------------

    @Test
    fun `손은 출발에서 도착까지 한 방향으로만 간다`() {
        // 왕복시키면 되돌아오는 절반이 반대 방향 안내로 읽힌다.
        val samples = (0..10).map { GestureMotion.progressAt(it / 10f * GestureMotion.STROKE_FRACTION) }
        samples.zipWithNext { a, b -> assertTrue("$a <= $b 이어야 한다", a <= b) }
    }

    @Test
    fun `이동 구간이 끝나면 도착점에 머문다`() {
        assertEquals(1f, GestureMotion.progressAt(GestureMotion.STROKE_FRACTION), TOLERANCE)
        assertEquals(1f, GestureMotion.progressAt(0.95f), TOLERANCE)
    }

    @Test
    fun `되돌아오는 구간에서는 손이 보이지 않는다`() {
        // 시작점으로 순간이동하는 것을 감춘다.
        assertEquals(0f, GestureMotion.alphaAt(GestureMotion.STROKE_FRACTION + 0.05f), TOLERANCE)
        assertEquals(0f, GestureMotion.alphaAt(0.99f), TOLERANCE)
    }

    @Test
    fun `양 끝에서 부드럽게 나타나고 사라진다`() {
        assertEquals(0f, GestureMotion.alphaAt(0f), TOLERANCE)
        assertTrue(GestureMotion.alphaAt(GestureMotion.STROKE_FRACTION / 2f) > 0.9f)
    }

    @Test
    fun `진하기는 언제나 0과 1 사이다`() {
        var phase = 0f
        while (phase < 2f) {
            val a = GestureMotion.alphaAt(phase)
            assertTrue("위상 $phase 에서 $a", a in 0f..1f)
            phase += 0.01f
        }
    }

    @Test
    fun `한 주기가 지나면 같은 모습으로 돌아온다`() {
        assertEquals(GestureMotion.alphaAt(0.3f), GestureMotion.alphaAt(1.3f), TOLERANCE)
        assertEquals(GestureMotion.progressAt(0.3f), GestureMotion.progressAt(1.3f), TOLERANCE)
    }

    // --- 정지 프레임 ---------------------------------------------------------------

    @Test
    fun `정지 위상은 이동 중간이 아니라 끝점 가까이다`() {
        // 중간에서 멈추면 그냥 손이 떠 있는 그림이라 방향이 읽히지 않는다.
        assertTrue(GestureMotion.progressAt(GestureMotion.STATIC_PHASE) > 0.6f)
    }

    @Test
    fun `정지 프레임에서 손이 확실히 보인다`() {
        // 하한이 없으면 하필 옅어지는 구간이 걸려 손이 흐릿하게 멈춘다.
        assertTrue("정지 진하기 ${GestureMotion.staticAlpha()}", GestureMotion.staticAlpha() > 0.8f)
    }

    @Test
    fun `정지 프레임은 출발점 잔상과 본체가 떨어져 있다`() {
        // 한 장만 있으면 기준점이 없어 어느 쪽으로 가라는 것인지 읽히지 않는다(PR #159 리뷰 5번).
        val ghost = GestureMotion.STATIC_GHOST_PROGRESS
        val main = GestureMotion.progressAt(GestureMotion.STATIC_PHASE)
        assertTrue("잔상 $ghost 과 본체 $main 이 겹친다", main - ghost > 0.5f)
    }

    @Test
    fun `잔상은 본체보다 확실히 옅다`() {
        // 둘이 비슷하면 어느 손이 도착점인지 알 수 없어 방향이 다시 모호해진다.
        assertTrue(
            "잔상 ${GestureMotion.staticGhostAlpha()} vs 본체 ${GestureMotion.staticAlpha()}",
            GestureMotion.staticGhostAlpha() < GestureMotion.staticAlpha() * 0.5f,
        )
        assertTrue("잔상이 아예 안 보인다", GestureMotion.staticGhostAlpha() > 0.1f)
    }

    // --- 안내창 회피 ---------------------------------------------------------------

    @Test
    fun `안내창이 없으면 화면 한가운데다`() {
        assertEquals(
            500f,
            GestureMotion.verticalCenterFor(0f, 1000f, BAND, boxTop = null, boxBottom = null),
            TOLERANCE,
        )
    }

    @Test
    fun `안내창이 아래에 있으면 손은 위쪽 공간 가운데로 간다`() {
        // 안내창은 대상이 없을 때 화면 아래에 붙는다 — 가장 흔한 배치다.
        val center = GestureMotion.verticalCenterFor(0f, 1000f, BAND, boxTop = 700f, boxBottom = 1000f)
        assertEquals(350f, center, TOLERANCE)
        assertTrue("손이 안내창을 침범한다", center + BAND / 2f <= 700f)
    }

    @Test
    fun `안내창이 위에 있으면 손은 아래쪽 공간 가운데로 간다`() {
        val center = GestureMotion.verticalCenterFor(0f, 1000f, BAND, boxTop = 0f, boxBottom = 300f)
        assertEquals(650f, center, TOLERANCE)
        assertTrue("손이 안내창을 침범한다", center - BAND / 2f >= 300f)
    }

    @Test
    fun `손이 다 들어가는 쪽이 하나뿐이면 넓지 않아도 그쪽을 고른다`() {
        // 위가 더 넓지만 손이 안 들어간다. 아래는 좁아도 들어간다 — 겹치지 않는 쪽이 우선이다.
        val center = GestureMotion.verticalCenterFor(0f, 1000f, 300f, boxTop = 280f, boxBottom = 600f)
        assertEquals(800f, center, TOLERANCE)
        assertTrue(center - 150f >= 600f)
    }

    @Test
    fun `어느 쪽에도 안 들어가면 넓은 쪽에 두되 화면 안으로 물린다`() {
        // 겹치는 것은 감수하지만 잘리는 것은 아니다 — 잘리면 방향을 말해 주는 끝점이 먼저 사라진다.
        // 위(400)가 아래(300)보다 넓어 위쪽을 고르지만, 중점 200으로는 손이 -250까지 삐져나온다.
        val band = 900f
        val center = GestureMotion.verticalCenterFor(0f, 1000f, band, boxTop = 400f, boxBottom = 700f)
        assertTrue(
            "화면 밖으로 나갔다 — 구간 ${center - band / 2f}..${center + band / 2f}",
            center - band / 2f >= 0f && center + band / 2f <= 1000f,
        )
    }

    @Test
    fun `손이 화면보다 크면 한가운데에 둔다`() {
        // 물릴 곳이 없다. 위아래로 고르게 잘리는 편이 한쪽만 잘리는 것보다 낫다.
        assertEquals(
            500f,
            GestureMotion.verticalCenterFor(0f, 1000f, 1200f, boxTop = 400f, boxBottom = 700f),
            TOLERANCE,
        )
    }


    // --- 가장자리 제스처의 자리 (#222) ------------------------------------------------

    @Test
    fun `가장자리 제스처는 C17 뒤집기를 타지 않는다`() {
        // 정하고 갈 것 3 — fingerDirectionFor는 **서버가 준 값**을 뒤집는 자리다. 여기 방향은
        // 처음부터 손가락 방향이라, 한 번 더 태우면 셰이드를 위로 올리라고 가르치게 된다.
        val quick = requireNotNull(EdgeGesture.specFor(ActionType.OPEN_QUICK_SETTINGS))
        val drawer = requireNotNull(EdgeGesture.specFor(ActionType.OPEN_APP_DRAWER))
        assertEquals(SwipeDirection.DOWN, quick.fingerDirection)
        assertEquals(SwipeDirection.UP, drawer.fingerDirection)
        assertTrue(GestureMotion.travelFor(quick.fingerDirection, DISTANCE).dy > 0f)
        assertTrue(GestureMotion.travelFor(drawer.fingerDirection, DISTANCE).dy < 0f)
    }

    @Test
    fun `손은 정확히 화면 변에서 출발한다`() {
        // 시작 지점이 곧 이 안내의 알맹이다. 한 뼘이라도 안쪽에서 시작하면 "화면 밖에서 끌어온다"가
        // 사라져 그냥 스크롤 시연과 구분되지 않는다.
        listOf(0f to DISTANCE, HEIGHT to -DISTANCE).forEach { (edgeY, dy) ->
            val travel = GestureMotion.Travel(0f, dy)
            val center = GestureMotion.edgeCenterFor(edgeY, travel)
            // 그리는 쪽이 중심에서 이동의 절반을 되돌려 출발점을 잡는다.
            assertEquals(edgeY, center - travel.dy / 2f, TOLERANCE)
        }
    }

    @Test
    fun `앵커가 없으면 손은 화면 가로 가운데다`() {
        // 스크롤은 가리킬 가로 위치가 없다 — 기존 동작 그대로여야 한다.
        assertEquals(
            WIDTH / 2f,
            GestureMotion.horizontalCenterFor(0f, WIDTH, HAND),
            TOLERANCE,
        )
    }

    @Test
    fun `앵커가 있으면 손은 그 가운데서 출발한다`() {
        // 퀵세팅의 오른쪽 절반 — One UI 6+에서 왼쪽 위는 알림창이라 가운데로 그리면 안 된다.
        val quick = requireNotNull(EdgeGesture.specFor(ActionType.OPEN_QUICK_SETTINGS))
        val band = EdgeGesture.bandFor(quick, 0f, 0f, WIDTH, HEIGHT, EDGE_BAND)
        assertEquals(
            WIDTH * 0.75f,
            GestureMotion.horizontalCenterFor(0f, WIDTH, HAND, band.left, band.right),
            TOLERANCE,
        )
    }

    @Test
    fun `앵커가 가장자리에 붙어도 손은 화면 안에 남는다`() {
        // 잘린 손은 방향을 말하지 못한다(세로에서 verticalCenterFor가 세운 규칙과 같다).
        val left = GestureMotion.horizontalCenterFor(0f, WIDTH, HAND, 0f, 0f)
        val right = GestureMotion.horizontalCenterFor(0f, WIDTH, HAND, WIDTH, WIDTH)
        assertEquals(HAND / 2f, left, TOLERANCE)
        assertEquals(WIDTH - HAND / 2f, right, TOLERANCE)
    }

    @Test
    fun `손이 캔버스보다 넓으면 가운데에 둔다`() {
        // 물릴 곳이 없다 — 억지로 물리면 좌우 하한이 상한을 넘어 coerceIn이 던진다.
        assertEquals(
            WIDTH / 2f,
            GestureMotion.horizontalCenterFor(0f, WIDTH, WIDTH * 2f, 0f, WIDTH),
            TOLERANCE,
        )
    }

    private companion object {
        /** 세로 스크롤에서 손이 훑는 폭 — 이동 거리 + 손 높이. */
        const val BAND = 200f
        const val DISTANCE = 120f
        const val TOLERANCE = 0.001f

        // 가장자리 제스처(#222). 검증 기기(1080x2340, 3x)의 픽셀값 근사다.
        const val WIDTH = 1080f
        const val HEIGHT = 2340f

        /** 손 크기 96dp를 3x로 친 값. */
        const val HAND = 288f

        /** 가장자리 선 8dp를 3x로 친 값. */
        const val EDGE_BAND = 24f
    }
}
