package com.davi.helper

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * 앱 <-> 서버 계약의 Kotlin 판. **기준은 `server/app/schemas.py`다.**
 *
 * 필드명은 서버와 정확히 일치해야 하므로 전부 [SerialName]으로 못박는다.
 * 계약을 바꾸려면 서버의 "API 계약 결정"(server/CLAUDE.md C1~C15)을 먼저 읽고 양쪽을 같이 고친다.
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
    /**
     * 텍스트 입력을 받을 수 있는 요소인가. [label]에는 현재 값도 들어올 수 있지만, 기기 1차와
     * 서버 2차의 패턴 마스킹을 일반 label과 똑같이 거친다(#462). 비밀번호·인증 코드 값은
     * 직렬화 전에 제외한다.
     */
    val editable: Boolean = false,
    /** 현재 접근성 입력 포커스를 가진 요소인가. */
    val focused: Boolean = false,
    /**
     * 지금 조작할 수 있는 상태인가(#271).
     *
     * **[clickable]은 뷰의 설정값이지 상태가 아니다** — 비활성 버튼도 대개 `clickable=true`로 온다.
     * `null`은 미관측이다. 낡은 앱의 침묵을 "비활성"으로 읽으면 tap 안내가 통째로 막히므로
     * 기본값을 `false`로 두지 않는다(C10의 세 상태와 같은 이유).
     */
    val enabled: Boolean? = null,
    /**
     * 체크 상태(#297 · C25). Android 16의 `partial`을 잃지 않고 그대로 관측한다.
     *
     * `unavailable`은 checkable이 아니거나 플랫폼이 상태를 알려 주지 못한 경우다. `partial`은
     * **관측 전용**이라, 탭 뒤의 기대 상태는 여전히 [ExpectedTargetState]의 checked=true/false만
     * 쓴다. 부분 선택 요소를 누르면 checked와 unchecked 중 어느 쪽이 될지는 이 값으로 추측하지 않는다.
     */
    @SerialName("check_state") val checkState: CheckState = CheckState.UNAVAILABLE,
    /** 목록·탭에서 지금 선택된 항목인가. */
    val selected: Boolean? = null,
    /**
     * 입력칸에 지금 보이는 글자가 hint인가. `true` 비어 있음 / `false` 값이 들어 있음 /
     * `null` 입력칸이 아니거나 미관측.
     *
     * editable 값이 [label]에 실려도 이 상태는 유지한다(#112·#462). hint 자체를 사용자 입력으로
     * 오인하지 않고, 값의 존재만으로 입력 완료를 단정하지 않기 위한 별도 관측이다.
     */
    @SerialName("showing_hint_text") val showingHintText: Boolean? = null,
    /**
     * 위에 그려진 다른 요소에게 **완전히 덮여** 사용자가 보지도 누르지도 못하는가.
     *
     * 접근성 트리는 겹쳐 쌓인 화면을 평평한 목록으로 눌러 편 것이라, 모달이 떠 있어도
     * 그 아래 요소가 그대로 딸려 온다. `isVisibleToUser`는 이걸 못 잡는다 — 그 플래그는
     * GONE/INVISIBLE과 창 밖 클리핑만 보고 **형제 가림은 애초에 소관이 아니다**(안드로이드 스펙).
     * 실측: 배민 타임세일 시트가 떠 있는 화면에서 53개 중 39개가 시트 뒤였는데 전부
     * `isVisibleToUser=true`였고, 모델이 그중 하나를 탭 대상으로 골랐다.
     *
     * `null`은 미관측이다. 낡은 앱의 침묵을 "안 가려짐"으로 읽으면 같은 오안내가 되살아나므로
     * 기본값을 `false`로 두지 않는다(C10의 세 상태와 같은 이유).
     */
    val obscured: Boolean? = null,
    /** [left, top, right, bottom] 화면 좌표. */
    val bounds: List<Int>? = null,
    @SerialName("can_scroll_forward") val canScrollForward: Boolean? = null,
    @SerialName("can_scroll_backward") val canScrollBackward: Boolean? = null,
    @SerialName("scroll_axis") val scrollAxis: ScrollAxis? = null,
    @SerialName("next_swipe_direction") val nextSwipeDirection: SwipeDirection? = null,
    @SerialName("prev_swipe_direction") val prevSwipeDirection: SwipeDirection? = null,
    /**
     * 서버로 보내지 않는 기기 내부 시그니처 투영(#462).
     *
     * 자유 입력값은 매 글자 화면 변화로 읽지 않도록 값 대신 입력칸 이름이나 고정 토큰을 담는다.
     * `null`이면 [label] 자체를 쓴다. 제한 선택 여부는 [signatureTracksEditableValue]가 함께
     * 가르며 둘 다 wire에는 실리지 않는다.
     */
    @Transient internal val signatureLabel: String? = null,
    /** 제한 선택 editable 값이면 [signatureLabel]의 숫자도 타깃 앵커 변화로 보존한다. */
    @Transient internal val signatureTracksEditableValue: Boolean = false,
)

