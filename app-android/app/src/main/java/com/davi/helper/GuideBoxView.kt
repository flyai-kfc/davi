package com.davi.helper

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

/**
 * 안내창 자체 — 상자·테두리·안내문을 그리고 **그 영역의 터치를 삼킨다**(#126).
 *
 * 조작 오버레이 창이 안내창 크기로 잡히므로 이 뷰는 **자기 로컬 좌표**에만 그린다. 어디에 놓을지는
 * [GuideBoxLayout]이 정하고 창이 그리로 옮겨간다.
 *
 * **터치를 삼키는 것이 이 뷰의 두 번째 일이다.** 칩만 터치를 받으면 안내창의 나머지 영역은 그대로
 * 통과해, 칩을 겨냥하다 빗나갔을 때 불투명한 안내창에 가려 보이지도 않는 아래 앱 버튼이 눌린다.
 * `isClickable`을 빼면 창이 이미 터치를 가로챈 뒤라 **아래 앱에도 안 가고 우리도 안 받는** 상태가
 * 되는데, 조용히 먹혀서 실기기에서 눌러 보기 전에는 드러나지 않는다.
 */
class GuideBoxView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var lines: List<String> = emptyList()

    private val textPaint = GuideBoxStyle.textPaint(context)
    private val padding = GuideBoxStyle.padding(context)
    private val corner = resources.getDimension(R.dimen.davi_radius_md)

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.davi_overlay_box)
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.getDimension(R.dimen.davi_button_stroke)
        color = ContextCompat.getColor(context, R.color.davi_border)
    }

    init {
        // 안내창 영역의 터치를 여기서 끝낸다. 위 주석 참조 — 빼면 조용히 깨진다.
        isClickable = true
    }

    /** 이미 접힌 줄들. [GuideBoxLayout]이 정한 그대로 그린다 — 여기서 다시 접지 않는다. */
    fun setLines(next: List<String>) {
        if (lines == next) return
        lines = next
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (lines.isEmpty()) return

        // 채움은 창 전체, 테두리는 선 두께의 절반만큼 안으로. 창이 상자에 딱 맞게 잡혀 있어
        // 테두리를 가장자리에 걸치면 바깥 절반이 창 밖으로 잘린다.
        val half = borderPaint.strokeWidth / 2f
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), corner, corner, boxPaint)
        canvas.drawRoundRect(
            RectF(half, half, width - half, height - half),
            corner,
            corner,
            borderPaint,
        )

        val metrics = textPaint.fontMetrics
        var baseline = padding - metrics.top
        lines.forEach { line ->
            canvas.drawText(line, padding, baseline, textPaint)
            baseline += metrics.bottom - metrics.top
        }
    }
}
