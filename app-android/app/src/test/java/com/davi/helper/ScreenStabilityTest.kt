package com.davi.helper

import android.view.accessibility.AccessibilityEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenSignatureTest {

    private fun screen(
        vararg elements: UiElement,
        page: Int? = null,
        total: Int? = null,
        imeVisible: Boolean? = null,
        windowTitle: String? = null,
    ) = Screen(
        packageName = "com.example.app",
        windowTitle = windowTitle,
        elements = elements.toList(),
        currentPage = page,
        totalPages = total,
        hasScrollableElement = false,
        imeVisible = imeVisible,
    )

    @Test
    fun `숫자만 다른 화면은 같은 시그니처다`() {
        // 시계·배터리% 때문에 화면이 계속 바뀐 것으로 오인하던 문제(HANDOFF §4).
        val at3 = screen(UiElement(nodeId = "n1", label = "3:45 화요일", role = "TextView"))
        val at4 = screen(UiElement(nodeId = "n1", label = "4:07 화요일", role = "TextView"))

        assertEquals(ScreenSignature.of(at3), ScreenSignature.of(at4))
    }

    @Test
    fun `NumberPicker 선택 숫자가 바뀌면 새 화면 시그니처다`() {
        // SM-S921N 삼성 시계는 선택된 분을 editable EditText의 "00, 분" 형태로 노출한다.
        // 일반 숫자처럼 지우면 스크롤로 30분에 맞춰도 후속 요청이 나가지 않는다.
        val at00 = screen(
            UiElement(nodeId = "n1", label = "00, 분", role = "EditText", editable = true),
        )
        val at30 = screen(
            UiElement(nodeId = "n1", label = "30, 분", role = "EditText", editable = true),
        )

        assertNotEquals(ScreenSignature.of(at00), ScreenSignature.of(at30))
    }

    @Test
    fun `NumberPicker 주변 버튼의 숫자만 바뀌는 것은 volatile로 유지한다`() {
        val before = screen(
            UiElement(nodeId = "n1", label = "29", role = "Button", clickable = true),
            UiElement(nodeId = "n2", label = "31", role = "Button", clickable = true),
        )
        val after = screen(
            UiElement(nodeId = "n1", label = "30", role = "Button", clickable = true),
            UiElement(nodeId = "n2", label = "32", role = "Button", clickable = true),
        )

        assertEquals(ScreenSignature.of(before), ScreenSignature.of(after))
    }

    @Test
    fun `스위치를 켜면 다른 화면이다`() {
        // 이 화면에서 바뀌는 것은 checked 하나뿐이다 — label도 role도 clickable도 그대로다.
        // 상태를 서명에 안 넣으면 스위치를 켜도 IDLE이라 다음 안내가 나가지 않는다(#271).
        val off = screen(
            UiElement(nodeId = "n1", label = "Wi-Fi", role = "Switch", clickable = true, checked = false),
        )
        val on = screen(
            UiElement(nodeId = "n1", label = "Wi-Fi", role = "Switch", clickable = true, checked = true),
        )

        assertNotEquals(ScreenSignature.of(off), ScreenSignature.of(on))
    }

    @Test
    fun `버튼이 활성화되면 다른 화면이다`() {
        val disabled = screen(
            UiElement(nodeId = "n1", label = "다음", role = "Button", clickable = true, enabled = false),
        )
        val enabled = screen(
            UiElement(nodeId = "n1", label = "다음", role = "Button", clickable = true, enabled = true),
        )

        assertNotEquals(ScreenSignature.of(disabled), ScreenSignature.of(enabled))
    }

    @Test
    fun `입력칸이 채워지면 다른 화면이다`() {
        // 입력 원문을 label에 넣지 않으므로(#112) label은 hint 그대로다. 이 값이 유일한 신호다.
        val empty = screen(
            UiElement(
                nodeId = "n1",
                label = "검색어 입력",
                role = "EditText",
                editable = true,
                showingHintText = true,
            ),
        )
        val filled = screen(
            UiElement(
                nodeId = "n1",
                label = "검색어 입력",
                role = "EditText",
                editable = true,
                showingHintText = false,
            ),
        )

        assertNotEquals(ScreenSignature.of(empty), ScreenSignature.of(filled))
    }

    @Test
    fun `선택된 항목이 바뀌면 다른 화면이다`() {
        val first = screen(
            UiElement(nodeId = "n1", label = "연결", clickable = true, selected = true),
            UiElement(nodeId = "n2", label = "소리", clickable = true, selected = false),
        )
        val second = screen(
            UiElement(nodeId = "n1", label = "연결", clickable = true, selected = false),
            UiElement(nodeId = "n2", label = "소리", clickable = true, selected = true),
        )

        assertNotEquals(ScreenSignature.of(first), ScreenSignature.of(second))
    }

    @Test
    fun `미관측과 꺼짐은 다른 상태다`() {
        // 낡은 앱의 침묵(null)을 false로 접으면 세 상태가 죽는다.
        val unknown = screen(UiElement(nodeId = "n1", label = "Wi-Fi", role = "Switch", clickable = true))
        val off = screen(
            UiElement(nodeId = "n1", label = "Wi-Fi", role = "Switch", clickable = true, checked = false),
        )

        assertNotEquals(ScreenSignature.of(unknown), ScreenSignature.of(off))
    }

    @Test
    fun `창 제목이 바뀌면 다른 화면이다`() {
        val connection = screen(windowTitle = "연결")
        val battery = screen(windowTitle = "배터리")

        assertNotEquals(ScreenSignature.of(connection), ScreenSignature.of(battery))
    }

    @Test
    fun `제목에 붙는 숫자는 volatile로 본다`() {
        // "받은편지함 (3)"처럼 개수가 붙는 제목이 매 폴링마다 새 화면이 되면 안 된다.
        val three = screen(windowTitle = "받은편지함 (3)")
        val four = screen(windowTitle = "받은편지함 (4)")

        assertEquals(ScreenSignature.of(three), ScreenSignature.of(four))
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

    @Test
    fun `입력 포커스나 IME 상태가 바뀌면 다른 화면이다`() {
        val before = screen(
            UiElement(nodeId = "n1", label = "검색어 입력", role = "EditText", editable = true),
            imeVisible = false,
        )
        val ready = screen(
            UiElement(
                nodeId = "n1",
                label = "검색어 입력",
                role = "EditText",
                editable = true,
                focused = true,
            ),
            imeVisible = true,
        )

        assertNotEquals(ScreenSignature.of(before), ScreenSignature.of(ready))
    }

    @Test
    fun `창 제목만 있고 요소 label이 없으면 준비된 화면이 아니다`() {
        val splash = screen(
            UiElement(nodeId = "n1", label = "   ", role = "FrameLayout"),
            windowTitle = "카카오 T",
        )

        assertFalse(splash.hasLabeledElement())
        assertTrue(
            screen(UiElement(nodeId = "n1", label = "어디로 갈까요?", role = "TextView"))
                .hasLabeledElement(),
        )
    }
}

class InputStateEventTest {

    @Test
    fun `포커스와 IME 창 변화는 안정화 샘플을 예약한다`() {
        assertTrue(
            GuideAccessibilityService.isInterestingEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED),
        )
        assertTrue(
            GuideAccessibilityService.isInterestingEvent(AccessibilityEvent.TYPE_WINDOWS_CHANGED),
        )
        // 텍스트 원문 변화 자체는 상태 계약이 아니다. 다른 content 이벤트가 오더라도
        // 입력 text를 제외한 시그니처가 같아 추가 요청으로 이어지지 않는다.
        assertFalse(
            GuideAccessibilityService.isInterestingEvent(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED),
        )
    }
}

