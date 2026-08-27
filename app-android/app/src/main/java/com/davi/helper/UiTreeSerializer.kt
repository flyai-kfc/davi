package com.davi.helper

import android.annotation.SuppressLint
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.view.accessibility.AccessibilityWindowInfo

/**
 * 접근성 트리 -> LLM용 요소 목록.
 *
 * 핵심은 **클릭 가능한 노드에 자손 텍스트를 합쳐 `label`을 만드는 것**이다.
 * 안드로이드에서는 "누를 수 있는 것"과 "글자"가 다른 노드로 갈라져 있어서,
 * 이걸 안 하면 모델이 무엇을 누를지 알 수 없다(HANDOFF §4의 핵심 자산).
 *
 * 반대로 **아무 데나 합치면 안 된다.** 홈 화면 워크스페이스처럼 커다란 컨테이너에 합치면
 * 라벨이 "시계 텍스트"가 되어 모델이 시계를 조작 대상으로 고른다(HANDOFF §3-9).
 * 그래서 [shouldMergeDescendants]가 큰 컨테이너를 걸러낸다.
 *
 * 화면 요소와 스크롤/page/IME 상태를 함께 채운다. `has_scrollable_element`는 보이는 트리 전체에서
 * 스크롤 가능 노드나 scroll action을 하나라도 관측했는지를 나타낸다(C10). IME는 창의 type만
 * 관측하고 키보드의 키 노드는 순회하지 않는다. editable 값은 비밀번호 상태나 일반 의미 표지로
 * 인증·보안 코드라고 식별된 값을 제외한 뒤 일반 label과 같은 1차 마스킹을 거쳐 전달한다(#462).
 */
object UiTreeSerializer {

    /** 트리가 깊어도 이 이상은 안 내려간다(무한·초대형 트리 방어). */
    private const val MAX_DEPTH = 40

    /** 한 화면에서 만들 요소 수 상한. 초과분은 버리고 로그로 알린다. */
    private const val MAX_ELEMENTS = 200

    /** 가림 판정에서 "내용이 있나"를 볼 때 내려갈 깊이. */
    private const val MAX_CONTENT_DEPTH = 6

    /**
     * **안 보이는 노드만 연속으로 이어진 사슬**을 몇 겹까지 따라갈지(#367). 보이는 노드를 만나면
     * 0으로 리셋된다.
     *
     * #358이 없앤 절단을 되살리는 것이 아니다 — 거기서 끊던 근거는 노드 **하나의 자기 보고**였고,
     * 가시성은 트리 아래로 단조롭지 않아 그 보고가 자식에 대해 아무것도 말해 주지 않는다. 여기서
     * 끊는 근거는 **관측이 연속으로 비었다는 사실**이다. 이 파일이 순회에 상한을 두는 관행은
     * [MAX_DEPTH]·[MAX_CONTENT_DEPTH]·[MAX_MERGE_DEPTH]에 이미 있으나, 그 셋은 **절대 깊이**이고
     * 이것은 **연속 길이**다 — 보이는 노드를 하나 만나면 0으로 되돌아간다([nextHiddenRun]).
     *
     * 값은 실측이 정했다(2026-08-24 SM-S921N / One UI, 7개 화면 · 화면당 20~39 폴링).
     * 사슬 길이별 방출 요소 수를 재니 **깊이 1까지만 요소가 있었다** — 배민 가게 11개, 메뉴 상세
     * 7개(#358이 되찾은 `담기`·상단 바가 그것이다). 깊이 2 이상에서 방출된 요소는 7개 화면
     * 어디에도 없었다. 그래서 손실 0의 하한은 1이다.
     *
     * **하한에 붙이지 않고 세 겹 위로 올린 것은 두 방향의 값이 다르기 때문이다**(PR #416 리뷰).
     * 짧아서 잃는 것은 #358이 되찾은 종류의 컨트롤이고 **조용하다** — 요소가 그냥 없고 서명은
     * 폴링마다 일관되게 같아 홀드도 안 걸리므로, 재지 않은 앱에서 "안내가 엉뚱한 것을 가리킨다"로만
     * 드러난다. 길어서 잃는 것은 **폴링당 방문 몇십 회**다 — 다만 **그 크기를 잰 것은 네이버 앱 홈
     * 하나뿐이다**(방출 깊이는 7개 화면을 쟀지만 방문 분포는 한 화면이다). 다른 앱에서 질량이 얕은
     * 층에 걸려 있으면 이 값이 커진다.
     *
     * 그 화면(변경 전 순회 822회)에서 상한별 잔여 방문은 `2 → 83` · `3 → 100` · `4 → 101` ·
     * `9 → 109`이고, 질량 713회는 깊이 **10 이상**에 있다. **한계비용이 `2→3`에서 17회, 거기부터는
     * 층당 1~3회**라 곡선은 3에서 꺾이고 4는 그 안쪽이다. 4는 관측 방출 깊이보다 세 겹 위이면서
     * 상한이 여전히 병적 서브트리를 잡는 지점이다 — 더 올려도 몇 회를 더 쓸 뿐이지만 그만큼
     * 상한이 무뎌진다. 이 숫자는 [WalkCost.visits]와 같은 축이라 **가림 스윕의 `getChild()`는 밖이다.**
     *
     * 이득은 사슬이 긴 화면에 몰린다 — 그 사슬의 정체는 오프스크린 ViewPager 페이지다(최대 깊이 22).
     * 사슬이 얕은 화면(캘린더·앱서랍·배민)에서는 이 상한에 걸리는 노드가 없어 이득도 손실도 없다.
     *
     * **상한은 양쪽으로 관측을 바꾼다.** 짧으면 못 보고 지나가는 쪽만 생각하기 쉬운데, **올리면 더
     * 들어간다.** 요소 방출과 [observeScrollable]이 `!isVisibleToUser` return **뒤**에 있어서,
     * 상한을 올린 만큼 오프스크린 페이지 **안쪽으로** 더 들어가고 거기서 보이는 자손을 만나면
     * 요소·`has_scrollable_element`가 늘 수 있다. [walk]가 스크롤 관측을 일부러 그 자리에 둔 이유가
     * 바로 그 위험이다(오프스크린 페이지가 `scrollable=true`를 올려 런처 페이징 가드레일이 조용히
     * 꺼진다). **그래서 이 값을 바꾸면 화면 서명을 다시 대조해야 한다** — 값이 4인 지금은 7개 화면에서
     * 확인했다.
     *
     * **다만 천장은 절단 없는 원래 코드다**(PR #416 재리뷰). 올려서 늘어나는 관측이 #367 이전 동작을
     * 넘지는 못하므로, 이 경고는 "새 위험이 생긴다"가 아니라 **"낮은 상한에서 올릴 때는 서명을 다시
     * 대조하라"** 로 읽는다. 실제로 상한 4에서는 네이버 앱 홈을 뺀 6개 화면의 순회 노드 수가 변경 전과
     * **한 노드도 다르지 않았다**(`cut=0`).
     *
     * 반대 방향으로 **측정이 못 본 것도 하나 있다.** 근거로 쓴 것은 사슬 길이별 **방출 요소 수**인데,
     * `isScrollable`만 있고 scroll action도 label도 없는 노드는 요소로 방출되지 않으면서
     * `has_scrollable_element`는 올린다. 그런 노드가 상한 밖 사슬에 있으면 화면 단위 스크롤 관측이
     * 뒤집힐 수 있고, 그 값은 화면 서명의 **구조 축**이다. 재지 않은 앱에서는 열려 있다.
     */
    private const val MAX_HIDDEN_RUN = 4

    /** 라벨을 만들 때 자손을 몇 단계까지 훑을지. */
    private const val MAX_MERGE_DEPTH = 4

    /** 라벨에 이어붙일 텍스트 조각 수 상한. */
    private const val MAX_LABEL_PARTS = 6

    private const val MAX_LABEL_CHARS = 120

    /** NumberPicker 내부 구현이 wrapper를 두는 경우에도 가까운 최대 세 단계까지만 허용한다. */
    private const val MAX_ADJUSTABLE_ANCESTOR_DEPTH = 3

    private val WHITESPACE = Regex("\\s+")

    /** 시·분·날짜 picker에서 관측되는 "6, 시간", "00, 분" 같은 짧은 선택값을 식별한다. */
    private val ADJUSTABLE_NUMERIC_LABEL =
        Regex("""^\d{1,4}(?:\s*(?:[,،]\s*)?[\p{L}]{1,12})?$""")

