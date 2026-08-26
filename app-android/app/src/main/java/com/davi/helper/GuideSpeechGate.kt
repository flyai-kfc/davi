package com.davi.helper

/**
 * "지금 무엇을 소리 내어 읽을 것인가"의 순수 판정(#450). Android 타입을 모른다 —
 * 억제 조건(TalkBack·통화 중)과 실제 발화는 [GuideSpeaker]가 맡는다.
 *
 * **판정을 표시에서 떼어낸 이유는 표시 깔때기가 같은 내용을 반복해서 지나가기 때문이다.**
 * `GuideAccessibilityService.draw`는 서버 안내뿐 아니라 재표시(root=null 청소 회복)·강조
 * 내림/되돌림(#225)·오류 재시도까지 전부 통과하는 한 자리라, 그대로 읽으면 **같은 문장이
 * 폴링마다 다시 나간다.** 「안내가 바뀔 때 한 번」은 여기서 문구 대조로 성립한다.
 *
 * **정체 접미사는 덧붙은 만큼만 읽는다.** [GuideDisplayState.stuckContent]가 만드는 문구는
 * 원문 뒤에 한 줄을 이어 붙인 것이라 문자열로는 새 안내지만 사용자에게는 **같은 안내 +
 * 한마디**다. 전체를 다시 읽으면 화면이 안 바뀌어 답답한 사람에게 같은 문장을 통째로 한 번
 * 더 들려주게 된다. 접두 일치는 지금 이 경로에서만 생기지만, 규칙 자체는 "덧붙었으면
 * 덧붙은 것만"이라 새 경로가 생겨도 그대로 맞는다.
 *
 * **[repeat]은 델타가 아니라 전체를 돌려준다.** 다시 듣기는 "방금 못 들었다"는 요청이라
 * 지금 떠 있는 문장 전부가 답이다.
 *
 * **눈으로 읽으라고 쓴 기호는 말로 바꿔 읽는다**([SPOKEN_SYMBOLS]). 안내창의 확인쌍은 라벨이
 * 없고 본문 둘째 줄의 `× 계속, ○ 그만`이 무엇을 누르라는지 이르는데(#178·#229), 그 줄을 글자
 * 그대로 넘기면 TTS가 `×`를 「곱하기」, `○`를 「빈 원」으로 읽는다(2026-08-26 SM-S921N 실측).
 *
 * **읽다 끊긴 것은 안 읽은 것으로 친다**([markInterrupted]). 화면이 꺼지거나 전화가 들어와
 * 문장 중간에 끊기면, 같은 안내가 다시 떠도 문구가 같다는 이유로 영영 안 읽히는 구멍이
 * 생긴다 — 하필 소리에 기대는 사람에게 그렇게 된다.
 *
 * 메인 스레드에서만 만진다([GuideDisplayState]와 같은 계약).
 */
class GuideSpeechGate {

    /** 지금 안내창에 떠 있는 문구(정규화본). 대조와 다시 듣기가 같은 값을 본다. */
    private var current: String? = null

    /** [current]를 **끝까지** 읽었는가. 끊겼으면 같은 문구가 다시 떠도 처음부터 다시 읽는다. */
    private var spoken = false

    /**
     * [text]를 표시할 때 읽어야 할 발화. 읽을 것이 없으면 null.
     *
     * **억제 여부와 무관하게 상태를 옮긴다.** 통화 중이라 못 읽은 안내를 기억해 두면 통화가
     * 끝난 뒤 접두 일치로 **지나간 안내의 뒷부분만** 읽는 조합이 생긴다 — 억제는 "이번 것을
     * 안 읽는다"이지 "나중에 읽는다"가 아니다. 억제를 아는 쪽([GuideSpeaker])이 결과를 버린다.
     */
    fun next(text: String): String? {
        val normalized = normalize(text)
        if (normalized.isEmpty()) return null
        val previous = current
        val wasSpoken = spoken
        current = normalized
        spoken = true
        if (previous == normalized) return if (wasSpoken) null else normalized
        if (previous != null && wasSpoken && normalized.startsWith(previous)) {
            return normalized.removePrefix(previous).trim().ifEmpty { null }
        }
        // 앞 문장을 끝까지 못 읽었으면 덧붙은 부분만 읽어도 앞이 비어 말이 안 된다. 통째로 읽는다.
        return normalized
    }

