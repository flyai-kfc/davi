package com.davi.helper

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * `android.speech.SpeechRecognizer`를 감싸는 얇은 헬퍼.
 *
 * 화면3의 되묻기 반복이나 VAD 재시작이 실제 프레임워크 콜백 모양을 몰라도 되도록
 * partial/final/error 세 콜백만 노출한다. 세션 하나(start ~ 결과 하나)만 책임지고,
 * 반복 호출은 이 헬퍼를 쓰는 쪽(지금은 `MainActivity`)이 맡는다.
 *
 * `SpeechRecognizer`는 이 헬퍼를 생성한 스레드(Looper)에서만 안전하다 — 항상 메인 스레드에서
 * 만들고 쓴다.
 */
class SpeechRecognizerHelper(
    context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onPartialResult(text: String)
        fun onFinalResult(text: String)

        /** [error]는 `SpeechRecognizer.ERROR_*` 상수 또는 [ERROR_UNAVAILABLE]. */
        fun onError(error: Int)

        /** 마이크 음량이 바뀔 때마다. 대략 -2~10+dB — 파형 표시 등 시각 피드백 전용이다. */
        fun onVolumeChanged(rmsdB: Float)
    }

    private val appContext = context.applicationContext
    private var recognizer: SpeechRecognizer? = null

    val isAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(appContext)

    /** 이전 세션이 남아 있으면 정리하고 새로 듣기 시작한다. RECORD_AUDIO 권한은 호출자 책임. */
    fun start() {
        if (!isAvailable) {
            listener.onError(ERROR_UNAVAILABLE)
            return
        }
        stop()
        recognizer = SpeechRecognizer.createSpeechRecognizer(appContext).apply {
            setRecognitionListener(recognitionListener)
            startListening(buildRecognizerIntent())
        }
    }

    /** 듣기를 멈추고 리소스를 해제한다. 결과를 기다리지 않는다(사용자가 화면을 벗어난 경우 등). */
    fun stop() {
        recognizer?.apply {
            setRecognitionListener(null)
            cancel()
            destroy()
        }
        recognizer = null
    }

    private fun buildRecognizerIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
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
            firstResult(results)?.let(listener::onFinalResult)
        }

        override fun onError(error: Int) {
            listener.onError(error)
        }

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = listener.onVolumeChanged(rmsdB)
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    companion object {
        /** 기기에 음성 인식기가 아예 없을 때. `SpeechRecognizer.ERROR_*`는 전부 양수라 겹치지 않는다. */
        const val ERROR_UNAVAILABLE = -1
    }
}
