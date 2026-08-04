package com.davi.helper

import android.Manifest
import android.content.pm.PackageManager
import android.view.ViewGroup
import android.os.Bundle
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import com.davi.helper.databinding.ActivityMainBinding

/**
 * 음성 메인 화면(대기 → 리스닝 → 되묻기 → 최종 확인). pfront 담당 범위는 온보딩 **이후**의
 * 이 흐름뿐이다 — 접근성·오버레이 권한 온보딩은 다른 팀원이 별도 화면으로 만든다. 이 화면은
 * 두 권한이 이미 켜져 있다고 가정하고, 실기기에서 꺼져 있으면 시스템 설정 앱에서 직접 켜야
 * 한다(app-android/CLAUDE.md).
 *
 * UI 컨벤션: 화면은 XML 레이아웃 + ViewBinding. 글자 크기·터치 대상은 res/values/dimens.xml에서
 * 온다. 코드에서 px를 계산하지 않는다.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // 서버가 done을 주면 서비스 쪽에서 세션이 끝난다. 그때도 버튼이 따라 바뀌어야 한다.
    private val sessionListener = GuideSession.Listener { runOnUiThread { refreshUi() } }

    /**
     * 음성 흐름 전환 상태. `GuideSession`(오버레이 안내 세션)과는 무관한 화면 전용 로컬
     * 상태다. LISTENING부터는 `voice_fullscreen_overlay`가 전체화면으로 뜬다.
     *
     * SLOT_FILLING(되묻기)은 아직 실제 진입 조건(pback의 missing_slots 판단)이 없다 —
     * 지금은 개발자 미리보기 버튼으로만 진입한다. API 연동 시 STT 최종 결과 이후 분기를
     * 추가해서 여기 연결한다.
     */
    private enum class VoiceFlowState { IDLE, LISTENING, SLOT_FILLING, CONFIRMING }

    private var voiceFlowState = VoiceFlowState.IDLE

    /**
     * pback이 최종 확인 단계에서 만들어 줄 목표 문장("resolved_goal", 필드명 미확정 —
     * `mockSlotFillResponse` 참고). "네" 선택 시 이 값을 그대로 `GuideSession.start()`에
     * 넘긴다. 화면에 보여주는 원문 발화(echo)와는 별개다 — 앱이 slots를 조합하지 않는다
     * (2026-08 pback과 합의: 앱 버전마다 문장이 갈리는 걸 막으려고 서버가 만든다).
     */
    private var lastResolvedGoal = ""

    // 명시적 타입 필요: onError()가 speechRecognizerHelper.start()를 다시 부르면서
    // 두 프로퍼티가 서로를 참조해 타입 추론이 순환한다(컴파일러 "recursive problem").
    private val speechRecognizerHelper: SpeechRecognizerHelper by lazy {
        SpeechRecognizerHelper(this, voiceRecognitionListener)
    }

    private val requestRecordAudioPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startVoiceListening() else showVoiceError(getString(R.string.mic_permission_denied))
        }

    private val voiceRecognitionListener: SpeechRecognizerHelper.Listener = object : SpeechRecognizerHelper.Listener {
        override fun onPartialResult(text: String) {
            runOnUiThread { binding.voicePartialText.text = text }
        }

        override fun onVolumeChanged(rmsdB: Float) {
            runOnUiThread { binding.listeningIndicator.setVolumeLevel(rmsdB) }
        }

        override fun onFinalResult(text: String) {
            // pback의 /v1/intent/slot-fill이 붙기 전까지는 되묻기 없이 바로 확인 문장으로
            // 감싼다(목업). 실제 슬롯필링이 오면 missing_slots 유무로 SLOT_FILLING과 분기한다.
            Log.i(LOG_TAG, "voice final result: $text")
            runOnUiThread {
                val response = mockSlotFillResponse(text)
                lastResolvedGoal = response.resolvedGoal
                binding.confirmQuestionText.text = response.confirmationQuestion
                binding.confirmEchoText.text = text
                setVoiceFlowState(VoiceFlowState.CONFIRMING)
            }
        }

        override fun onError(error: Int) {
            Log.w(LOG_TAG, "voice recognizer error: $error")
            runOnUiThread {
                val stillWaitingForSpeech =
                    (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) &&
                        (voiceFlowState == VoiceFlowState.LISTENING || voiceFlowState == VoiceFlowState.SLOT_FILLING)
                when {
                    // 원 지시서의 "자동 음성 감지(VAD)" 1차 방식: 침묵·무인식은 실패가 아니라
                    // "아직 말을 안 함"이라 버튼 없이 조용히 다시 듣는다.
                    stillWaitingForSpeech -> speechRecognizerHelper.start()
                    error == SpeechRecognizerHelper.ERROR_UNAVAILABLE -> {
                        showVoiceError(getString(R.string.voice_recognizer_unavailable))
                        setVoiceFlowState(VoiceFlowState.IDLE)
                    }
                    else -> setVoiceFlowState(VoiceFlowState.IDLE)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // targetSdk 35+는 edge-to-edge 강제라 manifest의 adjustResize만으로는 창이 안 줄어든다.
        // 인셋을 **마진**으로 반영해 ScrollView 크기 자체를 줄인다 — 그래야 onSizeChanged가 불리고,
        // "리사이즈 때 포커스된 뷰를 화면 안에 유지"하는 프레임워크 로직이 입력칸을 키보드 위로
        // 올린다. 패딩 방식은 ScrollView의 가시 영역 계산이 패딩을 무시해 스크롤이 안 됐다
        // (#78 — SM-S921N 실측: fitsSystemWindows·인셋 패딩·requestRectangleOnScreen 모두 실패).
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

        binding.btnStop.setOnClickListener { GuideSession.stop() }
        binding.btnVoiceIdle.setOnClickListener { onVoiceIdleTapped() }
        binding.btnVoiceIdle.setOnTouchListener { view, event -> onVoiceIdleTouch(view, event) }

        // 개발자 전용 미리보기는 release 빌드에서 사용자에게 보이면 안 된다.
        binding.devPreviewSection.visibility = if (BuildConfig.DEBUG) View.VISIBLE else View.GONE

        binding.btnRetryListening.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            startVoiceListening()
        }
        binding.btnRetrySlotfill.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            startVoiceListening()
        }

        binding.btnConfirmReject.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            startVoiceListening()
        }
        binding.btnConfirmAccept.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            setVoiceFlowState(VoiceFlowState.IDLE)
            GuideSession.start(lastResolvedGoal)
        }

        // 개발자 전용 — 되묻기(SLOT_FILLING) 실제 진입 조건이 붙기 전까지 시각 검증용.
        // VAD: 화면 전환 후 버튼 없이 바로 다시 듣는다.
        binding.btnDevPreviewSlotfill.setOnClickListener {
            binding.slotfillQuestionText.text = getString(R.string.voice_slotfill_demo_question)
            binding.slotfillEchoText.text = getString(R.string.voice_slotfill_demo_echo)
            setVoiceFlowState(VoiceFlowState.SLOT_FILLING)
            speechRecognizerHelper.start()
        }

        // 개발자 전용 — STT 없이 최종 확인 카드(X/O 포함)를 바로 검증한다.
        binding.btnDevPreviewConfirm.setOnClickListener {
            val demoUtterance = getString(R.string.voice_slotfill_demo_echo)
            val response = mockSlotFillResponse(demoUtterance)
            lastResolvedGoal = response.resolvedGoal
            binding.confirmQuestionText.text = response.confirmationQuestion
            binding.confirmEchoText.text = demoUtterance
            setVoiceFlowState(VoiceFlowState.CONFIRMING)
        }
    }

    /** 목업 주석의 "입체 묘사·눌리는 효과"를 살짝의 축소 애니메이션으로 표현한다. */
    private fun onVoiceIdleTouch(view: View, event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                view.animate().scaleX(VOICE_BUTTON_PRESSED_SCALE).scaleY(VOICE_BUTTON_PRESSED_SCALE)
                    .setDuration(VOICE_BUTTON_ANIM_MS).start()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                view.animate().scaleX(1f).scaleY(1f).setDuration(VOICE_BUTTON_ANIM_MS).start()
        }
        // 눌림 효과만 얹고 실제 클릭 처리는 그대로 OnClickListener에 맡긴다.
        return false
    }

    /** 권한은 설정 화면에 다녀와야 켜진다. 돌아올 때마다 다시 읽는다. */
    override fun onResume() {
        super.onResume()
        GuideSession.addListener(sessionListener)
        refreshUi()
    }

    override fun onPause() {
        GuideSession.removeListener(sessionListener)
        // 화면을 벗어나는데 마이크를 계속 열어 두지 않는다.
        speechRecognizerHelper.stop()
        setVoiceFlowState(VoiceFlowState.IDLE)
        super.onPause()
    }

    // 온보딩(권한 설정)은 이 화면 담당이 아니다 — 다른 팀원의 별도 온보딩 화면 몫이라
    // 여기서는 접근성·오버레이 권한을 아예 확인하지 않는다. 텍스트 목표 입력·시작 버튼도
    // 삭제해 음성 흐름이 유일한 시작 경로다.
    private fun refreshUi() {
        binding.btnStop.isEnabled = GuideSession.isActive
    }

    private fun onVoiceIdleTapped() {
        if (hasRecordAudioPermission()) {
            startVoiceListening()
        } else {
            requestRecordAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startVoiceListening() {
        binding.voicePartialText.text = getString(R.string.voice_listening_placeholder)
        binding.listeningIndicator.resetVolumeLevel()
        setVoiceFlowState(VoiceFlowState.LISTENING)
        speechRecognizerHelper.start()
    }

    private fun setVoiceFlowState(state: VoiceFlowState) {
        voiceFlowState = state

        // LISTENING부터는 voice_fullscreen_overlay가 전체화면으로 뜨고, 그 안의 세 카드 중
        // 현재 상태에 해당하는 것만 보인다. IDLE이면 오버레이 전체를 내린다.
        binding.voiceFullscreenOverlay.visibility = if (state == VoiceFlowState.IDLE) View.GONE else View.VISIBLE
        binding.voiceListeningFullscreen.visibility =
            if (state == VoiceFlowState.LISTENING) View.VISIBLE else View.GONE
        binding.voiceSlotfillFullscreen.visibility =
            if (state == VoiceFlowState.SLOT_FILLING) View.VISIBLE else View.GONE
        binding.voiceConfirmFullscreen.visibility =
            if (state == VoiceFlowState.CONFIRMING) View.VISIBLE else View.GONE

        // SLOT_FILLING도 화면 전환 없이 계속 듣는다(VAD) — 인식기를 끄면 안 되는 상태는
        // 이 둘뿐이다.
        if (state != VoiceFlowState.LISTENING && state != VoiceFlowState.SLOT_FILLING) {
            speechRecognizerHelper.stop()
        }
    }

    private fun showVoiceError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    /**
     * pback의 `/v1/intent/slot-fill` 목업. 아직 배포 전이라 이 함수 하나에 몰아둔다 —
     * 실제 API로 바뀌면 여기만 고치면 된다(필드명 `resolved_goal`도 팀 합의로 미확정).
     *
     * 진짜로는 서버가 슬롯을 조합해 `confirmation_question`과 `resolved_goal`을 함께
     * 만들어 준다. 목업 단계에선 슬롯 데이터가 없으므로 인식된 발화를 그대로 목표로 쓴다 —
     * 목적은 자연어 목표 합성이 아니라 화면4 → `GuideSession.start()` 파이프라인 연결이다.
     */
    private data class MockSlotFillResult(val confirmationQuestion: String, val resolvedGoal: String)

    private fun mockSlotFillResponse(utterance: String): MockSlotFillResult =
        MockSlotFillResult(
            confirmationQuestion = buildConfirmSentence(utterance),
            resolvedGoal = utterance,
        )

    /**
     * 확인 문장을 조금 더 자연스럽게 만드는 목업 전용 근사치. 실제로는 pback의 LLM이
     * 사용자 말투를 반영해 `confirmation_question`을 직접 만들어 그대로 내려주므로
     * (pingpong/CONTRACT.md §2 — 앱은 이 문장을 다시 쓰지 않는다), 이 함수는 그 전까지만
     * 쓰는 임시 눈속임이다. 진짜 한국어 형태소 분석이 아니라 흔한 반말 종결 어미 몇 개만
     * 표에서 찾아 "-는 거 맞으시죠?"로 바꾸고, 표에 없으면 기존 방식(원문 그대로 + "하시는
     * 거 맞으시죠?")으로 안전하게 fallback한다.
     */
    private fun buildConfirmSentence(utterance: String): String {
        val trimmed = utterance.trimEnd('!', '~', '.', '?', ' ')
        val match = CASUAL_ENDING_REPLACEMENTS.firstOrNull { (suffix, _) -> trimmed.endsWith(suffix) }
        return if (match != null) {
            val stem = trimmed.removeSuffix(match.first) + match.second
            getString(R.string.voice_confirm_normalized_template, stem)
        } else {
            getString(R.string.voice_confirm_template, utterance)
        }
    }

    private fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val LOG_TAG = "GuideSvc"

        // 화면1 버튼 눌림 애니메이션. 리소스가 아니라 코드 상수인 이유는 float 값을 dimens.xml에
        // 담으려면 minSdk 26에서 아직 안 열린 Resources.getFloat API가 필요하기 때문이다.
        const val VOICE_BUTTON_PRESSED_SCALE = 0.92f
        const val VOICE_BUTTON_ANIM_MS = 80L

        // buildConfirmSentence()의 목업 어미 치환표. 긴 어미부터 검사해야 짧은 어미가
        // 먼저 걸려 잘못 잘리지 않는다(예: "낼래"보다 "래"가 먼저 걸리면 안 됨).
        val CASUAL_ENDING_REPLACEMENTS: List<Pair<String, String>> = listOf(
            "했는데요" to "하는", "했는데" to "하는",
            "했어요" to "하는", "했어" to "하는",
            "고 싶어요" to "는", "고 싶어" to "는",
            "고싶어요" to "는", "고싶어" to "는",
            "할래요" to "하는", "할래" to "하는",
            "낼래요" to "내는", "낼래" to "내는",
            "갈래요" to "가는", "갈래" to "가는",
            "볼래요" to "보는", "볼래" to "보는",
        ).sortedByDescending { it.first.length }
    }
}
