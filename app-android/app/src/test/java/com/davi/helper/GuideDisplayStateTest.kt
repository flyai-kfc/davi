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
    private val confirmText = "다 됐어요! 해결되셨나요?"
    private val errorText = "인터넷 연결이 원활하지 않아요."
    private val otherErrorText = "지금은 이용하는 분이 많아요."

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

        assertEquals(GuideDisplayState.Content(null, errorText), state.stuckContent(stuck))
    }

    /** 확인 화면과 달리 오류는 화면을 따라다니지 않는다 — 그 화면의 새 요청이 어차피 나간다. */
    @Test
    fun `오류는 화면이 바뀌면 폐기된다`() {
        state.showError(sigA, errorText)
        state.onCleared()

        assertNull(state.redisplay(sigB, true))
    }

    @Test
    fun `상한 안내는 지워진 뒤 같은 화면에서 재표시된다`() {
        state.showCapNotice(sigA, capNotice)

        state.onCleared() // 사용자가 '안내 중지'를 찾아 우리 앱을 열었다(root=null 청소)

        assertEquals(GuideDisplayState.Content(null, capNotice), state.redisplay(sigA, true))
    }

    /** 이전 화면의 안내가 다른 화면 위에 그려지면 안 된다(2차 리뷰 P1, #61 P1과 같은 계급). */
    @Test
    fun `지워진 뒤 다른 화면에서는 재표시하지 않는다`() {
        state.showCapNotice(sigA, capNotice)
        state.onCleared()

        assertNull(state.redisplay(sigB, true))
    }

    /** 다른 화면을 본 순간 이전 안내는 폐기된다 — 원래 화면에 돌아와도 안 되살린다. */
    @Test
    fun `다른 화면에서 폐기된 안내는 원래 화면에서도 재표시하지 않는다`() {
        state.showCapNotice(sigA, capNotice)
        state.onCleared()

        state.redisplay(sigB, true) // 폐기 지점

        assertNull(state.redisplay(sigA, true))
    }

    @Test
    fun `일반 안내도 지워지면 같은 화면에서 재표시된다`() {
        state.show(sigA, listOf(0, 0, 10, 10), "카메라를 누르세요.")

        state.onCleared() // 런처 dump 간헐 실패로 root=null 샘플이 한 번 떴다

        assertEquals(
            GuideDisplayState.Content(listOf(0, 0, 10, 10), "카메라를 누르세요."),
            state.redisplay(sigA, true),
        )
    }

    /** 떠 있는 안내를 폴링마다 다시 그리면 오버레이가 깜빡인다. 지워졌을 때만 되살린다. */
    @Test
    fun `떠 있는 동안에는 재표시하지 않는다`() {
        state.showCapNotice(sigA, capNotice)

        assertNull(state.redisplay(sigA, true))
    }

    @Test
    fun `재표시는 한 번만 일어난다`() {
        state.showCapNotice(sigA, capNotice)
        state.onCleared()

        state.redisplay(sigA, true)

        assertNull(state.redisplay(sigA, true))
    }

    /** 상한 안내는 행동 지시가 아니라서 "한 번 더 해 보세요"가 붙으면 앞뒤가 안 맞는다. */
    @Test
    fun `상한 안내에는 정체 접미사가 붙지 않는다`() {
        state.showCapNotice(sigA, capNotice)

        assertEquals(GuideDisplayState.Content(null, capNotice), state.stuckContent(stuck))
    }

    @Test
    fun `일반 안내에는 정체 접미사가 붙는다`() {
        state.show(sigA, null, "화면을 넘기세요.")

        assertEquals(
            GuideDisplayState.Content(null, "화면을 넘기세요.\n$stuck"),
            state.stuckContent(stuck),
        )
    }

    /** 이전 세션의 안내가 새 세션 화면 위에 되살아나면 안 된다. */
    @Test
    fun `세션이 리셋되면 재표시하지 않는다`() {
        state.showCapNotice(sigA, capNotice)
        state.onCleared()

        state.reset()

        assertNull(state.redisplay(sigA, true))
        assertNull(state.stuckContent(stuck))
    }

    @Test
    fun `안내가 없으면 재표시도 정체 알림도 없다`() {
        assertNull(state.redisplay(sigA, true))
        assertNull(state.stuckContent(stuck))
    }

    // --- 완료 확인 (GD-3, #139) --------------------------------------------------

    /** 확인 대기 중 우리 앱을 열면 확인쌍이 지워진다 — 답할 자리가 그대로 사라지면 안 된다. */
    @Test
    fun `확인 화면은 지워진 뒤 같은 화면에서 재표시된다`() {
        state.showConfirm(sigA, confirmText)

        state.onCleared()

        assertEquals(
            GuideDisplayState.Content(null, confirmText, confirm = GuideDisplayState.ConfirmKind.DONE),
            state.redisplay(sigA, true),
        )
    }

    /** 확인은 지금 시킬 행동이 아니다 — "한 번 더 해 보세요"가 붙으면 답을 재촉하는 말이 된다. */
    @Test
    fun `확인 화면에는 정체 접미사가 붙지 않는다`() {
        state.showConfirm(sigA, confirmText)

        assertEquals(
            GuideDisplayState.Content(null, confirmText, confirm = GuideDisplayState.ConfirmKind.DONE),
            state.stuckContent(stuck),
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
            state.redisplay(sigB, true),
        )
    }

    /** 폐기되지 않았으므로 원래 화면으로 돌아와도 그대로 남아 있다. */
    @Test
    fun `확인 화면은 다른 화면을 거쳐도 폐기되지 않는다`() {
        state.showConfirm(sigA, confirmText)
        state.onCleared()

        state.redisplay(sigB, true)
        state.onCleared()

        assertEquals(
            GuideDisplayState.Content(null, confirmText, confirm = GuideDisplayState.ConfirmKind.DONE),
            state.redisplay(sigA, true),
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

        assertEquals(
            GuideDisplayState.Content(null, "설정을 누르세요.\n$stuck"),
            state.stuckContent(stuck),
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
            state.dismissQuitConfirm(),
        )
    }

    /** 되살아난 안내는 자기 화면 서명과 접미사 규칙까지 되찾는다 — 껍데기만 돌려주면 안 된다. */
    @Test
    fun `되살아난 안내는 서명과 접미사 규칙을 되찾는다`() {
        state.show(sigA, null, "설정을 누르세요.")
        state.showQuitConfirm(quitText)

        state.dismissQuitConfirm()

        assertEquals(
            GuideDisplayState.Content(null, "설정을 누르세요.\n$stuck"),
            state.stuckContent(stuck),
        )
        state.onCleared()
        assertEquals(GuideDisplayState.Content(null, "설정을 누르세요."), state.redisplay(sigA, true))
    }

    /** 상한 안내를 밀어 뒀다면 그 접미사 금지도 함께 돌아와야 한다. */
    @Test
    fun `상한 안내를 밀어 뒀으면 접미사 금지도 되돌아온다`() {
        state.showCapNotice(sigA, capNotice)
        state.showQuitConfirm(quitText)

        state.dismissQuitConfirm()

        assertEquals(GuideDisplayState.Content(null, capNotice), state.stuckContent(stuck))
    }

    /** 확인 화면 계약은 종류를 가리지 않는다 — 셰이드를 내려도 답할 자리가 따라간다. */
    @Test
    fun `종료 확인은 다른 화면에서도 재표시된다`() {
        state.show(sigA, null, "설정을 누르세요.")
        state.showQuitConfirm(quitText)
        state.onCleared()

        assertEquals(
            GuideDisplayState.Content(null, quitText, confirm = GuideDisplayState.ConfirmKind.QUIT),
            state.redisplay(sigB, true),
        )
    }

    /** 답을 재촉하지 않는다 — 완료 확인과 같은 이유로 정체 접미사가 붙지 않는다. */
    @Test
    fun `종료 확인에는 정체 접미사가 붙지 않는다`() {
        state.show(sigA, null, "설정을 누르세요.")
        state.showQuitConfirm(quitText)

        assertEquals(
            GuideDisplayState.Content(null, quitText, confirm = GuideDisplayState.ConfirmKind.QUIT),
            state.stuckContent(stuck),
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
            state.dismissQuitConfirm(),
        )
    }

    /** 밀어 둔 안내는 세션에 매인다 — 남기면 다음 세션의 ×가 이전 세션 안내를 되살린다. */
    @Test
    fun `세션이 끝나면 밀어 둔 안내도 버려진다`() {
        state.show(sigA, null, "설정을 누르세요.")
        state.showQuitConfirm(quitText)

        state.reset()

        assertNull(state.dismissQuitConfirm())
    }

    /** 새 안내는 자기 화면 서명으로 재표시된다 — 이전 서명을 끌고 가면 재표시가 어긋난다. */
    @Test
    fun `새 안내는 새 화면 서명으로 재표시된다`() {
        state.show(sigA, null, "이전 안내")
        state.show(sigB, null, "새 안내")

        state.onCleared()

        assertEquals(GuideDisplayState.Content(null, "새 안내"), state.redisplay(sigB, true))
    }

    // --- 안정화 상태 머신과의 결합(4차 리뷰 P1) ---------------------------------
    //
    // 전환 직후에는 덜 그려진 과도기 트리가 온다(#32 실측). 그 서명은 화면의 정체가 아니므로
    // WAIT 판정 샘플로 보존 안내를 폐기하면, 안정된 원래 화면이 와도 되살릴 것이 없다.

    @Test
    fun `과도기(WAIT) 샘플은 보존 안내를 폐기하지 않는다`() {
        state.showCapNotice(sigA, capNotice)
        state.onCleared()

        assertNull(state.redisplay(sigB, false)) // 과도기 — 재표시도 폐기도 없음

        assertEquals(GuideDisplayState.Content(null, capNotice), state.redisplay(sigA, true))
    }

    /** 리뷰가 요구한 시퀀스: A 안내 → clear → 과도기 B(WAIT) → A(WAIT) → A(IDLE) 재표시. */
    @Test
    fun `과도기를 지나 안정된 원래 화면에서 재표시된다 - 추적기 결합`() {
        val tracker = ScreenStabilityTracker()
        // 서비스 sampleScreen의 배선 그대로: 판정을 먼저 얻고, WAIT가 아닐 때만 재표시/폐기.
        fun sync(signature: String, at: Long): GuideDisplayState.Content? {
            val decision = tracker.onSample(signature, at)
            return state.redisplay(signature, decision != ScreenStabilityTracker.Decision.WAIT)
        }

        // A 안정 → 요청 → 응답으로 상한 안내 표시(서비스의 SEND→applyAction에 해당)
        tracker.onSample(sigA, 0)
        tracker.onSample(sigA, 100)
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
}
