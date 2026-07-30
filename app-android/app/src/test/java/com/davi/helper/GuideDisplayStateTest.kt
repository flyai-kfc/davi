package com.davi.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 재표시·정체 접미사 판정(PR #63 리뷰 P1).
 *
 * 핵심 시나리오: 상한 안내의 '안내 중지' 버튼은 우리 앱에만 있어서, 문구를 따르러 앱을 열면
 * root=null 청소가 안내를 지운다. 중지 없이 작업 화면에 돌아온 사용자에게 같은 안내가
 * 다시 보여야 한다 — 이 회복 경로가 없으면 세션은 살았는데 화면은 영구히 빈다.
 */
class GuideDisplayStateTest {

    private val state = GuideDisplayState()

    private val capNotice = "다 된 것 같은데 확인하지 못했어요."
    private val stuck = "화면이 그대로예요."

    @Test
    fun `상한 안내는 지워진 뒤 유효 화면에서 재표시된다`() {
        state.showCapNotice(capNotice)

        state.onCleared() // 사용자가 '안내 중지'를 찾아 우리 앱을 열었다(root=null 청소)

        assertEquals(GuideDisplayState.Content(null, capNotice), state.redisplay())
    }

    @Test
    fun `일반 안내도 지워지면 재표시된다`() {
        state.show(listOf(0, 0, 10, 10), "카메라를 누르세요.")

        state.onCleared() // 런처 dump 간헐 실패로 root=null 샘플이 한 번 떴다

        assertEquals(
            GuideDisplayState.Content(listOf(0, 0, 10, 10), "카메라를 누르세요."),
            state.redisplay(),
        )
    }

    /** 떠 있는 안내를 폴링마다 다시 그리면 오버레이가 깜빡인다. 지워졌을 때만 되살린다. */
    @Test
    fun `떠 있는 동안에는 재표시하지 않는다`() {
        state.showCapNotice(capNotice)

        assertNull(state.redisplay())
    }

    @Test
    fun `재표시는 한 번만 일어난다`() {
        state.showCapNotice(capNotice)
        state.onCleared()

        state.redisplay()

        assertNull(state.redisplay())
    }

    /** 상한 안내는 행동 지시가 아니라서 "한 번 더 해 보세요"가 붙으면 앞뒤가 안 맞는다. */
    @Test
    fun `상한 안내에는 정체 접미사가 붙지 않는다`() {
        state.showCapNotice(capNotice)

        assertEquals(GuideDisplayState.Content(null, capNotice), state.stuckContent(stuck))
    }

    @Test
    fun `일반 안내에는 정체 접미사가 붙는다`() {
        state.show(null, "화면을 넘기세요.")

        assertEquals(
            GuideDisplayState.Content(null, "화면을 넘기세요.\n$stuck"),
            state.stuckContent(stuck),
        )
    }

    /** 이전 세션의 안내가 새 세션 화면 위에 되살아나면 안 된다. */
    @Test
    fun `세션이 리셋되면 재표시하지 않는다`() {
        state.showCapNotice(capNotice)
        state.onCleared()

        state.reset()

        assertNull(state.redisplay())
        assertNull(state.stuckContent(stuck))
    }

    @Test
    fun `안내가 없으면 재표시도 정체 알림도 없다`() {
        assertNull(state.redisplay())
        assertNull(state.stuckContent(stuck))
    }

    /** 상한 안내 뒤에 새 안내가 오면(화면이 바뀌어 재질의) 접미사 규칙도 일반으로 돌아온다. */
    @Test
    fun `새 안내가 오면 접미사 규칙이 되살아난다`() {
        state.showCapNotice(capNotice)

        state.show(null, "설정을 누르세요.")

        assertEquals(
            GuideDisplayState.Content(null, "설정을 누르세요.\n$stuck"),
            state.stuckContent(stuck),
        )
    }
}
