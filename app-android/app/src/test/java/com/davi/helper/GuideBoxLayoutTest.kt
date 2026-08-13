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
        controlHeight: Float = 0f,
    ) = GuideBoxLayout.compute(
        instruction = instruction,
        canvas = canvas,
        target = target,
        margin = MARGIN,
        padding = PADDING,
        lineHeight = LINE_HEIGHT,
        measure = measure,
        avoid = avoid,
        controlHeight = controlHeight,
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

    @Test
    fun `작은 영역을 통째로 가리느니 큰 영역을 조금 가린다`() {
        // 넓이로 재면 작은 것이 언제나 먼저 희생된다 — 입력칸(12,000)을 통째로 덮는 자리가
        // 키보드를 62px 무는 자리(59,520)보다 싸게 나온다. 비율로 재면 1.0 vs 0.07이다.
        val screen = GuideBoxLayout.Bounds(0f, 880f, 1000f, 2000f)
        val field = GuideBoxLayout.Avoid(GuideBoxLayout.Bounds(400f, 900f, 600f, 960f), hard = true)
        val keyboard = GuideBoxLayout.Avoid(GuideBoxLayout.Bounds(0f, 1200f, 1000f, 2000f), hard = true)

        val layout = compute("가나다", canvas = screen, avoid = listOf(field, keyboard))

        // 입력칸(900~960)을 덮는 화면 위(900)가 아니라 키보드를 조금 무는 화면 아래(1918).
        assertEquals(1918f, layout?.box?.top)
    }

    @Test
    fun `절반 상자로 hard를 못 피하면 3줄로 떨어진다`() {
        // 캡처 9 배치의 축약: 대상(1000~1100) 위 공간 980, hard 키보드(1150~) 위 빈 띠 50.
        // 절반 상자(32줄, 992)는 어디에도 못 들어가 대상을 물고, 3줄 상자(122)는
        // 대상 위에 온전히 들어간다.
        val long = (1..40).joinToString("\n") { "줄$it" }

        val layout = compute(long, target = TARGET, avoid = listOf(keyboard(hard = true)))

        assertEquals(3, layout?.lines?.size)
        assertTrue(layout!!.lines.last().endsWith("…"))
        // 대상(top=1000)도 키보드(top=1150)도 건드리지 않는다.
        assertTrue(layout.box.bottom <= 1000f - MARGIN)
    }

    @Test
    fun `축소 폴백이 절반 상한보다 상자를 키우지 않는다`() {
        // 작은 화면·아주 큰 글자에서는 절반이 3줄보다 적다(여기선 2줄).
        // 그대로 3줄을 쓰면 축소 폴백이 상자를 되레 키워 절반 상한을 깬다.
        val small = GuideBoxLayout.Bounds(0f, 0f, 1000f, 200f)
        val wall = GuideBoxLayout.Avoid(GuideBoxLayout.Bounds(0f, 0f, 1000f, 200f), hard = true)
        val long = (1..40).joinToString("\n") { "줄$it" }

        val layout = compute(long, canvas = small, avoid = listOf(wall))

        assertEquals(2, layout?.lines?.size)
    }

    @Test
    fun `soft를 크게 덮으면 3줄로 줄여 덜 덮는다`() {
        // 캡처 8 축약: tap 안내라 키보드가 soft인데, 다이얼로그(위)와 키보드(아래)가 화면을
        // 나눠 덮어 어느 자리든 soft를 문다. 이전엔 hard 0이라 절반 상자를 그대로 뒀지만,
        // 무관한 키보드조차 최소한으로만 덮도록 3줄로 줄인다(PR #118 리뷰 대응).
        val dialogSoft = GuideBoxLayout.Avoid(GuideBoxLayout.Bounds(0f, 0f, 1000f, 1000f), hard = false)
        val keyboardSoft = GuideBoxLayout.Avoid(GuideBoxLayout.Bounds(0f, 1000f, 1000f, 2000f), hard = false)
        val long = (1..40).joinToString("\n") { "줄$it" }

        val layout = compute(long, avoid = listOf(dialogSoft, keyboardSoft))

        assertEquals(3, layout?.lines?.size)
        assertTrue(layout!!.lines.last().endsWith("…"))
    }

    @Test
    fun `soft 침범이 없으면 긴 안내문도 줄이지 않는다`() {
        // 피할 자리가 있으면 글줄을 버릴 이유가 없다. 절반 상자(32줄, 992)가 soft 위(1200~)를
        // 피해 화면 위쪽에 온전히 들어간다.
        val lowSoft = GuideBoxLayout.Avoid(GuideBoxLayout.Bounds(0f, 1200f, 1000f, 2000f), hard = false)
        val long = (1..40).joinToString("\n") { "줄$it" }

        val layout = compute(long, avoid = listOf(lowSoft))

        assertEquals(32, layout?.lines?.size)
        assertTrue(layout!!.box.bottom <= 1200f)
    }

    @Test
    fun `축소 줄 수는 글자가 작아져도 늘지 않는다`() {
        // 비율 상한이었다면 lineHeight가 절반이 될 때 줄 수가 배로 늘어 상자가 도로
        // 커진다 — 폴백이 발동하는 화면은 빡빡한 화면이라 고정 3줄이 안전하다.
        // 여기서는 절반 상자(32줄)가 hard(0~1970를 덮는 큰 영역)를 못 피해 폴백이 돈다.
        val long = (1..40).joinToString("\n") { "줄$it" }
        val wall = GuideBoxLayout.Avoid(GuideBoxLayout.Bounds(0f, 0f, 1000f, 1970f), hard = true)

        val layout = compute(long, avoid = listOf(wall))

        assertEquals(3, layout?.lines?.size)
    }

    @Test
    fun `절반 상자가 이미 hard를 피하면 줄이지 않는다`() {
        // 위쪽 절반이 비어 있으면 32줄 그대로 둔다 — 글줄을 공짜로 버리지 않는다.
        val long = (1..40).joinToString("\n") { "줄$it" }
        val lowKeyboard = GuideBoxLayout.Avoid(GuideBoxLayout.Bounds(0f, 1500f, 1000f, 2000f), hard = true)

        val layout = compute(long, avoid = listOf(lowKeyboard))

        assertEquals(32, layout?.lines?.size)
        assertTrue(layout!!.box.bottom <= 1500f)
    }

    @Test
    fun `컨트롤 행만큼 상자가 높아지되 줄바꿈은 그대로다`() {
        val text = "여기를 누르세요"

        val plain = compute(text)
        val withRow = compute(text, controlHeight = CONTROL_ROW)

        assertEquals(plain?.lines, withRow?.lines)
        assertEquals(
            plain!!.box.bottom - plain.box.top + CONTROL_ROW,
            withRow!!.box.bottom - withRow.box.top,
            0.01f,
        )
    }

    @Test
    fun `컨트롤 행은 절반 상한 예산에서 먼저 빠진다`() {
        // 안 빼면 칩 높이만큼 절반을 넘는다. (1000 - 32 - 90) / 30 = 29줄.
        val long = (1..40).joinToString("\n") { "줄$it" }

        val layout = compute(long, controlHeight = CONTROL_ROW)

        assertEquals(29, layout?.lines?.size)
        assertTrue(layout!!.box.bottom - layout.box.top <= 1000f)
    }

    @Test
    fun `컨트롤 행이 회피 대상을 물면 다른 자리로 옮긴다`() {
        // 칩 자리도 상자의 일부다 — 텍스트 높이로만 재면 칩이 회피 영역을 덮는다(#126).
        val band = GuideBoxLayout.Avoid(GuideBoxLayout.Bounds(0f, 850f, 1000f, 900f), hard = true)
        val text = "여기를 누르세요"

        val plain = compute(text, target = TARGET, avoid = listOf(band))
        val withRow = compute(text, target = TARGET, avoid = listOf(band), controlHeight = CONTROL_ROW)

        assertEquals(918f, plain!!.box.top, 0.01f) // 대상 위 — 띠에 안 닿는다
        assertEquals(1120f, withRow!!.box.top, 0.01f) // 컨트롤 행까지 재면 띠를 물어 대상 아래로
    }

    private fun boxHeight(lines: Int) = lines * LINE_HEIGHT + PADDING * 2

    private fun keyboard(hard: Boolean) =
        GuideBoxLayout.Avoid(GuideBoxLayout.Bounds(0f, 1150f, 1000f, 2000f), hard = hard)

    private companion object {
        const val CHAR_WIDTH = 10f
        const val MARGIN = 20f
        const val PADDING = 16f
        const val LINE_HEIGHT = 30f

        /** 칩이 앉는 컨트롤 행 높이(#126). */
        const val CONTROL_ROW = 90f

        /** 상자 안쪽 폭 928 / 글자폭 10. */
        const val LINE_CHARS = 92

        /** 키보드 위 입력 대상. 아래(1120)는 키보드를 조금 물고, 위(918)는 다이얼로그를 문다. */
        val TARGET = GuideBoxLayout.Bounds(100f, 1000f, 400f, 1100f)
        val DIALOG = GuideBoxLayout.Avoid(GuideBoxLayout.Bounds(0f, 0f, 1000f, 980f), hard = false)
    }
}
