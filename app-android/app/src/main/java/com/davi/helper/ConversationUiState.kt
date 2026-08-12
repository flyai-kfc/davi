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

    /** MA-3 서버 질문 TTS 재생 중. 끝나면 Activity가 Listening으로 되돌린다. */
    data class Speaking(val question: String) : ConversationUiState

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
    val utteranceVisible: Boolean = false,
    val utteranceText: String = "",
    val micVisible: Boolean = false,
    val micEnabled: Boolean = false,
    val confirmVisible: Boolean = false,
    val closeVisible: Boolean = false,
)

/** 상태 → 렌더 모델. 발화 카드(핑크)는 대화 진행 중(듣는 중·재생 중·대기 중) 내내 보인다. */
fun renderModelOf(state: ConversationUiState): ConversationRenderModel = when (state) {
    is ConversationUiState.Home -> ConversationRenderModel(
        homeVisible = true,
        statusRes = state.statusRes,
        micVisible = true,
        micEnabled = true,
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
        confirmVisible = true,
    )
    ConversationUiState.Failed -> ConversationRenderModel(
        logoVisible = true,
        questionRes = R.string.conversation_failed,
    )
}

/**
 * 서버 응답 Outcome → 다음 UI 상태. Ask의 TTS 재생·Ready의 message TTS는 Activity가
 * 이 상태로 바꾼 뒤 수행한다. LimitReached는 기존 goalInput 텍스트 폴백을 대체해
 * 실패 화면으로 보낸다(#123 — 음성 인식 실패·무응답 세분화는 #106 범위).
 */
fun stateForOutcome(outcome: VoiceDialogueSession.Outcome): ConversationUiState = when (outcome) {
    is VoiceDialogueSession.Outcome.Ask -> ConversationUiState.Speaking(outcome.message)
    is VoiceDialogueSession.Outcome.Ready -> ConversationUiState.Confirm(outcome.goal)
    VoiceDialogueSession.Outcome.LimitReached -> ConversationUiState.Failed
    VoiceDialogueSession.Outcome.InvalidResponse -> ConversationUiState.Failed
}
