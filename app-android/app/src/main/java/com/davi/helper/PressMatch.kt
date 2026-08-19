package com.davi.helper

/**
 * 안내한 대상을 사용자가 실제로 눌렀는지 가르는 **순수 판정**(#238).
 * Android 타입을 쓰지 않아 JVM 테스트로 고정한다.
 *
 * **축이 둘인 이유는 `AccessibilityEvent.source`가 간헐적으로 null이기 때문이다.**
 * 시스템이 노드를 짧게만 들고 있어 부하가 걸린 구간에서는 회수된다 — 2026-08-19 SM-S921N
 * 실측에서 **같은 런처 아이콘을 같은 방식으로 탭했는데** 설치·진단이 연달아 돌던 구간에서만
 * `src=false`였고, 한산할 때는 정상이었다. 앱별 차이가 아니라 타이밍이다.
 *
 * source가 있으면 좌표가 정확하므로 그쪽을 쓰고, 없을 때만 이벤트가 함께 싣는 텍스트로 맞춘다.
 */
object PressMatch {

    /**
     * 클릭된 노드의 **중심**이 대상 사각형 안인가.
     *
     * 사각형이 정확히 같기를 요구하지 않는다 — 우리가 지목한 것은 컨테이너인데 클릭 이벤트는
     * 그 안쪽 뷰에서 날 수 있다(런처 독 아이콘이 그 모양이다).
     */
    fun byBounds(bounds: List<Int>, centerX: Int, centerY: Int): Boolean {
        if (bounds.size != 4) return false
        return centerX >= bounds[0] && centerX <= bounds[2] &&
            centerY >= bounds[1] && centerY <= bounds[3]
    }

    /**
     * 대상 label과 이벤트가 실어 온 텍스트가 같은 것을 가리키는가.
     *
     * **완전 일치만 받는다.** label이 자손 텍스트를 합친 긴 값일 때 포함까지 받으면 옆 대상의
     * 짧은 텍스트가 통과한다 — "소리 및 진동"을 안내하는 중에 옆 행 "진동"을 누르면 맞다고 한다
     * (PR #264 리뷰 6). 그건 미탐이 아니라 **오탐**이고, 이 축이 폴백인 이상 못 맞추는 쪽이 낫다.
     * 한 글자를 거르는 하한도 같은 이유로 남긴다(서버 완료 근거 판정의 같은 함정, #71).
     *
     * **틀리면 미탐이지 오탐이 아니다.** label은 1차 마스킹을 거친 값이라(전화번호 등) 원본
     * 텍스트와 어긋날 수 있는데, 그때는 파동이 빠질 뿐 엉뚱한 곳에 뜨지 않는다.
     */
    fun byLabel(targetLabel: String?, candidates: List<String>): Boolean {
        val label = targetLabel?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return candidates.any { raw ->
            val text = raw.trim()
            text.length >= MIN_LABEL_MATCH && label == text
        }
    }

    /** 이보다 짧은 텍스트로는 맞추지 않는다. */
    const val MIN_LABEL_MATCH = 2
}
