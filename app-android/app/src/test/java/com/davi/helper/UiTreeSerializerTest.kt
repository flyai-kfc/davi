package com.davi.helper

import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 목킹 프레임워크가 없어 `AccessibilityNodeInfo`는 다루지 못한다.
 * 대신 위험한 로직(페이지 dot 파싱, 축 추정)만 순수 함수로 분리해 여기서 고정한다.
 */
class UiTreeSerializerTest {

    @Test
    fun `실기기에서 관찰된 문구를 파싱한다`() {
        // 실측(SM-S921N, 앱서랍): content-desc가 이 형태로 온다.
        val dot = UiTreeSerializer.parsePageDot("2 페이지 중 1 페이지입니다.")
        assertEquals(UiTreeSerializer.PageDot(total = 2, current = 1), dot)
    }

    @Test
    fun `자릿수가 늘어도 파싱한다`() {
        assertEquals(
            UiTreeSerializer.PageDot(total = 12, current = 10),
            UiTreeSerializer.parsePageDot("12 페이지 중 10 페이지입니다."),
        )
    }

    @Test
    fun `페이지 문구가 아니면 null`() {
        assertNull(UiTreeSerializer.parsePageDot("카카오톡"))
        assertNull(UiTreeSerializer.parsePageDot("페이지"))
        assertNull(UiTreeSerializer.parsePageDot(""))
    }

    @Test
    fun `안 보이는 노드는 자기 신호로 덮개가 되지 않는다`() {
        // #358이 세운 규칙. 자식은 계속 보되(가시성은 트리 아래로 단조롭지 않다) **자기** 신호는
        // 안 센다 — 안 그리는 것을 가림으로 치면 보이는 요소가 안내에서 빠진다.
        assertFalse(
            UiTreeSerializer.paintsOwnContent(visibleToUser = false, clickable = true, hasOwnLabel = true),
        )
        assertTrue(
            UiTreeSerializer.paintsOwnContent(visibleToUser = true, clickable = true, hasOwnLabel = false),
        )
        assertTrue(
            UiTreeSerializer.paintsOwnContent(visibleToUser = true, clickable = false, hasOwnLabel = true),
        )
        // 보여도 그릴 것이 없으면 덮개가 아니다 — 빈 투명 오버레이를 걸러내는 자리다(#336).
        assertFalse(
            UiTreeSerializer.paintsOwnContent(visibleToUser = true, clickable = false, hasOwnLabel = false),
        )
    }

    @Test
    fun `상한은 안 보임이 연속된 길이에만 걸린다`() {
        // 실측이 정한 상한은 4다. **1은 안 된다** — #358이 되찾은 요소가 깊이 1에 있어
        // (배민 메뉴 상세 7개·가게 11개), 거기서 끊으면 그 요소들이 통째로 사라진다.
        // 손실 0의 하한이 1이고 세 겹을 여유로 얹었다(근거는 `MAX_HIDDEN_RUN` KDoc).
        // **상한을 올리거나 내리면 이 세 줄도 함께 고친다.**
        assertFalse(UiTreeSerializer.exceedsHiddenChain(1))
        assertFalse(UiTreeSerializer.exceedsHiddenChain(4))
        assertTrue(UiTreeSerializer.exceedsHiddenChain(5))

        // 경계가 `>=`로 밀리는 것을 막는다. cap을 받는 형태라 값과 규칙을 따로 고정할 수 있다.
        assertFalse(UiTreeSerializer.exceedsHiddenChain(run = 5, cap = 5))
        assertTrue(UiTreeSerializer.exceedsHiddenChain(run = 6, cap = 5))
    }

    @Test
    fun `보이는 노드를 만나면 사슬이 0으로 되돌아간다`() {
        // **리셋이 빠지는 것이 이 상한의 진짜 실패 방식이다.** 값 판정만 고정하면 못 잡는다 —
        // 리셋이 없으면 사슬이 화면 전체에 누적돼 깊은 트리의 아래쪽이 통째로 사라진다.
        assertEquals(0, UiTreeSerializer.nextHiddenRun(visibleToUser = true, previousRun = 7))
        assertEquals(0, UiTreeSerializer.nextHiddenRun(visibleToUser = true, previousRun = 0))

        // 안 보이면 한 칸씩만 는다.
        assertEquals(1, UiTreeSerializer.nextHiddenRun(visibleToUser = false, previousRun = 0))
        assertEquals(3, UiTreeSerializer.nextHiddenRun(visibleToUser = false, previousRun = 2))
    }