@Serializable
data class Screen(
    @SerialName("package_name") val packageName: String = "",
    /**
     * 지금 창의 제목. `package_name`만으로는 화면을 알 수 없다 — 설정>배터리가
     * `com.samsung.android.lool`로 오는데 창 제목은 '배터리'를 바로 준다(#269 실측).
     *
     * **label과 같은 취급이다** — 대화방 제목은 사람 이름이고 문서 뷰어는 파일명을 싣는다.
     * 기기 1차·서버 2차 마스킹을 그대로 통과한다. 제목이 없는 창이 흔해서 `null`이 정상이다.
     */
    @SerialName("window_title") val windowTitle: String? = null,
    val elements: List<UiElement> = emptyList(),
    /** 페이지 위치는 화면 단위 상태다(C5). 페이지 dot에서 읽는다(HANDOFF §3-3). */
    @SerialName("current_page") val currentPage: Int? = null,
    @SerialName("total_pages") val totalPages: Int? = null,
    /**
     * 스크롤 가능한 요소가 화면에 하나라도 있었는가(C10).
     * `true` 있음 / `false` 하나도 없음 / `null` 낡은 앱이 아직 안 채움.
     * **`false`와 `null`은 다르다.** 현재 serializer는 보이는 트리 순회를 끝내고 항상 채운다.
     */
    @SerialName("has_scrollable_element") val hasScrollableElement: Boolean? = null,
    /** `true` 표시 / `false` 미표시 / `null` 낡은 앱 또는 빈 window 목록으로 관측하지 못함. */
    @SerialName("ime_visible") val imeVisible: Boolean? = null,
    /**
     * 지금 이 화면의 내용 축 서명(#410). 기기 시그니처의 내용 축을 **세션 salt로 해시한** 값이라
     * 원문 label은 여기로 나가지 않는다([ContentSignature]).
     *
     * 서버가 이력의 [GuideStep.contentSignature]와 대조해 "구조는 같고 내용만 다른 화면"과
     * A→B→A 회귀를 가른다. **서버가 스스로 계산할 수 없어 앱이 싣는다** — salt가 기기 안에만
     * 있다.
     *
     * `null`은 label 없는 화면이거나 이 값을 아직 안 채우는 낡은 앱이다.
     */
    @SerialName("content_signature") val contentSignature: String? = null,
)

@Serializable
data class NextStepRequest(
    val goal: String,
    val screen: Screen,
    @SerialName("device_id") val deviceId: String? = null,
    /**
     * 이 세션에서 실제로 표시한 안내들. **스텝 하나 = 안내 하나**다(C11 개정, #270).
     * 활성 [GuideSession] 안에서만 최대 10개를 보관하므로 서버 상태나 기기 식별자가 필요 없다.
     */
    val steps: List<GuideStep> = emptyList(),
    /**
     * [steps] **앞에** 있었지만 상한에 밀려 빠진 안내 수(#418). 프롬프트가 "창이 꽉 찬 이유"를
     * 알게 하는 값이다 — 세션이 이제 10스텝짜리인지 40스텝째인지가 [steps]만으로는 구분되지 않는다.
     *
     * **개수만 나른다.** 밀려난 스텝의 화면 이름을 이어 붙이는 안은 실측으로 접었다 —
     * 근거는 `server/CLAUDE.md`의 「밀려난 안내 수」에 있다.
     */
    @SerialName("earlier_steps") val earlierSteps: Int = 0,
    /** 완료 확인에서 사용자가 "아직요"라고 답한 화면. 원문이나 세션 식별자는 싣지 않는다. */
    @SerialName("completion_feedback") val completionFeedback: CompletionFeedback? = null,
    @SerialName("client_version") val clientVersion: String? = null,
)