    /** 다시 듣기. 떠 있는 안내가 없으면 null. **끊겼던 것도 여기서는 전체가 답이다.** */
    fun repeat(): String? = current?.also { spoken = true }

    /**
     * 읽다 끊겼다(화면 꺼짐·포커스 상실·시스템의 피드백 중단 요청). 떠 있는 안내는 그대로 두되
     * **안 읽은 것으로** 되돌려, 같은 문구가 다시 그려지면 처음부터 다시 읽게 한다.
     *
     * **다시 그려지는 계기가 있어야 소리가 돌아온다.** 화면 꺼짐→켜짐은 재표시가 [next]를 다시
     * 부르지만, 통화로 끊긴 경우처럼 화면이 그대로면 부를 것이 없다 — 그쪽은 다시 듣기 탭이 답이다.
     */
    fun markInterrupted() {
        spoken = false
    }

    /** 세션이 끝났거나 바뀌었다. 다음 세션의 첫 안내는 문구가 같아도 읽어야 한다. */
    fun reset() {
        current = null
        spoken = false
    }

    /**
     * 안내문은 줄바꿈으로 두 문장을 담는다. 읽을 때는 한 줄로 이어 붙이고 앞뒤 공백을 턴다.
     *
     * **정규식을 쓰지 않는다.** `Regex("\\s+")`이면 되지만 그 식의 `\s`는 **JVM과 기기에서 뜻이
     * 다르다** — 기기 정규식은 ICU라 `\s`가 기본으로 유니코드고 NBSP 같은 것도 접히는데,
     * JVM(OpenJDK)에서는 안 접힌다. 그러면 아래 JVM 테스트가 고정하는 것과 기기 동작이
     * 갈린다. [Char.isWhitespace]는 양쪽 다 `Character.isWhitespace() || isSpaceChar()`라
     * **같은 정의**를 보고, NBSP도 양쪽에서 접힌다(유니코드 판이 다르면 새 코드포인트에서
     * 갈릴 수는 있으나, `\s`의 기본 의미 자체가 다른 것과는 종류가 다르다).
     * 덤으로 **새 정규식을 하나도 안 늘려** #400의 「정규식을 새로 쓰면 기기에서 한 번
     * 태울 것」 자체가 해당 없다(그 함정은 ICU가 거부하는 인라인 플래그였고 여기엔 없었다).
     */
    private fun normalize(text: String): String {
        val out = StringBuilder(text.length)
        var pendingSpace = false
        for (ch in text) {
            if (ch.isWhitespace()) {
                if (out.isNotEmpty()) pendingSpace = true
                continue
            }
            if (pendingSpace) {
                out.append(' ')
                pendingSpace = false
            }
            // 기호는 이름으로 바꿔 넣는다. **자리를 그대로 차지하므로 뒤따르는 조사가 붙어
            // 산다** — `×를 눌러` → `가위표를 눌러`. 앞뒤로 공백을 새로 넣지 않는 이유가 이것이다.
            val spoken = SPOKEN_SYMBOLS[ch]
            if (spoken != null) out.append(spoken) else out.append(ch)
        }
        return out.toString()
    }

    private companion object {
        /**
         * 화면에서는 그림으로 읽히지만 **소리로는 이름이 필요한 기호**.
         *
         * **화면 문구를 고치지 않는다.** `× 계속, ○ 그만`의 기호가 앞에 오는 형식은 디자인
         * 판정이고(2026-08-18, PR #229 리뷰 6), 라벨 없는 확인쌍을 가리키는 것이 그 기호의
         * 일이다. 여기서 하는 것은 **같은 문장의 낭독본**을 만드는 것뿐이라, 화면과 소리가
         * 어절 단위로 그대로 맞는다("× 계속" ↔ "가위표 계속").
         *
         * **말을 덧붙이지 않는다**("가위표를 누르면 계속" 같은 식). 그건 문구를 다시 쓰는
         * 것이고 그 판정은 화면 이슈(#178) 몫이다 — 여기는 기호를 읽는 방법만 정한다.
         *
         * 이름은 그 기호의 한국어 이름을 쓴다. `×`(U+00D7)는 곱셈 기호라 TTS 기본 읽기가
         * 「곱하기」이고, `○`(U+25CB)는 「빈 원」이다 — 둘 다 화면에서 뜻하는 것과 무관하다.
         */
        val SPOKEN_SYMBOLS = mapOf(
            '\u00D7' to "가위표",
            '\u25CB' to "동그라미",
        )
    }
}
