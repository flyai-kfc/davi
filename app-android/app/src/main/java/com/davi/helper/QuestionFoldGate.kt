package com.davi.helper

/**
 * 구체화 질문 카드가 **펼쳐져 있는가**만 판정한다(#452). Android 타입을 쓰지 않아 JVM 테스트로 고정한다.
 *
 * 규칙은 셋이고 전부 뷰가 필요 없다:
 *
 *     낭독 중이면 펼침 · 손잡이를 누르면 그 뒤로 고정 · 질문이 바뀌면 초기화
 *
 * **예전에는 이 셋이 액티비티 안에 변수 세 개로 흩어져 있었고**, 그래서 이 판정을 잡는 그물이
 * 실기기 확인뿐이었다. 그 사이 TTS가 끝 신호를 안 주는 갈래가 조용히 열려 있었다(아래).
 *
 * **[toggle] 뒤에는 낭독 상태가 안 덮는다.** 펼쳐 놓고 읽는 중에 낭독이 끝났다고 접히면
 * 읽던 것이 사라진다. 반대로 접어 둔 사람의 화면이 낭독 때문에 다시 열려서도 안 된다.
 *
 * 짝이 되는 것은 [speechGuardMillis]다 — 이쪽이 「무엇이 펼침을 정하는가」, 저쪽이
 * 「그 신호가 영영 안 올 때」를 맡는다.
 */
class QuestionFoldGate {

    private var key: String? = null

    /** 이 질문에서 사용자가 손잡이를 눌렀는가. 눌렀으면 낭독 상태가 덮어쓰지 않는다. */
    private var touched = false

    /** 지금 카드가 펼쳐져 있는가. */
    var expanded: Boolean = false
        private set

    /**
     * 이번 render의 질문과 낭독 상태를 넘긴다.
     *
     * `questionKey`가 null이면 구체화 화면이 아니다 — **기록을 통째로 지운다.** 남겨 두면
     * 다른 화면을 거쳐 같은 질문으로 돌아왔을 때 옛 손잡이 기록이 되살아난다.
     */
    fun sync(questionKey: String?, speaking: Boolean) {
        if (questionKey == null) {
            key = null
            touched = false
            expanded = false
            return
        }
        if (questionKey != key) {
            key = questionKey
            touched = false
            // 읽어 주는 동안만 펼쳐 둔다. 안 읽으면 처음부터 접힌다.
            expanded = speaking
            return
        }
        if (!touched) expanded = speaking
    }

    /** 손잡이를 눌렀다. */
    fun toggle() {
        expanded = !expanded
        touched = true
    }
}

/**
 * 낭독이 이보다 길 리 없다는 상한(ms). **정책이 아니라 그물이다.**
 *
 * 카드가 접히는 유일한 계기가 TTS의 끝 신호인데, 엔진이 말하다 죽거나 `onDone`을 안 부르면
 * **그 신호가 영영 안 온다** — 안드로이드 TTS에서 드물지 않고, [TextToSpeechHelper]에는
 * 타임아웃이 없다. 그러면 카드가 펼친 채로 굳어 보기가 화면 밖으로 밀린다.
 *
 * **넉넉히 잡는다.** 이 값이 낭독보다 짧으면 **읽는 도중에 카드가 접힌다** — 지금 고치려는
 * 것보다 나쁘다. 한국어 TTS는 기본 속도에서 초당 대여섯 자를 읽으므로 글자당 300ms는
 * 1.5배 남짓의 여유다.
 *
 * **상한이 무한이 아닌 근거는 계약에 있다** — 질문 문구는 200자를 못 넘는다
 * (`SlotFillApiClient.MAX_QUESTION_PROMPT_LENGTH`). 그래서 이 값도 1분 남짓에서 멈춘다.
 */
fun speechGuardMillis(promptLength: Int): Long =
    SPEECH_GUARD_BASE_MS + SPEECH_GUARD_PER_CHAR_MS * promptLength.coerceAtLeast(0)

private const val SPEECH_GUARD_BASE_MS = 3_000L
private const val SPEECH_GUARD_PER_CHAR_MS = 300L
