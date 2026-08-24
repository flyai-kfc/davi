package com.davi.helper

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi

/**
 * pfront에서 선택 이식한 `SpeechRecognizer` 경계.
 *
 * 원시 오디오나 인식 결과를 저장·로그하지 않는다. Android 12+에서 온디바이스 인식기가 있으면
 * 우선 사용하고, 없을 때도 오프라인 선호 힌트를 보낸다. 실제 제공자 동작은 기기 설정에 달려 있다.
 * 생성·호출·정리는 모두 메인 스레드에서 한다.
 */
class SpeechRecognizerHelper(
    context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onPartialResult(text: String)
        fun onFinalResult(text: String)
        fun onError(error: Int)

        /**
         * 인식기를 새로 붙이는 중 — 아직 마이크는 닫혀 있다. [onMicReady]와 한 쌍이다.
         *
         * **온디바이스→네트워크 폴백에서도 다시 불린다.** 완료 쪽만 받으면 그 폴백에서
         * 화면은 "듣고 있어요"를 켠 채인데 마이크만 닫혔다 다시 열린다(#273).
         */
        fun onMicPreparing() = Unit

        /**
         * **마이크가 실제로 소리를 넘기기 시작했다.** 화면이 "듣고 있어요"를 띄우는 시점이
         * 이 신호다(#273).
         *
         * 근거는 셋 중 **먼저 오는 것** 하나다 — 첫 음량, 발화 시작, 첫 부분 인식.
         * 셋 다 "이미 받아들이고 있다"의 확정 신호라 이르다가 될 수 없고, 세기를 주지 않는
         * 인식기에서는 뒤의 둘이 받는다. 세기 하나에만 걸면 그런 인식기에서 **받아쓰기가
         * 찍히는 중인데 화면은 "아직 안 듣는다"고 말하는** 거울상이 생긴다(PR #276 리뷰).
         *
         * **"마이크가 열렸다"와 "인식이 시작됐다"가 다르다**는 것이 2026-08-19 실측의 핵심이다
         * (SM-S921N, 청취 8회). `startListening()` 기준으로 오디오 정책의 입력 개시와 시스템
         * 마이크 표시는 +60ms, `onReadyForSpeech`는 +240~290ms, 첫 음량은 +890~940ms에 왔다.
         * 그런데 **첫 음량 전에 한 말은 마이크가 열려 있어도 인식되지 않았다** — 사용자가
         * 겪은 잘림이 이 구간이다. `onReadyForSpeech`는 이름 그대로 "엔드포인터가 준비됐다"이지
         * "녹음한 것을 받아들인다"가 아니라서 화면 기준으로 쓸 수 없다.
         */
        fun onMicReady() = Unit

        /**
         * `onReadyForSpeech` 그대로 — **진단용**이다. 화면 판단에 쓰지 않는다.
         *
         * 이 신호와 [onMicReady] 사이가 기기·인식기마다 얼마나 벌어지는지를 로그에 남겨
         * 두려고 유지한다. 위 실측에서 그 간격은 약 650ms였고, 다른 기기에서 다시 확인할 때도
         * 이 두 줄의 간격이 답이다.
         */
        fun onEndpointerReady() = Unit

        /**
         * 마이크 입력 세기(dB). 파도·글로우가 목소리에 반응하는 데만 쓴다(#132).
         *
         * **원시 오디오가 아니라 세기 하나뿐이다** — 내용이 남지 않으므로 이 클래스의
         * "저장·로그하지 않는다" 경계를 넘지 않는다. 그래도 로그로 흘리지는 않는다.
         */
        fun onRmsChanged(rmsdB: Float) = Unit
    }

    private val appContext = context.applicationContext
    private var recognizer: SpeechRecognizer? = null
    private var usingOnDevice = false
    private var fallbackAttempted = false

    /** 이번 인식 세션에서 [Listener.onMicReady]를 이미 보냈는가. 첫 음량 이벤트에서만 보낸다. */
    private var micReadySignalled = false

    val isAvailable: Boolean
        get() = onDeviceAvailable() || SpeechRecognizer.isRecognitionAvailable(appContext)

    /** 이전 인식 세션을 버리고 결과 하나를 새로 듣는다. RECORD_AUDIO 권한은 호출자 책임이다. */
    fun start() {
        if (!isAvailable) {
            listener.onError(ERROR_UNAVAILABLE)
            return
        }
        stop()
        fallbackAttempted = false
        if (onDeviceAvailable()) {
            startRecognizer(onDevice = true) { createOnDeviceRecognizer() }
        } else {
            startNetworkRecognizer()
        }
    }

    private fun startNetworkRecognizer() =
        startRecognizer(onDevice = false) { SpeechRecognizer.createSpeechRecognizer(appContext) }

    private fun startRecognizer(
        onDevice: Boolean,
        createRecognizer: () -> SpeechRecognizer,
    ) {
        // 폴백 재시작도 이 함수를 타므로 "준비 중"이 그때마다 다시 나간다(#273).
        micReadySignalled = false
        listener.onMicPreparing()
        try {
            usingOnDevice = onDevice
            val created = createRecognizer()
            // startListening이 던져도 catch의 stop()이 방금 만든 인스턴스를 회수할 수 있게 먼저 보관한다.
            recognizer = created
            created.setRecognitionListener(recognitionListener)
            created.startListening(buildRecognizerIntent())
        } catch (_: RuntimeException) {
            stop()
            listener.onError(ERROR_START_FAILED)
        }
    }

    /** 화면 이탈·대화 종료 시 결과를 기다리지 않고 마이크와 프레임워크 자원을 놓는다. */
    fun stop() {
        destroyRecognizer()
        usingOnDevice = false
        fallbackAttempted = false
        micReadySignalled = false
    }

    private fun destroyRecognizer() {
        recognizer?.apply {
            setRecognitionListener(null)
            cancel()
            destroy()
        }
        recognizer = null
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun createOnDeviceRecognizer(): SpeechRecognizer =
        SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)

    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.S)
    private fun onDeviceAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)

    private fun buildRecognizerIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

    private fun firstResult(bundle: Bundle): String? =
        bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    /**
     * "이제 들린다"를 한 번만 알린다. 부르는 자리가 셋이라(첫 음량·발화 시작·첫 부분 인식)
     * 한곳으로 모은다 — 어느 것이 먼저 오는지는 인식기마다 다르고, 우리에겐 먼저 온 것이 답이다.
     */
    private fun signalMicReadyOnce() {
        if (micReadySignalled) return
        micReadySignalled = true
        listener.onMicReady()
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onPartialResults(partialResults: Bundle) {
            // 받아쓰기가 왔다는 것 자체가 이미 듣고 있다는 뜻이다. 화면을 먼저 "듣는 중"으로
            // 돌리고 나서 문장을 넘긴다 — 순서가 반대면 그 프레임에 문장만 있고 레이블이 없다.
            signalMicReadyOnce()
            firstResult(partialResults)?.let(listener::onPartialResult)
        }

        override fun onResults(results: Bundle) {
            val text = firstResult(results)
            if (text == null) listener.onError(SpeechRecognizer.ERROR_NO_MATCH)
            else listener.onFinalResult(text)
        }

        override fun onError(error: Int) {
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED &&
                usingOnDevice &&
                !fallbackAttempted
            ) {
                fallbackAttempted = true
                destroyRecognizer()
                startNetworkRecognizer()
            } else {
                listener.onError(error)
            }
        }
        override fun onReadyForSpeech(params: Bundle?) = listener.onEndpointerReady()
        override fun onBeginningOfSpeech() = signalMicReadyOnce()
        override fun onRmsChanged(rmsdB: Float) {
            // 첫 음량 = 마이크가 실제로 소리를 넘기기 시작한 지점(#273). 값보다 **도착 자체**가
            // 정보다. 알림을 세기 전달보다 먼저 보내 화면이 파도를 켜고 나서 값을 받게 한다.
            signalMicReadyOnce()
            listener.onRmsChanged(rmsdB)
        }
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    companion object {
        /** `SpeechRecognizer.ERROR_*`는 양수라 내부 오류와 겹치지 않는다. */
        const val ERROR_UNAVAILABLE = -1
        const val ERROR_START_FAILED = -2
    }
}
