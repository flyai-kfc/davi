package com.davi.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ScreenSignatureTest {

    private fun screen(vararg elements: UiElement, page: Int? = null, total: Int? = null) = Screen(
        packageName = "com.example.app",
        elements = elements.toList(),
        currentPage = page,
        totalPages = total,
        hasScrollableElement = false,
    )

    @Test
    fun `숫자만 다른 화면은 같은 시그니처다`() {
        // 시계·배터리% 때문에 화면이 계속 바뀐 것으로 오인하던 문제(HANDOFF §4).
        val at3 = screen(UiElement(nodeId = "n1", label = "3:45 화요일", role = "TextView"))
        val at4 = screen(UiElement(nodeId = "n1", label = "4:07 화요일", role = "TextView"))

        assertEquals(ScreenSignature.of(at3), ScreenSignature.of(at4))
    }

    @Test
    fun `요소가 늘어나면 다른 시그니처다`() {
        // 관통에서 실제로 나온 전환 과도기: 카메라 elements=1 -> 14 (#32).
        val settling = screen(UiElement(nodeId = "n1", label = "카메라", clickable = true))
        val settled = screen(
            UiElement(nodeId = "n1", label = "카메라", clickable = true),
            UiElement(nodeId = "n2", label = "촬영", clickable = true),
        )

        assertNotEquals(ScreenSignature.of(settling), ScreenSignature.of(settled))
    }

    @Test
    fun `좌표만 흔들리는 것은 같은 화면으로 본다`() {
        val before = screen(UiElement(nodeId = "n1", label = "확인", bounds = listOf(0, 0, 10, 10)))
        val after = screen(UiElement(nodeId = "n1", label = "확인", bounds = listOf(1, 2, 11, 12)))

        assertEquals(ScreenSignature.of(before), ScreenSignature.of(after))
    }

    @Test
    fun `페이지가 바뀌면 다른 화면이다`() {
        // 런처는 페이지를 넘겨도 요소 라벨이 비슷하다. page 메타가 갈라준다(C5).
        val first = screen(UiElement(nodeId = "n1", label = "앱", clickable = true), page = 1, total = 3)
        val second = screen(UiElement(nodeId = "n1", label = "앱", clickable = true), page = 2, total = 3)

        assertNotEquals(ScreenSignature.of(first), ScreenSignature.of(second))
    }
}

class ScreenStabilityTrackerTest {

    private val tracker = ScreenStabilityTracker()

    @Test
    fun `흔들리는 동안에는 보내지 않는다`() {
        assertEquals(Decision.WAIT, tracker.onSample("a", 0))
        assertEquals(Decision.WAIT, tracker.onSample("b", 100))
        assertEquals(Decision.WAIT, tracker.onSample("c", 200))
    }

    @Test
    fun `같은 화면이 두 번 연속이면 보낸다`() {
        tracker.onSample("a", 0)

        assertEquals(Decision.SEND, tracker.onSample("a", 100))
    }

    @Test
    fun `이미 보낸 화면은 다시 보내지 않는다`() {
        tracker.onSample("a", 0)
        tracker.onSample("a", 100)
        tracker.markSent("a", 100)

        assertEquals(Decision.IDLE, tracker.onSample("a", 200))
    }

    @Test
    fun `안내 뒤 화면이 그대로면 정체를 한 번만 알린다`() {
        tracker.onSample("a", 0)
        tracker.onSample("a", 100)
        tracker.markSent("a", 100)

        val stuckAt = 100 + ScreenStabilityTracker.STUCK_AFTER_MS
        assertEquals(Decision.IDLE, tracker.onSample("a", stuckAt - 1))
        assertEquals(Decision.STUCK, tracker.onSample("a", stuckAt))
        assertEquals(Decision.IDLE, tracker.onSample("a", stuckAt + 1_000))
    }

    @Test
    fun `화면이 바뀌면 정체 판정이 풀린다`() {
        tracker.onSample("a", 0)
        tracker.onSample("a", 100)
        tracker.markSent("a", 100)
        tracker.onSample("a", 100 + ScreenStabilityTracker.STUCK_AFTER_MS)

        assertEquals(Decision.WAIT, tracker.onSample("b", 20_000))
        assertEquals(Decision.SEND, tracker.onSample("b", 20_100))
    }

    @Test
    fun `요청이 실패하면 같은 화면을 다시 보낸다`() {
        // 이벤트 기반일 때는 다음 이벤트가 재시도를 해줬다. 화면이 그대로면 그 이벤트가 오지 않는다.
        tracker.onSample("a", 0)
        tracker.onSample("a", 100)
        tracker.markSent("a", 100)
        tracker.markFailed()

        val retryAt = 100 + ScreenStabilityTracker.RETRY_BASE_MS
        assertEquals(Decision.SEND, tracker.onSample("a", retryAt))
    }

    @Test
    fun `재시도는 백오프만큼 기다린다`() {
        // 서버가 죽어 있을 때 폴링 주기마다 때리면 무료 티어 15 RPM을 1분에 태운다.
        // 실기기에서 adb reverse가 끊긴 채로 이 루프를 실제로 만들어 봤다.
        tracker.onSample("a", 0)
        tracker.onSample("a", 100)
        tracker.markSent("a", 100)
        tracker.markFailed()

        assertEquals(Decision.IDLE, tracker.onSample("a", 200))
        assertEquals(ScreenStabilityTracker.RETRY_BASE_MS, tracker.backoffMs())
    }

    @Test
    fun `연속 실패마다 백오프가 두 배로 늘고 상한에서 멈춘다`() {
        tracker.markFailed()
        assertEquals(ScreenStabilityTracker.RETRY_BASE_MS, tracker.backoffMs())

        tracker.markFailed()
        assertEquals(ScreenStabilityTracker.RETRY_BASE_MS * 2, tracker.backoffMs())

        repeat(30) { tracker.markFailed() }
        assertEquals(ScreenStabilityTracker.RETRY_MAX_MS, tracker.backoffMs())
    }

    @Test
    fun `성공하면 백오프가 풀린다`() {
        tracker.markFailed()
        tracker.markFailed()

        tracker.markSucceeded()

        assertEquals(0L, tracker.backoffMs())
    }

    @Test
    fun `세션이 바뀌면 이전 화면 기억을 끌고 가지 않는다`() {
        tracker.onSample("a", 0)
        tracker.onSample("a", 100)
        tracker.markSent("a", 100)

        tracker.reset()

        assertEquals(Decision.WAIT, tracker.onSample("a", 200))
        assertEquals(Decision.SEND, tracker.onSample("a", 300))
    }
}

private typealias Decision = ScreenStabilityTracker.Decision
