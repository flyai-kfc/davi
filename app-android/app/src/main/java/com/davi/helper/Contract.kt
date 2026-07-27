package com.davi.helper

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 앱 <-> 서버 계약의 Kotlin 판. **기준은 `server/app/schemas.py`다.**
 *
 * 필드명은 서버와 정확히 일치해야 하므로 전부 [SerialName]으로 못박는다.
 * 계약을 바꾸려면 서버의 "API 계약 결정"(server/CLAUDE.md C1~C8)을 먼저 읽고 양쪽을 같이 고친다.
 *
 * 스크롤 메타는 모델에게 "지식" 대신 "상태"를 주기 위한 것이다(HANDOFF §3).
 * 스크롤이 안 되는 요소에는 전부 null이 들어간다.
 */
@Serializable
data class UiElement(
    @SerialName("node_id") val nodeId: String,
    val label: String = "",
    val role: String = "",
    val clickable: Boolean = false,
    /** [left, top, right, bottom] 화면 좌표. */
    val bounds: List<Int>? = null,
    @SerialName("can_scroll_forward") val canScrollForward: Boolean? = null,
    @SerialName("can_scroll_backward") val canScrollBackward: Boolean? = null,
    @SerialName("scroll_axis") val scrollAxis: ScrollAxis? = null,
    @SerialName("next_swipe_direction") val nextSwipeDirection: SwipeDirection? = null,
    @SerialName("prev_swipe_direction") val prevSwipeDirection: SwipeDirection? = null,
)

@Serializable
data class Screen(
    @SerialName("package_name") val packageName: String = "",
    val elements: List<UiElement> = emptyList(),
    /** 페이지 위치는 화면 단위 상태다(C5). 페이지 dot에서 읽는다(HANDOFF §3-3). */
    @SerialName("current_page") val currentPage: Int? = null,
    @SerialName("total_pages") val totalPages: Int? = null,
)

@Serializable
data class NextStepRequest(
    val goal: String,
    val screen: Screen,
    @SerialName("device_id") val deviceId: String? = null,
    @SerialName("recent_instructions") val recentInstructions: List<String> = emptyList(),
    @SerialName("client_version") val clientVersion: String? = null,
)

@Serializable
data class NextStepResponse(
    val action: GuideAction,
    val model: String,
    val verified: Boolean = false,
)

@Serializable
data class GuideAction(
    @SerialName("expected_result") val expectedResult: String,
    val evidence: String,
    @SerialName("action_type") val actionType: ActionType,
    val direction: SwipeDirection? = null,
    @SerialName("target_node_id") val targetNodeId: String? = null,
    val instruction: String,
)

/**
 * 서버 `ActionType`과 **값이 정확히 같아야 한다.**
 *
 * kotlinx.serialization은 모르는 enum 값을 만나면 예외를 던진다. `ignoreUnknownKeys`는 모르는
 * **키**만 무시할 뿐 값은 막아주지 못하고, `action_type`은 기본값 없는 필수 필드라
 * `coerceInputValues`로도 못 덮는다. 즉 한 글자만 어긋나도 그 액션이 오는 순간 안내가 끊긴다.
 *
 * 값의 근거는 `server/CLAUDE.md`의 "API 계약 결정" C6이다. 전역 동작(뒤로/홈)은 아직 이슈 #10에서
 * 검토 중이므로 여기 없다. 추가하려면 **서버부터** 바꾸고 양쪽을 함께 움직인다.
 * 어긋나면 `server/tests/test_contract_drift.py`가 CI에서 잡는다.
 */
@Serializable
enum class ActionType {
    @SerialName("tap") TAP,
    @SerialName("scroll") SCROLL,
    @SerialName("type") TYPE,
    @SerialName("open_quick_settings") OPEN_QUICK_SETTINGS,
    @SerialName("done") DONE,
}

/** 손가락이 움직이는 방향. "무엇이 보이게 되는지"가 아니다. 서버 `SwipeDirection`과 값이 같아야 한다. */
@Serializable
enum class SwipeDirection {
    @SerialName("up") UP,
    @SerialName("down") DOWN,
    @SerialName("left") LEFT,
    @SerialName("right") RIGHT,
}

/** 서버 `ScrollAxis`와 값이 같아야 한다. */
@Serializable
enum class ScrollAxis {
    @SerialName("vertical") VERTICAL,
    @SerialName("horizontal") HORIZONTAL,
}

@Serializable
data class ApiError(
    val kind: ApiErrorKind,
    val message: String,
    @SerialName("retry_after_seconds") val retryAfterSeconds: Int? = null,
    @SerialName("request_id") val requestId: String? = null,
)

/**
 * 서버 `ErrorKind`와 **값이 정확히 같아야 한다**(위 [ActionType]과 같은 이유).
 *
 * 앱은 이 값을 보고 사용자 안내를 가른다 — "사용량 한도"와 "인터넷 불량"은 다르게 말해야 한다.
 * `message`는 디버그용이므로 어르신께 그대로 보여주지 않는다(C8).
 * 이름은 이전 PoC의 `LlmException(kind)` 계보를 그대로 따른다(HANDOFF §4).
 */
@Serializable
enum class ApiErrorKind {
    @SerialName("QUOTA") QUOTA,
    @SerialName("NETWORK") NETWORK,
    @SerialName("AUTH") AUTH,
    @SerialName("SERVER") SERVER,
    @SerialName("PARSE") PARSE,
    @SerialName("BAD_REQUEST") BAD_REQUEST,
}
