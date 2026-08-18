package com.davi.helper

import android.Manifest
import android.content.Intent
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

/**
 * 음성 대화 화면 — 홈(MA-1)부터 최종 확인(MA-4)·실패까지 상태 6개를 한 레이아웃에서
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

    /** 종료 확인 모달로 포커스를 한 번만 옮기기 위한 값. 렌더는 여러 번 돈다. */
    private var stopConfirmAnnounced = false

    /** 질문 카드 스크롤을 되돌릴지 판단하는 값 — 문구가 실제로 바뀔 때만 맨 위로 보낸다. */
    private var lastQuestionKey: Any? = null

    /** 직전 렌더에서 응답 대기 점이 떠 있었는가. 대기에 **새로** 들어갈 때만 지연을 다시 센다(#142). */
    private var loadingDotsShown = false

    @Volatile
    private var destroyed = false

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
        )
    }

    private val appResolutionApiClient: AppResolutionApiClient by lazy {
        AppResolutionApiClient(
            BuildConfig.SERVER_URL,
            devAccessToken = BuildConfig.DEV_ACCESS_TOKEN,
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
            runOnUiThread { submitVoiceTurn(text) }
        }

        override fun onError(error: Int) {
            runOnUiThread { handleSpeechError(error) }
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConversationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        launchableAppIndex.start()
        audioPermissionRequested = savedInstanceState?.getBoolean(AUDIO_PERMISSION_REQUESTED) ?: false

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
        binding.buttonPair.btnPairReject.setOnClickListener {
            cancelVoiceDialogue()
            setState(ConversationUiState.Home())
        }
        binding.buttonPair.btnPairAccept.setOnClickListener { onConfirmAccepted() }
        // 메인 9 — **"이어서하기"는 상태를 바꾸지 않는다.** 앱에서 나가기만 하고 안내는
        // 그대로 돈다. 하단 안내가 같은 동작을 뒤로가기로 설명하는 것과 짝이고, 이 버튼은
        // 뒤로가기를 모르는 사람을 위한 지름길이다.
        binding.stackedPair.btnStackedSecondary.setOnClickListener { finish() }
        binding.stackedPair.btnStackedPrimary.setOnClickListener {
            setState(ConversationUiState.StopConfirm)
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


        render(uiState)
    }

    /** 권한·세션 상태는 화면 밖에서 바뀐다 — 돌아올 때마다 다시 본다. */
    override fun onResume() {
        super.onResume()
        // 두 권한은 밖에서 꺼질 수 있다(설정 회수·앱 업데이트). 없는 채로 ○를 누르면
        // GuideSession은 시작되는데 화면을 못 읽거나 오버레이가 안 그려져 사용자가 런처에
        // 혼자 남는다 — 시작 자체를 막고 온보딩으로 되돌린다(SCREENS ON-2 상태③).
        //
        // 이 복귀는 화면을 바꾸므로 **안내창으로는 알릴 수 없다** — 토스트로 알린다(#29).
        // 지시("다시 켜 주세요")의 대상은 곧 뜰 온보딩 화면의 두 버튼이다.
        if (!canStartGuide()) {
            // 세션을 여기서 끊는다. 접근성이 꺼진 경우는 onUnbind가 정리하지만,
            // **오버레이만 회수되면** 서비스는 살아 있어 화면 직렬화와 서버 호출이 계속되고
            // 안내만 안 그려진다 — 그대로 두면 세션은 도는데 중지 수단만 손이 닿지 않는다.
            GuideSession.stop()
            Toast.makeText(this, R.string.guide_permission_lost, Toast.LENGTH_LONG).show()
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }
        GuideSession.addListener(sessionListener)
        render(uiState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(AUDIO_PERMISSION_REQUESTED, audioPermissionRequested)
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
        speechRecognizerHelper?.stop()
        textToSpeechHelper?.shutdown()
        launchableAppIndex.stop()
        voiceExecutor.shutdownNow()
        super.onDestroy()
    }

    /** 상태 전이의 단일 지점 — 복귀 타이머 정리와 렌더가 항상 함께 간다. */
    private fun setState(state: ConversationUiState) {
        // **상태 전이를 남긴다**(#197 리뷰). 안내 중·종료 확인은 사용자가 눈으로만 확인할 수
        // 있어 회귀가 나도 로그로 못 짚었다. **이름과 세션 유무뿐이다** — 발화·질문·goal은
        // 여기에 오지 않는다(기존 금지 그대로).
        if (state::class != uiState::class || state is ConversationUiState.Home) {
            GuideTelemetry.screenState(state.javaClass.simpleName, GuideSession.isActive)
        }
        uiState = state
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
        binding.spaceFlexTop.isVisible(model.homeVisible || model.guidingVisible)
        binding.spaceHomeMid.isVisible(model.homeVisible || model.guidingVisible)
        binding.homeTitle.isVisible(model.homeVisible)
        binding.homeStatus.isVisible(model.homeVisible && model.statusRes != null)
        model.statusRes?.let { binding.homeStatus.setText(it) }
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
        // **최종 확인에서도 weight를 준다**(#200 리뷰). 버튼쌍이 열 안으로 들어오면서
        // 잘림 규칙을 함께 받게 됐는데, 카드가 wrap_content면 긴 goal에서 카드가 먼저
        // 커져 버튼을 화면 밖으로 밀어낸다 — 확인 화면에서 ×/○를 못 누르면 빠져나갈
        // 길이 없다. weight를 주면 카드가 **남는 공간만** 갖고, 모자라면 카드가 줄어
        // 버튼이 보장된다. 넘치는 문구는 card_question의 내부 스크롤이 받는다.
        binding.cardQuestionWrap.updateLayoutParams<LinearLayout.LayoutParams> {
            weight = if (model.cardQuestionFlexible) CARD_WEIGHT_DAVI else 0f
        }
        binding.cardQuestionContainer.isVisible(questionVisible || model.loadingDotsVisible)
        binding.cardQuestion.isVisible(questionVisible)
        binding.glowCard.isVisible(model.cardGlowVisible)
        // 글로우가 화자를 가리킨다 — 다비가 말하면 위, 사용자가 말하면 아래(2026-08-17).
        // 둘은 상태가 배타적이라 동시에 켜지지 않는다(Speaking / Listening).
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
        }
        // **래퍼까지 끈다.** 안쪽 컨테이너만 끄면 빈 래퍼가 weight로 자리를 먹어 홈 제목이
        // 위로 밀리고 그 자리가 빈 화면으로 남는다(2026-08-17 실기기). 다비 카드도 같은 이유로
        // cardQuestionWrap을 함께 끈다.
        binding.cardUtteranceWrap.isVisible(model.utteranceVisible)
        binding.cardUtteranceContainer.isVisible(model.utteranceVisible)
        binding.cardUtterance.isVisible(model.utteranceVisible)
        binding.utteranceLabel.isVisible(model.utteranceLabelVisible)
        binding.cardUtterance.text = model.utteranceText
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
            bottom = if (model.guidingVisible) 0
            else resources.getDimensionPixelSize(R.dimen.davi_conversation_control_reserve),
        )
        // 배지 위 여백도 이 화면만 얕다 — 세로로 쌓는 것이 많아 배율이 커지면 아래가
        // 화면 밖으로 나간다. 위를 줄여 그 자리를 아래에 준다.
        binding.logo.updateLayoutParams<LinearLayout.LayoutParams> {
            topMargin = resources.getDimensionPixelSize(
                if (model.guidingVisible) R.dimen.davi_badge_margin_top_guiding
                else R.dimen.davi_badge_margin_top,
            )
        }
        // 카드 위 패딩도 이 화면만 얕다 — 근거는 davi_card_padding_top_guiding 주석.
        binding.cardQuestionContainer.updatePadding(
            top = resources.getDimensionPixelSize(
                if (model.guidingVisible) R.dimen.davi_card_padding_top_guiding
                else R.dimen.davi_card_padding_top_davi,
            ),
        )
        binding.cardGuidingHint.isVisible(model.guidingVisible)
        binding.cardGuidingHint.setText(R.string.conversation_guiding_hint)
        binding.stackedPair.root.isVisible(model.guidingVisible)
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
        binding.buttonPair.root.isVisible(model.confirmVisible)
        // 확인 화면이 둘이라 ○의 글자가 화면마다 다르다(#185/#194 + #196).
        // **contentDescription이 아니라 보이는 글자다** — #194는 버튼이 아이콘 전용이라
        // 스크린 리더에게만 구분을 줄 수 있었는데, 이제 글자가 있어 눈으로도 갈린다.
        binding.buttonPair.btnPairAccept.setText(model.confirmAcceptTextRes)
        binding.btnClose.isVisible(model.closeVisible)

        // 음성 모션(#132). 숨길 때 음량을 되돌려 둔다 — 안 그러면 다음에 다시 보일 때
        // 직전 발화의 크기로 튀어나온다. 프레임 루프는 뷰가 가시성 변화에서 알아서 멈춘다.
        renderVoiceMotion(model)

        // 응답 대기 점(#142). 대기에 새로 들어갈 때만 처음부터 센다 — render()는 한 대기
        // 구간에서도 여러 번 불리므로 무조건 되돌리면 지연이 매번 다시 시작돼 점이 안 나온다.
        if (model.loadingDotsVisible && !loadingDotsShown) binding.loadingDots.restart()
        loadingDotsShown = model.loadingDotsVisible
        binding.loadingDots.isVisible(model.loadingDotsVisible)
    }

    private fun renderVoiceMotion(model: ConversationRenderModel) {
        if (!model.waveVisible) binding.voiceWave.resetLevel()
        binding.voiceWave.isVisible(model.waveVisible)

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
            // barge-in: 질문 재생을 끊고 바로 답한다. TTS 정리 콜백이 늦게 와도
            // continueListening은 SPEAKING에서 한 번만 참이라 이중 시작이 없다.
            is ConversationUiState.Speaking -> {
                textToSpeechHelper?.stop()
                if (voiceSession.continueListening(state.generation)) {
                    setState(ConversationUiState.Listening(question = state.question))
                    GuideTelemetry.voiceState("listening_barge_in")
                    speechHelper().start()
                }
            }
            else -> return
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
        speechHelper().start()
    }

    private fun submitVoiceTurn(text: String) {
        speechRecognizerHelper?.stop()
        val pending = voiceSession.nextRequest(text) ?: return
        setState(ConversationUiState.Requesting(utterance = text))
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
    ) {
        when (result) {
            is SlotFillResult.Success -> {
                val outcome = voiceSession.applyResponse(pending, result.state)
                if (outcome == null) {
                    GuideTelemetry.voiceResponseDropped(logicalRequest, "dialogue_changed")
                    return
                }
                when (outcome) {
                    is VoiceDialogueSession.Outcome.Ask -> speakFollowUp(outcome)
                    is VoiceDialogueSession.Outcome.Ready -> showReadyGoal(outcome, appAvailability)
                    VoiceDialogueSession.Outcome.LimitReached,
                    VoiceDialogueSession.Outcome.InvalidResponse,
                    -> {
                        // 기존 goalInput 텍스트 폴백을 실패 화면(5초 뒤 홈)으로 대체(#123)
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
                if (voiceSession.fail(pending)) {
                    voiceRequestTracker.reset()
                    appResolutionRequestTracker.reset()
                    setState(ConversationUiState.Home(statusRes = errorMessageFor(result)))
                }
            }
        }
    }

    private fun speakFollowUp(outcome: VoiceDialogueSession.Outcome.Ask) {
        setState(stateForOutcome(outcome)) // Speaking(질문)
        GuideTelemetry.voiceState("speaking_question")
        speak(
            outcome.message,
            onFinished = {
                if (voiceSession.continueListening(outcome.generation)) {
                    setState(ConversationUiState.Listening(question = outcome.message))
                    GuideTelemetry.voiceState("listening_follow_up")
                    speechHelper().start()
                }
            },
        )
    }

    private fun showReadyGoal(
        outcome: VoiceDialogueSession.Outcome.Ready,
        appAvailability: AppAvailabilityPreflight?,
    ) {
        val warning = appAvailability == AppAvailabilityPreflight.UNCONFIRMED
        val goalConfirmation = stateForOutcome(outcome) as ConversationUiState.Confirm
        val confirmation = if (warning) {
            goalConfirmation.copy(kind = ConfirmKind.APP_AVAILABILITY_WARNING)
        } else {
            goalConfirmation
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
        is SlotFillResult.ApiFailure -> when (bucketOf(result.error.kind)) {
            ApiErrorBucket.BUSY -> R.string.voice_server_busy
            ApiErrorBucket.NETWORK -> R.string.voice_network_error
            ApiErrorBucket.SERVER -> R.string.voice_server_error
        }
        is SlotFillResult.NetworkFailure -> R.string.voice_network_error
        is SlotFillResult.HttpFailure,
        is SlotFillResult.ParseFailure,
        -> R.string.voice_server_error
        is SlotFillResult.Success -> R.string.voice_goal_ready
    }

    private fun cancelVoiceDialogue() {
        speechRecognizerHelper?.stop()
        textToSpeechHelper?.stop()
        voiceSession.cancel()
        voiceRequestTracker.reset()
        appResolutionRequestTracker.reset()
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

        /** `ImageButton.imageAlpha`의 불투명 값. 흐린 쪽은 리소스에서 온다. */
        const val OPAQUE_IMAGE_ALPHA = 255

        /**
         * 두 카드가 함께 보일 때 질문 카드가 갖는 weight. 레이아웃의 값과 같아야 한다 —
         * 카드가 혼자 남는 상태에서 0으로 껐다가 되돌릴 때 쓴다.
         */
        const val CARD_WEIGHT_DAVI = 176f
    }
}