    /**
     * 비밀번호 플래그를 누락한 입력 위젯과 OTP 입력칸을 보수적으로 가르는 접근성 이름 표지.
     * package나 resource-id의 고정값이 아니라 hint·관계 이름·resource-id에 드러난 일반 의미만 본다.
     */
    private val SENSITIVE_KOREAN_IDENTITIES = setOf(
        "비밀번호",
        "비번",
        "암호",
        "패스워드",
        "핀번호",
        "인증번호",
        "인증코드",
        "보안코드",
        "일회용비밀번호",
        "일회용코드",
    )
    private val SENSITIVE_ENGLISH_IDENTITY_TOKENS = setOf("password", "passcode", "pin", "otp")
    private val SENSITIVE_ENGLISH_NON_PIN_TOKENS = SENSITIVE_ENGLISH_IDENTITY_TOKENS - "pin"
    private val UPPERCASE_PIN_TOKEN = Regex("(?:^|[^A-Za-z])PIN(?:$|[^A-Za-z])")
    private val SENSITIVE_ENGLISH_IDENTITIES = setOf(
        "verificationcode",
        "securitycode",
        "onetimepassword",
        "onetimecode",
    )

    /** 자유 입력값을 화면 동일성에서 제외할 때 쓰는 기기 내부 고정 투영. wire에는 실리지 않는다. */
    private const val EDITABLE_SIGNATURE_TOKEN = "<editable>"
    private const val PASSWORD_EDITABLE_LABEL = "비밀번호 입력"
    private const val SENSITIVE_CODE_EDITABLE_LABEL = "인증 코드 입력"

    internal data class TextProjection(
        val label: String,
        val signatureLabel: String,
        val tracksEditableValue: Boolean = false,
        val targetAnchorLabel: String? = null,
    )

    /**
     * 런처 페이지 인디케이터(dot)의 content-desc. 예: "6 페이지 중 2 페이지입니다."
     *
     * 삼성 런처는 구성에 따라 `scrollable` 노드를 하나도 노출하지 않는다(다중 페이지 홈·격자
     * 앱서랍 실측 — `app-android/CLAUDE.md` 기기 함정). 그런 화면에서는
     * **현재/전체 페이지를 알 수 있는 유일한 경로가 이것뿐이다**(HANDOFF §3-3).
     *
     * 한국어 문구에 묶여 있다. 다국어는 SPEC §11의 열린 결정이다.
     */
    private val PAGE_DOT_RE = Regex("""(\d+)\s*페이지\s*중\s*(\d+)\s*페이지""")

    private const val ROLE_PAGE_DOT = "page_dot"

    /**
     * 순회 한 번의 비용(#367).
     *
     * `n`(요소 수)은 정규화 인자가 못 된다 — [MAX_ELEMENTS]에서 포화하고, 애초에 비용이 방출이
     * 아니라 방문을 따른다(실측: 네이버 앱 홈 21요소가 캘린더 55요소보다 30배 비쌌다).
     * `ms`를 나눠 볼 분모가 [visits]다.
     *
     * @param visits [walk]가 들어간 노드 수. **`getChild()` 총 호출 수가 아니다** — 가림 판정
     *   ([hasPaintedContent])과 라벨 병합([collectDescendantText]·[descendantCheckState])이
     *   내는 IPC는 이 값 밖이다. 그 셋의 비율을 알아야 하면 보정 계측을 다시 붙여야 한다.
     * @param cuts [MAX_HIDDEN_RUN]에 걸려 끊은 서브트리 수. **잎은 뺀다 — 잎만 뺀다.**
     *   자식이 없으면 아무것도 안 버린다는 것은 확실하지만, 자식이 있는데 그 서브트리가 아무것도
     *   방출 안 하는 경우는 여전히 센다. 오프스크린 ViewPager가 있는 화면에서는 그게 정상이다
     *   (네이버 앱 홈이 그렇다). 방출 여부로 세려면 끊은 서브트리를 돌아야 해서 상한의 취지와
     *   부딪친다. **그래서 `0`일 때만 강한 신호다** — 0이면 이 화면의 결과는 상한과 무관하고,
     *   0이 아닌 것은 "봐야 할 수도 있다"까지다. **상한을 얼마로 올려야 하는지도 이 값은 답하지
     *   못한다** — 그건 사슬 길이 분포라 보정 계측의 몫이다.
     */
    data class WalkCost(val visits: Int, val cuts: Int)

    /** [WalkCost]를 만드는 동안의 가변 카운터. */
    private class CostCounter {
        var visits = 0
        var cuts = 0

        fun snapshot(): WalkCost = WalkCost(visits = visits, cuts = cuts)
    }

    /** 직렬화 한 번 동안의 누적 상태. 파라미터가 늘어나는 것을 막는다. */
    private class Ctx(val screenRect: RectCover.Box, val masker: LabelMasker) {
        /** 0이면 아래 넓이 비율이 0으로 나눈다. 창을 못 읽은 화면에서 실제로 0이 될 수 있다. */
        val screenArea: Long =
            ((screenRect.right - screenRect.left).toLong() *
                (screenRect.bottom - screenRect.top).toLong()).coerceAtLeast(1L)
        val elements = mutableListOf<UiElement>()
        var counter = 0
        var currentPage: Int? = null
        var totalPages: Int? = null
        var hasScrollableElement = false
        val cost = CostCounter()

        /**
         * 기하 판정용 스크래치. 노드마다 `Rect`를 새로 만들면 폴링당 수천 개가 된다(#367).
         *
         * **채운 즉시 읽고, 그 사이에 다른 호출을 두지 않는다.** 지금 쓰는 자리는
         * [intersectsScreen] 하나뿐이고 거기서 채움과 소비 사이에 호출이 없다. 이 규칙을 어기면
         * 앨리어싱이 조용히 생긴다 — `boxOf`가 매번 새 `Rect`를 만드는 것도 그래서다.
         */
        val scratch = Rect()
    }

    /**
     * 자기 넓이가 화면의 이 비율을 넘으면 "누를 수 있는 대상"이 아니라 컨테이너로 본다.
     * 이런 노드에는 자손 텍스트를 합치지 않는다.
     *
     * 삼성 런처 워크스페이스는 `scrollable`을 노출하지 않는 구성이 있어(위 [PAGE_DOT_RE])
     * `isScrollable`만으로는 걸러낼 수 없다. 그래서 넓이로 판단한다.
     */
    private const val CONTAINER_AREA_RATIO = 0.5

    data class Result(val screen: Screen, val truncated: Boolean, val cost: WalkCost)

    /**
     * @param masker 서버로 나갈 label에 적용할 1차 마스킹 정책. 기본값은 빌드 플래그가
     *               정하며 release 산출물에서는 항상 [PrivacyMaskingLabelMasker]다(#203).
     */
    fun serialize(
        root: AccessibilityNodeInfo,
        windowTypes: Iterable<Int>? = null,
        windowTitle: CharSequence? = null,
        masker: LabelMasker = labelMaskerFor(BuildConfig.PRIVACY_MASKING_DISABLED),
    ): Result {
        val ctx = Ctx(boxOf(root), masker)
        walk(root, ctx, absorbed = false, depth = 0, cover = emptyList(), hiddenRun = 0)

        val truncated = ctx.elements.size > MAX_ELEMENTS
        return Result(
            cost = ctx.cost.snapshot(),
            screen = Screen(
                packageName = root.packageName?.toString().orEmpty(),
                windowTitle = windowTitleOf(windowTitle, masker),
                elements = if (truncated) ctx.elements.take(MAX_ELEMENTS) else ctx.elements,
                currentPage = ctx.currentPage,
                totalPages = ctx.totalPages,
                hasScrollableElement = ctx.hasScrollableElement,
                imeVisible = imeVisibility(windowTypes),
            ),
            truncated = truncated,
        )
    }