@Serializable
data class NextStepResponse(
    val action: GuideAction,
    val model: String,
    /**
     * 완료 검증(#54) 결과. `done`일 때만 의미가 있다.
     * `false`인 `done`은 세션을 끝내지 않는다 — [GuideSession.onDone]이 소비한다(#55).
     */
    val verified: Boolean = false,
    /**
     * 목표 완료 신뢰도(#424). 새 서버는 항상 보내며, 구버전 서버와의 호환 구간에는 null이다.
     * null이면 [verified]를 기존 규칙으로 해석한다.
     */
    @SerialName("completion_verdict") val completionVerdict: CompletionVerdict? = null,
    /**
     * 서버가 label 없이 계산한 현재 화면 구조 서명. 앱은 내용을 해석하거나 만들지 않고,
     * 표시한 행동과 함께 다음 요청으로 되돌려 보낸다.
     *
     * **선택 필드가 아니다.** 서버 스키마가 `str` + 패턴 검증으로 required이고 응답을 만드는
     * 곳이 한 군데뿐이라, 이 값이 빠진 응답은 서버가 만들 수 없다. 선택으로 열어 두면 세션이
     * "부정한 화면"을 가릴 수 없어 ×아직요가 조용히 먹지 않는다(PR #153 리뷰).
     */
    @SerialName("screen_signature") val screenSignature: String,
)

@Serializable
data class GuideAction(
    @SerialName("expected_result") val expectedResult: String,
    /**
     * 모델이 쓴 현재 화면 한 줄 요약(C11 개정, #270). 앱은 **해석하지 않고** 스텝의
     * `screen_hint`로 되돌려 보낸다 — 화면당 한 번 생성되고 이후로는 echo만 돈다.
     *
     * 서버 스키마는 required지만 여기 기본값을 두는 이유는 하나뿐이다 — 앱이 먼저 올라간
     * 구간에서 이 필드가 없는 응답을 만나면 **안내 전체가 파싱 실패로 죽는다.** 값이 비면
     * 이력에 싣지 않는 것으로 끝난다.
     */
    @SerialName("screen_summary") val screenSummary: String = "",
    val evidence: String,
    @SerialName("completion_evidence") val completionEvidence: CompletionEvidence? = null,
    @SerialName("action_type") val actionType: ActionType,
    val direction: SwipeDirection? = null,
    @SerialName("target_node_id") val targetNodeId: String? = null,
    @SerialName("expected_target_state") val expectedTargetState: ExpectedTargetState? = null,
    /**
     * `type`일 때 입력칸에 넣을 글자(C21). 서버 가드가 **안내문([instruction])이 집어 말한 말**만
     * 남긴다 — 값이 있으면 안내창에 「바로 입력」 버튼이 뜨고, 누르면 `ACTION_SET_TEXT`로 대상
     * 입력칸에 채운다(#405, `SPEC.md` §2의 유일한 예외).
     *
     * **동의를 받는 면이 안내문인 이유**: 사용자는 버튼을 누르기 직전에 그 문장을 읽으므로,
     * 채워지는 값이 방금 읽은 말과 같으면 버튼은 눈으로 확인된 일만 한다. 목표 문장과 대조하던
     * 초안은 2026-08-24에 뒤집혔다 — 검색어는 목표에 그대로 있지 않은 것이 정상이라
     * (`한국이랑 미국의 시차를 알고 싶어` -> `한국 미국 시차`) 정상 안내에서 버튼이 죽었다.
     */
    @SerialName("input_text") val inputText: String? = null,
    val instruction: String,
)

@Serializable
data class ExpectedTargetState(
    val attribute: ObservableState,
    val value: Boolean,
)

/**
 * 기대 상태는 서버 응답 원문의 id가 아니라, 앱이 현재 화면에서 실제로 찾은 tap 대상과 함께
 * 기록한다. 대상 조회가 실패했거나 다른 액션이면 id·label과 같은 자리에서 함께 비운다(#424).
 * 상태 의미는 서버가 검증하고 앱은 해석하지 않는다.
 */
internal fun GuideAction.expectedTargetStateFor(target: UiElement?): ExpectedTargetState? =
    expectedTargetState?.takeIf { actionType == ActionType.TAP && target != null }

@Serializable
data class CompletionEvidence(
    @SerialName("node_id") val nodeId: String,
    val kind: CompletionEvidenceKind,
    val state: ExpectedTargetState? = null,
)

@Serializable
data class CompletionFeedback(
    val status: CompletionFeedbackStatus,
    @SerialName("structure_signature") val structureSignature: String,
    @SerialName("content_signature") val contentSignature: String? = null,
)

