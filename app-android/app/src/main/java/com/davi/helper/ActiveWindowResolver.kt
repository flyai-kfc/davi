package com.davi.helper

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * "지금 사용자가 보는 화면"의 루트 노드를 고른다.
 *
 * **`isActive`로 고르지 않는다.** 셰이드가 열리면 systemui가 active가 되어 잘못 잡힌다(HANDOFF §7).
 * 대신 **패키지와 크기**로 고른다.
 *
 * 셰이드(systemui)는 **걸러내지 않는다.** 한때 필터링했다가 "셰이드 뒤의 홈 화면"을 잘못 안내한
 * 전례가 있고, 지금은 셰이드 트리를 읽어 빠른 설정 안내를 되살렸다(HANDOFF §2).
 * 펼쳐진 셰이드는 화면을 덮으니 크기로 이기고, 접힌 상태표시줄은 작아서 자연히 진다.
 *
 * 걸러내는 것은 **우리 앱뿐**이다. 우리가 띄운 오버레이/온보딩을 화면으로 착각하면 안 된다.
 */
object ActiveWindowResolver {

    /** 이보다 작은 창은 상태표시줄·토스트 따위로 보고 후보에서 뺀다(화면 넓이 대비 비율). */
    private const val MIN_AREA_RATIO = 0.15

    fun resolve(service: AccessibilityService): AccessibilityNodeInfo? {
        val ownPackage = service.packageName

        val candidates = runCatching { service.windows }.getOrNull().orEmpty()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION ||
                it.type == AccessibilityWindowInfo.TYPE_SYSTEM }
            .mapNotNull { window -> window.root?.let { window to it } }
            .filter { (_, root) -> root.packageName?.toString() != ownPackage }

        if (candidates.isEmpty()) {
            // windows를 못 읽는 상황(권한/타이밍)에서의 폴백.
            return service.rootInActiveWindow
        }

        val screenArea = candidates.maxOf { (window, _) -> area(window) }.coerceAtLeast(1L)

        return candidates
            .filter { (window, _) -> area(window).toDouble() / screenArea >= MIN_AREA_RATIO }
            .maxByOrNull { (window, _) -> area(window) }
            ?.second
            ?: service.rootInActiveWindow
    }

    private fun area(window: AccessibilityWindowInfo): Long {
        val r = Rect().also { window.getBoundsInScreen(it) }
        return r.width().toLong() * r.height().toLong()
    }
}