    /**
     * @param absorbed 조상 클릭 노드가 이 서브트리의 텍스트를 이미 라벨로 흡수했는지.
     *                 흡수됐다면 같은 글자를 별도 요소로 또 내보내지 않는다.
     */
    private fun walk(
        node: AccessibilityNodeInfo?,
        ctx: Ctx,
        absorbed: Boolean,
        depth: Int,
        cover: List<RectCover.Box>,
        hiddenRun: Int,
    ) {
        if (node == null) return
        // **상한 검사보다 먼저 센다.** 이 노드는 부모가 이미 `getChild()`로 당겨온 뒤라, 여기서
        // 돌아가도 그 비용은 치른 것이다. 뒤에 세면 요소가 [MAX_ELEMENTS]에 닿는 순간 `visits`도
        // 함께 얼어붙어 **`ms`를 나눌 분모가 되지 못한다** — `n`을 버린 이유와 같은 함정이다.
        ctx.cost.visits++
        if (depth > MAX_DEPTH || ctx.elements.size > MAX_ELEMENTS) return
        // **가시성은 트리 아래로 단조롭지 않다.** 부모가 false인데 자식은 true인 화면이 있다
        // (2026-08-21 SM-S921N 실측, #358: 배민 메뉴 상세의 상·하단 바 컨테이너가
        // `visibleToUser=false`인데 그 안의 `담기` 버튼·`뒤로가기`·제목은 true였다).
        // 그래서 **이 노드 하나의 보고로는 끊지 않는다** — 자기만 건너뛰고 자식은 계속 훑는다.
        // 화면 밖 목록 항목은 **자기 자신이** false라 그대로 걸러지므로, 이 규칙이 원래 막으려던
        // 것은 그대로 막힌다.
        // **끊는 자리는 하나이고 근거가 다르다** — 아래 [exceedsHiddenChain]은 노드의 자기 보고가
        // 아니라 **안 보임이 연속된 길이**를 본다. 값의 근거는 [MAX_HIDDEN_RUN]에 있다.
        // **한 노드에서 바로 끊는 자리가 둘 남아 있고, 그대로 두기로 했다**(#382) — 라벨 병합·상태
        // hoist([collectDescendantText]·[descendantCheckState]). 같은 규칙으로 고쳤을 때 값이
        // 달라지는 요소가 **20개 화면에서 0개**였다(2026-08-25 SM-S921N). 병합은 모든 화면에서
        // 돌았고(4~32회) 네이버 앱 홈에서는 완화 경로가 안 보이는 자식을 실제로 만나 내려갔는데도
        // (4회) label과 상태가 그대로였다 — 발화하려면 **병합 중인 노드 안**의 안 보이는 노드
        // **아래에 보이는 텍스트**가 있어야 하는데 그 조합이 관측되지 않는다. #358의 하단 바는
        // 병합 대상 밖이었고 자식들이 자기 요소로 나갔다. 고치는 쪽의 값이 0이라 순회를 더 하는
        // 비용만 남는다(#367이 줄인 축과 반대). 가림 판정 [hasPaintedContent]는 #359가 맞췄다.
        // **스크롤 관측은 일부러 둔다.** 아래 `observeScrollable`이 이 return 뒤에 있어, 스크롤
        // 컨테이너 자신이 `visibleToUser=false`면 `has_scrollable_element`도 스크롤 대상도 안 나간다.
        // 여기서도 관측하면 **오프스크린 페이지가 `scrollable=true`를 올려 런처 페이징 가드레일이
        // 조용히 꺼진다** — 그 위험이 구멍보다 크다는 것이 표본으로 나왔다(2026-08-22,
        // `scrollable`을 덤프에 넣어 **다시 뜬** 15개 화면 표본. PR #359 본문의 27개 화면 표본과는
        // 다른 회차라 절단 지점 수가 다르다):
        // 절단 지점 155곳 중 스크롤 노드를 가진 곳은 6곳뿐이고 그 11개 중 10개가 **화면 밖**이다
        // (오프스크린 ViewPager 페이지의 폭 0·화면 오른쪽 밖 컨테이너). 화면 안에 있던 하나는
        // 배민 가게의 가로 필터 칩 줄인데, 같은 화면 본문 스크롤러가 `vis=true`라 화면 단위
        // `has_scrollable_element`는 살아 있었다.
        if (!node.isVisibleToUser) {
            val run = nextHiddenRun(visibleToUser = false, previousRun = hiddenRun)
            if (exceedsHiddenChain(run)) {
                // **자식이 있을 때만 센다** — 잎에서 끊는 것은 아무것도 안 버린다(PR #416 리뷰).
                // `childCount`는 로컬 필드라 이 검사에 IPC가 없고, `getChild()`가 null을 줄 수
                // 있으므로 "자식이 실제로 잡힌다"의 근사다. 이 가드가 걷는 것은 잎뿐이라는 점은
                // [WalkCost.cuts]에 적었다.
                if (node.childCount > 0) ctx.cost.cuts++
                return
            }
            walkChildren(node, ctx, absorbed, depth, cover, hiddenRun = run)
            return
        }

        // 이 노드가 **위에 그려진 형제에게 완전히 덮였는지**. isVisibleToUser는 형제 가림을
        // 보지 않으므로(안드로이드 스펙) 여기서 따로 센다.
        val occluded = RectCover.isCovered(boxOf(node), cover, ctx.screenRect)

        val hasScrollActions = hasScrollActions(node)
        ctx.hasScrollableElement = observeScrollable(
            seen = ctx.hasScrollableElement,
            isScrollable = node.isScrollable,
            hasScrollActions = hasScrollActions,
        )

        var childrenAbsorbed = absorbed

        // editable 값도 label에는 싣는다(#462). 다만 자유 입력값은 TextProjection의 별도 투영으로
        // 시그니처·타깃 앵커에서 고정하고, NumberPicker 제한 선택값만 실제 값을 변화 신호로 쓴다.
        val ownTextValue = ownText(node)
        val isPageDot = ownTextValue != null && parsePageDot(ownTextValue.label) != null
        var emitted = false

        if (isPageDot) {
            readPageDot(ownTextValue!!.label, node, ctx)
            ctx.elements.add(
                element(
                    node,
                    ownTextValue.label,
                    ctx,
                    role = ROLE_PAGE_DOT,
                    clickable = false,
                    obscured = occluded,
                ),
            )
            emitted = true
        } else if (node.isClickable || node.isEditable) {
            // 커스텀 입력 위젯은 사용자가 입력한 값을 비편집 자손에 복제하기도 한다. 편집 노드는
            // 자기 값만 쓰고 자손을 라벨에 합치지도, 아래에서 순회하지도 않는다.
            val inspectDescendants = shouldInspectDescendants(node.isEditable)
            val merge = inspectDescendants && shouldMergeDescendants(node, ctx.screenArea)
            val text = if (merge) mergedText(node) else ownTextValue ?: TextProjection("", "")
            val label = text.label
            // 라벨을 흡수했으면 상태도 함께 올린다. 그러지 않으면 스위치가 든 행에서 켬/끔이
            // 통째로 사라진다 — 자식 스위치는 흡수돼 안 나가고, 행 자신은 checkable이 아니라
            // unavailable이 된다(#288 리뷰). 라벨과 상태가 같은 요소에 실려야 모델이 둘을 잇는다.
            val checkState = if (merge) mergedCheckState(node) else checkStateOf(node)
            // 이름 없는 클릭 노드는 내보내지 않는다. 모델이 가리킬 수단이 없어 잡음만 된다
            // (실측: 전화 앱에서 통화 버튼을 감싼 빈 FrameLayout 두 개가 이렇게 잡혔다).
            // 이름이 없어도 편집 가능한 노드는 type 대상과 포커스 상태를 전달해야 한다.
            if (label.isNotBlank() || node.isEditable) {
                ctx.elements.add(
                    element(
                        node,
                        label,
                        ctx,
                        checkState = checkState,
                        obscured = occluded,
                        signatureLabel = text.signatureLabel,
                        signatureTracksEditableValue = text.tracksEditableValue,
                        targetAnchorLabel = text.targetAnchorLabel,
                    ),
                )
                emitted = true
                // 라벨로 흡수한 경우에만 자손의 글자를 중복으로 내보내지 않는다.
                childrenAbsorbed = merge
            }
        } else if (!absorbed) {
            if (ownTextValue != null) {
                ctx.elements.add(
                    element(
                        node,
                        ownTextValue.label,
                        ctx,
                        obscured = occluded,
                        signatureLabel = ownTextValue.signatureLabel,
                        signatureTracksEditableValue = ownTextValue.tracksEditableValue,
                        targetAnchorLabel = ownTextValue.targetAnchorLabel,
                    ),
                )
                emitted = true
                // 시계처럼 자기 글자가 이미 자손의 요약인 **작은** 노드는 자손을 또 내보내지 않는다
                // (실측: 잠금화면 시계가 '오후12:23' + 낱개 숫자 '1','2','2','3'으로 5개가 됐다).
                // 큰 컨테이너는 흡수하지 않는다 — 그러면 그 안의 아이콘을 통째로 잃는다.
                if (shouldMergeDescendants(node, ctx.screenArea)) childrenAbsorbed = true
            }
        }

        // 스크롤 컨테이너는 글자도 없고 클릭도 안 돼서 위 조건에 하나도 안 걸린다.
        // 그런데 스크롤 상태는 **잎이 아니라 컨테이너에** 있다. 안 내보내면 모델은 화면이
        // 더 넘어가는지조차 모르고, 넘기라고 정해도 무엇을 쓸어야 하는지 가리킬 수단이 없다.
        // (실측: 설정 앱에서 scroll 메타가 한 건도 안 나왔다.)
        if (!emitted && hasScrollActions) {
            ctx.elements.add(element(node, "", ctx, obscured = occluded))
        }

        walkChildren(
            node,
            ctx,
            childrenAbsorbed,
            depth,
            cover,
            hiddenRun = nextHiddenRun(visibleToUser = true, previousRun = hiddenRun),
        )
    }

