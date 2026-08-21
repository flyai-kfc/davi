package com.davi.helper

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
     * 텍스트 입력을 받을 수 있는 요소인가. 입력 원문은 [label]에 넣지 않는다.
     * 예외는 구조 검증된 NumberPicker 선택 상태뿐이다(#117).
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
    /** 켬/끔 상태. 켤 수 없는(`checkable`이 아닌) 요소는 `null`이다 — 그 `false`는 "꺼짐"이 아니다. */
    val checked: Boolean? = null,
    /** 목록·탭에서 지금 선택된 항목인가. */
    val selected: Boolean? = null,
    /**
     * 입력칸에 지금 보이는 글자가 hint인가. `true` 비어 있음 / `false` 값이 들어 있음 /
     * `null` 입력칸이 아니거나 미관측.
     *
     * **입력 원문은 여전히 보내지 않는다**(#112). 비었는지 여부만 알려 준다.
     */
    @SerialName("showing_hint_text") val showingHintText: Boolean? = null,
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
    @SerialName("action_type") val actionType: ActionType,
    val direction: SwipeDirection? = null,
    @SerialName("target_node_id") val targetNodeId: String? = null,
    val instruction: String,
)

/**
 * 안내 한 번의 기록(C11 개정, #270). 기준은 서버 `GuideStep`이다.
 *
 * 값의 출처는 둘뿐이다 — **서버 응답 echo**([instruction]·[actionType]·[direction]·
 * [targetLabel]·[screenHint]·[structureSignature])와 **앱 관측**([screenChanged]·[performed]·
 * [contentSignature]). 앱은 나르기만 하고 해석하지 않는다. [targetLabel]만 `target_node_id`로
 * 현재 화면에서 찾아 붙이는데, 강조를 그리려고 어차피 하는 조회다.
 *
 * [performed]와 [contentSignature]는 **아직 채우지 않는다**(#270 서브이슈 3·4). 필드를 먼저
 * 두는 것은 앱·서버 관통 계약 변경을 한 번으로 묶기 위해서다.
 */
@Serializable
data class GuideStep(
    val instruction: String,
    @SerialName("action_type") val actionType: ActionType,
    val direction: SwipeDirection? = null,
    /** 안내 대상 요소의 label(1차 마스킹된 값). 대상이 없는 동작에는 없다. */
    @SerialName("target_label") val targetLabel: String? = null,
    /** 그 안내가 나간 화면이 무엇이었는지. 응답 [GuideAction.screenSummary]의 echo. */
    @SerialName("screen_hint") val screenHint: String? = null,
    /** 응답 [NextStepResponse.screenSignature]의 echo. 앱이 만들지 않는다. */
    @SerialName("structure_signature") val structureSignature: String,
    /** 그 안내 뒤 화면이 실제로 바뀌었는가. `null`은 아직 모른다는 뜻이다. */
    @SerialName("screen_changed") val screenChanged: Boolean? = null,
    val performed: Boolean? = null,
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
    @SerialName("open_quick_settings") OPEN_QUICK_SETTINGS,

    /** 화면 요소가 아니라 제스처다. `target_node_id`가 없으므로 강조 없이 안내문만 뜬다(#35). */
    @SerialName("open_app_drawer") OPEN_APP_DRAWER,
    @SerialName("done") DONE,
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
    @SerialName("unknown_or_declined") UNKNOWN_OR_DECLINED,
}

@Serializable
data class ClarificationQuestion(
    val id: String,
    val prompt: String,
    /** 대화가 뒷받침하는 보기 0~3개. 비어도 직접 말하기와 모름 답은 항상 가능하다. */
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
    /** 화면에 보여줄 질문 또는 준비 완료 문장. 질문 계획에서는 첫 문항만 TTS로 읽는다. */
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