class ScreenStabilityTrackerTest {

    private val tracker = ScreenStabilityTracker()

    @Test
    fun `흔들리는 동안에는 보내지 않는다`() {
        assertEquals(Decision.WAIT, tracker.onSample("a", 0, hasLabeledElement = true))
        assertEquals(Decision.WAIT, tracker.onSample("b", 100, hasLabeledElement = true))
        assertEquals(Decision.WAIT, tracker.onSample("c", 200, hasLabeledElement = true))
    }

    @Test
    fun `같은 화면이 두 번 연속이면 보낸다`() {
        tracker.onSample("a", 0, hasLabeledElement = true)

        assertEquals(Decision.SEND, tracker.onSample("a", 100, hasLabeledElement = true))
    }

    @Test
    fun `label 없는 화면은 반복돼도 보내지 않고 label이 생긴 뒤 다시 안정화한다`() {
        assertEquals(Decision.WAIT, tracker.onSample("splash", 0, hasLabeledElement = false))
        assertEquals(Decision.WAIT, tracker.onSample("splash", 100, hasLabeledElement = false))
        assertEquals("splash", tracker.currentSignature())

        assertEquals(Decision.WAIT, tracker.onSample("content", 200, hasLabeledElement = true))
        assertEquals(Decision.SEND, tracker.onSample("content", 300, hasLabeledElement = true))
    }

