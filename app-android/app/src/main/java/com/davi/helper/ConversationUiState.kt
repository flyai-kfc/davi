package com.davi.helper

enum class ConfirmKind {
    GOAL,
    APP_AVAILABILITY_WARNING,
}

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

    /**
     * MA-4 확인. [kind]가 [ConfirmKind.APP_AVAILABILITY_WARNING]이면 goal 대신 가능성 경고를
     * 보여 주되, 다음 화면에서 확인할 원래 goal은 그대로 보존한다. 경고 ○=goal 최종 확인,
     * 최종 확인 ○=안내 시작, 어느 화면에서든 ×=홈 복귀.
     */
    data class Confirm(
        val goal: String,
        val kind: ConfirmKind = ConfirmKind.GOAL,
    ) : ConversationUiState

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
    /**
     * 시니어 카드 위쪽 "듣고 있어요" 레이블(#174). **듣는 중에만** 뜬다 — 시안 메인 3·5.
     * 마이크가 그 동안 비활성이라(이미 듣고 있으므로) 사용자에게 남는 신호가 이것과 파도뿐이다.
     */
    val utteranceLabelVisible: Boolean = false,
    val utteranceText: String = "",
    val micVisible: Boolean = false,
    val micEnabled: Boolean = false,
    val stopGuideVisible: Boolean = false,
    val confirmVisible: Boolean = false,
    /**
     * 남는 세로 공간을 **다비 카드**가 먹는가.
     *
     * **읽어야 하는 쪽이 커진다**(#207). 다비 카드에는 **처음 보는 질문**이 들어오고 시니어
     * 카드에는 **사용자가 방금 한 말**이 들어온다 — 이미 아는 내용이다. 그래서 다비 문구가
     * 있는 동안은 다비 카드가 남는 공간을 갖고, 시니어 카드는 제 내용만큼만 선다.
     *
     * **"말하는 쪽"이 아니다.** 그렇게 잡았더니 다비가 질문을 던지고 듣는 상태에서 시니어가
     * 커지면서 **질문 상자가 하한(176dp)으로 눌렸다** — 사용자가 그 질문을 읽고 답하는
     * 바로 그 순간이다(2026-08-18 실기기: 다비 176dp / 시니어 280dp).
     *
     * **둘 다 켜면 안 된다.** 예전에는 두 카드가 weight를 나눠 가져(176:196) 둘 다 커졌고,
     * 문구가 위 정렬이라 양쪽 아래가 크게 비어 "글자가 위로 쏠렸다"로 보였다(#207).
     */
    val daviCardFills: Boolean = false,
    /**
     * 남는 세로 공간을 **아래**에 둘 것인가. 확인·실패에서 켠다 — 카드가 하나뿐이고
     * 마이크가 없으며, 시안이 버튼쌍 아래를 비워 둔다.
     */
    val spaceBottomVisible: Boolean = false,
    /**
     * 확인쌍 ○의 **보이는 글자**. 확인 화면이 둘이라(경고 -> 최종 확인) 같은 모양이 연달아
     * 나오는데, 무엇을 누르는지가 글자로 갈려야 한다. #194는 아이콘 전용이라 이 구분을
     * contentDescription으로만 줄 수 있었고 눈으로 읽는 사용자에게는 두 화면이 같아 보였다.
     */
    val confirmAcceptTextRes: Int = R.string.conversation_confirm_accept,
    val closeVisible: Boolean = false,
    /**
     * 하단 파도(#132). **사용자가 말하는 동안에만** true — 다비가 말할 때는 다른 모양으로
     * 바꾸는 게 아니라 아예 그리지 않는다. 이 있음/없음이 발화 주체 구분 그 자체다
     * (시안 메인 2·4에는 없고 3·5에는 있다).
     */
    val waveVisible: Boolean = false,
    /** 마이크 글로우. 듣는 중(음량 반응)과 홈 유도(고정 숨쉬기) 두 자리에서 쓴다. */
    val glowVisible: Boolean = false,
    /**
     * 다비 카드 뒤 분홍 글로우(#170). **다비가 말하는 동안에만** true.
     * 파도가 "사용자 차례"의 신호이듯 이쪽이 "다비 차례"의 신호다 — 둘은 같이 켜지지 않는다.
     */
    val cardGlowVisible: Boolean = false,
    /**
     * 시니어 카드가 발화 중인가(#176). 시안이 카드마다 발화/대기 그림자를 따로 잡았다 —
     * 발화 쪽이 진하고 윗변에 유리 하이라이트가 얹힌다.
     * **다비 쪽은 [cardGlowVisible]이 같은 역할을 한다** — 분홍 글로우가 함께 켜지므로.
     */
    val utteranceSpeaking: Boolean = false,
    /**
     * 글로우가 음량에 반응하는가. 듣는 중에는 true, 홈 유도에서는 들을 마이크가 없어 false.
     * [glowVisible]이 false면 의미가 없다.
     */
    val glowReactsToVoice: Boolean = false,
    /**
     * 응답 대기 점 3개(#142). 서버가 느릴 때 화면이 멈춘 것으로 보이지 않게 한다.
     * **"잠시만요" 문구를 대체하지 않는다** — 시안(메인 6)은 문구 아래에 점을 둔다.
     */
    val loadingDotsVisible: Boolean = false,
)

