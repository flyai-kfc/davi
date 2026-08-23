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

    @Test
    fun `label만 다른 화면은 구조가 같고 내용만 다르다`() {
        // 광고 배너 로테이션의 모양이다(#380). 구조 축이 같아야 로테이션이 재질의·응답 폐기·
        // 안내 소속 어느 것도 흔들지 못한다.
        val ad1 = screen(
            UiElement(nodeId = "n1", label = "치킨 반값 쿠폰", role = "TextView"),
            UiElement(nodeId = "n2", label = "주문하기", role = "Button", clickable = true),
        )
        val ad2 = screen(
            UiElement(nodeId = "n1", label = "피자 무료 배달", role = "TextView"),
            UiElement(nodeId = "n2", label = "주문하기", role = "Button", clickable = true),
        )

        assertEquals(ScreenSignature.of(ad1).structure, ScreenSignature.of(ad2).structure)
        assertNotEquals(ScreenSignature.of(ad1).content, ScreenSignature.of(ad2).content)
    }

    @Test
    fun `행동 상태 변화는 구조 축에 나타난다`() {
        // 토글·활성화·입력 채움은 안내를 무효화하는 변화라 게이트 없이 재질의를 열어야 한다(#271·#380).
        val off = screen(
            UiElement(nodeId = "n1", label = "Wi-Fi", role = "Switch", clickable = true, checked = false),
        )
        val on = screen(
            UiElement(nodeId = "n1", label = "Wi-Fi", role = "Switch", clickable = true, checked = true),
        )

        assertNotEquals(ScreenSignature.of(off).structure, ScreenSignature.of(on).structure)
    }

    @Test
    fun `창 제목은 내용 축에 나타난다`() {
        // 제목은 label과 같은 취급이다(#288) — 안 읽은 개수처럼 volatile해서 구조 축에 두면
        // 광고와 같은 방식으로 소속·폐기를 흔든다.
        val a = screen(UiElement(nodeId = "n1", label = "메일함", role = "TextView"), windowTitle = "받은편지함")
        val b = screen(UiElement(nodeId = "n1", label = "메일함", role = "TextView"), windowTitle = "보낸편지함")

        assertEquals(ScreenSignature.of(a).structure, ScreenSignature.of(b).structure)
        assertNotEquals(ScreenSignature.of(a).content, ScreenSignature.of(b).content)
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

/** 타깃 앵커(#380) — 안내가 가리키는 슬롯이 구조 보존 재정렬로 밀렸는지 본다. */
class GuidedSlotAnchorTest {

    private fun screen(vararg elements: UiElement) = Screen(
        packageName = "com.example.app",
        elements = elements.toList(),
    )

    @Test
    fun `슬롯의 label이 바뀌면 밀린 것이다`() {
        // 새 메시지가 대화방 순서를 밀면 같은 node id가 다른 줄을 뜻하게 된다.
        val after = screen(UiElement(nodeId = "n3", label = "김철수", role = "View"))

        assertTrue(after.guidedSlotLabelChanged("n3", "이영희"))
    }

    @Test
    fun `같은 슬롯이면 밀린 것이 아니다`() {
        val after = screen(UiElement(nodeId = "n3", label = "이영희", role = "View"))

        assertFalse(after.guidedSlotLabelChanged("n3", "이영희"))
    }

    @Test
    fun `숫자만 다른 label은 밀린 것이 아니다`() {
        // 시그니처와 같은 정규화를 쓰지 않으면 대상에 붙은 시계·개수가 앵커를 매 초 흔든다.
        val after = screen(UiElement(nodeId = "n3", label = "안 읽음 12", role = "View"))

        assertFalse(after.guidedSlotLabelChanged("n3", "안 읽음 #"))
    }

    @Test
    fun `슬롯이 사라졌으면 구조 축에 맡긴다`() {
        val after = screen(UiElement(nodeId = "n1", label = "이영희", role = "View"))

        assertFalse(after.guidedSlotLabelChanged("n3", "이영희"))
    }

    @Test
    fun `안내 대상이 없으면 앵커도 없다`() {
        val after = screen(UiElement(nodeId = "n3", label = "이영희", role = "View"))

        assertFalse(after.guidedSlotLabelChanged(null, "이영희"))
        assertFalse(after.guidedSlotLabelChanged("n3", null))
    }
}

class ScreenStabilityTrackerTest {

    private val tracker = ScreenStabilityTracker()

    /**
     * 기존 시나리오는 구조 축만 다른 화면으로 표현한다 — "a"와 "b"는 구조가 다른 두 화면이다.
     * 내용 축이 필요한 시나리오(#380)만 [ScreenSig]를 직접 만든다.
     */
    private fun sig(structure: String) = ScreenSig(structure = structure, content = "")

    @Test
    fun `흔들리는 동안에는 보내지 않는다`() {
        assertEquals(Decision.WAIT, tracker.onSample(sig("a"), 0, hasLabeledElement = true))
        assertEquals(Decision.WAIT, tracker.onSample(sig("b"), 100, hasLabeledElement = true))
        assertEquals(Decision.WAIT, tracker.onSample(sig("c"), 200, hasLabeledElement = true))
    }

    @Test
    fun `같은 화면이 두 번 연속이면 보낸다`() {
        tracker.onSample(sig("a"), 0, hasLabeledElement = true)

        assertEquals(Decision.SEND, tracker.onSample(sig("a"), 100, hasLabeledElement = true))
    }

    @Test
    fun `label 없는 화면은 반복돼도 보내지 않고 label이 생긴 뒤 다시 안정화한다`() {
        assertEquals(Decision.WAIT, tracker.onSample(sig("splash"), 0, hasLabeledElement = false))
        assertEquals(Decision.WAIT, tracker.onSample(sig("splash"), 100, hasLabeledElement = false))
        assertEquals(sig("splash"), tracker.currentSignature())

        assertEquals(Decision.WAIT, tracker.onSample(sig("content"), 200, hasLabeledElement = true))
        assertEquals(Decision.SEND, tracker.onSample(sig("content"), 300, hasLabeledElement = true))
    }

    @Test
    fun `label 없는 화면이 오래 이어지면 서버 대신 읽기 불가를 알린다`() {
        assertEquals(Decision.WAIT, tracker.onSample(sig("blank"), 0, hasLabeledElement = false))
        assertEquals(
            Decision.WAIT,
            tracker.onSample(
                sig("blank"),
                ScreenStabilityTracker.UNREADABLE_AFTER_MS - 1,
                hasLabeledElement = false,
            ),
        )
        assertEquals(
            Decision.UNREADABLE,
            tracker.onSample(
                sig("blank"),
                ScreenStabilityTracker.UNREADABLE_AFTER_MS,
                hasLabeledElement = false,
            ),
        )
        // 상태 판정이라 이후 샘플도 UNREADABLE이다. 표시 상태가 같은 문구 재그리기를 막는다.
        assertEquals(
            Decision.UNREADABLE,
            tracker.onSample(
                sig("blank"),
                ScreenStabilityTracker.UNREADABLE_AFTER_MS + 1_000,
                hasLabeledElement = false,
            ),
        )
    }

    @Test
    fun `빈 화면 시그니처가 흔들려도 읽기 불가 타이머는 초기화하지 않는다`() {
        assertEquals(Decision.WAIT, tracker.onSample(sig("blank-a"), 0, hasLabeledElement = false))
        assertEquals(Decision.WAIT, tracker.onSample(sig("blank-b"), 3_500, hasLabeledElement = false))
        assertEquals(
            Decision.UNREADABLE,
            tracker.onSample(
                sig("blank-a"),
                ScreenStabilityTracker.UNREADABLE_AFTER_MS,
                hasLabeledElement = false,
            ),
        )
    }

    @Test
    fun `읽기 불가 뒤 label이 생기면 타이머를 풀고 안정화를 다시 센다`() {
        tracker.onSample(sig("blank"), 0, hasLabeledElement = false)
        tracker.onSample(
            sig("blank"),
            ScreenStabilityTracker.UNREADABLE_AFTER_MS,
            hasLabeledElement = false,
        )

        assertEquals(Decision.WAIT, tracker.onSample(sig("content"), 8_000, hasLabeledElement = true))
        assertEquals(Decision.SEND, tracker.onSample(sig("content"), 8_100, hasLabeledElement = true))
    }

    @Test
    fun `세션이 바뀌면 읽기 불가 타이머도 비운다`() {
        tracker.onSample(sig("blank"), 0, hasLabeledElement = false)

        tracker.reset()

        assertEquals(
            Decision.WAIT,
            tracker.onSample(
                sig("blank"),
                ScreenStabilityTracker.UNREADABLE_AFTER_MS,
                hasLabeledElement = false,
            ),
        )
    }

    @Test
    fun `화면 관측을 재개하면 관측 불가 시간은 읽기 불가 타이머에서 뺀다`() {
        tracker.onSample(sig("blank"), 0, hasLabeledElement = false)

        val resumedAt = 10_000L
        tracker.markResumed(resumedAt, requery = false)

        assertEquals(
            Decision.WAIT,
            tracker.onSample(
                sig("blank"),
                resumedAt + ScreenStabilityTracker.UNREADABLE_AFTER_MS - 1,
                hasLabeledElement = false,
            ),
        )
        assertEquals(
            Decision.UNREADABLE,
            tracker.onSample(
                sig("blank"),
                resumedAt + ScreenStabilityTracker.UNREADABLE_AFTER_MS,
                hasLabeledElement = false,
            ),
        )
    }

    @Test
    fun `이미 보낸 화면은 다시 보내지 않는다`() {
        tracker.onSample(sig("a"), 0, hasLabeledElement = true)
        tracker.onSample(sig("a"), 100, hasLabeledElement = true)
        tracker.markSent(sig("a"), 100)

        assertEquals(Decision.IDLE, tracker.onSample(sig("a"), 200, hasLabeledElement = true))
    }

    @Test
    fun `안내 뒤 화면이 그대로면 정체를 한 번만 알린다`() {
        tracker.onSample(sig("a"), 0, hasLabeledElement = true)
        tracker.onSample(sig("a"), 100, hasLabeledElement = true)
        tracker.markSent(sig("a"), 100)
        tracker.markFinished()

        val stuckAt = 100 + ScreenStabilityTracker.STUCK_AFTER_MS
        assertEquals(Decision.IDLE, tracker.onSample(sig("a"), stuckAt - 1, hasLabeledElement = true))
        assertEquals(Decision.STUCK, tracker.onSample(sig("a"), stuckAt, hasLabeledElement = true))
        assertEquals(Decision.IDLE, tracker.onSample(sig("a"), stuckAt + 1_000, hasLabeledElement = true))
    }

    /** "안내 후 화면 그대로 7초"는 사용자가 방금 조작했으면 그 조작부터 센다(#319). */
    @Test
    fun `눌림이 오면 정체 타이머가 그 시점부터 다시 돈다`() {
        tracker.onSample(sig("a"), 0, hasLabeledElement = true)
        tracker.onSample(sig("a"), 100, hasLabeledElement = true)
        tracker.markSent(sig("a"), 100)
        tracker.markFinished()

        tracker.markPressed(5_000)

        val stuckAt = 5_000 + ScreenStabilityTracker.STUCK_AFTER_MS
        assertEquals(Decision.IDLE, tracker.onSample(sig("a"), stuckAt - 1, hasLabeledElement = true))
        assertEquals(Decision.STUCK, tracker.onSample(sig("a"), stuckAt, hasLabeledElement = true))
    }

    /** 정체가 이미 보고된 뒤의 눌림도 다음 정체(와 눌림 확인 재질의)를 되살린다. */
    @Test
    fun `정체 보고 후에도 눌림이 오면 정체가 다시 뜬다`() {
        tracker.onSample(sig("a"), 0, hasLabeledElement = true)
        tracker.onSample(sig("a"), 100, hasLabeledElement = true)
        tracker.markSent(sig("a"), 100)
        tracker.markFinished()
        val firstStuck = 100 + ScreenStabilityTracker.STUCK_AFTER_MS
        assertEquals(Decision.STUCK, tracker.onSample(sig("a"), firstStuck, hasLabeledElement = true))

        tracker.markPressed(firstStuck + 1_000)

        val secondStuck = firstStuck + 1_000 + ScreenStabilityTracker.STUCK_AFTER_MS
        assertEquals(Decision.IDLE, tracker.onSample(sig("a"), secondStuck - 1, hasLabeledElement = true))
        assertEquals(Decision.STUCK, tracker.onSample(sig("a"), secondStuck, hasLabeledElement = true))
    }

    @Test
    fun `화면이 바뀌면 정체 판정이 풀린다`() {
        tracker.onSample(sig("a"), 0, hasLabeledElement = true)
        tracker.onSample(sig("a"), 100, hasLabeledElement = true)
        tracker.markSent(sig("a"), 100)
        tracker.markFinished()
        tracker.onSample(sig("a"), 100 + ScreenStabilityTracker.STUCK_AFTER_MS, hasLabeledElement = true)

        assertEquals(Decision.WAIT, tracker.onSample(sig("b"), 20_000, hasLabeledElement = true))
        assertEquals(Decision.SEND, tracker.onSample(sig("b"), 20_100, hasLabeledElement = true))
    }

    @Test
    fun `요청이 실패하면 같은 화면을 다시 보낸다`() {
        // 이벤트 기반일 때는 다음 이벤트가 재시도를 해줬다. 화면이 그대로면 그 이벤트가 오지 않는다.
        tracker.onSample(sig("a"), 0, hasLabeledElement = true)
        tracker.onSample(sig("a"), 100, hasLabeledElement = true)
        tracker.markSent(sig("a"), 100)
        tracker.markFailed()
        tracker.markFinished()

        val retryAt = 100 + ScreenStabilityTracker.RETRY_BASE_MS
        assertEquals(Decision.SEND, tracker.onSample(sig("a"), retryAt, hasLabeledElement = true))
    }

    @Test
    fun `재시도는 백오프만큼 기다린다`() {
        // 서버가 죽어 있을 때 폴링 주기마다 때리면 무료 티어 15 RPM을 1분에 태운다.
        // 실기기에서 adb reverse가 끊긴 채로 이 루프를 실제로 만들어 봤다.
        tracker.onSample(sig("a"), 0, hasLabeledElement = true)
        tracker.onSample(sig("a"), 100, hasLabeledElement = true)
        tracker.markSent(sig("a"), 100)
        tracker.markFailed()
        tracker.markFinished()

        assertEquals(Decision.IDLE, tracker.onSample(sig("a"), 200, hasLabeledElement = true))
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
    fun `서버가 준 재시도 시각이 상한(30초)보다 길면 그 값을 쓴다`() {
        // Gemini QUOTA의 retry_after_seconds. 5 RPM 모델은 60초가 흔하다(2026-08-18 실기기).
        tracker.markFailed(60_000L)

        assertEquals(60_000L, tracker.backoffMs())
    }

    @Test
    fun `서버가 준 재시도 시각이 지수 백오프보다 짧으면 지수 백오프를 쓴다`() {
        // 값 자체가 잘못되어도(예: 1초) 첫 재시도보다 급하게 다시 두드리지 않는다.
        tracker.markFailed(1_000L)

        assertEquals(ScreenStabilityTracker.RETRY_BASE_MS, tracker.backoffMs())
    }

    @Test
    fun `재시도 시각 없이 실패하면 서버 힌트가 없던 것과 같다`() {
        tracker.markFailed(null)

        assertEquals(ScreenStabilityTracker.RETRY_BASE_MS, tracker.backoffMs())
    }

    @Test
    fun `재시도 시각 힌트는 성공하면 풀린다`() {
        tracker.markFailed(60_000L)
        tracker.markSucceeded()
        tracker.markFailed()

        assertEquals(ScreenStabilityTracker.RETRY_BASE_MS, tracker.backoffMs())
    }

    @Test
    fun `진행 중인 요청이 있으면 새 화면을 보내지 않는다`() {
        // A가 도는 동안 B를 SEND로 내면, 호출자는 "보냈다"고 기록하는데 실제 요청은
        // in-flight 가드에 막혀 나가지 않는다 → B의 안내가 영영 오지 않는다(PR #61 리뷰 P1).
        tracker.onSample(sig("a"), 0, hasLabeledElement = true)
        tracker.onSample(sig("a"), 100, hasLabeledElement = true)
        tracker.markSent(sig("a"), 100)

        tracker.onSample(sig("b"), 200, hasLabeledElement = true)

        assertEquals(Decision.IDLE, tracker.onSample(sig("b"), 300, hasLabeledElement = true))
    }

    @Test
    fun `요청이 끝나면 그동안 바뀐 화면을 보낸다`() {
        tracker.onSample(sig("a"), 0, hasLabeledElement = true)
        tracker.onSample(sig("a"), 100, hasLabeledElement = true)
        tracker.markSent(sig("a"), 100)
        tracker.onSample(sig("b"), 200, hasLabeledElement = true)
        tracker.onSample(sig("b"), 300, hasLabeledElement = true)

        tracker.markFinished()

        assertEquals(Decision.SEND, tracker.onSample(sig("b"), 400, hasLabeledElement = true))
    }

    @Test
    fun `보내지 못한 화면은 전송으로 기록되지 않는다`() {
        // 호출자는 요청이 실제로 시작됐을 때만 markSent를 부른다. 그 계약이 지켜지면
        // 막혔던 화면은 다음 기회에 그대로 다시 나간다.
        tracker.onSample(sig("a"), 0, hasLabeledElement = true)
        tracker.onSample(sig("a"), 100, hasLabeledElement = true)
        tracker.markSent(sig("a"), 100)
        tracker.onSample(sig("b"), 200, hasLabeledElement = true)
        assertEquals(Decision.IDLE, tracker.onSample(sig("b"), 300, hasLabeledElement = true))

        tracker.markFinished()

        assertEquals(Decision.SEND, tracker.onSample(sig("b"), 400, hasLabeledElement = true))
        tracker.markSent(sig("b"), 400)
        assertEquals(Decision.IDLE, tracker.onSample(sig("b"), 500, hasLabeledElement = true))
    }

    @Test
    fun `응답을 기다리는 중에는 정체로 보지 않는다`() {
        // 느린 응답과 멈춘 화면은 다르다. 기다리는 동안 "화면이 그대로예요"라고 말하면 헷갈린다.
        tracker.onSample(sig("a"), 0, hasLabeledElement = true)
        tracker.onSample(sig("a"), 100, hasLabeledElement = true)
        tracker.markSent(sig("a"), 100)

        val stuckAt = 100 + ScreenStabilityTracker.STUCK_AFTER_MS
        assertEquals(Decision.IDLE, tracker.onSample(sig("a"), stuckAt, hasLabeledElement = true))

        tracker.markFinished()

        assertEquals(Decision.STUCK, tracker.onSample(sig("a"), stuckAt, hasLabeledElement = true))
    }

    @Test
    fun `응답이 도착한 시점의 화면을 대조할 수 있다`() {
        tracker.onSample(sig("a"), 0, hasLabeledElement = true)
        assertEquals(sig("a"), tracker.currentSignature())

        tracker.onSample(sig("b"), 100, hasLabeledElement = true)

        assertEquals(sig("b"), tracker.currentSignature())
    }

    @Test
    fun `세션이 바뀌면 이전 화면 기억을 끌고 가지 않는다`() {
        tracker.onSample(sig("a"), 0, hasLabeledElement = true)
        tracker.onSample(sig("a"), 100, hasLabeledElement = true)
        tracker.markSent(sig("a"), 100)

        tracker.reset()

        assertEquals(Decision.WAIT, tracker.onSample(sig("a"), 200, hasLabeledElement = true))
        assertEquals(Decision.SEND, tracker.onSample(sig("a"), 300, hasLabeledElement = true))
    }

    @Test
    fun `세션이 바뀌면 이전 재시도 시각 힌트도 끌고 가지 않는다`() {
        tracker.markFailed(60_000L)

        tracker.reset()
        tracker.markFailed()

        assertEquals(ScreenStabilityTracker.RETRY_BASE_MS, tracker.backoffMs())
    }

    @Test
    fun `포커스와 IME가 안정된 새 상태는 한 번만 보낸다`() {
        tracker.onSample(sig("before"), 0, hasLabeledElement = true)
        assertEquals(Decision.SEND, tracker.onSample(sig("before"), 100, hasLabeledElement = true))
        tracker.markSent(sig("before"), 100)
        tracker.markFinished()

        assertEquals(Decision.WAIT, tracker.onSample(sig("input-ready"), 200, hasLabeledElement = true))
        assertEquals(Decision.SEND, tracker.onSample(sig("input-ready"), 300, hasLabeledElement = true))
        tracker.markSent(sig("input-ready"), 300)
        tracker.markFinished()

        assertEquals(Decision.IDLE, tracker.onSample(sig("input-ready"), 400, hasLabeledElement = true))
        assertEquals(Decision.IDLE, tracker.onSample(sig("input-ready"), 500, hasLabeledElement = true))
    }

    @Test
    fun `포커스가 빠르게 왕복하면 안정된 마지막 상태만 보낸다`() {
        tracker.onSample(sig("before"), 0, hasLabeledElement = true)
        assertEquals(Decision.SEND, tracker.onSample(sig("before"), 100, hasLabeledElement = true))
        tracker.markSent(sig("before"), 100)
        tracker.markFinished()

        assertEquals(Decision.WAIT, tracker.onSample(sig("focus-a"), 200, hasLabeledElement = true))
        assertEquals(Decision.WAIT, tracker.onSample(sig("focus-b"), 250, hasLabeledElement = true))
        assertEquals(Decision.WAIT, tracker.onSample(sig("focus-a"), 300, hasLabeledElement = true))
        assertEquals(Decision.SEND, tracker.onSample(sig("focus-a"), 400, hasLabeledElement = true))
        tracker.markSent(sig("focus-a"), 400)
        tracker.markFinished()

        assertEquals(Decision.IDLE, tracker.onSample(sig("focus-a"), 500, hasLabeledElement = true))
    }

    // ---- 구조/내용 2계층과 인과 게이트(#380) ----

    @Test
    fun `내용만 도는 화면은 한 번만 보내고 이후 로테이션은 무시한다`() {
        // 배민 홈의 광고 배너. 로테이션(3~5초)마다 SEND가 나가던 무한 재질의를 끊는다.
        val ad1 = ScreenSig("home", "치킨")
        tracker.onSample(ad1, 0, hasLabeledElement = true)
        assertEquals(Decision.SEND, tracker.onSample(ad1, 700, hasLabeledElement = true))
        tracker.markSent(ad1, 700)
        tracker.markFinished()

        // 구조는 그대로, 내용만 바뀐다 — 조작이 없으니 광고다. 재질의가 없다.
        assertEquals(
            Decision.IDLE,
            tracker.onSample(ScreenSig("home", "피자"), 4_000, hasLabeledElement = true),
        )
        assertEquals(
            Decision.IDLE,
            tracker.onSample(ScreenSig("home", "족발"), 5_000, hasLabeledElement = true),
        )
    }

    @Test
    fun `내용이 돌아도 정체 알림은 보낸 시점부터 정상으로 뜬다`() {
        // 이전에는 로테이션마다 sentSignature 불일치로 SEND가 나가 정체가 영영 못 왔다.
        val ad1 = ScreenSig("home", "치킨")
        tracker.onSample(ad1, 0, hasLabeledElement = true)
        tracker.onSample(ad1, 700, hasLabeledElement = true)
        tracker.markSent(ad1, 700)
        tracker.markFinished()
        assertEquals(
            Decision.IDLE,
            tracker.onSample(ScreenSig("home", "피자"), 4_000, hasLabeledElement = true),
        )

        val stuckAt = 700 + ScreenStabilityTracker.STUCK_AFTER_MS
        assertEquals(
            Decision.STUCK,
            tracker.onSample(ScreenSig("home", "족발"), stuckAt, hasLabeledElement = true),
        )
    }

    @Test
    fun `구조는 안정인데 내용이 계속 흔들리면 승격해 보낸다`() {
        // 홀드(2샘플)보다 빨리 도는 marquee. 이전에는 안정 판정이 영영 안 나 안내가 못 나갔다.
        assertEquals(
            Decision.WAIT,
            tracker.onSample(ScreenSig("news", "속보1"), 0, hasLabeledElement = true),
        )
        assertEquals(
            Decision.WAIT,
            tracker.onSample(ScreenSig("news", "속보2"), 700, hasLabeledElement = true),
        )
        assertEquals(
            Decision.WAIT,
            tracker.onSample(ScreenSig("news", "속보3"), 2_900, hasLabeledElement = true),
        )
        assertEquals(
            Decision.SEND,
            tracker.onSample(
                ScreenSig("news", "속보4"),
                ScreenStabilityTracker.CONTENT_SETTLE_MS,
                hasLabeledElement = true,
            ),
        )
    }

    @Test
    fun `내용만의 변화는 조작이 관측됐을 때만 새 화면이다`() {
        // 균일한 목록을 정확히 N칸 스크롤하면 구조는 같고 label만 밀린다 — 스크롤 이벤트가
        // 게이트를 열어 재질의가 나가야 한다. 같은 모양의 변화가 조작 없이 오면 광고다.
        val page1 = ScreenSig("list", "1페이지")
        tracker.onSample(page1, 0, hasLabeledElement = true)
        tracker.onSample(page1, 700, hasLabeledElement = true)
        tracker.markSent(page1, 700)
        tracker.markFinished()

        tracker.markInteraction(1_000)
        val page2 = ScreenSig("list", "2페이지")
        assertEquals(Decision.WAIT, tracker.onSample(page2, 1_100, hasLabeledElement = true))
        assertEquals(Decision.SEND, tracker.onSample(page2, 1_800, hasLabeledElement = true))
    }

    @Test
    fun `조작 게이트는 시간이 지나면 닫힌다`() {
        val page1 = ScreenSig("list", "1페이지")
        tracker.onSample(page1, 0, hasLabeledElement = true)
        tracker.onSample(page1, 700, hasLabeledElement = true)
        tracker.markSent(page1, 700)
        tracker.markFinished()

        tracker.markInteraction(1_000)
        val drifted = ScreenSig("list", "다른내용")
        val late = 1_000 + ScreenStabilityTracker.INTERACTION_WINDOW_MS + 1_000
        // 창이 닫힌 뒤의 내용 변화는 새 화면이 아니다(광고와 같은 취급). 구조가 오래 안정이라
        // 홀드도 승격으로 건너뛰므로 WAIT가 아니라 곧장 IDLE이다.
        assertEquals(Decision.IDLE, tracker.onSample(drifted, late, hasLabeledElement = true))
    }

    @Test
    fun `안내 대상 슬롯이 바뀌면 조작 없이도 새 화면이다`() {
        // 새 메시지가 대화방 순서를 미는 재정렬 — 강조가 엉뚱한 줄을 가리키게 되므로
        // 타깃 앵커가 게이트를 우회해 재질의를 연다.
        val before = ScreenSig("rooms", "A;B;C")
        tracker.onSample(before, 0, hasLabeledElement = true)
        tracker.onSample(before, 700, hasLabeledElement = true)
        tracker.markSent(before, 700)
        tracker.markFinished()

        val reordered = ScreenSig("rooms", "B;A;C")
        assertEquals(
            Decision.WAIT,
            tracker.onSample(reordered, 1_400, hasLabeledElement = true, guidedTargetChanged = true),
        )
        assertEquals(
            Decision.SEND,
            tracker.onSample(reordered, 2_100, hasLabeledElement = true, guidedTargetChanged = true),
        )
    }

    @Test
    fun `조작 직후의 내용 흔들림은 승격하지 않고 홀드로 가라앉힌다`() {
        // 승격 조건(구조 3초 안정)이 서 있어도 방금 조작이 있었으면 그 내용 변화는 사용자가
        // 만드는 중일 수 있다(스크롤). 덜 그려진 화면 방지(#32)와 같은 이유로 홀드를 지킨다.
        val base = ScreenSig("list", "c1")
        tracker.onSample(base, 0, hasLabeledElement = true)
        tracker.onSample(base, 700, hasLabeledElement = true)
        tracker.markSent(base, 700)
        tracker.markFinished()

        tracker.markInteraction(4_000)
        val moving = ScreenSig("list", "c2")
        assertEquals(Decision.WAIT, tracker.onSample(moving, 4_100, hasLabeledElement = true))
        assertEquals(Decision.SEND, tracker.onSample(moving, 4_800, hasLabeledElement = true))
    }

    @Test
    fun `요청 이후의 조작 관측 여부를 대조할 수 있다`() {
        // 늦은 응답 폐기(#380): 구조가 같아도 요청~응답 사이 조작이 있었고 내용이 다르면
        // 같은 구조의 다른 화면일 수 있어 버린다.
        assertFalse(tracker.interactionAfter(0))

        tracker.markInteraction(1_000)

        assertTrue(tracker.interactionAfter(500))
        assertTrue(tracker.interactionAfter(1_000))
        assertFalse(tracker.interactionAfter(1_001))
    }

    @Test
    fun `세션이 바뀌면 조작 관측도 끌고 가지 않는다`() {
        tracker.markInteraction(1_000)

        tracker.reset()

        assertFalse(tracker.interactionAfter(0))
    }

    @Test
    fun `화면 관측을 재개하면 승격 타이머도 다시 찍는다`() {
        // 꺼져 있던 구간은 "구조가 안정했던 시간"이 아니다. 그대로 세면 잠금 해제 첫 샘플이
        // 곧바로 승격하고, requery가 sentSignature까지 비워 둔 터라 홀드 없이 SEND가 나간다 —
        // 배너가 도는 화면에서는 그 한 장이 로테이션 한복판이다(#32).
        val banner1 = ScreenSig("home", "배너1")
        tracker.onSample(banner1, 0, hasLabeledElement = true)
        tracker.onSample(banner1, 700, hasLabeledElement = true)
        tracker.markSent(banner1, 700)
        tracker.markFinished()
        // 구조가 승격 조건을 넘길 만큼 오래 안정됐다.
        tracker.onSample(banner1, 5_000, hasLabeledElement = true)

        // 화면이 꺼진 사이 배너가 돌았고, 꺼진 동안 도착한 응답은 버렸다.
        tracker.markResumed(20_000, requery = true)

        val banner2 = ScreenSig("home", "배너2")
        assertEquals(Decision.WAIT, tracker.onSample(banner2, 20_400, hasLabeledElement = true))
        assertEquals(Decision.SEND, tracker.onSample(banner2, 21_100, hasLabeledElement = true))
    }

    @Test
    fun `승격 억제가 풀리는 시점에 인과 창이 아직 열려 있다`() {
        // 승격 억제와 재질의 조건이 같은 창을 반대로 요구하면 상호배타가 된다 — 창이 열린
        // 동안은 승격이 막혀 WAIT이고, 닫히는 순간 재질의 조건도 함께 꺼져 사용자가 스크롤한
        // 화면이 7초 뒤 "화면이 안 바뀌었어요"로 끝난다(#380 리뷰). 억제를 시계 되감기로
        // 표현해 두 구간이 겹치게 한다.
        val base = ScreenSig("news", "속보1")
        tracker.onSample(base, 0, hasLabeledElement = true)
        tracker.onSample(base, 700, hasLabeledElement = true)
        tracker.markSent(base, 700)
        tracker.markFinished()

        // 사용자가 스크롤했고, 그 뒤로 샘플마다 내용이 달라 홀드가 영영 못 찬다.
        val scrolledAt = 5_000L
        tracker.markInteraction(scrolledAt)
        var t = scrolledAt + 350
        var n = 2
        while (t < scrolledAt + ScreenStabilityTracker.CONTENT_SETTLE_MS) {
            assertEquals(
                Decision.WAIT,
                tracker.onSample(ScreenSig("news", "속보$n"), t, hasLabeledElement = true),
            )
            t += 350
            n++
        }

        // 억제가 풀렸고 인과 창은 아직 열려 있다.
        val promotedAt = scrolledAt + ScreenStabilityTracker.CONTENT_SETTLE_MS
        assertEquals(
            Decision.SEND,
            tracker.onSample(ScreenSig("news", "속보$n"), promotedAt, hasLabeledElement = true),
        )
    }

    @Test
    fun `인과 창이 승격 억제보다 길다`() {
        // 위 테스트가 성립하는 전제다. 뒤집히면 승격이 열리는 시점에 인과가 이미 닫혀 있어
        // 조작 기반 재질의가 구조적으로 도달 불가가 된다.
        assertTrue(
            ScreenStabilityTracker.CONTENT_SETTLE_MS <
                ScreenStabilityTracker.INTERACTION_WINDOW_MS,
        )
    }

    @Test
    fun `내용만 바뀐 전송인지 스텝 이력에 알려 준다`() {
        // 균일 목록 스크롤은 구조가 같아, 구조 축만 보면 screen_changed가 false로 적힌다.
        val page1 = ScreenSig("list", "1페이지")
        tracker.onSample(page1, 0, hasLabeledElement = true)
        assertEquals(Decision.SEND, tracker.onSample(page1, 700, hasLabeledElement = true))
        assertFalse(tracker.lastSendWasContentOnly())
        tracker.markSent(page1, 700)
        tracker.markFinished()

        tracker.markInteraction(1_000)
        val page2 = ScreenSig("list", "2페이지")
        tracker.onSample(page2, 1_100, hasLabeledElement = true)
        assertEquals(Decision.SEND, tracker.onSample(page2, 1_800, hasLabeledElement = true))
        assertTrue(tracker.lastSendWasContentOnly())

        // 구조가 바뀐 전송은 다시 false다.
        tracker.markSent(page2, 1_800)
        tracker.markFinished()
        val other = ScreenSig("detail", "상세")
        tracker.onSample(other, 2_500, hasLabeledElement = true)
        assertEquals(Decision.SEND, tracker.onSample(other, 3_200, hasLabeledElement = true))
        assertFalse(tracker.lastSendWasContentOnly())
    }

    @Test
    fun `잠그기 직전의 조작은 잠금 해제 뒤 게이트를 열지 않는다`() {
        // 관측 불가 구간을 사이에 둔 조작은 "방금 한 조작"이 아니다. 남겨 두면 해제 뒤
        // 배너가 한 번 도는 것만으로 재질의가 나간다.
        val banner1 = ScreenSig("home", "배너1")
        tracker.onSample(banner1, 0, hasLabeledElement = true)
        tracker.onSample(banner1, 700, hasLabeledElement = true)
        tracker.markSent(banner1, 700)
        tracker.markFinished()
        tracker.markInteraction(1_000)

        tracker.markResumed(2_000, requery = false)

        assertFalse(tracker.interactionAfter(0))
        assertFalse(tracker.interactionGateOpen(2_400))
        val banner2 = ScreenSig("home", "배너2")
        assertEquals(Decision.WAIT, tracker.onSample(banner2, 2_400, hasLabeledElement = true))
        assertEquals(Decision.IDLE, tracker.onSample(banner2, 3_100, hasLabeledElement = true))
    }
}

private typealias Decision = ScreenStabilityTracker.Decision
