package com.davi.helper

import kotlin.math.PI
import kotlin.math.cos

/**
 * 응답 대기 점 3개의 **순수 계산**(#142). Android 타입을 쓰지 않아 JVM 테스트가 닿는다.
 *
 * 시안(Figma SSoT SCREENS "메인 v2" 메인 6, 승인 대기)의 캡션이 규격을 확정해 뒀다:
 * "점 3개가 순서대로 커졌다 작아짐 (0.6s 주기, 0.15s 간격). 모션 끄기 시 정지 프레임."
 *
 * **크기와 진하기가 함께 변한다.** 시안 실측: 지름 16 / 13 / 11 이면서 불투명도도
 * 100% / 55% / 29%다. 크기만 바꾸면 시안보다 밋밋하다.
 */
object LoadingDotsMotion {

    const val DOT_COUNT = 3

    /**
     * 점 하나의 진행도(0=가장 작고 옅음, 1=가장 크고 진함).
     *
     * **코사인을 쓴다.** 사인이면 시간 0에서 모든 점이 중간값이라 시안의 정지 화면과 다르다.
     * 코사인이면 시간 0에서 첫 점이 최대, 둘째가 중간, 셋째가 최소 — 시안이 그려 둔 순간과
     * 정확히 같다. **모션 끄기에서 이 순간이 정지 프레임으로 그려지므로** 이 선택이 곧
     * "정지 화면이 쓸 만한가"를 결정한다.
     *
     * @param index 0부터. 클수록 늦게 커진다.
     * @param elapsedSeconds 애니메이션 시작부터의 시간.
     * @param periodSeconds 한 점이 커졌다 작아지는 한 주기.
     * @param staggerSeconds 점 사이의 시차.
     */
    fun progressAt(
        index: Int,
        elapsedSeconds: Float,
        periodSeconds: Float,
        staggerSeconds: Float,
    ): Float {
        if (periodSeconds <= 0f) return progressAt(index, 0f, 1f, staggerSeconds)
        val turns = (elapsedSeconds - index * staggerSeconds) / periodSeconds
        return (cos(TWO_PI * turns) + 1f) / 2f
    }

    /** 진행도 → 실제로 쓸 값. 최소와 최대 사이를 선형으로 잇는다. */
    fun lerp(min: Float, max: Float, progress: Float): Float =
        min + (max - min) * progress.coerceIn(0f, 1f)

    /**
     * 점을 그릴 때가 됐는가.
     *
     * **응답이 빠르면 점이 순간적으로 스쳐 오히려 산만하다.** 서버가 곧장 답하는 경우
     * 점이 한 번 깜빡이고 사라지는데, 시니어에게는 화면이 흔들린 것으로만 남는다.
     * 그래서 일정 시간이 지난 뒤에만 띄운다 — 그 전에는 "잠시만요" 문구만 보인다
     * (#127이 읽는 중 표시에서 같은 함정을 "깜빡임"으로 적어 뒀다).
     *
     * **모션이 꺼져 있으면 지연을 건너뛴다.** [MotionDriver]는 모션이 꺼지면 `elapsed=0`인
     * 프레임을 **한 번만** 흘리고 루프를 돌지 않는다. 그 하나를 지연 구간으로 판정해 버리면
     * 다음 프레임이 영영 오지 않아 점이 아예 그려지지 않는다 — 정지 프레임이 아니라 빈 화면이
     * 된다(PR #144 리뷰 지적). 애초에 지연은 깜빡임을 막으려는 것이고, 움직이지 않는 그림에는
     * 깜빡일 것이 없으므로 건너뛰는 편이 맞다.
     */
    fun shouldDrawAt(elapsedSeconds: Float, delaySeconds: Float, motionDisabled: Boolean): Boolean =
        motionDisabled || elapsedSeconds >= delaySeconds

    /**
     * 점이 보이기 시작한 뒤의 경과 시간. 지연 구간을 빼야 **첫 프레임이 시안의 정지 화면과
     * 같아진다** — 빼지 않으면 점이 주기 중간에서 튀어나온다.
     *
     * 모션이 꺼져 있으면 항상 0이다. 시간 0의 모습이 곧 시안이 그려 둔 정지 화면이다.
     */
    fun animationSecondsAt(
        elapsedSeconds: Float,
        delaySeconds: Float,
        motionDisabled: Boolean,
    ): Float = if (motionDisabled) 0f else (elapsedSeconds - delaySeconds).coerceAtLeast(0f)

    private const val TWO_PI = (2.0 * PI).toFloat()
}
