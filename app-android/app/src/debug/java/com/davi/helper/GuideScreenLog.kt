package com.davi.helper

import android.util.Log

/**
 * 화면 상세 진단 로그의 **debug 구현**(#58).
 *
 * 요소 `label`과 `bounds`는 사용자가 보고 있는 화면의 내용 그 자체다. 1차 마스킹이
 * 패턴에 걸리는 개인정보를 지우지만 사람 이름·대화 내용처럼 패턴 밖의 텍스트는 남으므로,
 * **release 산출물에는 이 상세를 아예 넣지 않는다.** 같은 이름의 release 구현이 no-op이다.
 *
 * 상세 로그를 낼지 말지의 판정(`shouldLogDetailedScreen`)은 호출부에 그대로 둔다 —
 * 그 조건은 마스킹 우회·E2E 세션이라는 **정책**이고, 여기는 그 결정을 받아 적기만 한다.
 */
object GuideScreenLog {
    /** 한 화면에서 찍을 요소 수 상한. 목록 화면은 수십 개가 나와 로그가 화면을 덮는다. */
    private const val MAX_LOGGED_ELEMENTS = 30

    fun elements(screen: Screen) {
        screen.elements.take(MAX_LOGGED_ELEMENTS).forEach { e ->
            Log.d(
                GuideAccessibilityService.TAG,
                "  ${e.nodeId} ${if (e.clickable) "tap" else "text"} " +
                    "role=${e.role} bounds=${e.bounds}${scrollInfo(e)} label='${e.label}'",
            )
        }
        if (screen.elements.size > MAX_LOGGED_ELEMENTS) {
            Log.d(
                GuideAccessibilityService.TAG,
                "  ... ${screen.elements.size - MAX_LOGGED_ELEMENTS} more",
            )
        }
    }

    /**
     * 강조 대상과 좌표. bounds는 화면 위 어디를 짚었는지라 debug에서만 남긴다.
     * release 구현은 좌표 없이 node_id만 남긴다 — 어느 요소를 골랐는지는 비식별이다.
     */
    fun highlight(nodeId: String?, bounds: List<Int>) {
        // `at=`는 **CI 카나리아다**(ci.yml "release 산출물에 화면 상세 로그가 없는지 확인").
        // 좌표를 찍는 자리가 요소 덤프와 독립이라 카나리아도 따로 있어야 한다 — 덤프 쪽
        // 카나리아(label='·scroll=)만 보면 여기서 좌표가 되살아나도 CI가 통과한다(PR #287 리뷰).
        // 요소 덤프의 `bounds=`를 그대로 쓰지 않는 이유는 그 문자열이 UiElement의 자동 생성
        // toString(`..., bounds=...`)과 겹쳐 release 산출물에도 남기 때문이다.
        // **이 리터럴을 바꾸면 ci.yml의 카나리아 목록도 함께 고친다.**
        Log.i(GuideAccessibilityService.TAG, "highlight ${nodeId ?: "-"} at=$bounds")
    }

    /** 스크롤 가능한 요소에만 붙는다. 방향 안내가 맞는지 로그에서 바로 보려고 찍는다. */
    private fun scrollInfo(e: UiElement): String {
        if (e.canScrollForward == null && e.canScrollBackward == null) return ""
        val fwd = if (e.canScrollForward == true) "+${e.nextSwipeDirection?.name?.lowercase()}" else ""
        val back = if (e.canScrollBackward == true) "-${e.prevSwipeDirection?.name?.lowercase()}" else ""
        return " scroll=${e.scrollAxis?.name?.lowercase()}$fwd$back"
    }
}
