package com.davi.helper

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat

/**
 * 강조할 영역에 테두리를 하나 그리고, 서버가 준 안내문을 상자에 담아 함께 보여주는 뷰.
 *
 * **줄바꿈·자리 계산은 [GuideBoxLayout]이 한다.** 이 뷰는 결과를 그리기만 한다(#38 1·2번).
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

    /** 안내창이 가리지 말아야 할 영역(키보드·다이얼로그). 창 목록은 서비스만 볼 수 있어 받아 온다. */
    private var avoid: List<GuideBoxLayout.Avoid> = emptyList()

    /** 이 뷰가 화면에서 실제로 어디에 놓였는지. 좌표 보정에 쓴다. */
    private val locationOnScreen = IntArray(2)

    /** 계산해 둔 안내창. 안내가 바뀌거나 창이 옮겨질 때만 다시 만든다. */
    private var layout: GuideBoxLayout.Layout? = null
    private var layoutOriginX = Int.MIN_VALUE
    private var layoutOriginY = Int.MIN_VALUE

    // 색·크기 전부 디자인 토큰(colors.xml·dimens.xml)에서 온다 — 하드코딩·px 계산 금지(#100).
    // 검증 기기(3x 밀도)에서 기존 raw px(12/24)와 dp 토큰(4dp/8dp)이 같은 픽셀값이라 시인성은 보존된다.
    private val highlightStroke = resources.getDimension(R.dimen.davi_overlay_highlight_stroke)
    private val highlightCorner = resources.getDimension(R.dimen.davi_radius_sm)
    private val boxCorner = resources.getDimension(R.dimen.davi_radius_md)

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = highlightStroke
        color = ContextCompat.getColor(context, R.color.davi_highlight)
    }

    // 안내창(GD 명세): 흰 배경 + 진한 글자 + 테두리 — 배경 불문 가독 전략(#111 확정 디자인).
    private val instructionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.davi_overlay_on_box)
        textSize = resources.getDimension(R.dimen.text_overlay_instruction)
        // Canvas 직접 그리기라 테마 fontFamily가 닿지 않는다 — 서체를 손으로 물린다(C1).
        typeface = ResourcesCompat.getFont(context, R.font.pretendard)
    }
    private val instructionBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.davi_overlay_box)
    }
    private val instructionBoxBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.getDimension(R.dimen.davi_button_stroke)
        color = ContextCompat.getColor(context, R.color.davi_border)
    }
    private val instructionPadding = resources.getDimension(R.dimen.overlay_instruction_padding)
    private val instructionMargin = resources.getDimension(R.dimen.overlay_instruction_margin)

    /** 강조할 영역(없으면 null)과 안내문. 서버가 화면에 없는 대상을 골라도 안내문은 보여준다. */
    fun setGuide(
        bounds: Rect?,
        instruction: String,
        avoid: List<GuideBoxLayout.Avoid> = emptyList(),
    ) {
        hasTarget = bounds != null
        if (bounds != null) target.set(bounds)
        this.instruction = instruction.trim()
        this.avoid = avoid
        relayout()
        invalidate()
    }

    fun clearTarget() {
        hasTarget = false
        instruction = ""
        avoid = emptyList()
        layout = null
        invalidate()
    }

    /** 창 크기가 잡히기 전에 온 안내는 자리를 못 잡는다. 크기가 생기면 그때 계산한다. */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        relayout()
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
        // 창이 옮겨졌으면 자리를 다시 잡는다. 평소에는 좌표 두 개를 비교할 뿐,
        // 매 프레임 줄바꿈을 다시 접지 않는다(#38 2번).
        if (locationOnScreen[0] != layoutOriginX || locationOnScreen[1] != layoutOriginY) relayout()
        canvas.translate(-locationOnScreen[0].toFloat(), -locationOnScreen[1].toFloat())

        if (hasTarget) {
            val bounds = drawableBounds()
            // 테두리가 대상 가장자리를 덮지 않도록 선 두께의 절반만큼 바깥으로 물린다.
            // 단 화면 밖으로 나가면 그만큼 잘리므로, 그릴 수 있는 범위 안으로 당긴다
            // (실측: 네비게이션바 버튼처럼 bounds 아래가 화면 높이와 같으면 아랫변이 잘렸다).
            val half = highlightStroke / 2f
            canvas.drawRoundRect(
                (target.left - half).coerceAtLeast(bounds.left),
                (target.top - half).coerceAtLeast(bounds.top),
                (target.right + half).coerceAtMost(bounds.right),
                (target.bottom + half).coerceAtMost(bounds.bottom),
                highlightCorner,
                highlightCorner,
                strokePaint,
            )
        }
        drawInstruction(canvas)
    }

    private fun drawInstruction(canvas: Canvas) {
        val current = layout ?: return

        val box = RectF(current.box.left, current.box.top, current.box.right, current.box.bottom)
        canvas.drawRoundRect(box, boxCorner, boxCorner, instructionBoxPaint)
        canvas.drawRoundRect(box, boxCorner, boxCorner, instructionBoxBorderPaint)

        val metrics = instructionPaint.fontMetrics
        var baseline = box.top + instructionPadding - metrics.top
        current.lines.forEach { line ->
            canvas.drawText(line, box.left + instructionPadding, baseline, instructionPaint)
            baseline += metrics.bottom - metrics.top
        }
    }

    private fun relayout() {
        getLocationOnScreen(locationOnScreen)
        layoutOriginX = locationOnScreen[0]
        layoutOriginY = locationOnScreen[1]

        // 크기가 없으면 폭을 모르니 접을 수 없다. onSizeChanged가 다시 부른다.
        layout = if (width == 0 || height == 0) {
            null
        } else {
            val metrics = instructionPaint.fontMetrics
            GuideBoxLayout.compute(
                instruction = instruction,
                canvas = drawableBounds(),
                target = if (hasTarget) target.toLayoutBounds() else null,
                margin = instructionMargin,
                padding = instructionPadding,
                lineHeight = metrics.bottom - metrics.top,
                measure = { text, maxWidth ->
                    instructionPaint.breakText(text, true, maxWidth, null)
                },
                avoid = avoid,
            )
        }
    }

    /** 선 두께를 물린 뒤 실제로 그릴 수 있는 화면 범위. 화면 좌표계다. */
    private fun drawableBounds(): GuideBoxLayout.Bounds {
        val half = highlightStroke / 2f
        return GuideBoxLayout.Bounds(
            left = locationOnScreen[0] + half,
            top = locationOnScreen[1] + half,
            right = locationOnScreen[0] + width - half,
            bottom = locationOnScreen[1] + height - half,
        )
    }

    private fun RectF.toLayoutBounds() = GuideBoxLayout.Bounds(left, top, right, bottom)
}
