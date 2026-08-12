package com.davi.helper

/**
 * 안내창의 줄바꿈과 놓을 자리를 계산하는 순수 로직. [HighlightOverlayView]는 결과를 그리기만 한다.
 *
 * **Android 타입을 쓰지 않는다.** [GuideDisplayState]와 같은 이유다 — 이 계산은 `onDraw` 안에서
 * 돌던 코드라 실패하면 화면이 멈추는데(무한 루프 가드), View 안에 있는 동안은 JVM 테스트로
 * 고정할 수 없었다(#38 1번).
 *
 * 좌표는 **화면 좌표 그대로**다. 오버레이 창이 `FLAG_LAYOUT_IN_SCREEN`으로 전체 화면을 차지하므로
 * 접근성 `bounds`와 같은 계를 쓴다.
 */
object GuideBoxLayout {

    /**
     * 주어진 폭에 들어가는 글자 수를 센다. `Paint.breakText(text, true, maxWidth, null)`과 같은 계약이라
     * View 쪽에서 그대로 넘길 수 있고, 테스트에서는 고정 글자폭으로 대신할 수 있다.
     */
    fun interface Measure {
        fun fits(text: String, maxWidth: Float): Int
    }

    data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float)

    /** 그릴 것이 확정된 안내창. [lines]는 이미 접힌 상태다. */
    data class Layout(val lines: List<String>, val box: Bounds)

    /**
     * @param canvas   그릴 수 있는 범위(테두리 두께를 물린 뒤의 화면 안쪽).
     * @param target   강조 대상. 없으면 null이고, 그때는 화면 아래에 붙인다.
     * @return 그릴 것이 없으면 null.
     */
    fun compute(
        instruction: String,
        canvas: Bounds,
        target: Bounds?,
        margin: Float,
        padding: Float,
        lineHeight: Float,
        measure: Measure,
    ): Layout? {
        if (instruction.isEmpty()) return null

        val left = canvas.left + margin
        val right = canvas.right - margin
        // 줄바꿈 기준은 **상자 안쪽 폭**이다. 상자 폭 그대로 재면 글자가 상자 밖으로 삐져나온다.
        val textWidth = (right - left - padding * 2).coerceAtLeast(1f)
        val lines = wrapLines(instruction, textWidth, measure)
        val height = lines.size * lineHeight + padding * 2

        val top = topFor(height, canvas, target, margin)
        return Layout(lines, Bounds(left, top, right, top + height))
    }

    /** 대상을 가리지 않도록 위 → 아래 순으로 자리를 고르고, 둘 다 안 되면 화면 아래에 붙인다. */
    private fun topFor(height: Float, canvas: Bounds, target: Bounds?, margin: Float): Float {
        if (target == null) return bottomAnchored(height, canvas, margin)

        val above = target.top - margin - height
        val below = target.bottom + margin
        return when {
            above >= canvas.top -> above
            below + height <= canvas.bottom -> below
            else -> bottomAnchored(height, canvas, margin)
        }
    }

    private fun bottomAnchored(height: Float, canvas: Bounds, margin: Float): Float =
        (canvas.bottom - margin - height).coerceAtLeast(canvas.top)

    /**
     * 명시적 개행을 먼저 끊고, 각 문단을 폭에 맞춰 접는다.
     *
     * `breakText`는 `\n`을 보통 글자로 세기 때문에 이 분리가 없으면 개행이 통째로 삼켜져
     * 두 문장이 "…주세요.화면이"처럼 붙는다(실기기에서 확인).
     */
    internal fun wrapLines(text: String, maxWidth: Float, measure: Measure): List<String> =
        text.split('\n').flatMap { paragraph ->
            if (paragraph.isBlank()) listOf("") else wrapParagraph(paragraph, maxWidth, measure)
        }

    private fun wrapParagraph(text: String, maxWidth: Float, measure: Measure): List<String> {
        val lines = mutableListOf<String>()
        var remaining = text
        while (remaining.isNotEmpty()) {
            val fits = measure.fits(remaining, maxWidth)
            if (fits >= remaining.length) {
                lines += remaining
                break
            }
            // 한 글자도 안 들어가는 폭이면 fits가 0이라 남은 글자가 줄지 않는다. 그대로 두면
            // 루프가 끝나지 않는다. 최소 한 글자는 잘라낸다.
            val hardBreak = fits.coerceAtLeast(1)
            val spaceAt = remaining.lastIndexOf(' ', hardBreak - 1)
            val breakAt = if (spaceAt > 0) spaceAt else hardBreak
            lines += remaining.substring(0, breakAt).trimEnd()
            remaining = remaining.substring(breakAt).trimStart()
        }
        return lines
    }
}
