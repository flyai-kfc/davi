package com.davi.helper

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.os.PowerManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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
 */
class GuideAccessibilityService : AccessibilityService() {

    private var overlay: OverlayController? = null
    private var guideApiClient: GuideApiClient? = null
    private var authStore: AuthStore? = null
    private var e2ePublisher: GuideE2ePublisher? = null

    /**
     * 지금 안내 중인 대상의 label(#238). `source`가 회수된 순간에 눌림을 맞추는 유일한 축이다.
     * 1차 마스킹을 거친 값이라 원본 개인정보가 아니다.
     */
    private var guidedTargetLabel: String? = null

    /**
     * 그 안내가 나간 화면의 package(#319). 클릭 이벤트는 창 구분 없이 온다 — 우리 오버레이의
     * 중지 칩, IME 키, 셰이드, 권한 대화상자 전부 `TYPE_VIEW_CLICKED`를 낸다. 안내 앱이 아닌
     * 창의 클릭을 `performed`로 적으면 "다른 곳을 누름"이 사용자가 하지도 않은 조작을
     * 기록하게 되므로, **이 package의 이벤트만 판정에 들인다.**
     */
    private var guidedScreenPackage: String? = null

    /**
     * 안내 대상이 `clickable=true`였는가(#319). 비클릭 요소를 대상으로 안내하면(#311) 탭이
     * 클릭 가능한 조상에서 발화해 중심이 대상 밖일 수 있다 — 시킨 대로 눌렀는데
     * "다른 곳"이 되는 오탐이라, 그런 대상에서는 OFF_TARGET을 확신으로 쓰지 않는다.
     */
    private var guidedTargetClickable = false

    /**
     * 타깃 앵커(#380) — 지금 안내가 가리키는 슬롯의 node id와, 응답 시점의 정규화 label.
     * 구조가 같은 채 목록이 재정렬되면(새 메시지로 순서가 밀리는 대화방 목록) 강조가 엉뚱한
     * 줄을 가리키게 되는데, 그 순간 이 슬롯의 label이 달라지므로 조작 이벤트 없이도 재질의를
     * 연다. node id는 직렬화 순서 값이라 **구조가 같은 동안만** 같은 슬롯을 뜻한다 — 앵커를
     * 쓰는 자리도 정확히 그 경우뿐이다(구조가 다르면 어차피 구조 축이 재질의를 연다).
     */
    private var guidedTargetNodeId: String? = null
    private var guidedTargetStableLabel: String? = null

    /**
     * 앵커를 잡은 화면의 **구조 축**. node id는 구조가 같은 동안만 같은 슬롯을 뜻하므로
     * (`Screen.guidedSlotLabelChanged`), 구조가 다르면 앵커를 아예 보지 않는다.
     */
    private var guidedTargetStructure: String? = null

    /**
     * 직전 샘플의 화면 package(#380). 인과 게이트의 귀속 기준이다 — 이 package에서 난
     * 클릭만 [ScreenStabilityTracker.markInteraction]으로 넘긴다. 우리 오버레이의
     * 중지 칩과 IME 키 클릭이 게이트를 여는 것을 막는다([guidedScreenPackage]와 같은 이유).
     * 응답이 아직 없는 화면에서도 게이트가 서도록 응답이 아니라 샘플에서 채운다.
     */
    private var currentScreenPackage: String? = null

    /** 직전 샘플에서 고른 활성 창의 영역. 화면 전체가 아니면 다이얼로그로 보고 안내창이 피한다(#38 4번). */
    private var activeWindowBounds: Rect? = null

    /** 도그푸딩 화이트리스트용 해시(#41). debug에서만 값이 있고 release는 항상 null이다. */
    private var dogfoodDeviceId: String? = null
    private val apiExecutor = Executors.newSingleThreadExecutor()
    private val requestInFlight = AtomicBoolean(false)

    /** 직전에 보낸 읽는 중 여부. 전이일 때만 로그를 남긴다(폴링마다 찍으면 묻힌다). */
    private var lastReading: Boolean? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val logicalRequests = LogicalRequestTracker()
    private var lastLoggedScreenSignature: ScreenSig? = null
    private var rootUnavailable = false

    /**
     * [ScreenStabilityTracker]에 넣는 시각은 전부 같은 clock이어야 한다. uptime은 deep sleep을
     * 제외하므로 화면 샘플·상호작용·실패 완료 사이의 "깨어 있는 동안" 간격을 일관되게 잰다.
     * 요청 소요시간 telemetry만 deep sleep을 포함하는 elapsedRealtime을 별도로 쓴다.
     */
    private fun stabilityNowMs(): Long = SystemClock.uptimeMillis()

    /**
     * 화면이 꺼져 있는가. **꺼진 동안 오버레이를 걷고 샘플러를 재운다. 세션은 유지한다**(#56).
     *
     * 창 타입을 접근성 오버레이로 바꾸면서 우리 창이 키가드보다 **위** 층으로 올라갔다
     * (2026-08-18 SM-S921N `dumpsys window` 실측 — 우리 두 창이 #2·#3, `NavigationBar0` #5,
     * `Bouncer` #6, `NotificationShade` #9, `StatusBar` #10). 그대로 두면 잠금화면에 안내창과
     * 중지 칩이 뜨고 **칩이 눌린다**(실측). 피해는 둘이다 — ① 잠긴 기기에서 남이 칩을 눌러
     * 세션을 끝낼 수 있다 ② 안내문에는 화면 label 인용이 들어가는데 그것이 잠금화면에 뜬다.
     * (`SPEC.md` §2를 근거로 들지 않는다 — 그 절은 화면 데이터의 **외부 전송**을 다루고 잠긴
     * 기기에서의 기기 내 표시는 아직 저장소 어디에도 규칙이 없다. 원칙으로 올릴지는 docs 배치.)
     *
     * **잠금·인증 화면을 이유로 세션을 끝내지는 않는다** — 안내 도중 보안 인증이 뜨는 것은
     * 정상 흐름이고, 깨어나면 이어져야 한다. 그래서 기준은 키가드가 아니라 **화면 꺼짐**이고,
     * 되살리는 시점은 잠금이 풀린 뒤([Intent.ACTION_USER_PRESENT])다 — 화면만 켜진 잠금화면에서
     * 되살리면 같은 문제로 돌아간다.
     */
    private var screenOff = false

    /**
     * 화면이 꺼진 동안 응답을 버렸는가. 버리기만 하면 그 화면은 `sentSignature`가 찍힌 채로
     * 남아 **새 요청이 영영 안 나간다** — 재개할 때 [ScreenStability.markResumed]로 되돌린다.
     */
    private var droppedWhileScreenOff = false

