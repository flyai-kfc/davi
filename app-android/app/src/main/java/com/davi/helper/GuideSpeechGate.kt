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
 * 메인 스레드에서만 만진다([GuideDisplayState]와 같은 계약).
 */
class GuideSpeechGate {

    /** 지금 안내창에 떠 있는 문구(정규화본). 대조와 다시 듣기가 같은 값을 본다. */
    private var current: String? = null

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
        current = normalized
        if (previous == normalized) return null
        if (previous != null && normalized.startsWith(previous)) {
            return normalized.removePrefix(previous).trim().ifEmpty { null }
        }
        return normalized
    }

    /** 다시 듣기. 떠 있는 안내가 없으면 null. */
    fun repeat(): String? = current

    /** 세션이 끝났거나 바뀌었다. 다음 세션의 첫 안내는 문구가 같아도 읽어야 한다. */
    fun reset() {
        current = null
    }

    private fun normalize(text: String) = text.replace(WHITESPACE, " ").trim()

    private companion object {
        /** 안내문은 줄바꿈으로 두 문장을 담는다. 읽을 때는 한 줄로 이어 붙인다. */
        val WHITESPACE = Regex("\\s+")
    }
}