    @Test
    fun `가림 스윕을 건너뛰는 형제는 덮개 판정이 버리던 형제와 같다`() {
        // **이 동치가 「화면 밖 형제는 스윕하지 않는다」의 근거 전부다**(#367).
        // `RectCover.isCovered`가 화면으로 자른 뒤 빈 조각을 버리므로(`clip.isEmpty` → continue),
        // 그 형제의 가림 판정 결과는 결과에 닿지 못한다. 한쪽 식이 바뀌면 여기서 갈린다.
        val screen = RectCover.Box(0, 0, 1080, 2340)
        val boxes = listOf(
            // #358의 배민 메뉴 상세 하단 바. 안 보인다고 보고하면서 화면 안에 면적을 갖는다 —
            // 이런 형제는 계속 스윕한다.
            RectCover.Box(0, 1928, 1080, 2178),
            // 오프스크린 ViewPager 페이지의 두 얼굴: 폭 0과 화면 오른쪽 밖(실측, #359 표본).
            RectCover.Box(540, 0, 540, 2340),
            RectCover.Box(1080, 0, 2160, 2340),
            // 화면 안이어도 빈 사각형은 덮개가 될 수 없다.
            RectCover.Box(100, 200, 100, 400),
            RectCover.Box(100, 200, 400, 200),
            // 화면 위·아래로 완전히 벗어난 것.
            RectCover.Box(0, -500, 1080, -100),
            RectCover.Box(0, 2340, 1080, 2800),
            // 변이 맞닿기만 한 것 — 좌·상 양쪽.
            RectCover.Box(-100, 0, 0, 2340),
            RectCover.Box(0, -100, 1080, 0),
            // 목록의 첫 행처럼 화면 위로 걸친 것은 살아 있다.
            RectCover.Box(0, -80, 1080, 40),
            // 화면을 통째로 덮는 것.
            RectCover.Box(-10, -10, 1090, 2350),
        )
        for (box in boxes) {
            assertEquals(
                "box=$box",
                !box.intersect(screen).isEmpty,
                UiTreeSerializer.boxIntersects(box.left, box.top, box.right, box.bottom, screen),
            )
        }

        // 위 동치와 별개로 값 자체도 못박는다 — 두 식이 함께 틀리는 것을 막는다.
        assertTrue(UiTreeSerializer.boxIntersects(0, 1928, 1080, 2178, screen))
        assertFalse(UiTreeSerializer.boxIntersects(540, 0, 540, 2340, screen))
        assertFalse(UiTreeSerializer.boxIntersects(1080, 0, 2160, 2340, screen))
    }

    @Test
    fun `방향 액션이 있으면 그것으로 축을 정한다`() {
        // 모양과 반대여도 액션이 이긴다. 가로로 넓은 세로 스크롤러가 실제로 있다.
        assertEquals(
            UiTreeSerializer.Axis.VERTICAL,
            UiTreeSerializer.resolveAxis(horizontal = false, vertical = true, width = 900, height = 100),
        )
        assertEquals(
            UiTreeSerializer.Axis.HORIZONTAL,
            UiTreeSerializer.resolveAxis(horizontal = true, vertical = false, width = 100, height = 900),
        )
    }

    @Test
    fun `방향 액션이 없으면 모양으로 추정한다`() {
        assertEquals(
            UiTreeSerializer.Axis.VERTICAL,
            UiTreeSerializer.resolveAxis(horizontal = false, vertical = false, width = 100, height = 900),
        )
        assertEquals(
            UiTreeSerializer.Axis.HORIZONTAL,
            UiTreeSerializer.resolveAxis(horizontal = false, vertical = false, width = 900, height = 100),
        )
    }