/**
 * 안내 한 번의 기록(C11 개정, #270). 기준은 서버 `GuideStep`이다.
 *
 * 값의 출처는 둘뿐이다 — **서버 응답 echo**([instruction]·[actionType]·[direction]·
 * [screenHint]·[structureSignature])와 **앱 관측**([screenChanged]·[performed]·
 * [contentSignature]). 앱은 나르기만 하고 해석하지 않는다. [targetLabel]과 [targetNodeId]
 * 둘만은 응답의 `target_node_id`로 **현재 화면에서 찾은 결과**이고, 강조를 그리려고 어차피
 * 하는 조회라 새 해석이 아니다 — 대상이 안 잡히면 둘 다 비어 있다.
 *
 * [contentSignature]는 그 안내가 나간 화면의 [Screen.contentSignature]를 그대로 옮긴 값이다
 * (#410) — 요청 하나에서 두 자리가 같은 값을 쓰도록 보내는 자리에서 한 번만 계산한다.
 */
@Serializable
data class GuideStep(
    val instruction: String,
    @SerialName("action_type") val actionType: ActionType,
    val direction: SwipeDirection? = null,
    /** 안내 대상 요소의 label(1차 마스킹된 값). 대상이 없는 동작에는 없다. */
    @SerialName("target_label") val targetLabel: String? = null,
    /**
     * 안내 대상 요소의 node id. **응답 원문이 아니라 [targetLabel]과 같은 조회 결과다** —
     * 응답의 `target_node_id`로 현재 화면에서 찾은 요소의 id이고, 대상이 안 잡히면 둘 다 비어
     * 있다. 화면마다 다시 매겨지는 값이라 서버가 [structureSignature]와 짝으로만 쓴다.
     */
    @SerialName("target_node_id") val targetNodeId: String? = null,
    /** 실제로 조회된 tap 대상의 기대 상태. 상태의 의미는 해석하지 않고 다음 요청에 echo한다. */
    @SerialName("expected_target_state") val expectedTargetState: ExpectedTargetState? = null,
    /** 그 안내가 나간 화면이 무엇이었는지. 응답 [GuideAction.screenSummary]의 echo. */
    @SerialName("screen_hint") val screenHint: String? = null,
    /** 응답 [NextStepResponse.screenSignature]의 echo. 앱이 만들지 않는다. */
    @SerialName("structure_signature") val structureSignature: String,
    /** 그 안내 뒤 화면이 실제로 바뀌었는가. `null`은 아직 모른다는 뜻이다. */
    @SerialName("screen_changed") val screenChanged: Boolean? = null,
    /**
     * 안내한 대상을 사용자가 실제로 눌렀는가(`PressMatch`, #319). `false`는 좌표 축의 확신
     * 불일치(다른 노드를 누름)에서만 나오고, `null`은 귀속 미확인이다 — 이벤트가 안 왔거나,
     * source 없는 텍스트 불일치거나, 판정 밖 액션(TAP·TYPE 외)이다.
     */
    val performed: Boolean? = null,
    /** 그 안내가 나간 화면의 내용 축 서명. 출처는 [Screen.contentSignature]다. */
    @SerialName("content_signature") val contentSignature: String? = null,
)

/**
 * 서버 `ActionType`과 **값이 정확히 같아야 한다.**
 *
 * kotlinx.serialization은 모르는 enum 값을 만나면 예외를 던진다. `ignoreUnknownKeys`는 모르는
 * **키**만 무시할 뿐 값은 막아주지 못하고, `action_type`은 기본값 없는 필수 필드라
 * `coerceInputValues`로도 못 덮는다. 즉 한 글자만 어긋나도 그 액션이 오는 순간 안내가 끊긴다.
 *
 * 값의 근거는 `server/CLAUDE.md`의 "API 계약 결정" C6·C9다. 전역 제스처는 실기기 로그로 필요성이
 * 확인된 것만 하나씩 들어온다 — 뒤로/홈은 아직 근거가 없어 여기 없다.
 * 어긋나면 `server/tests/test_contract_drift.py`가 CI에서 잡는다.
 */
@Serializable
enum class ActionType {
    @SerialName("tap") TAP,
    @SerialName("scroll") SCROLL,
    @SerialName("type") TYPE,
    /**
     * 화면 요소가 아니라 **가장자리 제스처**다(#222) — `target_node_id` 없이 오고, 시작 지점
     * 선과 손 모션은 앱이 [EdgeGesture]의 표에서 만들어 그린다. "강조 없이 안내문만 뜬다"(#35)는
     * #222 이전 동작이다.
     *
     * `direction`도 안 오는 것이 관측이지만 **계약 보장은 아니다** — 서버 스키마는 scroll에서만
     * 필수로 요구할 뿐 다른 액션에서 금지하지 않는다(`server/app/schemas.py`의
     * `validate_scroll_direction`). 모델이 실어 보내도 앱은 표의 손가락 방향에 우선권을 줘
     * 무시한다(`HighlightOverlayView.setGuide`).
     */
    @SerialName("open_quick_settings") OPEN_QUICK_SETTINGS,

