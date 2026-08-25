package com.davi.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 안내 낭독의 "무엇을 읽을 것인가"(#450).
 *
 * 이 테스트가 지키는 것은 **표시 깔때기가 같은 내용을 반복해 지나간다**는 사실이다 —
 * `GuideAccessibilityService.draw`는 서버 안내뿐 아니라 재표시·강조 내림/되돌림·오류 재시도까지
 * 전부 통과하는 한 자리라, 문구 대조가 없으면 같은 문장이 폴링마다 다시 나간다.
 */
class GuideSpeechGateTest {

    private val gate = GuideSpeechGate()

    @Test
    fun `새 안내는 읽는다`() {
        assertEquals("여기를 눌러 주세요", gate.next("여기를 눌러 주세요"))
    }

    @Test
    fun `같은 안내가 다시 그려져도 다시 읽지 않는다`() {
        gate.next("여기를 눌러 주세요")
        assertNull(gate.next("여기를 눌러 주세요"))
        assertNull(gate.next("여기를 눌러 주세요"))
    }

    @Test
    fun `줄바꿈은 한 줄로 이어 읽는다`() {
        assertEquals("첫 줄 둘째 줄", gate.next("첫 줄\n둘째 줄"))
    }

    @Test
    fun `줄바꿈만 다른 같은 문구는 다시 읽지 않는다`() {
        gate.next("첫 줄\n둘째 줄")
        assertNull(gate.next("첫 줄 둘째 줄"))
    }

    /**
     * 정체 접미사([GuideDisplayState.stuckContent])는 원문 뒤에 한 줄을 이어 붙인다.
     * 화면이 안 바뀌어 답답한 사람에게 같은 문장을 통째로 한 번 더 들려주지 않는다.
     */
    @Test
    fun `덧붙은 안내는 덧붙은 부분만 읽는다`() {
        gate.next("여기를 눌러 주세요")
        assertEquals(
            "화면이 그대로예요. 한 번 더 해 주세요.",
            gate.next("여기를 눌러 주세요\n화면이 그대로예요. 한 번 더 해 주세요."),
        )
    }

    @Test
    fun `덧붙은 뒤에도 같은 문구는 다시 읽지 않는다`() {
        gate.next("여기를 눌러 주세요")
        gate.next("여기를 눌러 주세요\n한 번 더 해 주세요.")
        assertNull(gate.next("여기를 눌러 주세요\n한 번 더 해 주세요."))
    }

    @Test
    fun `다른 안내로 바뀌면 통째로 읽는다`() {
        gate.next("여기를 눌러 주세요")
        assertEquals("아래로 넘겨 주세요", gate.next("아래로 넘겨 주세요"))
    }

    @Test
    fun `빈 문구는 읽지 않고 떠 있는 안내도 밀어내지 않는다`() {
        gate.next("여기를 눌러 주세요")
        assertNull(gate.next("   "))
        assertEquals("여기를 눌러 주세요", gate.repeat())
    }

    /** 다시 듣기는 델타가 아니라 **지금 떠 있는 문장 전부**가 답이다. */
    @Test
    fun `다시 듣기는 덧붙은 뒤에도 전체를 돌려준다`() {
        gate.next("여기를 눌러 주세요")
        gate.next("여기를 눌러 주세요\n한 번 더 해 주세요.")
        assertEquals("여기를 눌러 주세요 한 번 더 해 주세요.", gate.repeat())
    }

    @Test
    fun `읽은 것이 없으면 다시 들을 것도 없다`() {
        assertNull(gate.repeat())
    }

    @Test
    fun `세션이 바뀌면 같은 문구라도 다시 읽는다`() {
        gate.next("여기를 눌러 주세요")
        gate.reset()
        assertNull(gate.repeat())
        assertEquals("여기를 눌러 주세요", gate.next("여기를 눌러 주세요"))
    }

    /**
     * 화면이 꺼지거나 전화가 들어와 문장 중간에 끊기면, 같은 안내가 다시 떠도 문구가 같다는
     * 이유로 영영 안 읽히는 구멍이 생긴다 — 하필 소리에 기대는 사람이 반 토막만 듣는다.
     */
    @Test
    fun `읽다 끊긴 안내는 다시 그려질 때 처음부터 읽는다`() {
        gate.next("여기를 눌러 주세요")
        gate.markInterrupted()
        assertEquals("여기를 눌러 주세요", gate.next("여기를 눌러 주세요"))
    }

    @Test
    fun `끊겼다 다시 읽은 뒤에는 또 읽지 않는다`() {
        gate.next("여기를 눌러 주세요")
        gate.markInterrupted()
        gate.next("여기를 눌러 주세요")
        assertNull(gate.next("여기를 눌러 주세요"))
    }

    /** 앞을 못 들은 사람에게 덧붙은 한마디만 주면 말이 안 된다. */
    @Test
    fun `끊긴 뒤 접미사가 붙으면 통째로 읽는다`() {
        gate.next("여기를 눌러 주세요")
        gate.markInterrupted()
        assertEquals(
            "여기를 눌러 주세요 한 번 더 해 주세요.",
            gate.next("여기를 눌러 주세요\n한 번 더 해 주세요."),
        )
    }

    @Test
    fun `다시 듣기로 들려준 안내는 다시 그려질 때 또 읽지 않는다`() {
        gate.next("여기를 눌러 주세요")
        gate.markInterrupted()
        assertEquals("여기를 눌러 주세요", gate.repeat())
        assertNull(gate.next("여기를 눌러 주세요"))
    }

    /**
     * 정규화를 정규식 대신 [Char.isWhitespace]로 하는 이유가 이 줄이다 — 기기 정규식(ICU)의
     * `\s`는 NBSP를 접고 JVM의 `\s`는 안 접어, 이 테스트가 고정하는 것과 기기 동작이 갈린다.
     * `isWhitespace`는 양쪽 다 `Character.isWhitespace() || isSpaceChar()`라 같은 정의를 본다.
     */
    @Test
    fun `NBSP도 보통 공백으로 접는다`() {
        assertEquals("첫 줄 둘째 줄", gate.next("첫 줄\u00A0둘째 줄"))
    }

    @Test
    fun `앞뒤 공백은 턴다`() {
        assertEquals("여기를 눌러 주세요", gate.next("\n  여기를 눌러 주세요  \n"))
    }

    /**
     * 억제(통화 중 등)는 **읽지 않는다**이지 **나중에 읽는다**가 아니다. 상태를 세워 두지 않으면
     * 통화가 끝난 뒤 접두 일치로 지나간 안내의 뒷부분만 읽는 조합이 생긴다 — 그래서 게이트는
     * 억제를 모른 채 늘 전진하고, 억제를 아는 쪽이 결과를 버린다.
     */
    @Test
    fun `버려진 발화도 상태를 전진시킨다`() {
        gate.next("여기를 눌러 주세요") // 호출자가 억제로 버렸다고 치자
        assertNull(gate.next("여기를 눌러 주세요"))
    }
}