    @Test
    fun `스와이프 방향은 화면이 이동하는 방향이다`() {
        // 계약(C17)은 손가락이 아니라 "보러 가는 쪽"이다. 목록 아래를 더 보려면 next 는 down,
        // 다음 페이지로 넘어가려면 right다. 손가락은 그 반대이며 그리는 경계에서만 뒤집는다.
        assertEquals(SwipeDirection.DOWN, UiTreeSerializer.Axis.VERTICAL.next)
        assertEquals(SwipeDirection.UP, UiTreeSerializer.Axis.VERTICAL.prev)
        assertEquals(SwipeDirection.RIGHT, UiTreeSerializer.Axis.HORIZONTAL.next)
        assertEquals(SwipeDirection.LEFT, UiTreeSerializer.Axis.HORIZONTAL.prev)
    }

    @Test
    fun `입력 메타를 손가락으로 바꾸면 실제로 해야 하는 동작이 된다`() {
        // 입력 메타(여기)와 손 모션(GestureMotion)이 서로 다른 기준을 쓰면 안내가 조용히
        // 반대로 나간다. 두 자리를 한 테스트로 묶어 고정한다.
        assertEquals(
            SwipeDirection.UP,
            GestureMotion.fingerDirectionFor(UiTreeSerializer.Axis.VERTICAL.next),
        )
        assertEquals(
            SwipeDirection.LEFT,
            GestureMotion.fingerDirectionFor(UiTreeSerializer.Axis.HORIZONTAL.next),
        )
    }

    @Test
    fun `스크롤 가능 요소 관측은 트리 전체에서 누적한다`() {
        assertEquals(
            false,
            UiTreeSerializer.observeScrollable(
                seen = false,
                isScrollable = false,
                hasScrollActions = false,
            ),
        )
        assertEquals(
            true,
            UiTreeSerializer.observeScrollable(
                seen = false,
                isScrollable = true,
                hasScrollActions = false,
            ),
        )
        assertEquals(
            true,
            UiTreeSerializer.observeScrollable(
                seen = false,
                isScrollable = false,
                hasScrollActions = true,
            ),
        )
        assertEquals(
            true,
            UiTreeSerializer.observeScrollable(
                seen = true,
                isScrollable = false,
                hasScrollActions = false,
            ),
        )
    }

    @Test
    fun `IME는 창 type 존재 여부만 관측한다`() {
        assertEquals(
            true,
            UiTreeSerializer.imeVisibility(
                listOf(
                    AccessibilityWindowInfo.TYPE_APPLICATION,
                    AccessibilityWindowInfo.TYPE_INPUT_METHOD,
                ),
            ),
        )
        assertEquals(
            false,
            UiTreeSerializer.imeVisibility(listOf(AccessibilityWindowInfo.TYPE_APPLICATION)),
        )
        assertNull(UiTreeSerializer.imeVisibility(emptyList()))
        assertNull(UiTreeSerializer.imeVisibility(null))
    }

    @Test
    fun `Android 16 체크 상태는 부분 선택을 보존한다`() {
        assertEquals(
            CheckState.CHECKED,
            UiTreeSerializer.checkState(AccessibilityNodeInfo.CHECKED_STATE_TRUE),
        )
        assertEquals(
            CheckState.UNCHECKED,
            UiTreeSerializer.checkState(AccessibilityNodeInfo.CHECKED_STATE_FALSE),
        )
        assertEquals(
            CheckState.PARTIAL,
            UiTreeSerializer.checkState(AccessibilityNodeInfo.CHECKED_STATE_PARTIAL),
        )
        assertEquals(CheckState.UNAVAILABLE, UiTreeSerializer.checkState(Int.MIN_VALUE))
    }

    @Test
    fun `API 게이트는 지원되는 체크 판독기만 호출한다`() {
        assertEquals(
            CheckState.UNCHECKED,
            UiTreeSerializer.checkStateFor(
                sdkInt = 35,
                checkable = true,
                modernState = { error("API 36 판독기를 호출하면 안 된다") },
                legacyChecked = { false },
            ),
        )
        assertEquals(
            CheckState.PARTIAL,
            UiTreeSerializer.checkStateFor(
                sdkInt = 36,
                checkable = true,
                modernState = { AccessibilityNodeInfo.CHECKED_STATE_PARTIAL },
                legacyChecked = { error("구 API 판독기를 호출하면 안 된다") },
            ),
        )
        assertEquals(
            CheckState.UNAVAILABLE,
            UiTreeSerializer.checkStateFor(
                sdkInt = 36,
                checkable = false,
                modernState = { error("non-checkable 노드를 읽으면 안 된다") },
                legacyChecked = { error("non-checkable 노드를 읽으면 안 된다") },
            ),
        )
    }

