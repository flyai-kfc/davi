package com.davi.helper

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * 닫힌 루프의 본체. 기기측 책임만 여기 있다(app-android/CLAUDE.md):
 *  - 화면 트리 직렬화 + 1차 마스킹
 *  - 폴링/정체 감지(시간 기반)
 *  - 오버레이 렌더링, 서버가 준 "다음 안내 하나" 실행(안내만, 대신 조작 금지)
 *
 * 추론(프롬프트·모델·완료 검증)은 서버에 있다. 이 서비스는 상태를 모아 서버에 보내고
 * 받은 안내를 그린다. 로그 태그는 GuideSvc로 통일한다(디버깅 1순위, CLAUDE.md).
 *
 * 지금 구현된 것: 활성 화면 루트 선택 + 최소 직렬화 + 로그 출력 (이슈 #4).
 * TODO: 1차 마스킹(#8), 서버 호출(#5), 오버레이(#6), 스크롤 메타·페이지 dot, 정체 감지.
 */
class GuideAccessibilityService : AccessibilityService() {

    private var lastRunAtMs = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "connected server=${BuildConfig.SERVER_URL}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !isInteresting(event.eventType)) return

        // 접근성 이벤트는 초당 수십 번 온다. 디바운스하지 않으면 로그를 읽을 수 없다.
        val now = SystemClock.uptimeMillis()
        if (now - lastRunAtMs < DEBOUNCE_MS) return
        lastRunAtMs = now

        val root = ActiveWindowResolver.resolve(this)
        if (root == null) {
            Log.w(TAG, "active root=null")
            return
        }

        val result = runCatching { UiTreeSerializer.serialize(root) }.getOrElse {
            Log.e(TAG, "serialize failed", it)
            return
        }

        logScreen(result)
    }

    private fun logScreen(result: UiTreeSerializer.Result) {
        val screen = result.screen
        val clickableCount = screen.elements.count { it.clickable }
        Log.i(
            TAG,
            "screen pkg=${screen.packageName} elements=${screen.elements.size} " +
                "clickable=$clickableCount" + if (result.truncated) " (truncated)" else "",
        )
        screen.elements.take(MAX_LOGGED_ELEMENTS).forEach { e ->
            Log.d(
                TAG,
                "  ${e.nodeId} ${if (e.clickable) "tap" else "text"} " +
                    "role=${e.role} bounds=${e.bounds} label='${e.label}'",
            )
        }
        if (screen.elements.size > MAX_LOGGED_ELEMENTS) {
            Log.d(TAG, "  ... ${screen.elements.size - MAX_LOGGED_ELEMENTS} more")
        }
    }

    private fun isInteresting(eventType: Int): Boolean =
        eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED

    override fun onInterrupt() {
        Log.w(TAG, "interrupted")
    }

    companion object {
        const val TAG = "GuideSvc"

        /** HANDOFF의 첫 추정치와 같은 값. 실사용하며 근거를 갖고 다시 정할 것. */
        private const val DEBOUNCE_MS = 700L

        private const val MAX_LOGGED_ELEMENTS = 30
    }
}
