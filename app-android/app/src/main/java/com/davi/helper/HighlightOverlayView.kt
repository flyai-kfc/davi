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

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = STROKE_WIDTH_PX
        color = STROKE_COLOR
    }

    fun setTarget(bounds: Rect) {
        target.set(bounds)
        hasTarget = true
        invalidate()
    }

    fun clearTarget() {
        hasTarget = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!hasTarget) return
        // 테두리가 대상 가장자리를 덮지 않도록 선 두께의 절반만큼 바깥으로 물린다.
        val half = STROKE_WIDTH_PX / 2f
        canvas.drawRoundRect(
            target.left - half,
            target.top - half,
            target.right + half,
            target.bottom + half,
            CORNER_RADIUS_PX,
            CORNER_RADIUS_PX,
            strokePaint,
        )
    }

    private companion object {
        /** 시니어가 알아볼 수 있게 두껍게. 값은 실사용하며 다시 정한다. */
        const val STROKE_WIDTH_PX = 12f
        const val CORNER_RADIUS_PX = 24f
        val STROKE_COLOR = Color.parseColor("#FF3D00")
    }
}
