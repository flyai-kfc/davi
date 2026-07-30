package com.davi.helper

/**
 * 오버레이에 "지금 무엇이 보여야 하는가"를 판정하는 순수 상태. 서비스는 판정 결과만 그린다.
 *
 * PR #63 리뷰 P1이 이 분리의 이유다: 상한 안내가 root=null 청소(`overlay.clear`)로 한 번
 * 지워지면 되살릴 길이 없었다 — 문구가 시키는 행동('안내 중지' 버튼이 있는 우리 앱 열기)
 * 자체가 문구를 지우는 조건이었다. 재표시 결정을 Android 밖으로 꺼내 JVM 테스트로 고정한다.
 *
 * [ScreenStabilityTracker]처럼 메인 스레드에서만 만진다.
 */
class GuideDisplayState {

    /** 오버레이에 그릴 내용. [bounds]는 `[left, top, right, bottom]`, 강조가 없으면 null. */
    data class Content(val bounds: List<Int>?, val text: String)

    private var guide: Content? = null

    /** 정체(STUCK) 접미사를 붙여도 되는가. 상한 안내는 행동 지시가 아니라서 붙이지 않는다. */
    private var stuckSuffixAllowed = true

    /** 오버레이에 실제로 떠 있는가. root=null 청소가 지우면 내용은 남기고 이것만 내린다. */
    private var visible = false

    /** 서버 안내 하나를 표시한다. 그릴 내용을 돌려준다. */
    fun show(bounds: List<Int>?, text: String): Content {
        val content = Content(bounds, text)
        guide = content
        stuckSuffixAllowed = true
        visible = true
        return content
    }

    /** 미검증 done 상한 안내를 표시한다(#55). STUCK 접미사를 붙이지 않는다. */
    fun showCapNotice(text: String): Content {
        val content = Content(null, text)
        guide = content
        stuckSuffixAllowed = false
        visible = true
        return content
    }

    /** 오버레이가 지워졌다(우리 앱이 앞에 오는 등 root=null 청소). 내용은 기억해 둔다. */
    fun onCleared() {
        visible = false
    }

    /** 세션이 끝나거나 바뀌었다. 이전 세션의 안내를 재표시하면 안 된다. */
    fun reset() {
        guide = null
        stuckSuffixAllowed = true
        visible = false
    }

    /**
     * 유효한 화면 샘플에서 다시 그려야 할 내용. 지워진 안내가 있으면 돌려주고 "떠 있음"으로
     * 되돌린다 — 상한 안내가 앱 전환 한 번에 영영 사라지는 구멍의 회복 경로다(PR #63 리뷰 P1).
     */
    fun redisplay(): Content? {
        val current = guide ?: return null
        if (visible) return null
        visible = true
        return current
    }

    /**
     * 정체(STUCK)를 알릴 내용. 일반 안내에는 [suffix]를 덧붙이고, 상한 안내는 원문 그대로
     * 유지한다 — 덮어쓰지도, 잃지도 않는다.
     */
    fun stuckContent(suffix: String): Content? {
        val current = guide ?: return null
        visible = true
        return if (stuckSuffixAllowed) Content(current.bounds, "${current.text}\n$suffix") else current
    }
}