    /**
     * 안 보임 사슬의 다음 길이. **보이는 노드를 만나면 0으로 되돌아간다** — 안 보이는 노드가
     * 화면에 100개 흩어져 있어도 사이사이 보이는 노드가 있으면 상한에 안 걸린다는 뜻이다.
     *
     * [exceedsHiddenChain]과 따로 뽑는 이유는 **깨지는 방식이 다르기 때문**이다. 그쪽은 경계
     * (`>` vs `>=`)가 밀리는 것이고, 이쪽은 **리셋이 빠지는 것**이다. 리셋이 빠지면 사슬이 화면
     * 전체에 걸쳐 누적돼 깊은 트리의 아래쪽이 통째로 사라지는데, 값 판정만 고정해서는 그걸 못 잡는다.
     */
    internal fun nextHiddenRun(visibleToUser: Boolean, previousRun: Int): Int =
        if (visibleToUser) 0 else previousRun + 1

    /**
     * 자식들을 훑는다. 보이는 노드와 [walk]가 건너뛴 노드가 **같은 규칙으로** 자식을 훑도록
     * 한 곳에 둔다 — 건너뛴 노드는 자기 자신을 내보내지 않을 뿐, 아래는 살아 있다(#358).
     * 그 "아래"의 끝이 [MAX_HIDDEN_RUN]이다 — 안 보임이 그만큼 연속되면 [walk]가 여기 오기 전에
     * 돌아간다(#367).
     * 순회를 끊는 편집 경계([shouldInspectDescendants])도 여기서 한 번만 본다 — 호출자마다
     * 두면 두 경로가 조용히 어긋난다.
     */
    private fun walkChildren(
        node: AccessibilityNodeInfo,
        ctx: Ctx,
        childrenAbsorbed: Boolean,
        depth: Int,
        cover: List<RectCover.Box>,
        hiddenRun: Int,
    ) {
        if (!shouldInspectDescendants(node.isEditable)) return
        val kids = (0 until node.childCount).mapNotNull { node.getChild(it) }
        // **형제당 한 번만** 판정한다. 쌍마다 다시 부르면 자식 k개에 k*(k-1)번이 되고, 내용이
        // 없는 서브트리는 조기 반환이 없어 매번 깊이 끝까지 `getChild()`로 IPC가 나간다.
        // 직렬화는 폴링마다 돌고 안정·정체 판정이 그 시간 위에 얹혀 있다(PR #336 리뷰).
        //
        // **길이가 갈래마다 다르다**(0 또는 `kids.size`). 아래에서 `getOrElse`로 읽는 이유가 그것이고,
        // 기본값 `false`는 의미상으로도 맞다 — 건너뛴 갈래는 덮개 후보가 없는 경우다.
        val painted = when {
            // **형제가 있어야 읽히는 값이다.** 안드로이드 트리는 단일 자식 래퍼 사슬이 흔한데,
            // 거기서 계산하면 사슬 깊이만큼 안 쓰는 순회(깊이 6, `getChild()` IPC)가 폴링마다 생긴다.
            kids.size < 2 -> emptyList()
            // 그리는 순서가 모두 같으면 아래 `>` 비교가 하나도 참이 안 된다 — 덮개 후보가 없으니
            // 스윕도 필요 없다(#367).
            // `kids[0]`은 위 갈래가 크기를 보장한 뒤라 안전하고, 둘 다 `Int`라 박싱이 없다.
            kids.all { it.drawingOrder == kids[0].drawingOrder } -> emptyList()
            // **화면과 안 겹치는 형제는 덮개가 될 수 없다.** [RectCover.isCovered]가 화면으로 자른
            // 뒤 빈 조각을 `continue`로 버리므로, 그런 형제의 판정 결과는 결과에 닿지 않는다.
            // 기하 판정을 앞에 두어 **가장 비싼 스윕을 가장 먼저 없앤다** — 안 보이는 노드만으로
            // 이뤄진 오프스크린 페이지는 [paintsOwnContent]가 `visibleToUser`를 요구해 조기 반환이
            // 한 번도 안 걸리고 깊이 6까지 전수 조사를 돈다(2026-08-24 SM-S921N 실측: 네이버 앱
            // 홈에서 이 스윕이 `getChild()` 3788회로 순회 822회의 4.6배였다).
            else -> kids.map { intersectsScreen(it, ctx) && hasPaintedContent(it, 0) }
        }
        for ((index, child) in kids.withIndex()) {
            // 이 자식보다 **위에 그려지고 내용이 있는** 형제만 가림으로 친다.
            // 내용 조건이 없으면 빈 투명 오버레이(실측: draw=5)가 시트를 가린 것으로 잡힌다.
            // drawingOrder는 Compose의 **가상 노드에서도 채워진다**(2026-08-21 SM-S921N 실측:
            // AndroidComposeView 안쪽에서 스크림 draw=0 / 패널 draw=1). 전부 0으로 와서 판정이
            // 조용히 꺼지는 provider(WebView 등)는 아직 관측하지 못했다 — PR #336 "남은 것".
            // **`boxOf`는 조건이 참일 때만 부른다.** `takeIf`로 쓰면 인자가 먼저 평가돼 자식 k개마다
            // 형제 k개의 `Rect`가 만들어진다 — 노드당 k²개이고 대부분 그 자리에서 버려진다(#367).
            val childCover = cover + kids.withIndex().mapNotNull { (other, sibling) ->
                if (
                    other != index &&
                    sibling.drawingOrder > child.drawingOrder &&
                    painted.getOrElse(other) { false }
                ) {
                    boxOf(sibling)
                } else {
                    null
                }
            }
            walk(child, ctx, childrenAbsorbed, depth + 1, childCover, hiddenRun)
        }
    }

    /**
     * 이 노드가 화면과 조금이라도 겹치는가. `Rect`를 새로 만들지 않으려고 [Ctx.scratch]에
     * 채워 읽는다 — 노드마다 할당하면 계측이 재려는 꼬리를 스스로 만든다(#367).
     */
    private fun intersectsScreen(node: AccessibilityNodeInfo, ctx: Ctx): Boolean {
        node.getBoundsInScreen(ctx.scratch)
        return boxIntersects(
            ctx.scratch.left,
            ctx.scratch.top,
            ctx.scratch.right,
            ctx.scratch.bottom,
            ctx.screenRect,
        )
    }

    /**
     * 겹침 판정을 Android 타입 없이 고정하기 위한 순수 함수.
     *
     * **빈 사각형은 안 겹치는 것으로 친다** — 폭이나 높이가 0이면 화면 안에 있어도 덮개가 될 수
     * 없고([RectCover.isCovered]가 `clip.isEmpty`로 버린다), 그리는 것도 없다.
     */
    internal fun boxIntersects(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        screen: RectCover.Box,
    ): Boolean = maxOf(left, screen.left) < minOf(right, screen.right) &&
        maxOf(top, screen.top) < minOf(bottom, screen.bottom)

    private fun boxOf(node: AccessibilityNodeInfo): RectCover.Box =
        boundsOf(node).let { RectCover.Box(it.left, it.top, it.right, it.bottom) }

    /**
     * 이 서브트리가 실제로 무언가를 그리는지. **투명한 빈 오버레이를 가림에서 빼려고** 둔다.
     * 접근성 트리에는 알파가 없어서 이것이 유일한 대용 신호다.
     */
    private fun hasPaintedContent(node: AccessibilityNodeInfo, depth: Int): Boolean {
        if (depth > MAX_CONTENT_DEPTH) return false
        // 안 보이는 노드는 **자기 자신만** 내용에서 빼고 자식은 계속 본다([walk]와 같은 이유).
        // 안 보인다고 보고된 컨테이너가 실제로는 그리는 경우가 있어서다 — 2026-08-21 SM-S921N
        // 실측(#358): 배민 메뉴 상세 하단 바 `[0,1928,1080,2178]`가 `visibleToUser=false`인데
        // 화면을 덮고 있고, 그 뒤로 완전히 들어간 옵션 행 `[0,1933,1080,2137]`이 한 픽셀도 안
        // 보이는데 `obscured`로 안 찍혔다.
        // 안 그리는 것을 가림으로 치는 반대쪽 사고는 열리지 않는다 — 자식까지 안 보이면 false다.
        // **[MAX_HIDDEN_RUN]은 여기 걸지 않는다.** 실패 방향이 반대다 — 여기서 덜 보면 덮개를
        // 놓쳐 **모달 뒤 요소가 안내 대상이 된다**(#336이 고친 것). 이미 [MAX_CONTENT_DEPTH]로
        // 묶여 있고, 비용은 호출부가 화면과 안 겹치는 형제를 빼면서 걷었다(#367).
        if (
            paintsOwnContent(
                visibleToUser = node.isVisibleToUser,
                clickable = node.isClickable,
                hasOwnLabel = !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank(),
            )
        ) {
            return true
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (hasPaintedContent(child, depth + 1)) return true
        }
        return false
    }

