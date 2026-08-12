package com.davi.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 안내창 줄바꿈·배치 회귀. `HighlightOverlayView` 안에 있던 동안은 걸 수 없던 테스트다(#38 1번).
 *
 * 폭 측정은 "글자 하나당 [CHAR_WIDTH]"인 가짜로 대신한다. `Paint.breakText`의 계약만 같으면
 * 되므로 실제 글꼴이 필요 없다.
 */
class GuideBoxLayoutTest {

    private val measure = GuideBoxLayout.Measure { text, maxWidth ->
        val fits = (maxWidth / CHAR_WIDTH).toInt()
        minOf(text.length, maxOf(fits, 0))
    }

    private fun compute(
        instruction: String,
        target: GuideBoxLayout.Bounds? = null,
        canvas: GuideBoxLayout.Bounds = GuideBoxLayout.Bounds(0f, 0f, 1000f, 2000f),
        avoid: List<GuideBoxLayout.Avoid> = emptyList(),
    ) = GuideBoxLayout.compute(
        instruction = instruction,
        canvas = canvas,
        target = target,
        margin = MARGIN,
        padding = PADDING,
        lineHeight = LINE_HEIGHT,
        measure = measure,
        avoid = avoid,
    )

    @Test
    fun `안내문이 없으면 그릴 것도 없다`() {
        assertNull(compute(""))
    }

    @Test(timeout = 1_000)
    fun `한 글자도 안 들어가는 폭에서도 루프가 끝난다`() {
        // 이 계산은 onDraw 안에서 돌던 코드다. 안 끝나면 화면이 멈춘다(ANR).
        // 좁은 화면이나 아주 큰 글꼴에서 실제로 도달할 수 있는 상태라 가드를 테스트로 고정한다.
        val narrow = GuideBoxLayout.Bounds(0f, 0f, MARGIN * 2 + PADDING * 2, 2000f)

        val layout = compute("가나다라", canvas = narrow)

        assertEquals(listOf("가", "나", "다", "라"), layout?.lines)
    }

    @Test
    fun `띄어쓰기가 없으면 폭에서 강제로 끊는다`() {
        val layout = compute("가".repeat(120))

        assertEquals(2, layout?.lines?.size)
        assertTrue(layout!!.lines.all { it.length <= LINE_CHARS })
    }

    @Test
    fun `가능하면 띄어쓰기에서 끊는다`() {
        // 한 줄에 92자가 들어가는 폭에서 81번째 글자가 공백이면 거기서 끊는다.
        val layout = compute("${"가".repeat(80)} ${"나".repeat(20)}")

        assertEquals(listOf("가".repeat(80), "나".repeat(20)), layout?.lines)
    }

    @Test
    fun `개행이 보통 글자로 삼켜지지 않는다`() {
        // breakText는 \n을 글자로 세므로 문단을 먼저 갈라야 한다.
        // 없으면 두 문장이 "…주세요.화면이"처럼 붙는다(실기기에서 확인).
        val layout = compute("다 되면 눌러 주세요.\n화면이 바뀝니다.")

        assertEquals(listOf("다 되면 눌러 주세요.", "화면이 바뀝니다."), layout?.lines)
    }

    @Test
    fun `대상 위에 자리가 있으면 위에 놓는다`() {
        val layout = compute("가나다", target = GuideBoxLayout.Bounds(100f, 500f, 400f, 600f))

        assertEquals(500f - MARGIN - boxHeight(1), layout?.box?.top)
    }

    @Test
    fun `대상 위가 좁으면 아래에 놓는다`() {
        val layout = compute("가나다", target = GuideBoxLayout.Bounds(100f, 10f, 400f, 100f))

        assertEquals(100f + MARGIN, layout?.box?.top)
    }

    @Test
    fun `위아래 모두 안 되면 화면 아래에 붙인다`() {
        val layout = compute("가나다", target = GuideBoxLayout.Bounds(100f, 10f, 400f, 1990f))

        assertEquals(2000f - MARGIN - boxHeight(1), layout?.box?.top)
    }

    @Test
    fun `강조 대상이 없어도 안내문은 보여준다`() {
        // 서버가 화면에 없는 대상을 골라도 안내는 떠야 한다.
        val layout = compute("가나다", target = null)

        assertEquals(2000f - MARGIN - boxHeight(1), layout?.box?.top)
    }

    @Test
    fun `다이얼로그 본문을 피해 그 밖으로 나간다`() {
        // 2026-08-12 SM-S921N 실측 배치: 다이얼로그가 화면 하단 밴드(1470~2178)로 뜨고
        // 대상은 그 안의 버튼이다. 지금까지는 "대상 위"가 곧 다이얼로그 본문이었다(#38 4번).
        val screen = GuideBoxLayout.Bounds(0f, 103f, 1080f, 2178f)
        val dialog = GuideBoxLayout.Avoid(GuideBoxLayout.Bounds(0f, 1470f, 1080f, 2178f), hard = false)

        val layout = compute(
            "가나다",
            target = GuideBoxLayout.Bounds(600f, 2000f, 900f, 2100f),
            canvas = screen,
            avoid = listOf(dialog),
        )

        assertEquals(103f + MARGIN, layout?.box?.top)
    }

    @Test
    fun `타이핑 안내면 키보드를 절대 피한다`() {
        val layout = compute("가나다", target = TARGET, avoid = listOf(DIALOG, keyboard(hard = true)))

        // 키보드를 건드리지 않는 자리 중 첫 번째(대상 위).
        assertEquals(918f, layout?.box?.top)
    }

    @Test
    fun `무관한 키보드는 덜 가리는 쪽을 고른다`() {
        // 같은 화면이라도 이번 안내가 타이핑이 아니면 키보드는 조작 대상이 아니다.
        // 그러면 키보드를 조금 덮더라도 겹침이 가장 작은 자리가 낫다.
        val layout = compute("가나다", target = TARGET, avoid = listOf(DIALOG, keyboard(hard = false)))

        assertEquals(1120f, layout?.box?.top)
    }

    @Test
    fun `상자 폭은 여백을 뺀 만큼이다`() {
        val layout = compute("가나다")

        assertEquals(MARGIN, layout?.box?.left)
        assertEquals(1000f - MARGIN, layout?.box?.right)
    }

    @Test
    fun `화면보다 긴 안내문은 절반 상한에서 잘리고 말줄임이 붙는다`() {
        // canvas 높이 2000 → 상한 1000 → (1000-32)/30 = 32줄.
        val long = (1..40).joinToString("\n") { "줄$it" }

        val layout = compute(long)

        assertEquals(32, layout?.lines?.size)
        assertTrue(layout!!.lines.last().endsWith("…"))
        assertTrue(layout.box.bottom - layout.box.top <= 1000f)
    }

    @Test
    fun `말줄임이 붙은 마지막 줄도 폭 안에 들어간다`() {
        // 좁은 화면: 안쪽 폭 6글자. 마지막 줄이 꽉 찬 채 말줄임을 덧붙이면 폭을 넘는다.
        val narrow = GuideBoxLayout.Bounds(0f, 0f, MARGIN * 2 + PADDING * 2 + CHAR_WIDTH * 6, 200f)
        val long = (1..10).joinToString("\n") { "가나다라마바" }

        val layout = compute(long, canvas = narrow)

        assertTrue(layout!!.lines.last().endsWith("…"))
        assertTrue(layout.lines.last().length <= 6)
    }

    @Test
    fun `상한으로 잘린 뒤에는 회피가 다시 동작한다`() {
        // 잘리기 전이라면 상자가 화면을 넘어 후보 0개 → 회피 무시 폴백이었다(2026-08-12 실측).
        // 상한 덕에 후보가 살아나 hard 키보드를 피하는 것을 고정한다.
        val long = (1..40).joinToString("\n") { "줄$it" }
        val keyboardTop = 1150f

        val layout = compute(long, target = TARGET, avoid = listOf(keyboard(hard = true)))

        // 키보드(1150~2000)와 겹치지 않는 유일한 방향은 위쪽이다.
        assertTrue(layout!!.box.bottom <= keyboardTop)
    }

    private fun boxHeight(lines: Int) = lines * LINE_HEIGHT + PADDING * 2

    private fun keyboard(hard: Boolean) =
        GuideBoxLayout.Avoid(GuideBoxLayout.Bounds(0f, 1150f, 1000f, 2000f), hard = hard)

    private companion object {
        const val CHAR_WIDTH = 10f
        const val MARGIN = 20f
        const val PADDING = 16f
        const val LINE_HEIGHT = 30f

        /** 상자 안쪽 폭 928 / 글자폭 10. */
        const val LINE_CHARS = 92

        /** 키보드 위 입력 대상. 아래(1120)는 키보드를 조금 물고, 위(918)는 다이얼로그를 문다. */
        val TARGET = GuideBoxLayout.Bounds(100f, 1000f, 400f, 1100f)
        val DIALOG = GuideBoxLayout.Avoid(GuideBoxLayout.Bounds(0f, 0f, 1000f, 980f), hard = false)
    }
}
