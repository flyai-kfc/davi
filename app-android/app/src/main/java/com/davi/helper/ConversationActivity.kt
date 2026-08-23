package com.davi.helper

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.SpeechRecognizer
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import com.davi.helper.databinding.ActivityConversationBinding
import java.util.concurrent.Executors
import kotlin.system.measureTimeMillis

/** 계측이 앱 화면만 검증할 때 쓰는 debug 전용 진입점. release에서는 항상 무시된다. */
internal const val EXTRA_INSTRUMENTATION_SKIP_ACCESSIBILITY =
    "com.davi.helper.extra.INSTRUMENTATION_SKIP_ACCESSIBILITY"
internal const val EXTRA_INSTRUMENTATION_SKIP_SPEECH_RECOGNIZER =
    "com.davi.helper.extra.INSTRUMENTATION_SKIP_SPEECH_RECOGNIZER"

/**
 * MA-3 구체화 화면을 **서버 없이** 띄우는 debug 전용 진입점(#374 · #323과 같은 성격).
 *
 * 이 화면의 모양은 **보기 셋을 전제로** 만들어져 있는데(뒤판·아이콘 슬롯이 전부 보기용),
 * 보기는 서버가 `options`로 준다. 서버가 빈 배열을 주면 자유 응답으로 떨어져
 * **디자인을 눈으로 확인할 방법이 없다.** 실기기 확인을 서버 상태에 걸어 두지 않기 위한 것이다.
 *
 * **화면 상태만 만든다.** 실제 대화 세션은 시작하지 않으므로 보기를 눌러도 서버로 답이
 * 가지 않는다 — 모양을 보기 위한 자리이지 흐름을 흉내 내는 자리가 아니다.
 *
 *     adb shell am start -n com.davi.helper.debug/com.davi.helper.ConversationActivity \
 *       --es com.davi.helper.extra.DEBUG_CLARIFICATION "카카오톡|문자 메시지|인스타그램" \
 *       --es com.davi.helper.extra.DEBUG_CLARIFICATION_PROMPT "어느 앱으로 사진을 보낼까요?"
 */
internal const val EXTRA_DEBUG_CLARIFICATION =
    "com.davi.helper.extra.DEBUG_CLARIFICATION"
internal const val EXTRA_DEBUG_CLARIFICATION_PROMPT =
    "com.davi.helper.extra.DEBUG_CLARIFICATION_PROMPT"

/** 질문 개수. 1이면 칩이 숨는 경우를 볼 수 있다(`--ei ... 1`). 기본 2. */
internal const val EXTRA_DEBUG_CLARIFICATION_COUNT =
    "com.davi.helper.extra.DEBUG_CLARIFICATION_COUNT"

/** Activity가 리소스를 읽기 전 배율을 주입하는 debug 계측 전용 메모리 값. */
internal object ConversationInstrumentationConfiguration {
    @Volatile
    var fontScale: Float? = null
}

/** 구체화 답을 보존하고 선택 화면으로 돌아가도 되는, 사용자 무발화 계열 오류만 고른다. */
internal fun canResumeClarificationAfterSpeechError(error: Int): Boolean =
    error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT

/**
 * 음성 대화 화면 — 홈(MA-1)부터 구체화·안전 종료·최종 확인·실패까지 한 레이아웃에서
 * 렌더한다(#123, 임시 와이어프레임 디자인). 음성 파이프라인은 기존 개발 화면
 * (debug MainActivity)에서 이식 — 발화·질문·goal은 화면과 메모리에만 두고 logcat에
 * 넘기지 않는다. ○ 확인 시 GuideSession을 시작하고 런처로 나간다(안내는 런처에서 시작).
 * 사용자를 대신해 조작하지 않는다.
 */
class ConversationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConversationBinding
    private val voiceSession = VoiceDialogueSession()
    private val voiceRequestTracker = LogicalRequestTracker()
    private val appResolutionRequestTracker = LogicalRequestTracker()
    private val voiceExecutor = Executors.newSingleThreadExecutor()
    private var speechRecognizerHelper: SpeechRecognizerHelper? = null
    private var textToSpeechHelper: TextToSpeechHelper? = null
    private var audioPermissionRequested = false

    /**
     * 이 인스턴스에서 알림 권한을 이미 물었는가(#212 · PR #240 리뷰).
     *
     * **영구 거부 상태에서는 요청이 화면 없이 즉시 거부된다** — 그래서 다시 물어도 보이지 않지만,
     * `onResume`마다 부르는 것은 의미가 없어 인스턴스당 한 번으로 묶는다.
     * `shouldShowRequestPermissionRationale`로 가르지 않는 이유도 그것이다: 그 값은 **한 번 거부**
     * 에서만 true고 영구 거부에서는 다시 false라 "이미 거부했다"의 판정으로 쓸 수 없다.
     */
    private var notificationPermissionRequested = false

    private var uiState: ConversationUiState = ConversationUiState.Home()

    /** 누를 수 없는 동안의 마이크 글리프 불투명도(#172). 근거는 dimens.xml 주석. */
    private val micDisabledImageAlpha by lazy {
        resources.getInteger(R.integer.davi_mic_disabled_image_alpha)
    }

    // 스스로 끝나지 않는 상태(실패·최종 확인)의 홈 복귀 타이머.
    // setState()가 상태 전이마다 제거하므로 유령 타이머가 없다.
    private val autoReturnHandler = Handler(Looper.getMainLooper())
    private val autoReturnRunnable = Runnable {
        // 홈으로 가는 다른 경로(× 칩·확인쌍 ×·onPause)와 같은 정리를 거친다. Confirm에서
        // TTS 완료 콜백이 오지 않으면 세션이 READY로 살아 있어, 안 접으면 홈 화면에서
        // 계속 말하는 상태가 된다.
        cancelVoiceDialogue()
        setState(ConversationUiState.Home())
    }

    // 마이크가 실제로 열릴 때까지 "듣고 있어요"를 미루는 구간의 상한(#273).
    // 준비 완료 신호는 인식기 구현에 달려 있어 보장으로 삼지 않는다 — 안 오면 라벨이
    // 영영 안 뜨는 게 최악이라, 이 타이머가 도는 최악의 경우를 **이 PR 이전 동작**으로 둔다.
    private val micTone = MicTone()

    /** 이번 듣기 턴에서 차임이 아직 안 울렸는가. [startListeningTurn]이 무장한다. */
    private var micToneArmed = false

    /** 바로가기 행에 아이콘을 이미 붙인 패키지. 같은 앱이면 다시 조회하지 않는다(#348). */
    private var shownShortcutPackage: String? = null
    private val micReadyHandler = Handler(Looper.getMainLooper())
    private val micReadyFallback = Runnable { markMicReady("mic_ready_fallback") }

    /** 종료 확인 모달로 포커스를 한 번만 옮기기 위한 값. 렌더는 여러 번 돈다. */
    private var stopConfirmAnnounced = false

    /** 질문 카드 스크롤을 되돌릴지 판단하는 값 — 문구가 실제로 바뀔 때만 맨 위로 보낸다. */
    private var lastQuestionKey: Any? = null

    @Volatile
    private var destroyed = false

    private val authStore: AuthStore by lazy { AuthStore(this) }
    private val sessionLoginRecovery: SessionLoginRecovery by lazy {
        SessionLoginRecovery(this, authStore, ::cancelVoiceDialogue)
    }

    // 안내 세션 시작·종료를 홈 렌더(안내 중지 버튼·mic 잠금)에 반영한다.
    // 안내 세션은 **밖에서** 끝난다(사용자가 대상 앱에서 완료 · 권한 소실 · 다른 종료 경로).
    // 그때 종료 확인이 떠 있으면 **끝낼 안내가 없는데 묻고 있는** 상태가 되므로 홈으로 내린다.
    // 나머지 상태는 세션 유무로 그림만 달라지면 되니 다시 그리기만 한다.
    private val sessionListener = GuideSession.Listener {
        runOnUiThread {
            if (uiState is ConversationUiState.StopConfirm && !GuideSession.isActive) {
                setState(ConversationUiState.Home())
            } else {
                render(uiState)
            }
        }
    }

    private val slotFillApiClient: SlotFillApiClient by lazy {
        SlotFillApiClient(
            BuildConfig.SERVER_URL,
            devAccessToken = BuildConfig.DEV_ACCESS_TOKEN,
            sessionToken = { authStore.sessionToken(System.currentTimeMillis() / 1000) },
        )
    }

    private val appResolutionApiClient: AppResolutionApiClient by lazy {
        AppResolutionApiClient(
            BuildConfig.SERVER_URL,
            devAccessToken = BuildConfig.DEV_ACCESS_TOKEN,
            sessionToken = { authStore.sessionToken(System.currentTimeMillis() / 1000) },
        )
    }

    private val launchableAppIndex: LaunchableAppIndex by lazy {
        LaunchableAppIndex(AndroidLaunchableAppSource(applicationContext))
    }
    private val appResolutionFlow: AppResolutionFlow by lazy {
        AppResolutionFlow(
            launchableAppIndex,
            appResolutionApiClient,
            appResolutionRequestTracker,
        )
    }

    /**
     * 안내가 멈춘 것을 알릴 유일한 수단이 알림이라 한 번 묻는다(#212).
     *
     * **결과를 보지 않는다** — 거부해도 진행을 막지 않는다. 거부하면 그 갈래가 조용해질 뿐이고
     * (지금도 그렇다), 사용자가 앱을 열면 [onResume]의 권한 재판정이 여전히 잡아낸다.
     */
    private val requestPostNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val requestRecordAudioPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            audioPermissionRequested = true
            if (granted) beginVoiceDialogue()
            else setState(ConversationUiState.Home(statusRes = R.string.voice_permission_denied))
        }

    private val voiceRecognitionListener = object : SpeechRecognizerHelper.Listener {
        override fun onPartialResult(text: String) {
            runOnUiThread {
                // 사용자에게만 보이는 일시적 받아쓰기. 저장·로그하지 않는다.
                val state = uiState
                if (state is ConversationUiState.Listening) {
                    setState(state.copy(partial = text))
                }
            }
        }

        override fun onFinalResult(text: String) {
            runOnUiThread {
                val state = uiState
                if (state is ConversationUiState.Listening && state.clarification != null) {
                    submitClarificationAnswer(ClarificationAnswerSource.FREEFORM, text)
                } else {
                    submitVoiceTurn(text)
                }
            }
        }

        override fun onError(error: Int) {
            runOnUiThread { handleSpeechError(error) }
        }

        // 인식기를 새로 붙는 중 — 마이크는 아직 닫혀 있다. **폴백 재시작도 여기로 온다**
        // (#273): 완료 신호를 이미 받은 뒤 온디바이스가 끊기면 라벨만 켜진 채 마이크가
        // 닫히므로, 그 경우 라벨을 도로 내린다.
        override fun onMicPreparing() {
            runOnUiThread {
                val state = uiState as? ConversationUiState.Listening ?: return@runOnUiThread
                if (state.micReady) setState(state.copy(micReady = false))
                micReadyHandler.removeCallbacks(micReadyFallback)
                micReadyHandler.postDelayed(micReadyFallback, MIC_READY_FALLBACK_MS)
            }
        }

        override fun onMicReady() {
            runOnUiThread { markMicReady("mic_ready") }
        }

        // 화면을 바꾸지 않는다 — 이 신호가 이르다는 것이 #273의 실측이다. 남기는 이유는
        // 아래 mic_ready와의 간격이 기기·인식기별 차이를 보여 주기 때문이다.
        override fun onEndpointerReady() {
            runOnUiThread { GuideTelemetry.voiceState("endpointer_ready") }
        }

        override fun onRmsChanged(rmsdB: Float) {
            // 프레임마다 오는 값이라 setState를 거치지 않고 뷰에 곧장 넘긴다 — 상태가 바뀐 게
            // 아니므로 render()를 다시 돌릴 이유가 없다. 값 정리·수렴은 뷰 쪽에서 한다(#132).
            runOnUiThread {
                binding.voiceWave.onRmsChanged(rmsdB)
                binding.glowMic.onRmsChanged(rmsdB)
            }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        val testFontScale = if (BuildConfig.DEBUG) {
            ConversationInstrumentationConfiguration.fontScale
        } else {
            null
        }
        if (testFontScale != null && testFontScale > 0f) {
            val configuration = Configuration(newBase.resources.configuration).apply {
                fontScale = testFontScale
            }
            super.attachBaseContext(newBase.createConfigurationContext(configuration))
        } else {
            super.attachBaseContext(newBase)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConversationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // 파형 계산을 미리 해 둔다 — 처음 울릴 때 메인 스레드에서 하면 소리가 늦다(#313).
        micTone.prepare()
        launchableAppIndex.start()
        audioPermissionRequested = savedInstanceState?.getBoolean(AUDIO_PERMISSION_REQUESTED) ?: false
        notificationPermissionRequested =
            savedInstanceState?.getBoolean(NOTIFICATION_PERMISSION_REQUESTED) ?: false

        // 3버튼 내비게이션에서 시스템이 내비 바에 스크림을 깔아 배경을 가린다(PR #162 리뷰 실측:
        // y=2178부터 54dp가 흰 띠). 배경이 화면 끝까지 깔리려면 그 대비 강제를 꺼야 한다.
        //
        // API 29+에만 있는 설정이지만 "API 분기가 필요한 기법"을 채택하는 것은 아니다 —
        // 26~28에는 이 강제 자체가 없어 그냥 동작한다. 즉 신버전에서 생긴 문제를 신버전에서
        // 끄는 것이고, 구버전 경로가 열등해지지 않는다(SpeechRecognizerHelper의 온디바이스
        // 분기와 같은 성격).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        // targetSdk 35+ edge-to-edge: 시스템 바 인셋을 마진으로 반영(#78 패턴)
        // 배경(MeshBackgroundView)은 이 층 밖에 있어 인셋을 받지 않는다 — 화면 끝까지 깔린다.
        ViewCompat.setOnApplyWindowInsetsListener(binding.conversationInsets) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = bars.left
                topMargin = bars.top
                rightMargin = bars.right
                bottomMargin = bars.bottom
            }
            WindowInsetsCompat.CONSUMED
        }

        binding.btnMic.setOnClickListener { onMicTapped() }
        binding.btnClose.setOnClickListener {
            cancelVoiceDialogue()
            setState(ConversationUiState.Home())
        }
        // MA-4 확인 버튼 셋(#348). (3)은 안내하지 않고 홈으로, (1)은 홈 화면부터 안내,
        // (2)는 앱을 열고 그 앱 안 다음 걸음부터 안내한다.
        binding.confirmReject.setOnClickListener {
            cancelVoiceDialogue()
            setState(ConversationUiState.Home())
        }
        binding.confirmAccept.setOnClickListener { onConfirmAccepted() }
        binding.shortcutRow.setOnClickListener { openShortcut() }
        // 메인 9 — **"이어서하기"는 상태를 바꾸지 않는다.** 앱에서 나가기만 하고 안내는
        // 그대로 돈다. 하단 안내가 같은 동작을 뒤로가기로 설명하는 것과 짝이고, 이 버튼은
        // 뒤로가기를 모르는 사람을 위한 지름길이다.
        binding.stackedPair.btnStackedSecondary.setOnClickListener { onStackedSecondaryTapped() }
        binding.stackedPair.btnStackedPrimary.setOnClickListener { onStackedPrimaryTapped() }
        val optionButtons = listOf(
            binding.clarificationChoices.btnOption1,
            binding.clarificationChoices.btnOption2,
            binding.clarificationChoices.btnOption3,
        )
        optionButtons.forEachIndexed { index, button ->
            button.setOnClickListener {
                val state = uiState as? ConversationUiState.Clarifying ?: return@setOnClickListener
                val option = state.outcome.question.options.getOrNull(index) ?: return@setOnClickListener
                submitClarificationAnswer(ClarificationAnswerSource.OPTION, option)
            }
        }
        binding.clarificationChoices.btnDirectSpeak.setOnClickListener {
            beginClarificationSpeech()
        }
        // **「직접 말하기」의 글로우**(#374 시안 `#F70824@22 y8 b18`).
        //
        // **채움을 밝게 하거나 투명하게 하지 않는다** — 밝은 빨강 위에는 흰 글자가 안 읽히고
        // (color/highlight #F2001C는 4.38:1), 투명은 배경 베일이 비쳐 2.7~3.2:1까지 내려간다.
        // **밝기는 글자가 없는 자리에서 낸다** — 버튼 둘레로 번지는 이 빛이 그 자리다.
        //
        // elevation 그림자의 **색을 바꿔** 만든다. layer-list로는 blur가 없어 단단한 띠가
        // 되고(bg_davi_card 주석), 별도 View를 깔면 버튼과 터치 영역이 갈린다.
        // **API 28부터다** — 그 아래에서는 공통 검정 그림자만 남고 버튼은 그대로 성립한다.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val glow = getColor(R.color.davi_choice_primary_glow)
            binding.clarificationChoices.btnDirectSpeak.outlineSpotShadowColor = glow
            binding.clarificationChoices.btnDirectSpeak.outlineAmbientShadowColor = glow
        }
        // 메인 10 — ×는 확인을 접고 메인 9로, ○는 안내를 끝내고 홈으로.
        // **기본 설명이 정반대다.** 이 컴포넌트는 최종 확인 기준으로 x가 "처음부터 다시",
        // o가 "이대로 안내 시작"인데 여기서는 x가 안내를 계속하고 o가 끝낸다. 화면 문구가
        // 뜻을 부르는 시각 사용자와 달리 스크린리더 사용자는 이 값만 듣는다(#197 리뷰).
        binding.stopConfirmPair.btnConfirmReject.contentDescription =
            getString(R.string.conversation_stop_confirm_reject_desc)
        binding.stopConfirmPair.btnConfirmAccept.contentDescription =
            getString(R.string.conversation_stop_confirm_accept_desc)
        binding.stopConfirmPair.btnConfirmReject.setOnClickListener {
            setState(ConversationUiState.Home())
        }
        binding.stopConfirmPair.btnConfirmAccept.setOnClickListener {
            GuideSession.stop()
            setState(ConversationUiState.Home())
        }

        // 질문 카드는 maxHeight로 묶여 있어 긴 문구(특히 최종 확인의 goal)는 카드 안에서
        // 스크롤해 읽는다. 상태가 바뀌면 render()가 맨 위로 되돌린다.
        binding.cardQuestion.movementMethod = ScrollingMovementMethod.getInstance()
        // **포커스를 도로 뺏는다.** `setMovementMethod`가 TextView를 focusable로 만드는데,
        // 포커스를 잡는 순간 TextView가 **글자 끝으로 스크롤한다** — 배율 2.0에서 goal의
        // **첫 줄이 통째로 밀려 나갔다**(2026-08-22 실기기: 접근성 서비스를 켠 뒤부터 재현).
        // 문장 앞이 사라지는 것은 뒤가 잘리는 것보다 나쁘다.
        //
        // **드래그 스크롤은 그대로 된다** — TextView.onTouchEvent가 movementMethod에
        // 넘기는 데 포커스가 필요 없다. 이 카드는 입력란이 아니라 읽는 글이라 포커스를
        // 받을 이유가 없고, 스크린리더는 accessibilityLiveRegion으로 따로 읽는다.
        // **XML로는 못 막는다** — setMovementMethod가 나중에 덮으므로 여기서 꺼야 한다.
        binding.cardQuestion.isFocusable = false
        binding.cardQuestion.isFocusableInTouchMode = false

        // MA-3 모양을 서버 없이 보기 위한 debug 전용 상태 주입(#374). release에서는 무시된다.
        debugClarificationState()?.let { setState(it) }


        render(uiState)
    }

    /**
     * 이미 떠 있는 화면에 debug 주입이 다시 오면 받는다(#374).
     * **재실행 없이 값을 바꿔 볼 수 있어야** 디자인 확인이 빠르다 — 매번 앱을 죽였다 켜면
     * 보기 하나 바꾸는 데 몇 십 초가 든다. release에서는 [debugClarificationState]가 null이다.
     */
    override fun onNewIntent(newIntent: Intent) {
        super.onNewIntent(newIntent)
        intent = newIntent
        debugClarificationState()?.let { setState(it) }
    }

    /** 권한·세션 상태는 화면 밖에서 바뀐다 — 돌아올 때마다 다시 본다. */
    override fun onResume() {
        super.onResume()
        // 접근성은 밖에서 꺼질 수 있다(설정 회수·앱 업데이트). 없는 채로 ○를 누르면
        // GuideSession은 시작되는데 화면을 못 읽어 사용자가 런처에 혼자 남는다 —
        // 시작 자체를 막고 온보딩으로 되돌린다(SCREENS ON-2 상태③).
        //
        // 이 복귀는 화면을 바꾸므로 **안내창으로는 알릴 수 없다** — 토스트로 알린다(#29).
        // 지시("다시 켜 주세요")의 대상은 곧 뜰 온보딩 화면의 버튼이다.
        //
        // **예전에는 오버레이 권한도 같이 봤다**(#212 이전). 그쪽만 회수되는 갈래가 있어서
        // 여기서 세션을 끊어야 했는데 — 서비스는 살아 있어 화면 직렬화와 서버 호출이 계속되고
        // 안내만 안 그려졌다 — 그 권한을 걷어내며 그 갈래 자체가 없어졌다.
        val instrumentationUiTest = BuildConfig.DEBUG &&
            intent.getBooleanExtra(EXTRA_INSTRUMENTATION_SKIP_ACCESSIBILITY, false)
        if (!canStartGuide() && !instrumentationUiTest) {
            // 접근성 회수는 onUnbind도 정리하지만, 그 콜백보다 이 화면이 먼저 살아나는 경합이
            // 있어 여기서도 끊는다. 두 번 불려도 GuideSession.stop()은 멱등이다.
            GuideSession.stop()
            Toast.makeText(this, R.string.guide_permission_lost, Toast.LENGTH_LONG).show()
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }
        // **여기가 알림 권한을 묻는 자리다**(#212 · PR #240 리뷰). 온보딩에서 물으면 접근성이
        // 이미 켜진 기기에서 그 화면이 같은 프레임에 finish()돼 대화상자가 함께 사라진다 —
        // 하필 이 알림이 필요한 사람이 그 사람이다(알림은 "안내 중 접근성이 꺼졌을 때" 뜬다).
        // 이 화면은 사라지지 않고, **모든 안내 세션이 여기서 시작하므로** 묻는 시점이 항상
        // 세션보다 앞선다. 안내가 도는 중에는 사용자가 다른 앱에 있어 물을 화면 자체가 없다.
        askPostNotificationsOnce()
        GuideSession.addListener(sessionListener)
        render(uiState)
    }

    /** 인스턴스당 한 번만 묻는다. 판정 근거는 [notificationPermissionRequested]에 있다. */
    private fun askPostNotificationsOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (notificationPermissionRequested) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) return
        notificationPermissionRequested = true
        requestPostNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(AUDIO_PERMISSION_REQUESTED, audioPermissionRequested)
        outState.putBoolean(NOTIFICATION_PERMISSION_REQUESTED, notificationPermissionRequested)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        GuideSession.removeListener(sessionListener)
        // 화면을 떠나면 대화를 접는다 — Confirm의 goal도 함께 사라진다(임시 디자인에서
        // 수용, 디자인 확정 시 재검토). 복귀 시 유령 상태가 없도록 홈으로 되돌린다.
        cancelVoiceDialogue()
        if (uiState !is ConversationUiState.Home) {
            setState(ConversationUiState.Home())
        }
        super.onPause()
    }

    override fun onDestroy() {
        destroyed = true
        autoReturnHandler.removeCallbacks(autoReturnRunnable)
        micReadyHandler.removeCallbacks(micReadyFallback)
        speechRecognizerHelper?.stop()
        textToSpeechHelper?.shutdown()
        micTone.release()
        launchableAppIndex.stop()
        voiceExecutor.shutdownNow()
        super.onDestroy()
    }

    /** 상태 전이의 단일 지점 — 복귀 타이머 정리와 렌더가 항상 함께 간다. */
    /**
     * [EXTRA_DEBUG_CLARIFICATION]이 오면 그것으로 만든 구체화 상태. 아니면 null.
     * **`BuildConfig.DEBUG`가 아니면 항상 null이다** — release에 진입점을 남기지 않는다.
     */
    private fun debugClarificationState(): ConversationUiState.Clarifying? {
        if (!BuildConfig.DEBUG) return null
        val raw = intent.getStringExtra(EXTRA_DEBUG_CLARIFICATION) ?: return null
        val options = raw.split('|').map(String::trim).filter(String::isNotEmpty).take(3)
        val prompt = intent.getStringExtra(EXTRA_DEBUG_CLARIFICATION_PROMPT)
            ?: "어느 앱으로 사진을 보낼까요?"
        GuideTelemetry.voiceState("debug_clarification_injected")
        return ConversationUiState.Clarifying(
            VoiceDialogueSession.Outcome.Clarify(
                generation = 0L,
                question = ClarificationQuestion(
                    id = "debug",
                    prompt = prompt,
                    options = options,
                    allowFreeform = true,
                ),
                questionIndex = 0,
                questionCount = intent.getIntExtra(EXTRA_DEBUG_CLARIFICATION_COUNT, 2),
            ),
            // 읽어 주지 않는다 — 모양만 보는 자리다.
            speaking = false,
        )
    }

    private fun setState(state: ConversationUiState) {
        // **상태 전이를 남긴다**(#197 리뷰). 안내 중·종료 확인은 사용자가 눈으로만 확인할 수
        // 있어 회귀가 나도 로그로 못 짚었다. **이름과 세션 유무뿐이다** — 발화·질문·goal은
        // 여기에 오지 않는다(기존 금지 그대로).
        if (state::class != uiState::class || state is ConversationUiState.Home) {
            GuideTelemetry.screenState(state.javaClass.simpleName, GuideSession.isActive)
        }
        uiState = state
        // 듣는 중을 벗어나면 준비 상한도 함께 내린다 — 다른 화면에서 라벨이 뒤늦게 뜨지 않게.
        // 듣는 중 → 듣는 중(부분 인식 갱신)에서는 그대로 둔다. 매번 다시 걸면 상한이 밀린다.
        if (state !is ConversationUiState.Listening) micReadyHandler.removeCallbacks(micReadyFallback)
        autoReturnHandler.removeCallbacks(autoReturnRunnable)
        autoReturnDelayMs(state)?.let { autoReturnHandler.postDelayed(autoReturnRunnable, it) }
        render(state)
    }

    /** 렌더 판단은 renderModelOf(순수 함수)에 있고 여기는 대입만 한다. */
    private fun render(state: ConversationUiState) {
        val model = renderModelOf(state, guideActive = GuideSession.isActive)
        // 대화 중에는 화면을 켜 둔다. 어르신이 무엇을 말할지 생각하는 20~30초 동안 손이
        // 화면에 닿지 않아 디스플레이 타임아웃이 걸리면, onPause가 왕복 전체를 접는다.
        if (state is ConversationUiState.Home) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        // **안내 중에도 유연 여백을 켠다**(#197). 남는 높이가 있으면 위아래로 나눠 먹어
        // 내용이 가운데로 내려오고, 배율이 커져 남는 게 없으면 저절로 0이 된다 —
        // 배율마다 다른 값을 두지 않고 한 구조로 양쪽을 만족시킨다.
        // (1.0배율에서 아래가 177dp 비어 있었고, 2.0에서는 0이다.)
        // **위아래로 나눠 먹는 것은 안내(메인 9)뿐이다** — 홈은 아래 짝을 걷었다(#231, 아래 참고).
        binding.spaceFlexTop.isVisible(model.homeVisible || model.guidingVisible)
        // **홈에서는 아래 빈칸을 걷는다**(#231) — 남는 세로를 아래가 나눠 가지면 말풍선과
        // 마이크 사이가 시안(48)과 무관하게 벌어진다. 홈은 마이크가 바닥에 못 박혀 있어
        // 아래 여백(davi_home_control_reserve)이 그 자리를 대신한다.
        binding.spaceHomeMid.isVisible(model.guidingVisible)
        // **확인 화면에서 남는 높이는 버튼 셋 아래에 남긴다**(#378, 2026-08-23 시안 담당 결정).
        //
        // 한때 이 Space를 켜서 슬랙을 **카드와 (1) 사이**로 몰았다(#351 리뷰) — 「(3)의 바닥은
        // 다른 화면의 마이크 바닥과 같은 선」이라는 시안 근거였다. 그런데 **시안 프레임은 620이고
        // 실기기는 780dp**라, 세로 거리 1:1에서 **시안에 없는 160dp**가 남는다. 그것이 전부
        // 가운데로 가면 기본 배율에서 카드와 (1) 사이가 **150dp 비었다**(실기기 실측).
        //
        // **가운데가 비면 「덜 그려진 화면」으로 읽힌다.** 아래가 비는 것은 다르다 — 그 자리는
        // 원래 마이크 자리라 「여기엔 마이크가 없다」로 읽히고 어르신이 거기서 무엇을 기다리지
        // 않는다. **「같은 바닥선」보다 「가운데가 비지 않는 것」이 먼저다.**
        //
        // **포기한 것을 숨기지 않는다** — (3)의 바닥이 마이크 바닥선보다 위에 서고, (2)가
        // 숨으면 (1)(3)이 96dp 위로 뛴다. (3)이 늘 카드를 따라오므로 **내용에 대해서는**
        // 일관적이다. 되돌리려면 #378의 근거를 먼저 뒤집어야 한다.
        binding.spaceConfirmFlex.isVisible(false)
        binding.homeHead.isVisible(model.homeVisible)
        binding.homeSubtitle.isVisible(model.homeVisible)
        binding.homeCane.isVisible(model.homeVisible)
        binding.homeBubble.isVisible(model.homeVisible)
        binding.homeCaption.isVisible(model.homeVisible)
        // **마이크와 글로우가 홈에서만 올라간다**(#231) — 아래에 캡션이 들어가기 때문이다.
        // 둘을 함께 옮긴다. 글로우 여백은 마이크 기하에서 나온 값이라 혼자 두면
        // 후광만 제자리에 남는다(davi_mic_margin_bottom 주석의 파생 관계).
        binding.micSlot.updateLayoutParams<FrameLayout.LayoutParams> {
            bottomMargin = resources.getDimensionPixelSize(
                if (model.homeVisible) R.dimen.davi_home_mic_margin_bottom
                else R.dimen.davi_mic_margin_bottom,
            )
        }
        binding.glowMic.updateLayoutParams<FrameLayout.LayoutParams> {
            bottomMargin = resources.getDimensionPixelSize(
                if (model.homeVisible) R.dimen.davi_home_glow_margin_bottom
                else R.dimen.davi_glow_mic_margin_bottom,
            )
        }
        // **실패 문구는 말풍선이 받는다**(#231). 따로 두면 마이크와 겹치고, 애초에
        // "잘 듣지 못했어요"는 다비가 하는 말이라 말풍선이 제자리다.
        //
        // **여기 들어올 문구의 길이에는 상한이 있다.** 홈은 아래 빈칸을 걷어서(space_home_mid)
        // 남는 세로를 **위 여백 하나로만** 흡수한다. 지금 가장 긴 문구
        // (voice_permission_open_settings)가 글꼴 배율 2.0에서 세 줄까지 들어가는 것을
        // 실기기로 확인했다(PR #235). 이보다 긴 문구를 넣으면 그때는 잘리고, 그때 접어야
        // 하는 것은 안내 문구가 아니라 캐릭터다.
        binding.homeBubbleText.setText(model.statusRes ?: R.string.voice_home_bubble)
        binding.logo.isVisible(model.logoVisible)
        // 실패 화면만 배지 대신 전신 캐릭터가 선다(메인 8, #208). 둘은 배타적이다.
        binding.stand.isVisible(model.standVisible)
        // 카드가 위 요소를 파고드는 깊이. **캐릭터는 배지보다 깊다** — 시안이 배지 36,
        // 캐릭터 53으로 따로 잡았다(x1.2 환산). 겹침을 카드의 음수 위 마진으로 만드는
        // 수법은 같고 값만 다르다.
        binding.cardQuestionWrap.updateLayoutParams<LinearLayout.LayoutParams> {
            topMargin = resources.getDimensionPixelSize(
                if (model.standVisible) R.dimen.davi_stand_overlap_negative
                else R.dimen.davi_badge_overlap_negative,
            )
        }
        // 실패 카드는 시안이 높이를 따로 잡았다(FIXED 200 -> 240dp). 다른 화면은 하한만 쓴다.
        binding.cardQuestionContainer.minimumHeight = resources.getDimensionPixelSize(
            if (model.standVisible) R.dimen.davi_failed_card_height
            else R.dimen.davi_card_min_height_davi,
        )
        // 카드 배경이 컨테이너로 올라갔다(#142) — 문구나 점 중 하나라도 있으면 카드를 보인다.
        val questionVisible = model.questionRes != null || model.questionText != null
        // **래퍼를 토글한다.** 래퍼가 세로 weight를 갖고 있어(카드 두 장이 남는 공간을
        // 나눠 가진다) 안쪽만 숨기면 빈 래퍼가 계속 자리를 먹어 홈 화면 문구가 밀려 올라간다.
        binding.cardQuestionWrap.isVisible(questionVisible || model.loadingDotsVisible)
        // **카드가 혼자 남으면 weight를 끈다**(PR #171 리뷰 지적).
        //
        // 최종 확인·실패에서는 발화 카드와 홈 여백 둘이 모두 숨어, 질문 카드 하나가 남는
        // 세로 공간을 전부 받아 내용과 무관하게 수백 dp까지 커진다. weight는 **두 카드가
        // 함께 보일 때 둘의 비(176:196)를 뜻하는 값**이지 혼자일 때 쓰라는 값이 아니다.
        //
        // 끄면 wrap_content + minHeight가 살아 카드가 내용 높이(하한 176dp)로 선다.
        // 시안 메인 7·8의 카드 높이 200은 그 화면들을 다시 짤 때 함께 본다.
        // **목표 확인(ConfirmKind.GOAL)에서는 weight를 뺀다**(2026-08-21, #200 리뷰를
        // 뒤집는다). 한때 여기에도 weight를 줬다 — 카드가 wrap_content면 긴 goal에서
        // 카드가 먼저 커져 버튼을 화면 밖으로 밀어내고, **확인 화면에서 버튼을 못 누르면
        // 빠져나갈 길이 없기** 때문이었다.
        //
        // 그 걱정은 이제 **maxHeight가 진다**(davi_question_card_max_height_confirm).
        // weight를 주면 남는 높이를 카드가 다 먹어 **한 줄짜리 goal에도 상자가 화면만큼
        // 커지고** 버튼이 바닥까지 밀린다 — 상자는 문장만큼만 자라고 버튼이 그만큼 따라
        // 내려가야 한다. 넘치는 문구는 card_question의 내부 스크롤이 받는다.
        // (경고 쪽은 종전대로 weight를 받는다 — 문구가 고정이라 자랄 일이 없다.)
        binding.cardQuestionWrap.updateLayoutParams<LinearLayout.LayoutParams> {
            // 구체화에서는 아래 선택 ScrollView가 남은 높이를 받는다. 질문 카드까지 weight로
            // 줄이면 2.0x 배율에서 176dp 하한이 깨져 질문 한 줄도 남지 않는다(#326 리뷰).
            weight = if (model.cardQuestionFlexible && !model.clarificationVisible) {
                CARD_WEIGHT_DAVI
            } else {
                0f
            }
        }
        binding.cardQuestionContainer.isVisible(questionVisible || model.loadingDotsVisible)
        binding.cardQuestion.isVisible(questionVisible)
        binding.glowCard.isVisible(model.cardGlowVisible)
        // 글로우가 화자를 가리킨다 — 다비가 말하면 위, 사용자가 말하면 아래(2026-08-17).
        // 둘은 상태가 배타적이라 동시에 켜지지 않는다(다비 TTS / 사용자 Listening).
        binding.glowUtterance.isVisible(model.utteranceSpeaking)
        // 발화 중인 카드는 그림자가 진해지고 윗변에 유리 하이라이트가 얹힌다(시안).
        // 배경 drawable을 통째로 바꾼다 — 그림자가 layer-list 안에 있어 부분 교체가 안 된다.
        // **문구 크기도 화면마다 다르다**(#208). 실패 문구는 시안 19sp이고 다른 화면 카드는
        // 24sp다. 레이아웃에 고정하면 한쪽이 틀리므로 배경과 같은 자리에서 함께 간다.
        binding.cardQuestion.setTextAppearance(
            if (model.standVisible) R.style.TextAppearance_Davi_Failed
            else R.style.TextAppearance_Davi_Subtitle_Davi,
        )
        // 실패 카드만 불투명이다 — 카드가 캐릭터를 파고들어 글라스면 몸통이 비친다(#208).
        binding.cardQuestionContainer.setBackgroundResource(
            when {
                model.standVisible -> R.drawable.bg_davi_card_failed
                model.cardGlowVisible -> R.drawable.bg_davi_card_speaking
                else -> R.drawable.bg_davi_card
            },
        )
        binding.cardUtteranceContainer.setBackgroundResource(
            if (model.utteranceSpeaking) {
                R.drawable.bg_davi_card_utterance_speaking
            } else {
                R.drawable.bg_davi_card_utterance
            },
        )
        binding.cardQuestion.maxHeight = resources.getDimensionPixelSize(model.questionMaxHeightRes)
        when {
            model.questionRes != null -> binding.cardQuestion.setText(model.questionRes)
            model.questionText != null -> binding.cardQuestion.text = model.questionText
        }
        // 새 문구가 들어올 때만 맨 위로. render()는 부분 인식마다 불리므로 무조건 되돌리면
        // 긴 질문을 스크롤해 읽던 중 말을 시작한 순간 카드가 계속 튄다.
        val questionKey = model.questionRes ?: model.questionText
        if (questionKey != lastQuestionKey) {
            lastQuestionKey = questionKey
            binding.cardQuestion.scrollTo(0, 0)
            // **레이아웃이 잡힌 뒤 한 번 더.** 위 호출은 새 글자의 줄 배치가 아직 없어
            // 스크롤 범위가 0으로 보이는 순간에 걸릴 수 있고, 그러면 아무 일도 하지 않는다.
            binding.cardQuestion.post { binding.cardQuestion.scrollTo(0, 0) }
        }
        // **래퍼까지 끈다.** 안쪽 컨테이너만 끄면 빈 래퍼가 weight로 자리를 먹어 홈 제목이
        // 위로 밀리고 그 자리가 빈 화면으로 남는다(2026-08-17 실기기). 다비 카드도 같은 이유로
        // cardQuestionWrap을 함께 끈다.
        binding.cardUtteranceWrap.isVisible(model.utteranceVisible)
        binding.cardUtteranceContainer.isVisible(model.utteranceVisible)
        binding.cardUtterance.isVisible(model.utteranceVisible)
        binding.utteranceLabel.isVisible(model.utteranceLabelVisible)
        binding.cardUtterance.text = model.utteranceText
        binding.clarificationChoices.root.isVisible(model.clarificationVisible)
        // 칩은 카드 **위**에 앉는다(#374 시안 y46) — 진행도는 「지금 어디쯤인가」라
        // 묻기 전에 보여야 한다. **✕는 전역 btn_close를 그대로 쓴다**(모든 화면에서 같은 자리).
        //
        // **보이는 조건을 상태로 판단하지 않는다** — 「직접 말하기」로 넘어간 청취에서도
        // 칩이 남아야 하고, 그때 배지 위 여백도 같은 값을 써야 카드가 안 뛴다.
        val stepOutcome = when (state) {
            is ConversationUiState.Clarifying -> state.outcome
            is ConversationUiState.Listening -> state.clarification
            else -> null
        }
        binding.questionProgress.isVisible(model.stepChipVisible)
        if (stepOutcome != null) {
            binding.questionProgress.text = getString(
                R.string.clarification_progress,
                stepOutcome.questionIndex + 1,
                stepOutcome.questionCount,
            )
        }
        if (state is ConversationUiState.Clarifying) {
            renderClarificationOptions(state)
        }
        binding.btnMic.isVisible(model.micVisible)
        binding.btnMic.isEnabled = model.micEnabled
        // **`isEnabled`만으로는 화면이 안 바뀐다.** ImageButton은 disabled에서 src를 자동으로
        // 흐리게 하지 않고, 표면 selector도 시안대로 양쪽이 같은 흰 원이다 — 그래서 누를 수
        // 없는 동안에도 활성과 똑같이 보였다(PR #173 리뷰 실측). 글리프만 흐리게 해 구분한다.
        binding.btnMic.imageAlpha =
            if (model.micEnabled) OPAQUE_IMAGE_ALPHA else micDisabledImageAlpha
        // 메인 9·10(#197). 카드 안 지시문·세로 버튼쌍·하단 안내는 안내 중에만 뜬다.
        // **안내 중에는 바닥 예약을 비운다**(#197). 그 85dp는 마이크 자리인데 이 화면에는
        // 마이크가 없다 — 그대로 두면 열이 그만큼 일찍 끝나 하단 안내 둘째 줄이 잘린다
        // (실기기: 34dp만 남아 두 줄에 필요한 53dp를 못 채웠다).
        //
        // **마이크 유무로 일반화하지 않았다.** 최종 확인·실패도 마이크가 없지만 그쪽은
        // 이미 확인된 배치라, 근거 없이 건드리면 방금 맞춘 것이 틀어진다.
        binding.cardColumn.updatePadding(
            bottom = when {
                model.guidingVisible || model.clarificationVisible || model.safeEndVisible -> 0
                // 홈만 마이크가 위로 올라가 있어 비울 자리도 그만큼 크다(#231).
                model.homeVisible ->
                    resources.getDimensionPixelSize(R.dimen.davi_home_control_reserve)
                else -> resources.getDimensionPixelSize(R.dimen.davi_conversation_control_reserve)
            },
        )
        // 세로 조작을 쌓는 안내·구체화 화면은 배지 위 여백이 얕다. 구체화는 세 보기와
        // 직접 말하기를 기본 글꼴에서 한 화면에 모두 보여야 하므로, 일반 72dp를 쓰면
        // 선택 영역 전체가 실기기 화면 아래로 밀린다(#316 리뷰).
        binding.logo.updateLayoutParams<LinearLayout.LayoutParams> {
            topMargin = resources.getDimensionPixelSize(
                when {
                    // 구체화는 위에 칩 줄이 하나 더 있어 **음수**다 — 배지가 그 줄과 겹친다
                    // (#374 시안: 칩 46~78, 배지 70~130). 안 겹치면 칩 줄이 통째로 카드를
                    // 밀어 카드가 102dp 내려간다.
                    // **clarificationVisible이 아니라 stepChipVisible을 본다** — 칩이 자리를
                    // 차지하는 동안에는 청취 화면에서도 같은 값이어야 카드가 안 뛴다.
                    model.stepChipVisible -> R.dimen.davi_badge_margin_top_clarification
                    model.guidingVisible -> R.dimen.davi_badge_margin_top_guiding
                    else -> R.dimen.davi_badge_margin_top
                },
            )
        }
        // 같은 두 화면은 카드 위 패딩도 겹침 안전 하한까지 줄여 아래 조작 영역을 보장한다.
        binding.cardQuestionContainer.updatePadding(
            top = resources.getDimensionPixelSize(
                if (model.guidingVisible || model.clarificationVisible) {
                    R.dimen.davi_card_padding_top_guiding
                }
                else R.dimen.davi_card_padding_top_davi,
            ),
        )
        binding.cardGuidingHint.isVisible(model.guidingVisible)
        binding.cardGuidingHint.setText(R.string.conversation_guiding_hint)
        binding.stackedPair.root.isVisible(model.guidingVisible || model.safeEndVisible)
        binding.stackedPair.btnStackedSecondary.setText(
            if (model.safeEndVisible) R.string.clarification_restart
            else R.string.conversation_guiding_resume,
        )
        binding.stackedPair.btnStackedPrimary.setText(
            if (model.safeEndVisible) R.string.clarification_go_home
            else R.string.conversation_guiding_stop,
        )
        binding.guidingBottom.isVisible(model.guidingVisible)
        binding.stopConfirmScrim.isVisible(model.stopConfirmVisible)
        // **모달 중에는 뒤 화면을 스크린리더에서 뺀다.** 스크림은 시각 차단일 뿐이라
        // 그것만으로는 TalkBack이 뒤의 "기존 안내 이어서하기"·"안내 종료"로 넘어간다 —
        // 답하기 전에는 사라지지 않는 화면인데 뒤를 누를 수 있게 읽히면 안 된다.
        // **인셋 층 전체를 뺀다.** 카드 열만 빼면 그 밖의 것(마이크·파도·X 버튼)이 남는다.
        // 모달은 인셋 층 **밖**(루트의 마지막 자식)이라 함께 가려지지 않는다.
        //
        // **uiautomator로는 확인할 수 없다.** UiAutomation이 not-important 뷰까지 포함해
        // 덤프하므로 제외한 뷰가 그대로 나온다 — 레이아웃에서 이미 제외한 mesh_background나
        // logo도 덤프에 찍힌다(2026-08-18 실측). 실제 확인은 TalkBack을 켜야 한다.
        binding.conversationInsets.importantForAccessibility =
            if (model.stopConfirmVisible) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        if (model.stopConfirmVisible && !stopConfirmAnnounced) {
            stopConfirmAnnounced = true
            binding.stopConfirmText.requestFocus()
            binding.stopConfirmText.sendAccessibilityEvent(
                AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
            )
        } else if (!model.stopConfirmVisible) {
            stopConfirmAnnounced = false
        }
        binding.confirmAccept.isVisible(model.confirmVisible)
        binding.confirmReject.isVisible(model.confirmVisible)
        // 확인 화면이 둘이라 (1)의 글자가 화면마다 다르다(#185/#194 + #196).
        // **contentDescription이 아니라 보이는 글자다** — #194는 버튼이 아이콘 전용이라
        // 스크린 리더에게만 구분을 줄 수 있었는데, 이제 글자가 있어 눈으로도 갈린다.
        binding.confirmAccept.setText(model.confirmAcceptTextRes)
        renderShortcutRow(model)
        binding.btnClose.isVisible(model.closeVisible)

        // 음성 모션(#132). 숨길 때 음량을 되돌려 둔다 — 안 그러면 다음에 다시 보일 때
        // 직전 발화의 크기로 튀어나온다. 프레임 루프는 뷰가 가시성 변화에서 알아서 멈춘다.
        renderVoiceMotion(model)

        // 응답 대기 점(#142). 지연·주기 되돌림은 뷰가 GONE→VISIBLE 전이에서 스스로 한다
        // (#148 MotionView.onMotionLifecycleChanged) — 여기서는 가시성만 넘긴다. render()가
        // 한 대기 구간에서 여러 번 불려도 전이가 아니면 지연이 다시 시작되지 않는다.
        binding.loadingDots.isVisible(model.loadingDotsVisible)
    }

    private fun renderVoiceMotion(model: ConversationRenderModel) {
        if (!model.waveVisible) binding.voiceWave.resetLevel()
        binding.voiceWave.isVisible(model.waveVisible)
        // 준비 중에는 **화면 전체**를 흐리게 덮는다(#277). 실제로 듣기 시작하면
        // 걷히고 그때 띠링이 함께 울린다 — 걷히는 순간이 곧 "지금 말하세요"다.
        binding.preparingScrim.isVisible(model.signalsDimmed)

        binding.glowMic.reactsToVoice = model.glowReactsToVoice
        if (!model.glowVisible || !model.glowReactsToVoice) binding.glowMic.resetLevel()
        binding.glowMic.isVisible(model.glowVisible)
    }

    private fun onMicTapped() {
        when (val state = uiState) {
            is ConversationUiState.Home -> when {
                hasRecordAudioPermission() -> beginVoiceDialogue()
                audioPermissionRequested &&
                    !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) -> {
                    setState(ConversationUiState.Home(statusRes = R.string.voice_permission_open_settings))
                    openAppSettings()
                }
                else -> {
                    audioPermissionRequested = true
                    requestRecordAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
            else -> return
        }
    }

    private fun onStackedSecondaryTapped() {
        if (uiState is ConversationUiState.SafeEnd) {
            beginVoiceDialogue()
        } else {
            finish()
        }
    }

    private fun onStackedPrimaryTapped() {
        if (uiState is ConversationUiState.SafeEnd) {
            setState(ConversationUiState.Home())
        } else {
            setState(ConversationUiState.StopConfirm)
        }
    }

    /** MA-4 ○ — 경고는 goal 재확인으로, goal 재확인은 안내 시작(GD-1)으로 진행한다. */
    private fun onConfirmAccepted() {
        val state = uiState as? ConversationUiState.Confirm ?: return
        followUpConfirmationAfterAccept(state)?.let { goalConfirmation ->
            cancelVoiceDialogue()
            setState(goalConfirmation)
            GuideTelemetry.voiceState("goal_ready_after_app_warning")
            speak(getString(R.string.voice_goal_ready), onFinished = {})
            return
        }
        cancelVoiceDialogue()
        GuideJourneyStarter.start(this, state.goal) {
            setState(ConversationUiState.Home())
        }
    }

    private fun beginVoiceDialogue() {
        cancelVoiceDialogue()
        voiceSession.start()
        setState(ConversationUiState.Listening())
        GuideTelemetry.voiceState("listening")
        startListeningTurn()
    }

    private fun submitVoiceTurn(text: String) {
        speechRecognizerHelper?.stop()
        val pending = voiceSession.nextRequest(text) ?: return
        submitPendingRequest(pending, text)
    }

    private fun submitPendingRequest(
        pending: VoiceDialogueSession.PendingRequest,
        visibleUtterance: String,
    ) {
        setState(
            ConversationUiState.Requesting(
                utterance = visibleUtterance,
                finalizing = pending.turnIndex == 1,
            ),
        )
        GuideTelemetry.voiceState("requesting")

        voiceExecutor.execute {
            val logicalRequest = voiceRequestTracker.next(
                signature = "voice-turn-${pending.turnIndex}",
                generation = pending.generation,
            )
            GuideTelemetry.voiceRequestStarted(logicalRequest, pending.turnIndex)
            lateinit var result: SlotFillResult
            var appResolutionOutcome: AppResolutionCoordinator.Outcome? = null
            val durationMs = measureTimeMillis {
                result = slotFillApiClient.resolve(pending.request, logicalRequest)
            }
            recordVoiceRequestResult(logicalRequest, result, durationMs)
            if (
                result is SlotFillResult.Success &&
                result.state.status == IntentDialogueStatus.READY &&
                result.state.goal != null
            ) {
                appResolutionOutcome = resolveGoalApp(pending, result.state.goal)
            }
            if (!destroyed) {
                runOnUiThread {
                    if (!destroyed) {
                        handleResolvedVoiceTurn(
                            pending,
                            logicalRequest,
                            result,
                            appResolutionOutcome,
                        )
                    }
                }
            }
        }
    }

    private fun beginClarificationSpeech() {
        val state = uiState as? ConversationUiState.Clarifying ?: return
        textToSpeechHelper?.stop()
        setState(
            ConversationUiState.Listening(
                question = state.outcome.question.prompt,
                clarification = state.outcome,
            ),
        )
        GuideTelemetry.voiceState("listening_clarification_freeform")
        startListeningTurn()
    }

    private fun submitClarificationAnswer(source: ClarificationAnswerSource, answer: String) {
        speechRecognizerHelper?.stop()
        textToSpeechHelper?.stop()
        when (val outcome = voiceSession.answerCurrent(source, answer)) {
            is VoiceDialogueSession.AnswerOutcome.NextQuestion -> showClarification(outcome.outcome)
            is VoiceDialogueSession.AnswerOutcome.FinalRequest -> {
                submitPendingRequest(outcome.pending, visibleUtterance = "")
            }
            null -> Unit
        }
    }

    private fun handleResolvedVoiceTurn(
        pending: VoiceDialogueSession.PendingRequest,
        logicalRequest: LogicalRequestAttempt,
        slotResult: SlotFillResult,
        appOutcome: AppResolutionCoordinator.Outcome?,
    ) {
        when (appOutcome) {
            null -> handleSlotFillResult(pending, logicalRequest, slotResult)
            AppResolutionCoordinator.Outcome.Dropped ->
                GuideTelemetry.voiceResponseDropped(logicalRequest, "app_resolution_changed")
            AppResolutionCoordinator.Outcome.SessionAuthenticationRequired ->
                sessionLoginRecovery.start()
            is AppResolutionCoordinator.Outcome.Completed -> {
                if (!voiceSession.isPending(pending)) {
                    GuideTelemetry.voiceResponseDropped(logicalRequest, "dialogue_changed")
                    if (voiceSession.fail(pending)) {
                        setState(ConversationUiState.Home(statusRes = R.string.voice_server_error))
                    }
                    return
                }
                handleSlotFillResult(
                    pending,
                    logicalRequest,
                    slotResult,
                    appOutcome.availability,
                    appOutcome.launchableApp,
                )
            }
        }
    }

    private fun resolveGoalApp(
        pending: VoiceDialogueSession.PendingRequest,
        goal: String,
    ): AppResolutionCoordinator.Outcome {
        return appResolutionFlow.resolve(
            goal,
            pending.generation,
            pending.turnIndex,
            isCurrent = { voiceSession.isPending(pending) },
        )
    }

    private fun handleSlotFillResult(
        pending: VoiceDialogueSession.PendingRequest,
        logicalRequest: LogicalRequestAttempt,
        result: SlotFillResult,
        appAvailability: AppAvailabilityPreflight? = null,
        launchableApp: LaunchableAppRecord? = null,
    ) {
        when (result) {
            is SlotFillResult.Success -> {
                val outcome = voiceSession.applyResponse(pending, result.state)
                if (outcome == null) {
                    GuideTelemetry.voiceResponseDropped(logicalRequest, "dialogue_changed")
                    return
                }
                when (outcome) {
                    is VoiceDialogueSession.Outcome.Clarify -> showClarification(outcome)
                    is VoiceDialogueSession.Outcome.Ready ->
                        showReadyGoal(outcome, appAvailability, launchableApp)
                    is VoiceDialogueSession.Outcome.UnableToFinalize,
                    VoiceDialogueSession.Outcome.InvalidResponse,
                    -> {
                        voiceRequestTracker.reset()
                        appResolutionRequestTracker.reset()
                        setState(stateForOutcome(outcome))
                    }
                }
            }
            is SlotFillResult.ApiFailure,
            is SlotFillResult.HttpFailure,
            is SlotFillResult.NetworkFailure,
            is SlotFillResult.ParseFailure,
            -> {
                if (
                    result is SlotFillResult.ApiFailure &&
                    result.sessionAuthenticationRequired
                ) {
                    sessionLoginRecovery.start()
                    return
                }
                if (voiceSession.fail(pending)) {
                    voiceRequestTracker.reset()
                    appResolutionRequestTracker.reset()
                    setState(ConversationUiState.Home(statusRes = errorMessageFor(result)))
                }
            }
        }
    }

    private fun showClarification(outcome: VoiceDialogueSession.Outcome.Clarify) {
        val speaking = shouldSpeakClarification(outcome)
        setState(ConversationUiState.Clarifying(outcome, speaking = speaking))
        GuideTelemetry.voiceState("clarification_question")
        if (!speaking) return
        speak(
            outcome.question.prompt,
            onFinished = {
                val current = uiState as? ConversationUiState.Clarifying
                if (
                    current?.outcome?.generation == outcome.generation &&
                    current.outcome.question.id == outcome.question.id
                ) {
                    setState(current.copy(speaking = false))
                }
            },
        )
    }

    private fun showReadyGoal(
        outcome: VoiceDialogueSession.Outcome.Ready,
        appAvailability: AppAvailabilityPreflight?,
        launchableApp: LaunchableAppRecord? = null,
    ) {
        val warning = appAvailability == AppAvailabilityPreflight.UNCONFIRMED
        val goalConfirmation = stateForOutcome(outcome) as ConversationUiState.Confirm
        val confirmation = if (warning) {
            goalConfirmation.copy(kind = ConfirmKind.APP_AVAILABILITY_WARNING)
        } else {
            // 경고 쪽에는 싣지 않는다 — 열 수 있는지 모르는 앱으로 보내면 안 된다(#348).
            goalConfirmation.copy(shortcutApp = launchableApp?.toShortcutApp())
        }
        setState(confirmation)
        GuideTelemetry.voiceState(if (warning) "app_availability_warning" else "goal_ready")
        speak(
            if (warning) getString(R.string.conversation_app_availability_warning) else outcome.message,
            // 세션 정리만 — 화면은 ○/× 입력을 기다린다. goal은 UiState가 들고 있다.
            onFinished = { voiceSession.finish(outcome.generation) },
        )
    }

    private fun speak(message: String, onFinished: () -> Unit) {
        val helper = textToSpeechHelper ?: TextToSpeechHelper(this).also {
            textToSpeechHelper = it
        }
        helper.speak(
            message,
            onDone = { runOnUiThread { if (!destroyed) onFinished() } },
            // TTS가 없어도 질문은 화면에 보인다. 대화 상태를 멈추지 않고 같은 흐름을 잇는다.
            onError = { runOnUiThread { if (!destroyed) onFinished() } },
        )
    }

    private fun handleSpeechError(error: Int) {
        GuideTelemetry.voiceState("recognition_error", error)
        val listening = uiState as? ConversationUiState.Listening ?: return
        val clarification = listening.clarification
        if (clarification != null && canResumeClarificationAfterSpeechError(error)) {
            // 직접 말하기 실패는 질문을 취소한 것이 아니다. 원발화와 앞 답을 세션에 둔 채
            // 같은 선택 화면으로 돌아가 보기 또는 직접 말하기를 다시 고를 수 있게 한다. 권한·서비스·
            // 시작 실패까지 여기서 삼키면 직접 말하기가 무반응으로 반복되므로 그 오류는 아래의
            // 기존 홈 오류 문구로 종료한다.
            speechRecognizerHelper?.stop()
            setState(ConversationUiState.Clarifying(clarification, speaking = false))
            return
        }
        voiceSession.cancel()
        voiceRequestTracker.reset()
        appResolutionRequestTracker.reset()
        // 인식 실패·무응답의 재시도 흐름은 #106 범위 — 여기서는 홈에 문구만 남긴다.
        val message = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
            -> R.string.voice_no_speech
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> R.string.voice_permission_denied
            // ERROR_UNAVAILABLE만 "이 기기는 못 쓴다"로 보낸다(#29 리뷰). ERROR_START_FAILED는
            // createRecognizer/startListening의 RuntimeException — 바인딩 순간 실패나 폴백 실패라
            // 일시적이다. 재시도 여지를 지운 종료 선언을 그쪽에 쓰면 멀쩡한 기기의 어르신이
            // 한 번 삐끗한 것으로 앱을 접는다(릴리스엔 되돌릴 글자 입력칸도 없다).
            SpeechRecognizerHelper.ERROR_UNAVAILABLE -> R.string.voice_recognizer_unavailable
            else -> R.string.voice_recognition_error
        }
        setState(ConversationUiState.Home(statusRes = message))
    }

    private fun recordVoiceRequestResult(
        request: LogicalRequestAttempt,
        result: SlotFillResult,
        durationMs: Long,
    ) {
        when (result) {
            is SlotFillResult.Success -> {
                voiceRequestTracker.markHttpResponse(request, 200)
                GuideTelemetry.voiceRequestFinished(
                    request,
                    outcome = "success",
                    durationMs = durationMs,
                    statusCode = 200,
                    serverRequestId = result.trace.requestId,
                )
            }
            is SlotFillResult.ApiFailure -> {
                voiceRequestTracker.markHttpResponse(request, result.statusCode)
                GuideTelemetry.voiceRequestFinished(
                    request,
                    outcome = "api_error",
                    durationMs = durationMs,
                    statusCode = result.statusCode,
                    errorKind = result.error.kind.name,
                    serverRequestId = result.trace.requestId,
                )
            }
            is SlotFillResult.HttpFailure -> {
                voiceRequestTracker.markHttpResponse(request, result.statusCode)
                GuideTelemetry.voiceRequestFinished(
                    request,
                    outcome = "http_error",
                    durationMs = durationMs,
                    statusCode = result.statusCode,
                    serverRequestId = result.trace.requestId,
                )
            }
            is SlotFillResult.NetworkFailure -> GuideTelemetry.voiceRequestFinished(
                request,
                outcome = "network_error",
                durationMs = durationMs,
                exceptionClass = result.exception.javaClass.simpleName,
            )
            is SlotFillResult.ParseFailure -> GuideTelemetry.voiceRequestFinished(
                request,
                outcome = "parse_error",
                durationMs = durationMs,
                serverRequestId = result.trace?.requestId,
                exceptionClass = result.exception.javaClass.simpleName,
            )
        }
    }

    /**
     * 오류를 가르는 기준은 [bucketOf] 하나를 안내창(`GuideErrorCopy`)과 공유한다 — 같은 원인이
     * 두 화면에서 다른 말로 나오지 않게 하려는 것이다(#29). 문구만 화면마다 다르다.
     *
     * `Success` 가지는 when 소진용이고 호출부는 실패 분기뿐이다 — 릴리스에서 `voice_goal_ready`는
     * 도달하지 않는다(실제 표시는 개발판 글자 입력 화면).
     */
    private fun errorMessageFor(result: SlotFillResult): Int = when (result) {
        is SlotFillResult.ApiFailure -> when (bucketOf(result.error)) {
            ApiErrorBucket.BUSY -> R.string.voice_server_busy
            ApiErrorBucket.DAILY_LIMIT -> R.string.voice_daily_limit
            ApiErrorBucket.NETWORK -> R.string.voice_network_error
            ApiErrorBucket.SERVER -> R.string.voice_server_error
        }
        is SlotFillResult.NetworkFailure -> R.string.voice_network_error
        is SlotFillResult.HttpFailure,
        is SlotFillResult.ParseFailure,
        -> R.string.voice_server_error
        is SlotFillResult.Success -> R.string.voice_goal_ready
    }

    /**
     * 마이크가 열렸다고 화면에 알린다(#273). **듣는 중일 때만** — 상태가 이미 넘어갔으면
     * 무시한다. 유령 콜백은 인식기 정리가 리스너를 먼저 끊어 막지만, 상한 타이머는 우리
     * 것이라 여기서 한 번 더 거른다.
     *
     * 부르는 쪽은 첫 음량 이벤트(정상)와 상한 타이머(신호가 안 오는 인식기) 둘뿐이다.
     */
    private fun markMicReady(telemetry: String) {
        micReadyHandler.removeCallbacks(micReadyFallback)
        val state = uiState as? ConversationUiState.Listening ?: return
        if (state.micReady) return
        setState(state.copy(micReady = true))
        // **소리도 여기서 낸다**(#313). 파도·「듣고 있어요」·글로우가 이 한 신호로 함께 서는데,
        // 그 셋은 **보고 있어야** 알 수 있다. 시니어 사용자가 "언제 말해야 할지 모르겠다"고 한
        // 것이 이 자리다(2026-08-20 관찰) — 화면을 안 보고 있어도 귀로 알게 한다.
        //
        // **누를 때가 아니라 여기다.** 누르고 실제로 들리기까지 약 0.9초가 걸리는데(#278에서
        // 줄이려다 실패했다), 누를 때 소리를 내면 **아직 안 듣는 동안 말하게 된다.**
        //
        // **상한 타이머로 들어와도 운다**(2026-08-21 결정 · PR #317 리뷰). 그쪽은 열렸다는
        // 확증이 없어 "이미 말하는 중에 소리가 겹칠 수 있다"는 위험이 있지만, 막으면 음량을
        // 안 주는 인식기에서 **띠링이 아예 없어져 #313이 그 기기에서 통째로 사라진다.**
        // 실측(SM-S921N 8회)에서 상한이 돈 적은 없어 지금은 가정상의 위험이다 —
        // 다른 기기 로그에 `mic_ready_fallback`이 보이면 그때 이 자리를 다시 본다.
        //
        // **다만 턴당 한 번이다**([startListeningTurn]). 폴백 재시작으로 이 자리에 두 번째로
        // 오는 구간은 사용자가 이미 말하는 중이라, 그때 우는 소리는 도움이 아니라 방해다.
        if (micToneArmed) {
            micToneArmed = false
            micTone.play()
        }
        // 상태 이름은 setState가 남기지 않는다(같은 클래스라 전이가 아니다). 준비까지 걸린
        // 시간은 이 줄과 직전 listening 줄의 간격으로 로그에서 읽는다.
        GuideTelemetry.voiceState(telemetry)
    }

    private fun cancelVoiceDialogue() {
        speechRecognizerHelper?.stop()
        textToSpeechHelper?.stop()
        voiceSession.cancel()
        voiceRequestTracker.reset()
        appResolutionRequestTracker.reset()
    }

    /**
     * 듣기 턴 하나를 시작한다. **차임을 여기서 다시 무장한다** — 턴마다 한 번만 운다.
     *
     * 인식기는 한 턴 안에서 **다시 준비 중으로 돌아갈 수 있다**: 온디바이스가 끊기면
     * `ERROR_SERVER_DISCONNECTED` 폴백이 네트워크 인식기로 갈아타며 `onMicPreparing`을
     * 다시 보낸다(#273). 그때 차임이 또 울면 **사용자가 이미 말하는 중**이다 — 문장 한가운데를
     * 끊고, 곧 열릴 마이크로 들어가고, "지금 말하세요"를 이미 말하는 사람에게 다시 말한다.
     *
     * **두 번째 구간에는 화면 신호만 준다.** 흐림이 다시 덮였다 걷히는 것은 조용하지만 소리는
     * 안 조용하고, 그 구간의 사용자는 이미 붙잡혀 있어 "언제 말하나"를 다시 알릴 필요가 없다.
     *
     * 두 곳에서 부른다(최초 목표 발화·구체화 직접 발화). **`speechHelper().start()`를 직접 부르지
     * 않는다** — 그러면 그 턴만 차임이 안 무장돼 조용히 갈린다.
     */
    private fun startListeningTurn() {
        micToneArmed = true
        if (
            BuildConfig.DEBUG &&
            intent.getBooleanExtra(EXTRA_INSTRUMENTATION_SKIP_SPEECH_RECOGNIZER, false)
        ) return
        speechHelper().start()
    }

    /**
     * MA-3 보기 버튼 셋(#374). 라벨·아이콘·여백을 함께 정한다.
     *
     * **아이콘은 이름이 설치된 앱과 정확히 맞을 때만 붙는다**([launchableAppForLabel]).
     * 서버는 보기를 문자열로만 주므로 어느 앱인지 알려주지 않는다 — 비슷한 것을 붙이면
     * 어르신이 **다른 앱으로 가는 줄 알고** 누른다. 「시간 정하기」처럼 앱이 아닌 답은
     * 자연히 슬롯이 숨는다.
     *
     * **아이콘이 숨어도 라벨은 안 움직인다** — 라벨이 본체 가운데에 서기 때문이다(시안
     * 「가로 중앙」). 좌우 여백만 아이콘 유무에 따라 갈린다.
     */
    private fun renderClarificationOptions(state: ConversationUiState.Clarifying) {
        val snapshot = launchableAppIndex.current()
        val buttons = listOf(
            binding.clarificationChoices.btnOption1 to
                (binding.clarificationChoices.option1Icon to binding.clarificationChoices.option1Label),
            binding.clarificationChoices.btnOption2 to
                (binding.clarificationChoices.option2Icon to binding.clarificationChoices.option2Label),
            binding.clarificationChoices.btnOption3 to
                (binding.clarificationChoices.option3Icon to binding.clarificationChoices.option3Label),
        )
        buttons.forEachIndexed { index, (button, views) ->
            val (icon, label) = views
            val option = state.outcome.question.options.getOrNull(index)
            button.isVisible(option != null)
            if (option == null) return@forEachIndexed
            label.text = option
            button.contentDescription = option

            val app = launchableAppForLabel(option, snapshot)
            val drawable = app?.let {
                runCatching { packageManager.getApplicationIcon(it.packageName) }.getOrNull()
            }
            icon.isVisible(drawable != null)
            // 기기 아이콘은 정사각·원형·스퀘어클이 섞여 있어 슬롯 윤곽으로 깎아야 균일하다
            // (MA-4 바로가기 행과 같은 처리). centerCrop이어야 적응형 아이콘의 25% 여백이
            // 슬롯 안에서 작게 앉지 않는다.
            icon.clipToOutline = true
            icon.setImageDrawable(drawable)
        }
    }

    /**
     * MA-4 확인 버튼 (2) 앱 바로가기(#348). **(1)(3)과 달리 이것만 숨을 수 있다** —
     * 사전 점검이 미확인이면 열 수 있는지 모르는 앱이라 보내지 않는다(#185).
     *
     * **아이콘은 그릴 때마다 조회하지 않는다** — 같은 앱이면 이미 붙은 것을 그대로 둔다.
     * render는 상태가 바뀔 때마다 여러 번 돌고, `PackageManager` 조회는 그때마다 할 일이
     * 아니다.
     */
    private fun renderShortcutRow(model: ConversationRenderModel) {
        binding.shortcutRow.isVisible(model.shortcutVisible)
        if (!model.shortcutVisible) {
            shownShortcutPackage = null
            return
        }
        binding.shortcutLabel.text =
            getString(R.string.conversation_shortcut_label, model.shortcutAppName)
        binding.shortcutRow.contentDescription =
            getString(R.string.conversation_shortcut_desc, model.shortcutAppName)

        val app = (uiState as? ConversationUiState.Confirm)?.shortcutApp ?: return
        if (shownShortcutPackage == app.packageName) return
        // 앱마다 아이콘 모양이 달라(원형·스퀘어클·사각) 그대로 두면 줄이 안 맞는다.
        // 배경 도형의 윤곽으로 깎아 같은 실루엣으로 세운다.
        //
        // **`centerCrop`이어야 슬롯을 채운다.** 적응형 아이콘은 108dp 캔버스에 25%가 여백이고
        // 그 여백까지 맞추는 `fitCenter`로 두면 아이콘이 슬롯 안에서 작게 앉는다 — 런처가
        // 하는 것처럼 가운데를 잘라 채운 뒤 r12로 깎는다.
        val icon = runCatching { packageManager.getApplicationIcon(app.packageName) }.getOrNull()
        // **실패해도 이전 아이콘을 남기지 않는다.** 앱 A(성공) 다음에 앱 B(실패)가 오면
        // **A의 아이콘에 B의 라벨**이 붙어 「지도 아이콘에 카카오톡 이름」이 된다 —
        // 빈 슬롯보다 나쁘다. 어느 앱으로 가는지를 아이콘이 거짓으로 말한다.
        binding.shortcutIcon.clipToOutline = true
        binding.shortcutIcon.setImageDrawable(icon)
        // **성공했을 때만 기억한다.** 조회 전에 기억하면 실패했을 때 빈 슬롯이 그대로
        // 굳는다 — 같은 앱인 동안 다시 조회하지 않기 때문이다. render는 상태가 바뀔 때마다
        // 도므로, 기억하지 않으면 다음 render가 알아서 다시 해 본다.
        if (icon != null) shownShortcutPackage = app.packageName
    }

    /**
     * 바로가기 행을 연다. **여기서 여는 것은 앱뿐이다** — 그 안에서 다비가 무엇을 누르지
     * 않는다. 「대신 조작하지 않는다」가 깨지는 자리가 아니다.
     */
    private fun openShortcut() {
        val state = uiState as? ConversationUiState.Confirm ?: return
        val app = state.shortcutApp ?: return
        val goal = state.goal.trim()
        // **빈 goal로는 세션을 켜지 않는다.** `GuideJourneyStarter.start`가 지고 있던 가드다 —
        // 그쪽을 안 거치기로 하면서 같이 와야 했다. 목표 없이 켜면 닫힌 루프가 무엇을
        // 향하는지 모르는 채로 화면만 읽는다.
        //
        // 도달할 일이 사실상 없지만 **조용히 죽지는 않는다** — 그냥 return하면 어르신에게는
        // 「눌러도 아무 일도 안 하는 버튼」이고, 로그가 없으면 그 제보를 재현할 수 없다.
        if (goal.isEmpty()) {
            GuideTelemetry.voiceState("shortcut_blocked_empty_goal")
            return
        }
        val intent = packageManager.getLaunchIntentForPackage(app.packageName)
        if (intent == null) {
            // 사전 점검과 탭 사이에 지워졌거나, 다비가 못 여는 프로필의 앱이다.
            // 조용히 넘기지 않고 화면을 되돌린다 — **서버 오류가 아니라고 말한다.**
            setState(ConversationUiState.Home(statusRes = R.string.conversation_shortcut_unavailable))
            return
        }
        cancelVoiceDialogue()
        // **여는 것으로 끝나지 않는다.** (1)과 같은 안내를 그대로 켠다 — 둘의 차이는
        // 「안내를 받느냐 마느냐」가 아니라 **어디서부터 받느냐**다. (1)은 홈 화면부터
        // (앱 아이콘 찾기 포함), (2)는 앱이 열린 다음 걸음부터다. 닫힌 루프가 이미 앱 안인
        // 화면을 읽으므로 「카카오톡을 눌러주세요」 단계를 코드로 건너뛸 필요가 없다 —
        // 화면을 보고 정하는 구조라 알아서 그다음이 나온다.
        //
        // **예외의 범위는 「첫 실행 한 번」뿐이다.** 앱을 여는 것까지가 우리가 대신 하는
        // 전부이고, 그 뒤로는 평소대로 안내만 한다. 앱 안에서 버튼을 눌러 주는 일은
        // 이 경로로 들어와도 없다. **이 경계를 넓히지 않는다** — 「어차피 앱도 열어 줬으니
        // 한 걸음만 더」가 원칙이 무너지는 전형적인 경로다.
        // **`GuideJourneyStarter`를 쓰지 않는다.** 그쪽은 세션을 켠 뒤 **Android 홈으로
        // 나가는** 것까지 한다 — (1)은 홈 화면부터 안내하니 맞지만, 여기서는 그 홈 인텐트가
        // 방금 연 앱을 덮어 **앱 밖에서 안내가 시작된다**(2026-08-21 실기기).
        //
        // 그래서 **앱을 띄우고 세션을 켠다.** 닫힌 루프가 이미 앱 안인 화면을 읽어 다음
        // 한 걸음을 낸다 — 「카카오톡을 눌러주세요」를 코드로 건너뛸 필요가 없다.
        //
        // **순서가 이 방향이어야 한다.** 뒤집으면 startActivity가 던졌을 때 세션만 켜진 채
        // 남아 **닫힌 루프가 다비 화면과 홈을 읽고 다음 걸음을 낸다** — 앱은 안 열렸는데
        // 안내가 시작되는 최악의 조합이다.
        if (runCatching { startActivity(intent) }.isFailure) {
            setState(ConversationUiState.Home(statusRes = R.string.conversation_shortcut_unavailable))
            return
        }
        // **실행이 성공한 뒤에 남긴다** — 시도 앞에 두면 실패도 「열렸다」로 집계돼
        // 지표가 거짓말을 한다.
        GuideTelemetry.voiceState("shortcut_opened")
        GuideSession.start(goal)
        setState(ConversationUiState.Home())
    }

    private fun speechHelper(): SpeechRecognizerHelper =
        speechRecognizerHelper ?: SpeechRecognizerHelper(this, voiceRecognitionListener).also {
            speechRecognizerHelper = it
        }

    private fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName"),
            ),
        )
    }

    private fun View.isVisible(visible: Boolean) {
        visibility = if (visible) View.VISIBLE else View.GONE
    }

    private companion object {
        const val AUDIO_PERMISSION_REQUESTED = "audio_permission_requested"
        const val NOTIFICATION_PERMISSION_REQUESTED = "notification_permission_requested"

        /**
         * 첫 음량 이벤트를 기다리는 상한(ms). 세기를 안 주는 인식기에서도 레이블이 뜨게 하는
         * 최후 수단이다.
         *
         * **넉넉히 잡는다.** 이게 돌면 아직 안 들리는데 "듣고 있어요"를 띄우는 것이라
         * #273이 고친 그 상태로 돌아간다 — 늦게 뜨는 손해보다 크다. 실측(SM-S921N, 청취 8회)이
         * 890~940ms에 몰려 있어 두 배 남짓을 뒀고, 그 8회에서 상한이 돈 적은 없다.
         * 다른 기기에서 `mic_ready_fallback`이 로그에 보이면 그 기기 값으로 다시 잡는다.
         */
        const val MIC_READY_FALLBACK_MS = 2_000L

        /** `ImageButton.imageAlpha`의 불투명 값. 흐린 쪽은 리소스에서 온다. */
        const val OPAQUE_IMAGE_ALPHA = 255

        /**
         * 두 카드가 함께 보일 때 질문 카드가 갖는 weight. 레이아웃의 값과 같아야 한다 —
         * 카드가 혼자 남는 상태에서 0으로 껐다가 되돌릴 때 쓴다.
         */
        const val CARD_WEIGHT_DAVI = 176f
    }
}