    /**
     * 페이지 dot 하나를 읽어 화면 단위 페이지 위치를 채운다.
     *
     * **`selected`인 dot만 현재 페이지로 삼는다.** 홈에는 "N 페이지 중 M 페이지" 노드가
     * 페이지마다 따로 있어서, 아무거나 집으면 틀린 값이 나온다(HANDOFF §2에서 폐기한 길).
     * 전체 페이지 수는 어느 dot에서 읽어도 같으므로 처음 본 값을 쓴다.
     */
    private fun readPageDot(text: String, node: AccessibilityNodeInfo, ctx: Ctx) {
        val dot = parsePageDot(text) ?: return
        if (ctx.totalPages == null) ctx.totalPages = dot.total
        if (node.isSelected) ctx.currentPage = dot.current
    }

    /** "6 페이지 중 2 페이지입니다." -> total=6, current=2. 목킹 없이 테스트하려고 분리했다. */
    internal data class PageDot(val total: Int, val current: Int)

    internal fun parsePageDot(text: String): PageDot? {
        val m = PAGE_DOT_RE.find(text) ?: return null
        val total = m.groupValues[1].toIntOrNull() ?: return null
        val current = m.groupValues[2].toIntOrNull() ?: return null
        return PageDot(total, current)
    }

    private fun element(
        node: AccessibilityNodeInfo,
        label: String,
        ctx: Ctx,
        role: String? = null,
        clickable: Boolean? = null,
        checkState: CheckState? = null,
        obscured: Boolean = false,
        signatureLabel: String? = null,
        signatureTracksEditableValue: Boolean = false,
        targetAnchorLabel: String? = null,
    ): UiElement {
        ctx.counter++
        val r = boundsOf(node)
        val scroll = scrollStateOf(node, r)
        val maskedLabel = normalizeAndMaskLabel(label, ctx.masker)
        val maskedSignatureLabel = signatureLabel
            ?.let { normalizeAndMaskLabel(it, ctx.masker) }
            ?.takeUnless { it == maskedLabel && !signatureTracksEditableValue }
        val maskedTargetAnchorLabel = targetAnchorLabel
            ?.let { normalizeAndMaskLabel(it, ctx.masker) }
        return UiElement(
            nodeId = "n${ctx.counter}",
            // 줄바꿈·중복 공백을 한 칸으로 눌러 로그와 프롬프트를 읽을 수 있게 만든다
            // (실측: 키패드 라벨이 "6\n,ㄷ,ㅌ\n,M,N,O" 처럼 여러 줄로 나왔다).
            // 정규화된 label을 외부 서버로 보내기 전에 여기서 반드시 1차 마스킹한다(SPEC §2).
            // 길이 제한보다 먼저 마스킹해야 잘린 개인정보 조각이 정규식 매칭을 피하지 않는다.
            // 정책은 [Ctx.masker]가 들고 있다 — debug 비교 실험(#203)에서만 우회할 수 있고,
            // 그때도 아래 정규화와 길이 제한은 그대로 적용된다.
            label = maskedLabel,
            role = role ?: roleOf(node),
            // 페이지 dot은 clickable=true지만 약 50x50px이라 시니어가 조준할 수 없다.
            // 탭 대상이 아니라 **정보원**으로만 쓴다(HANDOFF §3-3)는 결정을 여기서 못박는다.
            clickable = clickable ?: node.isClickable,
            obscured = obscured,
            editable = node.isEditable,
            focused = node.isFocused,
            // 여기부터가 "지금 어떤 상태인가"다. clickable/editable은 뷰의 설정값이라 이걸 대신하지
            // 못한다 — 비활성 버튼도 clickable=true로 오고, 스위치를 켜도 label이 그대로다(#271).
            enabled = node.isEnabled,
            checkState = checkState ?: checkStateOf(node),
            selected = node.isSelected,
            showingHintText = hintTextState(
                editable = node.isEditable,
                showingHintText = node.isShowingHintText,
            ),
            bounds = listOf(r.left, r.top, r.right, r.bottom),
            canScrollForward = scroll?.forward,
            canScrollBackward = scroll?.backward,
            scrollAxis = scroll?.axis?.wire,
            nextSwipeDirection = scroll?.axis?.next,
            prevSwipeDirection = scroll?.axis?.prev,
            signatureLabel = maskedSignatureLabel,
            signatureTracksEditableValue = signatureTracksEditableValue,
            targetAnchorLabel = maskedTargetAnchorLabel,
        )
    }

    private fun normalizeAndMaskLabel(label: String, masker: LabelMasker): String =
        masker.mask(label.replace(WHITESPACE, " ").trim()).take(MAX_LABEL_CHARS)

    /**
     * 스와이프 방향은 **화면이 이동하는 방향**, 즉 사용자가 **보러 가는 쪽**이다(C17, SPEC §4).
     * 손가락이 움직이는 방향이 아니다 — 목록 아래를 더 보려면 세로축의 next 는 down이고,
     * 손가락은 그 반대로 올라간다.
     *
     * **서버로 나가는 입력 메타도 서버가 답으로 주는 [GuideAction.direction]과 같은 기준이어야
     * 한다.** 한 프롬프트에 반대 규약 둘이 들어가면 모델이 기준을 섞어 #109를 재현한다.
     * 손가락으로의 변환은 그리는 경계에서 한 번만 한다([GestureMotion.fingerDirectionFor]).
     */
    internal enum class Axis(
        val wire: ScrollAxis,
        val next: SwipeDirection,
        val prev: SwipeDirection,
    ) {
        VERTICAL(ScrollAxis.VERTICAL, SwipeDirection.DOWN, SwipeDirection.UP),
        HORIZONTAL(ScrollAxis.HORIZONTAL, SwipeDirection.RIGHT, SwipeDirection.LEFT),
    }

    private class ScrollState(val forward: Boolean, val backward: Boolean, val axis: Axis)

    /**
     * 스크롤 상태. 모델에게 "지식" 대신 "상태"를 주기 위한 것이다(HANDOFF §3).
     * 스크롤 끝을 몰라 엉뚱한 방향을 안내하던 문제가 이 값들로 풀린다.
     */
    private fun hasScrollActions(node: AccessibilityNodeInfo): Boolean =
        node.actionList.any {
            it.id == AccessibilityAction.ACTION_SCROLL_FORWARD.id ||
                it.id == AccessibilityAction.ACTION_SCROLL_BACKWARD.id
        }

    /**
     * 이 노드 **자신이** 무언가를 그리는가. [hasPaintedContent]가 서브트리를 훑을 때 쓰는 잎 판정이다.
     *
     * 안 보이는 노드는 자기 신호를 세지 않는다 — 자식은 계속 보되(가시성은 트리 아래로 단조롭지
     * 않다) **안 보이는 노드 자신의** clickable·label로 덮개를 인정하지는 않는다는 뜻이다.
     * 이 규칙이 이 PR이 새로 세운 것이라 순수 함수로 뽑아 고정한다(#358).
     */
    internal fun paintsOwnContent(
        visibleToUser: Boolean,
        clickable: Boolean,
        hasOwnLabel: Boolean,
    ): Boolean = visibleToUser && (clickable || hasOwnLabel)

    /**
     * 안 보이는 노드가 [cap]겹 넘게 이어졌는가. 넘으면 그 자리에서 서브트리를 끊는다(#367).
     *
     * Android 타입 없이 고정하려고 순수 함수로 뺀다 — 이 파일의 [paintsOwnContent]·[resolveAxis]와
     * 같은 자리다. 경계가 테스트에 없으면 `>=`로 잘못 써도 초록이고, 그 한 칸이 #358이 되찾은
     * 요소를 통째로 지운다.
     */
    internal fun exceedsHiddenChain(run: Int, cap: Int = MAX_HIDDEN_RUN): Boolean = run > cap

