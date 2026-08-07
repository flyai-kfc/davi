package com.davi.helper

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

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
    }

    private val appContext = context.applicationContext
    private var recognizer: SpeechRecognizer? = null
    private var usingOnDevice = false
    private var fallbackAttempted = false

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
        startRecognizer(onDeviceAvailable())
    }

    private fun startRecognizer(onDevice: Boolean) {
        try {
            usingOnDevice = onDevice
            val created = createRecognizer(onDevice)
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
    }

    private fun destroyRecognizer() {
        recognizer?.apply {
            setRecognitionListener(null)
            cancel()
            destroy()
        }
        recognizer = null
    }

    private fun createRecognizer(onDevice: Boolean): SpeechRecognizer =
        if (onDevice) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
        } else {
            SpeechRecognizer.createSpeechRecognizer(appContext)
        }

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

    private val recognitionListener = object : RecognitionListener {
        override fun onPartialResults(partialResults: Bundle) {
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
                startRecognizer(onDevice = false)
            } else {
                listener.onError(error)
            }
        }
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
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
