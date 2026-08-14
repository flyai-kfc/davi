package com.davi.helper

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
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

    private var overlay: OverlayController? = null
    private var guideApiClient: GuideApiClient? = null
    private var e2ePublisher: GuideE2ePublisher? = null

    /** 직전 샘플에서 고른 활성 창의 영역. 화면 전체가 아니면 다이얼로그로 보고 안내창이 피한다(#38 4번). */
    private var activeWindowBounds: Rect? = null

    /** 도그푸딩 화이트리스트용 해시(#41). debug에서만 값이 있고 release는 항상 null이다. */
    private var dogfoodDeviceId: String? = null
    private val apiExecutor = Executors.newSingleThreadExecutor()
    private val requestInFlight = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val logicalRequests = LogicalRequestTracker()
    private var lastLoggedScreenSignature: String? = null
    private var rootUnavailable = false

    private val stability = ScreenStabilityTracker()

    /** 무엇이 보여야 하는지의 판정. 재표시·정체 접미사 규칙이 여기 있다(PR #63 리뷰 P1). */
    private val display = GuideDisplayState()

    /** 직전 샘플의 판정. null이면 세션 시작 후 첫 샘플 전이다([ReadingBorder.isReading]). */
    private var lastDecision: ScreenStabilityTracker.Decision? = null

    /** 이벤트가 없어도 도는 샘플러. 세션이 있는 동안에만 스스로 다시 예약한다(#39). */
    private val sampleRunnable = Runnable { sampleScreen() }

    /**
     * 세션이 끝나는 순간 화면에서 지운다. 다음 접근성 이벤트를 기다리면, 사용자가 중지를 눌러도
     * 화면이 가만히 있는 동안에는 강조가 그대로 남는다.
     *
     * 시작에는 샘플러를 깨운다. 사용자가 대상 앱으로 이동할 때까지 기다리지 않아도 되고,
     * 이전 세션의 화면 기억을 끌고 가지 않는다.
     */
    private val sessionListener = GuideSession.Listener { goal ->
        mainHandler.post {
            stability.reset()
            logicalRequests.reset()
            lastLoggedScreenSignature = null
            rootUnavailable = false
            display.reset()
            lastDecision = null
            if (goal == null) {
                mainHandler.removeCallbacks(sampleRunnable)
                overlay?.clear()
            } else {
                scheduleSample(SETTLE_MS)
            }
            // 시작이면 첫 샘플까지의 공백(SETTLE_MS)부터 읽는 중으로 덮는다.
            updateReading()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // 칩 클릭은 메인 스레드다(GuideSession의 스레드 계약 충족). 세션이 끝나면 위 리스너가
        // 오버레이를 걷는데, 그 경로가 mainHandler.post라 클릭 디스패치 안에서 자기 창을 걷지 않는다.
        // 로그를 남기는 이유: 사용자가 끝낸 세션은 그 외에 아무 흔적도 안 남아, 로그만 보면
        // "안내가 그냥 멈췄다"와 구별이 안 된다.
        overlay = OverlayController(
            this,
            object : OverlayController.Callbacks {
                override fun onStop() {
                    Log.i(TAG, "session stopped by chip")
                    GuideSession.stop()
                }

                override fun onConfirmAccept() {
                    // 앱을 앞으로 가져오지 않는다 — 방금 끝낸 작업 화면에 그대로 남는 것이
                    // "스스로 해냈다"는 경험에 맞다(2026-08-14 결정).
                    Log.i(TAG, "confirm accepted, session done")
                    GuideSession.confirmAccepted()
                }

                override fun onConfirmReject() {
                    Log.i(TAG, "confirm rejected, continuing")
                    GuideSession.confirmRejected()
                    // 확인 화면을 **버린다**(onCleared가 아니라 reset). 기억만 해 두면 다음 샘플의
                    // redisplay가 같은 화면이라는 이유로 확인쌍을 곧바로 되살린다 — 사용자가 방금
                    // 아니라고 답한 질문이 다시 뜨는 셈이다.
                    overlay?.clear()
                    display.reset()
                    // 그 화면을 '안 보낸 것'으로 되돌려 다음 샘플이 다시 묻게 한다(RETRY와 같은 경로).
                    stability.markFailed()
                    updateReading()
                }
            },
        )
        e2ePublisher = GuideE2ePublisher(this)
        guideApiClient = GuideApiClient(
            BuildConfig.SERVER_URL,
            devAccessToken = BuildConfig.DEV_ACCESS_TOKEN,
        )
        // 옵트인 빌드만 해시를 계산한다 — 서버 화이트리스트는 저장만 거를 뿐 전송은 여기서만
        // 막을 수 있다(#77 리뷰). null이면 로그에 '-'가 찍히고 요청 필드도 통째로 빠진다.
        dogfoodDeviceId = if (BuildConfig.DOGFOOD_OPT_IN) {
            DogfoodDeviceId.hash(
                Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID),
            )
        } else {
            null
        }
        GuideSession.addListener(sessionListener)
        // debug E2E 제어 receiver가 접근성 callback보다 먼저 세션을 시작한 경합도 복구한다.
        // 보통은 listener가 시작 이벤트를 받지만, 시스템의 Bound 표시는 callback보다 먼저 바뀔 수 있다.
        if (GuideSession.isActive) scheduleSample(SETTLE_MS)
        // dogfoodDeviceId는 화이트리스트 등록의 유일한 추출 경로다(DogfoodDeviceId 참고).
        Log.i(
            TAG,
            "connected server=${BuildConfig.SERVER_URL} " +
                "overlayPermission=${overlay?.canDraw()} " +
                "dogfoodDeviceId=${dogfoodDeviceId ?: "-"}",
        )
    }

    /**
     * 이벤트는 이제 "지금 볼 만하다"는 신호일 뿐이다. 판단은 샘플러가 한다.
     *
     * 이벤트에만 기대면 목록 끝처럼 **이벤트가 아예 안 오는 상황**에서 안내가 멈춘다
     * (`HANDOFF.md` §3-4 실측, SPEC §4). 반대로 전환 직후에는 이벤트가 몰아쳐서
     * 덜 그려진 트리가 그대로 나간다(#32). 그래서 이벤트는 샘플을 **미루면서** 예약한다 —
     * 연속된 이벤트는 한 번으로 합쳐지고, 마지막 이벤트 뒤 [SETTLE_MS]가 지나야 샘플을 뜬다.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !isInteresting(event.eventType)) return

        // 세션이 없으면 화면을 읽지 않는다. 직렬화 이전에 끊는 것이 중요하다 —
        // 사용자가 안내를 요청하지 않은 동안에는 화면 내용이 프로세스 안에서도 만들어지지 않는다.
        if (GuideSession.active() == null) return

        scheduleSample(SETTLE_MS)
    }

    private fun scheduleSample(delayMs: Long) {
        mainHandler.removeCallbacks(sampleRunnable)
        mainHandler.postDelayed(sampleRunnable, delayMs)
    }

    /**
     * 화면을 한 번 떠서 [ScreenStabilityTracker]에 묻고, 답에 따라 움직인 뒤 다음 샘플을 예약한다.
     *
     * 메인 스레드 전용이다(접근성 콜백과 같은 스레드). 트래커 상태를 여기서만 만지므로 잠금이 필요 없다.
     */
    private fun sampleScreen() {
        val session = GuideSession.active() ?: return

        val resolved = ActiveWindowResolver.resolve(this)
        activeWindowBounds = resolved?.bounds
        val root = resolved?.root
        if (root == null) {
            // 우리 앱이 앞에 있거나 읽을 창이 없다. 남은 강조를 지우고 계속 지켜본다.
            // 내용은 display가 기억한다 — 유효 화면으로 돌아오면 아래 redisplay가 되살린다.
            if (!rootUnavailable) {
                rootUnavailable = true
                GuideTelemetry.rootState("unavailable")
            }
            overlay?.clear()
            display.onCleared()
            // 아래에 그릴 대상이 없다. 읽는 중도 함께 내린다 — clear()는 안내만 지운다.
            lastDecision = null
            updateReading()
            scheduleSample(POLL_MS)
            return
        }
        if (rootUnavailable) {
            rootUnavailable = false
            GuideTelemetry.rootState("recovered")
        }

        // IME는 창 type만 읽고 키보드 root나 키 label은 직렬화하지 않는다(#112).
        // 빈 목록은 "IME 없음"이 아니라 interactive window 관측 실패일 수 있으므로 미관측으로 둔다.
        val windowTypes = runCatching { windows.map { it.type } }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
        val result = runCatching { UiTreeSerializer.serialize(root, windowTypes) }.getOrElse {
            Log.e(TAG, "serialize failed", it)
            scheduleSample(POLL_MS)
            return
        }

        val signature = ScreenSignature.of(result.screen)
        val now = SystemClock.uptimeMillis()
        val decision = stability.onSample(signature, now)
        lastDecision = decision
        updateReading()

        // 지워진 안내의 회복 경로(PR #63 리뷰 P1). 상한 안내는 '안내 중지' 버튼이 우리 앱에만
        // 있어서, 문구를 따르러 앱을 여는 것만으로 위 root=null 청소에 지워졌다 — 중지 없이
        // 작업 화면으로 돌아오면(화면 불변 → IDLE만 반복) 되살릴 길이 없었다.
        // 같은 화면(서명 일치)에서만 되살리고, 다른 화면이면 폐기해 새 요청에 맡긴다(2차 리뷰 P1).
        // 단 **안정 판정 뒤에만** — 과도기(WAIT) 샘플의 서명으로 폐기하면 #32의 덜 그려진
        // 트리 한 번에 보존 안내를 잃는다(4차 리뷰 P1).
        display.redisplay(signature, decision != ScreenStabilityTracker.Decision.WAIT)?.let {
            Log.i(TAG, "redisplay guide")
            draw(it)
        }
        when (decision) {
            ScreenStabilityTracker.Decision.WAIT -> {
                // 억제한 샘플이 로그에 없으면 "덜 그려진 트리를 안 보냈다"를 실기기에서 볼 수 없다(#32).
                Log.d(TAG, "settling pkg=${result.screen.packageName} elements=${result.screen.elements.size}")
                scheduleSample(SETTLE_MS)
            }

            ScreenStabilityTracker.Decision.SEND -> {
                // 확인 대기 중에는 묻지 않는다(#139). 폴링은 그대로 돌아야 위 redisplay가
                // 사라진 확인 화면을 되살릴 수 있어서, 멈추는 것은 요청뿐이다.
                if (GuideSession.awaitingConfirm) {
                    scheduleSample(POLL_MS)
                    return
                }
                if (lastLoggedScreenSignature != signature) {
                    lastLoggedScreenSignature = signature
                    GuideTelemetry.screenStable(result)
                    // raw 상세 로그의 debug/release 분리는 #58. #81은 같은 화면 retry의 반복만 막는다.
                    if (e2ePublisher?.suppressDetailedScreenLogs != true) logScreenDetails(result)
                }
                // 실제로 나간 요청만 "보냈다"로 기록한다. 보낸 셈 치면 in-flight 가드에 막힌
                // 화면의 안내가 영영 오지 않는다(PR #61 리뷰 P1).
                if (requestNextStep(result.screen, signature, session)) {
                    stability.markSent(signature, now)
                }
                scheduleSample(POLL_MS)
            }

            ScreenStabilityTracker.Decision.STUCK -> {
                // 확인 대기 중 화면이 안 바뀌는 것은 사용자가 답을 고르는 동안 당연하다.
                // 알리지 않는 데 그치지 않고 건너뛰는 이유는, stuckContent가 같은 내용으로
                // 다시 그리면서 우리 창을 만져 스스로를 깨우기 때문이다(ControlOverlay 참고).
                if (!GuideSession.awaitingConfirm) {
                    Log.i(TAG, "screen stuck for ${ScreenStabilityTracker.STUCK_AFTER_MS}ms")
                    showStuckNotice()
                }
                scheduleSample(POLL_MS)
            }

            ScreenStabilityTracker.Decision.IDLE -> scheduleSample(POLL_MS)
        }
    }

    /**
     * 안내한 대로 했는데 화면이 그대로일 때. **서버를 다시 부르지 않는다** — 폴링이 곧 호출량이고
     * 무료 티어는 15 RPM이다(#39). 강조와 원래 안내는 그대로 두고 한 줄만 덧붙인다.
     * 동작을 새로 시키는 문장이 아니라서 "한 instruction에 두 동작" 금지(`HANDOFF.md` §3-14)에 걸리지 않는다.
     * 상한 안내에는 접미사를 붙이지 않고 원문을 유지한다 — 덮어쓰지도, 잃지도 않는다(PR #63 리뷰 P1).
     *
     * 최종 문구는 안내 상태 표시(#29)에서 다시 정한다.
     */
    private fun showStuckNotice() {
        display.stuckContent(getString(R.string.guide_stuck))?.let { draw(it) }
    }

    /**
     * 읽는 중 테두리를 지금 상태에 맞춘다(GD-1). 판정은 [ReadingBorder]에 있고 여기서는
     * 값을 모아 넘기기만 한다. 멱등이라 같은 값으로 반복 호출해도 안전하다.
     */
    private fun updateReading() {
        overlay?.setReading(
            ReadingBorder.isReading(
                // 유무만 필요하다. active()는 잠금을 잡고 이력 리스트 두 개를 복사하는데,
                // 그 잠금은 네트워크 스레드의 기록 경로와 공유한다.
                sessionActive = GuideSession.isActive,
                screenReadable = !rootUnavailable,
                decision = lastDecision,
                requestInFlight = requestInFlight.get(),
                // 확인 대기 중에는 같은 자리를 성공 tone 테두리가 쓴다(#139).
                awaitingConfirm = GuideSession.awaitingConfirm,
            ),
        )
    }

    /** 판정된 표시 내용을 그린다. bounds 변환만 여기서 한다 — 상태는 Android 타입을 모른다. */
    private fun draw(content: GuideDisplayState.Content) {
        val rect = content.bounds?.takeIf { it.size == 4 }?.let { Rect(it[0], it[1], it[2], it[3]) }
        overlay?.showGuide(rect, content.text, avoidRegions(content.actionType), content.confirm)
    }

    /**
     * 안내창이 가리지 말아야 할 영역(#38 4·5번). 창 목록은 서비스만 볼 수 있어 여기서 모은다.
     * 메인 스레드에서만 불린다 — 응답 경로도 [mainHandler]를 거친다.
     *
     * 등급은 "가리면 지금 안내한 조작이 불가능해지는가"로 가른다. 타이핑 안내의 키보드는
     * 조작 대상 자체라 절대 회피고, 무관하게 떠 있는 키보드와 다이얼로그 본문은 최선 노력이다.
     */
    private fun avoidRegions(actionType: ActionType?): List<GuideBoxLayout.Avoid> {
        val regions = mutableListOf<GuideBoxLayout.Avoid>()

        // IME 창은 focused·active가 둘 다 false로 온다(2026-08-12 SM-S921N 실측) — type으로 찾는다.
        runCatching { windows }.getOrNull().orEmpty()
            .firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            ?.let { ime -> Rect().also { ime.getBoundsInScreen(it) } }
            ?.takeIf { !it.isEmpty }
            ?.let { regions += avoid(it, hard = actionType == ActionType.TYPE) }

        // 다이얼로그는 따로 찾지 않는다 — 뜨는 순간 그게 곧 활성 창이다(ActiveWindowResolver.isPartial).
        activeWindowBounds
            ?.takeIf {
                ActiveWindowResolver.isPartial(
                    windowArea = it.width().toLong() * it.height().toLong(),
                    displayArea = ActiveWindowResolver.displayArea(this),
                )
            }
            ?.let { regions += avoid(it, hard = false) }

        return regions
    }

    private fun avoid(rect: Rect, hard: Boolean) = GuideBoxLayout.Avoid(
        bounds = GuideBoxLayout.Bounds(
            left = rect.left.toFloat(),
            top = rect.top.toFloat(),
            right = rect.right.toFloat(),
            bottom = rect.bottom.toFloat(),
        ),
        hard = hard,
    )

    /** @return 요청이 실제로 시작됐는가. 시작하지 못했으면 호출자가 "보냈다"고 기록하면 안 된다. */
    private fun requestNextStep(
        screen: Screen,
        signature: String,
        session: GuideSession.Active,
    ): Boolean {
        if (!requestInFlight.compareAndSet(false, true)) return false
        val client = guideApiClient ?: run {
            requestInFlight.set(false)
            return false
        }
        val logicalRequest = logicalRequests.next(signature, session.generation)

        apiExecutor.execute {
            val startedAt = SystemClock.elapsedRealtime()
            GuideTelemetry.requestStarted(logicalRequest)
            try {
                val request = NextStepRequest(
                    goal = session.goal,
                    screen = screen,
                    // 화이트리스트 판정은 서버가 한다. debug가 아니면 null이라 안 실린다(#41).
                    deviceId = dogfoodDeviceId,
                    // 직전에 무엇을 안내했는지 모르면 모델이 같은 말을 반복한다(HANDOFF §3-10).
                    recentInstructions = session.recentInstructions,
                    // 문장과 별도로 실제 행동 의미를 보내 서버의 무상태 반복 가드가 사용한다.
                    recentActions = session.recentActions,
                    clientVersion = BuildConfig.VERSION_NAME,
                )
                when (val result = client.nextStep(request, logicalRequest)) {
                    is NextStepResult.Success -> {
                        val action = result.response.action
                        GuideTelemetry.requestFinished(
                            logicalRequest,
                            outcome = "success",
                            durationMs = SystemClock.elapsedRealtime() - startedAt,
                            statusCode = 200,
                            serverRequestId = result.trace.requestId,
                        )
                        Log.i(
                            TAG,
                            "action=${action.actionType.name.lowercase()} " +
                                "target=${action.targetNodeId ?: "-"} " +
                                // scroll은 방향이 곧 안내다. 로그에 없으면 방향이 맞았는지
                                // 실기기에서 확인할 길이 없다(#37).
                                "direction=${action.direction?.name?.lowercase() ?: "-"} " +
                                "history=${session.recentInstructions.size}",
                        )
                        mainHandler.post {
                            logicalRequests.markHttpResponse(logicalRequest, 200)
                            applyAction(request, signature, result.response, session, logicalRequest)
                        }
                    }

                    is NextStepResult.ApiFailure -> {
                        logicalRequests.markHttpResponse(logicalRequest, result.statusCode)
                        GuideTelemetry.requestFinished(
                            logicalRequest,
                            outcome = "api_error",
                            durationMs = SystemClock.elapsedRealtime() - startedAt,
                            statusCode = result.statusCode,
                            errorKind = result.error.kind.name,
                            serverRequestId = result.trace.requestId,
                        )
                        retryThisScreen()
                    }

                    is NextStepResult.HttpFailure -> {
                        logicalRequests.markHttpResponse(logicalRequest, result.statusCode)
                        GuideTelemetry.requestFinished(
                            logicalRequest,
                            outcome = "http_error",
                            durationMs = SystemClock.elapsedRealtime() - startedAt,
                            statusCode = result.statusCode,
                            serverRequestId = result.trace.requestId,
                        )
                        retryThisScreen()
                    }

                    is NextStepResult.NetworkFailure -> {
                        GuideTelemetry.requestFinished(
                            logicalRequest,
                            outcome = "network_error",
                            durationMs = SystemClock.elapsedRealtime() - startedAt,
                            errorKind = "NETWORK",
                            exceptionClass = result.exception.javaClass.simpleName,
                        )
                        retryThisScreen()
                    }

                    is NextStepResult.ParseFailure -> {
                        // trace가 있으면 서버의 성공 응답까지 받은 뒤 body 계약 파싱이 실패한 것이다.
                        // #82가 같은 ID의 결과를 캐시해도 불량 200을 되받지 않도록 논리 요청을 닫는다.
                        if (result.trace != null) logicalRequests.markHttpResponse(logicalRequest, 200)
                        GuideTelemetry.requestFinished(
                            logicalRequest,
                            outcome = "response_parse_error",
                            durationMs = SystemClock.elapsedRealtime() - startedAt,
                            errorKind = "PARSE",
                            serverRequestId = result.trace?.requestId,
                            exceptionClass = result.exception.javaClass.simpleName,
                        )
                        retryThisScreen()
                    }
                }
            } finally {
                requestInFlight.set(false)
                // 요청이 도는 동안 화면이 바뀌었을 수 있다. 다음 폴링까지 기다리지 않고 바로 다시 뜬다.
                mainHandler.post {
                    stability.markFinished()
                    // 안내가 방금 떴다(또는 실패했다). 다음 샘플까지 기다리면 안내와 테두리가
                    // 겹쳐 남는다. 단 **이전 세션의 늦은 응답**이면 새 세션의 "첫 샘플 전"
                    // 상태를 덮어 시작 공백의 표시를 지운다 — applyAction과 같은 세대 가드를 둔다.
                    if (GuideSession.isCurrent(session.generation)) {
                        lastDecision = ScreenStabilityTracker.Decision.IDLE
                        updateReading()
                    }
                    scheduleSample(SETTLE_MS)
                }
            }
        }
        return true
    }

    /**
     * 요청이 실패한 화면은 "안 보낸 것"으로 되돌려 다음 샘플이 다시 시도하게 한다.
     * 이벤트 기반일 때는 다음 이벤트가 재시도를 해줬지만, 이제 화면이 그대로면 이벤트도 오지 않는다.
     */
    private fun retryThisScreen() {
        mainHandler.post {
            stability.markFailed()
            Log.i(TAG, "retry backoff=${stability.backoffMs()}ms")
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
        request: NextStepRequest,
        requestSignature: String,
        response: NextStepResponse,
        session: GuideSession.Active,
        logicalRequest: LogicalRequestAttempt,
    ) {
        val screen = request.screen
        if (!GuideSession.isCurrent(session.generation)) {
            GuideTelemetry.responseDropped(logicalRequest, "session_changed")
            return
        }

        // 전송은 성공했으니 백오프는 푼다. 화면이 바뀌었는지는 그다음 문제다.
        stability.markSucceeded()

        // 세대만 보면 같은 세션 안에서 A 화면의 안내가 B 화면 위에 붙는다(PR #61 리뷰 P1).
        // 버리면 다음 샘플이 B를 새로 물어본다.
        val current = stability.currentSignature()
        if (current != null && current != requestSignature) {
            GuideTelemetry.responseDropped(logicalRequest, "screen_changed")
            return
        }

        val action = response.action
        if (action.actionType == ActionType.DONE) {
            e2ePublisher?.displayed(
                generation = session.generation,
                requestSignature = requestSignature,
                request = request,
                response = response,
                target = null,
            )
            when (GuideSession.onDone(session.generation, response.verified, response.screenSignature)) {
                // 검증된 완료도 여기서 끝내지 않는다(#139) — 성공 오탐을 사용자에게 한 번 묻는다.
                GuideSession.DoneOutcome.CONFIRM_CONFIDENT -> {
                    Log.i(TAG, "done verified, asking user")
                    draw(display.showConfirm(requestSignature, getString(R.string.overlay_confirm_confident)))
                }

                GuideSession.DoneOutcome.CONFIRM_UNSURE -> {
                    // 상한에서도 done으로 끝내지 않는다 — 막힘이 성공으로 보인다. 세션을 살려 두고
                    // 종료 결정을 사용자에게 넘긴다(가르치기, SPEC §2). 확신 있는 경로와 같은
                    // 화면이고 문구만 다르다. 문구 확정은 #29.
                    Log.w(TAG, "done unverified x${GuideSession.MAX_UNVERIFIED_DONE}, asking user")
                    draw(display.showConfirm(requestSignature, getString(R.string.overlay_confirm_unsure)))
                }

                GuideSession.DoneOutcome.NOTICE_ONLY -> {
                    // 사용자가 이미 아니라고 답한 화면인데 완료 신호가 상한까지 반복됐다.
                    // 같은 질문을 다시 하지 않으면서 재질의도 멈춘다 — 문구만 남기고 칩으로 넘긴다.
                    Log.w(TAG, "done after reject x${GuideSession.MAX_UNVERIFIED_DONE}, notice only")
                    draw(display.showCapNotice(requestSignature, getString(R.string.guide_done_unconfirmed)))
                }

                GuideSession.DoneOutcome.RETRY -> {
                    // 완료 검증(#54)이 화면 근거를 못 찾은 done이다. 조기 종료일 수 있으니 세션을
                    // 두고, 보낸 적 없는 화면으로 되돌려 백오프 뒤 같은 화면을 다시 묻는다(#55).
                    // 이게 없으면 화면이 그대로인 한 다음 요청이 영영 없다 — 무시가 곧 정지다.
                    // 이 백오프는 사실상 2초 고정이다 — 위 markSucceeded()가 failures를 매번
                    // 리셋한다. 상한 3회로 총량이 묶여 있어 수용한다(PR #63 리뷰 관찰).
                    // "unverified"라고 쓰지 않는다 — 사용자가 부정한 화면에서는 검증된 done도
                    // 이 분기로 온다(실기기 로그에서 실제로 헷갈렸다).
                    stability.markFailed()
                    Log.i(TAG, "done not trusted, retry backoff=${stability.backoffMs()}ms")
                }

                null -> GuideTelemetry.responseDropped(logicalRequest, "session_changed")
            }
            return
        }

        GuideSession.recordInstruction(session.generation, action.instruction)
        GuideSession.recordAction(session.generation, response.screenSignature, action)

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
        // 응답은 위 서명 대조로 requestSignature 화면에만 반영되므로, 안내도 그 화면 소속이다.
        draw(display.show(requestSignature, bounds, action.instruction, action.actionType))
        e2ePublisher?.displayed(
            generation = session.generation,
            requestSignature = requestSignature,
            request = request,
            response = response,
            target = target,
        )
    }

    private fun logScreenDetails(result: UiTreeSerializer.Result) {
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
                "clickable=$clickableCount " +
                "editable=${screen.elements.count { it.editable }} " +
                "focused=${screen.elements.count { it.focused }} " +
                "ime=${screen.imeVisible ?: "?"} " +
                "scrollable=${screen.hasScrollableElement ?: "?"}$page" +
                if (result.truncated) " (truncated)" else "",
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

    private fun isInteresting(eventType: Int): Boolean = isInterestingEvent(eventType)

    override fun onInterrupt() {
        // 시스템이 접근성 피드백 중단을 요청해도 서비스·세션은 계속 살 수 있다. root=null 경로와
        // 같이 표시 상태도 함께 내려야 다음 유효 샘플이 재표시한다 — clear만 하면 display가
        // "떠 있음"으로 남아 활성 세션의 오버레이가 영구히 빈다(PR #63 리뷰 3차 P1).
        Log.w(TAG, "interrupted")
        overlay?.clear()
        display.onCleared()
        lastDecision = null
        overlay?.setReading(false)
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        // 서비스가 내려가면 안내할 수단이 없다. 세션을 남겨두면 화면에는 "안내 중"인데
        // 아무 일도 일어나지 않는 상태가 된다.
        mainHandler.removeCallbacks(sampleRunnable)
        GuideSession.removeListener(sessionListener)
        GuideSession.stop()
        // 창을 걷지 않으면 서비스가 꺼진 뒤에도 유령 오버레이가 화면에 남는다.
        overlay?.destroy()
        overlay = null
        guideApiClient = null
        e2ePublisher = null
        apiExecutor.shutdownNow()
        return super.onUnbind(intent)
    }

    companion object {
        const val TAG = "GuideSvc"

        /**
         * 이벤트가 몰아친 뒤 이만큼 조용해야 화면을 뜬다. 전환 애니메이션 중에는 샘플이 계속
         * 달라지므로 이 값이 곧 "안정 판정 간격"이 된다(#32).
         */
        private const val SETTLE_MS = 350L

        /**
         * 안정된 뒤의 감시 주기. **폴링이 곧 호출량은 아니다** — 화면이 그대로면 서버를 부르지 않고
         * 트리만 다시 뜬다(#39). HANDOFF의 첫 추정치와 같은 값이며 근거를 갖고 다시 정할 것.
         */
        private const val POLL_MS = 700L

        private const val MAX_LOGGED_ELEMENTS = 30

        /** 포커스·IME 창 이벤트도 같은 settle 예약 경로로 합친다(#112). */
        internal fun isInterestingEvent(eventType: Int): Boolean =
            eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
                eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
                eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED
    }
}
