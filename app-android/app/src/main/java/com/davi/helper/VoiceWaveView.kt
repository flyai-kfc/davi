package com.davi.helper

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils

/**
 * 화면 하단에 일렁이는 파도 3겹(#132). **사용자가 말하는 동안에만** 보인다 —
 * 다비가 말할 때는 다른 모양으로 바꾸는 게 아니라 아예 그리지 않는다(시안 메인 2·4 대 3·5).
 *
 * 디자인 근거: Figma SSoT SCREENS "메인 v2" `v2/wave/bottom`(노드 820:1419).
 *
 * **구조가 두 층이다**(#161에서 v2 확정값으로 승격). 아래에 세로 페이드 한 장을 깔고
 * 그 위에 파도 3겹을 평면으로 채운다 — 임시 구현(#132)은 겹마다 세로 그라디언트를 물렸는데,
 * 시안은 페이드가 겹과 독립한 별도 레이어이고 겹은 각자 고정 불투명도다.
 *
 * 불투명도를 여기서 주는 이유는 `colors.xml`이 hex만 두기 때문이다. 리소스에 ARGB를 박으면
 * Figma 시맨틱과 1:1이 깨지고, 알파를 조정할 때 색까지 다시 적게 된다.
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

        /**
         * 이 겹의 누적 위상. **겹마다 따로 갖고, 기본파와 잔물결도 따로 갖는다.**
         * 공유 위상 하나를 접은 뒤 겹의 속도를 곱하면 접히는 순간 겹마다 다르게 튄다
         * (PR #136 리뷰 지적). 자세한 근거는 [WaveMotion.baseTurnsPerSecond].
         */
        var baseTurns = 0f
        var rippleTurns = 0f
    }

    private val layers: List<LayerBrush>

    /** 파도 아래 깔리는 세로 페이드. 겹과 독립한 별도 레이어다. */
    private val fadePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val fadeStart = ContextCompat.getColor(context, R.color.davi_wave_fade_start)
    private val fadeMid = ContextCompat.getColor(context, R.color.davi_wave_fade_mid)
    private val fadeEnd = ContextCompat.getColor(context, R.color.davi_wave_fade_end)

    private val amplitude = resources.getDimension(R.dimen.davi_wave_amplitude)
    private val wavePeriodSeconds =
        resources.getInteger(R.integer.davi_motion_wave_period_ms) / MILLIS_PER_SECOND
    private val levelFollowSeconds =
        resources.getInteger(R.integer.davi_motion_level_follow_ms) / MILLIS_PER_SECOND

    /** 마지막으로 받은 음량(0..1). 화면은 이 값을 곧장 쓰지 않고 [shownLevel]로 수렴시킨다. */
    private var targetLevel = 0f
    private var shownLevel = 0f

    override fun onMotionFrame(elapsedSeconds: Float, deltaSeconds: Float) {
        shownLevel = WaveMotion.approach(shownLevel, targetLevel, deltaSeconds, levelFollowSeconds)
        // 위상은 겹마다 따로 민다. 음량에 따라 빠르기가 변하므로 절대 시간으로 계산하지 않는다.
        layers.forEach { layer ->
            layer.baseTurns = WaveMotion.advanceTurns(
                layer.baseTurns,
                WaveMotion.baseTurnsPerSecond(layer.motion, wavePeriodSeconds, shownLevel),
                deltaSeconds,
            )
            layer.rippleTurns = WaveMotion.advanceTurns(
                layer.rippleTurns,
                WaveMotion.rippleTurnsPerSecond(layer.motion, wavePeriodSeconds, shownLevel),
                deltaSeconds,
            )
        }
    }

    init {
        val colors = listOf(
            R.color.davi_wave_layer_1 to LAYER_1_ALPHA,
            R.color.davi_wave_layer_2 to LAYER_2_ALPHA,
            R.color.davi_wave_layer_3 to LAYER_3_ALPHA,
        )
        layers = WaveMotion.LAYERS.mapIndexed { index, motion ->
            val (colorRes, alpha) = colors[index]
            LayerBrush(motion, withAlpha(ContextCompat.getColor(context, colorRes), alpha))
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
        // 셰이더는 크기가 바뀔 때만 만든다 — onDraw에서 만들면 안 되는 대표적인 객체다.
        //
        // 세로 페이드(시안 "페이드 · 세로")는 겹이 아니라 이 한 장이 맡는다. 위쪽을 투명하게
        // 빼서 배경과의 경계를 없애고, 아래로 갈수록 붉어진다.
        fadePaint.shader = LinearGradient(
            0f,
            0f,
            0f,
            h.toFloat(),
            intArrayOf(
                withAlpha(fadeStart, FADE_START_ALPHA),
                withAlpha(fadeMid, FADE_MID_ALPHA),
                withAlpha(fadeEnd, FADE_END_ALPHA),
            ),
            FADE_STOPS,
            Shader.TileMode.CLAMP,
        )
        // 겹은 평면 채움이다. 색에 이미 불투명도가 들어 있으므로 셰이더를 걸지 않는다.
        layers.forEach { layer ->
            layer.paint.shader = null
            layer.paint.color = layer.color
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        canvas.drawPaint(fadePaint)
        val currentAmplitude = amplitude * WaveMotion.amplitudeFraction(shownLevel)
        layers.forEach { drawLayer(canvas, it, currentAmplitude) }
    }

    private fun withAlpha(color: Int, alpha: Float): Int =
        ColorUtils.setAlphaComponent(color, (alpha * MAX_ALPHA).toInt().coerceIn(0, MAX_ALPHA))

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
            baseTurns = layer.baseTurns,
            rippleTurns = layer.rippleTurns,
            amplitude = currentAmplitude,
            // 목소리가 클수록 결이 거칠어진다.
            choppiness = shownLevel,
        )

    private companion object {
        const val MILLIS_PER_SECOND = 1000f
        const val MAX_ALPHA = 255

        /** 겹별 불투명도 — 시안 26 / 20 / 16%. 뒤 겹이 진하고 앞으로 올수록 옅다. */
        const val LAYER_1_ALPHA = 0.26f
        const val LAYER_2_ALPHA = 0.20f
        const val LAYER_3_ALPHA = 0.16f

        /** 세로 페이드 — 시안 0 → 22 → 42%. 색도 스톱마다 다르다. */
        val FADE_STOPS = floatArrayOf(0f, 0.5f, 1f)
        const val FADE_START_ALPHA = 0f
        const val FADE_MID_ALPHA = 0.22f
        const val FADE_END_ALPHA = 0.42f

        /**
         * 곡선을 몇 픽셀마다 표본할지. 촘촘할수록 매끄럽고 느리다.
         * 음량이 커지면 잔물결이 얹혀 파장이 짧아지므로, 큰 물결 기준으로 잡으면
         * 각이 보인다. 잔물결 파장에 맞춰 촘촘하게 둔다.
         */
        const val SAMPLE_STEP_PX = 4f
    }
}
