package com.davi.helper

import kotlin.math.cos

/**
 * 읽는 중 표시(GD-1·GD-2r)의 **판정과 위상 계산**. Android 타입을 쓰지 않아 JVM 테스트로 고정한다.
 *
 * "언제 보이나"([isReading])와 "어떻게 보이나"([frame])를 한 파일에 둔 것은 둘 다 이 기능
 * 하나의 규칙이고 각각 몇 줄이라서다(`ScreenSignature` + `ScreenStabilityTracker`가 한 파일에
 * 있는 것과 같은 이유).
 *
 * **표시 규칙은 여기 없다.** 지연 표시(onset)·애니메이터 수명은 뷰가 갖는다 — 서비스의
 * mainHandler에 토큰을 늘리면 세션 종료·root 소실·언바인드 세 곳에서 각각 지워야 하고,
 * 하나만 빠뜨려도 세션이 끝난 뒤 테두리가 뜬다.
 */
object ReadingBorder {

    /**
     * 지금 "읽는 중"인가.
     *
     * [decision]이 null이면 세션 시작 후 첫 샘플 전이다 — 그 공백(SETTLE_MS)이 이 기능의
     * 1순위 대상이라 켠다. [requestInFlight]를 따로 받는 이유는
     * [ScreenStabilityTracker]가 응답 대기 중에 [ScreenStabilityTracker.Decision.IDLE]을
     * 돌려주기 때문이다 — 판정만 보면 요청이 도는 내내 꺼진다.
     *
     * 백오프 재시도 대기는 켜지 않는다. "읽는 중"으로 보이면 오해를 준다 — 서버 실패를
     * 알리는 것은 안내창이 맡는다(#29, 두 번째 연속 실패부터 `guide_error_*`).
     *
     * [awaitingConfirm]이면 끈다(#139). 완료 확인 중에는 화면을 보고 있어도 서버에 묻지 않고,
     * 같은 자리를 성공 tone 테두리가 쓴다 — 둘이 겹치면 색만 깜빡인다.
     */
    fun isReading(
        sessionActive: Boolean,
        screenReadable: Boolean,
        decision: ScreenStabilityTracker.Decision?,
        requestInFlight: Boolean,
        awaitingConfirm: Boolean = false,
    ): Boolean {
        if (!sessionActive || !screenReadable || awaitingConfirm) return false
        if (requestInFlight) return true
        return when (decision) {
            null -> true
            ScreenStabilityTracker.Decision.WAIT -> true
            ScreenStabilityTracker.Decision.SEND -> true
            ScreenStabilityTracker.Decision.IDLE -> false
            ScreenStabilityTracker.Decision.STUCK -> false
            ScreenStabilityTracker.Decision.UNREADABLE -> false
        }
    }

    /** 한 프레임에 그릴 두 겹의 두께·투명도. 굵고 옅은 [glow] 위에 [core]를 얹는다. */
    data class Frame(
        val glowStroke: Float,
        val glowAlpha: Int,
        val coreStroke: Float,
        val coreAlpha: Int,
    )

    /**
     * 위상 → 한 프레임. [phase]는 0..1이 한 주기이고 그 밖의 값도 감싸서 받는다.
     * breath는 0→1→0으로 오가며, glow만 두께가 함께 커진다(core는 고정 두께).
     */
    fun frame(phase: Float, baseStroke: Float, glowSpread: Float): Frame {
        val wrapped = phase - kotlin.math.floor(phase)
        // cos 한 주기를 0→1→0으로 뒤집는다. 0에서 최소, 0.5에서 최대.
        val breath = (0.5 - 0.5 * cos(wrapped * 2.0 * Math.PI)).toFloat()
        return Frame(
            glowStroke = baseStroke + glowSpread * breath,
            glowAlpha = lerpAlpha(GLOW_ALPHA_MIN, GLOW_ALPHA_MAX, breath),
            coreStroke = baseStroke,
            coreAlpha = lerpAlpha(CORE_ALPHA_MIN, CORE_ALPHA_MAX, breath),
        )
    }

    private fun lerpAlpha(min: Int, max: Int, t: Float): Int =
        (min + (max - min) * t).toInt().coerceIn(0, 255)

    // ── 조정 지점 ─────────────────────────────────────────────────────────────
    // 아래 값들은 임시 디자인 단계의 임시치다. 확정 디자인이 테두리의 모양·주기·투명도를
    // 다시 잡을 자리라 정밀하게 맞추지 않는다. dp가 아니라 시간·비율·투명도라
    // dimens.xml로 빼지 않는다(GuideBoxLayout의 조정 지점과 같은 규약).

    /** 브리딩 한 주기. */
    const val PERIOD_MS = 1_600L

    /**
     * 이만큼 지나야 테두리를 띄운다. 응답이 빠르면 한 프레임도 그리지 않아 깜빡임이 없다.
     * SETTLE_MS(350)보다 짧아야 "세션 시작 공백을 덮는다"는 목적이 성립한다.
     */
    const val ONSET_DELAY_MS = 250L

    /** 애니메이터가 시스템 설정으로 꺼진 기기에서 그릴 정적 한 장의 위상(최대 밝기). */
    const val STATIC_PHASE = 0.5f

    // 강조 사각형은 불투명(255)이다. 읽는 중은 훨씬 옅게 둬서 색이 같아도 층이 갈리게 한다 —
    // 배경에 깔리는 앰비언트 표시이지, 지금 눌러야 할 곳이 아니다.
    private const val GLOW_ALPHA_MIN = 20
    private const val GLOW_ALPHA_MAX = 50
    private const val CORE_ALPHA_MIN = 40
    private const val CORE_ALPHA_MAX = 90
    // ─────────────────────────────────────────────────────────────────────────
}
