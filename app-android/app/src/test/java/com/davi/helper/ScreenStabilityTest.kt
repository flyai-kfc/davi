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
    fun `숫자만 다른 화면은 다른 시그니처다`() {
        // 예전에는 시계·배터리% 때문에 숫자를 지웠는데(HANDOFF §4), 그 탓에 장바구니 수량
        // `2`와 `3`도 같은 화면이 됐다(#427). 지금은 지우지 않는다 — 시계처럼 스스로 도는
        // 숫자를 막는 것은 정규화가 아니라 **인과 게이트**이고, 홀드는 구조가 안정이면
        // [ScreenStabilityTracker.CONTENT_SETTLE_MS] 뒤 면제되므로 안정 판정도 늦지 않는다.
        val at3 = screen(UiElement(nodeId = "n1", label = "3:45 화요일", role = "TextView"))
        val at4 = screen(UiElement(nodeId = "n1", label = "4:07 화요일", role = "TextView"))

        assertNotEquals(ScreenSignature.of(at3), ScreenSignature.of(at4))
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
    fun `picker 주변 버튼의 숫자도 시그니처에 그대로 들어간다`() {
        val before = screen(
            UiElement(nodeId = "n1", label = "29", role = "Button", clickable = true),
            UiElement(nodeId = "n2", label = "31", role = "Button", clickable = true),
        )
        val after = screen(
            UiElement(nodeId = "n1", label = "30", role = "Button", clickable = true),
            UiElement(nodeId = "n2", label = "32", role = "Button", clickable = true),
        )

        // #117이 editable picker에만 두던 예외가 이제 전체 규칙이다(#427).
        assertNotEquals(ScreenSignature.of(before), ScreenSignature.of(after))
    }

    @Test
    fun `스위치를 켜면 다른 화면이다`() {
        // 이 화면에서 바뀌는 것은 checkState 하나뿐이다 — label도 role도 clickable도 그대로다.
        // 상태를 서명에 안 넣으면 스위치를 켜도 IDLE이라 다음 안내가 나가지 않는다(#271).
        val off = screen(
            UiElement(nodeId = "n1", label = "Wi-Fi", role = "Switch", clickable = true, checkState = CheckState.UNCHECKED),
        )
        val on = screen(
            UiElement(nodeId = "n1", label = "Wi-Fi", role = "Switch", clickable = true, checkState = CheckState.CHECKED),
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
        // unavailable을 unchecked로 접으면 네 상태 계약이 죽는다.
        val unknown = screen(UiElement(nodeId = "n1", label = "Wi-Fi", role = "Switch", clickable = true))
        val off = screen(
            UiElement(nodeId = "n1", label = "Wi-Fi", role = "Switch", clickable = true, checkState = CheckState.UNCHECKED),
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
    fun `제목에 붙는 숫자도 시그니처에 들어간다`() {
        // "받은편지함 (3)"이 (4)가 되는 것은 진짜 변화다. 사용자 조작 없이 바뀌면 인과 게이트가
        // 막으므로, 이 축이 그것을 보는 것 자체는 비용이 아니다(#427).
        val three = screen(windowTitle = "받은편지함 (3)")
        val four = screen(windowTitle = "받은편지함 (4)")

        assertNotEquals(ScreenSignature.of(three), ScreenSignature.of(four))
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
            UiElement(nodeId = "n1", label = "Wi-Fi", role = "Switch", clickable = true, checkState = CheckState.UNCHECKED),
        )
        val on = screen(
            UiElement(nodeId = "n1", label = "Wi-Fi", role = "Switch", clickable = true, checkState = CheckState.CHECKED),
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

/**
 * 내용 축 해시(#410).
 *
 * **#427로 이 자리의 전각 숫자 위험은 사라졌다.** 내용 축이 정규화를 거치지 않는 원문이라
 * 정규식이 개입할 자리가 없다 — 예전 경고는 내용 축을 `DIGITS`로 지우던 때의 것이다.
 * 같은 위험은 [ScreenSignature.stableLabelOf]로 **옮겨 갔다**(아래 앵커 테스트).
 */
class ContentSignatureTest {

    private val salt = ByteArray(16) { it.toByte() }
    private val otherSalt = ByteArray(16) { (it + 1).toByte() }

    @Test
    fun `같은 내용이면 같은 해시다`() {
        // 세션 안에서 "같은 화면인가"를 서버가 판정할 수 있는 근거가 이 동일성뿐이다.
        assertEquals(
            ContentSignature.of(salt, "제목|장바구니;결제하기;"),
            ContentSignature.of(salt, "제목|장바구니;결제하기;"),
        )
    }

    @Test
    fun `내용이 다르면 다른 해시다`() {
        // 구조가 같고 내용만 바뀐 화면(다른 상품 상세)을 가르는 자리다.
        assertNotEquals(
            ContentSignature.of(salt, "제목|사과;담기;"),
            ContentSignature.of(salt, "제목|배;담기;"),
        )
    }

    @Test
    fun `salt가 다르면 같은 내용이라도 다른 해시다`() {
        // 세션 밖 비교를 막는 것이 salt의 목적이다. 이게 깨지면 해시 하나가 세션을 넘어 산다.
        assertNotEquals(
            ContentSignature.of(salt, "제목|장바구니;"),
            ContentSignature.of(otherSalt, "제목|장바구니;"),
        )
    }

    @Test
    fun `형식은 hex 64자다`() {
        // 서버 `structure_signature`의 `^[0-9a-f]{64}$`와 같은 형식이어야 한 레코드에 나란히 선다.
        val signature = ContentSignature.of(salt, "제목|장바구니;")

        assertTrue(signature.matches(Regex("^[0-9a-f]{64}$")))
    }

    @Test
    fun `빈 내용도 값을 낸다`() {
        // 값을 안 낼지 말지는 [GuideSession]이 정한다. 해시 자체는 입력을 가리지 않는다.
        assertTrue(ContentSignature.of(salt, "").matches(Regex("^[0-9a-f]{64}$")))
    }

    @Test
    fun `salt는 만들 때마다 다르다`() {
        assertNotEquals(
            ContentSignature.newSalt().toList(),
            ContentSignature.newSalt().toList(),
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

/** 타깃 앵커(#380) — 안내가 가리키는 슬롯이 구조 보존 재정렬로 밀렸는지 본다. */
class GuidedSlotAnchorTest {

    /** 앵커는 구조가 같은 동안만 유효하다. 그 전제를 만족시키는 값. */
    private val SAME = "structure-a"

    private fun screen(vararg elements: UiElement) = Screen(
        packageName = "com.example.app",
        elements = elements.toList(),
    )

    @Test
    fun `슬롯의 label이 바뀌면 밀린 것이다`() {
        // 새 메시지가 대화방 순서를 밀면 같은 node id가 다른 줄을 뜻하게 된다.
        val after = screen(UiElement(nodeId = "n3", label = "김철수", role = "View"))

        assertTrue(after.guidedSlotLabelChanged("n3", "이영희", SAME, SAME))
    }

    @Test
    fun `같은 슬롯이면 밀린 것이 아니다`() {
        val after = screen(UiElement(nodeId = "n3", label = "이영희", role = "View"))

        assertFalse(after.guidedSlotLabelChanged("n3", "이영희", SAME, SAME))
    }

    @Test
    fun `숫자만 다른 label은 앵커에서 같은 줄로 본다`() {
        // 앵커는 "강조가 아직 같은 줄인가"를 묻는다(#427·PR #445 리뷰). 그 줄에 붙은 개수·시계가
        // 도는 것은 줄이 바뀐 것이 아니다 — 원문으로 비교하면 한 번 돈 것만으로 앵커가 영구히
        // 어긋나 강조가 다음 안내까지 내려간 채 남는다. **내용 축은 반대로 이 숫자를 본다.**
        val before = UiElement(nodeId = "n3", label = "안 읽음 11", role = "View")
        val after = screen(UiElement(nodeId = "n3", label = "안 읽음 12", role = "View"))

        // 얼려 두는 값도 호출부에서 같은 함수를 거친다(`GuideAccessibilityService`의 응답 처리).
        val expected = ScreenSignature.stableLabelOf(before)
        assertFalse(after.guidedSlotLabelChanged("n3", expected, SAME, SAME))
    }

    @Test
    fun `앵커가 보는 값은 숫자를 지운 값이다`() {
        // 위 테스트는 "두 label이 다르다"만 봐서 옛 구현에서도 통과한다(`안 읽음 #` != `안 읽음 11`).
        // 규칙을 고정하려면 반환값 자체를 단언해야 한다(PR #445 리뷰).
        //
        // **전각 숫자 표본을 넣지 말 것.** `ANCHOR_DIGITS`의 `\d`가 기기(ICU, 유니코드 기본)와
        // JVM(OpenJDK, ASCII 기본)에서 다른 것을 잡아, JVM에서 통과하는 기대값이 기기에서는
        // 거짓이 된다(#402가 `PrivacyMasker`에서 실측). #427로 내용 축이 정규화를 버린 뒤로
        // 이 갈림이 남은 자리는 **앵커뿐**이다.
        val element = UiElement(nodeId = "n3", label = "안 읽음 12", role = "View")

        assertEquals("안 읽음 #", ScreenSignature.stableLabelOf(element))
    }

    @Test
    fun `picker의 짧은 선택 숫자는 앵커에서도 남는다`() {
        // #117의 분 picker `00, 분`→`30, 분`은 줄에 붙은 장식이 아니라 **선택 상태 자체**다.
        // 지우면 앵커가 안 움직이는데, 그 화면에서는 앵커가 유일하게 열리는 문이다(시계 앱은
        // 클릭을 쏴 조용함 문이 닫히고, picker 스크롤은 CLICKED가 아니다). 「안 읽음 12」와는
        // serializer가 검증한 editable + EditText + 짧은 숫자로 갈린다(PR #445 리뷰).
        val picker = UiElement(
            nodeId = "n2",
            label = "00, 분",
            role = "android.widget.EditText",
            editable = true,
        )

        assertEquals("00, 분", ScreenSignature.stableLabelOf(picker))

        val after = screen(picker.copy(label = "30, 분"))
        assertTrue(
            after.guidedSlotLabelChanged("n2", ScreenSignature.stableLabelOf(picker), SAME, SAME),
        )
    }

    @Test
    fun `내용 축은 앵커와 달리 숫자를 남긴다`() {
        // 두 자리가 서로 다른 질문을 한다는 것이 #427의 결론이다. 한쪽으로 통일하면 둘 중
        // 하나가 깨진다 — 앵커로 통일하면 수량 변화가 안 보이고, 내용 축으로 통일하면 강조가 죽는다.
        fun sig(label: String) = ScreenSignature.of(
            screen(UiElement(nodeId = "n1", label = label, role = "View")),
        )

        assertNotEquals(sig("수량 2").content, sig("수량 3").content)
        assertEquals(
            ScreenSignature.stableLabelOf(UiElement(nodeId = "n1", label = "수량 2", role = "View")),
            ScreenSignature.stableLabelOf(UiElement(nodeId = "n1", label = "수량 3", role = "View")),
        )
    }

    @Test
    fun `슬롯이 사라졌으면 구조 축에 맡긴다`() {
        val after = screen(UiElement(nodeId = "n1", label = "이영희", role = "View"))

        assertFalse(after.guidedSlotLabelChanged("n3", "이영희", SAME, SAME))
    }

    @Test
    fun `안내 대상이 없으면 앵커도 없다`() {
        val after = screen(UiElement(nodeId = "n3", label = "이영희", role = "View"))

        assertFalse(after.guidedSlotLabelChanged(null, "이영희", SAME, SAME))
        assertFalse(after.guidedSlotLabelChanged("n3", null, SAME, SAME))
    }

    @Test
    fun `구조가 다르면 앵커를 보지 않는다`() {
        // node id는 직렬화 순서 값이라 다른 화면의 같은 id는 다른 슬롯이다. 그 자리를
        // 앵커가 판정하면 구조 축이 이미 맡은 일을 겹쳐 하고, 강조 복원 분기가 엉뚱하게 걸린다.
        val other = screen(UiElement(nodeId = "n3", label = "전혀 다른 화면", role = "View"))

        assertFalse(other.guidedSlotLabelChanged("n3", "이영희", SAME, "structure-b"))
    }

    @Test
    fun `앵커를 잡은 적이 없으면 구조가 같아도 보지 않는다`() {
        val after = screen(UiElement(nodeId = "n3", label = "김철수", role = "View"))

        assertFalse(after.guidedSlotLabelChanged("n3", "이영희", null, SAME))
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
        tracker.markResumed(resumedAt, requery = false, clearQuietBudget = false)

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
        tracker.markFailed(100)
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
        tracker.markFailed(100)
        tracker.markFinished()

        assertEquals(Decision.IDLE, tracker.onSample(sig("a"), 200, hasLabeledElement = true))
        assertEquals(ScreenStabilityTracker.RETRY_BASE_MS, tracker.backoffMs())
    }

    @Test
    fun `긴 timeout도 실패가 끝난 시점부터 백오프한다`() {
        tracker.onSample(sig("a"), 0, hasLabeledElement = true)
        tracker.onSample(sig("a"), 100, hasLabeledElement = true)
        tracker.markSent(sig("a"), 100)

        val failedAt = 30_000L
        tracker.markFailed(failedAt)
        tracker.markFinished()

        val retryAt = failedAt + ScreenStabilityTracker.RETRY_BASE_MS
        assertEquals(Decision.IDLE, tracker.onSample(sig("a"), retryAt - 1, hasLabeledElement = true))
        assertEquals(Decision.SEND, tracker.onSample(sig("a"), retryAt, hasLabeledElement = true))
    }

    @Test
    fun `연속 실패마다 백오프가 두 배로 늘고 상한에서 멈춘다`() {
        tracker.markFailed(0)
        assertEquals(ScreenStabilityTracker.RETRY_BASE_MS, tracker.backoffMs())

        tracker.markFailed(0)
        assertEquals(ScreenStabilityTracker.RETRY_BASE_MS * 2, tracker.backoffMs())

        repeat(30) { tracker.markFailed(0) }
        assertEquals(ScreenStabilityTracker.RETRY_MAX_MS, tracker.backoffMs())
    }

    @Test
    fun `성공하면 백오프가 풀린다`() {
        tracker.markFailed(0)
        tracker.markFailed(0)

        tracker.markSucceeded()

        assertEquals(0L, tracker.backoffMs())
    }

    @Test
    fun `서버가 준 재시도 시각이 상한(30초)보다 길면 그 값을 쓴다`() {
        // Gemini QUOTA의 retry_after_seconds. 5 RPM 모델은 60초가 흔하다(2026-08-18 실기기).
        tracker.markFailed(0, 60_000L)

        assertEquals(60_000L, tracker.backoffMs())
    }

    @Test
    fun `서버가 준 재시도 시각이 지수 백오프보다 짧으면 지수 백오프를 쓴다`() {
        // 값 자체가 잘못되어도(예: 1초) 첫 재시도보다 급하게 다시 두드리지 않는다.
        tracker.markFailed(0, 1_000L)

        assertEquals(ScreenStabilityTracker.RETRY_BASE_MS, tracker.backoffMs())
    }

    @Test
    fun `재시도 시각 없이 실패하면 서버 힌트가 없던 것과 같다`() {
        tracker.markFailed(0, null)

        assertEquals(ScreenStabilityTracker.RETRY_BASE_MS, tracker.backoffMs())
    }

    @Test
    fun `재시도 시각 힌트는 성공하면 풀린다`() {
        tracker.markFailed(0, 60_000L)
        tracker.markSucceeded()
        tracker.markFailed(0)

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
        tracker.markFailed(0, 60_000L)

        tracker.reset()
        tracker.markFailed(0)

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
    fun `클릭을 안 쏘는 앱에서 조용하던 화면이 움직이면 새 화면이다`() {
        // 코레일 인원 선택 — 어린이 `1`→`2`. 클릭 이벤트가 0건이라 인과 게이트가 영영 안 열리고,
        // 구조·내용(숫자 제거) 축도 그대로다. 조작 전까지 조용했다는 것이 유일한 인과 근거다
        // (2026-08-25 SM-S921N 실측: 그 화면은 80샘플 넘게 고정이었다).
        fun sample(t: Long, v: String) =
            tracker.onSample(ScreenSig("count", v), t, hasLabeledElement = true, clickAxisDead = true)

        sample(0, "어린이 1명")
        sample(700, "어린이 1명")
        tracker.markSent(ScreenSig("count", "어린이 1명"), 700)
        tracker.markFinished()
        // 조용한 구간을 [QUIET_MS]만큼 채운다. **샘플 수가 아니라 시간이다**(PR #445 리뷰).
        sample(4_900, "어린이 1명")

        assertEquals(Decision.SEND, sample(5_600, "어린이 2명"))
    }

    @Test
    fun `연속 조작은 조용함을 다시 요구하지 않는다`() {
        // 코레일은 `+`를 여러 번 누르는 화면이다. 매번 조용함을 요구하면 두 번째 탭부터
        // 인정되지 않아 「화면이 그대로예요」가 뜬다 — 이 이슈가 없애려던 거짓 문구가 같은
        // 자리에 다시 선다(PR #445 리뷰). 첫 탭이 인과를 세우면 뒤는 같은 조작의 연속이다.
        fun sample(t: Long, v: String) =
            tracker.onSample(ScreenSig("count", v), t, hasLabeledElement = true, clickAxisDead = true)

        sample(0, "어린이 1명")
        sample(700, "어린이 1명")
        tracker.markSent(ScreenSig("count", "어린이 1명"), 700)
        tracker.markFinished()
        sample(4_900, "어린이 1명")
        assertEquals(Decision.SEND, sample(5_600, "어린이 2명"))
        tracker.markSent(ScreenSig("count", "어린이 2명"), 5_600)
        tracker.markFinished()

        // 1초 뒤 두 번째 탭. 조용하지 않았지만 연속 창 안이다.
        assertEquals(Decision.SEND, sample(6_600, "어린이 3명"))
    }

    @Test
    fun `연속 창 안 주기로 도는 화면도 총량 천장에서 멈춘다`() {
        // **연속 창이 cold 천장을 우회한다**(PR #445 자체 리뷰, 실행으로 재현). 창은 열릴 때마다
        // 스스로 갱신되고 그 경로는 cold를 안 올려서, 주기가 QUIET_MS~QUIET_ACTIVITY_MS 사이인
        // 배너는 첫 회전에서 cold 하나만 태우고 이후 **무한히** 나갔다 — 5초 주기면 12 RPM으로
        // 무료 티어(15 RPM)에 붙는다. 총량 천장이 없으면 이 단언이 그 자리에서 깨진다.
        fun sample(t: Long, v: String) =
            tracker.onSample(ScreenSig("home", v), t, hasLabeledElement = true, clickAxisDead = true)

        sample(0, "배너 0")
        sample(700, "배너 0")
        tracker.markSent(ScreenSig("home", "배너 0"), 700)
        tracker.markFinished()

        // 5초 주기 = 조용함(4초)은 넘고 연속 창(15초) 안이다. 총량만큼은 열린다.
        var sends = 0
        repeat(ScreenStabilityTracker.QUIET_OPENS + 6) { i ->
            val t = 5_000L * (i + 1)
            val v = "배너 ${i + 1}"
            if (sample(t, v) == Decision.SEND) {
                sends++
                tracker.markSent(ScreenSig("home", v), t)
                tracker.markFinished()
            }
        }

        assertEquals(ScreenStabilityTracker.QUIET_OPENS, sends)
    }

    @Test
    fun `요청이 안 나간 판정은 천장을 태우지 않는다`() {
        // 앵커 상한이 세운 규칙과 같다 — 상한은 **요청이 실제로 나갔을 때만** 쓴다.
        // 예전에는 `quietColdOpens++`가 in-flight 조기 반환보다 앞이라, 요청이 한 건도 안
        // 나가는데 화면당 예산이 사라졌다(PR #445 자체 리뷰).
        fun sample(t: Long, v: String) =
            tracker.onSample(ScreenSig("count", v), t, hasLabeledElement = true, clickAxisDead = true)

        sample(0, "인원 1명")
        sample(700, "인원 1명")
        tracker.markSent(ScreenSig("count", "인원 1명"), 700)
        // markFinished를 부르지 않는다 — 요청이 아직 진행 중이다.

        // 조용함 뒤 내용이 바뀌지만 in-flight라 IDLE이다. 이때 예산이 줄면 안 된다.
        assertEquals(Decision.IDLE, sample(5_600, "인원 2명"))
        assertEquals(0, tracker.quietState(5_600).coldOpens)
        assertEquals(0, tracker.quietState(5_600).opens)
    }

    @Test
    fun `실패 뒤 옮겨 간 화면은 앞 화면의 천장을 물려받지 않는다`() {
        // 초기화가 `sentSignature != null` 뒤에 있어서, 실패·폐기가 그 값을 비우고 나면 다음
        // 화면의 첫 요청이 초기화를 건너뛰었다 — B에서 조용함 뒤 눌러도 열리지 않았다
        // (PR #445 자체 리뷰, 실행으로 재현).
        fun sample(t: Long, structure: String, v: String) =
            tracker.onSample(ScreenSig(structure, v), t, hasLabeledElement = true, clickAxisDead = true)

        sample(0, "A", "9분 전")
        sample(700, "A", "9분 전")
        tracker.markSent(ScreenSig("A", "9분 전"), 700)
        tracker.markFinished()
        // 화면 A에서 cold 천장을 전부 태운다(연속 창 밖 간격).
        repeat(ScreenStabilityTracker.QUIET_COLD_OPENS) { i ->
            val t = 60_000L * (i + 1)
            val v = "${8 - i}분 전"
            sample(t, "A", v)
            tracker.markSent(ScreenSig("A", v), t)
            tracker.markFinished()
        }

        // 요청 하나가 실패해 sentSignature가 비워진다.
        tracker.markFailed(300_000)

        // 화면 B로 옮겨 실제로 요청이 나간다.
        sample(400_000, "B", "인원 1명")
        sample(400_700, "B", "인원 1명")
        tracker.markSent(ScreenSig("B", "인원 1명"), 400_700)
        tracker.markFinished()
        assertEquals(0, tracker.quietState(400_700).coldOpens)

        // B에서 조용함 뒤 사용자가 탭하면 열려야 한다.
        assertEquals(Decision.SEND, sample(410_000, "B", "인원 2명"))
    }

    @Test
    fun `빈 화면이 끼면 연속 창이 닫힌다`() {
        // 관측 불가 구간은 인과 근거가 아니다. 조용함 시계만 되감고 창을 살려 두면 되감은
        // 의미가 없다 — 스켈레톤이 끼었다 돌아온 화면이 근거 없이 창을 탄다(PR #445 자체 리뷰).
        fun sample(t: Long, v: String, labeled: Boolean = true) =
            tracker.onSample(ScreenSig("home", v), t, hasLabeledElement = labeled, clickAxisDead = true)

        sample(0, "9분 전")
        sample(700, "9분 전")
        tracker.markSent(ScreenSig("home", "9분 전"), 700)
        tracker.markFinished()
        // 조용함으로 한 번 연다 — 여기서 연속 창이 선다.
        assertEquals(Decision.SEND, sample(5_600, "8분 전"))
        tracker.markSent(ScreenSig("home", "8분 전"), 5_600)
        tracker.markFinished()

        // label이 사라졌다 돌아온다. 창이 살아 있으면 아래가 SEND가 된다.
        //
        // **샘플을 둘 넣는다.** 돌아온 첫 샘플은 홀드에 걸려 무조건 `WAIT`이라, 거기서 단언하면
        // 창이 살았든 닫혔든 통과해 **테스트가 공허해진다**(변이로 확인 — 이 줄이 하나였을 때
        // 연속 창을 안 닫는 변이가 살아남았다).
        sample(6_300, "", labeled = false)
        sample(7_000, "", labeled = false)
        sample(7_700, "7분 전")
        assertNotEquals(Decision.SEND, sample(8_400, "7분 전"))
    }

    @Test
    fun `구조 전환 요청 뒤의 연속 조작도 연속 창을 탄다`() {
        // 코레일 인원 시트의 0↔1 경계는 구조 변화다(「인원감소」 활성화 + 행 추가). 그 전환
        // 요청이 창을 안 열면, 4초 안의 다음 탭이 조용함 미달로 막혀 거짓 정체 문구가 뜬다
        // (PR #445 리뷰 확인 2 — JVM 재현 뒤 고침). 요청이 실제로 나간 구조 전환은 앵커
        // 상한을 다시 여는 것과 같은 진행 신호다.
        fun s(at: Long, sig: ScreenSig) =
            tracker.onSample(sig, at, hasLabeledElement = true, clickAxisDead = true)

        val child0 = ScreenSig("sheet-child0", "어린이 0")
        s(0, child0); s(700, child0)
        tracker.markSent(child0, 700)
        tracker.markFinished()
        s(10_000, child0)

        // 탭① 0→1: 구조 전환. 요청이 실제로 나간다.
        val child1 = ScreenSig("sheet-child1", "어린이 1")
        s(11_000, child1)
        assertEquals(Decision.SEND, s(11_700, child1))
        tracker.markSent(child1, 11_700)
        tracker.markFinished()

        // 탭② 2초 뒤 1→2: 내용만 변화, 조용함 미달 — 구조 전환이 연 창으로 열려야 한다.
        val child2 = ScreenSig("sheet-child1", "어린이 2")
        s(13_700, child2) // 홀드 1샘플
        assertEquals(Decision.SEND, s(14_400, child2))
    }

    @Test
    fun `요청 없는 구조 변화는 연속 창을 못 연다`() {
        // 창을 여는 것은 markSent(요청이 실제로 나감)이지 raw 샘플의 구조 변화가 아니다 —
        // 앵커 상한과 같은 경계다(#399 리뷰). 세션 첫 요청(sentSignature 없음)도 전환이
        // 아니므로 안 연다: 첫 화면 진입만으로 회전 배너가 창을 타면 안 된다.
        //
        // **시나리오가 raw 구조 변화를 실제로 밟는다**(#481 ②). 예전에는 전 구간이 구조
        // 하나라 「세션 첫 요청은 안 연다」 절반만 고정했고, `onSample`의 raw 구조 변화에
        // `quietOpenedAtMs = nowMs`를 더한 변이가 전체를 통과했다(PR #445 리뷰 3라운드).
        // 그 변이가 되살리는 것이 #399가 앵커에 그어 둔 경계다 — 회전 UI의 과도기 트리는
        // 한 샘플만 다른 구조로 잡히고 홀드가 그것을 버리는데, 창만 열린다.
        fun s(at: Long, sig: ScreenSig) =
            tracker.onSample(sig, at, hasLabeledElement = true, clickAxisDead = true)

        val home = ScreenSig("home", "배너 A")
        s(0, home); s(700, home)
        tracker.markSent(home, 700) // 세션 첫 요청 — 전환 아님
        tracker.markFinished()

        // ① 과도기 트리가 한 샘플만 다른 구조로 잡혔다 → 곧바로 원래 구조로 돌아온다.
        //    홀드가 둘 다 버리므로 이 사이에 나간 요청은 없다.
        assertNotEquals(Decision.SEND, s(1_400, ScreenSig("home-transient", "배너 A")))
        assertNotEquals(Decision.SEND, s(2_100, home))
        assertNotEquals(Decision.SEND, s(2_800, home))

        // ② 조용함 미달(첫 내용 변화까지 3.5초 < QUIET_MS)인 배너 회전. 창이 없어야 막힌다.
        //    ①이 창을 열었다면 두 번째 샘플에서 홀드가 차며 SEND가 된다.
        assertNotEquals(Decision.SEND, s(3_500, ScreenSig("home", "배너 B")))
        assertNotEquals(Decision.SEND, s(4_200, ScreenSig("home", "배너 B")))
    }

    @Test
    fun `root 복구는 조용함 예산을 되돌리지 않는다`() {
        // 예산은 **인과 근거가 아니라 비용 상한**이라 [markResumed]의 논거("관측할 수 없던
        // 구간은 근거가 아니다")가 안 걸린다(#481 ①). root는 우리 앱이 잠깐 앞에 서거나 권한
        // 대화상자가 뜨기만 해도 깜빡이므로(2026-08-27 SM-S921N 26분 세션에서 3회, 셋 다 그
        // 경로였다), 여기서 비우면 「한 구조에 머무는 동안은 QUIET_OPENS가 상한」이 거짓이 된다.
        burnQuietOpensOnRotatingBanner()

        tracker.markResumed(200_000, requery = false, clearQuietBudget = false)

        assertNotEquals(Decision.SEND, resumeAndRotateBanner())
    }

    @Test
    fun `화면 켜짐은 조용함 예산을 되돌린다`() {
        // 화면 꺼짐은 사용자가 폰을 덮었다 다시 여는 **실제 세션 경계**라, root 깜빡임과 달리
        // 예산을 새로 준다. 두 갈래가 실제로 갈리는지를 위 테스트와 짝으로 고정한다.
        burnQuietOpensOnRotatingBanner()

        tracker.markResumed(200_000, requery = false, clearQuietBudget = true)

        assertEquals(Decision.SEND, resumeAndRotateBanner())
    }

    /** 5초 주기 배너로 총량 천장([ScreenStabilityTracker.QUIET_OPENS])을 전부 태운다. */
    private fun burnQuietOpensOnRotatingBanner() {
        fun sample(t: Long, v: String) =
            tracker.onSample(ScreenSig("home", v), t, hasLabeledElement = true, clickAxisDead = true)

        sample(0, "배너 0")
        sample(700, "배너 0")
        tracker.markSent(ScreenSig("home", "배너 0"), 700)
        tracker.markFinished()

        repeat(ScreenStabilityTracker.QUIET_OPENS + 6) { i ->
            val t = 5_000L * (i + 1)
            val v = "배너 ${i + 1}"
            if (sample(t, v) == Decision.SEND) {
                tracker.markSent(ScreenSig("home", v), t)
                tracker.markFinished()
            }
        }
    }

    /**
     * 재개 뒤 조용함(연속 창이 아니라 cold 경로)만으로 한 번 더 열리는지 본다.
     * [ScreenStabilityTracker.markResumed]가 [ScreenStabilityTracker.QUIET_ACTIVITY_MS] 창을
     * 닫으므로, 여기서 열리는 유일한 길은 cold이고 그 문을 막는 것은 총량 천장뿐이다.
     */
    private fun resumeAndRotateBanner(): Decision {
        fun sample(t: Long, v: String) =
            tracker.onSample(ScreenSig("home", v), t, hasLabeledElement = true, clickAxisDead = true)

        sample(200_100, "배너 재개")
        sample(210_000, "배너 재개 다음") // 9.9초 조용했다 바뀐다 — cold 조건을 만족한다.
        return sample(210_700, "배너 재개 다음")
    }

    @Test
    fun `실패 뒤의 구조 전환도 연속 창을 연다`() {
        // 창을 여는 근거는 `sentSignature`가 아니라 예산이 속한 구조여야 한다(PR #445 리뷰
        // 3라운드). [ScreenStabilityTracker.markFailed]가 `sentSignature`를 비우므로, 그 근거로는
        // 실패 직후의 구조 전환이 전환으로 안 보이고 4초 안의 다음 탭이 막혀 이 PR이 없애려던
        // 거짓 정체 문구가 그대로 선다. 429·네트워크·timeout이라 클릭 유무와 무관한 경로다.
        fun s(at: Long, sig: ScreenSig) =
            tracker.onSample(sig, at, hasLabeledElement = true, clickAxisDead = true)

        val child0 = ScreenSig("sheet-child0", "어린이 0")
        s(0, child0)
        s(700, child0)
        tracker.markSent(child0, 700)
        tracker.markFailed(1_500) // 백오프 2초
        tracker.markFinished()
        assertNotEquals(Decision.SEND, s(2_200, child0)) // 아직 백오프 안

        // 탭① 0→1: 구조 전환. 백오프가 풀린 뒤 요청이 실제로 나간다.
        val child1 = ScreenSig("sheet-child1", "어린이 1")
        s(4_000, child1)
        assertEquals(Decision.SEND, s(4_700, child1))
        tracker.markSent(child1, 4_700)
        tracker.markFinished()

        // 탭② 2초 뒤 1→2: 내용만·조용함 미달 — 구조 전환이 연 창으로만 열릴 수 있다.
        val child2 = ScreenSig("sheet-child1", "어린이 2")
        s(6_700, child2)
        assertEquals(Decision.SEND, s(7_400, child2))
    }

    @Test
    fun `화면 꺼짐 폐기 뒤의 구조 전환도 연속 창을 연다`() {
        // [ScreenStabilityTracker.markResumed]의 `requery`도 `sentSignature`를 비운다 — 안내를
        // 읽다 화면이 꺼지는, 시니어에게 흔한 자리다(PR #445 리뷰 3라운드).
        fun s(at: Long, sig: ScreenSig) =
            tracker.onSample(sig, at, hasLabeledElement = true, clickAxisDead = true)

        val child0 = ScreenSig("sheet-child0", "어린이 0")
        s(0, child0)
        s(700, child0)
        tracker.markSent(child0, 700)
        tracker.markFinished()

        // 꺼져 있는 동안 도착한 응답을 버리고, 깨어 보니 화면이 옮겨 가 있다.
        tracker.markResumed(300_000, requery = true, clearQuietBudget = true)
        val child1 = ScreenSig("sheet-child1", "어린이 1")
        s(300_700, child1)
        assertEquals(Decision.SEND, s(301_400, child1))
        tracker.markSent(child1, 301_400)
        tracker.markFinished()

        val child2 = ScreenSig("sheet-child1", "어린이 2")
        s(303_400, child2)
        assertEquals(Decision.SEND, s(304_100, child2))
    }

    @Test
    fun `분 단위로 도는 숫자는 천장까지만 연다`() {
        // 「자기발생은 조용할 틈이 없다」는 실측은 **초 단위**로 도는 값의 이야기다. 인앱 시계나
        // 「N분 전」 목록은 정반대라 조용함 조건을 매번 만족한다(PR #445 리뷰). 클릭을 안 쏘는
        // 앱에서 그것을 조작과 가를 신호가 없으므로, 가르는 대신 **비용에 천장을 씌운다**.
        fun sample(t: Long, v: String) =
            tracker.onSample(ScreenSig("home", v), t, hasLabeledElement = true, clickAxisDead = true)

        sample(0, "9분 전")
        sample(700, "9분 전")
        tracker.markSent(ScreenSig("home", "9분 전"), 700)
        tracker.markFinished()

        repeat(ScreenStabilityTracker.QUIET_COLD_OPENS) { i ->
            val t = 60_000L * (i + 1)
            val v = "${8 - i}분 전"
            assertEquals(Decision.SEND, sample(t, v))
            tracker.markSent(ScreenSig("home", v), t)
            tracker.markFinished()
        }

        val past = 60_000L * (ScreenStabilityTracker.QUIET_COLD_OPENS + 1)
        assertNotEquals(Decision.SEND, sample(past, "0분 전"))
    }

    @Test
    fun `조용함의 임계는 시간이다`() {
        // 임계 바로 아래는 안 열리고 바로 위는 열린다 — 상수를 고정한다. 샘플 수로 재면 같은
        // 상수가 앱마다 4초도 7.5초도 되어 [STUCK_AFTER_MS]를 넘긴다(PR #445 리뷰).
        fun fresh() = ScreenStabilityTracker()
        fun run(gap: Long): Decision {
            val t = fresh()
            fun s(at: Long, v: String) =
                t.onSample(ScreenSig("count", v), at, hasLabeledElement = true, clickAxisDead = true)
            s(0, "A")
            s(700, "A")
            t.markSent(ScreenSig("count", "A"), 700)
            t.markFinished()
            s(1_400, "B") // 내용이 바뀌어 조용 시계가 여기서 다시 선다.
            return s(1_400 + gap, "C")
        }

        assertNotEquals(Decision.SEND, run(ScreenStabilityTracker.QUIET_MS - 100))
        assertEquals(Decision.SEND, run(ScreenStabilityTracker.QUIET_MS))
    }

    @Test
    fun `화면이 꺼져 있던 시간은 조용했던 시간이 아니다`() {
        // [markResumed]가 조작 관측을 지우는 것과 같은 성격이다 — 관측 불가 구간을 사이에 둔
        // 변화는 "방금 한 조작"이 아니다(PR #445 리뷰).
        fun sample(t: Long, v: String) =
            tracker.onSample(ScreenSig("count", v), t, hasLabeledElement = true, clickAxisDead = true)

        sample(0, "어린이 1명")
        sample(700, "어린이 1명")
        tracker.markSent(ScreenSig("count", "어린이 1명"), 700)
        tracker.markFinished()
        sample(4_900, "어린이 1명")

        tracker.markResumed(300_000, requery = false, clearQuietBudget = true)

        assertNotEquals(Decision.SEND, sample(300_700, "어린이 2명"))
    }

    @Test
    fun `세션이 바뀌면 조용함도 지워진다`() {
        fun sample(t: Long, v: String) =
            tracker.onSample(ScreenSig("count", v), t, hasLabeledElement = true, clickAxisDead = true)

        sample(0, "이전 세션")
        sample(60_000, "이전 세션") // 아주 오래 조용했던 화면을 남긴다.

        tracker.reset()

        // 새 세션의 첫 화면은 보낸 적이 없어 홀드만 채우면 SEND다 — 누수는 그 **다음**
        // 변화에서 드러난다.
        sample(100_000, "새 세션 1")
        assertEquals(Decision.SEND, sample(100_700, "새 세션 1"))
        tracker.markSent(ScreenSig("count", "새 세션 1"), 100_700)
        tracker.markFinished()
        assertNotEquals(Decision.SEND, sample(101_400, "새 세션 2"))
    }

    @Test
    fun `조용하지 않았던 화면은 클릭이 없으면 열리지 않는다`() {
        // 회전하는 검색 화면 — 샘플마다 내용이 바뀌어 조용할 틈이 없다. 실측에서 이런 화면의
        // 최대 연속 정지는 3샘플(약 2.3초)이었고 [QUIET_MS]는 그보다 길다.
        fun sample(t: Long, v: String) =
            tracker.onSample(ScreenSig("search", v), t, hasLabeledElement = true, clickAxisDead = true)

        sample(0, "검색어 A")
        sample(700, "검색어 A")
        tracker.markSent(ScreenSig("search", "검색어 A"), 700)
        tracker.markFinished()
        // 시그니처가 매 샘플 달라 초반은 WAIT이고, 승격 뒤에는 IDLE이다. 어느 쪽이든
        // **SEND는 없어야 한다** — 조용할 틈이 없으니 이 문이 열리면 안 된다.
        repeat(8) { i ->
            val decision = sample(1_400 + i * 700L, "검색어 ${'A' + i + 1}")
            assertNotEquals(Decision.SEND, decision)
        }
    }

    @Test
    fun `클릭을 쏘는 앱에서는 조용함만으로 열리지 않는다`() {
        // 배민처럼 클릭 축이 살아 있는 앱은 이 문을 아예 쓰지 않는다 — 기존 동작이 그대로다.
        fun sample(t: Long, v: String) =
            tracker.onSample(ScreenSig("cart", v), t, hasLabeledElement = true, clickAxisDead = false)

        sample(0, "수량 1")
        sample(700, "수량 1")
        tracker.markSent(ScreenSig("cart", "수량 1"), 700)
        tracker.markFinished()
        sample(4_900, "수량 1")

        assertEquals(Decision.IDLE, sample(5_600, "수량 2"))
    }

    @Test
    fun `숫자만 바뀐 화면도 조작이 관측되면 새 화면이다`() {
        // 배민 장바구니 수량 `2`->`3`. 내용 축이 숫자를 그대로 담으므로 두 화면이 갈리고,
        // 인과 게이트가 열려 있으니 다시 물어야 한다(2026-08-25 SM-S921N 실측: 정규화가
        // 있던 동안 이 경우가 `gate=open`인데 IDLE로 멈춰 있었다).
        val before = ScreenSig("cart", "수량 2")
        val after = ScreenSig("cart", "수량 3")
        tracker.onSample(before, 0, hasLabeledElement = true)
        tracker.onSample(before, 700, hasLabeledElement = true)
        tracker.markSent(before, 700)
        tracker.markFinished()

        tracker.markInteraction(1_000)
        assertEquals(Decision.WAIT, tracker.onSample(after, 1_100, hasLabeledElement = true))
        assertEquals(Decision.SEND, tracker.onSample(after, 1_800, hasLabeledElement = true))
    }

    @Test
    fun `숫자만 바뀐 화면은 조작이 없으면 새 화면이 아니다`() {
        // 시계처럼 스스로 도는 숫자. 내용 축이 숫자를 담아도 인과 게이트가 닫혀 있으면
        // 열리지 않는다 — 정규화를 걷어도 폭주로 돌아가지 않는 근거가 이 자리다.
        val base = ScreenSig("home", "남은 시간 9분")
        tracker.onSample(base, 0, hasLabeledElement = true)
        tracker.onSample(base, 700, hasLabeledElement = true)
        tracker.markSent(base, 700)
        tracker.markFinished()

        // 구조 안정이 CONTENT_SETTLE_MS를 넘긴 뒤라 홀드는 면제되고, 남는 관문은 인과뿐이다.
        repeat(3) { i ->
            val ticking = ScreenSig("home", "남은 시간 ${8 - i}분")
            assertEquals(
                Decision.IDLE,
                tracker.onSample(ticking, 4_000 + i * 700L, hasLabeledElement = true),
            )
        }
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
    fun `picker 스크롤이 앵커로 재질의를 연다`() {
        // #117 관통 확인(PR #445 리뷰). 시계 앱은 진입 탭이 클릭을 쏴 조용함 문이 닫혀 있고
        // (clickAxisDead = false), picker 스크롤은 CLICKED가 아니라 인과 게이트도 안 열린다 —
        // 남는 문이 앵커뿐이므로, 앵커가 picker 숫자를 지우면 세 문이 다 닫혀 거짓 정체
        // 문구가 뜬다. guidedTargetChanged를 가짜로 넣지 않고 **실제 판정 함수로 계산해**
        // stableLabelOf의 picker 예외까지 한 사슬로 고정한다.
        fun pickerScreen(minute: String) = Screen(
            packageName = "com.sec.android.app.clockpackage",
            elements = listOf(
                UiElement(
                    nodeId = "n2",
                    label = "$minute, 분",
                    role = "android.widget.EditText",
                    editable = true,
                ),
            ),
        )

        val before = pickerScreen("00")
        val sigBefore = ScreenSignature.of(before)
        val frozen = ScreenSignature.stableLabelOf(before.elements.first())
        tracker.onSample(sigBefore, 0, hasLabeledElement = true)
        tracker.onSample(sigBefore, 700, hasLabeledElement = true)
        tracker.markSent(sigBefore, 700)
        tracker.markFinished()

        val after = pickerScreen("30")
        val sigAfter = ScreenSignature.of(after)
        // label만 달라 구조는 같다 — 내용만의 변화라 게이트가 필요한 자리가 맞다.
        assertEquals(sigBefore.structure, sigAfter.structure)
        val moved = after.guidedSlotLabelChanged(
            "n2", frozen, sigBefore.structure, sigAfter.structure,
        )

        tracker.onSample(sigAfter, 1_400, hasLabeledElement = true, guidedTargetChanged = moved)
        assertEquals(
            Decision.SEND,
            tracker.onSample(sigAfter, 2_100, hasLabeledElement = true, guidedTargetChanged = moved),
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
        tracker.markResumed(20_000, requery = true, clearQuietBudget = true)

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

        // 사용자가 조작했고, 그 뒤로 샘플마다 내용이 달라 홀드가 영영 못 찬다.
        val interactedAt = 5_000L
        tracker.markInteraction(interactedAt)
        var t = interactedAt + 350
        var n = 2
        while (t < interactedAt + ScreenStabilityTracker.CONTENT_SETTLE_MS) {
            assertEquals(
                Decision.WAIT,
                tracker.onSample(ScreenSig("news", "속보$n"), t, hasLabeledElement = true),
            )
            t += 350
            n++
        }

        // 억제가 풀렸고 인과 창은 아직 열려 있다.
        val promotedAt = interactedAt + ScreenStabilityTracker.CONTENT_SETTLE_MS
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

    /** 회전 목록에서 슬롯이 밀린 샘플 둘(홀드 충족). 마지막 판정을 돌려준다. */
    private fun rotate(content: String, at: Long): Decision {
        val sig = ScreenSig("rooms", content)
        tracker.onSample(sig, at, hasLabeledElement = true, guidedTargetChanged = true)
        return tracker.onSample(sig, at + 700, hasLabeledElement = true, guidedTargetChanged = true)
    }

    @Test
    fun `앵커는 진행 신호가 있기 전까지 한 번만 재질의를 연다`() {
        // 안내마다 여는 것으로는 못 막는다 — 막으려는 루프의 주기가 "응답 하나 = 안내 하나"다.
        // 안내 대상의 정체성으로도 못 막는다 — node id는 자리 번호라 대상이 슬롯을 옮기면
        // 매번 달라진다(새 메시지가 대화방 순서를 밀 때).
        val base = ScreenSig("rooms", "A;B;C")
        tracker.onSample(base, 0, hasLabeledElement = true)
        tracker.onSample(base, 700, hasLabeledElement = true)
        tracker.markSent(base, 700)
        tracker.markFinished()

        assertEquals(Decision.SEND, rotate("B;A;C", 1_400))
        tracker.markSent(ScreenSig("rooms", "B;A;C"), 2_100)
        tracker.markFinished()

        // 진행이 없었으므로 이후 회전은 몇 번이 오든 앵커를 못 연다.
        assertEquals(Decision.IDLE, rotate("C;B;A", 2_800))
        assertEquals(Decision.IDLE, rotate("A;C;B", 4_200))
    }

    @Test
    fun `구조가 바뀌면 앵커 상한이 다시 열린다`() {
        val base = ScreenSig("rooms", "A;B;C")
        tracker.onSample(base, 0, hasLabeledElement = true)
        tracker.onSample(base, 700, hasLabeledElement = true)
        tracker.markSent(base, 700)
        tracker.markFinished()
        assertEquals(Decision.SEND, rotate("B;A;C", 1_400))
        tracker.markSent(ScreenSig("rooms", "B;A;C"), 2_100)
        tracker.markFinished()
        assertEquals(Decision.IDLE, rotate("C;B;A", 2_800))

        // 다른 화면에 다녀왔다 — 진행이다.
        val other = ScreenSig("detail", "상세")
        tracker.onSample(other, 4_200, hasLabeledElement = true)
        tracker.onSample(other, 4_900, hasLabeledElement = true)
        tracker.markSent(other, 4_900)
        tracker.markFinished()
        val back = ScreenSig("rooms", "C;B;A")
        tracker.onSample(back, 5_600, hasLabeledElement = true)
        tracker.onSample(back, 6_300, hasLabeledElement = true)
        tracker.markSent(back, 6_300)
        tracker.markFinished()

        assertEquals(Decision.SEND, rotate("B;C;A", 7_000))
    }

    @Test
    fun `조작이 관측되면 앵커 상한이 다시 열린다`() {
        val base = ScreenSig("rooms", "A;B;C")
        tracker.onSample(base, 0, hasLabeledElement = true)
        tracker.onSample(base, 700, hasLabeledElement = true)
        tracker.markSent(base, 700)
        tracker.markFinished()
        assertEquals(Decision.SEND, rotate("B;A;C", 1_400))
        tracker.markSent(ScreenSig("rooms", "B;A;C"), 2_100)
        tracker.markFinished()
        assertEquals(Decision.IDLE, rotate("C;B;A", 2_800))

        tracker.markInteraction(20_000)

        // **게이트가 닫힌 뒤를 본다.** 창 안에서 재면 게이트만으로도 SEND가 나서, 조작이
        // 상한을 연다는 것을 고정하지 못한다(승격 억제는 그보다 먼저 풀린다).
        val gateClosed = 20_000 + ScreenStabilityTracker.INTERACTION_WINDOW_MS + 1_000
        assertFalse(tracker.interactionGateOpen(gateClosed))
        assertEquals(Decision.SEND, rotate("A;C;B", gateClosed))
    }

    @Test
    fun `버려진 과도기 트리는 앵커 상한을 열지 않는다`() {
        // 자동 회전 UI가 전환 중간 트리를 한 샘플만 다른 구조로 내놓는다. 홀드(#32)가 그
        // 샘플을 WAIT로 버리는데 상한만 열리면, 회전마다 앵커 재질의가 되살아나 무한
        // 재질의가 이 문으로 돌아온다(#399 리뷰).
        val base = ScreenSig("rooms", "A;B;C")
        tracker.onSample(base, 0, hasLabeledElement = true)
        tracker.onSample(base, 700, hasLabeledElement = true)
        tracker.markSent(base, 700)
        tracker.markFinished()
        assertEquals(Decision.SEND, rotate("B;A;C", 1_400))
        tracker.markSent(ScreenSig("rooms", "B;A;C"), 2_100)
        tracker.markFinished()

        // 과도기 트리 — 구조가 다른 샘플이 하나 끼었다가 원래 구조로 돌아온다(WAIT로 버려진다).
        assertEquals(
            Decision.WAIT,
            tracker.onSample(ScreenSig("rooms-mid", "…"), 2_800, hasLabeledElement = true),
        )

        // 복귀 뒤 슬롯이 또 밀려도 상한은 닫힌 채여야 한다.
        assertEquals(Decision.IDLE, rotate("C;B;A", 3_500))
        assertEquals(Decision.IDLE, rotate("A;C;B", 4_900))
    }

    @Test
    fun `관측 재개와 빈 화면 샘플은 앵커 상한을 열지 않는다`() {
        // 상한을 여는 자리는 구조 전환 전송(markSent)과 조작 둘뿐이다. 관측 공백 표식
        // (`structureStableSinceMs == null`)에 얹으면 재개가 진행으로 둔갑한다.
        val base = ScreenSig("rooms", "A;B;C")
        tracker.onSample(base, 0, hasLabeledElement = true)
        tracker.onSample(base, 700, hasLabeledElement = true)
        tracker.markSent(base, 700)
        tracker.markFinished()
        assertEquals(Decision.SEND, rotate("B;A;C", 1_400))
        tracker.markSent(ScreenSig("rooms", "B;A;C"), 2_100)
        tracker.markFinished()

        // 빈 화면 샘플 하나(스플래시 등)와 root 복구를 거친다.
        tracker.onSample(ScreenSig("rooms", ""), 2_800, hasLabeledElement = false)
        tracker.markResumed(3_500, requery = false, clearQuietBudget = false)

        // 같은 구조로 돌아왔다 — 진행이 없었으므로 상한은 그대로 닫혀 있다.
        assertEquals(Decision.IDLE, rotate("C;B;A", 4_200))
    }

    @Test
    fun `게이트가 열려 있으면 앵커 상한을 태우지 않는다`() {
        // 그 재질의는 조작이 만든 것이라 앵커가 없어도 나갔다. 태우면 뒤이은 자기발생
        // 변화에 쓸 앵커가 없다.
        val base = ScreenSig("rooms", "A;B;C")
        tracker.onSample(base, 0, hasLabeledElement = true)
        tracker.onSample(base, 700, hasLabeledElement = true)
        tracker.markSent(base, 700)
        tracker.markFinished()

        tracker.markInteraction(1_000)
        val settled = 1_000 + ScreenStabilityTracker.CONTENT_SETTLE_MS
        assertEquals(Decision.SEND, rotate("B;A;C", settled))
        tracker.markSent(ScreenSig("rooms", "B;A;C"), settled + 700)
        tracker.markFinished()
        assertFalse(tracker.anchorRequerySpent())
    }

    @Test
    fun `요청이 실제로 나가지 않으면 앵커 상한을 쓰지 않는다`() {
        // `SEND`는 "요청이 나갔다"가 아니다 — 호출자가 확인 대기(GD-4)나 in-flight CAS에 막혀
        // 요청 없이 돌아설 수 있다. 그때 소진하면 그 안내의 재질의가 나가지도 못한 채 끝난다.
        val base = ScreenSig("rooms", "A;B;C")
        tracker.onSample(base, 0, hasLabeledElement = true)
        tracker.onSample(base, 700, hasLabeledElement = true)
        tracker.markSent(base, 700)
        tracker.markFinished()

        // SEND가 났지만 호출자가 요청을 시작하지 못해 markSent를 부르지 않았다.
        assertEquals(Decision.SEND, rotate("B;A;C", 1_400))
        assertFalse(tracker.anchorRequerySpent())
        assertEquals(Decision.SEND, rotate("B;A;C", 2_800))
    }

    @Test
    fun `내용 축으로 버린 응답은 그 화면을 다시 묻게 한다`() {
        // 버린 근거가 "요청~응답 사이 조작"인데 그 응답이 인과 창 끝에 도착하면, 버리기만 해서는
        // 다음 샘플이 새 상황이 못 된다 — 옮겨 간 화면을 영영 안 묻는다(#380 리뷰).
        val page1 = ScreenSig("list", "1페이지")
        tracker.onSample(page1, 0, hasLabeledElement = true)
        tracker.onSample(page1, 700, hasLabeledElement = true)
        tracker.markSent(page1, 700)
        tracker.markInteraction(800)
        tracker.markFinished()

        // 응답이 인과 창(5초)이 닫힌 뒤에 도착해 폐기됐다.
        tracker.markResponseDiscarded()

        val page2 = ScreenSig("list", "2페이지")
        val late = 800 + ScreenStabilityTracker.INTERACTION_WINDOW_MS + 1_000
        // 구조가 오래 안정이라 승격이 홀드를 면제한다 — 첫 샘플부터 나간다.
        assertEquals(Decision.SEND, tracker.onSample(page2, late, hasLabeledElement = true))
    }

    @Test
    fun `정체 시점에는 내용만 바뀐 전송이라고 적지 않는다`() {
        // 눌림 확인 재질의가 이 값을 실어 `screen_changed`를 적는다. 직전 SEND의 값이 남으면
        // `performed=true` + `screen_changed=true`라는 모순된 이력이 나가, 재질의의 존재
        // 이유("눌렀는데 그대로")가 페이로드에서 지워진다(#380 재리뷰).
        val page1 = ScreenSig("list", "1페이지")
        tracker.onSample(page1, 0, hasLabeledElement = true)
        tracker.onSample(page1, 700, hasLabeledElement = true)
        tracker.markSent(page1, 700)
        tracker.markFinished()

        tracker.markInteraction(1_000)
        val page2 = ScreenSig("list", "2페이지")
        tracker.onSample(page2, 1_100, hasLabeledElement = true)
        assertEquals(Decision.SEND, tracker.onSample(page2, 1_800, hasLabeledElement = true))
        assertTrue(tracker.lastSendWasContentOnly())
        tracker.markSent(page2, 1_800)
        tracker.markFinished()

        // 안내한 대상을 눌렀는데 화면이 그대로다.
        tracker.markPressed(2_000)
        val stuckAt = 2_000 + ScreenStabilityTracker.STUCK_AFTER_MS
        assertEquals(Decision.STUCK, tracker.onSample(page2, stuckAt, hasLabeledElement = true))
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

        tracker.markResumed(2_000, requery = false, clearQuietBudget = true)

        assertFalse(tracker.interactionAfter(0))
        assertFalse(tracker.interactionGateOpen(2_400))
        val banner2 = ScreenSig("home", "배너2")
        assertEquals(Decision.WAIT, tracker.onSample(banner2, 2_400, hasLabeledElement = true))
        assertEquals(Decision.IDLE, tracker.onSample(banner2, 3_100, hasLabeledElement = true))
    }
}

private typealias Decision = ScreenStabilityTracker.Decision
