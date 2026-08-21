package com.davi.helper

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * **마이크가 실제로 열린 순간** 한 번 울리는 짧은 「띠링」(#313).
 *
 * **왜 누를 때가 아닌가** — 누르고 실제로 들리기까지 약 0.9초가 걸린다(#278에서 줄이려다
 * 실패했다). 누를 때 울리면 **아직 안 듣는 동안 말하게 되어** 첫 음절이 잘린다 — #273이
 * 「듣고 있어요」에서 고친 것과 같은 함정이다. 그래서 파도·레이블·글로우와 **같은 신호**에
 * 걸어 넷이 함께 선다.
 *
 * **왜 소리가 필요한가** — 그 셋은 **보고 있어야** 알 수 있다. 시니어 사용자가 "언제 말해야
 * 할지 모르겠다"고 한 자리라(2026-08-20 관찰), 화면을 안 보고 있어도 귀로 알게 한다.
 *
 * **왜 시스템 인식기의 시작음에 기대지 않는가** — 그 소리는 엔진이 낸다. 네트워크 인식기는
 * 내고 구글 온디바이스(SODA)는 안 낸다. `on_device_recognition_service`가 빈 기기에서 실제로
 * 안 났다(2026-08-19 실측). **기기 설정에 딸린 부수 효과라 기댈 수 없다.**
 *
 * **`ToneGenerator`를 쓰지 않는다.** 처음엔 그걸 썼는데 낼 수 있는 것이 전화기 계열
 * 신호음뿐이라 "삑" 소리가 됐고, **톤 자체가 별로**라는 지적을 받았다(2026-08-20).
 * 여기서는 **두 음을 직접 합성한다** — 음원 파일을 만들지 않으면서 부드러운 차임이 된다.
 *
 * **메인 스레드 전용이다.** [track]에 동기화가 없다 — [prepare]·[play]·[release]가 전부
 * 메인에서 불린다는 전제다. [prepare]가 16,317샘플 `sin()`과 [AudioTrack] 생성(바인더
 * 왕복)을 하는 자리라 **작업 스레드로 옮기고 싶어지는 모양**인데, 그러면 [play]와 조용히
 * 경합한다. 옮길 거면 동기화를 먼저 넣는다.
 *
 * **스트림은 미디어다.** TTS가 타는 것과 같게 둔다 — 다르면 무음 모드에서 **소리는 없고
 * 목소리만 나는** 조합이 생긴다. 무음 모드를 따로 다루지는 않는다.
 *
 * **대신 미디어 볼륨 하나에 묶인다.** 시스템 스트림이면 손잡이가 갈렸는데 이제 같아서,
 * **미디어 볼륨이 0이면 차임도 TTS도 둘 다 없다.** #313의 목표("화면을 안 보고 있어도 귀로
 * 알게 한다")가 그 기기에서 통째로 사라진다 — 받아들이는 트레이드오프다. 소리가 갈리면
 * "다비가 말은 하는데 시작음은 안 난다"가 되어 **오히려 신호가 어긋나기** 때문이다.
 * 볼륨 0 자체를 다루려면 화면 신호(흐림이 걷힘)가 유일한 통로라, 그쪽을 강화하는 편이 맞다.
 */
class MicTone {

    private var track: AudioTrack? = null
    private val samples: ShortArray by lazy { buildChime() }

    /**
     * 파형 계산과 [AudioTrack] 생성을 **미리 끝내 둔다.** 화면에 들어올 때 부른다.
     *
     * 안 부르면 **처음 울릴 때** 16,000샘플 계산과 트랙 생성이 메인 스레드에서 일어난다.
     * 하필 그 순간이 「듣기 시작」이라, 소리가 늦으면 화면·파도와 어긋난다 — 셋이 같이 서야
     * "지금"이 된다.
     *
     * 실패해도 그만이다. [play]가 그때 다시 만든다.
     */
    fun prepare() {
        if (track != null) return
        runCatching { track = buildTrack() }
    }

    /**
     * 한 번 울린다. **트랙을 재사용한다** — 정지 후 같은 버퍼를 되감아 다시 재생하므로
     * 이 자리에서 할당이 일어나지 않는다.
     *
     * 실패하면 조용히 넘긴다. **소리가 없다고 대화가 멈추면 안 된다** — 소리는 거들 뿐이고
     * 「듣고 있어요」와 파도가 같은 순간에 이미 뜬다.
     */
    fun play() {
        try {
            val active = track ?: buildTrack().also { track = it }
            active.stop()
            // **되감기 실패는 예외가 아니라 반환값으로 온다.** 아래 catch에 안 걸리므로
            // 여기서 본다 — 실패하면 재생 위치가 버퍼 끝이라 소리 없이 지나간다.
            val rewound = active.reloadStaticData()
            if (rewound != AudioTrack.SUCCESS) Log.w(TAG, "mic tone rewind failed: $rewound")
            active.play()
        } catch (e: RuntimeException) {
            Log.w(TAG, "mic tone unavailable: ${e.javaClass.simpleName}")
            release()
        }
    }

    private fun buildTrack(): AudioTrack {
        val pcm = samples
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // **미디어다.** TTS(TextToSpeech 기본값)가 STREAM_MUSIC을 타므로 같게 둔다.
                    // SONIFICATION은 STREAM_SYSTEM이라 무음 모드에서 **차임만 죽고 목소리는
                    // 나오는** 조합이 생긴다 — 클래스 문서가 피하려던 그 조합이다.
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(pcm.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
            .apply {
                // **짧게 쓰이면 조용히 무음이 된다.** 실패해도 대화를 멈추지는 않지만
                // (소리는 거들 뿐이다) 왜 안 들렸는지는 로그에 남긴다.
                val written = write(pcm, 0, pcm.size)
                if (written < pcm.size) Log.w(TAG, "mic tone short write: $written/${pcm.size}")
            }
    }

    fun release() {
        track?.let {
            runCatching { it.stop() }
            it.release()
        }
        track = null
    }

    /**
     * 두 음을 **겹쳐** 쌓은 PCM. 낮은 음이 울리는 동안 높은 음이 들어와 "띠-링"으로 이어진다.
     *
     * **종소리처럼 사그라들게 한다.** 평평하게 울리다 끊으면 전자음처럼 들린다 — 짧게 열고
     * 지수적으로 감쇠시키면 같은 음이라도 훨씬 부드럽다. 앞서 "톤 자체가 별로"라는 지적을
     * 받은 원인이 음 높이보다 이 포락선에 있었다(2026-08-20).
     */
    private fun buildChime(): ShortArray {
        val overlap = SAMPLE_RATE * OVERLAP_MS / 1000
        val noteLen = SAMPLE_RATE * NOTE_MS / 1000
        val total = noteLen + overlap
        val out = ShortArray(total)
        listOf(0 to NOTE_LOW_HZ, overlap to NOTE_HIGH_HZ).forEach { (offset, freq) ->
            for (i in 0 until noteLen) {
                val at = offset + i
                if (at >= total) break
                val t = i.toDouble() / SAMPLE_RATE
                val value = sin(2.0 * PI * freq * t) * envelope(i, noteLen) * AMPLITUDE
                out[at] = (out[at] + value).toInt().coerceIn(-32_768, 32_767).toShort()
            }
        }
        return out
    }

    /**
     * 짧게 열고(attack) 길게 사그라든다(decay). 끝을 0으로 맞춰 잡음이 안 붙게 한다.
     */
    private fun envelope(i: Int, total: Int): Double {
        if (i < ATTACK_SAMPLES) return i.toDouble() / ATTACK_SAMPLES
        val progress = (i - ATTACK_SAMPLES).toDouble() / (total - ATTACK_SAMPLES)
        return exp(-DECAY * progress) * (1.0 - progress)
    }

    private companion object {
        const val TAG = "GuideSvc"
        const val SAMPLE_RATE = 44_100

        /** 한 음의 길이. 사그라드는 꼬리를 담아야 해서 넉넉히 준다. */
        const val NOTE_MS = 260

        /** 둘째 음이 들어오는 시점. 겹쳐야 "띠-링"으로 이어지고 계단처럼 안 들린다. */
        const val OVERLAP_MS = 110

        /**
         * E5 → B5(완전 5도). 앞서 A5 → E6는 밝고 날카로웠다 — **한 옥타브 낮춰** 부드럽게 한다.
         */
        const val NOTE_LOW_HZ = 659.25
        const val NOTE_HIGH_HZ = 987.77

        /**
         * 두 음이 겹치므로 **합쳐서** 16bit 최대를 넘지 않아야 한다. 겹치는 구간의 위상이
         * 어긋나 실제 최대는 산술합보다 낮다 — 이 값에서 **29,094/32,767(89%)**이다.
         *
         * **8,000에서 올렸다**(2026-08-21). 스트림을 미디어로 옮기면서(리뷰 #317-2) 소리가
         * 기기 미디어 볼륨에 곱해지는데, 실기기(볼륨 6/15)에서 두 번 "약하다"는 확인을
         * 받았다. 어르신은 볼륨을 낮게 쓰는 경우가 많아 **우리 쪽 여유를 먼저 쓴다.**
         *
         * **여기가 상한이다. 값을 정하는 근거는 하나뿐이다** — 남은 11%는 클리핑까지의
         * 여유가 아니라 **기기 쪽 후처리(음량 정규화·이퀄라이저)가 먹을 수 있는 폭**이다.
         * 다 쓰면 그런 기기에서 파형이 잘려 "지직"이 붙는다.
         *
         * **올리는 쪽으로 갈 때 함께 커지는 비용이 하나 더 있는데, 크기를 모른다.**
         * 차임은 **열려 있는 마이크로 나간다.** [play]가 불리는 시점이 곧 인식기가 받기
         * 시작한 순간이라(`markMicReady`), 스피커로 나가는 370ms는 **정의상 녹음 구간과
         * 겹친다.** 여기서 더 올리면 사용자 목소리에 섞이는 양이 는다.
         *
         * **얼마나 섞이는지는 못 쟀다**(#322 · 후속은 #325). 조용한 방에서 잰 한 벌은
         * 인식기가 보고하는 음량이 진폭을 따라 오르는 것처럼 보였는데, **소음이 있는 방에서
         * 다시 재니 평상 잡음에 묻혀 구분되지 않았다.** 게다가 `onRmsChanged` 값은
         * **[-2, 10]으로 잘린다**(양 끝값이 그대로 반복해 나온다) — 차임 구간이 그 위쪽에
         * 걸려서 애초에 이 계기로는 못 가른다. **재려면 다른 계기가 필요하다.**
         *
         * **그래서 이 비용은 경계를 어디에 그을지를 정하지 못한다.** "올리지 말아야 할
         * 이유"이지 "26,000이 맞는 이유"는 아니다 — 크기가 나오면(#325) 그때 다시 잡는다.
         *
         * **인식 자체는 이 값에서 멀쩡했다**(#322) — 차임 도중에 바로 말해도 첫 음절이 살았고
         * 서버까지 통과했다. 더 필요하면 진폭이 아니라 **음 길이나 겹침**을 손본다 —
         * 그쪽은 마이크로 나가는 순간 음량을 안 올린다.
         */
        const val AMPLITUDE = 26_000.0

        /** 약 4ms. 더 짧으면 "틱"이 붙고 길면 뭉개진다. */
        const val ATTACK_SAMPLES = 180

        /** 클수록 빨리 사그라든다. 종소리 꼬리가 남을 만큼만. */
        const val DECAY = 3.2
    }
}
