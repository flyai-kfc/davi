package com.davi.helper

/**
 * 대화 화면(ConversationActivity)의 UI 상태와 렌더 모델.
 *
 * [VoiceDialogueSession.Stage]와 1:1이 아니다 — [Failed]는 Outcome(LimitReached·
 * InvalidResponse)에서 오고, [Confirm]은 READY 이후 화면 고유 상태다. 전이는 Activity의
 * 이벤트 지점(콜백 가드 통과 후)에서 일어나고, "상태에서 무엇을 보여줄지"는
 * [renderModelOf]가 순수 함수로 결정한다(JVM 테스트 대상). 디자인 확정 시 XML과
 * [renderModelOf]만 고치면 되도록 화면 판단을 여기로 모은다.
 */
sealed interface ConversationUiState {
    /** MA-1 홈. [statusRes]는 직전 시도의 에러 문구(없으면 null). */
    data class Home(val statusRes: Int? = null) : ConversationUiState

    /** MA-2 듣는 중. [question]이 null이면 첫 청취(초기 프롬프트), 아니면 서버 후속 질문. */
    data class Listening(val question: String? = null, val partial: String = "") : ConversationUiState

    /**
     * MA-3 서버 질문 TTS 재생 중. 끝나면 Activity가 Listening으로 되돌린다(자동 재청취는
     * 잠정 — 미결). mic을 누르면 재생을 끊고 바로 듣기로 들어간다(barge-in) —
     * [generation]은 그 전이를 세션에 대조하는 값이다.
     */
    data class Speaking(val question: String, val generation: Long) : ConversationUiState

    /** 서버 응답 대기("잠시만요"). [utterance]는 방금 인식된 발화 — 발화 카드에 유지한다. */
    data class Requesting(val utterance: String) : ConversationUiState

    /** MA-4 최종 확인. ○=안내 시작, ×=홈 복귀. */
    data class Confirm(val goal: String) : ConversationUiState

    /** 이해 실패. 5초 뒤 홈 복귀 타이머는 Activity가 담당한다. */
    data object Failed : ConversationUiState
}

/**
 * 화면에 그릴 것의 선언적 기술. Activity의 render()는 이 값을 binding에 대입만 한다.
 * 질문 카드 문구는 [questionRes](고정 문구) 또는 [questionText](서버 생성 문구·goal) 중
 * 하나만 채워진다.
 */
data class ConversationRenderModel(
    val homeVisible: Boolean = false,
    val statusRes: Int? = null,
    val logoVisible: Boolean = false,
    val questionRes: Int? = null,
    val questionText: String? = null,
    /**
     * 질문 카드 높이 상한(dimen). 상한은 dp 고정이라 글꼴을 키울수록 보이는 줄 수가 줄어드는데,
     * 그 사용자가 스크롤이 제일 어려운 사용자다. 발화 카드가 숨는 최종 확인에서는 세로 여유가
     * 있으므로 상한을 넓게 잡는다(PR #125 리뷰).
     */
    val questionMaxHeightRes: Int = R.dimen.davi_question_card_max_height,
    val utteranceVisible: Boolean = false,
    val utteranceText: String = "",
    val micVisible: Boolean = false,
    val micEnabled: Boolean = false,
    val stopGuideVisible: Boolean = false,
    val confirmVisible: Boolean = false,
    val closeVisible: Boolean = false,
)

/**
 * 상태 → 렌더 모델. 발화 카드(핑크)는 대화 진행 중(듣는 중·재생 중·대기 중) 내내 **자리를
 * 지키되 비어 있을 수 있다** — 질문 재생·재청취 대기 구간에서 비는 것은 와이어프레임대로다
 * (사용자가 다음에 말할 자리라는 표시). 채워지는 것은 부분 인식과 응답 대기 구간이다.
 * 마이크: active=누를 수 있는 상태(홈 idle, 질문 재생 중 barge-in) / deactive=누르지 못하는
 * 상태(듣는 중·응답 대기 — 둘의 시각 구분은 추후 웨이브폼 등으로).
 *
 * [guideActive]: 안내 세션이 도는 동안 홈은 "안내 중지"를 보이고 mic을 잠근다 — 새 화면에
 * 세션 중단 수단이 없던 공백의 임시 조치(#123). 오버레이 X 칩은 터치 계약 [미결].
 */
