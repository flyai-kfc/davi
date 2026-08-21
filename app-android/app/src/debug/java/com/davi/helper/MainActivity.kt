package com.davi.helper

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.SpeechRecognizer
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import com.davi.helper.databinding.ActivityMainBinding
import java.util.concurrent.Executors
import kotlin.system.measureTimeMillis

/**
 * 개발용 백업 화면(debug 전용) — 온보딩 + 텍스트/음성 목표 입력이 한 화면에 있다.
 * 릴리스 홈이 Onboarding→Conversation으로 교체되면서(#123) 여기로 물러났다. 텍스트 목표
 * 입력·안내 시작/중지 버튼이 필요한 개발 시나리오에서 쓴다. 음성은 목표를 입력칸까지 옮길
 * 뿐 안내를 자동 시작하거나 사용자를 대신해 조작하지 않는다. 발화·질문·goal은 화면과
 * 메모리에만 두고 logcat에 넘기지 않는다.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val voiceSession = VoiceDialogueSession()
    private val voiceRequestTracker = LogicalRequestTracker()
    private val appResolutionRequestTracker = LogicalRequestTracker()
    private val voiceExecutor = Executors.newSingleThreadExecutor()
    private var speechRecognizerHelper: SpeechRecognizerHelper? = null
    private var textToSpeechHelper: TextToSpeechHelper? = null
    private var audioPermissionRequested = false

    @Volatile
    private var destroyed = false

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

    // 서버가 done을 주면 서비스 쪽에서 세션이 끝난다. 그때도 버튼이 따라 바뀌어야 한다.
    private val sessionListener = GuideSession.Listener { runOnUiThread { refreshUi() } }

    private val requestRecordAudioPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            audioPermissionRequested = true
            if (granted) beginVoiceDialogue()
            else setVoiceStatus(R.string.voice_permission_denied)
            refreshUi()
        }

    private val voiceRecognitionListener = object : SpeechRecognizerHelper.Listener {
        override fun onPartialResult(text: String) {
            runOnUiThread {
                if (voiceSession.stage == VoiceDialogueSession.Stage.LISTENING) {
                    // 사용자에게만 보이는 일시적 받아쓰기. 저장·로그하지 않는다.
                    binding.voiceStatus.text = text
                }
            }
        }

        override fun onFinalResult(text: String) {
            runOnUiThread {
                if (voiceSession.stage == VoiceDialogueSession.Stage.CLARIFYING) {
                    submitClarificationAnswer(text)
                } else {
                    submitVoiceTurn(text)
                }
            }
        }

        override fun onError(error: Int) {
            runOnUiThread { handleSpeechError(error) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        launchableAppIndex.start()
        audioPermissionRequested = savedInstanceState?.getBoolean(AUDIO_PERMISSION_REQUESTED) ?: false

        // targetSdk 35+는 edge-to-edge 강제라 manifest의 adjustResize만으로는 창이 안 줄어든다.
        // 인셋을 마진으로 반영해야 ScrollView의 가시 영역이 IME 위로 실제로 줄어든다(#78).
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime(),
            )
            view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = bars.left
                topMargin = bars.top
                rightMargin = bars.right
                bottomMargin = bars.bottom
            }
            WindowInsetsCompat.CONSUMED
        }

        binding.btnAccessibility.setOnClickListener { openAccessibilitySettings() }
        binding.btnVoice.setOnClickListener { onVoiceInputTapped() }
        binding.btnStart.setOnClickListener { startGuide() }
        binding.btnStop.setOnClickListener { GuideSession.stop() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(AUDIO_PERMISSION_REQUESTED, audioPermissionRequested)
        super.onSaveInstanceState(outState)
    }

    /** 권한은 설정 화면에 다녀와야 켜진다. 돌아올 때마다 다시 읽는다. */
    override fun onResume() {
        super.onResume()
        GuideSession.addListener(sessionListener)
        refreshUi()
    }

    override fun onPause() {
        GuideSession.removeListener(sessionListener)
        speechRecognizerHelper?.stop()
        textToSpeechHelper?.stop()
        voiceSession.cancel()
        voiceRequestTracker.reset()
        appResolutionFlow.reset()
        super.onPause()
    }

    override fun onDestroy() {
        destroyed = true
        speechRecognizerHelper?.stop()
        textToSpeechHelper?.shutdown()
        launchableAppIndex.stop()
        voiceExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun startGuide() {
        val goal = binding.goalInput.text?.toString().orEmpty().trim()
        if (goal.isEmpty()) {
            binding.goalInput.error = getString(R.string.goal_required)
            binding.goalInput.requestFocus()
            return
        }
        cancelVoiceDialogue()
        GuideSession.start(goal)
    }

    private fun onVoiceInputTapped() {
        when {
            hasRecordAudioPermission() -> beginVoiceDialogue()
            audioPermissionRequested &&
                !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) -> {
                setVoiceStatus(R.string.voice_permission_open_settings)
                openAppSettings()
            }
            else -> {
                audioPermissionRequested = true
                requestRecordAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private fun beginVoiceDialogue() {
        cancelVoiceDialogue()
        voiceSession.start()
        setVoiceStatus(R.string.voice_listening)
        GuideTelemetry.voiceState("listening")
        refreshUi()
        speechHelper().start()
    }

    private fun submitVoiceTurn(text: String) {
        speechRecognizerHelper?.stop()
        val pending = voiceSession.nextRequest(text) ?: return
        submitPendingRequest(pending)
    }

    private fun submitPendingRequest(pending: VoiceDialogueSession.PendingRequest) {
        setVoiceStatus(R.string.voice_processing)
        GuideTelemetry.voiceState("requesting")
        refreshUi()

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
                appResolutionOutcome = appResolutionFlow.resolve(
                    result.state.goal,
                    pending.generation,
                    pending.turnIndex,
                    isCurrent = { voiceSession.isPending(pending) },
                )
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
                        setVoiceStatus(R.string.voice_server_error)
                        refreshUi()
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
                    is VoiceDialogueSession.Outcome.Clarify -> speakClarification(outcome)
                    is VoiceDialogueSession.Outcome.Ready -> showReadyGoal(outcome, appAvailability)
                    is VoiceDialogueSession.Outcome.UnableToFinalize -> {
                        binding.voiceStatus.text = outcome.message
                        voiceRequestTracker.reset()
                        appResolutionFlow.reset()
                        refreshUi()
                    }
                    VoiceDialogueSession.Outcome.InvalidResponse -> {
                        setVoiceStatus(R.string.voice_server_error)
                        voiceRequestTracker.reset()
                        appResolutionFlow.reset()
                        refreshUi()
                    }
                }
            }
            is SlotFillResult.ApiFailure,
            is SlotFillResult.HttpFailure,
            is SlotFillResult.NetworkFailure,
            is SlotFillResult.ParseFailure,
            -> {
                if (voiceSession.fail(pending)) {
                    setVoiceStatus(errorMessageFor(result))
                    voiceRequestTracker.reset()
                    appResolutionFlow.reset()
                    refreshUi()
                }
            }
        }
    }

    private fun speakClarification(outcome: VoiceDialogueSession.Outcome.Clarify) {
        binding.voiceStatus.text = outcome.question.prompt
        GuideTelemetry.voiceState("speaking_question")
        refreshUi()
        speak(
            outcome.question.prompt,
            onFinished = {
                if (voiceSession.stage == VoiceDialogueSession.Stage.CLARIFYING) {
                    setVoiceStatus(R.string.voice_listening)
                    GuideTelemetry.voiceState("listening_follow_up")
                    refreshUi()
                    speechHelper().start()
                }
            },
        )
    }

    private fun submitClarificationAnswer(text: String) {
        speechRecognizerHelper?.stop()
        when (val outcome = voiceSession.answerCurrent(ClarificationAnswerSource.FREEFORM, text)) {
            is VoiceDialogueSession.AnswerOutcome.NextQuestion -> speakClarification(outcome.outcome)
            is VoiceDialogueSession.AnswerOutcome.FinalRequest -> submitPendingRequest(outcome.pending)
            null -> Unit
        }
    }

    private fun showReadyGoal(
        outcome: VoiceDialogueSession.Outcome.Ready,
        appAvailability: AppAvailabilityPreflight?,
    ) {
        val warningRes = if (appAvailability == AppAvailabilityPreflight.UNCONFIRMED) {
            R.string.conversation_app_availability_warning
        } else {
            null
        }
        val message = warningRes?.let(::getString) ?: outcome.message
        binding.goalInput.setText(outcome.goal)
        binding.voiceStatus.text = message
        GuideTelemetry.voiceState(if (warningRes == null) "goal_ready" else "app_availability_warning")
        refreshUi()
        speak(
            message,
            onFinished = {
                if (voiceSession.finish(outcome.generation)) {
                    setVoiceStatus(warningRes ?: R.string.voice_goal_ready)
                    refreshUi()
                }
            },
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
        appResolutionFlow.reset()
        val message = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
            -> R.string.voice_no_speech
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> R.string.voice_permission_denied
            // 릴리스와 같은 이유로 가른다(#29 리뷰) — ERROR_START_FAILED는 일시적 실패다.
            SpeechRecognizerHelper.ERROR_UNAVAILABLE -> R.string.voice_recognizer_unavailable
            else -> R.string.voice_recognition_error
        }
        setVoiceStatus(message)
        refreshUi()
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
        appResolutionFlow.reset()
    }

    private fun refreshUi() {
        // 권한 기준은 한 곳에서만 판정한다([GuidePermissions]) — 갈리면 이 화면은 통과시키고
        // 안내는 조용히 실패한다.
        val ready = canStartGuide()
        val active = GuideSession.isActive
        val voiceBusy = voiceSession.stage != VoiceDialogueSession.Stage.IDLE

        binding.goalInput.isEnabled = !active && !voiceBusy
        binding.btnVoice.isEnabled = !active && !voiceBusy
        binding.btnStart.isEnabled = ready && !active && !voiceBusy
        binding.btnStop.isEnabled = active
    }

    private fun setVoiceStatus(stringRes: Int) {
        binding.voiceStatus.setText(stringRes)
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

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName"),
            ),
        )
    }

    private companion object {
        const val AUDIO_PERMISSION_REQUESTED = "audio_permission_requested"
    }
}
