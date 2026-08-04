package com.davi.helper

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.sin

/**
 * "듣고 있어요" 표시. 마이크 아이콘(레이아웃에서 View 위에 겹쳐 그린다) 양옆으로 막대가
 * 대칭으로 오르내리는 음파 모양이다 — 사용자 피드백: 기존의 단순 막대 웨이브는 "사람들이
 * 못 알아볼 것 같다"고 해서, 마이크+음파 조합의 통용되는 아이콘 형태로 바꿨다. 가운데는
 * 비워 두고([CENTER_GAP_RATIO]) 그 자리에 레이아웃에서 마이크 아이콘 `ImageView`를 겹친다
 * (이 View는 막대만 그린다).
 *
 * 가만히 있을 때도 은은하게 움직이고, 실제 마이크 음량이 들어오면([setVolumeLevel]) 그만큼
 * 크게 움직여 "지금 내 목소리가 들리고 있다"를 눈으로 확인할 수 있게 한다.
 * [HighlightOverlayView]와 같은 커스텀 View + Canvas 컨벤션을 따른다.
 */
class ListeningIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = resources.getColor(R.color.voice_listening_pulse, context.theme)
    }

    /** 0(대기)~1(마이크에 크게 잡힘). onRmsChanged가 없으면 은은한 대기값에 머문다. */
    private var smoothedAmplitude = IDLE_AMPLITUDE

    private var phase = 0f

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = WAVE_PERIOD_MS
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    /**
     * `SpeechRecognizer`가 넘겨주는 `rmsdB`(대략 -2~10+dB)를 0~1로 정규화해 반영한다.
     * 프레임마다 값이 튀지 않도록 지수 평활을 건다.
     */
    fun setVolumeLevel(rmsdB: Float) {
        val normalized = ((rmsdB + 2f) / 10f).coerceIn(0f, 1f)
        smoothedAmplitude = smoothedAmplitude * SMOOTHING + normalized * (1f - SMOOTHING)
        invalidate()
    }

    /** 새로 듣기 시작할 때 이전 세션의 음량이 남아있지 않게 한다. */
    fun resetVolumeLevel() {
        smoothedAmplitude = IDLE_AMPLITUDE
        invalidate()
    }

    /** 화면에 붙어 있을 때만 돈다 — 숨겨진 뷰가 배터리를 쓰지 않게 한다. */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val centerY = height / 2f
        val gapWidth = width * CENTER_GAP_RATIO
        val sideWidth = (width - gapWidth) / 2f
        val slotWidth = sideWidth / BARS_PER_SIDE
        val barWidth = slotWidth * BAR_WIDTH_RATIO
        val radius = barWidth / 2f
        // 대기 중에도 완전히 죽어 보이지 않도록 최소 높이를 두고, 음량이 커질수록 그 위로
        // 흔들리는 폭을 넓힌다.
        val swing = height * (0.15f + 0.65f * smoothedAmplitude)

        // 중심에서 바깥쪽으로 j번째 막대. 왼쪽·오른쪽에 같은 높이를 대칭으로 그려
        // 참고 이미지처럼 마이크를 중심으로 좌우가 거울상이 되게 한다.
        for (j in 0 until BARS_PER_SIDE) {
            val barPhase = (phase + j.toFloat() / BARS_PER_SIDE) % 1f
            val wave = (sin(barPhase * 2 * PI).toFloat() + 1f) / 2f
            val barHeight = (height * MIN_HEIGHT_RATIO) + swing * wave
            val top = centerY - barHeight / 2f
            val bottom = centerY + barHeight / 2f

            val leftCx = sideWidth - slotWidth * (j + 0.5f)
            canvas.drawRoundRect(leftCx - radius, top, leftCx + radius, bottom, radius, radius, barPaint)

            val rightCx = sideWidth + gapWidth + slotWidth * (j + 0.5f)
            canvas.drawRoundRect(rightCx - radius, top, rightCx + radius, bottom, radius, radius, barPaint)
        }
    }

    private companion object {
        const val WAVE_PERIOD_MS = 1100L
        const val BARS_PER_SIDE = 4
        const val BAR_WIDTH_RATIO = 0.55f
        const val MIN_HEIGHT_RATIO = 0.18f
        const val SMOOTHING = 0.6f
        const val IDLE_AMPLITUDE = 0.12f
        // 가운데 마이크 아이콘이 앉을 자리. 레이아웃에서 이 View 위에 ImageView를 겹친다.
        const val CENTER_GAP_RATIO = 0.34f
    }
}
