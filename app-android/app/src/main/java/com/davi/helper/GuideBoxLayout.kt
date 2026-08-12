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

    /**
     * 안내창이 가리지 말아야 할 영역.
     *
     * [hard]는 **가리면 지금 안내한 조작이 불가능해지는** 영역이다 — 강조 대상, 그리고 이번
     * 안내가 타이핑일 때의 키보드. 그 외(무관하게 떠 있는 키보드, 다이얼로그 본문)는 가려도
     * 조작은 되므로 최선 노력이다(#38 4·5번).
     */
    data class Avoid(val bounds: Bounds, val hard: Boolean)

    /** 그릴 것이 확정된 안내창. [lines]는 이미 접힌 상태다. */
    data class Layout(val lines: List<String>, val box: Bounds)

    /**
     * @param canvas   그릴 수 있는 범위(테두리 두께를 물린 뒤의 화면 안쪽).
     * @param target   강조 대상. 없으면 null이고, 그때는 화면 아래에 붙인다. 언제나 절대 회피다.
     * @param avoid    대상 밖의 회피 영역.
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
        avoid: List<Avoid> = emptyList(),
    ): Layout? {
        if (instruction.isEmpty()) return null

        val left = canvas.left + margin
        val right = canvas.right - margin
        // 줄바꿈 기준은 **상자 안쪽 폭**이다. 상자 폭 그대로 재면 글자가 상자 밖으로 삐져나온다.
        val textWidth = (right - left - padding * 2).coerceAtLeast(1f)
        val lines = wrapLines(instruction, textWidth, measure)
        val height = lines.size * lineHeight + padding * 2

        val top = topFor(height, left, right, canvas, target, avoid, margin)
        return Layout(lines, Bounds(left, top, right, top + height))
    }

    /**
     * 들어갈 수 있는 자리 중 **절대 회피를 안 건드리는 자리**를 고르고, 그중 최선 노력 영역과
     * 겹치는 넓이가 가장 작은 것을 쓴다. 전부 겹치면 덜 겹치는 쪽이다.
     *
     * 동점이면 후보 순서를 따른다(대상 위 → 대상 아래 → 화면 아래 → 화면 위).
     * [minWith]가 첫 최솟값을 돌려주므로 기존 우선순위가 그대로 유지된다.
     */
    private fun topFor(
        height: Float,
        left: Float,
        right: Float,
        canvas: Bounds,
        target: Bounds?,
        avoid: List<Avoid>,
        margin: Float,
    ): Float {
        val candidates = candidateTops(height, canvas, target, margin)
        if (candidates.isEmpty()) return bottomAnchored(height, canvas, margin)

        // 대상은 언제나 절대 회피다 — 무엇을 누를지 안 보이면 안내가 성립하지 않는다.
        val hard = avoid.filter { it.hard }.map { it.bounds } + listOfNotNull(target)
        val soft = avoid.filterNot { it.hard }.map { it.bounds }

        fun boxAt(top: Float) = Bounds(left, top, right, top + height)
        return candidates.minWith(
            compareBy({ overlap(boxAt(it), hard) }, { overlap(boxAt(it), soft) }),
        )
    }

    /** 상자가 화면 안에 온전히 들어가는 자리만 남긴다. */
    private fun candidateTops(
        height: Float,
        canvas: Bounds,
        target: Bounds?,
        margin: Float,
    ): List<Float> {
        val tops = mutableListOf<Float>()
        if (target != null) {
            tops += target.top - margin - height
            tops += target.bottom + margin
        }
        tops += canvas.bottom - margin - height
        tops += canvas.top + margin
        return tops.filter { it >= canvas.top && it + height <= canvas.bottom }.distinct()
    }

    private fun overlap(box: Bounds, regions: List<Bounds>): Float =
        regions.fold(0f) { sum, region -> sum + intersectionArea(box, region) }

    private fun intersectionArea(a: Bounds, b: Bounds): Float {
        val width = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val height = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        return if (width <= 0f || height <= 0f) 0f else width * height
    }

    /** 어느 자리도 온전히 들어가지 않을 때. 상자가 화면보다 크면 위쪽을 살린다. */
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