    @Test
    fun `라벨을 흡수하면 자손의 켬끔도 함께 올린다`() {
        // 행 전체가 clickable인 설정 목록에서 스위치 자식이 clickable이 아니면 그 자식은 흡수돼
        // 별도 요소로 나가지 않는다. 상태를 행으로 올리지 않으면 화면에서 켬/끔이 사라진다(#288 리뷰).
        assertEquals(
            CheckState.CHECKED,
            UiTreeSerializer.hoistedCheckState(CheckState.UNAVAILABLE, CheckState.CHECKED),
        )
        assertEquals(
            CheckState.UNCHECKED,
            UiTreeSerializer.hoistedCheckState(CheckState.UNAVAILABLE, CheckState.UNCHECKED),
        )
        assertEquals(
            CheckState.PARTIAL,
            UiTreeSerializer.hoistedCheckState(CheckState.UNAVAILABLE, CheckState.PARTIAL),
        )
        assertEquals(
            CheckState.UNAVAILABLE,
            UiTreeSerializer.hoistedCheckState(CheckState.UNAVAILABLE, CheckState.UNAVAILABLE),
        )
    }

    @Test
    fun `자기 상태가 있으면 자손보다 우선한다`() {
        assertEquals(
            CheckState.UNCHECKED,
            UiTreeSerializer.hoistedCheckState(CheckState.UNCHECKED, CheckState.CHECKED),
        )
        assertEquals(
            CheckState.PARTIAL,
            UiTreeSerializer.hoistedCheckState(CheckState.PARTIAL, CheckState.CHECKED),
        )
    }

    @Test
    fun `이름 없는 클릭 노드는 상태를 조상에 맡긴다`() {
        // 실측(SM-S921N / One UI 7.0 SwitchPreference): Switch가 clickable인데 text·desc가 둘 다 없어
        // 자기 요소로 못 나간다. 조상이 안 올리면 켬/끔이 화면에서 사라진다(#288 리뷰).
        assertEquals(false, UiTreeSerializer.emitsOwnElement(label = "", editable = false))
        assertEquals(false, UiTreeSerializer.emitsOwnElement(label = "   ", editable = false))
    }

    @Test
    fun `이름이 있거나 입력칸이면 자기 상태를 자기가 들고 간다`() {
        assertEquals(true, UiTreeSerializer.emitsOwnElement(label = "Wi-Fi", editable = false))
        assertEquals(true, UiTreeSerializer.emitsOwnElement(label = "", editable = true))
    }

    @Test
    fun `hint 여부는 입력칸에만 있다`() {
        assertNull(UiTreeSerializer.hintTextState(editable = false, showingHintText = false))
        assertEquals(true, UiTreeSerializer.hintTextState(editable = true, showingHintText = true))
        assertEquals(false, UiTreeSerializer.hintTextState(editable = true, showingHintText = false))
    }

    @Test
    fun `창 제목은 label과 같은 1차 마스킹을 거친다`() {
        // 대화방 제목은 사람 이름이고 문서 뷰어는 파일명을 싣는다(#269).
        assertEquals(
            "••• 님",
            UiTreeSerializer.windowTitleOf("010-1234-5678 님", PrivacyMaskingLabelMasker),
        )
    }

    @Test
    fun `창 제목의 고유명은 일부러 지우지 않는다`() {
        // **여기가 유혹의 자리다.** 트리를 다 보는 것은 UiTreeSerializer뿐이라, "구조로 목록
        // 항목을 가리자"는 규칙이 들어온다면 이 경로다(app-android/CLAUDE.md). 그런데 그러면
        // 앱 서랍의 앱 이름까지 지워져 안내가 죽는다 — 저장소 루트 `SPEC.md` §2 「패턴 밖
        // 고유명」이 지우지 않기로 정했다(#360). 이 줄이 빨개지면 그 결정을 뒤집는 것이므로
        // 패턴이나 규칙을 늘리기 전에 SPEC부터 고친다.
        //
        // 값에 숫자를 섞어 둔 이유는, 숫자가 없는 이름은 형태 기반 패턴이 늘어도 애초에
        // 스치지 않아 가드가 되지 못하기 때문이다(PR #369 리뷰).
        for (title in listOf("지훈의 Galaxy Buds2", "Buds2 Pro 3C86", "거실 TV 65", "회의록 2026 초안")) {
            assertEquals(title, UiTreeSerializer.windowTitleOf(title, PrivacyMaskingLabelMasker))
        }
    }

