package com.davi.helper

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
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
         * @param box **화면 좌표**의 안내창 사각형. 그릴 안내가 없으면 null.
         *
         * 로컬 좌표가 아니라 화면 좌표인 이유는 실측이다(#139). 두 창의 flags를 맞춰도 **부모
         * 프레임까지 같아지지는 않는다** — `dumpsys window`에서 MATCH_PARENT인 표시 창은 부모가
         * `[0,103]`(상태표시줄 인셋 반영)인데 크기가 지정된 조작 창은 `[0,0]`이었다. 로컬 좌표를
         * 넘기면 조작 창이 그 차이만큼(103px) 위로 밀린다.
         *
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

    /**
     * 강조 사각형의 바깥 글로우(#147). 안쪽 선([strokePaint])은 불투명을 유지하고 이것만
     * 숨 쉰다 — 강조를 투명도로 흔들면 읽는 중 테두리와의 층 구분이 무너진다([HighlightMotion]).
     * 프레임마다 새로 만들지 않는다.
     */
    private val highlightGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = ContextCompat.getColor(context, R.color.davi_highlight)
    }
    private val highlightGlowSpread = resources.getDimension(R.dimen.davi_overlay_highlight_glow)

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
    }
    private val readingCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val readingRect = RectF()

    private val readingColor = ContextCompat.getColor(context, R.color.davi_overlay_reading)
    private val confirmColor = ContextCompat.getColor(context, R.color.davi_overlay_confirm)
    private val scrimColor = ContextCompat.getColor(context, R.color.davi_overlay_scrim)

    /** 서비스가 요청한 상태. 실제 표시는 onset 지연 뒤라 [readingVisible]과 다를 수 있다. */
    private var reading = false
    private var readingVisible = false
    private var readingPhase = ReadingBorder.STATIC_PHASE

    /**
     * 확인 대기 중이면 그 종류. 읽는 중과 **동시에 켜지지 않는다** — [ReadingBorder.isReading] 참고.
     *
     * 종류마다 크롬이 다르고 **배타적이다**(v2 시안) — 완료 확인(GD-3)은 성공 tone 테두리,
     * 종료 확인(GD-4)은 전면 스크림이다. 한 화면에 둘을 겹치지 않는다.
     */
    private var confirmKind: GuideDisplayState.ConfirmKind? = null

    private val confirmFrame get() = confirmKind == GuideDisplayState.ConfirmKind.DONE

    private val readingOnset = Runnable {
        readingVisible = true
        syncBreathing()
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
            readingVisible = false
            syncBreathing()
            invalidate()
        }
    }

    /**
     * 확인 화면의 크롬을 켜고 끈다(없으면 null).
     *
     * 완료 확인(GD-3)은 화면 가장자리 테두리다 — 읽는 중 테두리와 **같은 자리·같은 두께**이고
     * 색만 다르다. 서버 판단이 아니라 사용자 답을 기다리는 중임을 화면 전체로 알린다.
     * 종료 확인(GD-4)은 전면 스크림이다 — 테두리는 켜지 않는다.
     *
     * **브리딩하지 않는다.** 숨 쉬는 것은 "지금 처리 중"의 표현이고 확인은 멈춰 선 상태다.
     * 그래서 [syncBreathing]의 조건에도 넣지 않는다 — 확인 중에 강조도 읽는 중도 없으면
     * 애니메이터는 멈춰 있는 것이 맞다. 지연(onset)도 없다: 답할 자리라 곧바로 보여야 한다.
     */
    fun setConfirmChrome(kind: GuideDisplayState.ConfirmKind?) {
        if (kind == confirmKind) return
        confirmKind = kind
        invalidate()
    }

    /**
     * 브리딩 애니메이터를 켜고 끈다. **읽는 중 테두리와 강조 사각형이 이 하나를 공유한다**(#147).
     *
     * 구동부를 하나로 두는 이유는 둘이 동시에 뜰 수 있어서다 — 각자 애니메이터를 가지면 같은
     * 뷰가 한 프레임에 두 번 무효화되고, 주기가 어긋나면 두 테두리가 따로 논다. 공유하면 위상이
     * 자동으로 맞는다.
     *
     * 둘 다 없으면 멈춘다. 오버레이는 세션 내내 다른 앱 위에 떠 있으므로, 그릴 것이 없는데
     * 도는 것은 그대로 배터리다.
     *
     * 애니메이터가 시스템 설정(개발자 옵션·절전)으로 꺼진 기기에서는 [ValueAnimator]가 즉시
     * 끝나 표시가 사라진다. 그때는 브리딩 없이 가장 진한 한 장을 그린다.
     */
    private fun syncBreathing() {
        val needed = readingVisible || hasTarget
        if (!needed) {
            breathing.cancel()
            return
        }
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
        // 강조가 생기거나 사라지면 브리딩이 필요한지도 함께 바뀐다(#147).
        syncBreathing()
        relayout()
        invalidate()
    }

    fun clearTarget() {
        hasTarget = false
        instruction = ""
        avoid = emptyList()
        syncBreathing()
        applyLayout(null)
        invalidate()
    }

    /**
     * 화면 가장자리 테두리. 굵고 옅은 글로우 위에 안쪽 선을 얹는다. 어느 것도 대상 영역을
     * 가리키지 않는다 — 화면 전체에 깔리는 상태 표시다.
     *
     * 두 tone이 이 한 자리를 나눠 쓴다 — 읽는 중(숨 쉬듯 밝기가 오간다)과 완료 확인(정지).
     * 둘은 동시에 켜지지 않으므로 확인 쪽을 먼저 본다.
     */
    private fun drawReadingBorder(canvas: Canvas) {
        if (!confirmFrame && !readingVisible) return
        if (width == 0 || height == 0) return
        val phase = if (confirmFrame) ReadingBorder.STATIC_PHASE else readingPhase
        val color = if (confirmFrame) confirmColor else readingColor
        val frame = ReadingBorder.frame(phase, readingStroke, readingGlow)
        // 글로우가 두꺼워져도 화면 밖으로 새지 않게 가장 두꺼운 절반만큼 안으로 물린다.
        val margin = readingInset + (readingStroke + readingGlow) / 2f
        readingRect.set(margin, margin, width - margin, height - margin)
        // 색을 먼저 넣는다 — setColor가 alpha까지 덮으므로 순서가 뒤집히면 투명도가 사라진다.
        readingGlowPaint.color = color
        readingGlowPaint.strokeWidth = frame.glowStroke
        readingGlowPaint.alpha = frame.glowAlpha
        canvas.drawRoundRect(readingRect, readingRadius, readingRadius, readingGlowPaint)
        readingCorePaint.color = color
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
        // 스크림(GD-4)도 테두리와 같은 이유로 translate 앞이다 — 뷰가 차지한 범위를 덮는다.
        // 그 범위가 곧 화면 전체는 아니다: 이 창은 상태표시줄 아래에서 시작해서(`[0,103]` 실측)
        // 상태표시줄은 안 덮인다. 시안은 전면이라 그 차이는 #178에서 본다.
        // **터치는 막지 않는다.** 이 창이 FLAG_NOT_TOUCHABLE이고, 뒤집으면 "대신 조작하지
        // 않는다"(SPEC §2)와 부딪친다. 막을 이유도 없다 — 이 화면은 ○/× 말고는 사라지지
        // 않으므로 아래 앱을 만져도 답할 자리가 남는다.
        if (confirmKind == GuideDisplayState.ConfirmKind.QUIT) canvas.drawColor(scrimColor)
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
            //
            // **글로우가 가장 두꺼워졌을 때를 기준으로 물린다**(#147). 매 프레임 두께에 맞춰
            // 물리면 숨 쉬는 동안 사각형이 커졌다 작아졌다 해서 대상이 흔들려 보인다 —
            // 자리는 고정하고 두께·투명도만 변해야 "숨 쉰다"로 읽힌다.
            val frame = HighlightMotion.frame(readingPhase, highlightStroke, highlightGlowSpread)
            // **안쪽 선의 자리는 후광과 무관하게 잡는다.** 물림 계산에 번짐 거리를 섞었더니
            // 화면 가장자리에 붙은 대상(런처 독의 카메라)에서 사각형이 아이콘 안쪽으로 밀려
            // 찌그러져 보였다(실기기 실측, #147). 선은 대상에 붙어야 하고, 후광은 화면 밖으로
            // 나가면 캔버스가 알아서 자른다 — 그게 자연스럽다.
            val half = frame.coreStroke / 2f
            val left = (target.left - half).coerceAtLeast(bounds.left)
            val top = (target.top - half).coerceAtLeast(bounds.top)
            val right = (target.right + half).coerceAtMost(bounds.right)
            val bottom = (target.bottom + half).coerceAtMost(bounds.bottom)

            // 후광은 겹을 쌓아 번지게 한다 — 굵은 선 하나로는 이중 테두리로 보인다(실기기 실측).
            // 바깥으로 갈수록 빠르게 옅어져 마이크 뒤 글로우와 같은 인상을 준다(#147).
            if (frame.glowBaseAlpha > 0) {
                highlightGlowPaint.strokeWidth =
                    HighlightMotion.ringStrokeFor(frame.glowSpread, highlightStroke)
                for (ring in 0 until HighlightMotion.GLOW_RING_COUNT) {
                    val alpha = HighlightMotion.ringAlphaAt(ring, frame.glowBaseAlpha)
                    if (alpha <= 0) continue
                    val offset = HighlightMotion.ringOffsetAt(ring, frame.glowSpread)
                    highlightGlowPaint.alpha = alpha
                    canvas.drawRoundRect(
                        left - offset,
                        top - offset,
                        right + offset,
                        bottom + offset,
                        highlightCorner + offset,
                        highlightCorner + offset,
                        highlightGlowPaint,
                    )
                }
            }
            strokePaint.strokeWidth = frame.coreStroke
            canvas.drawRoundRect(
                left,
                top,
                right,
                bottom,
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
                    // 강조 클램프와 달리 **시스템 바를 뺀** 범위다(#169) — 안내창이 내비게이션
                    // 바를 덮으면 그 밴드의 탭까지 조작 오버레이가 흡수한다.
                    canvas = boxBounds(),
                    target = if (hasTarget) target.toLayoutBounds() else null,
                    margin = instructionMargin,
                    padding = instructionPadding,
                    lineHeight = metrics.bottom - metrics.top,
                    measure = { text, maxWidth ->
                        instructionPaint.breakText(text, true, maxWidth, null)
                    },
                    avoid = avoid,
                    controlHeight = controlRow,
                    centered = confirmKind == GuideDisplayState.ConfirmKind.QUIT,
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
        // 화면 좌표 그대로 넘긴다 — 창 원점 보정은 이 뷰의 캔버스에만 필요하다(onDraw의 translate).
        // 두 창의 부모 프레임이 다르다는 실측이 있어 로컬 좌표로 바꾸면 조작 창이 밀린다
        // ([GuideBoxListener] 참고). 폭·높이는 올림한다 — 내림하면 마지막 글자가 1px 차이로 잘린다.
        val box = next.box
        listener.onGuideBox(
            Rect(
                floor(box.left).toInt(),
                floor(box.top).toInt(),
                ceil(box.right).toInt(),
                ceil(box.bottom).toInt(),
            ),
            next.lines,
        )
    }

    /**
     * 선 두께를 물린 뒤 실제로 **그릴 수 있는** 화면 범위. 화면 좌표계다.
     *
     * 시스템 바를 뺀 범위가 아니다 — 강조 대상이 내비게이션 바 버튼일 수 있고, 그때 아랫변이
     * 잘리면 안 된다. 안내창이 앉을 범위는 [boxBounds]가 따로 준다.
     */
    private fun drawableBounds(): GuideBoxLayout.Bounds {
        val half = highlightStroke / 2f
        return GuideBoxLayout.Bounds(
            left = locationOnScreen[0] + half,
            top = locationOnScreen[1] + half,
            right = locationOnScreen[0] + width - half,
            bottom = locationOnScreen[1] + height - half,
        )
    }

    /**
     * 안내창이 앉을 수 있는 범위 — [drawableBounds]에서 **시스템 바 인셋을 뺀다**(#169).
     *
     * 안 빼면 `bottomAnchored`가 안내창을 내비게이션 바 안으로 밀어 넣는다. 그 자체는 시각
     * 문제로 보이지만, 안내창 사각형이 곧 **조작 오버레이 창의 터치 영역**이라(#126) 겹친
     * 밴드의 탭을 우리 창이 흡수한다 — 실측에서 내비바 162px 중 상단 88px의 뒤로·홈·최근 앱이
     * 죽었다(2026-08-17 SM-S921N, `dumpsys`의 z-order상 우리 창이 `NavigationBar0` 위).
     * 시니어 대상 앱에서 시스템 버튼 절반이 안 눌리는 자리다.
     *
     * **상수로 박지 않는다.** 하단 인셋이 3버튼 162px / 제스처 51px로 3배 차이라(같은 실측)
     * 런타임에 읽어야 한다. 앱 화면은 이미 같은 처리를 한다(`ConversationActivity`, #78 패턴) —
     * 오버레이만 빠져 있었다.
     *
     * 인셋은 **창이 아니라 디스플레이**에서 읽는다. 이 창은 `FLAG_LAYOUT_NO_LIMITS`라
     * `getRootWindowInsets()`가 `top=0 bottom=0`을 준다(2026-08-17 실측) — 시스템이 "이 창은
     * 인셋에 안 눌린다"고 답하는 것이라, 그걸 믿으면 아무것도 안 빼게 된다.
     * `currentWindowMetrics`는 창 flags와 무관하게 화면 기준 인셋을 준다.
     *
     * 인셋은 화면 기준이고 이 창은 이미 상태표시줄 아래에서 시작할 수 있다. 두 번 빼지 않도록
     * **화면 좌표에서 겹치는 만큼만** 자른다.
     */
    private fun boxBounds(): GuideBoxLayout.Bounds {
        val drawable = drawableBounds()
        val safe = safeBounds() ?: return drawable
        return GuideBoxLayout.Bounds(
            left = maxOf(drawable.left, safe.left),
            top = maxOf(drawable.top, safe.top),
            right = minOf(drawable.right, safe.right),
            bottom = minOf(drawable.bottom, safe.bottom),
        )
    }

    /**
     * 화면 좌표에서 시스템 바를 뺀 범위. 읽을 수 없으면 null이고 그때는 아무것도 빼지 않는다
     * (지금 동작 그대로 — 검증 기기 밖에서 근거 없이 자리를 흔들지 않는다).
     *
     * **네 변을 다 자른다.** 세로만 자르던 첫 구현은 가로 화면에서 그대로 뚫렸다 — 안내 대상
     * 앱은 우리 액티비티와 달리 세로 고정이 아니다(`AndroidManifest`의 `screenOrientation`은
     * 우리 화면에만 걸려 있다). 2026-08-18 SM-S921N 실측: 가로(`ROTATION_90`)에서 내비게이션
     * 바가 **오른쪽 변으로 가고** 안내창이 그 위를 덮어, 같은 좌표의 홈 버튼이 오버레이가
     * 있을 때만 죽었다(없을 때 `LauncherActivity`, 있을 때 브라우저 그대로).
     */
    private fun safeBounds(): GuideBoxLayout.Bounds? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val windowManager = context.getSystemService(WindowManager::class.java) ?: return null
        val metrics = windowManager.currentWindowMetrics
        val insets = WindowInsetsCompat
            .toWindowInsetsCompat(metrics.windowInsets)
            .getInsets(WindowInsetsCompat.Type.systemBars())
        return GuideBoxLayout.Bounds(
            left = (metrics.bounds.left + insets.left).toFloat(),
            top = (metrics.bounds.top + insets.top).toFloat(),
            right = (metrics.bounds.right - insets.right).toFloat(),
            bottom = (metrics.bounds.bottom - insets.bottom).toFloat(),
        )
    }

    private fun RectF.toLayoutBounds() = GuideBoxLayout.Bounds(left, top, right, bottom)
}