    /** 보이는 노드를 순회하며 한 번 본 `true`가 뒤의 일반 노드 때문에 사라지지 않게 한다. */
    internal fun observeScrollable(
        seen: Boolean,
        isScrollable: Boolean,
        hasScrollActions: Boolean,
    ): Boolean = seen || isScrollable || hasScrollActions

    /**
     * IME 창은 존재 여부만 관측한다. 키보드 root·키 label은 직렬화하지 않는다.
     * null/빈 목록은 interactive window를 못 얻은 상태일 수 있으므로 "숨김"으로 단정하지 않는다.
     */
    internal fun imeVisibility(windowTypes: Iterable<Int>?): Boolean? {
        val observed = windowTypes?.toList()?.takeIf { it.isNotEmpty() } ?: return null
        return observed.any { it == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
    }

    private fun scrollStateOf(node: AccessibilityNodeInfo, bounds: Rect): ScrollState? {
        val actions = node.actionList.mapTo(HashSet()) { it.id }
        val forward = AccessibilityAction.ACTION_SCROLL_FORWARD.id in actions
        val backward = AccessibilityAction.ACTION_SCROLL_BACKWARD.id in actions
        if (!forward && !backward) return null

        val horizontal = AccessibilityAction.ACTION_SCROLL_LEFT.id in actions ||
            AccessibilityAction.ACTION_SCROLL_RIGHT.id in actions
        val vertical = AccessibilityAction.ACTION_SCROLL_UP.id in actions ||
            AccessibilityAction.ACTION_SCROLL_DOWN.id in actions

        return ScrollState(
            forward,
            backward,
            resolveAxis(horizontal, vertical, bounds.width(), bounds.height()),
        )
    }

    /** 목킹 없이 테스트하려고 Android 타입 없이 받는다. */
    internal fun resolveAxis(
        horizontal: Boolean,
        vertical: Boolean,
        width: Int,
        height: Int,
    ): Axis = when {
        horizontal && !vertical -> Axis.HORIZONTAL
        vertical && !horizontal -> Axis.VERTICAL
        // 방향 액션을 노출하지 않는 뷰가 많다. 그럴 때만 모양으로 추정한다
        // (가로로 넘기는 것은 대개 화면만큼 넓고 낮다).
        width > height -> Axis.HORIZONTAL
        else -> Axis.VERTICAL
    }

    /** 자기가 들고 있는 전송 label과 기기 내부 시그니처 투영. */
    private fun ownText(node: AccessibilityNodeInfo): TextProjection? {
        if (node.isEditable) {
            val hint = node.hintText
            val name = supplementalDescriptionOf(node)
            val sensitive = isSensitiveInputIdentity(
                hintText = hint,
                name = name,
                contentDescription = node.contentDescription,
                resourceId = node.viewIdResourceName,
            )
            return editableTextProjection(
                text = node.text,
                contentDescription = node.contentDescription,
                hintText = hint,
                name = name,
                showingHintText = node.isShowingHintText,
                password = node.isPassword,
                sensitiveIdentity = sensitive,
                limitedSelection = !sensitive &&
                    node.isVisibleToUser &&
                    node.isEnabled &&
                    hasDirectManipulationAction(node) &&
                    hasNearbyAdjustableAncestor(node),
            )
        }
        return staticLabel(
            text = node.text,
            contentDescription = node.contentDescription,
            supplementalDescription = supplementalDescriptionOf(node),
        )?.let { TextProjection(it, it) }
    }

    /**
     * 관계로 붙은 이름이 사는 칸(#381). Chromium이 `<label for>`·`<label>`로 감싼 이름을
     * **API 36부터 여기로 보낸다** — 그 전에는 같은 값이 `text`(라디오)·`hintText`(입력칸)로 왔다.
     *
     * **API 36 미만에서는 값 자체가 없어 항상 `null`이다.** 그래서 이 폴백은 구버전 동작을
     * 한 톨도 바꾸지 않는다(2026-08-22 Android 14 에뮬레이터 대조, #291).
     */
    // lint는 게이트가 [supplementalDescriptionFor] 안에 있어 **람다 너머를 못 본다.** 런타임은
    // 안전하다 — 람다는 `sdkInt >= BAKLAVA`일 때만 호출되고 그 성질을 단위 테스트가 잡는다.
    // 게이트를 이 자리로 인라인하면 lint는 조용해지지만 경계가 테스트에서 빠진다(PR #384 2R).
    @SuppressLint("NewApi")
    private fun supplementalDescriptionOf(node: AccessibilityNodeInfo): CharSequence? =
        supplementalDescriptionFor(Build.VERSION.SDK_INT) { node.supplementalDescription }

    /**
     * 위 게이트를 Android 타입 없이 고정하기 위한 순수 함수.
     *
     * **게이트 자체에 테스트가 없으면 `>= 35`로 잘못 써도 초록이다**(PR #384 리뷰).
     * 이 파일의 `resolveAxis`·`hoistedCheckState`와 같은 컨벤션이다.
     *
     * **[value]가 람다인 것이 이 함수의 요점이다.** 값으로 받으면 코틀린이 인자를 먼저 평가해
     * `getSupplementalDescription()`이 게이트 **밖에서** 호출되고, API 36 미만에서는 그 메서드가
     * 없어 `NoSuchMethodError`로 죽는다. `walk`가 노드마다 부르므로 첫 직렬화에서 즉시다
     * (PR #384 2R 리뷰에서 실제로 그렇게 넣었다가 잡혔다). **값 인자로 되돌리지 말 것.**
     */
    internal fun supplementalDescriptionFor(
        sdkInt: Int,
        value: () -> CharSequence?,
    ): CharSequence? = if (sdkInt >= Build.VERSION_CODES.BAKLAVA) value() else null

    /** 이 노드 자신의 체크 상태. 자손은 보지 않는다. */
    // lint는 게이트가 [checkStateFor] 안에 있어 람다 너머를 못 본다. 새 API 람다는
    // `sdkInt >= BAKLAVA`일 때만 호출되고 그 경계를 단위 테스트가 고정한다. 게이트를 이 자리로
    // 인라인하면 lint는 조용해지지만 API 경계가 테스트에서 빠진다.
    @SuppressLint("NewApi")
    internal fun checkStateOf(node: AccessibilityNodeInfo): CheckState =
        checkStateFor(
            sdkInt = Build.VERSION.SDK_INT,
            checkable = node.isCheckable,
            modernState = { node.checked },
            legacyChecked = { legacyChecked(node) },
        )

    /** API 36 미만 호환 경계. 새 API 경로에서는 호출되지 않는다. */
    @Suppress("DEPRECATION")
    private fun legacyChecked(node: AccessibilityNodeInfo): Boolean = node.isChecked

    /**
     * API 게이트와 non-checkable 단락을 Android 노드 없이 고정하기 위한 순수 경계.
     *
     * 두 상태 판독기를 람다로 받는 이유는 [supplementalDescriptionFor]와 같다. 값으로 받으면
     * API 36 미만에서도 `getChecked()`가 인자 평가 중 먼저 호출돼 `NoSuchMethodError`가 난다.
     */
    internal fun checkStateFor(
        sdkInt: Int,
        checkable: Boolean,
        modernState: () -> Int,
        legacyChecked: () -> Boolean,
    ): CheckState {
        if (!checkable) return CheckState.UNAVAILABLE
        return if (sdkInt >= Build.VERSION_CODES.BAKLAVA) {
            checkState(modernState())
        } else {
            if (legacyChecked()) CheckState.CHECKED else CheckState.UNCHECKED
        }
    }

    /**
     * Android 16 `AccessibilityNodeInfo.getChecked()`의 세 결과를 wire 상태로 옮긴다.
     *
     * 플랫폼이 현재 정의한 셋 밖의 값은 읽을 수 없는 미래 상태이므로 fail-closed로
     * [CheckState.UNAVAILABLE]에 접는다. 그 경우 자손 상태 끌어올림과 `checkable_count`에서도
     * 미관측으로 취급하는 것이 의도다. Android가 새 상수를 추가하면 이 결정과 매핑을 함께
     * 다시 검토한다.
     */
    internal fun checkState(platformState: Int): CheckState = when (platformState) {
        AccessibilityNodeInfo.CHECKED_STATE_TRUE -> CheckState.CHECKED
        AccessibilityNodeInfo.CHECKED_STATE_FALSE -> CheckState.UNCHECKED
        AccessibilityNodeInfo.CHECKED_STATE_PARTIAL -> CheckState.PARTIAL
        else -> CheckState.UNAVAILABLE
    }

    /**
     * 라벨을 흡수한 노드의 켬/끔. 자기 것이 있으면 그것이고, 없으면 흡수한 자손에서 찾는다.
     *
     * 행 전체가 clickable인 설정 목록에서 스위치 자식이 clickable이 아니면, 그 자식은 흡수돼
     * 별도 요소로 나가지 않는다. 그때 상태를 행으로 올리지 않으면 화면에서 켬/끔이 사라진다.
     */
    private fun mergedCheckState(node: AccessibilityNodeInfo): CheckState =
        hoistedCheckState(checkStateOf(node), descendantCheckState(node, 0))

    /** 위 병합 규칙을 Android 타입 없이 고정하기 위한 순수 함수. 자기 것이 언제나 이긴다. */
    internal fun hoistedCheckState(own: CheckState, descendant: CheckState): CheckState =
        own.takeUnless { it == CheckState.UNAVAILABLE } ?: descendant

    /**
     * 라벨 병합과 같은 깊이로 자손을 훑되, **clickable 자손은 자기 요소로 나갈 때만** 건너뛴다.
     *
     * 라벨은 그 노드가 자기 label로 가져가니 건너뛰는 것이 맞다. 그런데 **이름이 없어 요소로
     * 나가지 못하는 clickable 노드**는 아무도 그 상태를 가져가지 않는다 — 건너뛰면 켬/끔이
     * 화면에서 통째로 사라진다.
     *
     * 실측(#288 리뷰, SM-S921N / One UI 7.0 개발자 옵션): 표준 `SwitchPreference` 행이
     * `LinearLayout(clickable) → [RelativeLayout → TextView 둘, LinearLayout → Switch]` 모양이고
     * 그 `Switch`는 `clickable=true checkable=true`인데 text·content-desc가 **둘 다 없다.**
     * 라벨은 TextView에서 행으로 합쳐지는데 상태만 갈 곳이 없었다.
     */
    private fun descendantCheckState(node: AccessibilityNodeInfo, depth: Int): CheckState {
        if (depth >= MAX_MERGE_DEPTH) return CheckState.UNAVAILABLE
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (!child.isVisibleToUser) continue
            if (child.isClickable && emitsOwnElement(mergedLabel(child), child.isEditable)) continue
            checkStateOf(child).takeUnless { it == CheckState.UNAVAILABLE }?.let { return it }
            if (child.isEditable) continue
            descendantCheckState(child, depth + 1)
                .takeUnless { it == CheckState.UNAVAILABLE }
                ?.let { return it }
        }
        return CheckState.UNAVAILABLE
    }

