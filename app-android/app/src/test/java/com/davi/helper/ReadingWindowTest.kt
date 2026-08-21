package com.davi.helper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 읽는 중이 안내창을 가져갈지의 판정 고정(GD-2r, #295).
 *
 * **이 파일의 이유는 [twoConsecutiveWindows_secondOneStillArms] 하나다.** 구현 초안이
 * `on == reading` 전이로 예약했는데, 그러면 첫 구간만 바뀌고 두 번째부터 문구가 영영 안
 * 바뀐다. 증상이 "화면을 두 번 옮겨야" 드러나 실기기에서만 잡혔다 — 여기서 고정한다.
 */
class ReadingWindowTest {

    @Test
    fun freshWindow_arms() {
        assertTrue(ReadingWindow.shouldArmOnset(windowTaken = false, onsetPending = false))
    }

    /** 이미 읽는 중 문구가 떠 있으면 다시 바꿀 것이 없다. */
    @Test
    fun windowAlreadyTaken_doesNotArm() {
        assertFalse(ReadingWindow.shouldArmOnset(windowTaken = true, onsetPending = false))
    }

    /** 서비스가 폴링마다 부른다 — 예약이 겹치면 지연이 리셋돼 표시가 늦어진다. */
    @Test
    fun onsetAlreadyPending_doesNotArmAgain() {
        assertFalse(ReadingWindow.shouldArmOnset(windowTaken = false, onsetPending = true))
    }

    /**
     * **GD-1 / GD-2r 갈래 고정**(#314). 창에 안내가 떠 있으면 사용자가 방금 무언가를 누른
     * 뒤라 GD-2r이고, 비어 있으면 세션이 막 시작한 GD-1이다. 뒤집히면 증상이 실기기에서만
     * 드러나는 종류라(두 번째 구간부터 문구가 안 바뀐다) 여기서 고정한다.
     */
    @Test
    fun midGuide_isDecidedByPendingGuide() {
        assertTrue(ReadingWindow.isMidGuide(hasPendingGuide = true))
        assertFalse(ReadingWindow.isMidGuide(hasPendingGuide = false))
    }

    /**
     * **회귀 고정** — 읽는 중이 끊기지 않은 채로 두 구간을 지난다.
     *
     * 켜짐 → 예약 → 창 가져감 → **서버 안내 도착(창을 안내가 가져감)** → 화면이 또 움직임.
     * 이때 `on`은 내내 true라 전이가 없다. 그래도 두 번째 예약이 걸려야 한다.
     */
    @Test
    fun twoConsecutiveWindows_secondOneStillArms() {
        // 1구간: 예약 → 실행(창 가져감)
        assertTrue(ReadingWindow.shouldArmOnset(windowTaken = false, onsetPending = false))
        assertFalse(ReadingWindow.shouldArmOnset(windowTaken = false, onsetPending = true))
        assertFalse(ReadingWindow.shouldArmOnset(windowTaken = true, onsetPending = false))

        // 서버 안내가 도착해 창을 가져갔다(windowTaken=false). on은 여전히 true다.
        assertTrue(ReadingWindow.shouldArmOnset(windowTaken = false, onsetPending = false))
    }
}
