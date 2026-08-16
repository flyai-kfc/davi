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

    /** 서버가 label 없이 만들어 준 화면 서명. 값 자체에는 의미가 없고 같은지 다른지만 본다. */
    private val SIGNATURE = "screen-a"
    private val OTHER_SIGNATURE = "screen-b"

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

    // --- done 판정 (#55, #139) ---------------------------------------------------
    //
    // `verified=false`인 done은 조기 종료일 수 있어 종료 신호로 쓰지 않는다(#55).
    // `verified=true`인 done도 이제 세션을 끝내지 않는다 — 성공 오탐을 사용자에게 한 번
    // 묻고, 종료는 "됐어요"만이 만든다(#139 GD-3).

    @Test
    fun `검증된 done은 세션을 끝내지 않고 확인을 기다린다`() {
        GuideSession.start("카메라 열기")

        val outcome = GuideSession.onDone(currentGeneration(), verified = true, screenSignature = SIGNATURE)

        assertEquals(GuideSession.DoneOutcome.CONFIRM_CONFIDENT, outcome)
        assertTrue(GuideSession.isActive)
        assertTrue(GuideSession.awaitingConfirm)
    }

    @Test
    fun `됐어요를 누르면 세션이 끝난다`() {
        GuideSession.start("카메라 열기")
        GuideSession.onDone(currentGeneration(), verified = true, screenSignature = SIGNATURE)

        GuideSession.confirmAccepted()

        assertFalse(GuideSession.isActive)
        assertFalse(GuideSession.awaitingConfirm)
    }

    @Test
    fun `아직요를 누르면 세션이 유지되고 대기가 풀린다`() {
        GuideSession.start("카메라 열기")
        GuideSession.onDone(currentGeneration(), verified = true, screenSignature = SIGNATURE)

        GuideSession.confirmRejected()

        assertTrue(GuideSession.isActive)
        assertFalse(GuideSession.awaitingConfirm)
    }

    /** 부정한 화면이 done을 또 줘도 같은 질문을 다시 하지 않는다 — 확인 화면 무한 반복 방지. */
    @Test
    fun `부정한 화면의 done은 확인을 다시 띄우지 않는다`() {
        GuideSession.start("카메라 열기")
        val generation = currentGeneration()
        GuideSession.onDone(generation, verified = true, screenSignature = SIGNATURE)
        GuideSession.confirmRejected()

        assertEquals(
            GuideSession.DoneOutcome.RETRY,
            GuideSession.onDone(generation, verified = true, screenSignature = SIGNATURE),
        )
        assertFalse(GuideSession.awaitingConfirm)
    }

    /** 재질의가 무한정 돌지 않게 상한은 그대로 걸린다. 다만 확인 화면이 아니라 문구로 끝난다. */
    @Test
    fun `부정한 화면에서 done이 상한까지 반복되면 문구만 남긴다`() {
        GuideSession.start("카메라 열기")
        val generation = currentGeneration()
        GuideSession.onDone(generation, verified = true, screenSignature = SIGNATURE)
        GuideSession.confirmRejected()

        assertEquals(
            GuideSession.DoneOutcome.RETRY,
            GuideSession.onDone(generation, verified = true, screenSignature = SIGNATURE),
        )
        assertEquals(
            GuideSession.DoneOutcome.RETRY,
            GuideSession.onDone(generation, verified = true, screenSignature = SIGNATURE),
        )
        assertEquals(
            GuideSession.DoneOutcome.NOTICE_ONLY,
            GuideSession.onDone(generation, verified = true, screenSignature = SIGNATURE),
        )
        assertTrue(GuideSession.isActive)
    }

    /** 다른 화면으로 진행했다면 부정 이력은 그 화면의 것이 아니다 — 다시 확인을 받는다. */
    @Test
    fun `부정 뒤 다른 화면의 검증된 done은 다시 확인을 띄운다`() {
        GuideSession.start("카메라 열기")
        val generation = currentGeneration()
        GuideSession.onDone(generation, verified = true, screenSignature = SIGNATURE)
        GuideSession.confirmRejected()

        assertEquals(
            GuideSession.DoneOutcome.CONFIRM_CONFIDENT,
            GuideSession.onDone(generation, verified = true, screenSignature = OTHER_SIGNATURE),
        )
    }

    @Test
    fun `미검증 done은 세션을 끝내지 않는다`() {
        GuideSession.start("카메라 열기")

        val outcome = GuideSession.onDone(currentGeneration(), verified = false, screenSignature = SIGNATURE)

        assertEquals(GuideSession.DoneOutcome.RETRY, outcome)
        assertTrue(GuideSession.isActive)
        assertFalse(GuideSession.awaitingConfirm)
    }

    /** 상한에서도 done으로 끝내지 않는다 — 막힘을 성공으로 오인시킨다(C11과 같은 근거). */
    @Test
    fun `미검증 done이 상한에 닿으면 확인을 띄우고 세션은 유지된다`() {
        GuideSession.start("카메라 열기")
        val generation = currentGeneration()

        assertEquals(GuideSession.DoneOutcome.RETRY, GuideSession.onDone(generation, false, SIGNATURE))
        assertEquals(GuideSession.DoneOutcome.RETRY, GuideSession.onDone(generation, false, SIGNATURE))
        assertEquals(
            GuideSession.DoneOutcome.CONFIRM_UNSURE,
            GuideSession.onDone(generation, false, SIGNATURE),
        )
        assertTrue(GuideSession.isActive)
        assertTrue(GuideSession.awaitingConfirm)
    }

    /** 상한 뒤에 또 와도(화면이 바뀌어 재질의된 경우) 재시도로 되돌아가지 않는다. */
    @Test
    fun `상한 이후의 미검증 done도 사용자에게 넘긴다`() {
        GuideSession.start("카메라 열기")
        val generation = currentGeneration()
        repeat(3) { GuideSession.onDone(generation, false, SIGNATURE) }

        assertEquals(
            GuideSession.DoneOutcome.CONFIRM_UNSURE,
            GuideSession.onDone(generation, false, SIGNATURE),
        )
    }

    /** 연속이 아니라 누적으로 세면 긴 세션에서 정상 진행 중에도 상한 알림이 뜬다. */
    @Test
    fun `다른 안내가 표시되면 미검증 done 연속이 끊긴다`() {
        GuideSession.start("카메라 열기")
        val generation = currentGeneration()
        GuideSession.onDone(generation, false, SIGNATURE)
        GuideSession.onDone(generation, false, SIGNATURE)

        GuideSession.recordInstruction(generation, "카메라를 누르세요.")

        assertEquals(GuideSession.DoneOutcome.RETRY, GuideSession.onDone(generation, false, SIGNATURE))
    }

    /** 늦게 도착한 이전 세션의 done은 검증 여부와 무관하게 새 세션을 건드리면 안 된다. */
    @Test
    fun `이전 세대의 done은 판정하지 않는다`() {
        GuideSession.start("카메라 열기")
        val stale = currentGeneration()
        GuideSession.stop()
        GuideSession.start("카메라 열기")

        assertNull(GuideSession.onDone(stale, verified = true, screenSignature = SIGNATURE))
        assertTrue(GuideSession.isActive)
        assertFalse(GuideSession.awaitingConfirm)
    }

    @Test
    fun `중지 후 다시 시작하면 미검증 done 카운트도 비워진다`() {
        GuideSession.start("카메라 열기")
        val before = currentGeneration()
        GuideSession.onDone(before, false, SIGNATURE)
        GuideSession.onDone(before, false, SIGNATURE)

        GuideSession.stop()
        GuideSession.start("카메라 열기")

        assertEquals(
            GuideSession.DoneOutcome.RETRY,
            GuideSession.onDone(currentGeneration(), false, SIGNATURE),
        )
    }

    /** 부정 이력이 세션을 넘어가면, 새 세션의 첫 완료가 확인 없이 지나간다. */
    @Test
    fun `중지 후 다시 시작하면 부정 이력도 비워진다`() {
        GuideSession.start("카메라 열기")
        GuideSession.onDone(currentGeneration(), verified = true, screenSignature = SIGNATURE)
        GuideSession.confirmRejected()

        GuideSession.stop()
        GuideSession.start("카메라 열기")

        assertEquals(
            GuideSession.DoneOutcome.CONFIRM_CONFIDENT,
            GuideSession.onDone(currentGeneration(), verified = true, screenSignature = SIGNATURE),
        )
    }

    /**
     * 화면이 바뀌어도 대기는 그대로다. 답하기 전에 풀리면 답할 자리가 사라지고, 대기 중에는
     * 요청도 안 나가 세션이 갇힌다(PR #153 리뷰 P1). 표시 쪽 계약은
     * `GuideDisplayStateTest`가 고정한다.
     */
    @Test
    fun `화면이 바뀌어도 확인 대기는 유지된다`() {
        GuideSession.start("카메라 열기")
        val generation = currentGeneration()
        GuideSession.onDone(generation, true, SIGNATURE)

        GuideSession.recordInstruction(generation, "다른 화면의 안내")

        assertTrue(GuideSession.awaitingConfirm)
        assertTrue(GuideSession.isActive)
    }

    /**
     * 부정 이력의 유효기간 — 안내가 한 번 나가면 끝난다(PR #153 리뷰 P2).
     *
     * 없으면 이렇게 된다: 화면 A에서 ×아직요 → 안내를 따라 다른 화면에서 작업을 끝내고 A로
     * 복귀 → 그 화면의 요청이 나가 완료 신호 도착 → **A가 아직 부정 이력에 있어** 성공 확인
     * 대신 "확인하지 못했어요". 실제로 달성했는데 성공 표시를 못 받는다.
     */
    @Test
    fun `안내가 나가면 부정 이력이 풀린다`() {
        GuideSession.start("알람 끄기")
        val generation = currentGeneration()
        GuideSession.onDone(generation, true, SIGNATURE)
        GuideSession.confirmRejected()

        GuideSession.recordInstruction(generation, "알람을 눌러 보세요.")

        assertEquals(
            GuideSession.DoneOutcome.CONFIRM_CONFIDENT,
            GuideSession.onDone(generation, true, SIGNATURE),
        )
    }

    /**
     * ×아직요 뒤 같은 화면이 done을 **반복해서** 줘도 확인 화면이 다시 발행되지 않는다.
     *
     * 1회만 보던 커버리지에 구멍이 있었다(PR #153 리뷰) — 서명이 선택 필드이던 동안에는 이
     * 반복 경로에서 `CONFIRM_CONFIDENT`가 매번 재발행됐다. 서명을 required로 좁혀 근원을
     * 지웠고, 그 사실을 여기서 고정한다.
     */
    @Test
    fun `부정 뒤 같은 화면의 done이 반복돼도 확인은 재발행되지 않는다`() {
        GuideSession.start("카메라 열기")
        val generation = currentGeneration()
        GuideSession.onDone(generation, true, SIGNATURE)
        GuideSession.confirmRejected()

        val outcomes = (1..GuideSession.MAX_UNVERIFIED_DONE).map {
            GuideSession.onDone(generation, true, SIGNATURE)
        }

        assertEquals(
            listOf(
                GuideSession.DoneOutcome.RETRY,
                GuideSession.DoneOutcome.RETRY,
                GuideSession.DoneOutcome.NOTICE_ONLY,
            ),
            outcomes,
        )
        assertFalse(GuideSession.awaitingConfirm)
        assertTrue(GuideSession.isActive)
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
