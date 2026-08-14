package com.davi.helper

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import androidx.core.content.ContextCompat
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * 마이크 뒤에서 웅웅거리는 글로우(#132). 시안의 원래 이름이 "글로우 · 마이크(**웅웅**)"다.
 *
 * 두 가지 자리에서 쓴다.
 * - **듣는 중** — 음량에 반응해 커졌다 작아진다. 파도와 짝이다.
 * - **홈 유도**(임시) — 마이크를 누르라는 신호. **홈 전용 시안이 아직 없어**
 *   `v2/glow/mic`을 재사용하는 임시 조치이며, 시안이 확보되면 교체한다(#132 결정 2026-08-14).
 *   이때는 음량이 없으므로 고정 주기로만 숨 쉰다.
 *
 * 디자인 근거: Figma SSoT SCREENS "메인 v2" `v2/glow/mic`(168×168, **승인 대기**).
 */
class MicGlowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : MotionView(context, attrs, defStyleAttr) {

    /** 음량에 반응할지. 홈 유도에서는 들을 마이크가 없으므로 false. */
    var reactsToVoice: Boolean = true

    private val glowColor = ContextCompat.getColor(context, R.color.davi_glow_mic)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val glowPeriodSeconds =
        resources.getInteger(R.integer.davi_motion_glow_period_ms) / MILLIS_PER_SECOND
    private val levelFollowSeconds =
        resources.getInteger(R.integer.davi_motion_level_follow_ms) / MILLIS_PER_SECOND

    private var targetLevel = 0f
    private var shownLevel = 0f

    /**
     * 누적 숨쉬기 위상. 절대 시간을 쓰지 않는 이유는 **듣는 중에는 말할 때만 숨을 쉬기**
     * 때문이다 — 흐르다 멈추다 하므로 경과 시간과 위상이 일치하지 않는다.
     */
    private var breathTurns = 0f

    override fun onMotionFrame(elapsedSeconds: Float, deltaSeconds: Float) {
        shownLevel = WaveMotion.approach(shownLevel, targetLevel, deltaSeconds, levelFollowSeconds)
        // 듣는 중이면 파도와 같은 규칙으로 말할 때만 숨 쉰다(2026-08-14 결정).
        // 홈 유도는 계속 숨 쉰다 — 멈추면 "누르세요"라는 신호 자체가 사라진다.
        val level = if (reactsToVoice) shownLevel else 1f
        val turnsPerSecond = if (glowPeriodSeconds > 0f) {
            WaveMotion.flowSpeed(level) / glowPeriodSeconds
        } else {
            0f
        }
        breathTurns = WaveMotion.advanceTurns(breathTurns, turnsPerSecond, deltaSeconds)
    }

    fun onRmsChanged(rmsdB: Float) {
        if (reactsToVoice) targetLevel = WaveMotion.normalizeRms(rmsdB)
    }

    fun resetLevel() {
        targetLevel = 0f
        shownLevel = 0f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val radius = min(w, h) / 2f
        if (radius <= 0f) return
        // 셰이더는 크기가 바뀔 때만 만든다.
        //
        // **중심에서 바로 옅어지게 하면 안 된다.** 글로우 안쪽 절반은 마이크 버튼(불투명)이
        // 덮으므로, 중심부터 감쇠시키면 실제로 보이는 바깥 테두리는 이미 다 사라진 뒤다
        // (실기기 실측: 흰 배경 255 대비 픽셀차가 4밖에 안 남아 유도로 읽히지 않았다).
        // 그래서 마이크에 가려지는 구간까지는 색을 유지하고, 거기서부터 바깥으로 감쇠시킨다.
        paint.shader = RadialGradient(
            w / 2f,
            h / 2f,
            radius,
            intArrayOf(glowColor, glowColor, Color.TRANSPARENT),
            floatArrayOf(0f, MIC_EDGE_FRACTION, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        canvas.save()
        // 숨 쉬는 주기 위에 음량을 얹는다. 모션이 꺼져 있으면 위상이 0에 머물러
        // 정지 프레임 한 장이 그려진다(MotionDriver).
        val breath = (sin(TWO_PI * breathTurns) + 1f) / 2f
        val voiceLevel = if (reactsToVoice) shownLevel else 0f
        val scale =
            MIN_SCALE + (breath * BREATH_RANGE + voiceLevel * LEVEL_RANGE).coerceAtMost(1f - MIN_SCALE)
        canvas.scale(scale, scale, width / 2f, height / 2f)
        canvas.drawCircle(width / 2f, height / 2f, min(width, height) / 2f, paint)
        canvas.restore()
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1000f
        val TWO_PI = (2.0 * PI).toFloat()

        /**
         * 마이크 버튼이 글로우를 덮는 지점(글로우 반지름에 대한 비율).
         * 마이크 130dp / 글로우 286dp → 65 / 143 ≈ 0.45. 두 dimen이 바뀌면 이 값도 바뀐다.
         */
        const val MIC_EDGE_FRACTION = 0.45f

        /** 가장 작을 때의 크기 배율. 0에서 시작하면 사라졌다 나타나 깜빡이는 것처럼 보인다. */
        const val MIN_SCALE = 0.72f

        /** 숨쉬기가 차지하는 크기 폭. */
        const val BREATH_RANGE = 0.18f

        /** 음량이 더할 수 있는 크기 폭. 숨쉬기보다 크게 잡아 목소리 반응이 주도하게 한다. */
        const val LEVEL_RANGE = 0.16f
    }
}
