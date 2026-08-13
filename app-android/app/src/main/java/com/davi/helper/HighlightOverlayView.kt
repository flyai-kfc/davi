package com.davi.helper

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import kotlin.math.ceil
import kotlin.math.floor

/**
 * 표시 오버레이의 루트 뷰. 레이어 둘을 그린다 — **읽는 중 테두리**(화면 가장자리, 브리딩)와
 * **강조 사각형**(대상 영역). 터치는 아래 앱으로 통과한다.
 *
 * **안내창은 여기서 그리지 않는다**(#126). 자리 계산만 하고([GuideBoxLayout]), 결과를
 * [guideBoxListener]로 넘기면 조작 오버레이의 [GuideBoxView]가 그린다. 계산이 여기 남는 이유는
 * 그릴 수 있는 범위·강조 대상·회피 영역을 아는 곳이 이 뷰뿐이라서다.
 *
 * 강조 리플·방향 스와이프 애니메이션은 아직 없다(HANDOFF §4의 `HighlightOverlayView` /
 * `SwipeGuideView` 참고).
 *
 * 좌표는 **화면 좌표 그대로** 받는다. 오버레이 윈도우가 `FLAG_LAYOUT_IN_SCREEN`으로 전체 화면을
 * 차지하므로 접근성 `bounds`와 캔버스 좌표계가 일치한다. 이 플래그가 없으면 상태표시줄 높이만큼
 * 어긋난다(HANDOFF §7 "왜 조금씩 아래로 밀리지?"의 정답).
 */
class HighlightOverlayView(context: Context) : View(context) {

    /** 안내창 자리가 정해질 때마다 받는다. 조작 오버레이가 이 자리로 창을 옮긴다(#126). */
    fun interface GuideBoxListener {
        /**
         * @param box **이 뷰의 로컬 좌표**(창 프레임 기준) 안내창 사각형. 그릴 안내가 없으면 null.
         * @param lines 이미 접힌 줄들. 받는 쪽은 다시 접지 않는다.
         */
        fun onGuideBox(box: Rect?, lines: List<String>)
    }

