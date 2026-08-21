package com.davi.helper

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * 마이크를 누른 **즉시** 나는 짧은 「띠링」(#313).
 *
 * **왜 필요한가** — 누른 뒤 인식기가 준비되기까지 시간이 걸리고 그동안 화면에 변화가 없다.
 * 시니어 사용자가 "눌렸는지 모르겠다"고 했다(2026-08-20 관찰).
 *
 * **왜 시스템 인식기의 시작음에 기대지 않는가** — 그 소리는 엔진이 낸다. 네트워크 인식기는
 * 내고 구글 온디바이스(SODA)는 안 낸다. `on_device_recognition_service`가 빈 기기에서 실제로
 * 안 났다(2026-08-19 실측). **기기 설정에 딸린 부수 효과라 기댈 수 없다.**
 *
 * **`ToneGenerator`를 쓰지 않는다.** 처음엔 그걸 썼는데 낼 수 있는 것이 전화기 계열
 * 신호음뿐이라 "삑" 소리가 됐고, **톤 자체가 별로**라는 지적을 받았다(2026-08-20).
 * 여기서는 **두 음을 직접 합성한다** — 음원 파일을 만들지 않으면서 부드러운 차임이 된다.
 *
 * **스트림은 미디어다.** TTS가 타는 것과 같게 둔다 — 다르면 무음 모드에서 **소리는 없고
 * 「지금 말하세요」 목소리만 나는** 조합이 생긴다. 무음 모드를 따로 다루지는 않는다.
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
            active.reloadStaticData()
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
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
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
            .apply { write(pcm, 0, pcm.size) }
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

        /** 두 음이 겹치므로 각각은 낮게 잡는다. 합쳐서 16bit 최대를 넘지 않게. */
        const val AMPLITUDE = 8_000.0

        /** 약 4ms. 더 짧으면 "틱"이 붙고 길면 뭉개진다. */
        const val ATTACK_SAMPLES = 180

        /** 클수록 빨리 사그라든다. 종소리 꼬리가 남을 만큼만. */
        const val DECAY = 3.2
    }
}