    @Test
    fun `창 제목도 label과 같은 정규화를 거친다`() {
        assertEquals(
            "설정 배터리",
            UiTreeSerializer.windowTitleOf("  설정\n  배터리 ", PrivacyMaskingLabelMasker),
        )
    }

    @Test
    fun `제목이 없는 창은 null이다`() {
        // 11번가·TMAP·플레이스토어는 제목이 없었다(#269 실측). null이 정상 값이다.
        assertNull(UiTreeSerializer.windowTitleOf(null, PrivacyMaskingLabelMasker))
        assertNull(UiTreeSerializer.windowTitleOf("   ", PrivacyMaskingLabelMasker))
    }

    @Test
    fun `편집 요소의 고정 문맥은 hint와 관계 이름으로 만든다`() {
        assertEquals("검색어 입력", UiTreeSerializer.editableLabel(" 검색어 입력 "))
        assertNull(UiTreeSerializer.editableLabel(null))
        assertEquals(false, UiTreeSerializer.shouldInspectDescendants(editable = true))
        assertEquals(true, UiTreeSerializer.shouldInspectDescendants(editable = false))
    }

    @Test
    fun `supplementalDescription은 API 36부터만 읽는다`() {
        // 게이트가 열리는 경계를 고정한다 — 결합 규칙 테스트만으로는 `>= 35` 오타를 못 잡는다.
        assertEquals("여성", UiTreeSerializer.supplementalDescriptionFor(36) { "여성" })
        assertEquals("여성", UiTreeSerializer.supplementalDescriptionFor(37) { "여성" })
        assertNull(UiTreeSerializer.supplementalDescriptionFor(35) { "여성" })
        assertNull(UiTreeSerializer.supplementalDescriptionFor(26) { "여성" })
        // 게이트가 열려도 값이 없으면 없는 것이다.
        assertNull(UiTreeSerializer.supplementalDescriptionFor(36) { null })
    }

    @Test
    fun `게이트가 닫히면 값을 읽지도 않는다`() {
        // 구버전에서 API 36 메서드를 부르면 NoSuchMethodError다 — 값 인자로 받으면 코틀린이
        // 인자를 먼저 평가해 게이트 밖에서 호출된다(PR #384 2R에서 실제로 그렇게 넣었다가 잡혔다).
        var read = false
        assertNull(UiTreeSerializer.supplementalDescriptionFor(35) { read = true; "여성" })
        assertFalse(read)

        assertEquals("여성", UiTreeSerializer.supplementalDescriptionFor(36) { read = true; "여성" })
        assertTrue(read)
    }

    @Test
    fun `편집 불가 요소의 이름은 text 다음 desc 다음 supplemental 순으로 고른다`() {
        // 앞의 값이 있으면 뒤는 보지 않는다.
        assertEquals("앞", UiTreeSerializer.staticLabel("앞", "뒤", "더뒤"))
        assertEquals("뒤", UiTreeSerializer.staticLabel(" ", "뒤", "더뒤"))
        // 웹뷰 라디오가 여기로 온다 — text·desc가 비고 supplemental만 있다(#381).
        assertEquals("여성", UiTreeSerializer.staticLabel("", null, "여성"))
        assertEquals("은평구", UiTreeSerializer.staticLabel(null, "  ", " 은평구 "))
        assertNull(UiTreeSerializer.staticLabel(null, "  ", ""))
    }