/**
 * 상태 → 렌더 모델. 발화 카드(핑크)는 대화 진행 중(듣는 중·재생 중·대기 중) 내내 **자리를
 * 지키되 비어 있을 수 있다** — 질문 재생·재청취 대기 구간에서 비는 것은 와이어프레임대로다
 * (사용자가 다음에 말할 자리라는 표시). 채워지는 것은 부분 인식과 응답 대기 구간이다.
 * 마이크: active=누를 수 있는 상태(홈 idle, 질문 재생 중 barge-in) / deactive=누르지 못하는
 * 상태(듣는 중·응답 대기 — 둘의 시각 구분은 추후 웨이브폼 등으로).
 *
 * [guideActive]: 안내 세션이 도는 동안 홈은 "안내 중지"를 보이고 mic을 잠그며, **왜 잠겼는지를
 * 상태 문구로 말한다**(#29 — 그 전에는 문구가 비어 있어 화면에 이유가 없었다).
 * #126이 오버레이에 X 칩을 세워 앱에 오지 않고도 끝낼 수 있게 됐지만, 사용자가 실수로 앱을
 * 다녀오는 경로가 남아 **중단 수단을 양쪽에 둔다**(2026-08-13 결정).
 *
 * 음성 모션(#132)의 판단도 여기 있다. **잠긴 마이크 뒤에서 유도 글로우를 돌리지 않는다** —
 * 누를 수 없는 버튼이 "누르세요" 하고 빛나면 거짓 유도이고, 어르신은 눌리지 않는 버튼을
 * 반복해서 누르게 된다. 그래서 홈 글로우는 [ConversationRenderModel.micEnabled]와 같은
 * 조건을 탄다.
 */
fun renderModelOf(state: ConversationUiState, guideActive: Boolean = false): ConversationRenderModel = when (state) {
    is ConversationUiState.Home -> ConversationRenderModel(
        homeVisible = true,
        // 안내 중이면 그 사실이 직전 시도의 오류보다 급하다(#29) — 마이크가 잠긴 이유가
        // 화면에 없으면 사용자는 눌리지 않는 버튼을 반복해서 누른다.
        statusRes = if (guideActive) R.string.conversation_guide_active else state.statusRes,
        micVisible = true,
        micEnabled = !guideActive,
        stopGuideVisible = guideActive,
        // 홈 유도(임시) — 누를 수 있을 때만. 시안에 홈 전용 모션이 아직 없다(#132).
        glowVisible = !guideActive,
        glowReactsToVoice = false,
    )
    is ConversationUiState.Listening -> ConversationRenderModel(
        logoVisible = true,
        questionRes = if (state.question == null) R.string.conversation_prompt_initial else null,
        questionText = state.question,
        utteranceVisible = true,
        daviCardFills = true,
        utteranceText = state.partial,
        micVisible = true,
        closeVisible = true,
        waveVisible = true,
        glowVisible = true,
        glowReactsToVoice = true,
        utteranceLabelVisible = true,
        utteranceSpeaking = true,
    )
    // 다비가 말하는 중 — **파도와 마이크 글로우는 의도적으로 켜지 않는다**(#132).
    // 사용자 차례와 구분되는 신호가 그 부재이므로, 하단에 다른 모션을 넣으면 구분이 사라진다.
    // 대신 v2는 **다비 카드 뒤 분홍 글로우**로 다비 차례를 표시한다(#170) — 신호가 하단이
    // 아니라 카드 쪽에 생기므로 위 구분을 흐리지 않는다.
    is ConversationUiState.Speaking -> ConversationRenderModel(
        logoVisible = true,
        questionText = state.question,
        utteranceVisible = true,
        daviCardFills = true,
        micVisible = true,
        micEnabled = true, // barge-in — 재생을 끊고 바로 답할 수 있다
        closeVisible = true,
        cardGlowVisible = true,
    )
    is ConversationUiState.Requesting -> ConversationRenderModel(
        logoVisible = true,
        questionRes = R.string.conversation_requesting,
        utteranceVisible = true,
        daviCardFills = true,
        utteranceText = state.utterance,
        micVisible = true,
        closeVisible = true,
        loadingDotsVisible = true,
    )
    is ConversationUiState.Confirm -> when (state.kind) {
        ConfirmKind.GOAL -> ConversationRenderModel(
            logoVisible = true,
            questionText = state.goal,
            questionMaxHeightRes = R.dimen.davi_question_card_max_height_confirm,
            spaceBottomVisible = true,
            confirmVisible = true,
            confirmAcceptTextRes = R.string.conversation_confirm_accept,
        )
        ConfirmKind.APP_AVAILABILITY_WARNING -> ConversationRenderModel(
            logoVisible = true,
            questionRes = R.string.conversation_app_availability_warning,
            questionMaxHeightRes = R.dimen.davi_question_card_max_height_confirm,
            spaceBottomVisible = true,
            confirmVisible = true,
            confirmAcceptTextRes = R.string.conversation_confirm_continue,
        )
    }
    ConversationUiState.Failed -> ConversationRenderModel(
        logoVisible = true,
        spaceBottomVisible = true,
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
 * 확인 화면의 ○가 안내 시작 전에 거쳐야 할 다음 확인 상태. 앱 가능성 경고에서는 원래 goal을
 * 반드시 다시 보여 주고, 일반 goal 확인에서는 null을 반환해 안내 시작을 허용한다(#185).
 */
fun followUpConfirmationAfterAccept(
    state: ConversationUiState.Confirm,
): ConversationUiState.Confirm? = when (state.kind) {
    ConfirmKind.GOAL -> null
    ConfirmKind.APP_AVAILABILITY_WARNING -> ConversationUiState.Confirm(
        goal = state.goal,
        kind = ConfirmKind.GOAL,
    )
}

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
