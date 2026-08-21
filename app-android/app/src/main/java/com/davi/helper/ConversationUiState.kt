package com.davi.helper

enum class ConfirmKind {
    GOAL,
    APP_AVAILABILITY_WARNING,
}

/**
 * 대화 화면(ConversationActivity)의 UI 상태와 렌더 모델.
 *
 * [VoiceDialogueSession.Stage]와 1:1이 아니다 — [Failed]는 잘못된 응답에서 오고,
 * [Confirm]은 READY 이후 화면 고유 상태다. 전이는 Activity의
 * 이벤트 지점(콜백 가드 통과 후)에서 일어나고, "상태에서 무엇을 보여줄지"는
 * [renderModelOf]가 순수 함수로 결정한다(JVM 테스트 대상). 디자인 확정 시 XML과
 * [renderModelOf]만 고치면 되도록 화면 판단을 여기로 모은다.
 */
sealed interface ConversationUiState {
    /** MA-1 홈. [statusRes]는 직전 시도의 에러 문구(없으면 null). */
    data class Home(val statusRes: Int? = null) : ConversationUiState

    /**
     * MA-2 듣는 중. [question]이 null이면 최초 목표 청취이고, 값이 있으면 구체화 직접 발화다.
     *
     * [micReady]는 **인식기가 실제로 말을 받아들이기 시작한 뒤**에만 true다(#273).
     * `startListening()`은 즉시 인식하지 않는다 — 실측(SM-S921N)에서 마이크 자체는 +60ms에
     * 열리는데 **그 구간에 한 말은 잡히지 않았고**, 실제로 받아들이기 시작한 것은 +0.9초였다.
     * 화면만 먼저 "듣고 있어요"를 띄우면 그 말을 믿고 바로 말한 첫 음절이 잘린다. 최초 발화와
     * 구체화 직접 발화가 모두 이 상태를 새로 만들므로 기본값 false가 자동으로 덮이고, 부분
     * 인식의 copy는 값을 그대로 들고 간다.
     */
    data class Listening(
        val question: String? = null,
        val partial: String = "",
        val micReady: Boolean = false,
        /** 보기 대신 직접 말하는 중이면 원래 문항을 보존해 무응답 뒤 선택 화면으로 돌아간다. */
        val clarification: VoiceDialogueSession.Outcome.Clarify? = null,
    ) : ConversationUiState

    /** 서버 응답 대기. [finalizing]이면 로컬 답을 합친 두 번째이자 마지막 호출이다. */
    data class Requesting(val utterance: String, val finalizing: Boolean = false) : ConversationUiState

    /** 서버가 한 번에 준 질문 계획 중 현재 한 문항. 선택은 서버 호출 없이 로컬에서 진행한다. */
    data class Clarifying(
        val outcome: VoiceDialogueSession.Outcome.Clarify,
        val speaking: Boolean = true,
    ) : ConversationUiState

    /** 두 번째 호출로도 안전한 목표를 만들지 못한 종료 화면. 사용자가 재시작 또는 홈을 고른다. */
    data class SafeEnd(val message: String) : ConversationUiState

    /**
     * MA-4 확인. [kind]가 [ConfirmKind.APP_AVAILABILITY_WARNING]이면 goal 대신 가능성 경고를
     * 보여 주되, 다음 화면에서 확인할 원래 goal은 그대로 보존한다. 경고 ○=goal 최종 확인,
     * 최종 확인 ○=안내 시작, 어느 화면에서든 ×=홈 복귀.
     */
    data class Confirm(
        val goal: String,
        val kind: ConfirmKind = ConfirmKind.GOAL,
    ) : ConversationUiState

