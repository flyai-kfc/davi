package com.davi.helper

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import androidx.core.content.ContextCompat

/**
 * 화면 하단에 일렁이는 파도 3겹(#132). **사용자가 말하는 동안에만** 보인다 —
 * 다비가 말할 때는 다른 모양으로 바꾸는 게 아니라 아예 그리지 않는다(시안 메인 2·4 대 3·5).
 *
 * 디자인 근거: Figma SSoT SCREENS "메인 v2" `v2/wave/bottom`(노드 820:1419, **승인 대기**).
 * 색은 시안에서 뽑은 **임시 토큰**이다 — v2 분홍 배경이 들어오기 전이라 흰 배경 위에서는
 * 시안과 느낌이 다르다. 배경이 들어오면 `colors.xml` 값만 갈아끼운다.
 *
 * 움직임 계산은 [WaveMotion]에 있다(JVM 테스트 대상). 이 뷰는 그 결과를 그리기만 한다 —
 * [HighlightOverlayView]가 [GuideBoxLayout]에 계산을 맡긴 것과 같은 구조다.
 */
class VoiceWaveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : MotionView(context, attrs, defStyleAttr) {

    /**
     * 파도 한 겹을 그릴 준비물. **Paint·Path를 겹마다 하나씩 만들어 두고 재사용한다** —
     * `onDraw`는 초당 60번 불리므로 여기서 객체를 새로 만들면 프레임마다 쓰레기가 쌓인다.
     */
    private class LayerBrush(val motion: WaveMotion.Layer, val color: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val path = Path()
    }

    private val layers: List<LayerBrush>

    private val amplitude = resources.getDimension(R.dimen.davi_wave_amplitude)
    private val wavePeriodSeconds =
        resources.getInteger(R.integer.davi_motion_wave_period_ms) / MILLIS_PER_SECOND
    private val levelFollowSeconds =
        resources.getInteger(R.integer.davi_motion_level_follow_ms) / MILLIS_PER_SECOND

    /** 마지막으로 받은 음량(0..1). 화면은 이 값을 곧장 쓰지 않고 [shownLevel]로 수렴시킨다. */
    private var targetLevel = 0f
    private var shownLevel = 0f

    /** 누적 위상. 음량에 따라 흐르는 빠르기가 변하므로 절대 시간으로 계산하지 않는다. */
    private var phaseTurns = 0f

    override fun onMotionFrame(elapsedSeconds: Float, deltaSeconds: Float) {
        shownLevel = WaveMotion.approach(shownLevel, targetLevel, deltaSeconds, levelFollowSeconds)
        phaseTurns = WaveMotion.advancePhase(phaseTurns, deltaSeconds, wavePeriodSeconds, shownLevel)
    }

    init {
        val colors = listOf(
            R.color.davi_wave_back,
            R.color.davi_wave_mid,
            R.color.davi_wave_front,
        )
        layers = WaveMotion.LAYERS.mapIndexed { index, motion ->
            LayerBrush(motion, ContextCompat.getColor(context, colors[index]))
        }
    }

    /**
     * 인식기가 준 음량을 넘긴다. 값 범위 정리는 [WaveMotion.normalizeRms]가 한다.
     *
     * 여기서 화면을 직접 다시 그리지 않는다 — 다음 프레임에 [MotionView]가 수렴시켜 그린다.
     * 콜백이 올 때마다 그리면 주사율과 어긋나 튄다.
     */
    fun onRmsChanged(rmsdB: Float) {
        targetLevel = WaveMotion.normalizeRms(rmsdB)
    }

    /** 다시 보일 때 이전 음량이 남아 갑자기 크게 시작하지 않도록 되돌린다. */
    fun resetLevel() {
        targetLevel = 0f
        shownLevel = 0f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 세로 페이드(시안 "페이드 · 세로"). 위쪽을 투명하게 빼서 흰 배경과의 경계를 없앤다.
        // 크기가 바뀔 때만 만든다 — 셰이더는 onDraw에서 만들면 안 되는 대표적인 객체다.
        layers.forEach { layer ->
            layer.paint.shader = LinearGradient(
                0f,
                0f,
                0f,
                h.toFloat(),
                Color.TRANSPARENT,
                layer.color,
                Shader.TileMode.CLAMP,
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val currentAmplitude = amplitude * WaveMotion.amplitudeFraction(shownLevel)
        layers.forEach { drawLayer(canvas, it, currentAmplitude) }
    }

    private fun drawLayer(canvas: Canvas, layer: LayerBrush, currentAmplitude: Float) {
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        val baseline = viewHeight * layer.motion.baselineFraction
        val path = layer.path
        // rewind는 할당해 둔 버퍼를 유지한 채 내용만 비운다. reset과 달리 재할당하지 않는다.
        path.rewind()

        // 곡선을 표본으로 근사한다. 시안은 벡터 3겹이지만 여기서는 사인파를 만들어 쓰므로
        // 좌우 끝이 드러날 여지가 없다 — 시안이 폭을 360(화면 300 + 좌우 30)으로 넓게 잡아 둔
        // 것은 정지 벡터를 가로로 밀기 위한 여유였고, 절차적으로 그리면 그 여유가 필요 없다.
        var x = 0f
        path.moveTo(0f, baseline + offsetAt(layer, 0f, currentAmplitude))
        while (x < viewWidth) {
            x = (x + SAMPLE_STEP_PX).coerceAtMost(viewWidth)
            path.lineTo(x, baseline + offsetAt(layer, x / viewWidth, currentAmplitude))
        }
        // 아래를 막아 면으로 만든다.
        path.lineTo(viewWidth, viewHeight)
        path.lineTo(0f, viewHeight)
        path.close()

        canvas.drawPath(path, layer.paint)
    }

    private fun offsetAt(layer: LayerBrush, xFraction: Float, currentAmplitude: Float): Float =
        WaveMotion.offsetAt(
            layer = layer.motion,
            xFraction = xFraction,
            phaseTurns = phaseTurns,
            amplitude = currentAmplitude,
            // 목소리가 클수록 결이 거칠어진다.
            choppiness = shownLevel,
        )

    private companion object {
        const val MILLIS_PER_SECOND = 1000f

        /**
         * 곡선을 몇 픽셀마다 표본할지. 촘촘할수록 매끄럽고 느리다.
         * 음량이 커지면 잔물결이 얹혀 파장이 짧아지므로, 큰 물결 기준으로 잡으면
         * 각이 보인다. 잔물결 파장에 맞춰 촘촘하게 둔다.
         */
        const val SAMPLE_STEP_PX = 4f
    }
}