fun renderModelOf(state: ConversationUiState, guideActive: Boolean = false): ConversationRenderModel = when (state) {
    is ConversationUiState.Home -> ConversationRenderModel(
        homeVisible = true,
        statusRes = state.statusRes,
        micVisible = true,
        micEnabled = !guideActive,
        stopGuideVisible = guideActive,
    )
    is ConversationUiState.Listening -> ConversationRenderModel(
        logoVisible = true,
        questionRes = if (state.question == null) R.string.conversation_prompt_initial else null,
        questionText = state.question,
        utteranceVisible = true,
        utteranceText = state.partial,
        micVisible = true,
        closeVisible = true,
    )
    is ConversationUiState.Speaking -> ConversationRenderModel(
        logoVisible = true,
        questionText = state.question,
        utteranceVisible = true,
        micVisible = true,
        micEnabled = true, // barge-in — 재생을 끊고 바로 답할 수 있다
        closeVisible = true,
    )
    is ConversationUiState.Requesting -> ConversationRenderModel(
        logoVisible = true,
        questionRes = R.string.conversation_requesting,
        utteranceVisible = true,
        utteranceText = state.utterance,
        micVisible = true,
        closeVisible = true,
    )
    is ConversationUiState.Confirm -> ConversationRenderModel(
        logoVisible = true,
        questionText = state.goal,
        questionMaxHeightRes = R.dimen.davi_question_card_max_height_confirm,
        confirmVisible = true,
    )
    ConversationUiState.Failed -> ConversationRenderModel(
        logoVisible = true,
        questionRes = R.string.conversation_failed,
    )
}

/**
 * 스스로 끝나지 않는 상태를 홈으로 되돌리는 지연. null이면 다른 경로로 빠져나간다
 * (Listening=인식 타임아웃, Speaking=TTS 완료, Requesting=서버 응답).
 *
 * [ConversationUiState.Confirm]은 ×/○ 탭 말고는 빠져나갈 길이 없어, 사용자가 자리를 뜨면
 * 화면이 계속 켜진 채 남는다. 값에 근거는 없다 — 무한을 막는 상한일 뿐이고 실사용을 보고 조정한다.
 */
fun autoReturnDelayMs(state: ConversationUiState): Long? = when (state) {
    ConversationUiState.Failed -> FAILED_RETURN_DELAY_MS
    is ConversationUiState.Confirm -> CONFIRM_TIMEOUT_MS
    else -> null
}

const val FAILED_RETURN_DELAY_MS = 5_000L
const val CONFIRM_TIMEOUT_MS = 120_000L

/**
 * 서버 응답 Outcome → 다음 UI 상태. Ask의 TTS 재생·Ready의 message TTS는 Activity가
 * 이 상태로 바꾼 뒤 수행한다. LimitReached는 기존 goalInput 텍스트 폴백을 대체해
 * 실패 화면으로 보낸다(#123 — 음성 인식 실패·무응답 세분화는 #106 범위).
 */
fun stateForOutcome(outcome: VoiceDialogueSession.Outcome): ConversationUiState = when (outcome) {
    is VoiceDialogueSession.Outcome.Ask ->
        ConversationUiState.Speaking(outcome.message, outcome.generation)
    is VoiceDialogueSession.Outcome.Ready -> ConversationUiState.Confirm(outcome.goal)
    VoiceDialogueSession.Outcome.LimitReached -> ConversationUiState.Failed
    VoiceDialogueSession.Outcome.InvalidResponse -> ConversationUiState.Failed
}
