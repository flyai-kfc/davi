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
 * 스크롤 메타·페이지 dot(#22), 서버가 고른 대상의 강조와 안내문 표시 + 세션 시작·중지(#28).
 * TODO: 정체 감지, 오버레이 불가 화면의 헤드업 폴백.
 */
class GuideAccessibilityService : AccessibilityService() {

    private var lastRunAtMs = 0L
    private var overlay: OverlayController? = null
    private var guideApiClient: GuideApiClient? = null
    private val apiExecutor = Executors.newSingleThreadExecutor()
    private val requestInFlight = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 세션이 끝나는 순간 화면에서 지운다. 다음 접근성 이벤트를 기다리면, 사용자가 중지를 눌러도
     * 화면이 가만히 있는 동안에는 강조가 그대로 남는다.
     */
    private val sessionListener = GuideSession.Listener { goal ->
        if (goal == null) mainHandler.post { overlay?.clear() }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        overlay = OverlayController(this)
        guideApiClient = GuideApiClient(BuildConfig.SERVER_URL)
        GuideSession.addListener(sessionListener)
        Log.i(
            TAG,
            "connected server=${BuildConfig.SERVER_URL} " +
                "overlayPermission=${overlay?.canDraw()}",
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !isInteresting(event.eventType)) return

        // 세션이 없으면 화면을 읽지 않는다. 직렬화 이전에 끊는 것이 중요하다 —
        // 사용자가 안내를 요청하지 않은 동안에는 화면 내용이 프로세스 안에서도 만들어지지 않는다.
        val session = GuideSession.active() ?: return

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
        requestNextStep(result.screen, session)
    }

    private fun requestNextStep(screen: Screen, session: GuideSession.Active) {
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
                            goal = session.goal,
                            screen = screen,
                            // 직전에 무엇을 안내했는지 모르면 모델이 같은 말을 반복한다(HANDOFF §3-10).
                            recentInstructions = session.recentInstructions,
                            // 문장과 별도로 실제 행동 의미를 보내 서버의 무상태 반복 가드가 사용한다.
                            recentActions = session.recentActions,
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
                                "history=${session.recentInstructions.size} " +
                                "instruction=${action.instruction}",
                        )
                        mainHandler.post {
                            applyAction(
                                screen,
                                action,
                                result.response.screenSignature,
                                session,
                            )
                        }
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

    /**
     * 서버가 준 안내 하나를 화면에 반영한다. **앱은 어떤 UI 동작도 수행하지 않는다** — 강조와
     * 안내문만 그리고, 실제 조작은 사용자가 한다(SPEC §2).
     *
     * 메인 스레드에서만 부른다. 응답이 늦게 오는 사이 사용자가 중지했거나 다시 시작할 수 있으므로,
     * **요청을 보낸 세대가 아직 현재 세션일 때만** 반영한다. 목표 문자열 비교로는 부족하다 —
     * 중지 후 같은 목표로 다시 시작하면 문자열이 같아 이전 응답이 그대로 통과한다.
     */
    private fun applyAction(
        screen: Screen,
        action: GuideAction,
        screenSignature: String?,
        session: GuideSession.Active,
    ) {
        if (!GuideSession.isCurrent(session.generation)) {
            Log.i(TAG, "stale response dropped goal=${session.goal}")
            return
        }

        if (action.actionType == ActionType.DONE) {
            Log.i(TAG, "session done goal=${session.goal}")
            GuideSession.stop() // 리스너가 오버레이를 지운다
            return
        }

        GuideSession.recordInstruction(session.generation, action.instruction)
        GuideSession.recordAction(session.generation, screenSignature, action)

        val target = action.targetNodeId?.let { nodeId ->
            screen.elements.firstOrNull { it.nodeId == nodeId }
        }
        val bounds = target?.bounds?.takeIf { it.size == 4 }
        if (bounds == null) {
            // 화면에 없는 node_id를 고르는 것은 경량 모델의 전형적 실수다. 안내문은 그대로 띄우되
            // 로그로 남긴다 — 앱 검증이 logcat 수동인 동안 이게 이 실패를 볼 유일한 창이다.
            Log.w(TAG, "highlight miss target=${action.targetNodeId ?: "-"}")
        } else {
            Log.i(TAG, "highlight ${target?.nodeId} bounds=$bounds")
        }
        overlay?.showGuide(
            bounds?.let { Rect(it[0], it[1], it[2], it[3]) },
            action.instruction,
        )
    }

    private fun logScreen(result: UiTreeSerializer.Result) {
        val screen = result.screen
        val clickableCount = screen.elements.count { it.clickable }
        val page = if (screen.currentPage != null || screen.totalPages != null) {
            " page=${screen.currentPage ?: "?"}/${screen.totalPages ?: "?"}"
        } else {
            ""
        }
        Log.i(
            TAG,
            "screen pkg=${screen.packageName} elements=${screen.elements.size} " +
                "clickable=$clickableCount$page" + if (result.truncated) " (truncated)" else "",
        )
        screen.elements.take(MAX_LOGGED_ELEMENTS).forEach { e ->
            Log.d(
                TAG,
                "  ${e.nodeId} ${if (e.clickable) "tap" else "text"} " +
                    "role=${e.role} bounds=${e.bounds}${scrollInfo(e)} label='${e.label}'",
            )
        }
        if (screen.elements.size > MAX_LOGGED_ELEMENTS) {
            Log.d(TAG, "  ... ${screen.elements.size - MAX_LOGGED_ELEMENTS} more")
        }
    }

    /** 스크롤 가능한 요소에만 붙는다. 방향 안내가 맞는지 로그에서 바로 보려고 찍는다. */
    private fun scrollInfo(e: UiElement): String {
        if (e.canScrollForward == null && e.canScrollBackward == null) return ""
        val fwd = if (e.canScrollForward == true) "+${e.nextSwipeDirection?.name?.lowercase()}" else ""
        val back = if (e.canScrollBackward == true) "-${e.prevSwipeDirection?.name?.lowercase()}" else ""
        return " scroll=${e.scrollAxis?.name?.lowercase()}$fwd$back"
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
        // 서비스가 내려가면 안내할 수단이 없다. 세션을 남겨두면 화면에는 "안내 중"인데
        // 아무 일도 일어나지 않는 상태가 된다.
        GuideSession.removeListener(sessionListener)
        GuideSession.stop()
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
    }
}
