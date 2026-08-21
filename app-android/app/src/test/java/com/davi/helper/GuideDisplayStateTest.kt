package com.davi.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 재표시·정체 접미사 판정(PR #63 리뷰 P1, 2차 P1).
 *
 * 핵심 시나리오: 상한 안내의 '안내 중지' 버튼은 우리 앱에만 있어서, 문구를 따르러 앱을 열면
 * root=null 청소가 안내를 지운다. **같은 화면**에 돌아온 사용자에게는 같은 안내가 다시
 * 보여야 하고(1차), **다른 화면**으로 갔다면 이전 안내를 그 위에 되살리면 안 된다(2차).
 */
class GuideDisplayStateTest {

    private val state = GuideDisplayState()

    private val sigA = "화면A"
    private val sigB = "화면B"
    private val capNotice = "다 된 것 같은데 확인하지 못했어요."
    private val stuck = "화면이 그대로예요."
    private val pressBounds = listOf(10, 20, 110, 220)
    private val scrollText = "아래쪽으로 스크롤해 주세요"
    private val confirmText = "다 됐어요! 해결되셨나요?"
    private val errorText = "인터넷 연결이 원활하지 않아요."
    private val otherErrorText = "지금은 이용하는 분이 많아요."

    /** 내용 재표시만 보는 기존 테스트용. 오버레이 제거 신호는 해당 회귀 테스트에서 직접 본다. */
    private fun redisplayContent(signature: String, screenStable: Boolean): GuideDisplayState.Content? =
        when (val result = state.redisplay(signature, screenStable)) {
            GuideDisplayState.RedisplayResult.Unchanged,
            GuideDisplayState.RedisplayResult.Clear,
            -> null
            is GuideDisplayState.RedisplayResult.Show -> result.content
        }

    // ── 안내 중 서버 오류(#29) ─────────────────────────────────────────

    /** 오류는 기존 안내를 덮는다. 걷히면 다음 성공 응답이 새 안내로 다시 덮는다. */
    @Test
    fun `오류는 기존 안내를 덮고 성공 응답이 다시 덮는다`() {
        state.show(sigA, listOf(1, 2, 3, 4), "확인을 누르세요")

        assertEquals(GuideDisplayState.Content(null, errorText), state.showError(sigA, errorText))
        assertEquals(
            GuideDisplayState.Content(listOf(5, 6, 7, 8), "다음을 누르세요"),
            state.show(sigA, listOf(5, 6, 7, 8), "다음을 누르세요"),
        )
    }

    /**
     * 재시도는 백오프로 계속 도는데 그때마다 같은 내용을 다시 그리면 조작 오버레이가 자기
     * 창을 만져 스스로를 깨운다(#153에서 정체 알림이 실제로 그랬다).
     */
    @Test
    fun `같은 화면의 같은 오류는 다시 그리지 않는다`() {
        state.showError(sigA, errorText)

        assertNull(state.showError(sigA, errorText))
    }

    /** 오류 종류가 바뀌었으면 사용자가 볼 말도 달라진다 — 억제 대상이 아니다. */
    @Test
    fun `오류 문구가 바뀌면 다시 그린다`() {
        state.showError(sigA, errorText)

        assertEquals(
            GuideDisplayState.Content(null, otherErrorText),
            state.showError(sigA, otherErrorText),
        )
    }

    /** 지워졌다면 같은 오류라도 다시 그려야 화면이 빈 채 남지 않는다. */
    @Test
    fun `지워진 뒤에는 같은 오류도 다시 그린다`() {
        state.showError(sigA, errorText)
        state.onCleared()

        assertEquals(GuideDisplayState.Content(null, errorText), state.showError(sigA, errorText))
    }

    /** 서버가 안 되어 화면이 그대로인 것이지 사용자가 조작을 놓친 게 아니다. */
    @Test
    fun `오류에는 정체 접미사를 붙이지 않는다`() {
        state.show(sigA, null, "확인을 누르세요")
        state.showError(sigA, errorText)

        assertEquals(GuideDisplayState.Content(null, errorText), state.stuckContent(stuck, sigA))
    }

    /** 확인 화면과 달리 오류는 화면을 따라다니지 않는다 — 그 화면의 새 요청이 어차피 나간다. */
    @Test
    fun `오류는 화면이 바뀌면 폐기된다`() {
        state.showError(sigA, errorText)
        state.onCleared()

        assertNull(redisplayContent(sigB, true))
    }

    @Test
    fun `읽기 불가 안내는 빈 화면 시그니처가 흔들려도 한 번만 그린다`() {
        assertEquals(
            GuideDisplayState.Content(null, errorText),
            state.showUnreadableNotice(sigA, errorText),
        )

        assertNull(state.showUnreadableNotice(sigB, errorText))
    }

    @Test
    fun `읽기 불가 안내는 오버레이 청소 뒤 다시 그린다`() {
        state.showUnreadableNotice(sigA, errorText)
        state.onCleared()

        assertEquals(
            GuideDisplayState.Content(null, errorText),
            state.showUnreadableNotice(sigB, errorText),
        )
    }

    @Test
    fun `상한 안내는 지워진 뒤 같은 화면에서 재표시된다`() {
        state.showCapNotice(sigA, capNotice)

        state.onCleared() // 사용자가 '안내 중지'를 찾아 우리 앱을 열었다(root=null 청소)

        assertEquals(GuideDisplayState.Content(null, capNotice), redisplayContent(sigA, true))
    }

    /** 이전 화면의 안내가 다른 화면 위에 그려지면 안 된다(2차 리뷰 P1, #61 P1과 같은 계급). */
    @Test
    fun `지워진 뒤 다른 화면에서는 재표시하지 않는다`() {
        state.showCapNotice(sigA, capNotice)
        state.onCleared()

        assertNull(redisplayContent(sigB, true))
    }

    /** 다른 화면을 본 순간 이전 안내는 폐기된다 — 원래 화면에 돌아와도 안 되살린다. */
    @Test
    fun `다른 화면에서 폐기된 안내는 원래 화면에서도 재표시하지 않는다`() {
        state.showCapNotice(sigA, capNotice)
        state.onCleared()

        redisplayContent(sigB, true) // 폐기 지점

        assertNull(redisplayContent(sigA, true))
    }

    @Test
    fun `일반 안내도 지워지면 같은 화면에서 재표시된다`() {
        state.show(sigA, listOf(0, 0, 10, 10), "카메라를 누르세요.")

        state.onCleared() // 런처 dump 간헐 실패로 root=null 샘플이 한 번 떴다

        assertEquals(
            GuideDisplayState.Content(listOf(0, 0, 10, 10), "카메라를 누르세요."),
            redisplayContent(sigA, true),
        )
    }

    /** 떠 있는 안내를 폴링마다 다시 그리면 오버레이가 깜빡인다. 지워졌을 때만 되살린다. */
    @Test
    fun `떠 있는 동안에는 재표시하지 않는다`() {
        state.showCapNotice(sigA, capNotice)

        assertNull(redisplayContent(sigA, true))
    }

    @Test
    fun `재표시는 한 번만 일어난다`() {
        state.showCapNotice(sigA, capNotice)
        state.onCleared()

        redisplayContent(sigA, true)

        assertNull(redisplayContent(sigA, true))
    }

    /** 상한 안내는 행동 지시가 아니라서 "한 번 더 해 보세요"가 붙으면 앞뒤가 안 맞는다. */
    @Test
    fun `상한 안내에는 정체 접미사가 붙지 않는다`() {
        state.showCapNotice(sigA, capNotice)

        assertEquals(GuideDisplayState.Content(null, capNotice), state.stuckContent(stuck, sigA))
    }

    @Test
    fun `일반 안내에는 정체 접미사가 붙는다`() {
        state.show(sigA, null, "화면을 넘기세요.")

        assertEquals(
            GuideDisplayState.Content(null, "화면을 넘기세요.\n$stuck"),
            state.stuckContent(stuck, sigA),
        )
    }

    /** 이전 세션의 안내가 새 세션 화면 위에 되살아나면 안 된다. */
    @Test
    fun `세션이 리셋되면 재표시하지 않는다`() {
        state.showCapNotice(sigA, capNotice)
        state.onCleared()

        state.reset()

        assertNull(redisplayContent(sigA, true))
        assertNull(state.stuckContent(stuck, sigA))
    }

    @Test
    fun `안내가 없으면 재표시도 정체 알림도 없다`() {
        assertNull(redisplayContent(sigA, true))
        assertNull(state.stuckContent(stuck, sigA))
    }

    // --- 완료 확인 (GD-3, #139) --------------------------------------------------

    /** 확인 대기 중 우리 앱을 열면 확인쌍이 지워진다 — 답할 자리가 그대로 사라지면 안 된다. */
    @Test
    fun `확인 화면은 지워진 뒤 같은 화면에서 재표시된다`() {
        state.showConfirm(sigA, confirmText)

        state.onCleared()

        assertEquals(
            GuideDisplayState.Content(null, confirmText, confirm = GuideDisplayState.ConfirmKind.DONE),
            redisplayContent(sigA, true),
        )
    }

    /** 확인은 지금 시킬 행동이 아니다 — "한 번 더 해 보세요"가 붙으면 답을 재촉하는 말이 된다. */
    @Test
    fun `확인 화면에는 정체 접미사가 붙지 않는다`() {
        state.showConfirm(sigA, confirmText)

        assertEquals(
            GuideDisplayState.Content(null, confirmText, confirm = GuideDisplayState.ConfirmKind.DONE),
            state.stuckContent(stuck, sigA),
        )
    }

    /**
     * **확인 화면은 다른 화면에서도 되살아난다.** 조작 안내와 갈리는 지점이다 — 가리킬 대상이
     * 없고 문구도 화면과 무관해서, 화면에 묶으면 알림창을 잠깐 내린 것만으로 답할 자리가
     * 사라진다(2026-08-16 실기기 실측, PR #153 리뷰 P1).
     */
    @Test
    fun `확인 화면은 다른 화면에서도 재표시된다`() {
        state.showConfirm(sigA, confirmText)
        state.onCleared()

        assertEquals(
            GuideDisplayState.Content(null, confirmText, confirm = GuideDisplayState.ConfirmKind.DONE),
            redisplayContent(sigB, true),
        )
    }

    /** 폐기되지 않았으므로 원래 화면으로 돌아와도 그대로 남아 있다. */
    @Test
    fun `확인 화면은 다른 화면을 거쳐도 폐기되지 않는다`() {
        state.showConfirm(sigA, confirmText)
        state.onCleared()

        redisplayContent(sigB, true)
        state.onCleared()

        assertEquals(
            GuideDisplayState.Content(null, confirmText, confirm = GuideDisplayState.ConfirmKind.DONE),
            redisplayContent(sigA, true),
        )
    }

    /** ×아직요 뒤의 새 안내는 확인쌍을 물려받지 않는다. */
    @Test
    fun `확인 뒤 새 안내는 일반 안내로 돌아온다`() {
        state.showConfirm(sigA, confirmText)

        val next = state.show(sigB, null, "설정을 누르세요.")

        assertEquals(GuideDisplayState.Content(null, "설정을 누르세요."), next)
    }

    /** 상한 안내 뒤에 새 안내가 오면(화면이 바뀌어 재질의) 접미사 규칙도 일반으로 돌아온다. */
    @Test
    fun `새 안내가 오면 접미사 규칙이 되살아난다`() {
        state.showCapNotice(sigA, capNotice)

        state.show(sigB, null, "설정을 누르세요.")

        // 다른 화면의 정체라 억제 신호가 함께 선다(PR #343 리뷰 P1) — 이 테스트의 주제는
        // 접미사 규칙이고, 신호 자체는 위 가장자리 제스처 테스트들이 고정한다.
        assertEquals(
            GuideDisplayState.Content(null, "설정을 누르세요.\n$stuck", targetSuppressed = true),
            state.stuckContent(stuck, sigA),
        )
    }

    // --- 종료 확인 (GD-4, #193) --------------------------------------------------

    private val quitText = "안내를 그만 볼까요?"

    /** ×("계속 볼래요")는 직전 안내로 **되돌린다**. 완료 확인의 ×(버리기)와 갈리는 지점이다. */
    @Test
    fun `종료 확인의 취소는 직전 안내를 되살린다`() {
        state.show(sigA, null, "설정을 누르세요.")

        state.showQuitConfirm(quitText)

        assertEquals(
            GuideDisplayState.Content(null, "설정을 누르세요."),
            state.dismissQuitConfirm(sigA),
        )
    }

    /** 되살아난 안내는 자기 화면 서명과 접미사 규칙까지 되찾는다 — 껍데기만 돌려주면 안 된다. */
    @Test
    fun `되살아난 안내는 서명과 접미사 규칙을 되찾는다`() {
        state.show(sigA, null, "설정을 누르세요.")
        state.showQuitConfirm(quitText)

        state.dismissQuitConfirm(sigA)

        assertEquals(
            GuideDisplayState.Content(null, "설정을 누르세요.\n$stuck"),
            state.stuckContent(stuck, sigA),
        )
        state.onCleared()
        // 정체 접미사는 상태에 남으므로 재표시도 그대로 가져온다 — 화면이 여전히 정체인데
        // 문구만 사라지면 안 된다(PR #226 리뷰 1).
        assertEquals(
            GuideDisplayState.Content(null, "설정을 누르세요.\n$stuck"),
            redisplayContent(sigA, true),
        )
    }

    /** 상한 안내를 밀어 뒀다면 그 접미사 금지도 함께 돌아와야 한다. */
    @Test
    fun `상한 안내를 밀어 뒀으면 접미사 금지도 되돌아온다`() {
        state.showCapNotice(sigA, capNotice)
        state.showQuitConfirm(quitText)

        state.dismissQuitConfirm(sigA)

        assertEquals(GuideDisplayState.Content(null, capNotice), state.stuckContent(stuck, sigA))
    }

    /** 확인 화면 계약은 종류를 가리지 않는다 — 셰이드를 내려도 답할 자리가 따라간다. */
    @Test
    fun `종료 확인은 다른 화면에서도 재표시된다`() {
        state.show(sigA, null, "설정을 누르세요.")
        state.showQuitConfirm(quitText)
        state.onCleared()

        assertEquals(
            GuideDisplayState.Content(null, quitText, confirm = GuideDisplayState.ConfirmKind.QUIT),
            redisplayContent(sigB, true),
        )
    }

    /** 답을 재촉하지 않는다 — 완료 확인과 같은 이유로 정체 접미사가 붙지 않는다. */
    @Test
    fun `종료 확인에는 정체 접미사가 붙지 않는다`() {
        state.show(sigA, null, "설정을 누르세요.")
        state.showQuitConfirm(quitText)

        assertEquals(
            GuideDisplayState.Content(null, quitText, confirm = GuideDisplayState.ConfirmKind.QUIT),
            state.stuckContent(stuck, sigA),
        )
    }

    /**
     * 밀어 둔 것을 확인 화면으로 덮어쓰지 않는다. 지금은 칩이 숨겨져 도달할 수 없는 경로지만,
     * 겹치면 되돌아갈 안내를 영영 잃는다.
     */
    @Test
    fun `종료 확인이 겹쳐도 밀어 둔 안내를 잃지 않는다`() {
        state.show(sigA, null, "설정을 누르세요.")
        state.showQuitConfirm(quitText)

        state.showQuitConfirm(quitText)

        assertEquals(
            GuideDisplayState.Content(null, "설정을 누르세요."),
            state.dismissQuitConfirm(sigA),
        )
    }

    /** 밀어 둔 안내는 세션에 매인다 — 남기면 다음 세션의 ×가 이전 세션 안내를 되살린다. */
    @Test
    fun `세션이 끝나면 밀어 둔 안내도 버려진다`() {
        state.show(sigA, null, "설정을 누르세요.")
        state.showQuitConfirm(quitText)

        state.reset()

        assertNull(state.dismissQuitConfirm(sigA))
    }

    /**
     * 칩 → 홈 → × 순이면 되살릴 안내가 **다른 화면의 것**이다. 그대로 그리면 이전 화면의 강조가
     * 런처 위에 뜨고, `visible`이 true라 재표시가 걷지도 못한다(PR #63 2차 리뷰 P1과 같은 규칙).
     */
    @Test
    fun `화면이 바뀐 뒤의 종료 확인 취소는 옛 안내를 되살리지 않는다`() {
        state.show(sigA, listOf(0, 0, 10, 10), "설정을 누르세요.")
        state.showQuitConfirm(quitText)

        assertNull(state.dismissQuitConfirm(sigB))
    }

    /** 폐기했으면 기억도 남기지 않는다 — 원래 화면으로 돌아와도 되살아나면 안 된다. */
    @Test
    fun `폐기된 안내는 원래 화면으로 돌아와도 재표시되지 않는다`() {
        state.show(sigA, null, "설정을 누르세요.")
        state.showQuitConfirm(quitText)

        state.dismissQuitConfirm(sigB)

        assertNull(redisplayContent(sigA, true))
    }

    /** 되살릴 안내가 애초에 없었으면 화면이 달라도 폐기 판정에 걸리지 않는다(둘 다 null이다). */
    @Test
    fun `밀어 둔 안내가 없으면 화면이 달라도 그냥 없다`() {
        state.showQuitConfirm(quitText)

        assertNull(state.dismissQuitConfirm(sigB))
    }

    /** 새 안내는 자기 화면 서명으로 재표시된다 — 이전 서명을 끌고 가면 재표시가 어긋난다. */
    @Test
    fun `새 안내는 새 화면 서명으로 재표시된다`() {
        state.show(sigA, null, "이전 안내")
        state.show(sigB, null, "새 안내")

        state.onCleared()

        assertEquals(GuideDisplayState.Content(null, "새 안내"), redisplayContent(sigB, true))
    }

    // --- 안정화 상태 머신과의 결합(4차 리뷰 P1) ---------------------------------
    //
    // 전환 직후에는 덜 그려진 과도기 트리가 온다(#32 실측). 그 서명은 화면의 정체가 아니므로
    // WAIT 판정 샘플로 보존 안내를 폐기하면, 안정된 원래 화면이 와도 되살릴 것이 없다.

    @Test
    fun `과도기(WAIT) 샘플은 보존 안내를 폐기하지 않는다`() {
        state.showCapNotice(sigA, capNotice)
        state.onCleared()

        assertNull(redisplayContent(sigB, false)) // 과도기 — 재표시도 폐기도 없음

        assertEquals(GuideDisplayState.Content(null, capNotice), redisplayContent(sigA, true))
    }

    @Test
    fun `읽기 불가 화면은 보존 안내를 폐기하지 않고 원래 화면에서 복구한다`() {
        val tracker = ScreenStabilityTracker(unreadableAfterMs = 100)
        state.showCapNotice(sigA, capNotice)
        state.onCleared()

        fun sync(signature: String, at: Long, labeled: Boolean): GuideDisplayState.Content? {
            val decision = tracker.onSample(signature, at, hasLabeledElement = labeled)
            val redisplayed = redisplayContent(signature, decision.isStableForGuideRedisplay())
            return if (decision == ScreenStabilityTracker.Decision.UNREADABLE) {
                state.showUnreadableNotice(signature, errorText) ?: redisplayed
            } else {
                redisplayed
            }
        }

        assertNull(sync(sigB, 0, labeled = false))
        assertEquals(GuideDisplayState.Content(null, errorText), sync(sigB, 100, labeled = false))
        assertNull(sync(sigA, 200, labeled = true))
        assertEquals(GuideDisplayState.Content(null, capNotice), sync(sigA, 300, labeled = true))
    }

    @Test
    fun `읽기 불가 뒤 다른 안정 화면이면 보존 안내도 함께 폐기한다`() {
        state.showCapNotice(sigA, capNotice)
        state.onCleared()
        state.showUnreadableNotice(sigB, errorText)

        assertEquals(
            GuideDisplayState.RedisplayResult.Clear,
            state.redisplay("stable-c", screenStable = true),
        )
        assertEquals(
            GuideDisplayState.RedisplayResult.Unchanged,
            state.redisplay(sigA, screenStable = true),
        )
    }

    @Test
    fun `읽기 불가 안내에서 연 종료 확인은 화면이 바뀌어도 우선 보존한다`() {
        state.showUnreadableNotice(sigA, errorText)
        val confirming = state.showQuitConfirm("안내를 그만 볼까요?")
        state.onCleared()

        assertEquals(confirming, redisplayContent(sigB, screenStable = true))
    }

    /** 리뷰가 요구한 시퀀스: A 안내 → clear → 과도기 B(WAIT) → A(WAIT) → A(IDLE) 재표시. */
    @Test
    fun `과도기를 지나 안정된 원래 화면에서 재표시된다 - 추적기 결합`() {
        val tracker = ScreenStabilityTracker()
        // 서비스 sampleScreen의 배선 그대로: 판정을 먼저 얻고, WAIT가 아닐 때만 재표시/폐기.
        fun sync(signature: String, at: Long): GuideDisplayState.Content? {
            val decision = tracker.onSample(signature, at, hasLabeledElement = true)
            return redisplayContent(signature, decision.isStableForGuideRedisplay())
        }

        // A 안정 → 요청 → 응답으로 상한 안내 표시(서비스의 SEND→applyAction에 해당)
        tracker.onSample(sigA, 0, hasLabeledElement = true)
        tracker.onSample(sigA, 100, hasLabeledElement = true)
        tracker.markSent(sigA, 100)
        tracker.markFinished()
        state.showCapNotice(sigA, capNotice)

        state.onCleared() // root=null 또는 onInterrupt

        assertNull(sync(sigB, 200)) // 과도기 B — WAIT, 폐기 금지
        assertNull(sync(sigA, 300)) // A 복귀 첫 샘플 — 아직 WAIT
        assertEquals( // A 안정(IDLE) — 재표시
            GuideDisplayState.Content(null, capNotice),
            sync(sigA, 400),
        )
    }

    // ── 흔들리는 동안 강조 내리기(#225) ──────────────────────────────

    /** 화면이 바뀌면 틀리는 것은 강조의 자리지 문장이 아니다. 손 모션도 자리에 딸린다. */
    @Test
    fun `흔들리는 동안 강조와 손 모션만 내리고 문구는 남는다`() {
        state.show(sigA, listOf(1, 2, 3, 4), scrollText, ActionType.SCROLL, SwipeDirection.DOWN)

        assertEquals(
            GuideDisplayState.Content(null, scrollText, ActionType.SCROLL, null, targetSuppressed = true),
            state.hideTarget(),
        )
    }

    /**
     * 가장자리 제스처는 bounds·direction 없이 actionType만으로 선·손을 그린다(#222) —
     * `copy(bounds = null, direction = null)` 규약만으로는 못 내리고, 명시 신호가 있어야
     * 사용자가 셰이드를 끌어내리기 시작한 화면 위에 선·손이 남지 않는다(PR #343 리뷰 P1-1).
     */
    @Test
    fun `가장자리 제스처도 흔들리는 동안 시연이 내려간다`() {
        state.show(sigA, null, "화면 맨 위에서 아래로 쓸어내리세요", ActionType.OPEN_QUICK_SETTINGS)

        val hidden = state.hideTarget()

        assertEquals(true, hidden?.targetSuppressed)
        assertEquals(ActionType.OPEN_QUICK_SETTINGS, hidden?.actionType)
        assertNull(state.hideTarget())
    }

    /** 잠깐 흔들렸을 뿐 같은 화면이면 가장자리 시연도 억제 없이 돌아온다. */
    @Test
    fun `가장자리 제스처 시연은 같은 화면에서 되살아난다`() {
        val shown = state.show(sigA, null, "화면 맨 아래에서 위로 쓸어올리세요", ActionType.OPEN_APP_DRAWER)
        state.hideTarget()

        val restored = state.restoreTarget(sigA)

        assertEquals(shown, restored)
        assertEquals(false, restored?.targetSuppressed)
    }

    /** 샘플마다 같은 내용을 다시 그리면 조작 오버레이가 자기 창을 만져 스스로를 깨운다. */
    @Test
    fun `이미 내린 강조는 다시 내리지 않는다`() {
        state.show(sigA, listOf(1, 2, 3, 4), "확인을 누르세요")
        state.hideTarget()

        assertNull(state.hideTarget())
    }

    /** 잠깐 흔들렸을 뿐 같은 화면이면 강조가 그대로 돌아온다. */
    @Test
    fun `같은 화면으로 안정되면 강조가 돌아온다`() {
        val shown = state.show(sigA, listOf(1, 2, 3, 4), "확인을 누르세요")
        state.hideTarget()

        assertEquals(shown, state.restoreTarget(sigA))
        assertNull(state.restoreTarget(sigA))
    }

    /** 되살리면 이전 화면의 강조가 새 화면 위에 그려진다(redisplay의 2차 리뷰 P1과 같은 계급). */
    @Test
    fun `다른 화면에서는 강조를 되살리지 않는다`() {
        state.show(sigA, listOf(1, 2, 3, 4), "확인을 누르세요")
        state.hideTarget()

        assertNull(state.restoreTarget(sigB))
    }

    /** 확인 화면은 가리킬 대상이 없다 — 내릴 것도 없다. */
    @Test
    fun `확인 화면에는 내릴 강조가 없다`() {
        state.showConfirm(sigA, confirmText)

        assertNull(state.hideTarget())
    }

    /** 숨김이 안 풀리면 새 안내의 강조가 첫 흔들림에서 사라진 뒤 영영 안 돌아온다. */
    @Test
    fun `새 안내가 뜨면 숨김이 풀린다`() {
        state.show(sigA, listOf(1, 2, 3, 4), "확인을 누르세요")
        state.hideTarget()

        val next = state.show(sigA, listOf(5, 6, 7, 8), "다음을 누르세요")

        assertNull(state.restoreTarget(sigA))
        assertEquals(listOf(5, 6, 7, 8), next.bounds)
    }

    /** 통째로 지워졌다 되살아나는 경로(PR #63 리뷰 P1)가 숨김 상태를 남기지 않는다. */
    @Test
    fun `오버레이가 지워졌다 되살아나면 숨김이 남지 않는다`() {
        val shown = state.show(sigA, listOf(1, 2, 3, 4), "확인을 누르세요")
        state.hideTarget()
        state.onCleared()

        assertEquals(shown, redisplayContent(sigA, screenStable = true))
        assertNull(state.restoreTarget(sigA))
    }

    /**
     * 정체 접미사는 [GuideDisplayState.stuckContent]가 반환만 하고 저장하지 않으면 다음
     * 재드로우에서 사라진다. `stuckReported`가 true라 다시 붙지도 않는다 (PR #226 리뷰 1).
     */
    @Test
    fun `정체 접미사는 강조를 내렸다 되살려도 남는다`() {
        state.show(sigA, listOf(1, 2, 3, 4), "확인을 누르세요")
        state.stuckContent(stuck, sigA)

        state.hideTarget()

        assertEquals("확인을 누르세요\n$stuck", state.restoreTarget(sigA)?.text)
    }

    /**
     * 정체 안내가 [GuideDisplayState.restoreTarget]이 막은 것을 되돌리면 안 된다 —
     * 이전 화면의 강조가 새 화면 위에 그려진다 (PR #226 리뷰 2).
     */
    @Test
    fun `다른 화면에서는 정체 안내가 강조를 그리지 않는다`() {
        state.show(sigA, listOf(1, 2, 3, 4), "확인을 누르세요")
        state.hideTarget()

        val content = state.stuckContent(stuck, sigB)

        assertNull(content?.bounds)
        assertEquals("확인을 누르세요\n$stuck", content?.text)
    }

    /**
     * bounds를 지우는 것만으로는 actionType에서 파생되는 가장자리 선·손이 살아남아, 이전
     * 화면의 시연이 새 화면 위에 그려진다(PR #343 리뷰 P1-2). 억제는 명시 신호로 나간다.
     */
    @Test
    fun `다른 화면의 정체는 가장자리 시연도 그리지 않는다`() {
        state.show(sigA, null, "화면 맨 위에서 아래로 쓸어내리세요", ActionType.OPEN_QUICK_SETTINGS)

        val content = state.stuckContent(stuck, sigB)

        assertEquals(true, content?.targetSuppressed)
        assertEquals("화면 맨 위에서 아래로 쓸어내리세요\n$stuck", content?.text)
    }

    /** 같은 화면의 정체는 시연을 그대로 둔다 — 억제 신호가 서면 안 된다. */
    @Test
    fun `같은 화면의 정체는 가장자리 시연을 남긴다`() {
        state.show(sigA, null, "화면 맨 위에서 아래로 쓸어내리세요", ActionType.OPEN_QUICK_SETTINGS)

        assertEquals(false, state.stuckContent(stuck, sigA)?.targetSuppressed)
    }

    // ── 눌림 감지(#238) ──────────────────────────────────────────────

    /** 눌림을 맞출 때도 파동을 그릴 때도 이 자리를 쓴다. */
    @Test
    fun `탭 안내가 떠 있으면 대상 자리를 돌려준다`() {
        state.show(sigA, pressBounds, "확인을 누르세요", ActionType.TAP)

        assertEquals(pressBounds, state.pressTargetBounds())
    }

    /** 입력 안내도 필드를 먼저 눌러야 하므로 눌림을 받는다. */
    @Test
    fun `입력 안내도 눌림을 받는다`() {
        state.show(sigA, pressBounds, "입력칸을 누르세요", ActionType.TYPE)

        assertEquals(pressBounds, state.pressTargetBounds())
    }

    /**
     * scroll도 컨테이너를 target으로 잡아 bounds가 있는데, 그 안 항목을 잘못 눌러도 중심
     * 포함이라 맞아 버린다 — 파동이 "잘 됐다"고 답하고 손 시연까지 함께 내려간다(PR #264 리뷰 1).
     */
    @Test
    fun `스크롤 안내에서는 눌림을 받지 않는다`() {
        state.show(sigA, pressBounds, scrollText, ActionType.SCROLL, SwipeDirection.DOWN)

        assertNull(state.pressTargetBounds())
    }

    /**
     * 화면 전환이 빠르면 WAIT이 클릭 이벤트보다 먼저 들어와 강조가 이미 내려가 있다.
     * 그때도 자리는 알려줘야 한다 — 파동은 "방금 누른 곳"이라 여전히 참이다(실측 회귀).
     */
    @Test
    fun `강조가 내려간 뒤에도 대상 자리는 남는다`() {
        state.show(sigA, pressBounds, "확인을 누르세요", ActionType.TAP)
        state.hideTarget()

        assertEquals(pressBounds, state.pressTargetBounds())
    }

    /**
     * 화면이 바뀌어도 visible은 안 내려가므로, 같은 자리를 한 번 더 누르면 아무것도 없는 곳에
     * 파동이 뜬다. 안내 하나당 한 번만 받는다(PR #264 리뷰 2).
     */
    @Test
    fun `눌림은 안내 하나당 한 번만 받는다`() {
        state.show(sigA, pressBounds, "확인을 누르세요", ActionType.TAP)
        state.consumePress()

        assertNull(state.pressTargetBounds())
    }

    /** 새 안내는 새 눌림을 받아야 한다. */
    @Test
    fun `새 안내가 뜨면 눌림을 다시 받는다`() {
        state.show(sigA, pressBounds, "확인을 누르세요", ActionType.TAP)
        state.consumePress()

        state.show(sigA, pressBounds, "다음을 누르세요", ActionType.TAP)

        assertEquals(pressBounds, state.pressTargetBounds())
    }

    /**
     * 눌렀는데 화면이 안 바뀌어 강조가 되살아난 경우다. 다시 눌러도 파동이 없고 내려가지도
     * 않으면, "제대로 눌렀는지 모른 채 다시 누르는" 바로 그 사용자가 아무 답을 못 받는다
     * (PR #264 재리뷰 1).
     */
    @Test
    fun `강조가 되살아나면 눌림도 다시 받는다`() {
        state.show(sigA, pressBounds, "확인을 누르세요", ActionType.TAP)
        state.consumePress()
        state.hideTarget()

        state.restoreTarget(sigA)

        assertEquals(pressBounds, state.pressTargetBounds())
    }

    /**
     * 다른 화면에서는 강조도 눌림도 되살아나지 않는다 — 재무장이 리뷰 2가 막은 경우
     * (옛 자리로 두 번 답하기)를 다시 열면 안 된다.
     */
    @Test
    fun `다른 화면에서는 눌림을 다시 받지 않는다`() {
        state.show(sigA, pressBounds, "확인을 누르세요", ActionType.TAP)
        state.consumePress()
        state.hideTarget()

        state.restoreTarget(sigB)

        assertNull(state.pressTargetBounds())
    }

    /** 확인 화면은 가리킬 대상이 없다([GuideDisplayState.Content.bounds]가 null). */
    @Test
    fun `확인 화면에는 누를 대상이 없다`() {
        state.showConfirm(sigA, confirmText)

        assertNull(state.pressTargetBounds())
    }

    /** 오버레이가 걷힌 동안에는 맞출 대상도 없다. */
    @Test
    fun `오버레이가 지워졌으면 대상 자리가 없다`() {
        state.show(sigA, pressBounds, "확인을 누르세요", ActionType.TAP)
        state.onCleared()

        assertNull(state.pressTargetBounds())
    }
}
