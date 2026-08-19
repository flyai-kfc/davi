package com.davi.helper

import android.animation.ValueAnimator
import android.view.Choreographer

/**
 * 반복 애니메이션의 프레임 공급자(#132). 파도·글로우가 공유하고, 이후 애니메이션 작업
 * (글자 등장·오버레이 강조·제스처 손동작 시연)도 이걸 쓴다.
 *
 * **`ValueAnimator`를 쓰지 않는다.** 무한 반복에서 시스템 애니메이션 배율이 0이면
 * `ValueAnimator`는 "즉시 끝"으로 동작해 반복이 이상해진다(#148에서 유지 확정 — 오버레이의
 * 1회성 애니메이션과 달리 무한 반복에는 이 구동이 맞다). 모션 꺼짐은 [ValueAnimator.areAnimatorsEnabled]를
 * **켜짐/꺼짐 신호로만** 읽고 — 배율 0과 절전 모드를 함께 반영하는 공식 API라 오버레이 계열과
 * 판정이 같다 — 프레임은 [Choreographer]에서 직접 받는다. 꺼져 있으면 정지 프레임 한 장만
 * 그리고 루프를 돌지 않는다(디자인 캡션 "모션 끄기 시 정지 프레임").
 *
 * 시작·정지는 호출자의 생명주기 책임이다. 화면이 가려졌는데 돌고 있으면 배터리만 먹는다.
 */
class MotionDriver(
    private val onFrame: (elapsedSeconds: Float, deltaSeconds: Float) -> Unit,
) {
    private val choreographer = Choreographer.getInstance()

    private var running = false
    private var startNanos = 0L
    private var lastNanos = 0L

    // 모션 꺼짐은 구동부 안에서만 판정한다 — 호출자는 "한 번은 그려진다"([start])만 알면 된다.
    private val isMotionDisabled: Boolean
        get() = !ValueAnimator.areAnimatorsEnabled()

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

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000f
    }
}
