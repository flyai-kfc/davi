package com.davi.helper

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log
import android.view.accessibility.AccessibilityManager

/**
 * 안내 문구를 **소리로도** 읽어 준다(#450). 화면만으로는 닿지 않는 사람이 있다 — 글자를 읽는
 * 것 자체가 부담이거나, 돋보기를 안 썼거나, 안내 대상 앱을 보느라 안내창을 못 보고 있다.
 *
 * **읽는 계기는 표시다.** `GuideAccessibilityService.draw` 한 자리가 화면에 뜨는 모든 문구를
 * 지나가므로 그 자리에서만 부른다. 무엇을 읽을지(반복 억제·정체 접미사)는 [GuideSpeechGate]가,
 * 읽어도 되는지는 여기가 판정한다.
 *
 * **읽지 않는 경우가 셋이다.**
 * - **TalkBack 등 낭독 스크린리더가 켜져 있을 때.** 그 사용자에게는 우리 창의 글자를 이미
 *   시스템이 읽어 준다 — 우리가 또 읽으면 **같은 말이 두 목소리로 겹친다.** 어느 쪽을 죽일지는
 *   고를 여지가 없다: 스크린리더는 우리 창 밖(다른 앱·시스템 UI)까지 읽어 주는 사용자의
 *   상시 도구이고, 우리 낭독은 우리 창에서만 도는 곁들이다.
 * - **통화 중일 때.** 통화 상대의 말 위에 안내가 얹힌다. 벨이 울리는 중(`MODE_RINGTONE`)도
 *   같이 막는다 — 받기 직전이 안내를 들을 순간은 아니다.
 * - **읽을 문구가 없거나 TTS 엔진이 한국어를 못 낼 때**([TextToSpeechHelper]가 판정한다).
 *
 * **끄는 스위치는 두지 않는다.** 앱에 설정 화면이 아직 없고(`docs/SCREENS.md`), 새로 만들면
 * 온보딩에 화면이 하나 느는 값을 이 기능 하나가 못 낸다. **손잡이는 이미 있다** — 낭독은
 * 미디어 볼륨을 타므로(아래) 볼륨을 내리면 조용해지고, 그건 시니어가 이미 아는 조작이다.
 * 그래서 「소리로 읽어주기」를 화면에 두는 판정은 설정 화면이 실제로 생길 때 함께 한다.
 *
 * **다른 앱의 소리를 죽이지 않는다.** 안내는 카카오톡·유튜브 위에서 돌기 때문에 오디오 포커스를
 * `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`으로 잡는다 — 상대는 **잠깐 작아질 뿐 멈추지 않고**,
 * 우리가 한 문장을 끝내면 곧바로 돌려준다. 영구 포커스나 `TRANSIENT`(상대를 멈추는 쪽)를 쓰면
 * "안내를 켰더니 보던 영상이 멈췄다"가 된다 — 우리는 **거드는 쪽**이다.
 *
 * **스트림은 미디어다.** [TextToSpeech][android.speech.tts.TextToSpeech] 기본값이 그렇고,
 * [MicTone]이 같은 이유로 거기에 맞춰져 있다. 갈라 두면 무음 모드에서 **한쪽만 나는** 조합이
 * 생긴다. 대가도 그 클래스에 적힌 것과 같다 — **미디어 볼륨이 0이면 아무 소리도 없다.**
 *
 * 메인 스레드에서 만든다. [speak]·[repeat]·[stop]도 메인이고, TTS 완료 콜백만 다른 스레드에서
 * 와 [focusLock]으로 만난다.
 *
 * 생성자 `context`는 필드로 들지 않는다 — 아래 초기화에서만 쓰고, 붙잡으면 서비스 Context가
 * 이 객체 수명에 묶인다([TextToSpeechHelper]는 applicationContext를 따로 잡는다).
 */
class GuideSpeaker(context: Context) {

    private val gate = GuideSpeechGate()

    /**
     * **엔진 연결을 세션마다 붙였다 떼지 않는다.** TTS 초기화는 비동기라 세션 시작에 붙이면
     * 첫 안내가 그 왕복을 기다린다 — 하필 그 순간이 "다비가 말을 거는" 첫인상이다.
     * 대가는 서비스가 사는 동안 엔진 바인딩 하나를 쥐는 것이고, 그건 감수한다.
     */
    private val tts = TextToSpeechHelper(context)

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val accessibilityManager =
        context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager

    /** 포커스 상태를 만지는 자리. TTS 완료 콜백이 엔진 스레드에서 들어온다. */
    private val focusLock = Any()

    private var focusHeld = false

    /** 직전에 로그로 남긴 억제 사유. 폴링마다 같은 줄을 찍지 않으려고 전이에서만 남긴다. */
    private var lastSuppression: String? = null