    /** [OPEN_QUICK_SETTINGS]와 같은 가장자리 제스처다(#222) — 계약도 표현 규칙도 같다. */
    @SerialName("open_app_drawer") OPEN_APP_DRAWER,
    @SerialName("done") DONE,
}

@Serializable
enum class CompletionVerdict {
    @SerialName("verified") VERIFIED,
    @SerialName("inconclusive") INCONCLUSIVE,
    @SerialName("contradicted") CONTRADICTED,
}

@Serializable
enum class CompletionEvidenceKind {
    @SerialName("success") SUCCESS,
    @SerialName("failure") FAILURE,
}

@Serializable
enum class ObservableState {
    @SerialName("checked") CHECKED,
    @SerialName("selected") SELECTED,
    @SerialName("enabled") ENABLED,
    @SerialName("showing_hint_text") SHOWING_HINT_TEXT,
}

/** 현재 접근성 노드에서 관측한 체크 상태(#297 · C25). */
@Serializable
enum class CheckState {
    @SerialName("checked") CHECKED,
    @SerialName("unchecked") UNCHECKED,
    @SerialName("partial") PARTIAL,
    @SerialName("unavailable") UNAVAILABLE,
}

@Serializable
enum class CompletionFeedbackStatus {
    @SerialName("user_rejected") USER_REJECTED,
}

/**
 * **화면이 이동하는 방향**, 즉 사용자가 **보러 가는 쪽**이다(C17, `SPEC.md` §4).
 * 손가락이 움직이는 방향이 아니다 — 목록 아래 내용을 보려면 `DOWN`, 다음 페이지는 `RIGHT`다.
 * 서버 `SwipeDirection`과 값이 같아야 한다.
 *
 * **이 값을 그대로 그리면 안 된다.** 실제로 해야 하는 손가락 동작은 정확히 반대이며, 변환은
 * [GestureMotion.fingerDirectionFor]가 한 자리에서 한 번만 한다. 새로 이 값을 소비하는 곳
 * (TTS 문구·화살표 등)은 무엇을 하는지를 말할 때는 이 값을, 어떻게 하는지를 그릴 때는
 * 변환한 값을 쓴다.
 */
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
    @SerialName("rate_limit_scope") val rateLimitScope: RateLimitScope? = null,
    @SerialName("request_id") val requestId: String? = null,
)

/** 서버 `RateLimitScope`와 값이 같아야 한다. null은 공급자·development의 기존 QUOTA다. */
@Serializable
enum class RateLimitScope {
    @SerialName("burst") BURST,
    @SerialName("daily") DAILY,
}

/**
 * 서버 `ErrorKind`와 **값이 정확히 같아야 한다**(위 [ActionType]과 같은 이유).
 *
 * 앱은 이 값과 [ApiError.rateLimitScope]를 보고 사용자 안내를 가른다 — 짧은 사용량 제한·24시간
 * 한도·인터넷 불량은 다르게 말해야 한다.
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

/**
 * 카카오 로그인 요청(기준: `server/app/kakao_auth.py`).
 * 카카오 access token은 이 body로만 나가고 로그·화면 어디에도 남기지 않는다.
 */
@Serializable
data class KakaoLoginRequest(
    @SerialName("kakao_access_token") val kakaoAccessToken: String,
    @SerialName("client_version") val clientVersion: String? = null,
)

/** 서버가 발급한 무상태 세션 토큰. 이후 보호 요청의 `Authorization: Bearer` 값이 된다. */
@Serializable
data class SessionTokenResponse(
    @SerialName("session_token") val sessionToken: String,
    @SerialName("expires_in_seconds") val expiresInSeconds: Int,
)

/** 음성 목표 대화의 이전 한 줄. 현재 발화는 [SlotFillRequest.utterance]에만 둔다(C13). */
@Serializable
data class ConversationTurn(
    val role: ConversationRole,
    val content: String,
)

@Serializable
enum class ConversationRole {
    @SerialName("user") USER,
    @SerialName("assistant") ASSISTANT,
}

