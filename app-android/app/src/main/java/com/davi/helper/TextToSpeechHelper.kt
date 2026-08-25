package com.davi.helper

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID

/** 서버가 만든 한 문장을 Android TTS로 읽는 최소 경계. 원문을 로그하거나 저장하지 않는다. */
class TextToSpeechHelper(context: Context) {
    private data class Pending(
        val text: String,
        val onDone: () -> Unit,
        val onError: () -> Unit,
    )

    private var ready = false
    private var failed = false
    private var destroyed = false
    private var pending: Pending? = null
    private var activeId: String? = null
    private var activeDone: (() -> Unit)? = null
    private var activeError: (() -> Unit)? = null

    private lateinit var textToSpeech: TextToSpeech

    init {
        textToSpeech = TextToSpeech(context.applicationContext, ::onInitialized).apply {
            setOnUtteranceProgressListener(
                object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit

                    override fun onDone(utteranceId: String?) = finishWithSuccess(utteranceId)

                    @Deprecated("Android framework callback")
                    override fun onError(utteranceId: String?) = finishWithError(utteranceId)

                    override fun onError(utteranceId: String?, errorCode: Int) =
                        finishWithError(utteranceId)
                },
            )
        }
    }

    @Synchronized
    private fun onInitialized(status: Int) {
        if (destroyed) return
        val languageResult = if (status == TextToSpeech.SUCCESS) {
            textToSpeech.setLanguage(Locale.KOREAN)
        } else {
            TextToSpeech.ERROR
        }
        ready = languageResult != TextToSpeech.LANG_MISSING_DATA &&
            languageResult != TextToSpeech.LANG_NOT_SUPPORTED &&
            languageResult != TextToSpeech.ERROR
        failed = !ready
        val waiting = pending.also { pending = null }
        if (ready) waiting?.let(::speakNow) else waiting?.onError?.invoke()
    }

    /**
     * 이 인스턴스로는 **앞으로도** 못 읽는가(엔진 초기화 실패·한국어 미지원·이미 종료).
     *
     * 개별 발화 실패와 다르다 — 그쪽은 다음 발화가 성공할 수 있다. 부르는 쪽이 헛수고를
     * 미리 접는 데 쓴다(#450: 오디오 포커스를 잡았다 놓는 왕복이 매 안내마다 도는 것).
     * **초기화 전에는 false다** — 아직 갈리지 않았고, [speak]가 그때는 발화를 쥐고 기다린다.
     */
    @Synchronized
    fun isUnavailable(): Boolean = failed || destroyed

    @Synchronized
    fun speak(text: String, onDone: () -> Unit, onError: () -> Unit) {
        val normalized = text.trim()
        if (normalized.isEmpty() || destroyed || failed) {
            onError()
            return
        }
        val request = Pending(normalized, onDone, onError)
        if (!ready) {
            pending = request
            return
        }
        speakNow(request)
    }

    @Synchronized
    fun stop() {
        pending = null
        clearActive()
        textToSpeech.stop()
    }

    @Synchronized
    fun shutdown() {
        if (destroyed) return
        destroyed = true
        stop()
        textToSpeech.shutdown()
    }

    @Synchronized
    private fun speakNow(request: Pending) {
        textToSpeech.stop()
        val id = UUID.randomUUID().toString()
        activeId = id
        activeDone = request.onDone
        activeError = request.onError
        if (textToSpeech.speak(request.text, TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.ERROR) {
            finishWithError(id)
        }
    }

    @Synchronized
    private fun finishWithSuccess(utteranceId: String?) {
        if (utteranceId != activeId) return
        val callback = activeDone
        clearActive()
        callback?.invoke()
    }

    @Synchronized
    private fun finishWithError(utteranceId: String?) {
        if (utteranceId != activeId) return
        val callback = activeError
        clearActive()
        callback?.invoke()
    }

    private fun clearActive() {
        activeId = null
        activeDone = null
        activeError = null
    }
}
