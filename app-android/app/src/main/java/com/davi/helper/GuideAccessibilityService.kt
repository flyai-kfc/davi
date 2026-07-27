package com.davi.helper

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 닫힌 루프의 본체. 기기측 책임만 여기 있다(app-android/CLAUDE.md):
 *  - 화면 트리 직렬화 + 1차 마스킹
 *  - 폴링/정체 감지(시간 기반)
 *  - 오버레이 렌더링, 서버가 준 "다음 안내 하나" 실행(안내만, 대신 조작 금지)
 *
 * 추론(프롬프트·모델·완료 검증)은 서버에 있다. 이 서비스는 상태를 모아 서버에 보내고
 * 받은 안내를 그린다. 로그 태그는 GuideSvc로 통일한다(디버깅 1순위, CLAUDE.md).
 *
 * 지금 구현된 것: 활성 화면 루트 선택 + 최소 직렬화 + 1차 마스킹 + 서버 호출·응답 로그(#4, #5, #8),
 * 서버가 고른 대상의 최소 오버레이 강조(#6).
 * TODO: 스크롤 메타·페이지 dot, 정체 감지.
 */
class GuideAccessibilityService : AccessibilityService() {

    private var lastRunAtMs = 0L
    private var overlay: OverlayController? = null
    private var guideApiClient: GuideApiClient? = null
    private val apiExecutor = Executors.newSingleThreadExecutor()
    private val requestInFlight = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        overlay = OverlayController(this)
        guideApiClient = GuideApiClient(BuildConfig.SERVER_URL)
        Log.i(
            TAG,
            "connected server=${BuildConfig.SERVER_URL} " +
                "overlayPermission=${overlay?.canDraw()}",
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !isInteresting(event.eventType)) return

        // 접근성 이벤트는 초당 수십 번 온다. 디바운스하지 않으면 로그를 읽을 수 없다.
        val now = SystemClock.uptimeMillis()
        if (now - lastRunAtMs < DEBOUNCE_MS) return
        lastRunAtMs = now

        val root = ActiveWindowResolver.resolve(this)
        if (root == null) {
            // 우리 앱이 앞에 있거나 읽을 창이 없다. 남은 강조를 지운다.
            Log.w(TAG, "active root=null")
            overlay?.clear()
            return
        }

        val result = runCatching { UiTreeSerializer.serialize(root) }.getOrElse {
            Log.e(TAG, "serialize failed", it)
            return
        }

        logScreen(result)
        requestNextStep(result.screen)
    }

    private fun requestNextStep(screen: Screen) {
        if (!requestInFlight.compareAndSet(false, true)) return
        val client = guideApiClient ?: run {
            requestInFlight.set(false)
            return
        }

        apiExecutor.execute {
            try {
                when (
                    val result = client.nextStep(
                        NextStepRequest(
                            goal = GOAL,
                            screen = screen,
                            clientVersion = BuildConfig.VERSION_NAME,
                        ),
                    )
                ) {
                    is NextStepResult.Success -> {
                        val action = result.response.action
                        Log.i(
                            TAG,
                            "action=${action.actionType.name.lowercase()} " +
                                "target=${action.targetNodeId ?: "-"} " +
                                "instruction=${action.instruction}",
                        )
                        mainHandler.post { highlightAction(screen, action) }
                    }

                    is NextStepResult.ApiFailure -> Log.w(
                        TAG,
                        "api_error kind=${result.error.kind.name} status=${result.statusCode}",
                    )

                    is NextStepResult.HttpFailure -> Log.w(TAG, "http_error status=${result.statusCode}")
                    is NextStepResult.NetworkFailure -> Log.w(TAG, "network_error", result.exception)
                    is NextStepResult.ParseFailure -> Log.w(TAG, "response_parse_error", result.exception)
                }
            } finally {
                requestInFlight.set(false)
            }
        }
    }

    /** 서버가 지정한 `target_node_id`만 강조한다. 앱은 어떤 UI 동작도 수행하지 않는다. */
    private fun highlightAction(screen: Screen, action: GuideAction) {
        val target = action.targetNodeId?.let { nodeId ->
            screen.elements.firstOrNull { it.nodeId == nodeId }
        }
        val bounds = target?.bounds
        if (target == null || bounds == null || bounds.size != 4) {
            overlay?.clear()
            return
        }
        Log.i(TAG, "highlight ${target.nodeId} bounds=$bounds")
        overlay?.highlight(Rect(bounds[0], bounds[1], bounds[2], bounds[3]))
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
        overlay?.clear()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        // 창을 걷지 않으면 서비스가 꺼진 뒤에도 유령 오버레이가 화면에 남는다.
        overlay?.destroy()
        overlay = null
        guideApiClient = null
        apiExecutor.shutdownNow()
        return super.onUnbind(intent)
    }

    companion object {
        const val TAG = "GuideSvc"

        /** HANDOFF의 첫 추정치와 같은 값. 실사용하며 근거를 갖고 다시 정할 것. */
        private const val DEBOUNCE_MS = 700L

        private const val MAX_LOGGED_ELEMENTS = 30
        private const val GOAL = "카카오톡 열기"
    }
}
