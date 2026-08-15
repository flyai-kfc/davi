package com.davi.helper

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor

/**
 * 방향 제스처 시연(GD-2)의 **판정과 위치 계산**(#152). Android 타입을 쓰지 않아 JVM 테스트가 닿는다.
 * [ReadingBorder]·[HighlightMotion]과 같은 자리·같은 규약이다.
 *
 * **서버가 보내는 방향을 뒤집어 그린다**(2026-08-14 결정). 원래는 뒤집지 않는 것이 맞다 —
 * 계약(`server/app/schemas.py`)의 `SwipeDirection`은 "손가락이 움직이는 방향"이고, "무엇이
 * 보이게 되는가 → 손가락이 어디로"라는 변환은 서버가 끝냈어야 한다.
 *
 * **그런데 서버가 실제로는 내용이 움직일 방향을 보낸다.** 실기기 실측(SM-S921N, 2026-08-14):
 * 설정 목록에서 아래쪽 항목을 찾아야 하는데 `down`이 왔다(계약대로면 `up`).
 * 원인과 고칠 자리는 #109에 정리돼 있으나 **서버에서 고치지 않기로 했고**, 화면에는 방향이
 * 하나로 못박혀 그려지므로 여기서 보정한다.
 *
 * **이 보정은 #109가 고쳐지는 순간 정반대가 된다.** 되돌릴 자리는 [fingerDirectionFor] 하나이며,
 * 그 함수만 항등으로 바꾸면 된다. 서버 동작이 바뀌었다는 신호를 보면 가장 먼저 여기를 본다.
 */
object GestureMotion {

    /**
     * 한 주기 중 손이 실제로 움직이는 비율. 나머지는 다음 획을 위한 쉼이다.
     *
     * 아래 "조정 지점"에 속하지만 [STATIC_PHASE]가 이 값을 써서 초기화되므로 먼저 선언한다 —
     * object의 const는 선언 순서대로 초기화된다.
     */
    const val STROKE_FRACTION = 0.7f

    /** 손이 움직이는 축과 부호. 화면 좌표계 기준이라 [dy]는 아래가 양수다. */
    data class Travel(val dx: Float, val dy: Float)

    /**
     * 이 안내에 손 모션을 그리는가.
     *
     * **방향 제스처만 그린다**(2026-08-14 결정). 탭은 후광(#147)만으로 충분하고, 대상 위에 손을
     * 겹치면 그 후광을 가린다. 캡처는 `ActionType`에 없어 서버가 낼 수 없다.
     */
    fun isDemonstrated(actionType: ActionType?, direction: SwipeDirection?): Boolean =
        actionType == ActionType.SCROLL && direction != null

    /**
     * 서버가 준 방향 → **실제로 그릴 손가락 방향**. 넷 모두 뒤집는다.
     *
     * **이것이 이 파일의 유일한 보정 지점이다.** 서버(#109)가 계약대로 손가락 방향을 보내기
     * 시작하면 이 함수를 항등(`serverDirection`을 그대로 반환)으로 바꾸는 것만으로 끝난다 —
     * 뒤집기를 [travelFor]나 뷰에 흩어 놓지 않은 이유다.
     *
     * 되돌려야 하는 신호: 스크롤 안내에서 손이 사용자가 실제로 해야 하는 동작과 반대로 움직인다.
     */
    fun fingerDirectionFor(serverDirection: SwipeDirection): SwipeDirection = when (serverDirection) {
        SwipeDirection.UP -> SwipeDirection.DOWN
        SwipeDirection.DOWN -> SwipeDirection.UP
        SwipeDirection.LEFT -> SwipeDirection.RIGHT
        SwipeDirection.RIGHT -> SwipeDirection.LEFT
    }

    /**
     * 손이 [distance]만큼 움직일 때의 변위. **인자는 손가락 방향이지 서버 값이 아니다** —
     * 서버 값은 [fingerDirectionFor]로 한 번 거른 뒤 넣는다.
     *
     * 세로 스크롤과 가로 스와이프는 같은 `ActionType.SCROLL`의 다른 방향일 뿐이라 한 함수가 넷을
     * 모두 답한다.
     */
    fun travelFor(direction: SwipeDirection, distance: Float): Travel = when (direction) {
        SwipeDirection.UP -> Travel(0f, -distance)
        SwipeDirection.DOWN -> Travel(0f, distance)
        SwipeDirection.LEFT -> Travel(-distance, 0f)
        SwipeDirection.RIGHT -> Travel(distance, 0f)
    }

    /**
     * 한 주기 안에서 손이 이동 구간의 어디쯤 있는가(0=출발, 1=도착).
     *
     * 왕복하지 않는다 — **한 방향으로 밀고 처음으로 돌아온다.** 왕복시키면 되돌아오는 절반이
     * 반대 방향 안내로 읽힌다. 되돌아오는 구간은 [alphaAt]이 지워 눈에 띄지 않게 한다.
     */
    fun progressAt(phase: Float): Float {
        val wrapped = phase - floor(phase)
        return (wrapped / STROKE_FRACTION).coerceAtMost(1f)
    }

    /**
     * 그 순간의 진하기(0..1). 출발에서 차오르고 도착에서 잦아든다.
     *
     * 이동이 끝난 뒤 남는 구간에서는 0이다 — 손이 시작점으로 순간이동하는 것을 감춘다.
     */
    fun alphaAt(phase: Float): Float {
        val wrapped = phase - floor(phase)
        if (wrapped > STROKE_FRACTION) return 0f
        val t = wrapped / STROKE_FRACTION
        // 0 -> 1 -> 0. 양 끝에서 부드럽게 나타나고 사라진다.
        return (0.5 - 0.5 * cos(t * 2.0 * Math.PI)).toFloat()
    }

    /**
     * 애니메이터가 꺼진 기기에서 그릴 한 장의 위상.
     *
     * **이동 중간이 아니라 끝점 가까이를 고른다.** 중간에서 멈추면 그냥 손이 떠 있는 그림이라
     * 어느 방향인지 읽히지 않는다. 끝점이면 시작 위치와의 차이가 방향을 말해 준다
     * (#142에서 로딩 점의 정지 프레임을 고를 때와 같은 판단).
     */
    const val STATIC_PHASE = STROKE_FRACTION * 0.75f

    /** 정지 프레임에서도 손이 보여야 하므로 진하기의 하한을 둔다. */
    fun staticAlpha(): Float = maxOf(alphaAt(STATIC_PHASE), MIN_STATIC_ALPHA)

    /** 두 방향이 서로 반대인가. 회귀 테스트가 뒤집힘을 잡는 데 쓴다. */
    fun isOpposite(a: SwipeDirection, b: SwipeDirection): Boolean {
        val first = travelFor(a, 1f)
        val second = travelFor(b, 1f)
        return abs(first.dx + second.dx) < 0.001f &&
            abs(first.dy + second.dy) < 0.001f &&
            (abs(first.dx) > 0.001f || abs(first.dy) > 0.001f)
    }

    // ── 조정 지점 ─────────────────────────────────────────────────────────────
    // ReadingBorder의 규약을 따른다 — dp가 아니라 시간·비율이라 dimens.xml로 빼지 않는다.
    //
    // 주기는 여기 없다. 읽는 중 테두리·강조와 **같은 애니메이터**를 쓰므로
    // ReadingBorder.PERIOD_MS가 그대로 적용된다(#147 결정 — 새 구동부를 만들지 않는다).
    // STROKE_FRACTION도 이 묶음이지만 초기화 순서 때문에 파일 위쪽에 있다.

    private const val MIN_STATIC_ALPHA = 0.85f
}
