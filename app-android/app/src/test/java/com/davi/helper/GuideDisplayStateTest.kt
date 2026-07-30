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

    @Test
    fun `상한 안내는 지워진 뒤 같은 화면에서 재표시된다`() {
        state.showCapNotice(sigA, capNotice)

        state.onCleared() // 사용자가 '안내 중지'를 찾아 우리 앱을 열었다(root=null 청소)

        assertEquals(GuideDisplayState.Content(null, capNotice), state.redisplay(sigA))
    }

    /** 이전 화면의 안내가 다른 화면 위에 그려지면 안 된다(2차 리뷰 P1, #61 P1과 같은 계급). */
    @Test
    fun `지워진 뒤 다른 화면에서는 재표시하지 않는다`() {
        state.showCapNotice(sigA, capNotice)
        state.onCleared()

        assertNull(state.redisplay(sigB))
    }

    /** 다른 화면을 본 순간 이전 안내는 폐기된다 — 원래 화면에 돌아와도 안 되살린다. */
    @Test
    fun `다른 화면에서 폐기된 안내는 원래 화면에서도 재표시하지 않는다`() {
        state.showCapNotice(sigA, capNotice)
        state.onCleared()

        state.redisplay(sigB) // 폐기 지점

        assertNull(state.redisplay(sigA))
    }

    @Test
    fun `일반 안내도 지워지면 같은 화면에서 재표시된다`() {
        state.show(sigA, listOf(0, 0, 10, 10), "카메라를 누르세요.")

        state.onCleared() // 런처 dump 간헐 실패로 root=null 샘플이 한 번 떴다

        assertEquals(
            GuideDisplayState.Content(listOf(0, 0, 10, 10), "카메라를 누르세요."),
            state.redisplay(sigA),
        )
    }

    /** 떠 있는 안내를 폴링마다 다시 그리면 오버레이가 깜빡인다. 지워졌을 때만 되살린다. */
    @Test
    fun `떠 있는 동안에는 재표시하지 않는다`() {
        state.showCapNotice(sigA, capNotice)

        assertNull(state.redisplay(sigA))
    }

    @Test
    fun `재표시는 한 번만 일어난다`() {
        state.showCapNotice(sigA, capNotice)
        state.onCleared()

        state.redisplay(sigA)

        assertNull(state.redisplay(sigA))
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

        assertNull(state.redisplay(sigA))
        assertNull(state.stuckContent(stuck))
    }

    @Test
    fun `안내가 없으면 재표시도 정체 알림도 없다`() {
        assertNull(state.redisplay(sigA))
        assertNull(state.stuckContent(stuck))
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

    /** 새 안내는 자기 화면 서명으로 재표시된다 — 이전 서명을 끌고 가면 재표시가 어긋난다. */
    @Test
    fun `새 안내는 새 화면 서명으로 재표시된다`() {
        state.show(sigA, null, "이전 안내")
        state.show(sigB, null, "새 안내")

        state.onCleared()

        assertEquals(GuideDisplayState.Content(null, "새 안내"), state.redisplay(sigB))
    }
}
