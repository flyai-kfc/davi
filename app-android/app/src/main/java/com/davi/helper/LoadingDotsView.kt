package com.davi.helper

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import androidx.core.content.ContextCompat
import androidx.core.content.res.use

/**
 * 응답을 기다리는 동안 도는 점 3개(#142). 서버가 느릴 때 화면이 멈춘 것으로 보이지 않게 한다.
 *
 * 시안 근거: Figma SSoT SCREENS "메인 v2" 메인 6(승인 대기) — "잠시만요" 문구 **아래**,
 * 다비 카드 안에 놓인다. 문구를 대체하지 않는다.
 *
 * 움직임 계산은 [LoadingDotsMotion]에 있다(JVM 테스트 대상). 이 뷰는 그리기만 한다 —
 * [VoiceWaveView]가 [WaveMotion]에 계산을 맡긴 것과 같은 구조다.
 *
 * **지연 표시는 핸들러가 센다**(#148 — 오버레이 onset과 같은 방식). 응답이 빠르면 점이
 * 깜빡 스쳐 산만하므로 일정 시간 뒤에만 띄우는데, 이 지연을 프레임 경과 시간으로 재면
 * 모션 끄기(프레임 한 장)에서 점이 영영 안 나온다(#144에서 실제로 겪었다). 핸들러는
 * 프레임과 무관하게 발화하므로 그 함정이 구조적으로 없다.
 *
 * 그래서 **모션 끄기에서도 지연이 똑같이 적용된다** — 정지 프레임이 delay 뒤에 나온다.
 * 이전의 "모션 꺼짐이면 지연 건너뛰기"는 프레임이 더 안 온다는 구현 제약을 피하는 우회였지
 * 즉시 표시가 목적이 아니었고, 빠른 응답에 정지 그림이 깜빡 스치는 것도 지연이 막으려는
 * 산만함 그대로다(#148에서 의도한 변화).
 */
class LoadingDotsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : MotionView(context, attrs, defStyleAttr) {

    // 점 색은 시안 실측값이 기존 시맨틱 토큰과 정확히 같았다(#A50014) — 새 색을 만들지 않는다.
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.davi_button_primary)
        style = Paint.Style.FILL
    }

    // 크기는 **뷰 속성에서 읽는다**(기본값은 메인 6 시안 실측치). 리소스를 직접 읽으면
    // 대화 화면과 오버레이가 한 값을 나눠 쓰게 되어 한쪽을 줄이면 다른 쪽도 함께 줄어든다.
    private val sizes = context.obtainStyledAttributes(
        attrs,
        R.styleable.LoadingDotsView,
        defStyleAttr,
        0,
    ).use {
        Triple(
            it.getDimension(
                R.styleable.LoadingDotsView_daviDotSizeMax,
                resources.getDimension(R.dimen.davi_loading_dot_size_max),
            ),
            it.getDimension(
                R.styleable.LoadingDotsView_daviDotSizeMin,
                resources.getDimension(R.dimen.davi_loading_dot_size_min),
            ),
            it.getDimension(
                R.styleable.LoadingDotsView_daviDotSpacing,
                resources.getDimension(R.dimen.davi_loading_dot_spacing),
            ),
        )
    }

    private val dotSizeMax = sizes.first
    private val dotSizeMin = sizes.second
    private val dotSpacing = sizes.third

    private val periodSeconds =
        resources.getInteger(R.integer.davi_motion_dots_period_ms) / MILLIS_PER_SECOND
    private val staggerSeconds =
        resources.getInteger(R.integer.davi_motion_dots_stagger_ms) / MILLIS_PER_SECOND
    private val delayMs = resources.getInteger(R.integer.davi_motion_dots_delay_ms).toLong()

    private var elapsedSeconds = 0f
    private var delayElapsed = false

    /**
     * 점이 처음 보인 순간의 프레임 시각. 핸들러(벽시계)와 프레임 시계는 다른 시계라,
     * delay 상수를 빼는 대신 **드러난 순간의 프레임 시각**을 빼야 첫 프레임이 정확히
     * 시안의 정지 화면이 된다 — 메인 스레드가 밀려 핸들러가 늦게 발화해도 어긋나지 않는다.
     */
    private var shownAtSeconds = 0f

    private val showDots = Runnable {
        delayElapsed = true
        shownAtSeconds = elapsedSeconds
        invalidate()
    }

    override fun onMotionFrame(elapsedSeconds: Float, deltaSeconds: Float) {
        this.elapsedSeconds = elapsedSeconds
    }

    // 지연은 구동 조건 전이마다 처음부터 다시 센다 — 대기에 다시 들어갈 때(GONE→VISIBLE)
    // 점이 지연 없이 곧장 튀어나오지 않게. driver도 같은 전이에서 재시작돼 주기도 0부터다.
    override fun onMotionLifecycleChanged(active: Boolean) {
        if (active) {
            removeCallbacks(showDots)
            delayElapsed = false
            postDelayed(showDots, delayMs)
        } else {
            removeCallbacks(showDots)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // 가장 큰 점 기준으로 자리를 잡는다 — 작아질 때 레이아웃이 흔들리지 않게.
        val width = dotSizeMax * LoadingDotsMotion.DOT_COUNT +
            dotSpacing * (LoadingDotsMotion.DOT_COUNT - 1)
        setMeasuredDimension(
            resolveSize(width.toInt(), widthMeasureSpec),
            resolveSize(dotSizeMax.toInt(), heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 지연 구간에는 아무것도 그리지 않는다 — 이때 화면에는 "잠시만요" 문구만 있다.
        if (!delayElapsed) return

        val animationSeconds = LoadingDotsMotion.animationSecondsAt(elapsedSeconds, shownAtSeconds)
        // 가장 큰 점 기준으로 한 칸씩. 점이 작아져도 중심은 그대로라 자리가 흔들리지 않는다.
        val step = dotSizeMax + dotSpacing
        val rowWidth = step * LoadingDotsMotion.DOT_COUNT - dotSpacing
        val startCenterX = (width - rowWidth) / 2f + dotSizeMax / 2f
        val centerY = height / 2f

        for (index in 0 until LoadingDotsMotion.DOT_COUNT) {
            val progress = LoadingDotsMotion.progressAt(
                index = index,
                elapsedSeconds = animationSeconds,
                periodSeconds = periodSeconds,
                staggerSeconds = staggerSeconds,
            )
            // 시안은 크기와 진하기가 함께 변한다. 크기만 바꾸면 밋밋하다.
            val diameter = LoadingDotsMotion.lerp(dotSizeMin, dotSizeMax, progress)
            paint.alpha = LoadingDotsMotion.lerp(ALPHA_MIN, ALPHA_MAX, progress).toInt()
            canvas.drawCircle(startCenterX + step * index, centerY, diameter / 2f, paint)
        }
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1000f

        /** 시안 실측 불투명도 29% / 100%를 0..255로 옮긴 값. */
        const val ALPHA_MIN = 74f
        const val ALPHA_MAX = 255f
    }
}