    /**
     * 화면 꺼짐·잠금 해제를 듣는다. 접근성 이벤트로는 안 오는 신호다.
     *
     * 잠금이 아예 없는 기기에서도 깨어나는 즉시 이어지도록 [Intent.ACTION_SCREEN_ON]도 받아,
     * 키가드가 없으면 그 자리에서 되살린다.
     */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> pauseForScreenOff()
                Intent.ACTION_USER_PRESENT -> resumeAfterScreenOn()
                Intent.ACTION_SCREEN_ON ->
                    if (getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != true) {
                        resumeAfterScreenOn()
                    }
            }
        }
    }

    private val stability = ScreenStabilityTracker()

    /** 무엇이 보여야 하는지의 판정. 재표시·정체 접미사 규칙이 여기 있다(PR #63 리뷰 P1). */
    private val display = GuideDisplayState()

    /**
     * 오류를 언제 알릴지의 판정(#29). **연속으로 실패한 요청 수**를 원인 무관하게 센다.
     * [stability]의 `failures`와 일부러 따로 두는 이유는 [ErrorNoticeGate] KDoc에 있다.
     */
    private val errorNotice = ErrorNoticeGate()

    /** 직전 샘플의 판정. null이면 세션 시작 후 첫 샘플 전이다([ReadingBorder.isReading]). */
    private var lastDecision: ScreenStabilityTracker.Decision? = null

    /** 미루기의 기한을 들고 있다 — 폭주하는 화면에서 샘플이 영원히 밀리지 않게 한다(#380). */
    private val sampleSchedule = SampleSchedule()

    /** 이벤트가 없어도 도는 샘플러. 세션이 있는 동안에만 스스로 다시 예약한다(#39). */
    private val sampleRunnable = Runnable {
        // 예약이 소진됐다. 다음 예약은 기한을 새로 연다.
        sampleSchedule.reset()
        sampleScreen()
    }

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
            // 새 세션의 첫 예약은 기한을 새로 받는다 — 직전 세션이 열어 둔 기한이 그사이
            // 지났으면 settle 없이 곧바로 뜬다(#380).
            sampleSchedule.reset()
            lastLoggedScreenSignature = null
            rootUnavailable = false
            display.reset()
            lastReading = null
            guidedTargetLabel = null
            guidedScreenPackage = null
            guidedTargetClickable = false
            guidedTargetNodeId = null
            guidedTargetStableLabel = null
            guidedTargetStructure = null
            currentScreenPackage = null
            errorNotice.reset()
            lastDecision = null
            if (goal == null) {
                cancelSample()
                overlay?.clear()
            } else {
                // 안내가 다시 도는데 "멈췄어요" 알림이 남아 있으면 서로 어긋난다(#212).
                GuideNotifier.clear(this)
                scheduleSample(SETTLE_MS)
            }
            // 시작이면 첫 샘플까지의 공백(SETTLE_MS)부터 읽는 중으로 덮는다.
            updateReading()
        }
    }

    /**
     * **온보딩이 설정으로 보낸 사람을 여기서 되돌린다**(#451).
     *
     * 이 콜백은 사용자가 스위치를 켠 순간만이 아니라 부팅·재설치·안내 중 권한 회수 후
     * 복구에도 불린다. 아무 때나 다비가 튀어나오면 그게 더 나쁘므로,
     * [AccessibilityReturnStore]의 **일회용 표시**가 있을 때만 앞으로 나온다 — 그 표시는
     * 온보딩이 설정으로 보낼 때만 찍히고 여기서 소비되며 사라진다.
     *
     * **되는 것은 실측으로 확인했다**(SM-S918N · Android 15): `BAL_ALLOW_PERMISSION`으로
     * 백그라운드 액티비티 시작이 허용됐고 차단 로그는 없었다. 예외의 근거가 접근성 서비스로
     * 묶여 있다는 것 자체라 따로 받아 둘 권한이 없다. **그래도 던지는 것은 감싼다** —
     * 제조사·버전에 따라 막힐 수 있고, 그때 서비스가 죽으면 **접근성 자체가 안 붙어**
     * 고치려던 것보다 나쁜 상태가 된다.
     */
    private fun comeForwardIfOnboardingSentUs() {
        if (!AccessibilityReturnStore(this).consume()) return
        // **두 갈래가 서로 다른 이유로 맞는다**(#471 리뷰 5번·2차 지적).
        //
        // 안내 중 권한이 회수됐다 복구되는 갈래(#212)는 스택이 `[대화, 온보딩]`이라
        // **CLEAR_TOP이 실제로 위를 걷어낸다** — 뒤로가기가 「권한을 켜세요」로 안 돌아간다.
        //
        // **갓 설치한 사람은 스택이 `[온보딩]` 하나다.** 진입점은 `LoginActivity`지만 그 화면도
        // 사이에 끼는 `ConsentActivity`도 `startActivity` 다음 줄에서 스스로 `finish()`한다 —
        // 버튼을 누르는 시점에 남아 있는 것은 온보딩뿐이다(실측: `Hist #0`가 하나).
        // 어느 쪽이든 **대화 화면이 없어** CLEAR_TOP이 걷을 것이 없고 그냥 위에 쌓인다. 그 갈래가
        // 괜찮은 이유는 **설정이 이 작업 밖에 있기 때문**이다(온보딩이 `NEW_TASK`로 띄운다) —
        // 한때 설정이 이 작업 안에 쌓여 **뒤로가기가 접근성 설정으로 갔고**, 그것을 실기기로
        // 재서 잡았다. 온보딩은 `onResume`에서 스스로 끝나므로 그 위에 남지도 않는다.
        val intent = Intent(this, ConversationActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val requestedAt = SystemClock.elapsedRealtime()
        val thrown = try {
            startActivity(intent)
            null
        } catch (e: Exception) {
            e
        }
        GuideTelemetry.accessibilityReturn(thrown?.javaClass?.simpleName)
        if (thrown != null) return
        // **정말 떴는지는 잠깐 뒤에 따로 잰다**(#471 리뷰 2번). 백그라운드 액티비티 시작
        // 차단은 **예외를 안 던지고** 조용히 드롭되므로, 위 줄만으로는 막히는 기기에서도
        // 초록으로 보인다 — 그 줄이 차선(알림 폴백)을 안 만든 근거인데 근거가 못 된다.
        //
        // 상한의 근거는 실측이다: 「허용」을 누른 지 **1초 안에** 다비가 나왔다(#451 실측,
        // A15·A16 양쪽). 두 배로 잡아 느린 기기를 받는다. **못 떴다고 무엇을 하지는 않는다** —
        // 지금은 재기만 한다. `not_shown`이 도는 기기가 나오면 그때 차선을 만든다.
        val verify = Runnable {
            GuideTelemetry.accessibilityReturnResult(
                ConversationForeground.resumedSince(requestedAt),
                RETURN_VERIFY_DELAY_MS,
            )
            returnVerify = null
        }
        returnVerify = verify
        mainHandler.postDelayed(verify, RETURN_VERIFY_DELAY_MS)
    }

    /**
     * 걸어 둔 복귀 확인. **회수하려고 들고 있는다** — 서비스가 그 사이에 풀리면 판정할 대상이
     * 없는데도 줄이 하나 나간다. 같은 자리에서 리시버와 예약 샘플이 먼저 지적받았다(onUnbind 주석).
     */
    private var returnVerify: Runnable? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        // **서비스가 살아났다는 것이 곧 "안내 기능이 꺼졌어요" 알림의 전제가 사라졌다는 뜻이다**
        // (#212 · PR #240 리뷰). AUTO_CANCEL은 탭에만 걸리므로, 사용자가 알림을 누르지 않고
        // 설정에서 접근성을 다시 켜면 그 알림이 계속 남아 있었다. 첫 바인드에서는 지울 것이 없다.
        GuideNotifier.clear(this)
        comeForwardIfOnboardingSentUs()
        // 칩 클릭은 메인 스레드다(GuideSession의 스레드 계약 충족). 세션이 끝나면 위 리스너가
        // 오버레이를 걷는데, 그 경로가 mainHandler.post라 클릭 디스패치 안에서 자기 창을 걷지 않는다.
        // 로그를 남기는 이유: 사용자가 끝낸 세션은 그 외에 아무 흔적도 안 남아, 로그만 보면
        // "안내가 그냥 멈췄다"와 구별이 안 된다.
        overlay = OverlayController(
            this,
            object : OverlayController.Callbacks {
                override fun onStopRequested() {
                    // 칩은 끝내지 않고 되묻는다(GD-4, #193). 종료는 그 화면의 ○뿐이다.
                    if (!GuideSession.awaitQuitConfirm()) return
                    Log.i(TAG, "stop chip, asking user")
                    draw(display.showQuitConfirm(getString(R.string.overlay_quit_confirm)))
                    // 확인 대기로 들어갔으니 읽는 중은 여기서 끈다(CONFIRM_CONFIDENT와 같은 이유).
                    updateReading()
                }

                override fun onConfirmAccept(kind: GuideDisplayState.ConfirmKind) {
                    // 앱을 앞으로 가져오지 않는다 — 방금 끝낸 작업 화면에 그대로 남는 것이
                    // "스스로 해냈다"는 경험에 맞다(2026-08-14 결정). 종료 확인의 ○도 같은 자리다.
                    Log.i(TAG, "confirm accepted ($kind), session done")
                    GuideSession.confirmAccepted()
                }

                override fun onInputFillRequested() {
                    performInputFill()
                }

                override fun onConfirmReject(kind: GuideDisplayState.ConfirmKind) {
                    Log.i(TAG, "confirm rejected ($kind), continuing")
                    when (kind) {
                        // 종료 확인의 ×는 **되돌리기**다. 되살릴 안내가 있으면 그대로 그리고
                        // 서버에 다시 묻지 않는다.
                        //
                        // 없으면(그 사이 화면이 바뀌어 표시 상태가 폐기했으면) **다시 묻는다.**
                        // 안 그러면 그 화면은 `markSent`로 남아 재질의가 없고, 사용자는 빈
                        // 오버레이만 보게 된다 — 화면이 또 바뀔 때까지 안내가 영영 안 온다
                        // (2026-08-18 실기기 실측: 확인 중 도착한 응답이 폐기된 화면에서 재현).
                        // 완료 확인의 × 가 하는 것과 같은 처리다.
                        GuideDisplayState.ConfirmKind.QUIT -> {
                            GuideSession.quitRejected()
                            val restored = display.dismissQuitConfirm(stability.currentSignature()?.structure)
                            if (restored != null) {
                                draw(restored)
                            } else {
                                overlay?.clear()
                                stability.markFailed(stabilityNowMs())
                            }
                        }

                        // 완료 확인의 ×는 **버리기**다. 기억만 해 두면 다음 샘플의 redisplay가
                        // 같은 화면이라는 이유로 확인쌍을 곧바로 되살린다 — 사용자가 방금
                        // 아니라고 답한 질문이 다시 뜨는 셈이다.
                        GuideDisplayState.ConfirmKind.DONE -> {
                            GuideSession.confirmRejected()
                            overlay?.clear()
                            display.reset()
                            guidedTargetLabel = null
                            guidedScreenPackage = null
                            guidedTargetClickable = false
                            guidedTargetNodeId = null
                            guidedTargetStableLabel = null
                            guidedTargetStructure = null
                            // 그 화면을 '안 보낸 것'으로 되돌려 다음 샘플이 다시 묻게 한다
                            // (RETRY와 같은 경로).
                            stability.markFailed(stabilityNowMs())
                        }
                    }
                    updateReading()
                }
            },
        )
        e2ePublisher = GuideE2ePublisher(this)
        authStore = AuthStore(this)
        guideApiClient = GuideApiClient(
            BuildConfig.SERVER_URL,
            devAccessToken = BuildConfig.DEV_ACCESS_TOKEN,
            sessionToken = { authStore?.sessionToken(System.currentTimeMillis() / 1000) },
        )
        // 옵트인 빌드만 해시를 계산한다 — 서버 화이트리스트는 저장만 거를 뿐 전송은 여기서만
        // 막을 수 있다(#77 리뷰). 앱의 1차 마스킹이 꺼진 요청은 서버가 구분할 수 없으므로
        // 식별자도 보내지 않아 raw dogfood 저장 경로를 닫는다(#206 리뷰).
        // null이면 로그에 '-'가 찍히고 요청 필드도 통째로 빠진다.
        dogfoodDeviceId = if (
            shouldAttachDogfoodDeviceId(
                dogfoodOptIn = BuildConfig.DOGFOOD_OPT_IN,
                maskingDisabled = BuildConfig.PRIVACY_MASKING_DISABLED,
            )
        ) {
            DogfoodDeviceId.hash(
                Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID),
            )
        } else {
            null
        }
        GuideSession.addListener(sessionListener)
        // 세 action이 **모두 protected broadcast**라 targetSdk 34+에서도 RECEIVER_* 플래그
        // 없이 등록된다(system broadcast 전용 면제). **protected가 아닌 action을 하나라도
        // 더하면 그 순간 연결 시점 SecurityException이 된다** — 더할 때 플래그도 함께 준다.
        registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            },
        )
        // 실제 전원·잠금 상태에서 시작한다. false로 두면 ① 화면이 꺼진 리그에서 debug E2E가
        // 세션을 먼저 시작한 경합과 ② 재바인드 때 남은 true 잔재를 둘 다 놓친다(3차 리뷰).
        //
        // **키가드도 함께 본다** — 재개가 `ACTION_USER_PRESENT`라 "켜져 있지만 잠금은 아직"은
        // 일시정지 쪽이다. `isInteractive`만 보면 그 상태로 재바인드될 때 두 경로가 반 칸
        // 어긋난다(4차 리뷰).
        screenOff = getSystemService(PowerManager::class.java)?.isInteractive == false ||
            getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        // debug E2E 제어 receiver가 접근성 callback보다 먼저 세션을 시작한 경합도 복구한다.
        // 보통은 listener가 시작 이벤트를 받지만, 시스템의 Bound 표시는 callback보다 먼저 바뀔 수 있다.
        if (GuideSession.isActive) scheduleSample(SETTLE_MS)
        // dogfoodDeviceId는 화이트리스트 등록의 유일한 추출 경로다(DogfoodDeviceId 참고).
        Log.i(
            TAG,
            "connected server=${BuildConfig.SERVER_URL} " +
                "dogfoodDeviceId=${dogfoodDeviceId ?: "-"} " +
                // 앱 1차 마스킹 상태는 서버 2차 상태와 독립으로 드러낸다. 한쪽만 우회한 상태를
                // "둘 다 우회"로 오인하면 비교 결과를 잘못 읽는다(#203).
                "privacyMasking=${if (BuildConfig.PRIVACY_MASKING_DISABLED) "DISABLED" else "on"}",
        )
        if (BuildConfig.PRIVACY_MASKING_DISABLED) {
            Log.w(
                TAG,
                "1차 개인정보 마스킹이 꺼져 있습니다. 원문 화면 label이 서버와 실제 Gemini까지 " +
                    "그대로 나갑니다. 합성 데이터만 넣은 전용 테스트 기기에서만 사용하세요(#203).",
            )
        }
    }

    /**
     * 이벤트는 이제 "지금 볼 만하다"는 신호일 뿐이다. 판단은 샘플러가 한다.
     *
     * 이벤트에만 기대면 목록 끝처럼 **이벤트가 아예 안 오는 상황**에서 안내가 멈춘다
     * (`HANDOFF.md` §3-4 실측, SPEC §4). 반대로 전환 직후에는 이벤트가 몰아쳐서
     * 덜 그려진 트리가 그대로 나간다(#32). 그래서 이벤트는 샘플을 **미루면서** 예약한다 —
     * 연속된 이벤트는 한 번으로 합쳐지고, 마지막 이벤트 뒤 [SETTLE_MS]가 지나야 샘플을 뜬다.
     *
     * **다만 무한정 미루지는 않는다**(#380). 스스로 움직이는 화면은 이벤트를 [SETTLE_MS]보다
     * 촘촘히 쏘아 조용해지는 순간이 오지 않는다 — [SampleSchedule]이 그 기한을 쥔다.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // 세션이 없으면 화면을 읽지 않는다. 직렬화 이전에 끊는 것이 중요하다 —
        // 사용자가 안내를 요청하지 않은 동안에는 화면 내용이 프로세스 안에서도 만들어지지 않는다.
        // **필터보다 먼저 본다** — 계측이 걸러진 이벤트까지 봐야 "안 왔다"와 "걸렀다"가 갈린다(#336).
        if (GuideSession.active() == null) return

        val interesting = isInteresting(event.eventType)
        // 계측. release 구현은 no-op이고 이벤트 텍스트는 어느 쪽도 남기지 않는다.
        // 위 필터 앞이라는 자리가 감당 가능한 이유는 이 호출이 `event.source`를 읽지 않기 때문이다(#356).
        GuideScreenLog.event(event, interesting)
        if (!interesting) return

        // 인과 게이트의 **폴백 축**(#380). 판정에 쓰는 것은 타입과 package뿐이라, 부하 시
        // source가 회수되는 함정(#238)과 무관하게 동작한다.
        //
        // **스크롤은 여기 없다.** 2026-08-24 SM-S921N 실측 — 인기 검색어가 자동으로 도는
        // 검색 화면에서 사용자가 손대지 않은 50초 동안 `TYPE_VIEW_SCROLLED`가 92건(초당
        // 1.8건) 왔다. 게이트가 영구히 열려 로테이션마다 재질의가 나갔다 — 이 이슈가
        // 막으려던 바로 그 폭주다.
        //
        // 자동 스크롤과 사용자 스크롤은 접근성 이벤트만으로 구분되지 않는다. **진짜 터치를
        // 보는 길(`FLAG_SEND_MOTION_EVENTS` + `setMotionEventSources`, API 34+)은 시도했다가
        // 걷었다** — 그 플래그가 접근성 입력 필터를 세우고 터치가 아래 앱으로 내려가지 않아
        // 기기 조작이 통째로 막혔다(2026-08-24 SM-S921N 실측: `dumpsys input`의
        // `InputFilterEnabled: true`, 서비스는 down/move/up을 받는데 앱은 아무 반응 없음).
        // 되살리려면 필터가 이벤트를 통과시키는 방법부터 확인해야 한다 — #394.
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            val pkg = event.packageName?.toString()
            if (pkg != null && pkg == currentScreenPackage) {
                stability.markInteraction(stabilityNowMs())
            }
            onTargetPressed(event)
        }
        scheduleSample(SETTLE_MS)
    }

    /**
     * 화면이 꺼졌다. 안내를 화면에서 걷고 샘플러를 재운다 — 사용자가 볼 수도, 조작할 수도
     * 없는 동안이다. **내용은 [display]가 기억한다**(`root == null` 청소와 같은 계약) —
     * 잠금이 풀리고 같은 화면으로 돌아오면 `redisplay`가 되살린다.
     */
    private fun pauseForScreenOff() {
        if (screenOff) return
        screenOff = true
        cancelSample()
        overlay?.clear()
        display.onCleared()
        lastDecision = null
        updateReading()
        Log.i(TAG, "screen off, overlay cleared (session kept)")
    }

    /**
     * 잠금이 풀렸다(또는 키가드가 없다). 세션이 살아 있으면 다시 샘플링해 안내를 되살린다.
     *
     * **정체 타이머를 여기서 다시 찍는다** — 꺼져 있던 구간은 정체가 아니다
     * ([ScreenStability.markResumed]). 그 사이 응답을 버렸으면 그 화면을 다시 묻게 한다.
     */
    private fun resumeAfterScreenOn() {
        if (!screenOff) return
        screenOff = false
        stability.markResumed(stabilityNowMs(), requery = droppedWhileScreenOff)
        val requeried = droppedWhileScreenOff
        droppedWhileScreenOff = false
        // 읽는 중 판정은 첫 샘플이 lastDecision을 채운 뒤에 한다. 여기서 먼저 부르면 null을
        // "첫 샘플 전"으로 읽어 테두리가 한 프레임 깜빡인다(3차 리뷰).
        if (GuideSession.isActive) scheduleSample(SETTLE_MS) else updateReading()
        Log.i(
            TAG,
            "screen on, resuming (session active=${GuideSession.isActive} requery=$requeried)",
        )
    }

    /**
     * 안내한 대상을 사용자가 **실제로 눌렀는지** 보고, 맞으면 눌림을 알리고 강조를 내리며(#238)
     * 판정을 마지막 스텝의 `performed`에 적는다(#319).
     *
     * 화면 서명이 바뀌기 전이라 [ScreenStabilityTracker]는 아직 아무것도 모르는 구간이다.
     * #225가 닫은 것이 그 **뒤** 구간이고 여기가 그 앞이다.
     *
     * **안내가 나간 화면의 package에서 난 클릭만 판정한다.** 이 서비스의 이벤트에는 창 구분이
     * 없어 우리 오버레이(중지 칩)·IME 키·셰이드의 클릭도 여기로 온다 — 그걸 판정에 들이면
     * 안내를 잘 따르는 사용자가 "다른 곳을 누름"으로 적힌다. 걸러서 잃는 것은 미탐뿐이다.
     *
     * **정체·진행 판정은 여전히 시간 기반이다.** 목록 끝에서 이벤트가 오지 않는 제약은 그대로라
     * (`HANDOFF.md` §3), 이 이벤트의 쓰임은 표시·스텝 귀속 기록·정체 타이머의 기준점 셋이고
     * "물을지 말지"는 계속 샘플러가 정한다 — 이벤트가 안 오면 `performed`가 null로 남고 정체
     * 타이머가 안내 시점부터 돌 뿐, 안내는 멈추지 않는다. 미탐 → null 강등이 설계된 퇴화
     * 경로다(`../CLAUDE.md`의 "조작 이벤트에 의존하지 않는다"). 눌림 확인이 여는 유일한 예외가
     * 정체 시점의 재질의 1회다([GuideSession.consumePressRequery]).
     */
    private fun onTargetPressed(event: AccessibilityEvent) {
        val bounds = display.pressTargetBounds() ?: return
        val screenPackage = guidedScreenPackage ?: return
        if (event.packageName?.toString() != screenPackage) return
        when (pressVerdict(event, bounds)) {
            PressMatch.Verdict.ON_TARGET -> {
                // **표시가 먼저다.** 파동은 "방금 눌렀다"는 사실의 표시라 세션 상태에 매이지
                // 않는다(#238, `docs/SCREENS.md` GD-2). 기록을 앞에 두면 세션이 막 끝난 좁은
                // 구간에서 기록 실패가 표시까지 삼킨다(#333 리뷰).
                display.consumePress()
                Log.i(TAG, "press on target")
                overlay?.pressFeedback(Rect(bounds[0], bounds[1], bounds[2], bounds[3]))
                display.hideTarget()?.let { draw(it) }
                GuideSession.active()?.generation?.let { generation ->
                    GuideSession.observePress(generation, onTarget = true)
                    // "안내 후 화면 그대로 7초"는 방금 조작한 시점부터 다시 센다.
                    stability.markPressed(stabilityNowMs())
                }
            }

            // 좌표 축의 확신 불일치만 온다 — 표시(파동·강조)는 건드리지 않고 기록만 한다.
            // press는 소비하지 않는다: 빗맞은 뒤 대상을 제대로 누르면 true로 덮여야 한다.
            // 비클릭 대상(#311)의 불일치는 확신이 아니라서 버린다([guidedTargetClickable]).
            PressMatch.Verdict.OFF_TARGET -> {
                if (!guidedTargetClickable) return
                val generation = GuideSession.active()?.generation ?: return
                GuideSession.observePress(generation, onTarget = false)
                Log.i(TAG, "press off target")
            }

            PressMatch.Verdict.UNKNOWN -> Unit
        }
    }

    /**
     * 두 축으로 맞춘다 — 좌표가 정확하지만 **항상 있지는 않다**([PressMatch]).
     *
     * **이벤트 텍스트는 비교에만 쓰고 어디에도 남기지 않는다.** 원본이라 개인정보일 수 있어
     * 로그·전송 경로에 들어가면 안 된다(1차 마스킹은 직렬화 경로에만 걸려 있다). 좌표가 있으면
     * 텍스트를 꺼내지도 않는다 — 판정에 안 쓰는 원본을 만들 이유가 없다.
     */
    private fun pressVerdict(event: AccessibilityEvent, bounds: List<Int>): PressMatch.Verdict {
        val clicked = Rect()
        // **여기의 `event.source`는 남긴다.** #356이 계측에서 걷은 것은 `isInteresting` 앞에서
        // 모든 이벤트에 붙던 호출이고, 이 자리는 게이트 다섯을 지난 `TYPE_VIEW_CLICKED`뿐이다.
        // 텍스트 폴백은 `ON_TARGET`만 낼 수 있어 `OFF_TARGET`을 대체하지 못하고
        // ([PressMatch.Verdict]), 좌표를 버리면 **매 판정이 위 KDoc이 피하는 원본 텍스트 경로로
        // 내려간다.** 회수는 안 한다 — `recycle()`이 no-op이 되는 것은 API 33부터라 minSdk 26이
        // 대상에 들지만, 이 빈도에서는 감수한다.
        event.source?.getBoundsInScreen(clicked)
        if (!clicked.isEmpty) {
            return PressMatch.judge(
                bounds,
                clicked.centerX() to clicked.centerY(),
                guidedTargetLabel,
                emptyList(),
            )
        }
        val candidates = buildList {
            event.text.forEach { item -> item?.toString()?.let { add(it) } }
            event.contentDescription?.toString()?.let { add(it) }
        }
        return PressMatch.judge(bounds, null, guidedTargetLabel, candidates)
    }

    private fun scheduleSample(delayMs: Long) {
        // 꺼진 화면에는 그릴 것도 물을 것도 없다. 되살리는 것은 [resumeAfterScreenOn]뿐이다.
        if (screenOff) return
        // 이벤트가 폭주해도 [SampleSchedule.MAX_DEFER_MS] 안에는 한 번 뜬다(#380).
        val delay = sampleSchedule.delayFor(delayMs, SystemClock.uptimeMillis())
        mainHandler.removeCallbacks(sampleRunnable)
        mainHandler.postDelayed(sampleRunnable, delay)
    }

    /**
     * 예약을 걷는다. **기한도 함께 닫아야 한다** — 남겨 두면 걷혀 있는 동안 기한이 지나 버려,
     * 다시 예약될 때 지연 0으로 계산돼 settle 없이 곧바로 뜬다.
     */
    private fun cancelSample() {
        mainHandler.removeCallbacks(sampleRunnable)
        sampleSchedule.reset()
    }

    /**
     * 화면을 한 번 떠서 [ScreenStabilityTracker]에 묻고, 답에 따라 움직인 뒤 다음 샘플을 예약한다.
     *
     * 메인 스레드 전용이다(접근성 콜백과 같은 스레드). 트래커 상태를 여기서만 만지므로 잠금이 필요 없다.
     */
    private fun sampleScreen() {
        // 예약과 화면 꺼짐이 엇갈려 이미 큐에 들어간 실행을 여기서 한 번 더 막는다.
        if (screenOff) return
        val session = GuideSession.active() ?: return

        val resolved = ActiveWindowResolver.resolve(this)
        activeWindowBounds = resolved?.bounds
        val windowTitle = resolved?.title
        val root = resolved?.root
        if (root == null) {
            // 우리 앱이 앞에 있거나 읽을 창이 없다. 남은 강조를 지우고 계속 지켜본다.
            // 내용은 display가 기억한다 — 유효 화면으로 돌아오면 아래 redisplay가 되살린다.
            if (!rootUnavailable) {
                rootUnavailable = true
                GuideTelemetry.rootState("unavailable")
                e2ePublisher?.rootState(available = false)
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
            // root가 없던 시간은 화면을 관측한 시간이 아니다. 정체·읽기 불가 타이머 양쪽에서
            // 빼야 복구 직후 덜 채워진 트리에 곧바로 경고하지 않는다(PR #352 재리뷰).
            stability.markResumed(stabilityNowMs(), requery = false)
            GuideTelemetry.rootState("recovered")
            e2ePublisher?.rootState(available = true)
        }

        // IME는 창 type만 읽고 키보드 root나 키 label은 직렬화하지 않는다(#112).
        // 빈 목록은 "IME 없음"이 아니라 interactive window 관측 실패일 수 있으므로 미관측으로 둔다.
        val windowTypes = runCatching { windows.map { it.type } }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
        // 직렬화에 든 시간(#367). **`SystemClock.uptimeMillis`를 쓴다** — 벽시계는 시각 동기화로
        // 뒤로 갈 수 있고, 이 값은 `postDelayed`가 쓰는 시간축과 같아야 한다.
        // 무엇이 이 구간 밖인지는 아래 로그 자리에 적었다.
        val serializeStart = SystemClock.uptimeMillis()
        val result = runCatching {
            UiTreeSerializer.serialize(root, windowTypes, windowTitle)
        }.getOrElse {
            // 실패한 순회에도 소요를 남긴다. 안 남기면 **오래 끌다 던진 폴링이 통째로 빠져**
            // #367이 찾는 p90·최대가 낮은 쪽으로 치우친다.
            // **키를 `ms=`로 맞춘다** — 아래 `poll=` 줄과 같은 이름이라야 한 번의 grep으로 집계된다.
            Log.e(TAG, "serialize failed ms=${SystemClock.uptimeMillis() - serializeStart}", it)
            scheduleSample(POLL_MS)
            return
        }
        val serializeMs = SystemClock.uptimeMillis() - serializeStart

        val signature = ScreenSignature.of(result.screen)
        val now = stabilityNowMs()
        val hasLabeledElement = result.screen.hasLabeledElement()
        currentScreenPackage = result.screen.packageName.takeIf { it.isNotBlank() }
        // 한 번만 재서 재질의 판정과 강조 복원 양쪽에 쓴다 — 같은 질문이다.
        val targetSlotChanged = guidedTargetSlotChanged(result.screen, signature.structure)
        val decision = stability.onSample(
            signature,
            now,
            hasLabeledElement,
            guidedTargetChanged = targetSlotChanged,
        )
        // 계측. IDLE이 침묵해서 "화면이 안 바뀐 것"과 "우리가 못 본 것"이 구분되지 않는다.
        // `ms`는 **`serialize()` 구간만**이다(#367) — 폴링 한 번의 전체 비용이 아니다.
        // 밖에 남는 것: `ActiveWindowResolver.resolve()`의 창·root IPC, 직렬화 앞의 `windows.map`,
        // 직렬화 뒤의 `ScreenSignature.of`. 예산(`SETTLE_MS`·`POLL_MS`)과 대조할 때 이 값은 **하한**이다.
        // 폴링마다 찍혀야 p90·최대가 나오므로 화면당 한 번인 `screen_stable`이 아니라 이 줄에 싣는다.
        GuideScreenLog.sample(
            decision.name,
            result.screen.elements.size,
            signature,
            serializeMs,
            stability.interactionGateOpen(now),
            targetSlotChanged,
            stability.anchorRequerySpent(),
            result.cost,
        )
        lastDecision = decision
        updateReading()

        // 지워진 안내의 회복 경로(PR #63 리뷰 P1). 상한 안내는 '안내 중지' 버튼이 우리 앱에만
        // 있어서, 문구를 따르러 앱을 여는 것만으로 위 root=null 청소에 지워졌다 — 중지 없이
        // 작업 화면으로 돌아오면(화면 불변 → IDLE만 반복) 되살릴 길이 없었다.
        // 같은 화면(서명 일치)에서만 되살리고, 다른 화면이면 폐기해 새 요청에 맡긴다(2차 리뷰 P1).
        // 단 **안정 판정 뒤에만** — 과도기(WAIT)나 label 없는 UNREADABLE의 서명으로 폐기하면
        // #32의 덜 그려진 트리 한 번에 보존 안내를 잃는다(4차 리뷰 P1, PR #352 재리뷰).
        when (val redisplay = display.redisplay(signature.structure, decision.isStableForGuideRedisplay())) {
            GuideDisplayState.RedisplayResult.Unchanged -> Unit
            GuideDisplayState.RedisplayResult.Clear -> {
                Log.i(TAG, "clear unreadable guide")
                overlay?.clear()
            }
            is GuideDisplayState.RedisplayResult.Show -> {
                Log.i(TAG, "redisplay guide")
                draw(redisplay.content)
            }
        }

        // 화면이 흔들리는 동안에는 강조가 **이미 사라진 대상**을 가리킨다(#225). 자리만 내리고
        // 문구는 남겼다가, 같은 화면으로 안정되면 되돌린다. 위 redisplay는 오버레이가 통째로
        // 지워진 경우를 맡고 여기는 그 안쪽 — 안내는 떠 있는데 강조만 틀린 경우다.
        if (decision == ScreenStabilityTracker.Decision.WAIT) {
            display.hideTarget()?.let {
                Log.i(TAG, "hide target")
                draw(it)
            }
        } else if (targetSlotChanged) {
            // 구조가 같은 채 목록만 밀린 화면이다(#380). 소속 판정이 구조 축이라 여기까지
            // 오는데, 강조는 **다른 줄**을 가리키고 있다. 되돌리는 것만 막으면 이미 떠 있는
            // 강조가 그대로 남으므로 **내린다** — `hideTarget`의 계약 그대로(자리만 내리고
            // 문구는 남긴다, #225). 눌림 재무장도 함께 막힌다(`restoreTarget`이 푸는 것을
            // 안 부르므로), 그 자리를 누른 사용자가 `performed=true`로 오귀속되지 않는다.
            //
            // [GuideDisplayState.hideTarget]이 이미 내려간 강조에는 null을 돌려주므로
            // 로그도 전이에서만 나간다 — 따로 래치를 둘 필요가 없다.
            display.hideTarget()?.let {
                Log.i(TAG, "target slot moved, hide target")
                draw(it)
            }
        } else {
            display.restoreTarget(signature.structure)?.let {
                Log.i(TAG, "restore target")
                draw(it)
            }
        }
        when (decision) {
            ScreenStabilityTracker.Decision.WAIT -> {
                // 억제한 샘플이 로그에 없으면 "덜 그려진 트리를 안 보냈다"를 실기기에서 볼 수 없다(#32).
                Log.d(
                    TAG,
                    "settling pkg=${result.screen.packageName} " +
                        "elements=${result.screen.elements.size} labeled=$hasLabeledElement",
                )
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
                    // 1차 마스킹 우회 중에는 E2E 세션 밖에서도 원문 label을 남기지 않는다.
                    if (
                        shouldLogDetailedScreen(
                            maskingDisabled = BuildConfig.PRIVACY_MASKING_DISABLED,
                            e2eSuppressesDetailedLogs = e2ePublisher?.suppressDetailedScreenLogs == true,
                        )
                    ) {
                        logScreenDetails(result)
                    }
                }
                sendStableScreen(result.screen, signature, now, session.generation)
                scheduleSample(POLL_MS)
            }

            ScreenStabilityTracker.Decision.UNREADABLE -> {
                // label 없는 화면은 서버에 보내지 않는다. 다만 영구 빈 WebView·카메라에서도
                // 읽는 중 표시가 무한히 돌면 안 되므로 로컬 안내로 빠져나갈 길을 준다(#350).
                // 완료·종료 확인은 화면을 따라다니는 더 강한 상태라 덮지 않는다.
                if (!GuideSession.awaitingConfirm) {
                    display.showUnreadableNotice(
                        signature.structure,
                        getString(R.string.guide_screen_unreadable),
                    )?.let {
                        e2ePublisher?.screenUnreadable()
                        Log.w(
                            TAG,
                            "screen unreadable for ${ScreenStabilityTracker.UNREADABLE_AFTER_MS}ms",
                        )
                        draw(it)
                    }
                }
                scheduleSample(POLL_MS)
            }

            ScreenStabilityTracker.Decision.STUCK -> {
                // 확인 대기 중 화면이 안 바뀌는 것은 사용자가 답을 고르는 동안 당연하다.
                // 알리지 않는 데 그치지 않고 건너뛰는 이유는, stuckContent가 같은 내용으로
                // 다시 그리면서 우리 창을 만져 스스로를 깨우기 때문이다(ControlOverlay 참고).
                if (!GuideSession.awaitingConfirm) {
                    // 눌림 확인 재질의(#319). 안내한 대상을 눌렀는데(performed=true) 눌림
                    // 시점부터 7초 동안 화면이 그대로면 오안내 신호다 — 이때만 같은 화면을
                    // 한 번 더 묻는다. observeScreen이 같은 서명으로 screen_changed=false를
                    // 적으므로 서버는 "누른 것이 확인됨 · 화면이 그대로임"을 읽는다.
                    // 토큰은 눌림당 하나라(#39 예외의 상한) 다음 STUCK은 정체 문구다.
                    // 요청이 시작되지 못하면(서비스 해제 경합 등) 정체 문구로 떨어진다 —
                    // STUCK이 사용자에게 아무것도 안 보여 주는 경로를 만들지 않는다.
                    val requeried =
                        GuideSession.consumePressRequery(session.generation) &&
                            sendStableScreen(result.screen, signature, now, session.generation)
                    if (requeried) {
                        Log.i(TAG, "press requery")
                    } else {
                        e2ePublisher?.screenStuck()
                        Log.i(TAG, "screen stuck for ${ScreenStabilityTracker.STUCK_AFTER_MS}ms")
                        showStuckNotice(signature.structure)
                    }
                }
                scheduleSample(POLL_MS)
            }

            ScreenStabilityTracker.Decision.IDLE -> scheduleSample(POLL_MS)
        }
    }

    /**
     * 안정된 현재 화면으로 요청 하나를 시작한다. SEND와 눌림 확인 재질의(#319)가 같은 절차를
     * 쓴다 — 갈라 두면 다음 전송 규칙 변경이 한쪽만 고친다.
     *
     * 실제로 나간 요청만 "보냈다"로 기록한다. 보낸 셈 치면 in-flight 가드에 막힌 화면의 안내가
     * 영영 오지 않는다(PR #61 리뷰 P1). 보내기 직전에 마지막 스텝의 `screen_changed`를
     * 확정하고(C11 개정) **스냅샷을 그 뒤에 뜬다** — 샘플 처음의 스냅샷에는 방금 적은 값이
     * 없어, 그대로 보내면 한 요청씩 늦는다.
     *
     * @return 요청이 실제로 시작됐는가.
     */
    private fun sendStableScreen(
        screen: Screen,
        signature: ScreenSig,
        now: Long,
        generation: Long,
    ): Boolean {
        // 스텝의 screen_changed는 구조 축 + **인과 있는 내용 변화**다(#380). 내용을 통째로
        // 대조하면 눌림 확인 재질의가 "화면 그대로"를 전제하는 화면에서 배너 한 번에
        // changed=true가 적혀 서버가 모순된 이력을 읽고, 반대로 구조만 보면 균일 목록
        // 스크롤이 "안 바뀜"으로 적힌다. 재질의를 연 것과 **같은 판정**을 옮겨 적는다.
        GuideSession.observeScreen(
            generation,
            signature.structure,
            contentChangedCausally = stability.lastSendWasContentOnly(),
        )
        val sending = GuideSession.active() ?: return false
        if (!requestNextStep(screen, signature, now, sending)) return false
        stability.markSent(signature, now)
        return true
    }

    /**
     * 안내한 대로 했는데 화면이 그대로일 때. **서버를 다시 부르지 않는다** — 폴링이 곧 호출량이고
     * 무료 티어는 15 RPM이다(#39). 강조와 원래 안내는 그대로 두고 한 줄만 덧붙인다.
     * 동작을 새로 시키는 문장이 아니라서 "한 instruction에 두 동작" 금지(`HANDOFF.md` §3-14)에 걸리지 않는다.
     * 상한 안내에는 접미사를 붙이지 않고 원문을 유지한다 — 덮어쓰지도, 잃지도 않는다(PR #63 리뷰 P1).
     *
     * 최종 문구는 안내 상태 표시(#29)에서 다시 정한다.
     */
    private fun showStuckNotice(structureSignature: String) {
        display.stuckContent(getString(R.string.guide_stuck), structureSignature)?.let { draw(it) }
    }

    /**
     * 타깃 앵커(#380) — 안내가 가리키는 슬롯의 label이 응답 시점과 달라졌는가. 구조가 같은
     * 목록 재정렬(새 메시지가 대화방 순서를 미는 경우)을 조작 이벤트 없이 잡는 트리거다.
     * 슬롯이 사라진 경우는 false다 — 그건 구조 변화라 구조 축이 이미 재질의를 연다.
     */
    private fun guidedTargetSlotChanged(screen: Screen, structure: String): Boolean =
        screen.guidedSlotLabelChanged(
            guidedTargetNodeId,
            guidedTargetStableLabel,
            guidedTargetStructure,
            structure,
        )

    /**
     * 읽는 중 테두리를 지금 상태에 맞춘다(GD-1·GD-2r). 판정은 [ReadingBorder]에 있고 여기서는
     * 값을 모아 넘기기만 한다. 멱등이라 같은 값으로 반복 호출해도 안전하다.
     */
    private fun updateReading() {
        val reading =
            ReadingBorder.isReading(
                // 유무만 필요하다. active()는 잠금을 잡고 이력 리스트 두 개를 복사하는데,
                // 그 잠금은 네트워크 스레드의 기록 경로와 공유한다.
                sessionActive = GuideSession.isActive,
                // 화면이 꺼져 있으면 읽을 수 없다 — 테두리도 안내와 함께 내려간다(#56).
                screenReadable = !rootUnavailable && !screenOff,
                decision = lastDecision,
                requestInFlight = requestInFlight.get(),
                // 확인 대기 중에는 어느 종류든 읽는 중 표시를 끈다. 완료 확인(GD-3)은 같은
                // 자리를 성공 tone 테두리가 쓰고(#139), 종료 확인(GD-4)은 그 자리를 아무도
                // 안 쓴다 — 스크림은 다른 자리고 테두리가 없다(#193).
                awaitingConfirm = GuideSession.awaitingConfirm,
            )
        // 읽는 구간의 길이를 로그로 남긴다(#295). 문구 교체가 이 구간에 걸리므로, 구간이
        // 너무 짧아 깜빡이는지를 이 전이로 판정한다. **백오프 대기는 여기 안 잡힌다** —
        // ScreenStability가 IDLE을 내고 isReading이 false다(#29: 서버 실패는 안내창이 맡는다).
        if (lastReading != reading) {
            lastReading = reading
            Log.i(TAG, "reading=$reading")
        }
        overlay?.setReading(reading)
    }

    /** 판정된 표시 내용을 그린다. bounds 변환만 여기서 한다 — 상태는 Android 타입을 모른다. */
    private fun draw(content: GuideDisplayState.Content) {
        val rect = content.bounds?.takeIf { it.size == 4 }?.let { Rect(it[0], it[1], it[2], it[3]) }
        overlay?.showGuide(
            rect,
            content.text,
            avoidRegions(content.actionType),
            content.actionType,
            content.direction,
            content.confirm,
            content.targetSuppressed,
            content.inputText,
        )
    }

    /**
     * 「바로 입력」 버튼(C21 · #405) — `SPEC.md` §2 「대신 해주지 않는다」의 **유일한 예외**를
     * 실행하는 자리다. 예외의 세 경계가 다 여기 걸려 있다: ① 개시는 사용자의 버튼 탭이다(이
     * 함수는 그 콜백에서만 불린다). ② 값은 서버 가드가 **안내문과 대조해** 내려준 `input_text`라,
     * 사용자가 방금 읽은 문장이 집어 말한 말이다. ③ 채우기만 한다 — IME 액션(검색 실행)은
     * 부르지 않는다.
     *
     * 대상은 **누르는 시점에 다시 찾는다.** 응답 시점의 노드를 들고 있으면 화면이 조금만
     * 움직여도 stale이고, 시니어가 버튼을 누르기까지의 간격은 길다. 안내가 가리킨 자리의
     * 입력칸을 먼저 찾고, 없으면 입력 포커스를 쓴다 — 타이핑 안내는 키보드가 열린 화면에서
     * 나가므로(#38 5번) 포커스가 곧 "지금 글자가 들어갈 곳"이다.
     *
     * **루트는 직렬화와 같은 판정기로 고른다**([ActiveWindowResolver]). `rootInActiveWindow`를
     * 직접 쓰면 대조할 bounds가 나온 창과 **다른 창**을 뒤질 수 있다 — 셰이드가 열리면 systemui가
     * active가 되는 것이 그 판정기가 존재하는 이유고(HANDOFF §7), 우리 앱 필터도 거기 있다.
     *
     * 채운 뒤 아무것도 하지 않는다 — 대상 앱이 내는 텍스트 변경 이벤트가 손 타이핑과 같은
     * 경로로 샘플을 깨우고, 다음 안내는 그 화면 변화가 부른다.
     */
    private fun performInputFill() {
        // **값도 자리도 표시 상태에서 파생한다**([GuideDisplayState.inputFill]) — 서비스가 미러를
        // 들면 `input_text`가 빈 Content(종료 확인·읽기 불가)가 깔때기를 지날 때 자리가 죽는다.
        val (text, targetBounds) = display.inputFill() ?: return
        val root = ActiveWindowResolver.resolve(this)?.root ?: run {
            Log.w(TAG, "input fill: no root")
            return
        }
        // **어느 축으로 찾았는지 남긴다.** 두 축은 대개 같은 칸을 가리켜 결과가 구별되지 않는데,
        // 갈리는 순간(포커스가 다른 칸에 있을 때)이 바로 안내하지 않은 칸을 채우는 경우다.
        // 실기기 확인이 logcat 수동인 동안 이 표기가 그 축을 볼 유일한 창이다.
        val matched = findEditableNode(root, targetBounds)
        val node = matched
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
        val via = if (matched != null) "bounds" else "focus"
        if (node == null) {
            Log.w(TAG, "input fill: no editable target")
            return
        }
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text,
            )
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        // 값 자체는 남기지 않는다 — 모델이 쓴 문장에서 온 말이라 무엇이 들었는지 서버도 앱도
        // 보장하지 못한다(C21의 잔여 위험, `SPEC.md` §2). 운영 로그의 비식별 원칙 그대로다.
        // **폴백은 경고로 올린다**(PR #407 리뷰) — 안내한 칸을 못 찾고 포커스로 채운 것이라
        // 예외의 경계(안내한 자리) 밖으로 샌 신호다. 결과가 같아 보여 눈으로는 안 걸린다.
        val line = "input fill performed ok=$ok via=$via chars=${text.length}"
        if (matched == null) Log.w(TAG, line) else Log.i(TAG, line)
    }

    /**
     * [bounds]가 가리킨 자리의 입력칸을 지금 트리에서 찾는다. 자리가 없거나 못 찾으면 null —
     * 호출자가 입력 포커스로 폴백한다.
     *
     * **대조는 눌림 판정과 같은 규칙을 쓴다**([PressMatch.byBounds]) — 후보의 중심이 안내한
     * 사각형 안이면 같은 대상으로 본다. 사각형이 정확히 같기를 요구하면 두 가지에 깨진다:
     * 안내한 것이 입력칸을 감싼 컨테이너일 때(저 함수가 런처 독에서 겪은 것과 같은 모양), 그리고
     * 사용자가 버튼을 누르기까지 자리가 몇 px 움직였을 때다. 폴백이 있어 놓쳐도 실패는 아니지만,
     * **폴백은 안내한 칸이 아닌 곳을 채울 수 있으므로**(포커스가 옮겨간 경우) 이 축이 먼저 맞는
     * 편이 예외의 범위에 맞다.
     *
     * 순회 상한은 직렬화와 같은 결이다([UiTreeSerializer]) — 병적인 트리에서 클릭 디스패치를
     * 붙잡지 않는다.
     */
    private fun findEditableNode(
        root: AccessibilityNodeInfo,
        bounds: List<Int>?,
    ): AccessibilityNodeInfo? {
        if (bounds == null || bounds.size != 4) return null
        val queue = ArrayDeque(listOf(root))
        val nodeBounds = Rect()
        var visited = 0
        while (queue.isNotEmpty() && visited < INPUT_FILL_MAX_NODES) {
            val node = queue.removeFirst()
            visited++
            if (node.isEditable) {
                node.getBoundsInScreen(nodeBounds)
                if (!nodeBounds.isEmpty &&
                    PressMatch.byBounds(bounds, nodeBounds.centerX(), nodeBounds.centerY())
                ) {
                    return node
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let(queue::addLast)
            }
        }
        return null
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
        signature: ScreenSig,
        sentAtMs: Long,
        session: GuideSession.Active,
    ): Boolean {
        if (!requestInFlight.compareAndSet(false, true)) return false
        val client = guideApiClient ?: run {
            requestInFlight.set(false)
            e2ePublisher?.clientConfigurationError()
            return false
        }
        // 논리 요청의 "같은 화면" 판정도 구조 축이다(#380) — 내용까지 넣으면 배너 로테이션이
        // network 재전송을 새 논리 요청으로 쪼개 재시도 추적(#81)이 끊긴다.
        val logicalRequest = logicalRequests.next(signature.structure, session.generation)
        // 내용 서명은 **여기서 한 번만** 만든다(#410). 이 요청이 싣는 두 자리 — 현재 화면
        // (`Screen`)과 그 화면에서 나간 안내(`steps[]`) — 가 같은 값을 써야 서버의 이력 간
        // 대조가 성립한다. 스텝 쪽은 [applyAction]이 `request.screen`에서 도로 읽는다.
        val requestScreen = screen.copy(
            contentSignature = GuideSession.contentSignature(
                session.generation,
                signature,
                screen.hasLabeledElement(),
            ),
        )

        apiExecutor.execute {
            val startedAt = SystemClock.elapsedRealtime()
            GuideTelemetry.requestStarted(logicalRequest)
            e2ePublisher?.requestStarted()
            try {
                val request = NextStepRequest(
                    goal = session.goal,
                    screen = requestScreen,
                    // 화이트리스트 판정은 서버가 한다. debug가 아니면 null이라 안 실린다(#41).
                    deviceId = dogfoodDeviceId,
                    // 직전에 무엇을 안내했고 그래서 화면이 바뀌었는지 모르면 모델이 같은 말을
                    // 반복한다(HANDOFF §3-10, C11 개정).
                    steps = session.steps,
                    // 창이 꽉 찬 이유를 모델이 알아야 한다 — 이제 10스텝짜리인지 40스텝째인지가
                    // `steps`만으로는 구분되지 않는다(#418).
                    earlierSteps = session.earlierSteps,
                    completionFeedback = session.completionFeedback,
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
                                "history=${session.steps.size}",
                        )
                        mainHandler.post {
                            // 게이트는 결과를 통째로 받아 스스로 판정한다 — 분기에서 성공/실패를
                            // 골라 넘기지 않는 이유는 [ErrorNoticeGate.record] KDoc에 있다.
                            errorNotice.record(result)
                            logicalRequests.markHttpResponse(logicalRequest, 200)
                            // **꺼진 화면에는 그리지 않는다**(#56). 나가 있던 요청의 응답이
                            // 여기로 돌아오면 기존 가드 셋이 전부 통과한다 — 세션은 일부러
                            // 살려 두었고, 확인 대기도 아니며, 샘플러가 자고 있어 서명도 그대로다.
                            // 그대로 그리면 잠금화면에 안내창과 중지 칩이 다시 붙고
                            // `visible = true`가 서서 재개 후 redisplay까지 막힌다(3차 리뷰).
                            if (screenOff) {
                                droppedWhileScreenOff = true
                                GuideTelemetry.responseDropped(logicalRequest, "screen_off")
                                return@post
                            }
                            applyAction(request, signature, sentAtMs, result.response, session, logicalRequest)
                        }
                    }

                    is NextStepResult.ApiFailure -> {
                        e2ePublisher?.serverError()
                        logicalRequests.markHttpResponse(logicalRequest, result.statusCode)
                        GuideTelemetry.requestFinished(
                            logicalRequest,
                            outcome = "api_error",
                            durationMs = SystemClock.elapsedRealtime() - startedAt,
                            statusCode = result.statusCode,
                            errorKind = result.error.kind.name,
                            serverRequestId = result.trace.requestId,
                        )
                        when (GuideApiFailurePolicy.actionFor(result)) {
                            GuideApiFailureAction.REQUIRE_LOGIN ->
                                requireFreshLogin(session.generation)
                            GuideApiFailureAction.STOP_DAILY_LIMIT ->
                                stopForDailyLimit(session.generation)
                            GuideApiFailureAction.RETRY ->
                                retryThisScreen(signature.structure, session.generation, result)
                        }
                    }

                    is NextStepResult.HttpFailure -> {
                        e2ePublisher?.serverError()
                        logicalRequests.markHttpResponse(logicalRequest, result.statusCode)
                        GuideTelemetry.requestFinished(
                            logicalRequest,
                            outcome = "http_error",
                            durationMs = SystemClock.elapsedRealtime() - startedAt,
                            statusCode = result.statusCode,
                            serverRequestId = result.trace.requestId,
                        )
                        retryThisScreen(signature.structure, session.generation, result)
                    }

                    is NextStepResult.NetworkFailure -> {
                        e2ePublisher?.serverError()
                        GuideTelemetry.requestFinished(
                            logicalRequest,
                            outcome = "network_error",
                            durationMs = SystemClock.elapsedRealtime() - startedAt,
                            errorKind = "NETWORK",
                            exceptionClass = result.exception.javaClass.simpleName,
                        )
                        retryThisScreen(signature.structure, session.generation, result)
                    }

                    is NextStepResult.ParseFailure -> {
                        e2ePublisher?.responseParseError()
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
                        retryThisScreen(signature.structure, session.generation, result)
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

    /** 백그라운드 서비스는 Activity를 직접 띄우지 않고, 안내를 닫은 뒤 알림 탭으로 복구한다. */
    private fun requireFreshLogin(generation: Long) {
        mainHandler.post {
            if (!GuideSession.isCurrent(generation)) return@post
            authStore?.clearAuthenticationDecision()
            GuideSession.stop()
            GuideNotifier.notifyLoginRequired(this)
            Log.i(TAG, "session authentication required, guide stopped")
        }
    }

    /** 최대 24시간 재시도할 수 없는 하루 한도는 백오프하지 않고 세션을 끝내 즉시 마이크를 푼다. */
    private fun stopForDailyLimit(generation: Long) {
        mainHandler.post {
            if (!GuideSession.isCurrent(generation)) return@post
            GuideSession.stop()
            GuideNotifier.notifyDailyLimit(this)
            Log.i(TAG, "daily request limit reached, guide stopped")
        }
    }

    /**
     * 요청이 실패한 화면은 "안 보낸 것"으로 되돌려 다음 샘플이 다시 시도하게 한다.
     * 이벤트 기반일 때는 다음 이벤트가 재시도를 해줬지만, 이제 화면이 그대로면 이벤트도 오지 않는다.
     *
     * 그리고 **두 번째 연속 실패부터** 오류를 사용자에게 알린다(#29). 실패 분기에서만 불리지만
     * [result]를 그대로 넘겨 게이트가 판정하게 한다 — [ErrorNoticeGate.record] 참고.
     *
     * 첫 실패를 조용히 넘기는 이유는 백오프가 2초라서다 — 순간적인 끊김에도 안내가 2초간 오류
     * 문구로 덮였다 돌아오면, 그 깜빡임이 어르신에게는 안내보다 먼저 눈에 들어온다.
     *
     * 오류는 기존 안내를 **덮는다**. 되살릴 것을 기억하지 않는 이유는 오류가 걷히는 순간
     * 성공 응답이 새 안내를 그리기 때문이다 — 걷힌 뒤 옛 안내를 되살리면 오히려 낡은 지시가 뜬다.
     *
     * [structureSignature]는 **요청을 보낸 시점의 화면**(구조 축, #380)이다. 그 사이 화면이 바뀌고 오버레이가 한 번
     * 걷히면(우리 앱 왕복 등) 재표시가 서명 불일치로 폐기해 백오프가 끝날 때까지 화면이 빈다.
     * 수용한다 — 되살릴 대상은 **떠난 화면의** 오류이고, 새 화면의 요청은 백오프가 지나면
     * 어차피 나간다. 그때 성공하면 안내가, 또 실패하면 오류가 새 서명으로 다시 뜬다
     * (PR #189 리뷰 관찰).
     */
    private fun retryThisScreen(structureSignature: String, generation: Long, result: NextStepResult) {
        mainHandler.post {
            // QUOTA는 서버가 Gemini의 Retry-After를 그대로 넘긴 실제 재개 시각이다. 이걸 무시하고
            // 지수 백오프(최대 30초)만 쓰면, 아직 안 풀린 rate limit 창에 다시 걸려 같은 429가
            // 반복된다(2026-08-18 실기기 재현). 다른 실패(네트워크 등)는 이 값이 없어 기존 그대로다.
            val retryAfterMs = (result as? NextStepResult.ApiFailure)
                ?.error?.retryAfterSeconds
                ?.let { it * 1_000L }
            stability.markFailed(stabilityNowMs(), retryAfterMs)
            val notify = errorNotice.record(result)
            Log.i(
                TAG,
                "retry backoff=${stability.backoffMs()}ms " +
                    "failed_requests=${errorNotice.failureCount()}",
            )
            val messageRes = GuideErrorCopy.messageRes(result)
            if (messageRes == null || !notify) return@post
            // 성공 경로와 같은 이유로 꺼진 화면에는 그리지 않는다(#56). 위 markFailed()가
            // 이미 sentSignature를 비웠으므로 재질의는 그것으로 보장된다.
            if (screenOff) return@post
            // 늦게 온 실패가 새 세션의 화면을 덮지 않게 한다(applyAction과 같은 세대 가드).
            if (!GuideSession.isCurrent(generation)) return@post
            // 확인 대기 중에는 아무것도 덮지 않는다 — 답할 자리가 사라진다(#153).
            if (GuideSession.awaitingConfirm) return@post
            // showError가 null이면 같은 오류가 이미 떠 있다. 다시 그리면 우리 창을 만져
            // 스스로를 깨운다(#153에서 정체 알림이 실제로 그랬다).
            display.showError(structureSignature, getString(messageRes))?.let {
                Log.i(TAG, "server error shown")
                draw(it)
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
        request: NextStepRequest,
        requestSignature: ScreenSig,
        requestSentAtMs: Long,
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

        // 확인 대기 중에 도착한 응답은 답할 자리를 덮는다(오류 경로와 같은 규칙, #153).
        //
        // GD-3까지는 이 가드가 필요 없었다 — 확인 대기를 **여는 것이 이 함수 자신**이라 응답
        // 도착 시점에 대기 중인 경우가 없었다. GD-4는 그 전제를 깬다(#193): 중지 칩은 요청이
        // 나가 있는 동안에도 눌리고(읽는 중 테두리가 도는 그 몇 초가 오히려 그만두고 싶어지는
        // 구간이다), 그 응답이 확인 화면을 덮으면 `awaitingConfirm`만 true로 남아 세션이 갇힌다 —
        // 요청은 막히고, 확인쌍이 사라져 답할 수도 없고, 칩 재탭은 그 플래그에 막힌다.
        //
        // **`markFailed()`는 부르지 않는다.** 그 함수가 `sentSignature`를 비워 재질의를 만드는데,
        // "×는 서버에 다시 묻지 않는다"는 이 화면의 계약과 어긋난다. 화면이 그대로면 ×로 돌아온 뒤
        // 재질의가 없는 것이 맞고, 화면이 바뀌었으면 다음 샘플이 새 서명으로 알아서 묻는다.
        if (GuideSession.awaitingConfirm) {
            GuideTelemetry.responseDropped(logicalRequest, "awaiting_confirm")
            return
        }

        // 세대만 보면 같은 세션 안에서 A 화면의 안내가 B 화면 위에 붙는다(PR #61 리뷰 P1).
        // 버리면 다음 샘플이 B를 새로 물어본다.
        //
        // 대조는 구조 축이다(#380). 내용까지 대조하면 응답 대기 2~5초 사이 광고 배너가 한 번
        // 도는 것만으로 정상 응답이 버려지고, 버린 자리에 재질의가 또 나가 "돈 내고 버리는"
        // 루프가 된다(배민 홈에서 로테이션 3~5초 < 응답 지연이라 영영 안내가 못 뜰 수 있다).
        // 단 요청과 응답 사이 조작이 관측됐으면 내용 차이도 폐기 사유다 — 같은 구조의 **다른**
        // 화면(다른 상품 상세, 다른 대화방)으로 이동한 늦은 응답을 그 화면에 붙이면 안 된다.
        val current = stability.currentSignature()
        if (current != null && current.structure != requestSignature.structure) {
            GuideTelemetry.responseDropped(logicalRequest, "screen_changed")
            return
        }
        if (
            current != null && current.content != requestSignature.content &&
            stability.interactionAfter(requestSentAtMs)
        ) {
            GuideTelemetry.responseDropped(logicalRequest, "content_changed_after_interaction")
            // 옮겨 간 그 화면을 다시 묻게 한다. 버리기만 하면 인과 창이 닫힌 뒤라 다음 샘플이
            // 새 상황이 못 되고, 사용자는 7초 뒤 정체 문구만 본다(#380 리뷰).
            stability.markResponseDiscarded()
            return
        }

        val action = response.action
        if (action.actionType == ActionType.DONE) {
            e2ePublisher?.displayed(
                generation = session.generation,
                requestSignature = requestSignature.structure,
                request = request,
                response = response,
                target = null,
            )
            val completionVerdict = response.completionVerdict
                ?: if (response.verified) CompletionVerdict.VERIFIED else CompletionVerdict.INCONCLUSIVE
            when (
                GuideSession.onDone(
                    session.generation,
                    completionVerdict,
                    response.screenSignature,
                    screen.contentSignature,
                )
            ) {
                // 검증된 완료도 여기서 끝내지 않는다(#139) — 성공 오탐을 사용자에게 한 번 묻는다.
                GuideSession.DoneOutcome.CONFIRM_CONFIDENT -> {
                    Log.i(TAG, "done verified, asking user")
                    draw(display.showConfirm(requestSignature.structure, getString(R.string.overlay_confirm_confident)))
                    // 확인 대기로 들어갔으니 읽는 중은 여기서 끈다. 다음 샘플을 기다리면 그때까지
                    // 브리딩 애니메이터가 헛돈다 — 성공 tone이 먼저 그려져 보이지는 않는다(PR #153 리뷰).
                    updateReading()
                }

                GuideSession.DoneOutcome.CONFIRM_UNSURE -> {
                    // 상한에서도 done으로 끝내지 않는다 — 막힘이 성공으로 보인다. 세션을 살려 두고
                    // 종료 결정을 사용자에게 넘긴다(가르치기, SPEC §2). 확신 있는 경로와 같은
                    // 화면이고 문구만 다르다. 문구 확정은 #29.
                    Log.w(
                        TAG,
                        "done retry cap perScreen=${GuideSession.MAX_UNVERIFIED_DONE} " +
                            "total=${GuideSession.MAX_UNVERIFIED_DONE_TOTAL}, asking user",
                    )
                    draw(display.showConfirm(requestSignature.structure, getString(R.string.overlay_confirm_unsure)))
                    updateReading()
                }

                GuideSession.DoneOutcome.NOTICE_ONLY -> {
                    // 사용자가 이미 아니라고 답한 화면인데 완료 신호가 상한까지 반복됐다.
                    // 같은 질문을 다시 하지 않으면서 재질의도 멈춘다 — 문구만 남기고 칩으로 넘긴다.
                    Log.w(TAG, "done retry cap after reject, notice only")
                    display.showCapNotice(
                        requestSignature.structure,
                        getString(R.string.guide_done_unconfirmed),
                    )?.let(::draw)
                }

                GuideSession.DoneOutcome.RECOVER_CONTRADICTED -> {
                    // 명시 오류·사용자 부정·검증 가능한 반대 상태는 성공 확인으로 올리지 않는다.
                    // 고정 문구만 잠시 남기고 같은 화면을 다시 탐색한다. 원문 근거는 로그에 쓰지 않는다.
                    Log.i(TAG, "done contradicted, retrying")
                    display.showCapNotice(
                        requestSignature.structure,
                        getString(R.string.guide_done_contradicted),
                    )?.let(::draw)
                    stability.markFailed(stabilityNowMs())
                }

                GuideSession.DoneOutcome.NOTICE_CONTRADICTED -> {
                    // 재시도 예산이 끝나도 명시 반대를 성공·불확실 확인 UI로 올리지 않는다.
                    Log.w(TAG, "done contradicted retry cap, notice only")
                    display.showCapNotice(
                        requestSignature.structure,
                        getString(R.string.guide_done_contradicted),
                    )?.let(::draw)
                }

                GuideSession.DoneOutcome.RETRY -> {
                    // 완료 검증(#54)이 화면 근거를 못 찾은 done이다. 조기 종료일 수 있으니 세션을
                    // 두고, 보낸 적 없는 화면으로 되돌려 백오프 뒤 같은 화면을 다시 묻는다(#55).
                    // 이게 없으면 화면이 그대로인 한 다음 요청이 영영 없다 — 무시가 곧 정지다.
                    // 이 백오프는 사실상 2초 고정이다 — 위 markSucceeded()가 failures를 매번
                    // 리셋한다. 상한 3회로 총량이 묶여 있어 수용한다(PR #63 리뷰 관찰).
                    // "unverified"라고 쓰지 않는다 — 사용자가 부정한 화면에서는 검증된 done도
                    // 이 분기로 온다(실기기 로그에서 실제로 헷갈렸다).
                    stability.markFailed(stabilityNowMs())
                    Log.i(TAG, "done not trusted, retry backoff=${stability.backoffMs()}ms")
                }

                null -> GuideTelemetry.responseDropped(logicalRequest, "session_changed")
            }
            // 이 화면의 조작 안내는 끝났다. 앵커를 남기면 다음 안내가 나기 전까지 낡은
            // 슬롯을 가리켜, 강조 복원을 엉뚱하게 막는다(#380).
            guidedTargetNodeId = null
            guidedTargetStableLabel = null
            guidedTargetStructure = null
            return
        }

        val target = action.targetNodeId?.let { nodeId ->
            screen.elements.firstOrNull { it.nodeId == nodeId }
        }
        val bounds = target?.bounds?.takeIf { it.size == 4 }
        guidedTargetLabel = target?.label?.takeIf { it.isNotBlank() }
        // 타깃 앵커(#380). 응답 시점의 슬롯 label을 기억해 두고, 이후 샘플에서 같은 슬롯의
        // label이 달라지면(구조 보존 재정렬) 조작 이벤트 없이도 재질의를 연다.
        guidedTargetNodeId = target?.nodeId
        guidedTargetStableLabel = target?.let { ScreenSignature.stableLabelOf(it) }
        guidedTargetStructure = if (target != null) requestSignature.structure else null
        // 미관측 package는 `null`이 아니라 `""`로 온다(`Screen.packageName` 기본값). 그 화면의
        // 눌림은 판정하지 않는다 — "안내가 없다"와 같은 뜻으로 읽히게 값에 담아 둔다.
        guidedScreenPackage = screen.packageName.takeIf { it.isNotBlank() }
        guidedTargetClickable = target?.clickable == true

        // 안내 하나 = 스텝 하나(C11 개정). **값을 모으기만 한다** — 조회한 target label 말고는
        // 전부 응답 echo이고, 화면이 바뀌었는지는 다음 샘플이 채운다.
        GuideSession.recordStep(
            session.generation,
            requestSignature.structure,
            GuideStep(
                instruction = action.instruction,
                actionType = action.actionType,
                direction = action.direction,
                targetLabel = guidedTargetLabel,
                // label과 같은 조회 결과를 쓴다 — 응답 원문을 그대로 넣으면 대상이 현재
                // 화면에서 안 잡혔을 때 label은 null인데 id만 남아 두 자리가 어긋난다.
                targetNodeId = target?.nodeId,
                // 기대 상태도 id·label과 같은 실제 조회 결과에 묶는다. 응답 원문만 echo하면
                // 대상을 못 찾았을 때 상태만 남아 다음 완료 판정의 다른 요소와 결합된다(#424).
                expectedTargetState = action.expectedTargetStateFor(target),
                screenHint = action.screenSummary.takeIf { it.isNotBlank() },
                structureSignature = response.screenSignature,
                // 이 안내가 나간 화면의 내용 축(#410). 요청을 만들 때 계산한 값을 도로 읽어
                // 현재 화면과 스텝이 같은 값을 들게 한다 — 여기서 다시 만들면 그사이 화면이
                // 흔들린 경우 두 자리가 갈린다.
                contentSignature = screen.contentSignature,
            ),
        )

        val edge = EdgeGesture.specFor(action.actionType)
        if (edge != null) {
            // **miss가 아니다**(#222). 이 둘은 가리킬 노드가 애초에 없고 강조 좌표를 앱이 만든다.
            // W로 찍으면 실기기 검증에서 진짜 miss와 섞여, 고칠 것이 없는 줄을 매번 다시 쫓는다
            // (HANDOFF §9가 "유일한 MISS"로 적어 둔 자리가 여기다).
            Log.i(TAG, "edge gesture edge=${edge.edge} finger=${edge.fingerDirection}")
        } else if (bounds == null) {
            // 화면에 없는 node_id를 고르는 것은 경량 모델의 전형적 실수다. 안내문은 그대로 띄우되
            // 로그로 남긴다 — 앱 검증이 logcat 수동인 동안 이게 이 실패를 볼 유일한 창이다.
            Log.w(TAG, "highlight miss target=${action.targetNodeId ?: "-"}")
        } else {
            // bounds는 화면 위 좌표라 release에 남기지 않는다(#58). 구현이 variant마다 다르다.
            GuideScreenLog.highlight(target?.nodeId, bounds)
        }
        // 서버 방향(보러 가는 쪽) → 손가락 방향. **변환은 여기 한 곳에서만** 한다(C17). 아래로 흘러가는 값은
        // 이미 손가락 방향이며 뷰는 그대로 그린다 — 뷰에서 또 뒤집으면 보정이 두 번 걸린다.
        val fingerDirection = action.direction?.let { GestureMotion.fingerDirectionFor(it) }
        // 응답은 위 서명 대조로 requestSignature 화면에만 반영되므로, 안내도 그 화면 소속이다.
        // 소속도 구조 축이다(#380) — 내용까지 묶으면 배너가 도는 화면에서 재표시·복원이 죽는다.
        draw(
            display.show(
                requestSignature.structure,
                bounds,
                action.instruction,
                action.actionType,
                fingerDirection,
                action.inputText,
            ),
        )
        e2ePublisher?.displayed(
            generation = session.generation,
            requestSignature = requestSignature.structure,
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
        // 요소별 label·bounds는 **화면 내용 그 자체**라 main에서 찍지 않는다(#58).
        // debug 구현만 상세를 남기고 release 구현은 no-op이다. 위 요약 줄은 패키지명과 개수뿐이라
        // 비식별이므로 여기 남긴다 — release에서도 "몇 개짜리 화면을 봤나"는 볼 수 있어야 한다.
        GuideScreenLog.elements(screen)
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
        // **리시버와 예약된 샘플을 먼저 걷는다.** 아래 startActivity가 BAL로 던지면 이 둘이
        // 등록된 채 남는다(3차 리뷰). 알림 판정과 무관해서 앞으로 올릴 수 있다.
        //
        // **창 제거(destroy)는 여기로 올리지 않는다** — 아래 when이 `GuideSession.isActive`를
        // 읽으므로 stop()은 뒤여야 하고, 창을 미리 걷으면 복귀가 기대는
        // `BAL_ALLOW_NON_APP_VISIBLE_WINDOW`를 스스로 없앤다(4차 리뷰).
        cancelSample()
        // 복귀 확인도 같이 걷는다 — 서비스가 풀렸으면 「떴는가」를 물을 대상이 없다.
        returnVerify?.let { mainHandler.removeCallbacks(it) }
        returnVerify = null
        runCatching { unregisterReceiver(screenReceiver) }
            .onFailure { Log.w(TAG, "screen receiver 해제 실패", it) }

        // 안내를 받던 중이었다면 그 사실을 알린다(#29·#190). 판정은 [serviceOffAction]에 있다.
        // **여기서는 화면을 바꾸지 않는다** — 예전의 자동 복귀는 오버레이 권한이 주던 BAL 면제로만
        // 성립했고, 그 권한을 걷어내며 알림 탭으로 옮겼다(#212, `ServiceOffAction` KDoc의 실측).
        // 토스트도 쓰지 않는다: 이 갈래는 우리가 백그라운드일 때만 생기고 그때 토스트는 억제된다.
        when (serviceOffAction(GuideSession.isActive)) {
            ServiceOffAction.NOTIFY -> {
                Log.w(TAG, "service unbound during session, notifying")
                GuideNotifier.notifyGuideStopped(this, R.string.guide_service_off)
            }

            ServiceOffAction.SILENT -> Unit
        }
        // 서비스가 내려가면 안내할 수단이 없다. 세션을 남겨두면 화면에는 "안내 중"인데
        // 아무 일도 일어나지 않는 상태가 된다.
        GuideSession.removeListener(sessionListener)
        GuideSession.stop()
        // 창을 걷지 않으면 서비스가 꺼진 뒤에도 유령 오버레이가 화면에 남는다.
        overlay?.destroy()
        overlay = null
        guideApiClient = null
        authStore = null
        e2ePublisher = null
        apiExecutor.shutdownNow()
        return super.onUnbind(intent)
    }

    companion object {
        const val TAG = "GuideSvc"

        /**
         * 접근성 복귀를 요청한 뒤 **떴는지 확인하기까지**(#451). 실측에서 1초 안에 떴으므로
         * 두 배로 잡는다 — 재기만 하는 값이라 길어도 잃는 것이 없다.
         */
        private const val RETURN_VERIFY_DELAY_MS = 2_000L

        /**
         * [findEditableNode]의 순회 상한. 직렬화의 요소 상한과 같은 정신이다 — 클릭 디스패치
         * 안에서 도는 탐색이라 병적인 트리에 붙잡히면 안 된다. 폴백(입력 포커스)이 있어
         * 놓쳐도 실패가 아니다.
         */
        private const val INPUT_FILL_MAX_NODES = 500

        /**
         * 이벤트가 몰아친 뒤 이만큼 조용해야 화면을 뜬다. 전환 애니메이션 중에는 샘플이 계속
         * 달라지므로 이 값이 곧 "안정 판정 간격"이 된다(#32).
         *
         * **조용해지지 않는 화면이 있다** — 그때는 [SampleSchedule.MAX_DEFER_MS]가 이긴다(#380).
         */
        internal const val SETTLE_MS = 350L

        /**
         * 안정된 뒤의 감시 주기. **폴링이 곧 호출량은 아니다** — 화면이 그대로면 서버를 부르지 않고
         * 트리만 다시 뜬다(#39). HANDOFF의 첫 추정치와 같은 값이며 근거를 갖고 다시 정할 것.
         */
        internal const val POLL_MS = 700L


        /** 포커스·IME 창 이벤트도 같은 settle 예약 경로로 합친다(#112). */
        internal fun isInterestingEvent(eventType: Int): Boolean =
            eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
                eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
                eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
                // 안내한 대상을 실제로 눌렀는지 보려고 받는다(#238). 진행 판정에는 쓰지 않는다.
                eventType == AccessibilityEvent.TYPE_VIEW_CLICKED
    }
}
