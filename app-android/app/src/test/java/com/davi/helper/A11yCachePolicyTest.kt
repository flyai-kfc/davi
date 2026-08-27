package com.davi.helper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 노드 캐시 판정(#427 2단계). 플랫폼 호출을 뺀 순수 로직이라 JVM으로 지킨다 —
 * [SampleSchedule]·[EdgeGesture]와 같은 결이다.
 */
class A11yCachePolicyTest {

    private val policy = A11yCachePolicy(silenceSamples = 3)

    @Test
    fun `처음 보는 앱은 캐시를 켠 채로 둔다`() {
        // 증거 없이 최악을 가정하지 않는다. 끄고 시작하면 **앱 진입부터 첫 조작까지** 800ms대
        // 직렬화가 메인 스레드를 막는다 — 시니어가 안내를 읽으며 머뭇거리는 그 구간이다.
        assertTrue(policy.onSample("com.korail"))
    }

    @Test
    fun `자격 이벤트 없이 조용하면 끈다`() {
        repeat(3) { assertTrue(policy.onSample("com.korail")) }

        assertFalse(policy.onSample("com.korail"))
    }

    @Test
    fun `이벤트가 오면 다시 켠다`() {
        repeat(4) { policy.onSample("com.korail") }
        assertFalse(policy.onSample("com.korail"))

        policy.onContentEvent("com.korail")

        assertTrue(policy.onSample("com.korail"))
    }

    @Test
    fun `한 건으로 영영 켜지지는 않는다`() {
        // 단조 집합이면 다른 화면의 이벤트 한 건이 그 앱을 영영 「쏘는 앱」으로 만들어
        // 153샘플 정지가 되살아난다(PR #445 리뷰). 카운터는 다시 조용해지면 되돌아온다.
        policy.onContentEvent("com.korail")
        repeat(3) { assertTrue(policy.onSample("com.korail")) }

        assertFalse(policy.onSample("com.korail"))
    }

    @Test
    fun `판정은 앱마다 따로다`() {
        repeat(4) { policy.onSample("com.korail") }
        assertFalse(policy.onSample("com.korail"))

        assertTrue(policy.onSample("com.baemin"))
    }

    @Test
    fun `읽을 창이 없으면 켠다`() {
        repeat(4) { policy.onSample("com.korail") }
        assertFalse(policy.onSample("com.korail"))

        // 다음 앱이 우리가 보지도 않은 앱의 판정을 물려받으면 안 된다.
        assertTrue(policy.onSample(null))
    }

    @Test
    fun `세션이 바뀌면 판정도 지워진다`() {
        repeat(4) { policy.onSample("com.korail") }
        assertFalse(policy.onSample("com.korail"))

        policy.reset()

        assertTrue(policy.onSample("com.korail"))
    }
}
