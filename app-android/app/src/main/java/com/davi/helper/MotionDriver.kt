package com.davi.helper

import android.content.Context
import android.provider.Settings
import android.view.Choreographer

/**
 * 반복 애니메이션의 프레임 공급자(#132). 파도·글로우가 공유하고, 이후 애니메이션 작업
 * (글자 등장·오버레이 강조·제스처 손동작 시연)도 이걸 쓴다.
 *
 * **`ValueAnimator`를 쓰지 않는다.** 무한 반복에서 시스템 애니메이션 배율이 0이면
 * `ValueAnimator`는 "즉시 끝"으로 동작해 반복이 이상해진다. 여기서는 배율을 **켜짐/꺼짐
 * 신호로만** 읽고, 프레임은 [Choreographer]에서 직접 받는다 — 꺼져 있으면 정지 프레임 한 장만
 * 그리고 루프를 돌지 않는다(디자인 캡션 "모션 끄기 시 정지 프레임").
 *
 * 시작·정지는 호출자의 생명주기 책임이다. 화면이 가려졌는데 돌고 있으면 배터리만 먹는다.
 */
class MotionDriver(
    context: Context,
    private val onFrame: (elapsedSeconds: Float, deltaSeconds: Float) -> Unit,
) {
    private val appContext = context.applicationContext
    private val choreographer = Choreographer.getInstance()

    private var running = false
    private var startNanos = 0L
    private var lastNanos = 0L

    /** 모션이 꺼져 있으면 true. 호출자가 "움직이지 않는 그림"을 골라야 할 때 본다. */
    val isMotionDisabled: Boolean
        get() = animatorDurationScale() == 0f

    // 타입을 명시한다 — 콜백 본문이 자기 자신을 다시 거는 구조라, 추론에 맡기면 순환한다.
    private val frameCallback: Choreographer.FrameCallback = Choreographer.FrameCallback { frameNanos ->
        if (!running) return@FrameCallback
        if (startNanos == 0L) {
            startNanos = frameNanos
            lastNanos = frameNanos
        }
        val elapsed = (frameNanos - startNanos) / NANOS_PER_SECOND
        val delta = (frameNanos - lastNanos) / NANOS_PER_SECOND
        lastNanos = frameNanos
        onFrame(elapsed, delta)
        // 콜백 안에서 다시 걸어야 다음 프레임이 온다. onFrame이 stop()을 불렀으면 걸지 않는다.
        if (running) choreographer.postFrameCallback(this.frameCallback)
    }

    /**
     * 프레임 공급을 시작한다. 이미 돌고 있으면 아무 일도 하지 않는다.
     *
     * 모션이 꺼져 있으면 루프 대신 **정지 프레임 한 장**을 흘린다 — 호출자는 "한 번은 그려진다"를
     * 전제해도 된다.
     */
    fun start() {
        if (running) return
        if (isMotionDisabled) {
            onFrame(0f, 0f)
            return
        }
        running = true
        startNanos = 0L
        lastNanos = 0L
        choreographer.postFrameCallback(frameCallback)
    }

    fun stop() {
        if (!running) return
        running = false
        choreographer.removeFrameCallback(frameCallback)
    }

    /**
     * 시스템 "애니메이션 배율" 설정. 접근성·배터리 절약 목적으로 꺼 둔 사용자가 있다.
     *
     * 설정을 못 읽으면 켜진 것으로 본다 — 못 읽었다는 이유로 애니메이션을 없애면
     * 멀쩡한 기기에서 화면이 죽어 보인다.
     */
    private fun animatorDurationScale(): Float =
        Settings.Global.getFloat(
            appContext.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000f
    }
}