    @Test
    fun `label 없는 화면이 오래 이어지면 서버 대신 읽기 불가를 알린다`() {
        assertEquals(Decision.WAIT, tracker.onSample("blank", 0, hasLabeledElement = false))
        assertEquals(
            Decision.WAIT,
            tracker.onSample(
                "blank",
                ScreenStabilityTracker.UNREADABLE_AFTER_MS - 1,
                hasLabeledElement = false,
            ),
        )
        assertEquals(
            Decision.UNREADABLE,
            tracker.onSample(
                "blank",
                ScreenStabilityTracker.UNREADABLE_AFTER_MS,
                hasLabeledElement = false,
            ),
        )
        // 상태 판정이라 이후 샘플도 UNREADABLE이다. 표시 상태가 같은 문구 재그리기를 막는다.
        assertEquals(
            Decision.UNREADABLE,
            tracker.onSample(
                "blank",
                ScreenStabilityTracker.UNREADABLE_AFTER_MS + 1_000,
                hasLabeledElement = false,
            ),
        )
    }

    @Test
    fun `빈 화면 시그니처가 흔들려도 읽기 불가 타이머는 초기화하지 않는다`() {
        assertEquals(Decision.WAIT, tracker.onSample("blank-a", 0, hasLabeledElement = false))
        assertEquals(Decision.WAIT, tracker.onSample("blank-b", 3_500, hasLabeledElement = false))
        assertEquals(
            Decision.UNREADABLE,
            tracker.onSample(
                "blank-a",
                ScreenStabilityTracker.UNREADABLE_AFTER_MS,
                hasLabeledElement = false,
            ),
        )
    }

    @Test
    fun `읽기 불가 뒤 label이 생기면 타이머를 풀고 안정화를 다시 센다`() {
        tracker.onSample("blank", 0, hasLabeledElement = false)
        tracker.onSample(
            "blank",
            ScreenStabilityTracker.UNREADABLE_AFTER_MS,
            hasLabeledElement = false,
        )

        assertEquals(Decision.WAIT, tracker.onSample("content", 8_000, hasLabeledElement = true))
        assertEquals(Decision.SEND, tracker.onSample("content", 8_100, hasLabeledElement = true))
    }

    @Test
    fun `세션이 바뀌면 읽기 불가 타이머도 비운다`() {
        tracker.onSample("blank", 0, hasLabeledElement = false)

        tracker.reset()

        assertEquals(
            Decision.WAIT,
            tracker.onSample(
                "blank",
                ScreenStabilityTracker.UNREADABLE_AFTER_MS,
                hasLabeledElement = false,
            ),
        )
    }

    @Test
    fun `화면 관측을 재개하면 관측 불가 시간은 읽기 불가 타이머에서 뺀다`() {
        tracker.onSample("blank", 0, hasLabeledElement = false)

        val resumedAt = 10_000L
        tracker.markResumed(resumedAt, requery = false)

        assertEquals(
            Decision.WAIT,
            tracker.onSample(
                "blank",
                resumedAt + ScreenStabilityTracker.UNREADABLE_AFTER_MS - 1,
                hasLabeledElement = false,
            ),
        )
        assertEquals(
            Decision.UNREADABLE,
            tracker.onSample(
                "blank",
                resumedAt + ScreenStabilityTracker.UNREADABLE_AFTER_MS,
                hasLabeledElement = false,
            ),
        )
    }

    @Test
    fun `이미 보낸 화면은 다시 보내지 않는다`() {
        tracker.onSample("a", 0, hasLabeledElement = true)
        tracker.onSample("a", 100, hasLabeledElement = true)
        tracker.markSent("a", 100)

        assertEquals(Decision.IDLE, tracker.onSample("a", 200, hasLabeledElement = true))
    }

    @Test
    fun `안내 뒤 화면이 그대로면 정체를 한 번만 알린다`() {
        tracker.onSample("a", 0, hasLabeledElement = true)
        tracker.onSample("a", 100, hasLabeledElement = true)
        tracker.markSent("a", 100)
        tracker.markFinished()

        val stuckAt = 100 + ScreenStabilityTracker.STUCK_AFTER_MS
        assertEquals(Decision.IDLE, tracker.onSample("a", stuckAt - 1, hasLabeledElement = true))
        assertEquals(Decision.STUCK, tracker.onSample("a", stuckAt, hasLabeledElement = true))
        assertEquals(Decision.IDLE, tracker.onSample("a", stuckAt + 1_000, hasLabeledElement = true))
    }

