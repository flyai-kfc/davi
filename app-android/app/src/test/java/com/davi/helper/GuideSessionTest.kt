package com.davi.helper

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `GuideSession`은 object라 테스트끼리 상태를 공유한다. 앞뒤로 반드시 비운다.
 * (그래서 이 클래스는 Android 타입에 의존하지 않는다 — 세션 전이를 JVM에서 고정하려고 그렇게 뒀다.)
 */
class GuideSessionTest {

    @Before
    @After
    fun reset() {
        GuideSession.stop()
    }

    @Test
    fun `시작하면 공백을 제거한 목표가 남는다`() {
        GuideSession.start("  카카오톡 열기  ")

        assertEquals("카카오톡 열기", GuideSession.goal)
        assertTrue(GuideSession.isActive)
    }

    /** 목표는 서버 프롬프트의 첫 줄이다. 공백이면 안내가 성립하지 않으므로 시작하지 않는다. */
    @Test
    fun `빈 목표로는 시작하지 않는다`() {
        GuideSession.start("   ")

        assertNull(GuideSession.goal)
        assertFalse(GuideSession.isActive)
    }

    /** 세션이 없는 동안 서비스는 화면을 읽지 않는다. 그 판정이 이 값 하나에 걸려 있다. */
    @Test
    fun `중지하면 목표가 사라진다`() {
        GuideSession.start("카카오톡 열기")

        GuideSession.stop()

        assertNull(GuideSession.goal)
        assertFalse(GuideSession.isActive)
    }

    @Test
    fun `리스너가 시작과 종료를 모두 본다`() {
        val seen = mutableListOf<String?>()
        val listener = GuideSession.Listener { seen += it }
        GuideSession.addListener(listener)

        try {
            GuideSession.start("카카오톡 열기")
            GuideSession.stop()
        } finally {
            GuideSession.removeListener(listener)
        }

        assertEquals(listOf("카카오톡 열기", null), seen)
    }

    /** 같은 목표로 다시 시작해도 알리지 않는다. 매 이벤트마다 오버레이를 다시 그리지 않으려고. */
    @Test
    fun `상태가 그대로면 리스너를 부르지 않는다`() {
        GuideSession.start("카카오톡 열기")
        val seen = mutableListOf<String?>()
        val listener = GuideSession.Listener { seen += it }
        GuideSession.addListener(listener)

        try {
            GuideSession.start("카카오톡 열기")
        } finally {
            GuideSession.removeListener(listener)
        }

        assertTrue(seen.isEmpty())
    }

    @Test
    fun `안내 이력이 순서대로 쌓인다`() {
        GuideSession.start("카카오톡 열기")

        GuideSession.recordInstruction("카카오톡을 누르세요.")
        GuideSession.recordInstruction("  친구 목록을 누르세요.  ")

        assertEquals(
            listOf("카카오톡을 누르세요.", "친구 목록을 누르세요."),
            GuideSession.recentInstructions(),
        )
    }

    /** 반복 자체가 모델에게 주는 신호다. 중복이라고 지우면 그 신호가 사라진다. */
    @Test
    fun `같은 안내가 반복돼도 그대로 남는다`() {
        GuideSession.start("카카오톡 열기")

        GuideSession.recordInstruction("화면을 넘기세요.")
        GuideSession.recordInstruction("화면을 넘기세요.")

        assertEquals(2, GuideSession.recentInstructions().size)
    }

    @Test
    fun `상한을 넘으면 오래된 것부터 버린다`() {
        GuideSession.start("카카오톡 열기")

        repeat(13) { GuideSession.recordInstruction("안내 $it") }

        val history = GuideSession.recentInstructions()
        assertEquals(10, history.size)
        assertEquals("안내 3", history.first())
        assertEquals("안내 12", history.last())
    }

    @Test
    fun `빈 안내는 기록하지 않는다`() {
        GuideSession.start("카카오톡 열기")

        GuideSession.recordInstruction("   ")

        assertTrue(GuideSession.recentInstructions().isEmpty())
    }

    /** 이력이 남으면 다음 목표의 프롬프트에 엉뚱한 안내가 섞인다. */
    @Test
    fun `중지하면 이력도 비워진다`() {
        GuideSession.start("카카오톡 열기")
        GuideSession.recordInstruction("카카오톡을 누르세요.")

        GuideSession.stop()

        assertTrue(GuideSession.recentInstructions().isEmpty())
    }

    @Test
    fun `새 목표로 시작하면 이력이 비워진다`() {
        GuideSession.start("카카오톡 열기")
        GuideSession.recordInstruction("카카오톡을 누르세요.")

        GuideSession.start("카메라 열기")

        assertTrue(GuideSession.recentInstructions().isEmpty())
    }

    @Test
    fun `리스너를 떼면 더 이상 받지 않는다`() {
        val seen = mutableListOf<String?>()
        val listener = GuideSession.Listener { seen += it }
        GuideSession.addListener(listener)
        GuideSession.removeListener(listener)

        GuideSession.start("카카오톡 열기")

        assertTrue(seen.isEmpty())
    }
}
