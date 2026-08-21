package com.davi.helper

/**
 * 반복 애니메이션의 **구동 조건 전이**만 판정한다(#249). Android 타입을 쓰지 않아 JVM 테스트로
 * 고정한다 — [MotionView]가 이걸 들고, 조건을 읽어 넘기는 일만 한다.
 *
 * **전이일 때만 움직인다.** 모션이 꺼져 있으면 [MotionDriver]의 `running`이 서지 않아 `start()`의
 * 중복 가드가 안 걸리므로, 여기서 거르지 않으면 조건이 유지되는 동안의 가시성 이벤트마다 정지
 * 프레임이 재방출돼 [MotionView.onMotionFrame]의 "한 번만" 계약이 깨진다(#148).
 *
 * **구동 조작이 전이 훅보다 먼저다.** 모션 끄기의 정지 프레임 한 장이 흐른 뒤에 훅이 와야,
 * 훅에서 되감는 쪽([LoadingDotsGate])이 최신 프레임 시각을 보고 판단한다.
 *
 * **[onStart]가 값을 돌려주지 않는 것이 이 클래스의 요점이다.** 돌려주면 "시작이 실제로 루프를
 * 돌렸는가"에 훅을 걸 수 있게 되는데, 그 결합이 곧 #144다 — 모션이 꺼진 기기에서 훅이 영영
 * 안 와 지연이 arm되지 않고 화면이 빈다. 여기서는 그 결합을 **쓸 수 없게** 만들어 둔다.
 * 같은 이유로 상태 조회·카운터를 노출하지 않는다. 그런 손잡이가 곧 조건부 훅으로 가는 입구다.
 *
 * 짝이 되는 가드는 [LoadingDotsGate]다 — 이쪽이 "훅이 오는가", 저쪽이 "프레임으로 열지 않는가"를 맡는다.
 */
class MotionActivation(
    private val onStart: () -> Unit,
    private val onStop: () -> Unit,
    private val onTransition: (active: Boolean) -> Unit,
) {

    private var active = false

    /**
     * 구동 조건을 통째로 넘긴다(부착·가시·창 가시를 이미 AND로 접은 값).
     *
     * 조건이 그대로면 아무 일도 하지 않는다. 바뀌었으면 구동을 조작하고 **반드시** 전이 훅을
     * 부른다 — 모션이 꺼져 있어 [onStart]가 정지 프레임 한 장만 흘리고 끝나는 경우에도 그렇다.
     */
    fun sync(shouldRun: Boolean) {
        if (shouldRun == active) return
        active = shouldRun
        if (shouldRun) onStart() else onStop()
        onTransition(shouldRun)
    }
}
