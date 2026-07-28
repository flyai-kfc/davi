package com.davi.helper

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View

/**
 * 강조할 영역에 테두리를 하나 그리고, 서버가 준 안내문을 상자에 담아 함께 보여주는 뷰.
 *
 * 강조 리플·방향 스와이프 애니메이션은 아직 없다(HANDOFF §4의 `HighlightOverlayView` /
 * `SwipeGuideView` 참고). 안내문이 키보드를 가리는 문제(§3-13)도 다음 작업이다.
 *
 * 좌표는 **화면 좌표 그대로** 받는다. 오버레이 윈도우가 `FLAG_LAYOUT_IN_SCREEN`으로 전체 화면을
 * 차지하므로 접근성 `bounds`와 캔버스 좌표계가 일치한다. 이 플래그가 없으면 상태표시줄 높이만큼
 * 어긋난다(HANDOFF §7 "왜 조금씩 아래로 밀리지?"의 정답).
 */
class HighlightOverlayView(context: Context) : View(context) {

    private val target = RectF()
    private var hasTarget = false
    private var instruction = ""

    /** 이 뷰가 화면에서 실제로 어디에 놓였는지. 좌표 보정에 쓴다. */
    private val locationOnScreen = IntArray(2)

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = STROKE_WIDTH_PX
        color = STROKE_COLOR
    }

    // 크기는 res/values/dimens.xml에서 온다. 코드에서 px·밀도를 계산하지 않는다(app-android/CLAUDE.md).
    private val instructionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = INSTRUCTION_TEXT_COLOR
        textSize = resources.getDimension(R.dimen.text_overlay_instruction)
    }
    private val instructionBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = INSTRUCTION_BOX_COLOR
    }
    private val instructionPadding = resources.getDimension(R.dimen.overlay_instruction_padding)
    private val instructionMargin = resources.getDimension(R.dimen.overlay_instruction_margin)

    /** 강조할 영역(없으면 null)과 안내문. 서버가 화면에 없는 대상을 골라도 안내문은 보여준다. */
    fun setGuide(bounds: Rect?, instruction: String) {
        hasTarget = bounds != null
        if (bounds != null) target.set(bounds)
        this.instruction = instruction.trim()
        invalidate()
    }

    fun clearTarget() {
        hasTarget = false
        instruction = ""
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!hasTarget && instruction.isEmpty()) return

        // 화면 좌표 -> 캔버스 좌표 보정.
        //
        // FLAG_LAYOUT_IN_SCREEN을 줘도 뷰가 항상 (0,0)에 놓이지는 않는다. 실측에서 전화 앱은
        // 맞는데 런처에서만 상태표시줄 높이(약 96px)만큼 아래로 밀렸다 — 창마다 인셋이 다르게
        // 적용된다. 그래서 "어느 창이든 (0,0)일 것"이라 가정하지 않고, 뷰가 실제로 놓인 위치를
        // 물어보고 그만큼 되돌린다. 뷰가 (0,0)이면 이 보정은 아무 일도 하지 않는다.
        getLocationOnScreen(locationOnScreen)
        canvas.translate(-locationOnScreen[0].toFloat(), -locationOnScreen[1].toFloat())

        // 테두리가 대상 가장자리를 덮지 않도록 선 두께의 절반만큼 바깥으로 물린다.
        // 단 화면 밖으로 나가면 그만큼 잘리므로, 그릴 수 있는 범위 안으로 당긴다
        // (실측: 네비게이션바 버튼처럼 bounds 아래가 화면 높이와 같으면 아랫변이 잘렸다).
        val half = STROKE_WIDTH_PX / 2f
        val minX = locationOnScreen[0] + half
        val minY = locationOnScreen[1] + half
        val maxX = locationOnScreen[0] + width - half
        val maxY = locationOnScreen[1] + height - half

        if (hasTarget) {
            canvas.drawRoundRect(
                (target.left - half).coerceAtLeast(minX),
                (target.top - half).coerceAtLeast(minY),
                (target.right + half).coerceAtMost(maxX),
                (target.bottom + half).coerceAtMost(maxY),
                CORNER_RADIUS_PX,
                CORNER_RADIUS_PX,
                strokePaint,
            )
        }
        drawInstruction(canvas, minX, minY, maxX, maxY)
    }

    /**
     * 안내문 상자. 대상을 가리지 않도록 위 → 아래 순으로 들어가는 자리를 고르고,
     * 대상이 없거나 위아래 모두 안 들어가면 화면 아래에 붙인다.
     */
    private fun drawInstruction(canvas: Canvas, minX: Float, minY: Float, maxX: Float, maxY: Float) {
        if (instruction.isEmpty()) return

        val boxLeft = minX + instructionMargin
        val boxRight = maxX - instructionMargin
        // 줄바꿈 기준은 **상자 안쪽 폭**이다. 상자 폭 그대로 재면 글자가 상자 밖으로 삐져나온다.
        val textWidth = (boxRight - boxLeft - instructionPadding * 2).coerceAtLeast(1f)
        val lines = wrapLines(instruction, textWidth)
        val lineHeight = instructionPaint.fontMetrics.run { bottom - top }
        val boxHeight = lines.size * lineHeight + instructionPadding * 2

        val above = target.top - instructionMargin - boxHeight
        val below = target.bottom + instructionMargin
        val boxTop = when {
            hasTarget && above >= minY -> above
            hasTarget && below + boxHeight <= maxY -> below
            else -> (maxY - instructionMargin - boxHeight).coerceAtLeast(minY)
        }

        val box = RectF(boxLeft, boxTop, boxRight, boxTop + boxHeight)
        canvas.drawRoundRect(box, CORNER_RADIUS_PX, CORNER_RADIUS_PX, instructionBoxPaint)

        var baseline = box.top + instructionPadding - instructionPaint.fontMetrics.top
        lines.forEach { line ->
            canvas.drawText(line, box.left + instructionPadding, baseline, instructionPaint)
            baseline += lineHeight
        }
    }

    /** 안내문을 상자 안쪽 폭에 맞춰 자른다. 가능하면 띄어쓰기에서 끊는다. */
    /**
     * 명시적 개행을 먼저 끊고, 각 문단을 폭에 맞춰 접는다.
     *
     * `breakText`는 `\n`을 보통 글자로 세기 때문에 이 분리가 없으면 개행이 통째로 삼켜져
     * 두 문장이 "…주세요.화면이"처럼 붙는다(실기기에서 확인).
     */
    private fun wrapLines(text: String, maxWidth: Float): List<String> =
        text.split('\n').flatMap { paragraph ->
            if (paragraph.isBlank()) listOf("") else wrapParagraph(paragraph, maxWidth)
        }

    private fun wrapParagraph(text: String, maxWidth: Float): List<String> {
        val lines = mutableListOf<String>()
        var remaining = text
        while (remaining.isNotEmpty()) {
            val fits = instructionPaint.breakText(remaining, true, maxWidth, null)
            if (fits >= remaining.length) {
                lines += remaining
                break
            }
            // 한 글자도 안 들어가는 폭이면 fits가 0이라 남은 글자가 줄지 않는다. onDraw 안에서
            // 도는 루프라 그대로 두면 화면이 멈춘다. 최소 한 글자는 잘라낸다.
            val hardBreak = fits.coerceAtLeast(1)
            val spaceAt = remaining.lastIndexOf(' ', hardBreak - 1)
            val breakAt = if (spaceAt > 0) spaceAt else hardBreak
            lines += remaining.substring(0, breakAt).trimEnd()
            remaining = remaining.substring(breakAt).trimStart()
        }
        return lines
    }

    private companion object {
        /** 시니어가 알아볼 수 있게 두껍게. 값은 실사용하며 다시 정한다. */
        const val STROKE_WIDTH_PX = 12f
        const val CORNER_RADIUS_PX = 24f
        val STROKE_COLOR = Color.parseColor("#FF3D00")

        /** 어떤 앱 위에 떠도 읽히도록 어두운 상자 + 흰 글자. 대비는 실사용하며 다시 정한다. */
        val INSTRUCTION_TEXT_COLOR = Color.WHITE
        val INSTRUCTION_BOX_COLOR = Color.argb(235, 30, 30, 30)
    }
}