    /**
     * 메인 10 안내 종료 확인. **안내 중 화면(메인 9) 위에 모달로 뜬다.**
     *
     * **"안내 중"은 상태가 아니다.** 그건 세션(`GuideSession`)이 들고 있고 화면은 그릴 때마다
     * 물어본다 — 같은 사실을 두 군데가 기억하면 세션이 밖에서 끝났을 때 화면만 남는다.
     * 반면 이 확인은 사용자가 버튼을 눌러 들어오고 ×로 나가는 **진짜 화면 상태**다.
     */
    data object StopConfirm : ConversationUiState

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
    /**
     * 실패 화면의 전신 캐릭터(메인 8, #208). **[logoVisible]과 배타적이다** — 이 화면만
     * 배지 대신 캐릭터가 위를 차지하고, 카드가 그 하단을 파고든다.
     * 장식이라 스크린리더에서는 읽지 않는다(레이아웃에서 제외).
     */
    val standVisible: Boolean = false,
    /** 메인 9 카드 안 지시문("새로 물어보시려면...") — 마이크가 잠긴 이유를 설명한다(#197). */
    val guidingVisible: Boolean = false,
    val clarificationVisible: Boolean = false,
    val safeEndVisible: Boolean = false,
    /** 메인 10 종료 확인 모달(#197). 메인 9 위에 덮인다. */
    val stopConfirmVisible: Boolean = false,
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
     *
     * **듣는 중이어도 마이크가 열리기 전에는 뜨지 않는다**(#273). 이 레이블은 곧
     * "지금 말씀하세요"라는 지시이므로, 아직 안 들리는 구간에 띄우면 첫 음절이 잘린다.
     */
    val utteranceLabelVisible: Boolean = false,
    val utteranceText: String = "",
    val micVisible: Boolean = false,
    val micEnabled: Boolean = false,
    val confirmVisible: Boolean = false,
    /**
     * 질문 카드가 남는 세로 공간을 나눠 갖는가(weight). 끄면 wrap_content + minHeight로 선다.
     *
     * **최종 확인에서도 켜야 한다**(#200 리뷰). 확인쌍이 카드 아래 열 안에 있어 카드가
     * 먼저 커지면 버튼이 화면 밖으로 밀린다 — 긴 goal과 큰 글꼴이 겹칠 때 실제로 넘친다
     * (가용 698dp에 고정 소비 351dp인데 카드 상한은 496dp까지 간다). 확인 화면에서
     * 버튼을 못 누르면 빠져나갈 길이 없으므로 카드 쪽이 양보한다.
     */
    val cardQuestionFlexible: Boolean = false,
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
    /**
     * 인식 준비 중이라 **화면 전체를 흐림으로 덮는가**(#277).
     *
     * 신호 셋과 다른 축이다 — 셋은 "이제 말하세요"이고 이것은 "아직 아니다"다. 그래서
     * 함께 켜지지 않고 **서로 반대로** 움직인다.
     */
    val signalsDimmed: Boolean = false,
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
    // **안내 중이면 홈이 아니라 메인 9다**(#197). 사용자가 안내를 받다가 다비 앱으로
    // 돌아온 상태이고, 세 갈래 중 무엇을 하려는지 물어야 한다 —
    // 기존 안내 계속(나가기) · 종료 · 새로 물어보기(먼저 종료해야 함).
    //
    // **상태로 두지 않고 여기서 가른다.** "안내 중"은 세션이 들고 있는 바깥 사실이라
    // 화면 상태로 복제하면 세션이 밖에서 끝났을 때 화면만 남는다.
    is ConversationUiState.Home -> if (guideActive) guidingModel() else ConversationRenderModel(
        homeVisible = true,
        statusRes = state.statusRes,
        micVisible = true,
        micEnabled = true,
        // 홈 유도(임시) — 누를 수 있을 때만. 시안에 홈 전용 모션이 아직 없다(#132).
        glowVisible = true,
        glowReactsToVoice = false,
    )
    // 메인 10 — 메인 9 위에 모달만 얹는다. 뒤 화면은 그대로 보인다(시안).
    // **세션이 밖에서 끝나면 모달도 내린다**(#197 리뷰). 끝낼 안내가 없는데 "정말 종료할까요?"가
    // 남으면, 이 PR이 "안내 중"을 상태로 두지 않은 이유(유령 화면)를 스스로 어긴다.
    // 화면 상태는 Activity가 정리하고 여기서는 그리지 않는 것으로 먼저 막는다.
    ConversationUiState.StopConfirm ->
        if (guideActive) guidingModel().copy(stopConfirmVisible = true)
        else ConversationRenderModel(homeVisible = true, micVisible = true, micEnabled = true, glowVisible = true)
    // **"듣고 있어요"·파도·글로우는 마이크가 실제로 열린 뒤에 켠다**(#273). 셋은 함께
    // "지금 말씀하세요"라는 하나의 지시라 따로 놀면 안 된다 — 파도만 먼저 돌면 레이블이
    // 늦은 만큼 신호가 갈린다. 마이크가 닫힌 구간에 유도 글로우만 남기지도 않는다:
    // 이 화면의 마이크는 눌리지 않으므로 "잠긴 마이크 뒤에서 글로우를 돌리지 않는다"는
    // 아래 홈 규칙이 여기에도 그대로 걸린다.
    //
    // **카드·배지·×는 그대로 둔다.** 준비 구간에 레이아웃까지 흔들리면 어긋남을 고치려다
    // 화면이 덜컹거린다. 카드 그림자([utteranceSpeaking])도 같은 이유로 유지한다.
    is ConversationUiState.Listening -> ConversationRenderModel(
        logoVisible = true,
        questionRes = if (state.question == null) R.string.conversation_prompt_initial else null,
        questionText = state.question,
        utteranceVisible = true,
        cardQuestionFlexible = true,
        utteranceText = state.partial,
        micVisible = true,
        closeVisible = state.clarification == null,
        // **신호 셋은 micReady에 함께 선다**(#273) — 미리 띄우면 그 말을 믿고 말한 첫 음절이
        // 잘린다. 준비 중을 알리는 것은 아래 [signalsDimmed]가 켜는 **전체 스크림**이고,
        // 그쪽은 "아직 아니다"를 말하므로 이 계약을 깨지 않는다(#277).
        waveVisible = state.micReady,
        glowVisible = state.micReady,
        glowReactsToVoice = state.micReady,
        utteranceLabelVisible = state.micReady,
        signalsDimmed = !state.micReady,
        utteranceSpeaking = true,
    )
    is ConversationUiState.Requesting -> ConversationRenderModel(
        logoVisible = true,
        questionRes = R.string.conversation_requesting,
        utteranceVisible = !state.finalizing,
        cardQuestionFlexible = true,
        utteranceText = state.utterance,
        micVisible = !state.finalizing,
        closeVisible = !state.finalizing,
        loadingDotsVisible = true,
    )
    is ConversationUiState.Clarifying -> ConversationRenderModel(
        logoVisible = true,
        questionText = state.outcome.question.prompt,
        cardQuestionFlexible = true,
        clarificationVisible = true,
        cardGlowVisible = state.speaking,
    )
    is ConversationUiState.Confirm -> when (state.kind) {
        ConfirmKind.GOAL -> ConversationRenderModel(
            logoVisible = true,
            questionText = state.goal,
            questionMaxHeightRes = R.dimen.davi_question_card_max_height_confirm,
            cardQuestionFlexible = true,
            confirmVisible = true,
            confirmAcceptTextRes = R.string.conversation_confirm_accept,
        )
        ConfirmKind.APP_AVAILABILITY_WARNING -> ConversationRenderModel(
            logoVisible = true,
            questionRes = R.string.conversation_app_availability_warning,
            questionMaxHeightRes = R.dimen.davi_question_card_max_height_confirm,
            cardQuestionFlexible = true,
            confirmVisible = true,
            confirmAcceptTextRes = R.string.conversation_confirm_continue,
        )
    }
    // 메인 8 — 배지가 아니라 전신 캐릭터가 서고 카드가 그 하단을 파고든다(#208).
    // **카드는 시안에서 state=speaking이다** — 실패 문구도 다비가 하는 말이므로 분홍
    // 글로우가 켜진다("글로우가 화자를 가리킨다", #176). 대기로 착각해 빼면 안 된다.
    ConversationUiState.Failed -> ConversationRenderModel(
        standVisible = true,
        cardGlowVisible = true,
        questionRes = R.string.conversation_failed,
    )
    is ConversationUiState.SafeEnd -> ConversationRenderModel(
        logoVisible = true,
        cardGlowVisible = true,
        questionText = state.message,
        cardQuestionFlexible = true,
        safeEndVisible = true,
    )
}

/**
 * 스스로 끝나지 않는 상태를 홈으로 되돌리는 지연. null이면 다른 경로로 빠져나간다
 * (Listening=인식 타임아웃, Speaking=TTS 완료, Requesting=서버 응답).
 *
 * [ConversationUiState.Confirm]은 ×/○ 탭 말고는 빠져나갈 길이 없어, 사용자가 자리를 뜨면
 * 화면이 계속 켜진 채 남는다. 값에 근거는 없다 — 무한을 막는 상한일 뿐이고 실사용을 보고 조정한다.
 */
/**
 * 메인 9 안내 중 화면(#197). **마이크·파도·글로우가 없다** — 안내가 도는 동안은 새 목표를
 * 받지 않기 때문이고, 카드 안 지시문이 그 이유를 말한다("먼저 안내를 종료해주세요").
 *
 * 배지와 카드는 다른 화면과 같은 자리다. 카드 아래로 세로 버튼쌍과 하단 안내가 붙는다.
 */
private fun guidingModel() = ConversationRenderModel(
    logoVisible = true,
    questionRes = R.string.conversation_guiding_title,
    guidingVisible = true,
)

fun autoReturnDelayMs(state: ConversationUiState): Long? = when (state) {
    ConversationUiState.Failed -> FAILED_RETURN_DELAY_MS
    is ConversationUiState.Confirm -> CONFIRM_TIMEOUT_MS
    // **종료 확인에는 타이머를 두지 않는다.** 뒤에서 안내가 계속 돌고 있으므로 방치돼도
    // 사용자가 갇히지 않는다 — 나가면 그만이다. 최종 확인(Confirm)과 다른 점이다.
    ConversationUiState.StopConfirm -> null
    is ConversationUiState.SafeEnd -> null
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
 * 서버 응답 Outcome → 다음 UI 상태. 첫 Clarify 질문과 Ready의 message TTS는 Activity가
 * 이 상태로 바꾼 뒤 수행한다. 최종화 불가는 재시작/홈을 고르는 안전 종료로 분리한다.
 */
fun stateForOutcome(outcome: VoiceDialogueSession.Outcome): ConversationUiState = when (outcome) {
    is VoiceDialogueSession.Outcome.Clarify -> ConversationUiState.Clarifying(outcome)
    is VoiceDialogueSession.Outcome.Ready -> ConversationUiState.Confirm(outcome.goal)
    is VoiceDialogueSession.Outcome.UnableToFinalize -> ConversationUiState.SafeEnd(outcome.message)
    VoiceDialogueSession.Outcome.InvalidResponse -> ConversationUiState.Failed
}

/** 질문 계획은 첫 문항만 TTS로 읽는다. 뒤 문항은 같은 화면에서 즉시 선택 가능해야 한다. */
fun shouldSpeakClarification(outcome: VoiceDialogueSession.Outcome.Clarify): Boolean =
    outcome.questionIndex == 0
