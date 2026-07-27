package com.davi.helper

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 앱 <-> 서버 계약의 Kotlin 판. **기준은 `server/app/schemas.py`다.**
 *
 * 필드명은 서버와 정확히 일치해야 하므로 전부 [SerialName]으로 못박는다.
 * 계약을 바꾸려면 서버의 "API 계약 결정"(server/CLAUDE.md C1~C8)을 먼저 읽고 양쪽을 같이 고친다.
 *
 * 이번 이슈(#4)에서 실제로 채우는 것은 node_id / label / role / clickable / bounds 뿐이다.
 * 스크롤 메타와 페이지 위치는 필드만 두고 null로 남긴다 — 채우는 것은 다음 이슈다.
 */
@Serializable
data class UiElement(
    @SerialName("node_id") val nodeId: String,
    val label: String = "",
    val role: String = "",
    val clickable: Boolean = false,
    /** [left, top, right, bottom] 화면 좌표. */
    val bounds: List<Int>? = null,

    // --- 아래는 다음 이슈에서 채운다. 지금은 항상 null이다. ---
    @SerialName("can_scroll_forward") val canScrollForward: Boolean? = null,
    @SerialName("can_scroll_backward") val canScrollBackward: Boolean? = null,
    @SerialName("scroll_axis") val scrollAxis: String? = null,
    @SerialName("next_swipe_direction") val nextSwipeDirection: String? = null,
    @SerialName("prev_swipe_direction") val prevSwipeDirection: String? = null,
)

@Serializable
data class Screen(
    @SerialName("package_name") val packageName: String = "",
    val elements: List<UiElement> = emptyList(),

    // --- 페이지 위치도 다음 이슈. 화면 단위 상태라 여기 있다(C5). ---
    @SerialName("current_page") val currentPage: Int? = null,
    @SerialName("total_pages") val totalPages: Int? = null,
)