/**
 * 고정 슬롯을 노출하지 않는 음성 목표 요청. #316 앱은 [clarificationAnswers]를 두 번째이자 마지막
 * 요청에 싣고, [conversationHistory]는 이전 앱 wire 호환을 위해서만 남긴다.
 * [sessionId]는 무작위 대화 경계이며 원문·기기 값에서 만들거나 로그에 남기지 않는다.
 */
@Serializable
data class SlotFillRequest(
    val utterance: String,
    @SerialName("session_id") val sessionId: String,
    @SerialName("conversation_history") val conversationHistory: List<ConversationTurn> = emptyList(),
    @SerialName("clarification_answers")
    val clarificationAnswers: List<ClarificationAnswer> = emptyList(),
)

@Serializable
data class ClarificationAnswer(
    @SerialName("question_id") val questionId: String,
    val prompt: String,
    val answer: String,
    val source: ClarificationAnswerSource,
)

@Serializable
enum class ClarificationAnswerSource {
    @SerialName("option") OPTION,
    @SerialName("freeform") FREEFORM,

    /** #316 초기 APK가 보낸 값의 wire 호환용. 새 UI는 모름·거절도 직접 발화로 보낸다. */
    @SerialName("unknown_or_declined") UNKNOWN_OR_DECLINED,
}

@Serializable
data class ClarificationQuestion(
    val id: String,
    val prompt: String,
    /** 대화가 뒷받침하는 보기 0~3개. 비어도 직접 말하기는 항상 가능하다. */
    val options: List<String>,
    @SerialName("allow_freeform") val allowFreeform: Boolean,
)

@Serializable
data class ClarificationPlan(
    val questions: List<ClarificationQuestion>,
)

@Serializable
data class SlotFillState(
    val status: IntentDialogueStatus,
    /** 화면에 보여줄 질문 또는 준비 완료 문장. 질문 계획에서는 **문항마다** TTS로 읽는다. */
    val message: String,
    /** READY일 때만 존재하며, 사용자가 확인할 기존 목표 입력칸에 넣는다. */
    val goal: String? = null,
    /** NEEDS_CLARIFICATION일 때 한 화면씩 진행할 최대 두 질문. */
    val clarification: ClarificationPlan? = null,
)

@Serializable
enum class IntentDialogueStatus {
    @SerialName("needs_clarification") NEEDS_CLARIFICATION,
    @SerialName("ready") READY,
    @SerialName("unable_to_finalize") UNABLE_TO_FINALIZE,
}

/** 서버가 제안한 후보 하나. package/alias는 서버 원본이며 기기 index 값을 역으로 채우지 않는다. */
@Serializable
data class AppCandidate(
    @SerialName("candidate_id") val candidateId: String,
    @SerialName("canonical_name") val canonicalName: String,
    @SerialName("package_names") val packageNames: List<String> = emptyList(),
    @SerialName("label_aliases") val labelAliases: List<String> = emptyList(),
    val confidence: Double,
)

@Serializable
data class AppCandidatePlan(
    val capability: String,
    val candidates: List<AppCandidate> = emptyList(),
)

@Serializable
enum class AppCandidateAvailability {
    @SerialName("launchable") LAUNCHABLE,
    @SerialName("not_found") NOT_FOUND,
    @SerialName("ambiguous") AMBIGUOUS,
    @SerialName("unknown") UNKNOWN,
}

@Serializable
data class AppCandidateResult(
    @SerialName("candidate_id") val candidateId: String,
    val status: AppCandidateAvailability,
)

@Serializable
data class AppResolutionFeedback(
    val plan: AppCandidatePlan,
    @SerialName("candidate_results") val candidateResults: List<AppCandidateResult>,
)

@Serializable
data class AppResolutionRequest(
    val goal: String,
    val feedback: AppResolutionFeedback? = null,
)

@Serializable
data class AppResolutionState(
    val status: AppResolutionStatus,
    val message: String,
    val plan: AppCandidatePlan? = null,
    @SerialName("selected_candidate_id") val selectedCandidateId: String? = null,
    val goal: String? = null,
)

@Serializable
enum class AppResolutionStatus {
    @SerialName("candidates") CANDIDATES,
    @SerialName("resolved") RESOLVED,
    @SerialName("needs_user_choice") NEEDS_USER_CHOICE,
    @SerialName("needs_app_name") NEEDS_APP_NAME,
    @SerialName("unavailable_or_unknown") UNAVAILABLE_OR_UNKNOWN,
}