    /** "안내 후 화면 그대로 7초"는 사용자가 방금 조작했으면 그 조작부터 센다(#319). */
    @Test
    fun `눌림이 오면 정체 타이머가 그 시점부터 다시 돈다`() {
        tracker.onSample("a", 0, hasLabeledElement = true)
        tracker.onSample("a", 100, hasLabeledElement = true)
        tracker.markSent("a", 100)
        tracker.markFinished()

        tracker.markPressed(5_000)

        val stuckAt = 5_000 + ScreenStabilityTracker.STUCK_AFTER_MS
        assertEquals(Decision.IDLE, tracker.onSample("a", stuckAt - 1, hasLabeledElement = true))
        assertEquals(Decision.STUCK, tracker.onSample("a", stuckAt, hasLabeledElement = true))
    }

    /** 정체가 이미 보고된 뒤의 눌림도 다음 정체(와 눌림 확인 재질의)를 되살린다. */
    @Test
    fun `정체 보고 후에도 눌림이 오면 정체가 다시 뜬다`() {
        tracker.onSample("a", 0, hasLabeledElement = true)
        tracker.onSample("a", 100, hasLabeledElement = true)
        tracker.markSent("a", 100)
        tracker.markFinished()
        val firstStuck = 100 + ScreenStabilityTracker.STUCK_AFTER_MS
        assertEquals(Decision.STUCK, tracker.onSample("a", firstStuck, hasLabeledElement = true))

        tracker.markPressed(firstStuck + 1_000)

        val secondStuck = firstStuck + 1_000 + ScreenStabilityTracker.STUCK_AFTER_MS
        assertEquals(Decision.IDLE, tracker.onSample("a", secondStuck - 1, hasLabeledElement = true))
        assertEquals(Decision.STUCK, tracker.onSample("a", secondStuck, hasLabeledElement = true))
    }

    @Test
    fun `화면이 바뀌면 정체 판정이 풀린다`() {
        tracker.onSample("a", 0, hasLabeledElement = true)
        tracker.onSample("a", 100, hasLabeledElement = true)
        tracker.markSent("a", 100)
        tracker.markFinished()
        tracker.onSample("a", 100 + ScreenStabilityTracker.STUCK_AFTER_MS, hasLabeledElement = true)

        assertEquals(Decision.WAIT, tracker.onSample("b", 20_000, hasLabeledElement = true))
        assertEquals(Decision.SEND, tracker.onSample("b", 20_100, hasLabeledElement = true))
    }

    @Test
    fun `요청이 실패하면 같은 화면을 다시 보낸다`() {
        // 이벤트 기반일 때는 다음 이벤트가 재시도를 해줬다. 화면이 그대로면 그 이벤트가 오지 않는다.
        tracker.onSample("a", 0, hasLabeledElement = true)
        tracker.onSample("a", 100, hasLabeledElement = true)
        tracker.markSent("a", 100)
        tracker.markFailed()
        tracker.markFinished()

        val retryAt = 100 + ScreenStabilityTracker.RETRY_BASE_MS
        assertEquals(Decision.SEND, tracker.onSample("a", retryAt, hasLabeledElement = true))
    }

