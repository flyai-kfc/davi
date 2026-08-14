package com.davi.helper

import kotlin.math.cos
import kotlin.math.floor

/**
 * 강조 사각형(GD-2)의 **브리딩 위상 계산**. Android 타입을 쓰지 않아 JVM 테스트로 고정한다.
 * [ReadingBorder]와 같은 자리·같은 규약이다.
 *
 * **핵심 제약: 안쪽 선은 불투명을 유지한다.** [ReadingBorder]가 "강조 사각형은 불투명(255)이고
 * 읽는 중은 훨씬 옅게 둬서 색이 같아도 층이 갈린다"는 규칙을 세워 뒀다. 강조를 투명도로 숨
 * 쉬게 하면 그 구분이 무너져, 어느 쪽이 "지금 눌러야 할 곳"인지 흐려진다.
 * 그래서 **바깥 글로우만** 굵기·투명도가 오가고 안쪽 선은 그대로 둔다.
 *
 * 위상은 [HighlightOverlayView]가 **읽는 중 테두리와 같은 애니메이터에서** 받는다. 덕분에 둘이
 * 동시에 떠도 위상이 자동으로 맞아 따로 노는 인상이 없다(#147 결정 — 새 구동부를 만들지 않는다).
 */
object HighlightMotion {

    /**
     * 한 프레임에 그릴 값. 안쪽 선은 알파가 없다 — 항상 불투명이다.
     *
     * [glowSpread]는 후광이 안쪽 선 바깥으로 번지는 거리, [glowPeakAlpha]는 가장 안쪽
     * 후광의 진하기다. 실제 번짐은 [ringOffsetAt]·[ringAlphaAt]으로 여러 겹을 겹쳐 만든다.
     */
    data class Frame(
        val glowSpread: Float,
        val glowPeakAlpha: Int,
        val coreStroke: Float,
    )

    /**
     * 후광을 몇 겹으로 쌓아 번짐을 흉내 낼지.
     *
     * **`BlurMaskFilter`를 쓰지 않는 이유**는 하드웨어 가속 캔버스에서 동작하지 않아
     * 소프트웨어 레이어로 내려야 하기 때문이다. 이 뷰는 전체 화면 오버레이이고 세션 내내
     * 다른 앱 위에서 60fps로 도므로, 전면 소프트웨어 레이어는 비용이 너무 크다.
     * 겹을 쌓는 근사는 하드웨어 가속을 유지한다.
     */
    const val GLOW_RING_COUNT = 20

    /**
     * 위상 → 한 프레임. [phase]는 0..1이 한 주기이고 그 밖의 값도 감싸서 받는다.
     * [ReadingBorder.frame]과 같은 곡선을 쓴다 — 두 테두리가 한 화면에 뜨므로 리듬이 같아야 한다.
     *
     * 후광은 **번지면서 진해진다.** 가장 오므라든 순간에는 안쪽 선에 겹쳐 보이지 않아야 하므로
     * 그때 진하기가 0이다 — 그래야 "선 하나"에서 "번지는 후광"으로 자연스럽게 오간다.
     */
    fun frame(phase: Float, baseStroke: Float, glowSpread: Float): Frame {
        val wrapped = phase - floor(phase)
        // cos 한 주기를 0→1→0으로 뒤집는다. 0에서 최소, 0.5에서 최대.
        val breath = (0.5 - 0.5 * cos(wrapped * 2.0 * Math.PI)).toFloat()
        return Frame(
            glowSpread = glowSpread * breath,
            glowPeakAlpha = (HALO_ALPHA_MIN + (HALO_ALPHA_MAX - HALO_ALPHA_MIN) * breath)
                .toInt()
                .coerceIn(0, 255),
            coreStroke = baseStroke,
        )
    }

    /** [index]번째 겹이 안쪽 선에서 얼마나 바깥에 놓이는가. 0번이 가장 안쪽이다. */
    fun ringOffsetAt(index: Int, spread: Float): Float =
        spread * (index + 1) / GLOW_RING_COUNT

    /**
     * 후광 한 겹의 선 두께.
     *
     * **겹 간격보다 넉넉히 두껍게 잡는다.** 간격만큼만 두면 겹끼리 닿기만 해서 알파 차이가
     * 경계선으로 드러나고, 번짐이 아니라 동심원 띠로 보인다(실기기 실측, #147).
     * 서로 깊게 겹쳐야 계단이 지워진다.
     */
    fun ringStrokeFor(spread: Float, baseStroke: Float): Float {
        val spacing = spread / GLOW_RING_COUNT
        return maxOf(baseStroke, spacing * RING_OVERLAP)
    }

    /**
     * [index]번째 겹의 진하기. **바깥으로 갈수록 빠르게 옅어진다** — 선형으로 줄이면
     * 번짐이 아니라 굵은 띠로 보인다. 마이크 뒤 글로우(`MicGlowView`)의 방사형 감쇠와 같은 인상.
     */
    fun ringAlphaAt(index: Int, peakAlpha: Int): Int {
        val t = (index + 1).toFloat() / GLOW_RING_COUNT
        val falloff = (1f - t) * (1f - t)
        return (peakAlpha * falloff).toInt().coerceIn(0, 255)
    }

    // ── 조정 지점 ─────────────────────────────────────────────────────────────
    // ReadingBorder의 규약을 따른다 — dp가 아니라 시간·비율·투명도라 dimens.xml로 빼지 않는다.
    // 확정 디자인이 다시 잡을 자리라 정밀하게 맞추지 않는다.
    //
    // 주기는 여기 없다. 읽는 중 테두리와 **같은 애니메이터**를 쓰므로 ReadingBorder.PERIOD_MS가
    // 그대로 적용된다. 값을 따로 두면 둘이 어긋날 수 있고, 어긋나야 할 이유가 없다.

    /** 겹 하나가 간격의 몇 배로 두꺼운가. 클수록 매끄럽고 뿌옇다. */
    private const val RING_OVERLAP = 4f

    /** 애니메이터가 시스템 설정으로 꺼진 기기에서 그릴 정적 한 장의 위상(최대 밝기). */
    const val STATIC_PHASE = 0.5f

    // 오므라든 순간에는 안쪽 선에 겹치므로 아예 보이지 않게 둔다.
    //
    // 가장 퍼졌을 때는 읽는 중 테두리(최대 50)보다 훨씬 진하다 — 강조는 배경 표시가 아니라
    // "지금 여기"를 가리키는 신호다. 150으로 뒀더니 실기기에서 자세히 봐야 보이는 정도라
    // 강조 구실을 못 했고, 마이크 뒤 글로우(184)에 맞춰 올렸다.
    private const val HALO_ALPHA_MIN = 0
    private const val HALO_ALPHA_MAX = 200
    // ─────────────────────────────────────────────────────────────────────────
}