    @Test
    fun `입력칸 라벨은 이름과 placeholder를 구버전과 같은 한 덩어리로 만든다`() {
        // API 36: 이름이 supplemental로 갈라져 온다 → 되붙인다.
        assertEquals(
            "방문자명 방문자명을 입력하세요",
            UiTreeSerializer.editableLabel("방문자명을 입력하세요", "방문자명"),
        )
        // API 35 이하: hint에 이미 합쳐져 오고 supplemental은 null이다 → 그대로.
        assertEquals(
            "방문자명 방문자명을 입력하세요",
            UiTreeSerializer.editableLabel("방문자명 방문자명을 입력하세요", null),
        )
        // 구버전 hint가 이름으로 시작하면 두 번 싣지 않는다.
        assertEquals(
            "연락처 연락처를 입력하세요",
            UiTreeSerializer.editableLabel("연락처 연락처를 입력하세요", "연락처"),
        )
        // placeholder만 있는 칸은 이름이 supplemental에만 있다.
        assertEquals("라벨 없는 칸", UiTreeSerializer.editableLabel("", "라벨 없는 칸"))
        // 둘이 같으면 한 번만.
        assertEquals("연령", UiTreeSerializer.editableLabel("연령", "연령"))
        assertNull(UiTreeSerializer.editableLabel(null, null))
    }

    @Test
    fun `자유 입력값은 label에 싣고 시그니처에서는 고정한다`() {
        val projection = editableProjection(
            text = "치킨 배달",
            hintText = "검색어 입력",
        )

        assertEquals("검색어 입력 치킨 배달", projection?.label)
        assertEquals("검색어 입력", projection?.signatureLabel)
    }

    @Test
    fun `이름 없는 자유 입력도 값은 보내되 고정 토큰으로 서명한다`() {
        val projection = editableProjection(text = "메모 내용", hintText = null)

        assertEquals("메모 내용", projection?.label)
        assertEquals("<editable>", projection?.signatureLabel)
    }

    @Test
    fun `hint 표시 상태는 hint를 입력값으로 중복하지 않는다`() {
        val projection = editableProjection(
            text = "검색어 입력",
            hintText = "검색어 입력",
            showingHintText = true,
        )

        assertEquals("검색어 입력", projection?.label)
        assertEquals("검색어 입력", projection?.signatureLabel)
    }

    @Test
    fun `비밀번호와 인증 코드의 값은 직렬화 후보에서 완전히 제외한다`() {
        val password = editableProjection(
            text = "never-store-password",
            hintText = "비밀번호",
            password = true,
        )
        val otp = editableProjection(
            text = "654321",
            hintText = "인증 코드 654321",
            sensitiveIdentity = true,
        )
        val serializedCandidate = listOfNotNull(password, otp)
            .flatMap { listOf(it.label, it.signatureLabel) }
            .joinToString(" ")

        assertEquals("비밀번호 입력", password?.label)
        assertEquals("인증 코드 입력", otp?.label)
        assertFalse(serializedCandidate.contains("never-store-password"))
        assertFalse(serializedCandidate.contains("654321"))
    }

    @Test
    fun `content description에 코드와 이름이 섞여도 일반 이름만 남긴다`() {
        val projection = editableProjection(
            text = null,
            contentDescription = "인증 코드 654321",
            hintText = null,
            sensitiveIdentity = true,
        )

        assertEquals("인증 코드 입력", projection?.label)
        assertFalse(projection?.label.orEmpty().contains("654321"))
    }

    @Test
    fun `민감 입력 이름은 일반 의미 표지로 찾고 spinner 같은 부분 일치는 제외한다`() {
        assertSensitiveIdentity(hintText = "인증 번호")
        assertSensitiveIdentity(hintText = "Security code")
        assertSensitiveIdentity(resourceId = "com.example:id/otp_field")
        assertSensitiveIdentity(resourceId = "com.example:id/pinEditText")
        assertSensitiveIdentity(resourceId = "com.example:id/OTPField")
        assertSensitiveIdentity(resourceId = "com.example:id/verification_code")
        assertSensitiveIdentity(resourceId = "com.example:id/securityCode")
        assertFalse(sensitiveIdentity(hintText = "spinner value"))
        assertFalse(sensitiveIdentity(hintText = "shipping address"))
    }

