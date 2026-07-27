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

    /** 이보다 작은 창은 상태표시줄·네비게이션바 따위로 보고 후보에서 뺀다(실제 화면 대비 비율). */
    private const val MIN_AREA_RATIO = 0.15

    fun resolve(service: AccessibilityService): AccessibilityNodeInfo? {
        val ownPackage = service.packageName

        val candidates = runCatching { service.windows }.getOrNull().orEmpty()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION ||
                it.type == AccessibilityWindowInfo.TYPE_SYSTEM }
            .mapNotNull { window -> window.root?.let { window to it } }
            .filter { (_, root) -> root.packageName?.toString() != ownPackage }

        // 기준은 **실제 디스플레이 크기**여야 한다. 후보 중 가장 큰 창을 기준으로 삼으면,
        // 앱 창의 root가 null이라 후보에서 빠졌을 때 남은 네비게이션바가 "화면 전체"로 둔갑해
        // 그걸 사용자 화면으로 잘못 고른다. (실측: 홈 화면에서 런처 root가 null이라 네비바 3개
        // 요소가 화면으로 잡혔다. HANDOFF §12 "홈 화면 dump는 종종 실패한다"와 같은 현상.)
        val displayArea = displayArea(service)

        return candidates
            .filter { (window, _) -> area(window).toDouble() / displayArea >= MIN_AREA_RATIO }
            .maxByOrNull { (window, _) -> area(window) }
            ?.second
            ?: fallbackRoot(service, ownPackage)
    }

    /**
     * 후보가 하나도 없을 때의 폴백. **여기서도 우리 앱은 걸러야 한다.**
     *
     * 실측: 온보딩 화면을 띄우면 후보가 전부 빠지고 폴백이 우리 앱을 돌려줘서,
     * 우리 UI가 "사용자가 보는 화면"으로 잡혔다. 안내할 대상이 없으면 null이 맞다.
     */
    private fun fallbackRoot(
        service: AccessibilityService,
        ownPackage: String?,
    ): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow ?: return null
        return if (root.packageName?.toString() == ownPackage) null else root
    }

    private fun displayArea(service: AccessibilityService): Long {
        val metrics = service.resources.displayMetrics
        return (metrics.widthPixels.toLong() * metrics.heightPixels.toLong()).coerceAtLeast(1L)
    }

    private fun area(window: AccessibilityWindowInfo): Long {
        val r = Rect().also { window.getBoundsInScreen(it) }
        return r.width().toLong() * r.height().toLong()
    }
}
