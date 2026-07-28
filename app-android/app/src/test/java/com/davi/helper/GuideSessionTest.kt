package com.davi.helper

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    private fun currentGeneration(): Long = GuideSession.active()!!.generation

    private fun history(): List<String> = GuideSession.active()?.recentInstructions.orEmpty()

    private fun actionHistory(): List<RecentAction> = GuideSession.active()?.recentActions.orEmpty()

    private fun sampleAction(targetNodeId: String = "n1") =
        GuideAction(
            expectedResult = "목표 화면",
            evidence = "구조화 근거",
            actionType = ActionType.SCROLL,
            direction = SwipeDirection.UP,
            targetNodeId = targetNodeId,
            instruction = "화면을 넘기세요.",
        )

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

        GuideSession.recordInstruction(currentGeneration(), "카카오톡을 누르세요.")
        GuideSession.recordInstruction(currentGeneration(), "  친구 목록을 누르세요.  ")

        assertEquals(
            listOf("카카오톡을 누르세요.", "친구 목록을 누르세요."),
            history(),
        )
    }

    /** 반복 자체가 모델에게 주는 신호다. 중복이라고 지우면 그 신호가 사라진다. */
    @Test
    fun `같은 안내가 반복돼도 그대로 남는다`() {
        GuideSession.start("카카오톡 열기")

        GuideSession.recordInstruction(currentGeneration(), "화면을 넘기세요.")
        GuideSession.recordInstruction(currentGeneration(), "화면을 넘기세요.")

        assertEquals(2, history().size)
    }

    @Test
    fun `상한을 넘으면 오래된 것부터 버린다`() {
        GuideSession.start("카카오톡 열기")

        repeat(13) { GuideSession.recordInstruction(currentGeneration(), "안내 $it") }

        val history = history()
        assertEquals(10, history.size)
        assertEquals("안내 3", history.first())
        assertEquals("안내 12", history.last())
    }

    @Test
    fun `구조화 행동 이력도 순서와 상한을 지킨다`() {
        GuideSession.start("카카오톡 열기")
        val generation = currentGeneration()

        repeat(13) { index ->
            GuideSession.recordAction(
                generation,
                index.toString().padStart(64, '0'),
                sampleAction(targetNodeId = "n$index"),
            )
        }

        assertEquals(10, actionHistory().size)
        assertEquals("n3", actionHistory().first().targetNodeId)
        assertEquals("n12", actionHistory().last().targetNodeId)
    }

    @Test
    fun `화면 서명이 없는 낡은 서버 응답은 행동 이력에 넣지 않는다`() {
        GuideSession.start("카카오톡 열기")

        GuideSession.recordAction(currentGeneration(), null, sampleAction())

        assertTrue(actionHistory().isEmpty())
    }

    @Test
    fun `빈 안내는 기록하지 않는다`() {
        GuideSession.start("카카오톡 열기")

        GuideSession.recordInstruction(currentGeneration(), "   ")

        assertTrue(history().isEmpty())
    }

    /** 이력이 남으면 다음 세션의 프롬프트에 엉뚱한 안내가 섞인다. */
    @Test
    fun `중지 후 같은 목표로 다시 시작해도 이력은 비어 있다`() {
        GuideSession.start("카카오톡 열기")
        GuideSession.recordInstruction(currentGeneration(), "카카오톡을 누르세요.")
        GuideSession.recordAction(currentGeneration(), "a".repeat(64), sampleAction())

        GuideSession.stop()
        GuideSession.start("카카오톡 열기")

        assertTrue(history().isEmpty())
        assertTrue(actionHistory().isEmpty())
    }

    @Test
    fun `새 목표로 시작하면 이력이 비워진다`() {
        GuideSession.start("카카오톡 열기")
        GuideSession.recordInstruction(currentGeneration(), "카카오톡을 누르세요.")

        GuideSession.start("카메라 열기")

        assertTrue(history().isEmpty())
    }

    // --- 늦게 도착한 응답 (PR #44 리뷰) ------------------------------------------
    //
    // 목표 문자열 비교만으로는 못 거른다. 중지 후 **같은 목표로** 다시 시작하면 문자열이 같아
    // 이전 세션의 응답이 그대로 통과한다 — 이전 화면 bounds로 강조하거나, done이면 새 세션을 끝낸다.

    @Test
    fun `중지 후 같은 목표로 다시 시작하면 세대가 달라진다`() {
        GuideSession.start("카카오톡 열기")
        val before = currentGeneration()

        GuideSession.stop()
        GuideSession.start("카카오톡 열기")

        assertEquals("카카오톡 열기", GuideSession.goal)
        assertNotEquals(before, currentGeneration())
    }

    @Test
    fun `이전 세대는 더 이상 현재 세션이 아니다`() {
        GuideSession.start("카카오톡 열기")
        val stale = currentGeneration()

        GuideSession.stop()
        GuideSession.start("카카오톡 열기")

        assertFalse(GuideSession.isCurrent(stale))
        assertTrue(GuideSession.isCurrent(currentGeneration()))
    }

    /** 늦게 도착한 이전 세션의 안내가 새 세션 이력에 섞이면 안 된다. */
    @Test
    fun `이전 세대의 안내는 기록되지 않는다`() {
        GuideSession.start("카카오톡 열기")
        val stale = currentGeneration()
        GuideSession.stop()
        GuideSession.start("카카오톡 열기")

        GuideSession.recordInstruction(stale, "이전 세션의 안내입니다.")

        assertTrue(history().isEmpty())
    }

    /** 늦은 응답의 행동도 새 세션 서버 가드 이력에 섞이면 안 된다. */
    @Test
    fun `이전 세대의 행동은 기록되지 않는다`() {
        GuideSession.start("카카오톡 열기")
        val stale = currentGeneration()
        GuideSession.stop()
        GuideSession.start("카카오톡 열기")

        GuideSession.recordAction(stale, "b".repeat(64), sampleAction())

        assertTrue(actionHistory().isEmpty())
    }

    /** 세션이 없는 동안 도착한 응답도 버린다. */
    @Test
    fun `중지된 뒤에는 어떤 세대도 현재가 아니다`() {
        GuideSession.start("카카오톡 열기")
        val generation = currentGeneration()

        GuideSession.stop()

        assertFalse(GuideSession.isCurrent(generation))
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
