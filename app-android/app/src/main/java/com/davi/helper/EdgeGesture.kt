package com.davi.helper

/**
 * **화면 가장자리에서 시작하는 제스처**(#222)의 사양. Android 타입을 쓰지 않아 JVM 테스트가 닿는다.
 *
 * 목록 스크롤은 화면 어디서 시작해도 되지만, 셰이드를 끌어내리는 것과 앱 서랍을 끌어올리는 것은
 * **시작 지점이 화면 가장자리로 고정**이다. 그래서 이 둘만 "어디에 손을 대는가"를 함께 그린다 —
 * 가장자리 선를 강조하고, 손은 그 선에서 출발한다.
 *
 * **[GestureMotion.fingerDirectionFor]를 태우지 않는다**(#222 정하고 갈 것 3). 저쪽은 서버가 준
 * `direction`(= 보러 가는 쪽)을 뒤집는 자리인데, 이 액션들에는 `direction`이 애초에 오지 않는다.
 * 여기 [Spec.fingerDirection]은 **처음부터 손가락 방향**이라 뒤집지 않고 그대로 쓴다. 두 경로가
 * 갈리는 지점이 이 파일이다.
 *
 * **여기 있는 값이 제조사·버전 지식이다.** "앱별 지식을 코드에 박지 않는다"(루트 `CLAUDE.md`)의
 * 예외이며, 예외인 이유는 **서버가 줄 수 없는 값**이라서다 — 서버는 접근성 트리만 보고, 셰이드를
 * 여는 시작 지점은 트리에 없다. 대신 **한 파일 한 표로 가둬** 다른 기기가 오면 이 표만 고친다.
 * 베타 다기기 매트릭스(`SPEC.md` §5-6)가 확인할 항목도 이 표다.
 */
object EdgeGesture {

    /** 손이 출발하는 화면 변. */
    enum class Edge { TOP, BOTTOM }

    /**
     * @param edge 손이 출발하는 변.
     * @param fingerDirection **손가락이 실제로 움직이는 방향.** 서버 값이 아니므로 뒤집지 않는다.
     * @param startFraction·endFraction 가장자리 선가 차지하는 가로 구간(화면 폭 비율 0..1).
     */
    data class Spec(
        val edge: Edge,
        val fingerDirection: SwipeDirection,
        val startFraction: Float,
        val endFraction: Float,
    )

    /**
     * 시작 지점이 차지하는 네 변의 좌표. 좌표계는 호출자가 정한다(뷰는 로컬, 화면 좌표 둘 다).
     *
     * **사각형으로 받지만 그려지는 것은 선 하나다**(2026-08-21 요청). 사각형인 이유는 두께가
     * 곧 획 두께이고, 자리 계산(안내창 회피)에는 면이 필요하기 때문이다 — 그리는 쪽이 이
     * 사각형의 변 하나를 골라 선으로 칠한다.
     */
    data class Band(val left: Float, val top: Float, val right: Float, val bottom: Float)

    /**
     * 이 액션이 가장자리 제스처인가. 아니면 null이다.
     *
     * **퀵세팅은 화면 오른쪽 절반이다.** One UI 6+는 오른쪽 위가 빠른 설정, 왼쪽 위가 알림창이다
     * (HANDOFF §3-12, One UI 8.5 실측). 상단 전체 폭을 강조하면 왼쪽에서 끌어내린 시니어가
     * 알림창을 열고, 그 화면에는 우리가 안내한 것이 없다. **범위를 좁히는 것이 곧 정확도다.**
     *
     * **앱 서랍은 전체 폭이다.** 홈에서 위로 쓸어올리는 동작은 가로 위치를 가리지 않아 좁힐 근거가
     * 없다 — 근거 없이 좁히면 "거기서만 된다"는 틀린 지식을 가르친다.
     */
    fun specFor(actionType: ActionType?): Spec? = when (actionType) {
        ActionType.OPEN_QUICK_SETTINGS ->
            Spec(Edge.TOP, SwipeDirection.DOWN, startFraction = 0.5f, endFraction = 1f)

        ActionType.OPEN_APP_DRAWER ->
            Spec(Edge.BOTTOM, SwipeDirection.UP, startFraction = 0f, endFraction = 1f)

        else -> null
    }

    /**
     * 강조할 가장자리 선의 자리. **변에 딱 붙인다** — 안으로 물리면 "여기서 시작하라"가 아니라
     * "이 근처를 눌러라"로 읽힌다. [thickness]는 획 두께다.
     *
     * 좌표는 넘겨받은 캔버스 기준 그대로다. 이 함수는 어느 좌표계인지 모른다.
     */
    fun bandFor(
        spec: Spec,
        canvasLeft: Float,
        canvasTop: Float,
        canvasRight: Float,
        canvasBottom: Float,
        thickness: Float,
    ): Band {
        val width = canvasRight - canvasLeft
        val left = canvasLeft + width * spec.startFraction
        val right = canvasLeft + width * spec.endFraction
        return when (spec.edge) {
            Edge.TOP -> Band(left, canvasTop, right, canvasTop + thickness)
            Edge.BOTTOM -> Band(left, canvasBottom - thickness, right, canvasBottom)
        }
    }

    /** 손이 출발하는 변의 좌표. 손 모션의 이동 구간이 여기서 시작한다. */
    fun startEdgeY(spec: Spec, canvasTop: Float, canvasBottom: Float): Float = when (spec.edge) {
        Edge.TOP -> canvasTop
        Edge.BOTTOM -> canvasBottom
    }
}
