package com.davi.helper

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
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
 * **끝 신호가 안 오는 갈래에 그물을 건다**([speechGuardMillis], #452·#465가 대화 화면에서
 * 세운 그 상한을 그대로 쓴다 — 같은 엔진의 같은 결함이라 규칙을 두 벌로 두지 않는다).
 * 그 신호가 유일하게 포커스를 놓는 계기라, 안 오면 **상대 앱이 영영 낮아진 채**로 남는다.
 *
 * 메인 스레드에서 만든다. [speak]·[repeat]·[stop] 전부 메인이고, TTS 콜백은 엔진 스레드에서
 * 오지만 [mainHandler]로 넘겨 **상태는 메인 한 곳에서만** 만진다.
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

    private val mainHandler = Handler(Looper.getMainLooper())

    private var focusHeld = false

    /**
     * 지금 한 문장을 읽는 중인가. [stop]이 **읽던 것을 끊었는지** 알아야 [GuideSpeechGate]에
     * "안 읽은 것으로 되돌려라"를 전할 수 있다 — 아무것도 안 읽던 중의 [stop](세션 시작·
     * 포커스만 정리)까지 되돌리면 같은 안내를 이유 없이 두 번 읽는다.
     */
    private var speaking = false

    /**
     * 발화의 세대. **늦게 도착한 콜백과 늦게 깨어난 그물을 가르는 축이다** — 엔진 스레드가
     * 앞 발화의 `onDone`을 늦게 풀면 그것이 **새 발화의 상태를 지운다**(#465 리뷰가 대화
     * 화면에서 실제로 겪은 갈래다). 자기 세대가 아니면 아무 일도 하지 않는다.
     */
    private var generation = 0

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
        // **억제 판정이 먼저다.** [GuideSpeechGate.repeat]이 "들려줬다"로 상태를 옮기므로,
        // 읽지도 못할 상황에서 먼저 부르면 끊겼던 안내가 조용히 읽은 것으로 적힌다.
        if (!canSpeak()) return
        val utterance = gate.repeat() ?: return
        Log.i(TAG, "speech repeat")
        speakNow(utterance)
    }

    /** 세션 경계. 다음 세션의 첫 안내는 문구가 같아도 읽는다. */
    fun reset() {
        stop()
        gate.reset()
    }

    /**
     * 읽던 것을 끊는다(화면 꺼짐·포커스 상실·시스템의 피드백 중단 요청). 떠 있는 안내 자체는
     * [gate]가 그대로 들고 있되, **문장 중간에 끊었다면 안 읽은 것으로 되돌린다** — 안 그러면
     * 같은 안내가 다시 떠도 문구가 같다는 이유로 영영 안 읽히고, 하필 소리에 기대는 사람이
     * 반 토막만 듣는다.
     */
    fun stop() {
        if (speaking) {
            speaking = false
            gate.markInterrupted()
        }
        tts.stop()
        abandonFocus()
    }

    fun shutdown() {
        speaking = false
        // **그물을 걷는다.** 상한이 문구 길이에 비례해 길어서, 안 걷으면 서비스가 내려간 뒤에
        // 메인 루퍼에서 깨어난다(#465가 액티비티에서 같은 것을 겪었다).
        mainHandler.removeCallbacksAndMessages(GUARD_TOKEN)
        gate.reset()
        tts.shutdown()
        abandonFocus()
    }

    private fun speakNow(utterance: String) {
        val current = ++generation
        requestFocus()
        // **문구 자체는 로그에 남기지 않는다** — 안내문에는 화면 label 인용이 들어간다(#58).
        // 길이만 남기는 이유는 실기기 확인이 logcat 수동이라, 이 줄이 없으면 "소리가 안 났다"와
        // "읽을 것이 없어 안 읽었다"가 구별되지 않기 때문이다.
        Log.i(TAG, "speak len=${utterance.length}")
        speaking = true
        armGuard(current, utterance.length)
        tts.speak(
            utterance,
            onDone = { finish(current) },
            onError = {
                // 발화 하나가 실패한 것이라 끊긴 것으로 되돌리지 않는다 — 되돌리면 못 읽는
                // 문장을 다음 표시마다 다시 시도한다. 엔진 자체가 죽은 경우는 [canSpeak]가 막는다.
                Log.w(TAG, "speech failed")
                finish(current)
            },
            // **낭독이 실제로 시작하면 그물의 시계를 다시 출발시킨다**(#465가 대화 화면에서
            // 세운 것과 같은 이유). 엔진을 처음 바인딩하는 호출은 speak()가 즉시 돌아온 뒤
            // init이 끝날 때까지 한 글자도 안 읽는데, 그 대기를 상한이 먹으면 짧은 문구일수록
            // 읽는 도중에 그물이 돈다. **처음 건 그물을 그대로 두고 갈아 끼운다** — 시작
            // 신호가 영영 안 오는 갈래가 이 그물이 막으려는 바로 그것이다.
            onStarted = { mainHandler.post { armGuard(current, utterance.length) } },
        )
    }

    /**
     * 발화 하나가 끝났다(성공이든 실패든). **엔진 스레드에서 오므로 메인으로 넘긴다.**
     */
    private fun finish(forGeneration: Int) {
        mainHandler.post {
            if (forGeneration != generation) return@post
            speaking = false
            abandonFocus()
        }
    }

    /**
     * 끝 신호가 안 올 때 포커스만 대신 놓는 그물. 상한은 [speechGuardMillis]를 그대로 쓴다.
     *
     * **[GuideSpeechGate.markInterrupted]는 부르지 않는다.** 이 그물은 문장이 끝났어야 할
     * 시각보다 뒤에 도는 것이라, 십중팔구 **다 읽었는데 신호만 잃은** 경우다. 끊긴 것으로
     * 치면 다음 재표시가 같은 문장을 이유 없이 한 번 더 읽는다. 정말 중간에 죽은 경우는
     * 여기서 알 길이 없고, 그 자리의 답은 다시 듣기 탭이다.
     */
    private fun armGuard(forGeneration: Int, length: Int) {
        if (forGeneration != generation) return
        mainHandler.removeCallbacksAndMessages(GUARD_TOKEN)
        mainHandler.postAtTime(
            {
                if (forGeneration != generation || !speaking) return@postAtTime
                Log.w(TAG, "speech end signal missing, releasing focus")
                speaking = false
                abandonFocus()
            },
            GUARD_TOKEN,
            android.os.SystemClock.uptimeMillis() + speechGuardMillis(length),
        )
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
     * **바인더 왕복 둘이 도는 자리이지만 표시마다 도는 것은 아니다.** [speak]에서 `||`가
     * 짧게 끊어, **읽을 것이 실제로 생겼을 때만** 여기까지 온다 — 재표시·강조 내림/되돌림·오류
     * 재시도처럼 내용이 그대로인 호출은 [GuideSpeechGate]가 먼저 잘라낸다. 그래서 빈도는
     * 폴링이 아니라 **안내가 바뀌는 빈도**(서버 왕복당 한 번)에 붙는다. 나머지 하나는 안내창
     * 면 탭이라 사람 손 빠르기다.
     *
     * **값을 캐시하지 않는다.** 캐시하면 통화가 시작된 뒤 한 문장이 그 위로 나가는 창이
     * 생기는데, 위 빈도에서 아낄 것보다 그쪽이 나쁘다.
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

    /**
     * 통화 중인가. 셋을 본다 — 일반 통화(`MODE_IN_CALL`), 보이스톡 같은 VoIP
     * (`MODE_IN_COMMUNICATION`), 벨이 울리는 중(`MODE_RINGTONE`).
     *
     * **`MODE_CALL_SCREENING`(API 30)도 넣는다** — 전화를 걸러 주는 동안은 시스템이 상대 소리를
     * 다루는 중이라 안내가 얹히면 안 된다. 상수가 API 30이라 `minSdk 26`에서 `NewApi`
     * (`lintVitalRelease`에서 fatal)에 걸리므로 버전으로 가른다.
     *
     * **`MODE_CALL_REDIRECT`·`MODE_COMMUNICATION_REDIRECT`(API 31)는 넣지 않는다** — 통화를
     * 다른 앱으로 넘기는 **연결 단계**의 상태라 사용자가 통화 중이라는 뜻이 아니고, 실제로
     * 통화가 서면 위 둘 중 하나로 넘어간다.
     */
    private fun inCall(): Boolean {
        val mode = audioManager?.mode ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            mode == AudioManager.MODE_CALL_SCREENING
        ) {
            return true
        }
        return mode == AudioManager.MODE_IN_CALL ||
            mode == AudioManager.MODE_IN_COMMUNICATION ||
            mode == AudioManager.MODE_RINGTONE
    }

    private fun requestFocus() {
        val manager = audioManager ?: return
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

    private fun abandonFocus() {
        val manager = audioManager ?: return
        if (!focusHeld) return
        focusHeld = false
        manager.abandonAudioFocusRequest(focusRequest)
    }

    private companion object {
        const val TAG = GuideAccessibilityService.TAG

        /** 그물만 골라 걷으려고 쓰는 토큰. 다른 post와 섞이지 않는다. */
        val GUARD_TOKEN = Any()
    }
}