    /**
     * 그 노드가 자기 요소로 나가는가. 나가면 자기 상태를 들고 가므로 조상이 올릴 필요가 없고,
     * 못 나가면 조상이 올려야 상태가 남는다. [walk]의 방출 조건과 같은 규칙이다.
     */
    internal fun emitsOwnElement(label: String, editable: Boolean): Boolean =
        editable || label.isNotBlank()

    /** 입력칸이 아닌 요소에는 hint 개념이 없다. 위와 같은 이유로 `null`을 돌려준다. */
    internal fun hintTextState(editable: Boolean, showingHintText: Boolean): Boolean? =
        if (editable) showingHintText else null

    /**
     * 창 제목을 화면 이름으로 쓴다(#269).
     *
     * **label과 정확히 같은 취급이다** — 정규화 → 1차 마스킹 → 길이 제한. 대화방 제목은 사람
     * 이름이고 문서 뷰어는 파일명을 싣기 때문에, 마스킹을 건너뛸 근거가 없다.
     * 제목이 없는 창이 흔하므로(11번가·TMAP·플레이스토어 실측) `null`이 정상 값이다.
     */
    internal fun windowTitleOf(rawTitle: CharSequence?, masker: LabelMasker): String? {
        val normalized = rawTitle?.toString()?.replace(WHITESPACE, " ")?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        return masker.mask(normalized).take(MAX_LABEL_CHARS)
    }

    /**
     * Android 타입 없이 입력 라벨의 개인정보 경계를 테스트하기 위한 순수 함수.
     *
     * 이 함수는 현재 값이 아니라 고정 문맥만 만든다 — `hintText`는 placeholder, [name]은
     * `<label>`이 준 이름이다. 현재 값의 허용·제외는 [editableTextProjection]이 맡는다.
     *
     * 둘을 합치는 이유는 **구버전이 이미 합쳐서 주기 때문**이다 — API 35 이하에서는
     * `hint='방문자명 방문자명을 입력하세요'` 한 덩어리로 오고, API 36에서 이름만
     * `supplementalDescription`으로 갈라졌다(#291 대조). 같은 화면이 버전에 따라 다른 label을
     * 내지 않게 여기서 되붙인다.
     */
    internal fun editableLabel(hintText: CharSequence?, name: CharSequence? = null): String? {
        val hint = hintText?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val label = name?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        if (label == null) return hint
        if (hint == null || hint == label) return label
        // 이름이 hint 앞에 이미 붙어 있으면 두 번 싣지 않는다.
        //
        // **구버전이 여기 오지는 않는다**(PR #384 리뷰) — 구버전은 [name]이 항상 `null`이라
        // 위에서 이미 반환된다. 이 줄이 실제로 막는 것은 **API 36인데 앱이 이름을 hint에도 같이
        // 실은 경우**다(구 WebView + 신 플랫폼 조합).
        //
        // 공백까지 봐야 한다 — `startsWith(label)`만으로는 `'방문자명'`이 `'방문자명을 입력하세요'`에
        // 걸려 이름이 조용히 안 붙는다(한국어 조사가 붙는 형태라 흔하다).
        if (hint.startsWith("$label ")) return hint
        return "$label $hint"
    }

    /**
     * editable 값의 허용·제외·시그니처 투영을 Android 객체 없이 고정하는 단일 경계(#462).
     *
     * [label]에는 자유 입력값도 포함하지만 호출자가 일반 label과 같은 1차 마스킹을 적용한다.
     * 비밀번호 상태나 일반 의미 표지로 식별한 인증 코드는 값과 값이 섞였을 수 있는 접근성 이름을
     * 제외하고 안전한 일반 이름으로 바꾼다. 자유 입력은 [signatureLabel]에서 값만 고정해
     * 타이핑마다 재호출하지 않고,
     * NumberPicker 같은 제한 선택값은 실제 값을 남긴다.
     */
    internal fun editableTextProjection(
        text: CharSequence?,
        contentDescription: CharSequence?,
        hintText: CharSequence?,
        name: CharSequence? = null,
        showingHintText: Boolean,
        password: Boolean,
        sensitiveIdentity: Boolean,
        limitedSelection: Boolean,
    ): TextProjection? {
        val context = when {
            password -> PASSWORD_EDITABLE_LABEL
            sensitiveIdentity -> SENSITIVE_CODE_EDITABLE_LABEL
            else -> editableLabel(hintText, name)
        }
        val value = if (showingHintText || password || sensitiveIdentity) {
            null
        } else {
            sequenceOf(text, contentDescription)
                .firstNotNullOfOrNull { normalizedText(it) }
        }
        val label = combineEditableContextAndValue(context, value) ?: return null
        val tracksValue = limitedSelection && value != null
        val signature = if (tracksValue) label else context ?: EDITABLE_SIGNATURE_TOKEN
        return TextProjection(
            label = label,
            signatureLabel = signature,
            tracksEditableValue = tracksValue,
        )
    }

    private fun combineEditableContextAndValue(context: String?, value: String?): String? = when {
        context == null -> value
        value == null || value == context -> context
        value.startsWith(context) -> value
        else -> "$context $value"
    }

    internal fun isSensitiveInputIdentity(
        hintText: CharSequence?,
        name: CharSequence?,
        contentDescription: CharSequence?,
        resourceId: CharSequence?,
    ): Boolean = sequenceOf(hintText, name, contentDescription)
        .filterNotNull()
        .any(::isSensitiveTextIdentity) || isSensitiveResourceIdentity(resourceId)

    private fun isSensitiveTextIdentity(value: CharSequence): Boolean {
        val tokens = identityTokens(value)
        return matchesSensitiveIdentity(
            tokens,
            allowStandalonePin = UPPERCASE_PIN_TOKEN.containsMatchIn(value),
        )
    }

    private fun isSensitiveResourceIdentity(value: CharSequence?): Boolean {
        val identifier = value?.toString()?.substringAfterLast('/') ?: return false
        val tokens = identityTokens(identifier)
        if (matchesSensitiveIdentity(tokens, allowStandalonePin = false)) return true
        val pinIndex = tokens.indexOf("pin")
        if (pinIndex < 0) return false
        val inputContext = setOf("code", "number", "input", "field", "edit", "edittext")
        return tokens.getOrNull(pinIndex + 1) in inputContext
    }

