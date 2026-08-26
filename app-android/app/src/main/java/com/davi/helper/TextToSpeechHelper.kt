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
        /** 엔진이 **실제로 첫 소리를 낸** 순간. 초기화 대기가 여기서 끝난다. */
        val onStarted: () -> Unit = {},
    )

    private var ready = false
    private var failed = false
    private var destroyed = false
    private var pending: Pending? = null
    private var activeId: String? = null
    private var activeDone: (() -> Unit)? = null
    private var activeError: (() -> Unit)? = null
    private var activeStarted: (() -> Unit)? = null

    private lateinit var textToSpeech: TextToSpeech

    init {
        textToSpeech = TextToSpeech(context.applicationContext, ::onInitialized).apply {
            setOnUtteranceProgressListener(
                object : UtteranceProgressListener() {
                    // **초기화가 끝나야 여기가 온다.** 엔진을 처음 바인딩하는 호출에서는
                    // speak()가 즉시 돌아온 뒤 init + setLanguage가 끝날 때까지 한 글자도
                    // 안 읽는다 — 낭독 길이를 재는 쪽은 그 시각을 알아야 한다(#465 리뷰).
                    override fun onStart(utteranceId: String?) = notifyStarted(utteranceId)

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

    @Synchronized
    fun speak(
        text: String,
        onDone: () -> Unit,
        onError: () -> Unit,
        onStarted: () -> Unit = {},
    ) {
        val normalized = text.trim()
        if (normalized.isEmpty() || destroyed || failed) {
            onError()
            return
        }
        val request = Pending(normalized, onDone, onError, onStarted)
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
        activeStarted = request.onStarted
        if (textToSpeech.speak(request.text, TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.ERROR) {
            finishWithError(id)
        }
    }

    @Synchronized
    private fun notifyStarted(utteranceId: String?) {
        if (utteranceId == null || utteranceId != activeId) return
        val started = activeStarted ?: return
        activeStarted = null
        started()
    }

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
        activeStarted = null
    }
}