    var guideBoxListener: GuideBoxListener? = null

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

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = highlightStroke
        color = ContextCompat.getColor(context, R.color.davi_highlight)
    }

    // 그리지는 않지만 줄바꿈을 재려면 같은 글자여야 한다 — 그리는 쪽과 한 곳에서 만든다.
    private val instructionPaint = GuideBoxStyle.textPaint(context)
    private val instructionPadding = GuideBoxStyle.padding(context)
    private val instructionMargin = GuideBoxStyle.margin(context)
    private val controlRow = GuideBoxStyle.controlRow(context)

    // ── 읽는 중 테두리(GD-1) ──────────────────────────────────────────────────
    private val readingInset = resources.getDimension(R.dimen.davi_overlay_reading_inset)
    private val readingStroke = resources.getDimension(R.dimen.davi_overlay_reading_stroke)
    private val readingGlow = resources.getDimension(R.dimen.davi_overlay_reading_glow)
    private val readingRadius = resources.getDimension(R.dimen.davi_overlay_reading_radius)

    // 프레임마다 새로 만들지 않는다 — 애니메이션이 도는 동안 매초 60번 할당된다.
    private val readingGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = ContextCompat.getColor(context, R.color.davi_overlay_reading)
    }
    private val readingCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = ContextCompat.getColor(context, R.color.davi_overlay_reading)
    }
    private val readingRect = RectF()

    /** 서비스가 요청한 상태. 실제 표시는 onset 지연 뒤라 [readingVisible]과 다를 수 있다. */
    private var reading = false
    private var readingVisible = false
    private var readingPhase = ReadingBorder.STATIC_PHASE

    private val readingOnset = Runnable {
        readingVisible = true
        startBreathing()
        invalidate()
    }

    private val breathing = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = ReadingBorder.PERIOD_MS
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            readingPhase = it.animatedValue as Float
            invalidate()
        }
    }
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 읽는 중 표시를 켜고 끈다. **멱등이다** — 화면을 못 읽는 동안 서비스가 폴링마다 false를
     * 다시 보내고, 켜진 상태로도 샘플마다 true가 다시 온다. 가드가 없으면 매번 애니메이터를
     * 취소하거나 onset 지연이 리셋돼 테두리가 영영 뜨지 않는다.
     */
    fun setReading(on: Boolean) {
        if (on == reading) return
        reading = on
        if (on) {
            // 응답이 빠르면 러너블이 아예 실행되지 않아 한 프레임도 그리지 않는다.
            postDelayed(readingOnset, ReadingBorder.ONSET_DELAY_MS)
        } else {
            removeCallbacks(readingOnset)
            breathing.cancel()
            readingVisible = false
            invalidate()
        }
    }

    /**
     * 애니메이터가 시스템 설정(개발자 옵션·절전)으로 꺼진 기기에서는 [ValueAnimator]가 즉시
     * 끝나 표시가 사라진다. 그때는 브리딩 없이 가장 진한 한 장을 그린다.
     */
    private fun startBreathing() {
        if (!ValueAnimator.areAnimatorsEnabled()) {
            readingPhase = ReadingBorder.STATIC_PHASE
            return
        }
        if (!breathing.isStarted) breathing.start()
    }

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
        applyLayout(null)
        invalidate()
    }

    /**
     * 화면 가장자리 테두리. 굵고 옅은 글로우 위에 안쪽 선을 얹어 숨 쉬듯 밝기가 오간다.
     * 어느 것도 대상 영역을 가리키지 않는다 — "지금 읽고 있다"는 배경 표시다.
     */
    private fun drawReadingBorder(canvas: Canvas) {
        if (!readingVisible || width == 0 || height == 0) return
        val frame = ReadingBorder.frame(readingPhase, readingStroke, readingGlow)
        // 글로우가 두꺼워져도 화면 밖으로 새지 않게 가장 두꺼운 절반만큼 안으로 물린다.
        val margin = readingInset + (readingStroke + readingGlow) / 2f
        readingRect.set(margin, margin, width - margin, height - margin)
        readingGlowPaint.strokeWidth = frame.glowStroke
        readingGlowPaint.alpha = frame.glowAlpha
        canvas.drawRoundRect(readingRect, readingRadius, readingRadius, readingGlowPaint)
        readingCorePaint.strokeWidth = frame.coreStroke
        readingCorePaint.alpha = frame.coreAlpha
        canvas.drawRoundRect(readingRect, readingRadius, readingRadius, readingCorePaint)
    }

    /** 창 크기가 잡히기 전에 온 안내는 자리를 못 잡는다. 크기가 생기면 그때 계산한다. */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        relayout()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(readingOnset)
        breathing.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 읽는 중 테두리는 **뷰 로컬 좌표**라 아래 translate 앞에서 그린다. 창이 밀려 놓이는
        // 기기에서도 뷰가 실제로 차지한 사각형을 따라가야 잘리지 않는다.
        drawReadingBorder(canvas)
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
    }

    private fun relayout() {
        getLocationOnScreen(locationOnScreen)
        layoutOriginX = locationOnScreen[0]
        layoutOriginY = locationOnScreen[1]

        // 크기가 없으면 폭을 모르니 접을 수 없다. onSizeChanged가 다시 부른다.
        applyLayout(
            if (width == 0 || height == 0) {
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
                    controlHeight = controlRow,
                )
            },
        )
    }

    /**
     * 계산 결과를 반영하고 알린다. **[layout]에 대입하는 곳은 여기 하나뿐이다** — [clearTarget]은
     * [relayout]을 거치지 않으므로, 통지를 계산 쪽에만 걸면 지울 때 안내창이 화면에 남는다.
     */
    private fun applyLayout(next: GuideBoxLayout.Layout?) {
        layout = next
        val listener = guideBoxListener ?: return
        if (next == null) {
            listener.onGuideBox(null, emptyList())
            return
        }
        // 화면 좌표 -> 이 뷰의 로컬 좌표. 조작 오버레이가 같은 flags를 쓰므로 프레임 원점이 같다.
        // 폭·높이는 올림한다 — 내림하면 마지막 글자가 1px 차이로 잘릴 수 있다.
        val box = next.box
        listener.onGuideBox(
            Rect(
                floor(box.left).toInt() - layoutOriginX,
                floor(box.top).toInt() - layoutOriginY,
                ceil(box.right).toInt() - layoutOriginX,
                ceil(box.bottom).toInt() - layoutOriginY,
            ),
            next.lines,
        )
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
