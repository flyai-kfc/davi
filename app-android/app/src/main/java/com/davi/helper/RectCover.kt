package com.davi.helper

/**
 * "이 사각형이 저 사각형들에 빈틈없이 덮이는가"의 **순수 판정**(PR #336 리뷰).
 *
 * 가림 판정에서 제일 미묘한 부분이 기하라, `android.graphics.Region`에 묶어 두면 JVM 단위
 * 테스트로 고정할 수 없다. [UiTreeSerializer]의 규약대로 **위험한 로직만 순수 함수로** 뺀다.
 *
 * 덮개가 여러 조각인 경우를 정면으로 다룬다 — 바텀시트는 **스크림과 패널 둘로** 화면을
 * 나눠 덮으므로(2026-08-21 SM-S921N 실측: `[0,0,1080,234]` + `[0,234,1080,2340]`) 사각형
 * 하나로 비교하면 어느 쪽도 맞지 않는다.
 */
internal object RectCover {

    /** 조각이 이보다 많아지면 판정을 포기한다. 포기는 "안 덮였다"로 — 과잉 판정보다 안전하다. */
    private const val MAX_FRAGMENTS = 64

    /** [left, top, right, bottom]. Android `Rect`와 같은 순서이고 오른쪽·아래는 열린 구간이다. */
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val isEmpty: Boolean get() = left >= right || top >= bottom

        fun intersect(other: Box): Box = Box(
            maxOf(left, other.left),
            maxOf(top, other.top),
            minOf(right, other.right),
            minOf(bottom, other.bottom),
        )
    }

    /**
     * [target]이 [covers]의 합집합에 **빈틈없이** 들어가는가.
     *
     * **셋 다 [screen]으로 먼저 자른다.** `getBoundsInScreen`은 뷰의 전체 사각형을 주고 화면
     * 밖을 잘라 주지 않아서, 목록의 첫·마지막 행처럼 일부만 보이는 요소는 `[x, -80, ...]`으로
     * 온다. 자르지 않으면 화면 밖 조각이 차집합에 남아 "안 덮였다"로 판정되고, 그 요소는
     * `isVisibleToUser=true`라 프롬프트에 그대로 실린다 — **모달 뒤 목록의 위아래 끝 행이
     * 정확히 안전망을 빠져나간다**(PR #336 리뷰).
     *
     * 화면 안에서 빈 사각형은 **덮인 것이 아니라 판정 대상이 아니다.** 빈 영역은 무엇을 빼도
     * 비어 있어서 그대로 두면 아무것도 안 덮였는데 "덮였다"가 된다(같은 리뷰).
     */
    fun isCovered(target: Box, covers: List<Box>, screen: Box): Boolean {
        val clipped = target.intersect(screen)
        if (clipped.isEmpty) return false

        var remainder = listOf(clipped)
        for (cover in covers) {
            val clip = cover.intersect(screen)
            if (clip.isEmpty) continue
            remainder = remainder.flatMap { subtract(it, clip) }
            if (remainder.isEmpty()) return true
            if (remainder.size > MAX_FRAGMENTS) return false
        }
        return remainder.isEmpty()
    }

    /** [box]에서 [cut]을 뺀 나머지. 겹치는 부분이 가운데면 최대 네 조각이 된다. */
    internal fun subtract(box: Box, cut: Box): List<Box> {
        val overlap = box.intersect(cut)
        if (overlap.isEmpty) return listOf(box)
        return buildList {
            if (box.top < overlap.top) add(Box(box.left, box.top, box.right, overlap.top))
            if (overlap.bottom < box.bottom) add(Box(box.left, overlap.bottom, box.right, box.bottom))
            if (box.left < overlap.left) add(Box(box.left, overlap.top, overlap.left, overlap.bottom))
            if (overlap.right < box.right) add(Box(overlap.right, overlap.top, box.right, overlap.bottom))
        }
    }
}