    @Test
    fun `재시도는 백오프만큼 기다린다`() {
        // 서버가 죽어 있을 때 폴링 주기마다 때리면 무료 티어 15 RPM을 1분에 태운다.
        // 실기기에서 adb reverse가 끊긴 채로 이 루프를 실제로 만들어 봤다.
        tracker.onSample("a", 0, hasLabeledElement = true)
        tracker.onSample("a", 100, hasLabeledElement = true)
        tracker.markSent("a", 100)
        tracker.markFailed()
        tracker.markFinished()

        assertEquals(Decision.IDLE, tracker.onSample("a", 200, hasLabeledElement = true))
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
    fun `진행 중인 요청이 있으면 새 화면을 보내지 않는다`() {
        // A가 도는 동안 B를 SEND로 내면, 호출자는 "보냈다"고 기록하는데 실제 요청은
        // in-flight 가드에 막혀 나가지 않는다 → B의 안내가 영영 오지 않는다(PR #61 리뷰 P1).
        tracker.onSample("a", 0, hasLabeledElement = true)
        tracker.onSample("a", 100, hasLabeledElement = true)
        tracker.markSent("a", 100)

        tracker.onSample("b", 200, hasLabeledElement = true)

        assertEquals(Decision.IDLE, tracker.onSample("b", 300, hasLabeledElement = true))
    }

    @Test
    fun `요청이 끝나면 그동안 바뀐 화면을 보낸다`() {
        tracker.onSample("a", 0, hasLabeledElement = true)
        tracker.onSample("a", 100, hasLabeledElement = true)
        tracker.markSent("a", 100)
        tracker.onSample("b", 200, hasLabeledElement = true)
        tracker.onSample("b", 300, hasLabeledElement = true)

        tracker.markFinished()

        assertEquals(Decision.SEND, tracker.onSample("b", 400, hasLabeledElement = true))
    }

    @Test
    fun `보내지 못한 화면은 전송으로 기록되지 않는다`() {
        // 호출자는 요청이 실제로 시작됐을 때만 markSent를 부른다. 그 계약이 지켜지면
        // 막혔던 화면은 다음 기회에 그대로 다시 나간다.
        tracker.onSample("a", 0, hasLabeledElement = true)
        tracker.onSample("a", 100, hasLabeledElement = true)
        tracker.markSent("a", 100)
        tracker.onSample("b", 200, hasLabeledElement = true)
        assertEquals(Decision.IDLE, tracker.onSample("b", 300, hasLabeledElement = true))

        tracker.markFinished()

        assertEquals(Decision.SEND, tracker.onSample("b", 400, hasLabeledElement = true))
        tracker.markSent("b", 400)
        assertEquals(Decision.IDLE, tracker.onSample("b", 500, hasLabeledElement = true))
    }

    @Test
    fun `응답을 기다리는 중에는 정체로 보지 않는다`() {
        // 느린 응답과 멈춘 화면은 다르다. 기다리는 동안 "화면이 그대로예요"라고 말하면 헷갈린다.
        tracker.onSample("a", 0, hasLabeledElement = true)
        tracker.onSample("a", 100, hasLabeledElement = true)
        tracker.markSent("a", 100)

        val stuckAt = 100 + ScreenStabilityTracker.STUCK_AFTER_MS
        assertEquals(Decision.IDLE, tracker.onSample("a", stuckAt, hasLabeledElement = true))

        tracker.markFinished()

        assertEquals(Decision.STUCK, tracker.onSample("a", stuckAt, hasLabeledElement = true))
    }

    @Test
    fun `응답이 도착한 시점의 화면을 대조할 수 있다`() {
        tracker.onSample("a", 0, hasLabeledElement = true)
        assertEquals("a", tracker.currentSignature())

        tracker.onSample("b", 100, hasLabeledElement = true)

        assertEquals("b", tracker.currentSignature())
    }

    @Test
    fun `세션이 바뀌면 이전 화면 기억을 끌고 가지 않는다`() {
        tracker.onSample("a", 0, hasLabeledElement = true)
        tracker.onSample("a", 100, hasLabeledElement = true)
        tracker.markSent("a", 100)

        tracker.reset()

        assertEquals(Decision.WAIT, tracker.onSample("a", 200, hasLabeledElement = true))
        assertEquals(Decision.SEND, tracker.onSample("a", 300, hasLabeledElement = true))
    }

    @Test
    fun `포커스와 IME가 안정된 새 상태는 한 번만 보낸다`() {
        tracker.onSample("before", 0, hasLabeledElement = true)
        assertEquals(Decision.SEND, tracker.onSample("before", 100, hasLabeledElement = true))
        tracker.markSent("before", 100)
        tracker.markFinished()

        assertEquals(Decision.WAIT, tracker.onSample("input-ready", 200, hasLabeledElement = true))
        assertEquals(Decision.SEND, tracker.onSample("input-ready", 300, hasLabeledElement = true))
        tracker.markSent("input-ready", 300)
        tracker.markFinished()

        assertEquals(Decision.IDLE, tracker.onSample("input-ready", 400, hasLabeledElement = true))
        assertEquals(Decision.IDLE, tracker.onSample("input-ready", 500, hasLabeledElement = true))
    }

    @Test
    fun `포커스가 빠르게 왕복하면 안정된 마지막 상태만 보낸다`() {
        tracker.onSample("before", 0, hasLabeledElement = true)
        assertEquals(Decision.SEND, tracker.onSample("before", 100, hasLabeledElement = true))
        tracker.markSent("before", 100)
        tracker.markFinished()

        assertEquals(Decision.WAIT, tracker.onSample("focus-a", 200, hasLabeledElement = true))
        assertEquals(Decision.WAIT, tracker.onSample("focus-b", 250, hasLabeledElement = true))
        assertEquals(Decision.WAIT, tracker.onSample("focus-a", 300, hasLabeledElement = true))
        assertEquals(Decision.SEND, tracker.onSample("focus-a", 400, hasLabeledElement = true))
        tracker.markSent("focus-a", 400)
        tracker.markFinished()

        assertEquals(Decision.IDLE, tracker.onSample("focus-a", 500, hasLabeledElement = true))
    }
}

private typealias Decision = ScreenStabilityTracker.Decision
