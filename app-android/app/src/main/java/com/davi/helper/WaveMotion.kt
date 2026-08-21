package com.davi.helper

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * 하단 파도·마이크 글로우의 **순수 계산**(#132). Android 타입을 쓰지 않으므로 JVM 테스트가 닿는다.
 *
 * 그리기는 [VoiceWaveView]·[MicGlowView]가 하고, 여기는 "언제 어디가 얼마나 높은가"만 답한다.
 * 화면 판단을 `renderModelOf`에 모아 둔 것과 같은 이유다 — 눈으로만 확인 가능한 것과 값으로
 * 확인 가능한 것을 갈라 둔다.
 *
 * 디자인 근거: Figma SSoT SCREENS "메인 v2" `v2/wave/bottom`(노드 820:1419, 승인 대기).
 */
object WaveMotion {

    /**
     * 파도 한 겹. 시안은 세 겹이 층을 이루고 있고(뒤가 옅고 높으며, 앞이 진하고 낮다),
     * **세 겹이 서로 어긋나게 움직여야 물결로 보인다.**
     *
     * @param baselineFraction 겹의 기준선. 뷰 높이에 대한 비율(0=위, 1=아래).
     * @param speed 흐르는 빠르기. 가장 뒤 겹을 1.0으로 둔 상대값.
     * @param phase 시작 위치를 어긋내는 값. 파장 하나를 1.0으로 둔다.
     * @param amplitudeScale 이 겹의 진폭 배율. 뒤가 크고 앞이 작다.
     * @param waveLengthFraction 파장. 뷰 너비에 대한 비율 — 겹마다 달라야 겹침이 단조롭지 않다.
     */
    data class Layer(
        val baselineFraction: Float,
        val speed: Float,
        val phase: Float,
        val amplitudeScale: Float,
        val waveLengthFraction: Float,
    )

    /**
     * 시안(300×152) 실측에서 온 세 겹. 기준선은 시안의 y를 높이로 나눈 값이다
     * (파도1 y=34 → 34/152, 파도2 y=67, 파도3 y=97).
     *
     * **속도 비를 정수배로 두지 않았다.** 2배·3배로 두면 일정 주기마다 세 겹이 같은 배치로
     * 되돌아와 규칙적인 맥동이 눈에 띈다. 1 : 0.62 : 1.37은 서로 정수배가 아니라 겹치는
     * 모양이 오래 반복되지 않는다. 파장도 겹마다 다르게 둬서 같은 이유를 한 번 더 막는다.
     *
     * **부호가 섞여 있다.** 가운데 겹만 반대로 흘러 마주 지나가며, 붙어 다니는 인상을 깬다.
     */
    val LAYERS: List<Layer> = listOf(
        // 뒤 — 가장 옅고 가장 높이 뜬다.
        Layer(baselineFraction = 34f / 152f, speed = 1.00f, phase = 0.00f, amplitudeScale = 1.00f, waveLengthFraction = 1.05f),
        // 가운데 — 반대 방향으로 흐른다.
        Layer(baselineFraction = 67f / 152f, speed = -0.62f, phase = 0.37f, amplitudeScale = 0.78f, waveLengthFraction = 0.83f),
        // 앞 — 가장 진하고 가장 낮으며 가장 빠르다.
        Layer(baselineFraction = 97f / 152f, speed = 1.37f, phase = 0.71f, amplitudeScale = 0.60f, waveLengthFraction = 0.66f),
    )

    /**
     * 말을 하지 않아도 남겨 두는 최소 진폭(전체 진폭에 대한 비율).
     *
     * 무음에서 **흐름은 멈추지만 모양은 남는다**([flowSpeed] 참고). 0으로 두면 파도가
     * 평평해져 사라진 것처럼 보이는데, 파도가 떠 있다는 것 자체가 "말할 차례"라는 신호라
     * 그 신호까지 함께 사라진다.
     */
    const val IDLE_AMPLITUDE = 0.35f

    /**
     * `SpeechRecognizer.onRmsChanged`의 dB 값을 0..1로 만든다.
     *
     * **범위는 기기·마이크마다 다르다.** 문서가 -2..10을 말하지만 실제로는 벗어난 값이 온다.
     * 그래서 원시값을 픽셀에 그대로 쓰지 않고 여기서 한 번 접는다. 경계값은 실기기에서
     * 확인할 대상이며 지금은 첫 추정치다.
     *
     * **SM-S921N에서는 실제로 -2..10 안에 있었다**(973표본, 양 끝값이 정확히 반복 —
     * PR #324 · [MicTone]의 `AMPLITUDE` 주석 참고). **한 기기 · 그때 붙은 인식기 하나의 관측이라
     * 접는 것은 그대로 둔다** — 벗어나는 기기를 본 적이 있으면 여기에 적는다.
     */
    fun normalizeRms(rmsdB: Float): Float {
        if (rmsdB.isNaN()) return 0f
        val normalized = (rmsdB - RMS_FLOOR_DB) / (RMS_CEILING_DB - RMS_FLOOR_DB)
        return normalized.coerceIn(0f, 1f)
    }

    /** 정규화된 음량 → 실제로 쓸 진폭 비율. 바닥([IDLE_AMPLITUDE]) 위에 음량을 얹는다. */
    fun amplitudeFraction(level: Float): Float =
        IDLE_AMPLITUDE + level.coerceIn(0f, 1f) * (1f - IDLE_AMPLITUDE)

    /**
     * 현재값을 목표값 쪽으로 한 프레임만큼 당긴다.
     *
     * `onRmsChanged`는 화면 주사율과 무관하게 오므로 값을 그대로 그리면 프레임마다 튄다.
     * 지수 수렴이라 프레임 간격이 들쭉날쭉해도 결과가 같다 — 단순 선형 보간과 달리
     * `dt`에 정직하게 반응하므로 프레임 드롭에서 속도가 변하지 않는다.
     *
     * @param tauSeconds 목표에 63%만큼 다가가는 데 걸리는 시간. 0 이하면 즉시 목표값.
     */
    fun approach(current: Float, target: Float, deltaSeconds: Float, tauSeconds: Float): Float {
        if (tauSeconds <= 0f || deltaSeconds <= 0f) return target
        val factor = 1f - exp(-deltaSeconds / tauSeconds)
        return current + (target - current) * factor
    }

    /**
     * 위상을 한 프레임만큼 밀어 다음 값을 만든다. 단위는 "파장 몇 개분"이다.
     *
     * **절대 시간으로 위상을 계산하지 않는 이유**는 빠르기가 음량을 따라 변하기 때문이다.
     * `speed * t / period`로 두면 빠르기가 바뀌는 순간 이미 흘러간 t 전체에 새 배율이 걸려
     * 파도가 앞뒤로 튄다. 누적하면 지나간 것은 그대로 두고 앞으로만 달라진다.
     *
     * @param level 정규화된 음량(0..1). **무음이면 흐르지 않는다** — [flowSpeed] 참고.
     */
    fun advanceTurns(
        currentTurns: Float,
        turnsPerSecond: Float,
        deltaSeconds: Float,
    ): Float = wrapTurns(currentTurns + turnsPerSecond * deltaSeconds)

    /**
     * 한 겹의 **기본파**가 초당 몇 바퀴 흐르는가. 겹의 속도가 여기 이미 곱해져 있다.
     *
     * **속도를 위상에 곱하지 않고 여기서 미리 반영하는 것이 핵심이다.** 공유 위상 하나를
     * 접은 뒤 겹마다 비정수 배수를 곱하면, 접히는 순간 겹마다 다른 크기로 순간이동한다
     * (PR #136 리뷰 지적).
     *
     * **세 겹이 모두 끊겼다.** 속도가 -0.62·1.37인 두 겹은 기본파에서 끊겼고, 속도가 1.00이라
     * 기본파는 이어지던 첫 겹도 **잔물결에서 끊겼다** — 잔물결은 위상에 [RIPPLE_SPEED]=1.6을
     * 곱해 쓰므로 속도가 1이어도 정수가 아니다. 겹마다, 그리고 기본파·잔물결마다 자기 위상을
     * 갖고 각자 1바퀴에서 접으면 어느 쪽도 튀지 않는다.
     */
    fun baseTurnsPerSecond(layer: Layer, periodSeconds: Float, level: Float): Float {
        if (periodSeconds <= 0f) return 0f
        return layer.speed * flowSpeed(level) / periodSeconds
    }

    /**
     * 한 겹의 **잔물결**이 초당 몇 바퀴 흐르는가. 기본파보다 빠르다.
     *
     * 잔물결도 자기 위상을 따로 갖는다. 기본파 위상에 배수를 곱해 쓰면 기본파가 접히는
     * 순간 잔물결은 그 배수만큼 튄다.
     */
    fun rippleTurnsPerSecond(layer: Layer, periodSeconds: Float, level: Float): Float =
        baseTurnsPerSecond(layer, periodSeconds, level) * RIPPLE_SPEED

    /**
     * 위상을 0..1로 접는다. 계속 커지게 두면 언젠가 float 정밀도가 무너져 파도가 각져 보인다.
     * 음수 속도(반대로 흐르는 겹)가 있으므로 `%`만으로는 부족하다 — 한 번 더 올려 준다.
     */
    private fun wrapTurns(turns: Float): Float {
        val wrapped = turns % 1f
        return if (wrapped < 0f) wrapped + 1f else wrapped
    }

    /**
     * 음량 → 흐르는 빠르기. **말하지 않으면 0이다(2026-08-14 결정).**
     *
     * 처음에는 무음에서도 천천히 흐르게 뒀다 — "멈추면 안 듣고 있는 것으로 읽힌다"는 우려였다.
     * 뒤집은 근거는 **파도가 떠 있다는 것 자체가 말하라는 신호**라는 것이다. 파도는 사용자
     * 차례에만 그려지므로(다비가 말할 때는 아예 없다), 떠 있는데 멈춰 있는 상태가 곧
     * "당신 차례이고 아직 안 들리고 있다"는 뜻이 된다.
     *
     * **진폭까지 0으로 만들지는 않는다** — 평평해지면 파도가 사라진 것으로 보여 그 신호가
     * 같이 사라진다. 모양은 [IDLE_AMPLITUDE]로 남기고 흐름만 멈춘다.
     *
     * 조용한 방의 미세한 잡음에 파도가 실실 흐르지 않도록 문턱 아래는 완전히 0으로 두고,
     * 문턱 위에서는 0부터 이어 올려 시작이 튀지 않게 한다.
     */
    fun flowSpeed(level: Float): Float {
        val clamped = level.coerceIn(0f, 1f)
        if (clamped <= SILENCE_THRESHOLD) return 0f
        val ramped = (clamped - SILENCE_THRESHOLD) / (1f - SILENCE_THRESHOLD)
        return ramped * MAX_FLOW_SPEED
    }

    /**
     * 한 겹의 특정 가로 위치에서의 세로 오프셋(기준선 기준, 위가 음수).
     *
     * 사인파 하나만 쓰면 아무리 키워도 "완만한 물결"에 머문다. 목소리가 클수록 **잔물결**을
     * 얹어 결이 거칠어지게 한다 — 조용할 때는 매끈하고, 말할 때 요동친다.
     *
     * @param xFraction 뷰 왼쪽 끝 0, 오른쪽 끝 1.
     * @param baseTurns 이 겹의 기본파 누적 위상([advanceTurns]로 민다). **속도가 이미 반영돼
     *   있으므로 여기서 다시 곱하지 않는다** — 그렇게 하면 접히는 순간 튄다.
     * @param rippleTurns 이 겹의 잔물결 누적 위상. 기본파와 따로 접힌다.
     * @param amplitude 진폭(px). 겹의 [Layer.amplitudeScale]은 이 안에서 곱해진다.
     * @param choppiness 잔물결 세기(0..1). 보통 정규화된 음량을 그대로 넘긴다.
     */
    fun offsetAt(
        layer: Layer,
        xFraction: Float,
        baseTurns: Float,
        rippleTurns: Float,
        amplitude: Float,
        choppiness: Float = 0f,
    ): Float {
        val cycles = xFraction / layer.waveLengthFraction
        val base = sin(TWO_PI * (cycles + baseTurns + layer.phase))
        // 잔물결은 파장이 짧고 더 빨리 흐른다. 배수를 정수로 두지 않아야 마루가 겹쳐
        // 규칙적으로 커지는 자리가 생기지 않는다.
        val ripple = sin(
            TWO_PI * (cycles * RIPPLE_FREQUENCY + rippleTurns + layer.phase * 1.7f),
        )
        val shaped = base + ripple * choppiness.coerceIn(0f, 1f) * RIPPLE_WEIGHT
        return -shaped * amplitude * layer.amplitudeScale
    }

    private const val TWO_PI = (2.0 * PI).toFloat()

    /**
     * 이 아래 음량은 무음으로 보고 파도를 완전히 멈춘다. 조용한 방의 잡음에 실실 흐르지
     * 않도록 두는 여유이며, 실기기에서 조정할 대상이다.
     */
    private const val SILENCE_THRESHOLD = 0.06f

    /** 최대 음량에서의 흐름 배속. 기준 주기(davi_motion_wave_period_ms) 대비다. */
    private const val MAX_FLOW_SPEED = 2.1f

    /** 잔물결의 파장 배수. 정수가 아니어야 큰 물결과 마루가 규칙적으로 겹치지 않는다. */
    private const val RIPPLE_FREQUENCY = 2.7f

    /** 잔물결이 큰 물결보다 빠르게 흐르는 배수. */
    private const val RIPPLE_SPEED = 1.6f

    /** 최대 음량에서 잔물결이 큰 물결 대비 차지하는 비중. 너무 크면 파도가 아니라 노이즈가 된다. */
    private const val RIPPLE_WEIGHT = 0.42f

    /** 이 아래는 무음으로 본다. */
    private const val RMS_FLOOR_DB = -2f

    /** 이 위는 더 커져도 같게 본다. 시끄러운 곳에서 파도가 계속 최대로 붙어 있지 않도록. */
    private const val RMS_CEILING_DB = 8f
}
