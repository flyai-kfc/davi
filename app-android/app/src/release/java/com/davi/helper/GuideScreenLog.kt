package com.davi.helper

import android.util.Log

/**
 * 화면 상세 진단 로그의 **release 구현**(#58).
 *
 * 요소 `label`·`bounds`는 사용자 화면의 내용과 좌표라 제품 빌드의 logcat에 남기지 않는다.
 * 1차 마스킹은 패턴 기반이라 사람 이름·대화 내용처럼 패턴 밖 텍스트를 지우지 못한다 —
 * "마스킹했으니 남겨도 된다"가 성립하지 않는다.
 *
 * **비식별 메타데이터만 남긴다.** 화면 요약(패키지·요소 수 등)은 호출부가 main에서 그대로
 * 찍으므로 여기서 다시 남기지 않는다.
 */
object GuideScreenLog {
    /** release는 요소 상세를 남기지 않는다. 개수는 호출부의 화면 요약 줄에 이미 들어 있다. */
    @Suppress("UNUSED_PARAMETER")
    fun elements(screen: Screen) = Unit

    /**
     * 좌표 없이 어느 요소를 골랐는지만 남긴다. node_id는 직렬화가 붙인 일련 식별자(n1, n2…)라
     * 화면 내용을 담지 않는다. 이것마저 없으면 release에서 "강조가 나갔는지"조차 볼 수 없다.
     */
    @Suppress("UNUSED_PARAMETER")
    fun highlight(nodeId: String?, bounds: List<Int>) {
        Log.i(GuideAccessibilityService.TAG, "highlight ${nodeId ?: "-"}")
    }
}
