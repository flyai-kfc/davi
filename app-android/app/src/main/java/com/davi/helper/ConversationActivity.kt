package com.davi.helper

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.SpeechRecognizer
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
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
    private val voiceExecutor = Executors.newSingleThreadExecutor()
    private var speechRecognizerHelper: SpeechRecognizerHelper? = null
    private var textToSpeechHelper: TextToSpeechHelper? = null
    private var audioPermissionRequested = false

    private var uiState: ConversationUiState = ConversationUiState.Home()

    // 실패 화면 → 5초 뒤 홈 복귀. setState()가 상태 전이마다 제거하므로 유령 타이머가 없다.
    private val failedReturnHandler = Handler(Looper.getMainLooper())
    private val failedReturnRunnable = Runnable { setState(ConversationUiState.Home()) }

    @Volatile
    private var destroyed = false

    // 안내 세션 시작·종료를 홈 렌더(안내 중지 버튼·mic 잠금)에 반영한다.
    private val sessionListener = GuideSession.Listener { runOnUiThread { render(uiState) } }

    private val slotFillApiClient: SlotFillApiClient by lazy {
        SlotFillApiClient(
            BuildConfig.SERVER_URL,
            devAccessToken = BuildConfig.DEV_ACCESS_TOKEN,
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
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConversationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        audioPermissionRequested = savedInstanceState?.getBoolean(AUDIO_PERMISSION_REQUESTED) ?: false

        // targetSdk 35+ edge-to-edge: 시스템 바 인셋을 마진으로 반영(#78 패턴)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
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
        binding.confirmPair.btnConfirmReject.setOnClickListener {
            cancelVoiceDialogue()
            setState(ConversationUiState.Home())
        }
        binding.confirmPair.btnConfirmAccept.setOnClickListener { onConfirmAccepted() }
        binding.btnStopGuide.setOnClickListener { GuideSession.stop() }

        render(uiState)
    }

    /** 권한·세션 상태는 화면 밖에서 바뀐다 — 돌아올 때마다 다시 본다. */
    override fun onResume() {
        super.onResume()
        // 두 권한은 밖에서 꺼질 수 있다(설정 회수·앱 업데이트). 없는 채로 ○를 누르면
        // GuideSession은 시작되는데 화면을 못 읽거나 오버레이가 안 그려져 사용자가 런처에
        // 혼자 남는다 — 시작 자체를 막고 온보딩으로 되돌린다(SCREENS ON-2 상태③).
        //
        // #29(상태·오류 문구) 참고: 이 복귀는 화면을 바꾸므로 **안내창으로는 알릴 수 없다**.
        // 권한 소실을 사용자에게 알리려면 토스트·헤드업처럼 화면 전환에 살아남는 수단이어야 한다.
        if (!canStartGuide()) {
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
        failedReturnHandler.removeCallbacks(failedReturnRunnable)
        speechRecognizerHelper?.stop()
        textToSpeechHelper?.shutdown()
        voiceExecutor.shutdownNow()
        super.onDestroy()
    }

    /** 상태 전이의 단일 지점 — 실패 복귀 타이머 정리와 렌더가 항상 함께 간다. */
    private fun setState(state: ConversationUiState) {
        uiState = state
        failedReturnHandler.removeCallbacks(failedReturnRunnable)
        if (state == ConversationUiState.Failed) {
            failedReturnHandler.postDelayed(failedReturnRunnable, FAILED_RETURN_DELAY_MS)
        }
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
        binding.spaceHomeTop.isVisible(model.homeVisible)
        binding.spaceHomeMid.isVisible(model.homeVisible)
        binding.homeTitle.isVisible(model.homeVisible)
        binding.homeStatus.isVisible(model.homeVisible && model.statusRes != null)
        model.statusRes?.let { binding.homeStatus.setText(it) }
        binding.logo.isVisible(model.logoVisible)
        binding.cardQuestion.isVisible(model.questionRes != null || model.questionText != null)
        when {
            model.questionRes != null -> binding.cardQuestion.setText(model.questionRes)
            model.questionText != null -> binding.cardQuestion.text = model.questionText
        }
        binding.cardUtterance.isVisible(model.utteranceVisible)
        binding.cardUtterance.text = model.utteranceText
        binding.btnMic.isVisible(model.micVisible)
        binding.btnMic.isEnabled = model.micEnabled
        binding.btnStopGuide.isVisible(model.stopGuideVisible)
        binding.confirmPair.root.isVisible(model.confirmVisible)
        binding.btnClose.isVisible(model.closeVisible)
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

    /** MA-4 ○ — 안내를 시작하고 런처로 나간다. 안내는 런처 화면에서 시작된다(GD-1). */
    private fun onConfirmAccepted() {
        val state = uiState as? ConversationUiState.Confirm ?: return
        cancelVoiceDialogue()
        GuideSession.start(state.goal)
        setState(ConversationUiState.Home())
        startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
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
            val durationMs = measureTimeMillis {
                result = slotFillApiClient.resolve(pending.request, logicalRequest)
            }
            recordVoiceRequestResult(logicalRequest, result, durationMs)
            if (!destroyed) {
                runOnUiThread {
                    if (!destroyed) handleSlotFillResult(pending, logicalRequest, result)
                }
            }
        }
    }

    private fun handleSlotFillResult(
        pending: VoiceDialogueSession.PendingRequest,
        logicalRequest: LogicalRequestAttempt,
        result: SlotFillResult,
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
                    is VoiceDialogueSession.Outcome.Ready -> showReadyGoal(outcome)
                    VoiceDialogueSession.Outcome.LimitReached,
                    VoiceDialogueSession.Outcome.InvalidResponse,
                    -> {
                        // 기존 goalInput 텍스트 폴백을 실패 화면(5초 뒤 홈)으로 대체(#123)
                        voiceRequestTracker.reset()
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

    private fun showReadyGoal(outcome: VoiceDialogueSession.Outcome.Ready) {
        setState(stateForOutcome(outcome)) // Confirm(goal)
        GuideTelemetry.voiceState("goal_ready")
        speak(
            outcome.message,
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
        // 인식 실패·무응답의 재시도 흐름은 #106 범위 — 여기서는 홈에 문구만 남긴다.
        val message = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
            -> R.string.voice_no_speech
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> R.string.voice_permission_denied
            SpeechRecognizerHelper.ERROR_UNAVAILABLE,
            SpeechRecognizerHelper.ERROR_START_FAILED,
            -> R.string.voice_recognizer_unavailable
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

    private fun errorMessageFor(result: SlotFillResult): Int = when (result) {
        is SlotFillResult.ApiFailure -> when (result.error.kind) {
            ApiErrorKind.QUOTA -> R.string.voice_server_busy
            ApiErrorKind.NETWORK -> R.string.voice_network_error
            ApiErrorKind.AUTH,
            ApiErrorKind.SERVER,
            ApiErrorKind.PARSE,
            ApiErrorKind.BAD_REQUEST,
            -> R.string.voice_server_error
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
        const val FAILED_RETURN_DELAY_MS = 5_000L
    }
}