    @Test
    fun `민감 표지는 접근성 속성 경계를 이어 붙이지 않고 resource id의 지도 pin을 제외한다`() {
        assertFalse(
            sensitiveIdentity(
                hintText = "code input",
                resourceId = "com.example:id/map_security",
            ),
        )
        assertFalse(sensitiveIdentity(resourceId = "com.example:id/map_pin_label"))
        assertFalse(sensitiveIdentity(resourceId = "com.pin.example:id/search_input"))
        assertFalse(sensitiveIdentity(hintText = "map pin label"))
        assertTrue(sensitiveIdentity(hintText = "PIN"))
        assertTrue(sensitiveIdentity(hintText = "Enter PIN"))
        assertTrue(sensitiveIdentity(resourceId = "com.example:id/pin_input"))
    }

    @Test
    fun `제한 선택값은 label과 시그니처 양쪽에 보존한다`() {
        val minute = editableProjection(
            text = "30",
            hintText = "분",
            limitedSelection = true,
        )
        val period = editableProjection(
            text = "오후",
            hintText = "시간대",
            limitedSelection = true,
        )

        assertEquals("분 30", minute?.label)
        assertEquals(minute?.label, minute?.signatureLabel)
        assertTrue(minute?.tracksEditableValue == true)
        assertEquals("시간대 오후", period?.label)
        assertEquals(period?.label, period?.signatureLabel)
        assertTrue(period?.tracksEditableValue == true)
    }

    @Test
    fun `editable 개인정보 패턴도 일반 label과 같은 1차 마스킹을 거친다`() {
        val values = listOf(
            "hong@example.com",
            "010-1234-5678",
            "900101-1234567",
            "1234-5678-9012-3456",
            "123-456-789012",
            "3C:86:9A:1B:2C:3D",
        )

        values.forEach { value ->
            val projection = editableProjection(text = value, hintText = "입력값")!!
            assertEquals("입력값 •••", PrivacyMasker.maskText(projection.label))
            assertEquals("입력값", PrivacyMasker.maskText(projection.signatureLabel))
        }
    }

    @Test
    fun `가까운 조상은 활성 NumberPicker이며 조정 가능해야 한다`() {
        assertEquals(
            true,
            UiTreeSerializer.isAdjustableControlAncestor(
                visibleToUser = true,
                enabled = true,
                role = "NumberPicker",
                scrollable = true,
                hasAdjustmentAction = false,
            ),
        )
        assertEquals(
            true,
            UiTreeSerializer.isAdjustableControlAncestor(
                visibleToUser = true,
                enabled = true,
                role = "SeslNumberPicker",
                scrollable = false,
                hasAdjustmentAction = true,
            ),
        )
        assertEquals(
            false,
            UiTreeSerializer.isAdjustableControlAncestor(
                visibleToUser = true,
                enabled = true,
                role = "EditText",
                scrollable = true,
                hasAdjustmentAction = true,
            ),
        )
        assertEquals(
            false,
            UiTreeSerializer.isAdjustableControlAncestor(
                visibleToUser = false,
                enabled = true,
                role = "NumberPicker",
                scrollable = true,
                hasAdjustmentAction = true,
            ),
        )
    }

    private fun editableProjection(
        text: CharSequence?,
        hintText: CharSequence?,
        contentDescription: CharSequence? = null,
        name: CharSequence? = null,
        showingHintText: Boolean = false,
        password: Boolean = false,
        sensitiveIdentity: Boolean = false,
        limitedSelection: Boolean = false,
    ): UiTreeSerializer.TextProjection? = UiTreeSerializer.editableTextProjection(
        text = text,
        contentDescription = contentDescription,
        hintText = hintText,
        name = name,
        showingHintText = showingHintText,
        password = password,
        sensitiveIdentity = sensitiveIdentity,
        limitedSelection = limitedSelection,
    )

    private fun sensitiveIdentity(
        hintText: CharSequence? = null,
        name: CharSequence? = null,
        contentDescription: CharSequence? = null,
        resourceId: CharSequence? = null,
    ): Boolean = UiTreeSerializer.isSensitiveInputIdentity(
        hintText = hintText,
        name = name,
        contentDescription = contentDescription,
        resourceId = resourceId,
    )

    private fun assertSensitiveIdentity(
        hintText: CharSequence? = null,
        name: CharSequence? = null,
        contentDescription: CharSequence? = null,
        resourceId: CharSequence? = null,
    ) {
        assertTrue(sensitiveIdentity(hintText, name, contentDescription, resourceId))
    }
}
