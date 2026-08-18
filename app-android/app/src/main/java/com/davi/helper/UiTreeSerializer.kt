package com.davi.helper

import android.graphics.Rect
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
 * 관측하고 키보드의 키 노드나 입력 원문은 순회하지 않는다(#112).
 */
object UiTreeSerializer {

    /** 트리가 깊어도 이 이상은 안 내려간다(무한·초대형 트리 방어). */
    private const val MAX_DEPTH = 40

    /** 한 화면에서 만들 요소 수 상한. 초과분은 버리고 로그로 알린다. */
    private const val MAX_ELEMENTS = 200

    /** 라벨을 만들 때 자손을 몇 단계까지 훑을지. */
    private const val MAX_MERGE_DEPTH = 4

    /** 라벨에 이어붙일 텍스트 조각 수 상한. */
    private const val MAX_LABEL_PARTS = 6

    private const val MAX_LABEL_CHARS = 120

    /** NumberPicker 내부 구현이 wrapper를 두는 경우에도 가까운 최대 세 단계까지만 허용한다. */
    private const val MAX_ADJUSTABLE_ANCESTOR_DEPTH = 3

    private val WHITESPACE = Regex("\\s+")

    /** 시·분·날짜 picker에서 관측되는 "6, 시간", "00, 분" 같은 짧은 선택값만 허용한다. */
    private val ADJUSTABLE_NUMERIC_LABEL =
        Regex("""^\d{1,4}(?:\s*(?:[,،]\s*)?[\p{L}]{1,12})?$""")

    /**
     * 런처 페이지 인디케이터(dot)의 content-desc. 예: "6 페이지 중 2 페이지입니다."
     *
     * 삼성 런처는 홈·앱서랍 모두 `scrollable` 노드를 0개 노출하므로(HANDOFF §3-2)
     * **현재/전체 페이지를 알 수 있는 유일한 경로가 이것뿐이다**(§3-3).
     *
     * 한국어 문구에 묶여 있다. 다국어는 SPEC §11의 열린 결정이다.
     */
    private val PAGE_DOT_RE = Regex("""(\d+)\s*페이지\s*중\s*(\d+)\s*페이지""")

    private const val ROLE_PAGE_DOT = "page_dot"

    /** 직렬화 한 번 동안의 누적 상태. 파라미터가 늘어나는 것을 막는다. */
    private class Ctx(val screenArea: Long, val masker: LabelMasker) {
        val elements = mutableListOf<UiElement>()
        var counter = 0
        var currentPage: Int? = null
        var totalPages: Int? = null
        var hasScrollableElement = false
    }

    /**
     * 자기 넓이가 화면의 이 비율을 넘으면 "누를 수 있는 대상"이 아니라 컨테이너로 본다.
     * 이런 노드에는 자손 텍스트를 합치지 않는다.
     *
     * 삼성 런처는 홈·앱서랍 모두 `scrollable` 노드를 0개 노출하므로(HANDOFF §3-2)
     * `isScrollable`만으로는 워크스페이스를 걸러낼 수 없다. 그래서 넓이로 판단한다.
     */
    private const val CONTAINER_AREA_RATIO = 0.5

    data class Result(val screen: Screen, val truncated: Boolean)

    /**
     * @param masker 서버로 나갈 label에 적용할 1차 마스킹 정책. 기본값은 빌드 플래그가
     *               정하며 release 산출물에서는 항상 [PrivacyMaskingLabelMasker]다(#203).
     */
    fun serialize(
        root: AccessibilityNodeInfo,
        windowTypes: Iterable<Int>? = null,
        masker: LabelMasker = labelMaskerFor(BuildConfig.PRIVACY_MASKING_DISABLED),
    ): Result {
        val screenArea = boundsOf(root).let { it.width().toLong() * it.height().toLong() }
            .coerceAtLeast(1L)

        val ctx = Ctx(screenArea, masker)
        walk(root, ctx, absorbed = false, depth = 0)

        val truncated = ctx.elements.size > MAX_ELEMENTS
        return Result(
            screen = Screen(
                packageName = root.packageName?.toString().orEmpty(),
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
    ) {
        if (node == null || depth > MAX_DEPTH || ctx.elements.size > MAX_ELEMENTS) return
        if (!node.isVisibleToUser) return

        val hasScrollActions = hasScrollActions(node)
        ctx.hasScrollableElement = observeScrollable(
            seen = ctx.hasScrollableElement,
            isScrollable = node.isScrollable,
            hasScrollActions = hasScrollActions,
        )

        var childrenAbsorbed = absorbed

        // 일반 편집값은 계속 숨긴다. 다만 이 노드 자체가 NumberPicker의 직접 조작 파츠라는
        // 구조적 근거가 있을 때만 현재 숫자 선택값을 별도 요소로 보존한다.
        val ownTextValue = ownText(node, allowAdjustableNumericValue = true)
        val isPageDot = ownTextValue != null && parsePageDot(ownTextValue) != null
        var emitted = false

        if (isPageDot) {
            readPageDot(ownTextValue!!, node, ctx)
            ctx.elements.add(
                element(node, ownTextValue, ctx, role = ROLE_PAGE_DOT, clickable = false),
            )
            emitted = true
        } else if (node.isClickable || node.isEditable) {
            // 커스텀 입력 위젯은 사용자가 입력한 값을 비편집 자손에 복제하기도 한다.
            // 편집 노드는 hint만 쓰고 자손을 라벨에 합치지도, 아래에서 순회하지도 않는다.
            val inspectDescendants = shouldInspectDescendants(node.isEditable)
            val merge = inspectDescendants && shouldMergeDescendants(node, ctx.screenArea)
            val label = if (merge) mergedLabel(node) else ownTextValue.orEmpty()
            // 이름 없는 클릭 노드는 내보내지 않는다. 모델이 가리킬 수단이 없어 잡음만 된다
            // (실측: 전화 앱에서 통화 버튼을 감싼 빈 FrameLayout 두 개가 이렇게 잡혔다).
            // 이름이 없어도 편집 가능한 노드는 type 대상과 포커스 상태를 전달해야 한다.
            if (label.isNotBlank() || node.isEditable) {
                ctx.elements.add(element(node, label, ctx))
                emitted = true
                // 라벨로 흡수한 경우에만 자손의 글자를 중복으로 내보내지 않는다.
                childrenAbsorbed = merge
            }
        } else if (!absorbed) {
            if (ownTextValue != null) {
                ctx.elements.add(element(node, ownTextValue, ctx))
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
            ctx.elements.add(element(node, "", ctx))
        }

        if (!shouldInspectDescendants(node.isEditable)) return
        for (i in 0 until node.childCount) {
            walk(node.getChild(i), ctx, childrenAbsorbed, depth + 1)
        }
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
    ): UiElement {
        ctx.counter++
        val r = boundsOf(node)
        val scroll = scrollStateOf(node, r)
        return UiElement(
            nodeId = "n${ctx.counter}",
            // 줄바꿈·중복 공백을 한 칸으로 눌러 로그와 프롬프트를 읽을 수 있게 만든다
            // (실측: 키패드 라벨이 "6\n,ㄷ,ㅌ\n,M,N,O" 처럼 여러 줄로 나왔다).
            // 정규화된 label을 외부 서버로 보내기 전에 여기서 반드시 1차 마스킹한다(SPEC §2).
            // 길이 제한보다 먼저 마스킹해야 잘린 개인정보 조각이 정규식 매칭을 피하지 않는다.
            // 정책은 [Ctx.masker]가 들고 있다 — debug 비교 실험(#203)에서만 우회할 수 있고,
            // 그때도 아래 정규화와 길이 제한은 그대로 적용된다.
            label = ctx.masker.mask(label.replace(WHITESPACE, " ").trim())
                .take(MAX_LABEL_CHARS),
            role = role ?: roleOf(node),
            // 페이지 dot은 clickable=true지만 약 50x50px이라 시니어가 조준할 수 없다.
            // 탭 대상이 아니라 **정보원**으로만 쓴다(HANDOFF §3-3)는 결정을 여기서 못박는다.
            clickable = clickable ?: node.isClickable,
            editable = node.isEditable,
            focused = node.isFocused,
            bounds = listOf(r.left, r.top, r.right, r.bottom),
            canScrollForward = scroll?.forward,
            canScrollBackward = scroll?.backward,
            scrollAxis = scroll?.axis?.wire,
            nextSwipeDirection = scroll?.axis?.next,
            prevSwipeDirection = scroll?.axis?.prev,
        )
    }

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

    /**
     * 자기가 들고 있는 안전한 라벨.
     *
     * 편집 가능한 노드의 `text`와 contentDescription은 사용자가 입력한 원문을 되풀이할 수 있으므로
     * 기본적으로 읽지 않는다. 예외는 보이고 활성화된 비밀번호 아닌 노드가 직접 조작 action을
     * 제공하고, 가까운 조상이 조정 가능한 NumberPicker인 짧은 숫자 label뿐이다. 이 예외도
     * [allowAdjustableNumericValue]가 참인 자기 노드 직렬화에서만 적용해 조상 label로 합쳐지지 않는다.
     */
    private fun ownText(
        node: AccessibilityNodeInfo,
        allowAdjustableNumericValue: Boolean = false,
    ): String? {
        if (node.isEditable) {
            if (allowAdjustableNumericValue) {
                val rawLabel = node.text?.toString()?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: node.contentDescription?.toString()?.trim()
                adjustableNumericLabel(
                    rawLabel = rawLabel,
                    editable = node.isEditable,
                    visibleToUser = node.isVisibleToUser,
                    enabled = node.isEnabled,
                    password = node.isPassword,
                    hasDirectAction = hasDirectManipulationAction(node),
                    hasNearbyAdjustableAncestor = hasNearbyAdjustableAncestor(node),
                )?.let { return it }
            }
            return editableLabel(node.hintText)
        }
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrEmpty()) return text
        val desc = node.contentDescription?.toString()?.trim()
        if (!desc.isNullOrEmpty()) return desc
        return null
    }

    /** Android 타입 없이 입력 라벨의 개인정보 경계를 테스트하기 위한 순수 함수. */
    internal fun editableLabel(hintText: CharSequence?): String? {
        val hint = hintText?.toString()?.trim()
        return hint?.takeIf { it.isNotEmpty() }
    }

    /** Android 객체 없이 NumberPicker 값 공개 조건과 개인정보 경계를 테스트하기 위한 순수 함수. */
    internal fun adjustableNumericLabel(
        rawLabel: CharSequence?,
        editable: Boolean,
        visibleToUser: Boolean,
        enabled: Boolean,
        password: Boolean,
        hasDirectAction: Boolean,
        hasNearbyAdjustableAncestor: Boolean,
    ): String? {
        if (
            !editable || !visibleToUser || !enabled || password ||
            !hasDirectAction || !hasNearbyAdjustableAncestor
        ) {
            return null
        }
        val label = rawLabel?.toString()?.replace(WHITESPACE, " ")?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        return label.takeIf { isShortNumericControlLabel(it) }
    }

    /** 구조 검증 뒤 보존된 선택값을 화면 시그니처에서도 식별하기 위한 공통 형식 판정. */
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

    /** 입력 원문이 구현별 자손 노드로 새는 것을 막는 순회 경계. */
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

    private fun mergedLabel(node: AccessibilityNodeInfo): String {
        ownText(node)?.let { return it }
        val parts = mutableListOf<String>()
        collectDescendantText(node, parts, 0)
        return parts.joinToString(" ")
    }

    private fun collectDescendantText(
        node: AccessibilityNodeInfo,
        out: MutableList<String>,
        depth: Int,
    ) {
        if (depth >= MAX_MERGE_DEPTH || out.size >= MAX_LABEL_PARTS) return
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (!child.isVisibleToUser) continue
            // 중첩된 클릭 노드의 글자는 그 노드가 자기 라벨로 가져간다.
            if (child.isClickable) continue
            ownText(child)?.let { if (out.size < MAX_LABEL_PARTS) out.add(it) }
            // 편집 노드 아래의 구현별 자손에 입력 원문이 중복 노출될 수 있어 더 내려가지 않는다.
            if (child.isEditable) continue
            collectDescendantText(child, out, depth + 1)
        }
    }

    /** `android.widget.Button` -> `Button`. 모델에게 주는 힌트일 뿐이다. */
    private fun roleOf(node: AccessibilityNodeInfo): String =
        node.className?.toString()?.substringAfterLast('.').orEmpty()

    private fun boundsOf(node: AccessibilityNodeInfo): Rect =
        Rect().also { node.getBoundsInScreen(it) }
}