    private val focusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            // **포커스를 잃으면 읽던 것을 끊는다.** 되찾아 이어 읽지는 않는다 — 한 문장은 짧고,
            // 다음 안내가 새로 잡는다. 끊지 않으면 두 가지가 어긋난다: 전화가 들어와 상대가
            // 포커스를 통째로 가져간 위로 안내가 계속 나가고, [focusHeld]가 참으로 남아 다음
            // 발화가 **포커스를 다시 잡지 않는다**(상대는 낮아지지 않은 채 우리 목소리만 얹힌다).
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS ||
                    change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                ) {
                    stop()
                }
            }
            .build()
    }

    /**
     * 안내창에 [text]가 떴다. 새로 읽을 것이 있으면 읽는다.
     *
     * **억제 중에도 [GuideSpeechGate]는 지나간다.** 안 그러면 통화가 끝난 뒤 그동안 바뀐
     * 안내의 뒷부분만 읽는 조합이 생긴다(그 클래스의 [GuideSpeechGate.next] 주석).
     */
    fun speak(text: String) {
        val utterance = gate.next(text)
        if (utterance == null || !canSpeak()) return
        speakNow(utterance)
    }

    /**
     * 다시 듣기 — 지금 떠 있는 안내를 처음부터 다시 읽는다. 계기가 왜 안내창 면의 탭인지는
     * 그 탭을 다는 자리에 있다([ControlOverlay]의 `create`).
     *
     * **읽을 것이 없거나 억제 중이면 조용히 아무 일도 없다.** 부르는 쪽이 같은 판정을 한 번 더
     * 하게 두지 않는다 — 두 벌이 되면 갈린다.
     */
    fun repeat() {
        val utterance = gate.repeat() ?: return
        if (!canSpeak()) return
        Log.i(TAG, "speech repeat")
        speakNow(utterance)
    }

    /** 세션 경계. 다음 세션의 첫 안내는 문구가 같아도 읽는다. */
    fun reset() {
        stop()
        gate.reset()
    }

    /** 읽던 것을 끊는다(화면 꺼짐 등). 떠 있는 안내 자체는 [gate]가 그대로 들고 있다. */
    fun stop() {
        tts.stop()
        abandonFocus()
    }

    fun shutdown() {
        gate.reset()
        tts.shutdown()
        abandonFocus()
    }

    private fun speakNow(utterance: String) {
        requestFocus()
        // **문구 자체는 로그에 남기지 않는다** — 안내문에는 화면 label 인용이 들어간다(#58).
        // 길이만 남기는 이유는 실기기 확인이 logcat 수동이라, 이 줄이 없으면 "소리가 안 났다"와
        // "읽을 것이 없어 안 읽었다"가 구별되지 않기 때문이다.
        Log.i(TAG, "speak len=${utterance.length}")
        tts.speak(utterance, onDone = ::abandonFocus, onError = {
            Log.w(TAG, "speech failed")
            abandonFocus()
        })
    }

    /**
     * **엔진이 아예 못 읽는 상태면 포커스도 잡지 않는다.** 한국어 목소리가 없는 기기에서는
     * 모든 발화가 곧바로 실패로 돌아오는데, 그 앞에서 포커스를 잡았다 놓으면 안내마다 다른 앱
     * 소리가 한 번씩 흔들린다 — 우리가 소리를 내지도 않으면서.
     */
    private fun canSpeak(): Boolean = suppression() == null && !tts.isUnavailable()

    /**
     * 지금 읽으면 안 되는 이유. 없으면 null.
     *
     * 매 표시마다 두 번의 바인더 왕복이 있지만 표시는 초당 몇 회를 넘지 않고, **값을 캐시하면
     * 통화가 시작된 뒤 한 문장이 그 위로 나가는 창이 생긴다** — 그쪽이 더 나쁘다.
     */
    private fun suppression(): String? {
        val reason = when {
            screenReaderActive() -> "screen_reader"
            inCall() -> "call"
            else -> null
        }
        if (reason != lastSuppression) {
            lastSuppression = reason
            Log.i(TAG, "speech suppressed=${reason ?: "none"}")
        }
        return reason
    }

    /**
     * 낭독 스크린리더가 켜져 있는가. **우리 서비스는 여기 안 걸린다** —
     * `accessibility_service_config.xml`이 `feedbackGeneric`이라 `FEEDBACK_SPOKEN` 목록 밖이다.
     */
    private fun screenReaderActive(): Boolean {
        val manager = accessibilityManager ?: return false
        return runCatching {
            manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN)
                .isNotEmpty()
        }.getOrDefault(false)
    }

    private fun inCall(): Boolean {
        val mode = audioManager?.mode ?: return false
        return mode == AudioManager.MODE_IN_CALL ||
            mode == AudioManager.MODE_IN_COMMUNICATION ||
            mode == AudioManager.MODE_RINGTONE
    }

    private fun requestFocus() {
        val manager = audioManager ?: return
        synchronized(focusLock) {
            if (focusHeld) return
            val granted = manager.requestAudioFocus(focusRequest)
            // 거절돼도 읽는다. 포커스는 상대를 잠깐 낮춰 달라는 부탁이지 소리를 낼 자격이 아니고,
            // 안 낮아지는 기기에서 안내가 통째로 조용해지는 편이 더 나쁘다.
            if (granted != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                Log.w(TAG, "audio focus denied=$granted")
                return
            }
            focusHeld = true
        }
    }

    private fun abandonFocus() {
        val manager = audioManager ?: return
        synchronized(focusLock) {
            if (!focusHeld) return
            focusHeld = false
            manager.abandonAudioFocusRequest(focusRequest)
        }
    }

    private companion object {
        const val TAG = GuideAccessibilityService.TAG
    }
}
