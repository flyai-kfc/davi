package com.davi.helper

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View

/**
 * 강조할 영역에 테두리 하나를 그리는 뷰.
 *
 * 이번 이슈(#6)는 **사각형 하나**만 그린다. 강조 리플·방향 스와이프 애니메이션·안내 문구 배치는
 * 다음 작업이다(HANDOFF §4의 `HighlightOverlayView` / `SwipeGuideView` 참고).
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

    private val instructionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = resources.getDimension(R.dimen.text_overlay_instruction)
    }
    private val instructionBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 30, 30, 30)
    }
    private val instructionPadding = resources.getDimension(R.dimen.overlay_instruction_padding)
    private val instructionMargin = resources.getDimension(R.dimen.overlay_instruction_margin)

    fun setGuide(bounds: Rect?, instruction: String) {
        if (bounds == null) {
            hasTarget = false
        } else {
            target.set(bounds)
            hasTarget = true
        }
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
        if (!hasTarget && instruction.isBlank()) return

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

    /** 한 단계 안내문만 상자에 그린다. 상자는 대상 위·아래 중 화면에 들어가는 쪽을 고른다. */
    private fun drawInstruction(canvas: Canvas, minX: Float, minY: Float, maxX: Float, maxY: Float) {
        if (instruction.isBlank()) return
        val maxTextWidth = (maxX - minX - instructionPadding * 2).coerceAtLeast(1f)
        val lines = wrapLines(instruction, maxTextWidth)
        val lineHeight = instructionPaint.fontMetrics.run { bottom - top }
        val boxHeight = lines.size * lineHeight + instructionPadding * 2
        val preferredTop = if (hasTarget) target.top - instructionMargin - boxHeight else minY + instructionMargin
        val belowTop = if (hasTarget) target.bottom + instructionMargin else preferredTop
        val boxTop = when {
            preferredTop >= minY -> preferredTop
            belowTop + boxHeight <= maxY -> belowTop
            else -> minY + instructionMargin
        }
        val box = RectF(minX + instructionPadding, boxTop, maxX - instructionPadding, boxTop + boxHeight)
        canvas.drawRoundRect(box, CORNER_RADIUS_PX, CORNER_RADIUS_PX, instructionBackgroundPaint)
        var baseline = box.top + instructionPadding - instructionPaint.fontMetrics.top
        lines.forEach { line ->
            canvas.drawText(line, box.left + instructionPadding, baseline, instructionPaint)
            baseline += lineHeight
        }
    }

    private fun wrapLines(text: String, maxWidth: Float): List<String> {
        val lines = mutableListOf<String>()
        var remaining = text
        while (remaining.isNotEmpty()) {
            val count = instructionPaint.breakText(remaining, true, maxWidth, null)
            if (count >= remaining.length) {
                lines += remaining
                break
            }
            val breakAt = remaining.lastIndexOf(' ', count - 1).takeIf { it > 0 } ?: count
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
    }
}
