package com.davi.helper

/**
 * 응답 대기 점이 **드러났는가**만 판정한다(#249). Android 타입을 쓰지 않아 JVM 테스트로 고정한다.
 *
 * **지연을 프레임 시계로 재지 않는다.** 모션이 꺼진 기기에서 [MotionDriver]는 `elapsed=0` 프레임을
 * **딱 한 장** 흘리고 루프를 돌지 않는다. 그 한 장을 "아직 때가 아니다"로 판정하면 다음 기회가
 * 영영 오지 않아 정지 프레임이 아니라 **빈 화면**이 된다(#144에서 실제로 겪었다). 그래서 여는
 * 신호는 바깥(뷰의 핸들러, 벽시계)에서 오고, 이 게이트는 **왜 열렸는지 모르는 채로 남는다** —
 * 여는 조건이 여기 들어오는 순간 그 함정이 되살아난다.
 *
 * 짝이 되는 가드는 [MotionActivation]이다 — 저쪽이 "훅이 오는가", 이쪽이 "프레임으로 열지 않는가"를 맡는다.
 *
 * 메인 스레드에서만 만진다.
 */
class LoadingDotsGate {

    private var revealed = false
    private var revealedAtSeconds = 0f
    private var elapsedSeconds = 0f

    /**
     * 프레임 시각을 기록한다. **여기서 드러내지 않는다** — 그게 이 클래스가 존재하는 이유다.
     *
     * 가려져 있는 동안에도 계속 기록해야 [reveal]이 최신 시각을 집는다.
     */
    fun onFrame(elapsedSeconds: Float) {
        this.elapsedSeconds = elapsedSeconds
    }

    /**
     * 대기에 **다시 들어가는** 전이에서 부른다. 다시 감추고 처음부터 센다 — 점이 지연 없이
     * 곧장 튀어나오지 않게.
     *
     * 프레임 시계도 함께 되돌린다. 안 되돌리면 이전 구동의 큰 값이 남아, 새 구동의 첫 프레임보다
     * [reveal]이 먼저 발화하는 순간 그 낡은 값이 드러난 시각으로 박히고 점이 그만큼 위상 0에
     * 얼어붙는다. 지금은 지연이 첫 프레임보다 길어 안 터지지만 **그 안전은 이 클래스 바깥
     * (지연 상수·vsync 타이밍)에 있다** — 게이트가 왜 열렸는지 모르는 채로 남으려면 시계도
     * 스스로 0으로 둬야 한다. driver도 같은 전이에서 재시작돼 `elapsed`가 0부터 온다.
     */
    fun rearm() {
        revealed = false
        elapsedSeconds = 0f
    }

    /**
     * 지연이 끝났다. **지금까지 본 마지막 프레임 시각**을 드러난 시각으로 박는다.
     *
     * 핸들러(벽시계)와 프레임 시계는 다른 시계라, 지연 상수를 빼는 대신 이 값을 빼야 첫 프레임이
     * 정확히 시안의 정지 화면이 된다 — 메인 스레드가 밀려 핸들러가 늦게 발화해도 어긋나지 않는다.
     */
    fun reveal() {
        revealed = true
        revealedAtSeconds = elapsedSeconds
    }

    /** 그릴 때가 됐는가. 아니면 화면에는 "잠시만요" 문구만 있다. */
    fun isRevealed(): Boolean = revealed

    /**
     * 드러난 뒤의 경과. [isRevealed]가 참일 때만 뜻이 있다.
     *
     * `Float?` 하나로 합치지 않는 이유는 `onDraw`가 프레임마다 부르는 자리라서다 — 박싱이 매
     * 프레임 할당을 만든다.
     */
    fun animationSeconds(): Float =
        LoadingDotsMotion.animationSecondsAt(elapsedSeconds, revealedAtSeconds)
}
