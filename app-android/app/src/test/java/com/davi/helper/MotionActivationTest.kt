package com.davi.helper

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #249. PR #243이 지운 "모션 끄기 × 지연" 가드의 절반.
 *
 * **구동이 실제로 도는가와 전이 훅이 오는가는 다른 축이다.** 둘을 붙이면 모션이 꺼진 기기에서
 * 훅이 영영 오지 않아 로딩 점 지연이 arm되지 않고 화면이 빈다(#144). 그 결합을 여기서 막는다.
 *
 * 짝이 되는 가드는 [LoadingDotsGateTest]다.
 *
 * **단정을 이벤트 한 줄로 모으는 이유**는 순서까지 고정하기 위해서다 — 시작 횟수와 훅 횟수를
 * 정수 둘로 세면 "훅을 구동 조작 앞으로 옮기는" 회귀가 초록으로 통과한다.
 */
class MotionActivationTest {

    private val events = mutableListOf<String>()

    /** 모션이 꺼진 기기의 [MotionDriver]를 흉내 낸다 — 정지 프레임 한 장만 흘리고 루프는 안 돈다. */
    private val activation = MotionActivation(
        onStart = { events += "start" },
        onStop = { events += "stop" },
        onTransition = { active -> events += "transition:$active" },
    )

    // --- 전이 판정 ----------------------------------------------------------------

    @Test
    fun `전이일 때만 훅이 온다`() {
        activation.sync(true)
        activation.sync(true)
        activation.sync(true)

        assertEquals(listOf("start", "transition:true"), events)
    }

    @Test
    fun `조건이 유지되는 동안 가시성 이벤트가 여러 번 와도 다시 시작하지 않는다`() {
        // 모션 끄기에서는 driver.running이 서지 않아 start()의 중복 가드가 안 걸린다.
        // 여기서 거르지 않으면 정지 프레임이 이벤트마다 재방출돼 "한 번만" 계약이 깨진다(#148).
        repeat(5) { activation.sync(true) }

        assertEquals(1, events.count { it == "start" })
    }

    @Test
    fun `처음부터 거짓이면 아무 일도 하지 않는다`() {
        // onDetachedFromWindow는 syncDriver를 우회해 곧장 거짓을 넘긴다 — 한 번도 활성인 적이
        // 없었으면 중복 stop도, 훅도 나가면 안 된다.
        activation.sync(false)

        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun `참과 거짓을 오가면 매번 훅이 온다`() {
        activation.sync(true)
        activation.sync(false)
        activation.sync(true)

        assertEquals(2, events.count { it == "transition:true" })
        assertEquals(1, events.count { it == "transition:false" })
    }

    // --- 모션 끄기 x 훅 (PR #243 리뷰 회귀) ------------------------------------------

    @Test
    fun `모션이 꺼져 있어 시작이 아무 일도 안 해도 전이 훅은 온다`() {
        // 이 테스트가 지키는 것: 훅을 "구동이 실제로 돌기 시작했는가"에 걸지 않는다.
        // 걸면 모션 끄기에서 훅이 안 와 지연이 영영 arm되지 않고 점이 빈 화면이 된다(#144).
        activation.sync(true)

        assertEquals(listOf("start", "transition:true"), events)
    }

    @Test
    fun `구동 조작이 전이 훅보다 먼저다`() {
        // 모션 끄기의 정지 프레임 한 장이 훅보다 먼저 흘러야, 훅에서 되감는 쪽이 최신 프레임
        // 시각을 보고 판단한다([LoadingDotsGate.reveal]).
        activation.sync(true)

        assertEquals(0, events.indexOf("start"))
        assertEquals(1, events.indexOf("transition:true"))
    }

    @Test
    fun `참에서 거짓으로 가면 정지 뒤에 훅이 온다`() {
        activation.sync(true)
        events.clear()

        activation.sync(false)

        assertEquals(listOf("stop", "transition:false"), events)
    }
}
