package com.davi.helper

import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator
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
    private val paddingVertical = GuideBoxStyle.paddingVertical(context)
    private val corner = resources.getDimension(R.dimen.davi_overlay_window_radius)

    /**
     * 채움은 **세로 그라디언트**다(v2, #178) — 위 `#FDF6F8` 90% → 아래 `#FADCE6` 80%.
     * 구 버전은 흰 단색이었다. 셰이더는 크기를 알아야 만들 수 있어 [onSizeChanged]에서 건다.
     */
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillTop = ContextCompat.getColor(context, R.color.davi_overlay_window_top)
    private val fillBottom = ContextCompat.getColor(context, R.color.davi_overlay_window_bottom)

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.getDimension(R.dimen.davi_overlay_window_stroke)
        color = ContextCompat.getColor(context, R.color.davi_overlay_window_border)
    }

    /**
     * 창 아래 그림자(시안 DROP `#330D17` 30% y8 blur22).
     *
     * **소프트웨어 레이어가 필요하다** — `setShadowLayer`가 하드웨어 가속 캔버스에서 무시된다
     * (`CardGlowView`와 같은 사정). 안내창은 문구가 바뀔 때만 다시 그리므로 비용이 프레임마다
     * 들지 않는다.
     */
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.davi_overlay_window_shadow)
    }

    /** 윗변 안쪽 흰 띠(시안 INNER `#FFFFFF` 95% y1 blur1)의 근사. */
    private val innerHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.getDimension(R.dimen.davi_overlay_window_stroke)
        color = ContextCompat.getColor(context, R.color.davi_overlay_window_highlight)
    }

    private val shadowOffsetY = resources.getDimension(R.dimen.davi_overlay_window_shadow_y)
    private val boxRect = RectF()

    init {
        // 안내창 영역의 터치를 여기서 끝낸다. 위 주석 참조 — 빼면 조용히 깨진다.
        isClickable = true
        // **clickable인데 접근성에서는 뺀다** — 모순처럼 보이지만 둘은 다른 축이다.
        // 이 뷰에는 라벨도 실제 click 동작도 없어서, 트리에 남으면 TalkBack이 "라벨 없는 버튼"으로
        // 읽고 사용자가 아무 일도 일어나지 않는 영역에 포커스한다. 터치 처리에는 영향이 없다.
        // 누를 수 있는 것은 형제인 칩뿐이고, 칩은 라벨이 있어 그대로 노출된다(PR #133 리뷰).
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        // setShadowLayer가 하드웨어 가속에서 무시된다(위 shadowPaint 주석).
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (h <= 0) return
        boxPaint.shader = android.graphics.LinearGradient(
            0f, 0f, 0f, h.toFloat(), fillTop, fillBottom, android.graphics.Shader.TileMode.CLAMP,
        )
        // 그림자는 창 밖으로 못 번진다(창이 상자 크기다) — 아래로 민 만큼만 보인다.
        shadowPaint.setShadowLayer(shadowOffsetY, 0f, shadowOffsetY / 2f, shadowPaint.color)
    }

    /**
     * 이미 접힌 줄들. [GuideBoxLayout]이 정한 그대로 그린다 — 여기서 다시 접지 않는다.
     *
     * **문구가 실제로 바뀔 때만 떠오른다**(#147). 서비스는 폴링마다 상태를 다시 보내고 화면이
     * 안 바뀌면 같은 안내가 계속 도착하므로, 호출마다 등장을 다시 틀면 안내창이 끝없이 깜빡인다
     * (`setReading`을 멱등하게 짠 것과 같은 이유). 위의 조기 반환이 그 가드를 겸한다.
     */
    fun setLines(next: List<String>) {
        if (lines == next) return
        val hadContent = lines.isNotEmpty()
        lines = next
        invalidate()
        // 이미 문구가 있던 상태에서 내용만 바뀌는 것은 "새로 나타남"이 아니다 — 그때 투명도를
        // 되돌리면 읽던 사람 눈앞에서 안내창이 한 번 사라졌다 온다.
        if (!hadContent && next.isNotEmpty()) fadeIn()
    }

    /**
     * 안내창이 떠오르며 나타난다. **투명도만 바꾼다 — 자리·크기는 건드리지 않는다**(#147).
     *
     * 창 자체가 안내창 크기로 잡혀 있어([ControlOverlay]) 뷰를 움직이면 창 밖으로 잘리고,
     * 창을 움직이면 그 영역의 터치도 함께 움직여 X 칩을 겨냥하다 빗맞힌다. 무엇보다 창을
     * 건드릴 때마다 `TYPE_WINDOWS_CHANGED`가 떠서 서비스의 샘플 예약에 걸리므로
     * ([ControlOverlay]의 `appliedX` 주석), 프레임마다 창을 옮기면 안내 루프 자체를 흔든다.
     */
    private fun fadeIn() {
        if (!ValueAnimator.areAnimatorsEnabled()) {
            alpha = 1f
            return
        }
        animate().cancel()
        alpha = 0f
        // 감속 곡선 — 앞에서 빠르게 올라가 글자가 먼저 읽히고, 끝에서 부드럽게 잦아든다.
        // 선형이면 같은 시간이라도 "그냥 켜졌다"에 가깝게 느껴진다.
        animate()
            .alpha(1f)
            .setDuration(FADE_IN_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (lines.isEmpty()) return

        // 채움은 창 전체, 테두리는 선 두께의 절반만큼 안으로. 창이 상자에 딱 맞게 잡혀 있어
        // 테두리를 가장자리에 걸치면 바깥 절반이 창 밖으로 잘린다.
        val half = borderPaint.strokeWidth / 2f
        boxRect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(boxRect, corner, corner, boxPaint)
        boxRect.set(half, half, width - half, height - half)
        canvas.drawRoundRect(boxRect, corner, corner, borderPaint)
        // 윗변 안쪽 흰 띠 — 아래로 반 두께 내려 윗변에만 걸치게 한다.
        boxRect.set(half, half + half, width - half, height - half)
        canvas.drawRoundRect(boxRect, corner, corner, innerHighlightPaint)

        // **가운데 정렬이다**(v2, #178) — textAlign이 CENTER라 x는 창의 중앙이다.
        // 세로 시작점은 좌우(44)가 아니라 위아래 여백(18)을 쓴다.
        val metrics = textPaint.fontMetrics
        var baseline = paddingVertical - metrics.top
        val centerX = width / 2f
        lines.forEach { line ->
            canvas.drawText(line, centerX, baseline, textPaint)
            baseline += metrics.bottom - metrics.top
        }
    }

    private companion object {
        // ReadingBorder의 조정 지점 규약을 따른다 — dp가 아니라 시간이라 dimens.xml로 빼지 않는다.
        //
        // **180ms는 실기기에서 아예 인지되지 않았다**(#147, 작성자 확인). "빨리 읽히게"를
        // 우선해 짧게 잡았는데, 느껴지지 않으면 애초에 등장을 부드럽게 한 목적이 사라진다.
        // 감속 곡선과 함께 쓰므로 앞부분에서 이미 대부분 올라와 읽기는 늦어지지 않는다.
        const val FADE_IN_MS = 420L
    }
}
