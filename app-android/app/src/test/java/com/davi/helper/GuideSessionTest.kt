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

    private fun steps(): List<GuideStep> = GuideSession.active()?.steps.orEmpty()

    private fun history(): List<String> = steps().map { it.instruction }

    private fun sampleStep(
        instruction: String = "화면을 넘기세요.",
        actionType: ActionType = ActionType.SCROLL,
        targetLabel: String? = "카카오톡",
        screenHint: String? = "런처 홈 화면",
        structureSignature: String = "a".repeat(64),
    ) = GuideStep(
        instruction = instruction,
        actionType = actionType,
        direction = SwipeDirection.UP.takeIf { actionType == ActionType.SCROLL },
        targetLabel = targetLabel,
        screenHint = screenHint,
        structureSignature = structureSignature,
    )

    /** 안내 하나를 기록하는 짧은 손잡이. 화면 시그니처를 안 쓰는 테스트가 대부분이다. */
    private fun record(
        generation: Long = currentGeneration(),
        instruction: String = "화면을 넘기세요.",
        actionType: ActionType = ActionType.SCROLL,
        screenSignature: String = SIGNATURE,
    ) = GuideSession.recordStep(
        generation,
        screenSignature,
        sampleStep(instruction = instruction, actionType = actionType),
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

        record(instruction = "카카오톡을 누르세요.")
        record(instruction = "  친구 목록을 누르세요.  ")

        assertEquals(
            listOf("카카오톡을 누르세요.", "친구 목록을 누르세요."),
            history(),
        )
    }

    /** 앱은 값을 나르기만 한다 — 응답 echo가 스텝에 그대로 실려야 서버가 문장을 만들 수 있다. */
    @Test
    fun `스텝은 응답 echo 값을 그대로 나른다`() {
        GuideSession.start("카카오톡 열기")

        GuideSession.recordStep(currentGeneration(), SIGNATURE, sampleStep())

        val step = steps().single()
        assertEquals(ActionType.SCROLL, step.actionType)
        assertEquals(SwipeDirection.UP, step.direction)
        assertEquals("카카오톡", step.targetLabel)
        assertEquals("런처 홈 화면", step.screenHint)
        assertEquals("a".repeat(64), step.structureSignature)
        // 아직 관측하지 않은 셋. `performed`·`content_signature`는 후속 서브이슈다(#270).
        assertNull(step.screenChanged)
        assertNull(step.performed)
        assertNull(step.contentSignature)
    }

    /** 반복 자체가 모델에게 주는 신호다. 중복이라고 지우면 그 신호가 사라진다. */
    @Test
    fun `같은 안내가 반복돼도 그대로 남는다`() {
        GuideSession.start("카카오톡 열기")

        record()
        record()

        assertEquals(2, history().size)
    }

    @Test
    fun `상한을 넘으면 오래된 것부터 버린다`() {
        GuideSession.start("카카오톡 열기")

        repeat(13) { record(instruction = "안내 $it") }

        val history = history()
        assertEquals(10, history.size)
        assertEquals("안내 3", history.first())
        assertEquals("안내 12", history.last())
    }

    /**
     * 문장이 비어도 **행동은 있었다.** 스텝을 통째로 버리면 그 안내의 조작 종류와 화면 변화까지
     * 함께 사라진다 — 프롬프트에서 빈 줄을 거르는 것은 서버의 몫이다(`prompt._recent_step_lines`).
     */
    @Test
    fun `빈 안내도 스텝으로는 남는다`() {
        GuideSession.start("카카오톡 열기")

        record(instruction = "   ")

        assertEquals(1, steps().size)
        assertEquals("", steps().single().instruction)
    }

    /** 이력이 남으면 다음 세션의 프롬프트에 엉뚱한 안내가 섞인다. */
    @Test
    fun `중지 후 같은 목표로 다시 시작해도 이력은 비어 있다`() {
        GuideSession.start("카카오톡 열기")
        record(instruction = "카카오톡을 누르세요.")

        GuideSession.stop()
        GuideSession.start("카카오톡 열기")

        assertTrue(steps().isEmpty())
    }

    @Test
    fun `새 목표로 시작하면 이력이 비워진다`() {
        GuideSession.start("카카오톡 열기")
        record(instruction = "카카오톡을 누르세요.")

        GuideSession.start("카메라 열기")

        assertTrue(history().isEmpty())
    }

    // --- 화면이 바뀌었는가 (#270) -------------------------------------------------
    //
    // 스텝은 안내를 표시할 때 생기고, 이 값은 **다음 요청을 보내기 직전에** 확정된다.
    // 시간으로 끊으면 느린 사용자가 실패로 적힌다 — 시니어에게 7초는 흔하다.

    @Test
    fun `다른 화면에서 물으면 마지막 스텝이 바뀐 것으로 닫힌다`() {
        GuideSession.start("카카오톡 열기")
        record(screenSignature = SIGNATURE)

        GuideSession.observeScreen(currentGeneration(), OTHER_SIGNATURE)

        assertEquals(true, steps().single().screenChanged)
    }

    /** 같은 화면에서 다시 묻는 중(재시도·미검증 done)이라는 뜻이다. */
    @Test
    fun `같은 화면에서 다시 물으면 그대로였던 것으로 적힌다`() {
        GuideSession.start("카카오톡 열기")
        record(screenSignature = SIGNATURE)

        GuideSession.observeScreen(currentGeneration(), SIGNATURE)

        assertEquals(false, steps().single().screenChanged)
    }

    /** 느린 사용자를 실패로 적지 않는다 — 결국 진행했으면 그것이 사실이다. */
    @Test
    fun `그대로였던 스텝도 화면이 바뀌면 바뀐 것으로 덮인다`() {
        GuideSession.start("카카오톡 열기")
        record(screenSignature = SIGNATURE)
        GuideSession.observeScreen(currentGeneration(), SIGNATURE)

        GuideSession.observeScreen(currentGeneration(), OTHER_SIGNATURE)

        assertEquals(true, steps().single().screenChanged)
    }

    /** A→B→A로 돌아와도 그 안내가 먹혔다는 사실은 그대로다. 회귀는 내용 서명의 몫(#270 서브 4). */
    @Test
    fun `한 번 바뀐 스텝은 되돌아와도 true로 남는다`() {
        GuideSession.start("카카오톡 열기")
        record(screenSignature = SIGNATURE)
        GuideSession.observeScreen(currentGeneration(), OTHER_SIGNATURE)

        GuideSession.observeScreen(currentGeneration(), SIGNATURE)

        assertEquals(true, steps().single().screenChanged)
    }

    /** 새 스텝이 쌓이면 이전 스텝의 판정은 그대로 굳는다. */
    @Test
    fun `이전 스텝의 판정은 새 스텝이 쌓여도 굳는다`() {
        GuideSession.start("카카오톡 열기")
        record(screenSignature = SIGNATURE)
        GuideSession.observeScreen(currentGeneration(), OTHER_SIGNATURE)

        record(screenSignature = OTHER_SIGNATURE)
        GuideSession.observeScreen(currentGeneration(), OTHER_SIGNATURE)

        assertEquals(listOf(true, false), steps().map { it.screenChanged })
    }

    @Test
    fun `스텝이 없으면 화면 관측은 아무것도 하지 않는다`() {
        GuideSession.start("카카오톡 열기")

        GuideSession.observeScreen(currentGeneration(), OTHER_SIGNATURE)

        assertTrue(steps().isEmpty())
    }

    @Test
    fun `이전 세대의 화면 관측은 스텝을 건드리지 않는다`() {
        GuideSession.start("카카오톡 열기")
        record(screenSignature = SIGNATURE)
        val stale = currentGeneration()

        GuideSession.observeScreen(stale + 1, OTHER_SIGNATURE)

        assertNull(steps().single().screenChanged)
    }

    // --- 안내한 대상을 눌렀는가 (#319) -------------------------------------------
    //
    // 판정 시점은 클릭 이벤트가 온 순간이다. 이벤트가 안 오면 영원히 null(귀속 미확인)이고
    // 그것이 설계된 퇴화 경로다 — 미탐은 "안 누름"이 아니다.

    @Test
    fun `대상을 누르면 마지막 스텝에 적힌다`() {
        GuideSession.start("카카오톡 열기")
        record()

        GuideSession.observePress(currentGeneration(), onTarget = true)

        assertEquals(true, steps().single().performed)
    }

    /** 좌표 축의 확신 불일치 — 다른 노드를 눌렀다는 기록이다. */
    @Test
    fun `다른 곳을 누르면 false로 적힌다`() {
        GuideSession.start("카카오톡 열기")
        record()

        GuideSession.observePress(currentGeneration(), onTarget = false)

        assertEquals(false, steps().single().performed)
    }

    /** 빗맞은 뒤 대상을 제대로 누르면 그것이 사실이다 — observeScreen의 false→true와 같은 결. */
    @Test
    fun `빗맞은 스텝도 대상을 누르면 true로 덮인다`() {
        GuideSession.start("카카오톡 열기")
        record()
        GuideSession.observePress(currentGeneration(), onTarget = false)

        GuideSession.observePress(currentGeneration(), onTarget = true)

        assertEquals(true, steps().single().performed)
    }

    @Test
    fun `한 번 눌린 스텝은 빗맞아도 true로 남는다`() {
        GuideSession.start("카카오톡 열기")
        record()
        GuideSession.observePress(currentGeneration(), onTarget = true)

        GuideSession.observePress(currentGeneration(), onTarget = false)

        assertEquals(true, steps().single().performed)
    }

    /**
     * 화면이 바뀐 스텝에는 적지 않는다 — 다음 응답까지 옛 대상 bounds가 무장 상태로 남아,
     * 새 화면의 탭이 옛 스텝에 귀속되는 오탐 창을 닫는다.
     */
    @Test
    fun `화면이 바뀐 스텝에는 눌림을 적지 않는다`() {
        GuideSession.start("카카오톡 열기")
        record(screenSignature = SIGNATURE)
        GuideSession.observeScreen(currentGeneration(), OTHER_SIGNATURE)

        GuideSession.observePress(currentGeneration(), onTarget = true)

        assertNull(steps().single().performed)
    }

    @Test
    fun `스텝이 없으면 눌림 관측은 아무것도 하지 않는다`() {
        GuideSession.start("카카오톡 열기")

        GuideSession.observePress(currentGeneration(), onTarget = true)

        assertTrue(steps().isEmpty())
    }

    @Test
    fun `이전 세대의 눌림 관측은 스텝을 건드리지 않는다`() {
        GuideSession.start("카카오톡 열기")
        record()
        val stale = currentGeneration()

        GuideSession.observePress(stale + 1, onTarget = true)

        assertNull(steps().single().performed)
    }

    @Test
    fun `새 스텝의 눌림은 비어서 시작한다`() {
        GuideSession.start("카카오톡 열기")
        record()
        GuideSession.observePress(currentGeneration(), onTarget = true)

        record(instruction = "설정을 누르세요.")

        assertEquals(listOf(true, null), steps().map { it.performed })
    }

    // --- 눌림 확인 재질의 토큰 (#319) ---------------------------------------------
    //
    // 눌림 하나에 토큰 하나 — 재질의도 눌림당 최대 1회다(#39 호출량 규율의 유일한 예외).
    // tap 스텝만 무장한다: type의 눌림 확인은 "입력칸을 눌렀다"까지고 그 뒤 무변화는
    // 입력 중인 정상 상태다.

    @Test
    fun `tap 대상을 누르면 재질의 토큰이 생긴다`() {
        GuideSession.start("카카오톡 열기")
        record(actionType = ActionType.TAP)
        GuideSession.observePress(currentGeneration(), onTarget = true)

        assertTrue(GuideSession.consumePressRequery(currentGeneration()))
    }

    /** 소비는 한 번뿐이다 — 같은 눌림으로 재질의가 두 번 나가면 상한이 무너진다. */
    @Test
    fun `재질의 토큰은 한 번만 소비된다`() {
        GuideSession.start("카카오톡 열기")
        record(actionType = ActionType.TAP)
        GuideSession.observePress(currentGeneration(), onTarget = true)

        assertTrue(GuideSession.consumePressRequery(currentGeneration()))
        assertFalse(GuideSession.consumePressRequery(currentGeneration()))
    }

    @Test
    fun `type 스텝의 눌림은 기록되지만 토큰은 만들지 않는다`() {
        GuideSession.start("카카오톡 열기")
        record(actionType = ActionType.TYPE)
        GuideSession.observePress(currentGeneration(), onTarget = true)

        assertEquals(true, steps().single().performed)
        assertFalse(GuideSession.consumePressRequery(currentGeneration()))
    }

    @Test
    fun `빗맞음은 재질의 토큰을 만들지 않는다`() {
        GuideSession.start("카카오톡 열기")
        record(actionType = ActionType.TAP)
        GuideSession.observePress(currentGeneration(), onTarget = false)

        assertFalse(GuideSession.consumePressRequery(currentGeneration()))
    }

    /** 토큰은 그 안내의 눌림에 매인 근거다 — 새 안내가 나가면 소멸한다. */
    @Test
    fun `새 안내가 나가면 재질의 토큰이 사라진다`() {
        GuideSession.start("카카오톡 열기")
        record(actionType = ActionType.TAP)
        GuideSession.observePress(currentGeneration(), onTarget = true)

        record(instruction = "설정을 누르세요.")

        assertFalse(GuideSession.consumePressRequery(currentGeneration()))
    }

    /**
     * "눌렀는데 화면이 그대로"라는 근거는 화면이 바뀌는 순간 깨진다. 응답이 스텝을 못 남기는
     * 경로(폐기·미검증 done)에서도 토큰이 다음 화면으로 새지 않아야 한다.
     */
    @Test
    fun `화면이 바뀌면 재질의 토큰이 사라진다`() {
        GuideSession.start("카카오톡 열기")
        record(actionType = ActionType.TAP, screenSignature = SIGNATURE)
        GuideSession.observePress(currentGeneration(), onTarget = true)

        GuideSession.observeScreen(currentGeneration(), OTHER_SIGNATURE)

        assertFalse(GuideSession.consumePressRequery(currentGeneration()))
    }

    @Test
    fun `세션이 바뀌면 재질의 토큰이 사라진다`() {
        GuideSession.start("카카오톡 열기")
        record(actionType = ActionType.TAP)
        GuideSession.observePress(currentGeneration(), onTarget = true)

        GuideSession.stop()
        GuideSession.start("카카오톡 열기")

        assertFalse(GuideSession.consumePressRequery(currentGeneration()))
    }

    @Test
    fun `이전 세대는 재질의 토큰을 소비할 수 없다`() {
        GuideSession.start("카카오톡 열기")
        record(actionType = ActionType.TAP)
        GuideSession.observePress(currentGeneration(), onTarget = true)

        assertFalse(GuideSession.consumePressRequery(currentGeneration() + 1))
        assertTrue(GuideSession.consumePressRequery(currentGeneration()))
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

        record(generation = stale, instruction = "이전 세션의 안내입니다.")

        assertTrue(steps().isEmpty())
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

    /** 같은 화면에서 상한 뒤에 또 와도 재시도로 되돌아가지 않는다. */
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

    /** 스플래시가 쓴 횟수 때문에 다음 실화면의 확인 창이 일찍 뜨면 안 된다(#350). */
    @Test
    fun `화면이 바뀌면 미검증 done 재시도 예산을 새로 준다`() {
        GuideSession.start("카메라 열기")
        val generation = currentGeneration()
        repeat(GuideSession.MAX_UNVERIFIED_DONE - 1) {
            assertEquals(
                GuideSession.DoneOutcome.RETRY,
                GuideSession.onDone(generation, false, SIGNATURE),
            )
        }

        assertEquals(
            GuideSession.DoneOutcome.RETRY,
            GuideSession.onDone(generation, false, OTHER_SIGNATURE),
        )
        assertEquals(
            GuideSession.DoneOutcome.RETRY,
            GuideSession.onDone(generation, false, OTHER_SIGNATURE),
        )
        assertEquals(
            GuideSession.DoneOutcome.CONFIRM_UNSURE,
            GuideSession.onDone(generation, false, OTHER_SIGNATURE),
        )
    }

    /** 단일 직전 서명 카운터는 A↔B마다 0이 되어 호출을 무한히 열었다(PR #352 리뷰). */
    @Test
    fun `화면이 번갈아 나와도 화면별 done 예산은 누적된다`() {
        GuideSession.start("카메라 열기")
        val generation = currentGeneration()

        assertEquals(GuideSession.DoneOutcome.RETRY, GuideSession.onDone(generation, false, SIGNATURE))
        assertEquals(GuideSession.DoneOutcome.RETRY, GuideSession.onDone(generation, false, OTHER_SIGNATURE))
        assertEquals(GuideSession.DoneOutcome.RETRY, GuideSession.onDone(generation, false, SIGNATURE))
        assertEquals(GuideSession.DoneOutcome.RETRY, GuideSession.onDone(generation, false, OTHER_SIGNATURE))
        assertEquals(
            GuideSession.DoneOutcome.CONFIRM_UNSURE,
            GuideSession.onDone(generation, false, SIGNATURE),
        )
    }

    @Test
    fun `매번 다른 화면이어도 전체 done 예산에서 멈춘다`() {
        GuideSession.start("카메라 열기")
        val generation = currentGeneration()
        repeat(GuideSession.MAX_UNVERIFIED_DONE_TOTAL - 1) { index ->
            assertEquals(
                GuideSession.DoneOutcome.RETRY,
                GuideSession.onDone(generation, false, "signature-$index"),
            )
        }

        assertEquals(
            GuideSession.DoneOutcome.CONFIRM_UNSURE,
            GuideSession.onDone(generation, false, "signature-last"),
        )
    }

    /** 연속이 아니라 누적으로 세면 긴 세션에서 정상 진행 중에도 상한 알림이 뜬다. */
    @Test
    fun `다른 안내가 표시되면 미검증 done 연속이 끊긴다`() {
        GuideSession.start("카메라 열기")
        val generation = currentGeneration()
        GuideSession.onDone(generation, false, SIGNATURE)
        GuideSession.onDone(generation, false, SIGNATURE)

        record(generation, "카메라를 누르세요.")

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

        record(generation, "다른 화면의 안내")

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

        record(generation, "알람을 눌러 보세요.")

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

    // --- 종료 확인 (#193 GD-4) ---------------------------------------------------
    //
    // 중지 칩도 세션을 직접 끝내지 않는다. 종료 진입점을 ○ 응답 하나로 모은다.

    @Test
    fun `중지 칩은 세션을 끝내지 않고 확인을 기다린다`() {
        GuideSession.start("카메라 열기")

        assertTrue(GuideSession.awaitQuitConfirm())

        assertTrue(GuideSession.isActive)
        assertTrue(GuideSession.awaitingConfirm)
    }

    @Test
    fun `종료 확인의 됐어요는 세션을 끝낸다`() {
        GuideSession.start("카메라 열기")
        GuideSession.awaitQuitConfirm()

        GuideSession.confirmAccepted()

        assertFalse(GuideSession.isActive)
        assertFalse(GuideSession.awaitingConfirm)
    }

    @Test
    fun `종료 확인의 취소는 세션을 유지하고 대기를 푼다`() {
        GuideSession.start("카메라 열기")
        GuideSession.awaitQuitConfirm()

        GuideSession.quitRejected()

        assertTrue(GuideSession.isActive)
        assertFalse(GuideSession.awaitingConfirm)
    }

    /**
     * 부정 이력을 남기지 않는다. 남기면 그 화면의 done이 확인 없이 조용히 넘어간다 —
     * 사용자는 종료를 되물었을 뿐 "아직 안 됐다"고 답한 적이 없다.
     */
    @Test
    fun `종료 확인은 부정 이력을 남기지 않는다`() {
        GuideSession.start("카메라 열기")
        val generation = currentGeneration()

        GuideSession.awaitQuitConfirm()
        GuideSession.quitRejected()

        assertEquals(
            GuideSession.DoneOutcome.CONFIRM_CONFIDENT,
            GuideSession.onDone(generation, verified = true, screenSignature = SIGNATURE),
        )
    }

    /** 미검증 카운터도 건드리지 않는다. 0으로 되돌리면 재질의 예산이 공짜로 늘어난다. */
    @Test
    fun `종료 확인은 미검증 카운터를 되돌리지 않는다`() {
        GuideSession.start("카메라 열기")
        val generation = currentGeneration()
        repeat(GuideSession.MAX_UNVERIFIED_DONE - 1) {
            GuideSession.onDone(generation, verified = false, screenSignature = SIGNATURE)
        }

        GuideSession.awaitQuitConfirm()
        GuideSession.quitRejected()

        // 카운터가 살아 있으면 다음 미검증 done이 상한에 닿는다.
        assertEquals(
            GuideSession.DoneOutcome.CONFIRM_UNSURE,
            GuideSession.onDone(generation, verified = false, screenSignature = SIGNATURE),
        )
    }

    /** 세션이 없으면 되물을 것도 없다 — 창이 걷히는 사이에 들어온 클릭을 막는다. */
    @Test
    fun `세션이 없으면 종료 확인으로 들어가지 않는다`() {
        assertFalse(GuideSession.awaitQuitConfirm())
        assertFalse(GuideSession.awaitingConfirm)
    }

    /** 대기를 겹쳐 세우면 ○ 한 번에 안 끝나는 실패가 된다. */
    @Test
    fun `이미 확인 대기 중이면 종료 확인으로 겹쳐 들어가지 않는다`() {
        GuideSession.start("카메라 열기")
        GuideSession.onDone(currentGeneration(), verified = true, screenSignature = SIGNATURE)

        assertFalse(GuideSession.awaitQuitConfirm())
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