    private fun matchesSensitiveIdentity(
        tokens: List<String>,
        allowStandalonePin: Boolean,
    ): Boolean {
        val compact = tokens.joinToString("")
        val sensitiveTokens = if (allowStandalonePin) {
            SENSITIVE_ENGLISH_IDENTITY_TOKENS
        } else {
            SENSITIVE_ENGLISH_NON_PIN_TOKENS
        }
        return tokens.any(sensitiveTokens::contains) ||
            SENSITIVE_ENGLISH_IDENTITIES.any(compact::contains) ||
            SENSITIVE_KOREAN_IDENTITIES.any(compact::contains)
    }

    private fun identityTokens(value: CharSequence): List<String> = normalizedText(value)
        ?.let(::splitIdentityWords)
        ?.split(' ')
        ?.filter(String::isNotEmpty)
        .orEmpty()

    /** resource-id의 구분자와 camel/acronym 경계를 공백으로 바꿔 `pin` 부분 문자열 오탐을 막는다. */
    private fun splitIdentityWords(value: String): String = buildString {
        value.forEachIndexed { index, char ->
            if (!char.isLetterOrDigit()) {
                if (isNotEmpty() && last() != ' ') append(' ')
                return@forEachIndexed
            }
            val previous = value.getOrNull(index - 1)
            val next = value.getOrNull(index + 1)
            val startsWord = char.isUpperCase() && (
                previous?.isLowerCase() == true ||
                    (previous?.isUpperCase() == true && next?.isLowerCase() == true)
                )
            if (startsWord && isNotEmpty() && last() != ' ') append(' ')
            append(char.lowercaseChar())
        }
    }.trim()

    private fun normalizedText(value: CharSequence?): String? =
        value?.toString()?.replace(WHITESPACE, " ")?.trim()?.takeIf(String::isNotEmpty)

    /**
     * 편집 불가 노드의 이름을 **우선순위대로** 고른다(#381).
     *
     * `text` → `contentDescription` → `supplementalDescription` 순이고 앞의 값이 있으면 뒤는 안 본다.
     *
     * **`labeledBy`는 넣지 않았다.** 이름이 다른 노드에 있는 또 하나의 경로이지만, 두 번의
     * 프로브 실측(웹뷰 · 설정 개발자 옵션)에서 값이 온 적이 **0건**이다. 반면 그 값을 읽으려면
     * 앞이 전부 빈 노드마다 IPC가 한 번씩 더 나가는데, **이름 없는 컨테이너는 트리에 흔하다** —
     * 관측된 이득 없이 순회 비용만 는다(#367). 표본이 나오면 그때 넣는다.
     */
    internal fun staticLabel(
        text: CharSequence?,
        contentDescription: CharSequence?,
        supplementalDescription: CharSequence?,
    ): String? = sequenceOf(text, contentDescription, supplementalDescription)
        .firstNotNullOfOrNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }

    /** transient 투영이 없던 요소의 타깃 앵커 호환을 위한 기존 NumberPicker 형식 판정. */
    internal fun isShortNumericControlLabel(label: CharSequence?): Boolean {
        val normalized = label?.toString()?.replace(WHITESPACE, " ")?.trim() ?: return false
        return ADJUSTABLE_NUMERIC_LABEL.matches(normalized)
    }

    /** 클릭/선택/값 조정처럼 사용자가 이 노드 자체를 직접 조작할 수 있는 action만 인정한다. */
    private fun hasDirectManipulationAction(node: AccessibilityNodeInfo): Boolean =
        node.actionList.any {
            it.id == AccessibilityAction.ACTION_CLICK.id ||
                it.id == AccessibilityAction.ACTION_LONG_CLICK.id ||
                it.id == AccessibilityAction.ACTION_SELECT.id ||
                it.id == AccessibilityAction.ACTION_SET_PROGRESS.id
        }

    /** 앱·resource-id가 아니라 가까운 접근성 구조만으로 NumberPicker 소속 여부를 확인한다. */
    private fun hasNearbyAdjustableAncestor(node: AccessibilityNodeInfo): Boolean {
        var ancestor = node.parent
        repeat(MAX_ADJUSTABLE_ANCESTOR_DEPTH) {
            val current = ancestor ?: return false
            val hasAdjustmentAction = current.actionList.any {
                it.id == AccessibilityAction.ACTION_SCROLL_FORWARD.id ||
                    it.id == AccessibilityAction.ACTION_SCROLL_BACKWARD.id ||
                    it.id == AccessibilityAction.ACTION_SCROLL_UP.id ||
                    it.id == AccessibilityAction.ACTION_SCROLL_DOWN.id ||
                    it.id == AccessibilityAction.ACTION_SCROLL_LEFT.id ||
                    it.id == AccessibilityAction.ACTION_SCROLL_RIGHT.id ||
                    it.id == AccessibilityAction.ACTION_SET_PROGRESS.id
            }
            if (
                isAdjustableControlAncestor(
                    visibleToUser = current.isVisibleToUser,
                    enabled = current.isEnabled,
                    role = roleOf(current),
                    scrollable = current.isScrollable,
                    hasAdjustmentAction = hasAdjustmentAction,
                )
            ) {
                return true
            }
            ancestor = current.parent
        }
        return false
    }

    internal fun isAdjustableControlAncestor(
        visibleToUser: Boolean,
        enabled: Boolean,
        role: String,
        scrollable: Boolean,
        hasAdjustmentAction: Boolean,
    ): Boolean = visibleToUser && enabled &&
        role.endsWith("NumberPicker") &&
        (scrollable || hasAdjustmentAction)

    /** editable 값이 구현별 자손 노드에서 마스킹·민감 입력 판정을 우회하지 않게 하는 순회 경계. */
    internal fun shouldInspectDescendants(editable: Boolean): Boolean = !editable

    /**
     * 자손 텍스트를 합쳐도 되는 노드인가.
     *
     * 스크롤 컨테이너와 화면 대부분을 덮는 큰 노드는 제외한다(HANDOFF §3-9).
     */
    private fun shouldMergeDescendants(node: AccessibilityNodeInfo, screenArea: Long): Boolean {
        if (node.isScrollable) return false
        val r = boundsOf(node)
        val ratio = (r.width().toLong() * r.height().toLong()).toDouble() / screenArea
        return ratio < CONTAINER_AREA_RATIO
    }

    private fun mergedText(node: AccessibilityNodeInfo): TextProjection {
        ownText(node)?.let { return it }
        val labels = mutableListOf<String>()
        val signatures = mutableListOf<String>()
        val trackedValues = mutableListOf<Boolean>()
        val targetAnchors = mutableListOf<String>()
        collectDescendantText(node, labels, signatures, trackedValues, targetAnchors, 0)
        return TextProjection(
            labels.joinToString(" "),
            signatures.joinToString(" "),
            trackedValues.any { it },
            targetAnchors.joinToString(" "),
        )
    }

    private fun mergedLabel(node: AccessibilityNodeInfo): String = mergedText(node).label

    private fun collectDescendantText(
        node: AccessibilityNodeInfo,
        out: MutableList<String>,
        signatureOut: MutableList<String>,
        trackedValues: MutableList<Boolean>,
        targetAnchorOut: MutableList<String>,
        depth: Int,
    ) {
        if (depth >= MAX_MERGE_DEPTH || out.size >= MAX_LABEL_PARTS) return
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (!child.isVisibleToUser) continue
            // 중첩된 클릭 노드의 글자는 그 노드가 자기 라벨로 가져간다.
            if (child.isClickable) continue
            ownText(child)?.let {
                if (out.size < MAX_LABEL_PARTS) {
                    out.add(it.label)
                    signatureOut.add(it.signatureLabel)
                    trackedValues.add(it.tracksEditableValue)
                    targetAnchorOut.add(
                        it.targetAnchorLabel ?: if (it.tracksEditableValue) {
                            it.signatureLabel
                        } else {
                            ScreenSignature.normalizeTargetAnchorLabel(it.signatureLabel)
                        },
                    )
                }
            }
            // 구현별 자손이 editable 값의 마스킹·민감 입력 판정을 우회하지 않게 더 내려가지 않는다.
            if (child.isEditable) continue
            collectDescendantText(
                child,
                out,
                signatureOut,
                trackedValues,
                targetAnchorOut,
                depth + 1,
            )
        }
    }

    /** `android.widget.Button` -> `Button`. 모델에게 주는 힌트일 뿐이다. */
    private fun roleOf(node: AccessibilityNodeInfo): String =
        node.className?.toString()?.substringAfterLast('.').orEmpty()

    private fun boundsOf(node: AccessibilityNodeInfo): Rect =
        Rect().also { node.getBoundsInScreen(it) }
}
