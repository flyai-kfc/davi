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

    /**
     * 그릴 것이 확정된 안내창. [lines]는 이미 접힌 상태다.
     *
     * [box]는 컨트롤 행까지 **포함한** 안내창 전체다 — 조작 오버레이의 창 크기가 되고, 회피 판정도
     * 이 사각형으로 한다. 칩이 회피 대상을 덮는 일이 없어야 하므로 텍스트만으로 재면 안 된다(#126).
     */
    data class Layout(val lines: List<String>, val box: Bounds)

    /**
     * @param canvas   그릴 수 있는 범위(테두리 두께를 물린 뒤의 화면 안쪽).
     * @param target   강조 대상. 없으면 null이고, 그때는 화면 아래에 붙인다. 언제나 절대 회피다.
     * @param avoid    대상 밖의 회피 영역.
     * @param controlHeight 안내창 안에 비워 둘 컨트롤 행의 높이(칩이 앉는 자리, #126). 0이면 없다 —
     *   기본값이 0이라 칩을 모르는 호출자와 기존 테스트는 산술이 그대로다.
     * @param centered 화면 세로 중앙에 놓는다(GD-4 종료 확인, #193). **[target]·[avoid]를 보지
     *   않는다** — 스크림 위 모달이라 가릴 대상이 없고, 자리를 흥정할 상대도 없다.
     * @return 그릴 것이 없으면 null.
     */
    fun compute(
        instruction: String,
        canvas: Bounds,
        target: Bounds?,
        margin: Float,
        marginVertical: Float,
        padding: Float,
        paddingVertical: Float,
        lineHeight: Float,
        measure: Measure,
        avoid: List<Avoid> = emptyList(),
        controlHeight: Float = 0f,
        centered: Boolean = false,
    ): Layout? {
        if (instruction.isEmpty()) return null

        val left = canvas.left + margin
        val right = canvas.right - margin
        // 줄바꿈 기준은 **상자 안쪽 폭**이다. 상자 폭 그대로 재면 글자가 상자 밖으로 삐져나온다.
        // **가로 여백과 세로 여백이 다르다**(v2: 좌우 44 / 위아래 18) — 좌우는 로딩 점과
        // 종료 X가 앉는 자리라 넓다. 글자 폭은 좌우를, 창 높이는 위아래를 쓴다.
        val textWidth = (right - left - padding * 2).coerceAtLeast(1f)
        val wrapped = wrapLines(instruction, textWidth, measure)

        // 1차: 절반 상한. 아무것도 안 덮으면 그대로 — 글줄을 최대한 살린다.
        // 상한이 없으면 상자가 화면보다 커져 자리 후보가 0개가 되고 회피 등급 전체가
        // 무효가 된다 — 2.0배 글자 + 4문장에서 키보드·대상까지 전부 덮는 실측(2026-08-12).
        // 컨트롤 행도 상자의 일부라 절반 예산에서 먼저 뺀다. 안 빼면 칩 높이만큼 상한을 넘는다.
        val primaryLines =
            (((canvas.bottom - canvas.top) * MAX_HEIGHT_RATIO - paddingVertical * 2 - controlHeight) / lineHeight)
                .toInt().coerceAtLeast(1)
        val primary = attempt(primaryLines, wrapped, textWidth, left, right,
            canvas, target, avoid, margin, marginVertical, padding, paddingVertical, lineHeight, measure, controlHeight, centered)
        // 중앙 배치는 자리를 흥정하지 않는다 — 회피를 안 보므로 축소 폴백이 할 일도 없다.
        if (centered) return primary.layout
        if (primary.hardCoverage == 0f && primary.softCoverage == 0f) return primary.layout

        // 2차: 절반 상자가 무언가를 덮으면 3줄로 줄여 한 번 재시도한다. hard뿐 아니라
        // soft 침범에도 줄인다 — 무관한 키보드조차 최소한으로만 덮기 위해서다
        // (PR #118 리뷰 대응, 2026-08-12 결정).
        // 줄 수 고정인 이유: 비율 상한은 글자가 작아지면 줄 수가 늘어 상자가 도로 커진다 —
        // 이 폴백이 발동하는 화면이 곧 빡빡한 화면이라, 글자 크기와 무관한 최소 덩어리가
        // 들어갈 확률이 높다. 값은 실측이 쌓이기 전까지의 임시값이다(2026-08-12 결정).
        //
        // **1차보다 커지면 안 된다.** 작은 화면이나 아주 큰 글자에서는 절반이 3줄보다 적을 수
        // 있는데, 그대로 3줄을 쓰면 축소 폴백이 상자를 되레 키워 절반 상한을 깬다.
        val compactLines = COMPACT_MAX_LINES.coerceAtMost(primaryLines)
        if (compactLines == primaryLines) return primary.layout
        val compact = attempt(compactLines, wrapped, textWidth, left, right,
            canvas, target, avoid, margin, marginVertical, padding, paddingVertical, lineHeight, measure, controlHeight, centered)
        // (hard, soft) 사전식으로 엄격히 개선될 때만 채택한다.
        // 동률이면 큰 상자를 유지한다 — 글줄을 공짜로 버리지 않는다.
        val improves = compact.hardCoverage < primary.hardCoverage ||
            (compact.hardCoverage == primary.hardCoverage &&
                compact.softCoverage < primary.softCoverage)
        return if (improves) compact.layout else primary.layout
    }

    private data class Attempt(
        val layout: Layout,
        val hardCoverage: Float,
        val softCoverage: Float,
    )

    /** [maxLines]로 줄을 자르고 자리를 고른 뒤, 그 자리의 회피 침범 비율을 함께 돌려준다. */
    private fun attempt(
        maxLines: Int,
        wrapped: List<String>,
        textWidth: Float,
        left: Float,
        right: Float,
        canvas: Bounds,
        target: Bounds?,
        avoid: List<Avoid>,
        margin: Float,
        marginVertical: Float,
        padding: Float,
        paddingVertical: Float,
        lineHeight: Float,
        measure: Measure,
        controlHeight: Float,
        centered: Boolean,
    ): Attempt {
        // 잘림 표현은 W8에서 재론.
        val lines = truncate(wrapped, maxLines, textWidth, measure)
        val height = lines.size * lineHeight + paddingVertical * 2 + controlHeight

        val top = if (centered) {
            centeredTop(height, canvas)
        } else {
            topFor(height, left, right, canvas, target, avoid, margin, marginVertical)
        }
        val box = Bounds(left, top, right, top + height)
        val hard = avoid.filter { it.hard }.map { it.bounds } + listOfNotNull(target)
        val soft = avoid.filterNot { it.hard }.map { it.bounds }
        return Attempt(Layout(lines, box), coverage(box, hard), coverage(box, soft))
    }

    /** 상한을 넘는 줄을 버리고 마지막 줄 끝에 말줄임을 단다. 말줄임도 폭 안에 들어가야 한다. */
    private fun truncate(
        lines: List<String>,
        maxLines: Int,
        maxWidth: Float,
        measure: Measure,
    ): List<String> {
        if (lines.size <= maxLines) return lines
        val kept = lines.take(maxLines).toMutableList()
        var last = kept.last()
        while (last.isNotEmpty()) {
            val candidate = last + ELLIPSIS
            if (measure.fits(candidate, maxWidth) >= candidate.length) break
            last = last.dropLast(1).trimEnd()
        }
        kept[kept.size - 1] = last + ELLIPSIS
        return kept
    }

    /**
     * 들어갈 수 있는 자리 중 **절대 회피를 안 건드리는 자리**를 고르고, 그중 최선 노력 영역을
     * 가장 덜 가리는 것을 쓴다. 전부 가리면 덜 가리는 쪽이다.
     *
     * **넓이가 아니라 비율로 잰다.** 넓이로 재면 작은 것이 언제나 먼저 희생된다 — 입력칸을
     * 통째로 덮는 자리가 키보드를 살짝 물는 자리보다 "싸게" 나온다. 가려진 입력칸은 친 글자를
     * 확인할 수 없고, 가려진 키는 누를 수 없다. 어느 쪽도 넓이에 비례해 나빠지지 않는다.
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
        marginVertical: Float,
    ): Float {
        val candidates = candidateTops(height, canvas, target, margin, marginVertical)
        if (candidates.isEmpty()) return bottomAnchored(height, canvas, marginVertical)

        // 대상은 언제나 절대 회피다 — 무엇을 누를지 안 보이면 안내가 성립하지 않는다.
        val hard = avoid.filter { it.hard }.map { it.bounds } + listOfNotNull(target)
        val soft = avoid.filterNot { it.hard }.map { it.bounds }

        fun boxAt(top: Float) = Bounds(left, top, right, top + height)
        return candidates.minWith(
            compareBy({ coverage(boxAt(it), hard) }, { coverage(boxAt(it), soft) }),
        )
    }

    /** 상자가 화면 안에 온전히 들어가는 자리만 남긴다. */
    private fun candidateTops(
        height: Float,
        canvas: Bounds,
        target: Bounds?,
        margin: Float,
        marginVertical: Float,
    ): List<Float> {
        val tops = mutableListOf<Float>()
        // 대상과의 간격은 **좌우 여백을 쓴다** — 강조 상자와 안내창 사이의 거리이지
        // 화면 가장자리와의 거리가 아니다.
        if (target != null) {
            tops += target.top - margin - height
            tops += target.bottom + margin
        }
        // 화면 위아래 가장자리와의 거리라 세로 여백을 쓴다.
        tops += canvas.bottom - marginVertical - height
        tops += canvas.top + marginVertical
        return tops.filter { it >= canvas.top && it + height <= canvas.bottom }.distinct()
    }

    /**
     * 회피 영역 중 **가장 심하게 가려지는 것의 비율**(0~1). 하나라도 통째로 가려지면 1에 가깝다.
     * 합이 아니라 최댓값인 이유는, 여러 개를 조금씩 가리는 것보다 하나를 다 가리는 쪽이 나쁘기 때문이다.
     */
    private fun coverage(box: Bounds, regions: List<Bounds>): Float =
        regions.maxOfOrNull { intersectionArea(box, it) / areaOf(it) } ?: 0f

    private fun areaOf(bounds: Bounds): Float =
        ((bounds.right - bounds.left) * (bounds.bottom - bounds.top)).coerceAtLeast(1f)

    private fun intersectionArea(a: Bounds, b: Bounds): Float {
        val width = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val height = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        return if (width <= 0f || height <= 0f) 0f else width * height
    }

    /** 어느 자리도 온전히 들어가지 않을 때. 상자가 화면보다 크면 위쪽을 살린다. */
    private fun bottomAnchored(height: Float, canvas: Bounds, marginVertical: Float): Float =
        (canvas.bottom - marginVertical - height).coerceAtLeast(canvas.top)

    /**
     * 세로 중앙(GD-4). 상자가 화면보다 크면 [bottomAnchored]와 같은 이유로 위쪽을 살린다 —
     * 아래로 넘치면 확인쌍이 화면 밖으로 나가 답할 수 없다.
     *
     * **기준은 [canvas], 즉 화면이 아니라 안내창이 앉을 수 있는 범위다.** 호출자가 시스템 바를
     * 뺀 범위를 넘기므로(#169) 중앙도 그 안이다 — 안내창이 시스템 바에 걸치면 그 밴드의 탭이
     * 죽기 때문에, 화면 중심에 맞추는 것보다 이쪽이 맞다.
     *
     * 2026-08-17 SM-S921N `dumpsys window` 실측: 안전 영역 `[103, 2178]`, 확인창
     * `[74,817-1006,1471]`으로 중심 1144. 화면 중심(1170)보다 26px 위다.
     */
    private fun centeredTop(height: Float, canvas: Bounds): Float =
        (canvas.top + (canvas.bottom - canvas.top - height) / 2f).coerceAtLeast(canvas.top)

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

    // ── 조정 지점 ─────────────────────────────────────────────────────────────
    // 아래 두 값은 실측이 부족한 상태에서 정한 임시값이다(2026-08-12, 검증 기기 한 대·
    // 글자 배율 두 개 기준). 조정은 이 두 줄만 고치면 되고, 근거는 실사용 화면이 쌓인 뒤
    // 다시 잡는다. dp/px가 아니라 비율·개수라 dimens.xml로 빼지 않는다.

    /** 상자가 그리기 영역에서 차지할 수 있는 최대 비율. [truncate]가 이 상한을 강제한다. */
    private const val MAX_HEIGHT_RATIO = 0.5f

    /** 절반 상자로 절대 회피를 못 피할 때 떨어지는 축소 줄 수. */
    private const val COMPACT_MAX_LINES = 3
    // ─────────────────────────────────────────────────────────────────────────

    private const val ELLIPSIS = "…"
}
