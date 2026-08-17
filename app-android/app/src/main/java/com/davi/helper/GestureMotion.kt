package com.davi.helper

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor

/**
 * 방향 제스처 시연(GD-2)의 **판정과 위치 계산**(#152). Android 타입을 쓰지 않아 JVM 테스트가 닿는다.
 * [ReadingBorder]·[HighlightMotion]과 같은 자리·같은 규약이다.
 *
 * **서버가 보내는 방향을 뒤집어 그린다**(2026-08-14 결정, 2026-08-17 실기기 재확인).
 * 원래는 뒤집지 않는 것이 맞다 — 계약(`server/app/schemas.py`)의 `SwipeDirection`은 "손가락이
 * 움직이는 방향"이고, "무엇이 보이게 되는가 → 손가락이 어디로"라는 변환은 서버가 끝냈어야 한다.
 *
 * **그런데 서버가 실제로는 내용이 움직일 방향을 보낸다.** 실기기 실측(SM-S921N):
 * 설정 목록에서 아래쪽 항목을 찾아야 하는데 `down`이 왔다(계약대로면 `up`).
 * 원인과 고칠 자리는 #109에 정리돼 있으나 **서버에서 고치지 않기로 했고**, 화면에는 방향이
 * 하나로 못박혀 그려지므로 여기서 보정한다.
 *
 * PR #159 리뷰는 화면 상태(`next`/`prev_swipe_direction`)와 대조할 때만 뒤집는 안을 제안했고
 * 한번 그렇게 고쳤으나, **실기기에서 모션이 통째로 반대가 되어 되돌렸다**(2026-08-17).
 * 조건부 보정을 다시 시도한다면 실기기 확인이 먼저다.
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

    /** 정반대 방향. 보정과 회귀 테스트가 함께 쓴다. */
    fun opposite(direction: SwipeDirection): SwipeDirection = when (direction) {
        SwipeDirection.UP -> SwipeDirection.DOWN
        SwipeDirection.DOWN -> SwipeDirection.UP
        SwipeDirection.LEFT -> SwipeDirection.RIGHT
        SwipeDirection.RIGHT -> SwipeDirection.LEFT
    }

    /**
     * 서버가 준 방향 → **실제로 그릴 손가락 방향**. 넷 모두 뒤집는다.
     *
     * **이것이 유일한 보정 지점이다.** 서버(#109)가 계약대로 손가락 방향을 보내기 시작하면 이
     * 함수를 항등(`serverDirection`을 그대로 반환)으로 바꾸는 것만으로 끝난다 — 뒤집기를
     * [travelFor]나 뷰에 흩어 놓지 않은 이유다.
     *
     * 되돌려야 하는 신호: 스크롤 안내에서 손이 사용자가 실제로 해야 하는 동작과 반대로 움직인다.
     */
    fun fingerDirectionFor(serverDirection: SwipeDirection): SwipeDirection = opposite(serverDirection)

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
     * 손 모션이 놓일 **세로 중심**. 안내창과 겹치지 않는 자리를 고른다(2026-08-17 요청).
     *
     * 손은 화면 중앙을 지나가도록 그리는 것이 기본이지만, 안내창이 그 자리를 쓰면 글이 손에
     * 가린다. 그래서 안내창 **위/아래로 남은 공간 중 넓은 쪽**을 골라 그 안에서 가운데를 잡는다.
     *
     * @param canvasTop·canvasBottom 그릴 수 있는 세로 범위.
     * @param bandHeight 손이 훑고 지나가는 세로 폭(이동 거리 + 손 높이). 이만큼은 비어 있어야 한다.
     * @param boxTop·boxBottom 안내창의 세로 범위. 안내창이 없으면 둘 다 null이다.
     * @return 손 모션의 중심 y.
     *
     * 남은 공간이 [bandHeight]보다 좁으면 넓은 쪽 가운데에 그대로 둔다 — 완전히 피할 수 없는
     * 화면에서 억지로 밀어내면 손이 화면 밖으로 나가 아예 안 보인다. 겹치더라도 보이는 편이 낫다.
     */
    fun verticalCenterFor(
        canvasTop: Float,
        canvasBottom: Float,
        bandHeight: Float,
        boxTop: Float?,
        boxBottom: Float?,
    ): Float {
        val canvasCenter = (canvasTop + canvasBottom) / 2f
        if (boxTop == null || boxBottom == null) return canvasCenter

        val above = boxTop - canvasTop
        val below = canvasBottom - boxBottom
        // 손이 다 들어가는 쪽이 하나뿐이면 그쪽을 고른다. 둘 다 되거나 둘 다 안 되면 넓은 쪽이다.
        val useAbove = when {
            above >= bandHeight && below < bandHeight -> true
            below >= bandHeight && above < bandHeight -> false
            else -> above >= below
        }
        return if (useAbove) (canvasTop + boxTop) / 2f else (boxBottom + canvasBottom) / 2f
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
     * 어느 방향인지 읽히지 않는다.
     *
     * 끝점 하나만으로는 부족하다 — 비교할 기준이 없으면 "손이 떠 있는 그림"과 구분되지 않는다
     * (PR #159 리뷰 5번). 그래서 정지 프레임은 [STATIC_GHOST_PROGRESS] 위치에 옅은 잔상을
     * 함께 그려 **두 장의 차이**로 방향을 말한다.
     */
    const val STATIC_PHASE = STROKE_FRACTION * 0.75f

    /** 정지 프레임에서도 손이 보여야 하므로 진하기의 하한을 둔다. */
    fun staticAlpha(): Float = maxOf(alphaAt(STATIC_PHASE), MIN_STATIC_ALPHA)

    /** 정지 프레임의 잔상을 놓을 자리(0=출발). 본체와 충분히 떨어져야 방향이 읽힌다. */
    const val STATIC_GHOST_PROGRESS = 0f

    /**
     * 정지 프레임 잔상의 진하기. 본체보다 확실히 옅어야 **어느 쪽이 도착점인지** 읽힌다 —
     * 둘이 비슷하면 어느 손이 나중인지 알 수 없어 방향이 다시 모호해진다.
     */
    fun staticGhostAlpha(): Float = staticAlpha() * GHOST_ALPHA_RATIO

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

    /** 잔상은 본체의 이 비율만큼만 진하다. */
    private const val GHOST_ALPHA_RATIO = 0.35f
}
