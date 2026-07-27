package com.davi.helper

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

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
 * 이번 이슈(#4)는 최소 필드만 채운다: node_id / label / role / clickable / bounds.
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

    /**
     * 자기 넓이가 화면의 이 비율을 넘으면 "누를 수 있는 대상"이 아니라 컨테이너로 본다.
     * 이런 노드에는 자손 텍스트를 합치지 않는다.
     *
     * 삼성 런처는 홈·앱서랍 모두 `scrollable` 노드를 0개 노출하므로(HANDOFF §3-2)
     * `isScrollable`만으로는 워크스페이스를 걸러낼 수 없다. 그래서 넓이로 판단한다.
     */
    private const val CONTAINER_AREA_RATIO = 0.5

    data class Result(val screen: Screen, val truncated: Boolean)

    fun serialize(root: AccessibilityNodeInfo): Result {
        val screenArea = boundsOf(root).let { it.width().toLong() * it.height().toLong() }
            .coerceAtLeast(1L)

        val elements = mutableListOf<UiElement>()
        val counter = intArrayOf(0)
        walk(root, elements, counter, screenArea, absorbed = false, depth = 0)

        val truncated = elements.size > MAX_ELEMENTS
        return Result(
            screen = Screen(
                packageName = root.packageName?.toString().orEmpty(),
                elements = if (truncated) elements.take(MAX_ELEMENTS) else elements,
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
        out: MutableList<UiElement>,
        counter: IntArray,
        screenArea: Long,
        absorbed: Boolean,
        depth: Int,
    ) {
        if (node == null || depth > MAX_DEPTH || out.size > MAX_ELEMENTS) return
        if (!node.isVisibleToUser) return

        var childrenAbsorbed = absorbed

        if (node.isClickable) {
            val merge = shouldMergeDescendants(node, screenArea)
            val label = if (merge) mergedLabel(node) else ownText(node).orEmpty()
            out.add(element(node, label, counter))
            // 라벨로 흡수한 경우에만 자손의 글자를 중복으로 내보내지 않는다.
            childrenAbsorbed = merge
        } else if (!absorbed) {
            ownText(node)?.let { out.add(element(node, it, counter)) }
        }

        for (i in 0 until node.childCount) {
            walk(node.getChild(i), out, counter, screenArea, childrenAbsorbed, depth + 1)
        }
    }

    private fun element(
        node: AccessibilityNodeInfo,
        label: String,
        counter: IntArray,
    ): UiElement {
        counter[0]++
        val r = boundsOf(node)
        return UiElement(
            nodeId = "n${counter[0]}",
            label = label.take(MAX_LABEL_CHARS),
            role = roleOf(node),
            clickable = node.isClickable,
            bounds = listOf(r.left, r.top, r.right, r.bottom),
        )
    }

    /** 자기가 들고 있는 글자. text 우선, 없으면 contentDescription. */
    private fun ownText(node: AccessibilityNodeInfo): String? {
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrEmpty()) return text
        val desc = node.contentDescription?.toString()?.trim()
        if (!desc.isNullOrEmpty()) return desc
        return null
    }

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
            collectDescendantText(child, out, depth + 1)
        }
    }

    /** `android.widget.Button` -> `Button`. 모델에게 주는 힌트일 뿐이다. */
    private fun roleOf(node: AccessibilityNodeInfo): String =
        node.className?.toString()?.substringAfterLast('.').orEmpty()

    private fun boundsOf(node: AccessibilityNodeInfo): Rect =
        Rect().also { node.getBoundsInScreen(it) }
}
