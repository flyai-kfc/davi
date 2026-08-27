package com.davi.helper

/**
 * 오버레이에 "지금 무엇이 보여야 하는가"를 판정하는 순수 상태. 서비스는 판정 결과만 그린다.
 *
 * PR #63 리뷰 P1이 이 분리의 이유다: 상한 안내가 root=null 청소(`overlay.clear`)로 한 번
 * 지워지면 되살릴 길이 없었다 — 문구가 시키는 행동('안내 중지' 버튼이 있는 우리 앱 열기)
 * 자체가 문구를 지우는 조건이었다. 재표시 결정을 Android 밖으로 꺼내 JVM 테스트로 고정한다.
 *
 * [ScreenStabilityTracker]처럼 메인 스레드에서만 만진다.
 */
class GuideDisplayState {

    /**
     * 확인 화면의 종류. 둘은 **같은 상태 기계**이고 계기·문구·크롬만 다르다 — ○가 세션을
     * 끝내고 ×가 세션을 잇는 뼈대가 같아서 한 축으로 가른다(#193).
     *
     * **동시에 뜨지 않는다.** [DONE] 표시 중에는 컨트롤 행이 확인쌍이라 중지 칩이 없고,
     * 따라서 [QUIT]로 들어갈 입구가 닫힌다. 확인 상태를 겹쳐 쌓을 필요가 없는 근거다.
     */
    enum class ConfirmKind {
        /** 완료 확인(GD-3). 서버 `done`이 띄운다. 성공 tone 테두리 + 하단 고정. */
        DONE,

        /** 종료 확인(GD-4). 중지 칩이 띄운다. 전면 스크림 + 화면 세로 중앙. */
        QUIT,
    }

    /**
     * 오버레이에 그릴 내용. [bounds]는 `[left, top, right, bottom]`, 강조가 없으면 null.
     *
     * [actionType]은 안내창이 무엇을 피해야 하는지를 가른다 — 타이핑 안내면 키보드가 곧
     * 조작 대상이라 절대 가리면 안 된다(#38 5번). 우리가 만든 안내(정체·상한)는 null이다.
     *
     * [direction]은 방향 제스처 시연에 쓴다(#152). **손가락이 움직이는 방향이다** — 서버가 준
     * 값 그대로가 아니라, `GuideAccessibilityService`가 [GestureMotion.fingerDirectionFor]로
     * 한 번 뒤집은 결과다(C17 — 서버 값은 보러 가는 방향이다). 이 값을 새로 소비하는 곳
     * (예: TTS 문구, 화살표)은 **다시 보정하지 않는다**(PR #159 리뷰 4번).
     *
     * [confirm]이 있으면 안내창 컨트롤 행이 X칩 대신 확인쌍이 된다. 무엇을 그릴지의 판정이라
     * 여기 있고, 어떻게 그릴지는 오버레이가 정한다.
     *
     * [inputText]는 「바로 입력」 버튼이 입력칸에 채울 글자다(C21 · #405). 값이 있으면
     * 안내창 컨트롤 행에 버튼이 뜬다. **type 안내에서만 남긴다** — 서버 가드가 이미 비우지만
     * (`_keep_input_text_named_by_instruction`), 이 값으로 창에 버튼이 생기므로 앱도 [show]에서 한 번 더
     * 좁힌다. 값이 곧 예외 조작의 범위라서다(`SPEC.md` §2).
     *
     * [targetSuppressed]는 **강조·손 시연을 그리지 말라는 명시 신호**다(PR #343 리뷰 P1).
     * 예전에는 `copy(bounds = null, direction = null)`이 그 뜻을 겸했는데, 가장자리 제스처
     * (#222)의 강조 선·손은 [actionType]에서 파생돼 그 규약을 조용히 비껴갔다 — 지우는 쪽
     * 코드만 봐서는 새는 걸 알 수 없는 모양이라, 억제를 값 하나로 명시하고 그리는 쪽
     * (`HighlightOverlayView.setGuide`)이 이 값을 직접 본다. 새 억제 경로는 bounds·direction을
     * 지울 것이 아니라 **이 값을 세우면 된다.**
     *
     * [waiting]은 완료 재확인 백오프 대기다(#480, [showWaitNotice]). 컨트롤 행의 점 셋을
     * **읽는 중과 다른 리듬으로** 켜라는 신호이고, 읽는 중 테두리와는 무관하다 —
     * 그쪽은 [ReadingBorder.isReading]이 계속 false다(백오프를 읽는 중으로 보이면 안 된다,
     * #295). 문구·테두리 유무·점 리듬 셋이 두 상태를 가른다.
     */
    data class Content(
        val bounds: List<Int>?,
        val text: String,
        val actionType: ActionType? = null,
        val direction: SwipeDirection? = null,
        val inputText: String? = null,
        val confirm: ConfirmKind? = null,
        val targetSuppressed: Boolean = false,
        val waiting: Boolean = false,
    )

    /**
     * [redisplay]가 실제 오버레이에 요구하는 변경.
     *
     * nullable [Content]만 돌려주면 "그릴 것이 없다"와 "지금 떠 있는 것을 걷어라"가 둘 다
     * null이 된다. 읽기 불가 안내를 다른 안정 화면에서 폐기할 때 상태만 비고 오버레이 문구가
     * 남았던 이유다(PR #352 재리뷰). 서비스가 [Clear]를 빠뜨릴 수 없게 결과를 전수로 가른다.
     */
    sealed interface RedisplayResult {
        data object Unchanged : RedisplayResult

        data object Clear : RedisplayResult

        /**
         * 다른 화면이라 안내를 **폐기했다**(#456). 그리기로는 [Unchanged]와 같지만 —
         * 지워진 안내를 지웠으니 화면에 할 일이 없다 — 호출자에게는 다른 뜻이다.
         *
         * 폐기의 근거가 "그 화면의 새 요청은 어차피 나간다"인데, 회전으로 접힌 화면에서는
         * 그 요청이 나가지 않는다. 둘이 겹치면 **안내가 영영 사라진다** — 문구도 강조도 없이,
         * 정체 문구조차 `guide == null`이라 안 뜬다. 그래서 폐기를 신호로 올려 호출자가
         * 회전 걸쇠를 푼다.
         */
        data object Discarded : RedisplayResult

        data class Show(val content: Content) : RedisplayResult
    }

    private var guide: Content? = null

    /**
     * 안내가 속한 화면의 서명. 재표시는 **같은 화면에서만** 한다 — 서명 없이 되살리면
     * 이전 화면의 안내가 다른 화면 위에 그려진다(PR #63 2차 리뷰 P1, #61 P1과 같은 계급).
     */
    private var guideSignature: String? = null

    /** 정체(STUCK) 접미사를 붙여도 되는가. 상한 안내는 행동 지시가 아니라서 붙이지 않는다. */
    private var stuckSuffixAllowed = true

    /** 오버레이에 실제로 떠 있는가. root=null 청소가 지우면 내용은 남기고 이것만 내린다. */
    private var visible = false

    /**
     * 강조·손 모션만 내려간 상태인가(#225). 안내창 문구는 그대로 떠 있다.
     *
     * [visible]과 따로 두는 이유는 **되살리는 경로가 다르기 때문**이다. 오버레이가 통째로
     * 지워진 것은 [redisplay]가 회복하지만, 강조만 내린 것은 [visible]이 true라 그 경로에
     * 걸리지 않는다.
     */
    private var targetHidden = false

    /**
     * 지금 떠 있는 안내에서 눌림을 이미 받았는가(#238). [markVisible]과 [restoreTarget]이
     * 되돌린다 — 새 안내는 새 눌림을 받아야 하고, 같은 화면에서 강조가 되살아났다면 그 강조도
     * 답을 줘야 한다. 옛 안내의 자리로 두 번 답하는 것만 막는다.
     */
    private var pressConsumed = false

    /** [showQuitConfirm]이 밀어 둔 직전 안내. ×를 누르면 여기서 되돌아온다. */
    private var stashed: Stash? = null

    private data class Stash(val guide: Content?, val stuckSuffixAllowed: Boolean)

    /** 읽기 불가 안내가 잠시 덮은 화면 소속 안내. 원래 화면이 안정되면 되살린다. */
    private var unreadableStash: ScreenStash? = null

    private var unreadableNoticeActive = false

    private data class ScreenStash(
        val guide: Content,
        val signature: String?,
        val stuckSuffixAllowed: Boolean,
    )

    /** [signature] 화면에 서버 안내 하나를 표시한다. 그릴 내용을 돌려준다. */
    fun show(
        signature: String,
        bounds: List<Int>?,
        text: String,
        actionType: ActionType? = null,
        direction: SwipeDirection? = null,
        inputText: String? = null,
    ): Content {
        clearUnreadableState()
        val content = Content(
            bounds,
            text,
            actionType,
            direction,
            inputText = inputText?.takeIf { it.isNotBlank() && actionType == ActionType.TYPE },
        )
        guide = content
        guideSignature = signature
        stuckSuffixAllowed = true
        markVisible()
        return content
    }

    /**
     * [signature] 화면에 done 복구·상한 안내를 표시한다(#55, #424). STUCK 접미사를 붙이지 않는다.
     * 같은 화면에 같은 문구가 이미 떠 있으면 다시 그리지 않는다. 오버레이가 만든 접근성 이벤트가
     * 폴링 시계를 되감는 자기 루프를 막는 경계로, [showUnreadableNotice]와 같은 이유다(#380).
     */
    fun showCapNotice(signature: String, text: String): Content? {
        if (
            visible &&
            !stuckSuffixAllowed &&
            !unreadableNoticeActive &&
            guideSignature == signature &&
            guide?.text == text
        ) return null
        clearUnreadableState()
        val content = Content(null, text)
        guide = content
        guideSignature = signature
        stuckSuffixAllowed = false
        markVisible()
        return content
    }

    /**
     * [signature] 화면에 완료 재확인 대기를 표시한다(#480). 미검증 done을 다시 묻기까지의
     * 백오프 구간에 **창을 살려 두는 것이 목적**이다.
     *
     * **살리려는 것은 문구가 아니라 중지 칩이다.** 칩은 안내창과 한 창에 살고 그 창은
     * "보여줄 것이 없으면 걷는다"가 설계라([ControlOverlay]), 이 구간에 아무것도 안 그리면
     * 창이 통째로 걷혀 **멈출 수단이 사라진다**(2026-08-27 SM-S921N 실측 — 무표시 2.0초,
     * 프로브 실측으로는 4.77초). 목표를 이미 끝낸 사용자가 가장 오래, 아무 표시 없이 기다린다.
     *
     * **[showCapNotice]와 같은 dedupe 규약을 쓴다.** 백오프는 폴링마다 도는데 그때마다 같은
     * 내용을 다시 그리면 조작 오버레이가 자기 창을 만져 스스로를 깨운다(#153·#380). 오버레이가
     * 지워졌다면([onCleared]) `visible`이 false라 다시 그린다.
     *
     * STUCK 접미사를 붙이지 않는 이유는 상한 안내와 같다 — 화면이 그대로인 것은 우리가 다시
     * 묻는 동안 당연하고, "한 번 더 해 주세요"는 지금 시킬 행동이 아니다.
     */
    fun showWaitNotice(signature: String, text: String): Content? {
        if (
            visible &&
            !stuckSuffixAllowed &&
            !unreadableNoticeActive &&
            guideSignature == signature &&
            guide?.text == text
        ) return null
        clearUnreadableState()
        val content = Content(bounds = null, text = text, waiting = true)
        guide = content
        guideSignature = signature
        stuckSuffixAllowed = false
        markVisible()
        return content
    }

    /**
     * label 없는 상태가 오래 이어진 화면에 로컬 안내를 표시한다(#350). 서버 오류와 달리
     * 재시도 중인 요청은 없지만, 같은 문구를 매 폴링 다시 그리면 오버레이가 만든 이벤트로
     * 샘플러를 스스로 깨운다. 떠 있는 동안은 화면 시그니처가 흔들려도 한 번만 그린다.
     * root=null 청소 뒤에는 [visible]이 false라 다시 표시한다.
     *
     * 기존 안내는 [unreadableStash]에 잠시 보존한다. UNREADABLE은 화면 정체성을 확정한 상태가
     * 아니므로, 그 서명으로 기존 안내를 폐기하면 안 된다. label이 돌아온 안정 화면이 원래
     * 안내의 화면이면 [redisplay]가 복구하고, 정말 다른 안정 화면이면 그때 둘 다 폐기한다.
     */
    fun showUnreadableNotice(signature: String, text: String): Content? {
        if (unreadableNoticeActive && visible && guide?.text == text) return null
        if (!unreadableNoticeActive) {
            unreadableStash = guide?.let { ScreenStash(it, guideSignature, stuckSuffixAllowed) }
        }
        val content = Content(bounds = null, text = text)
        guide = content
        guideSignature = signature
        stuckSuffixAllowed = false
        unreadableNoticeActive = true
        markVisible()
        return content
    }

    /**
     * [signature] 화면에 안내 중 서버 오류를 표시한다(#29). 기존 안내를 **덮는다** — 오류가
     * 걷히면 다음 성공 응답의 [show]가 다시 덮으므로 되살릴 것을 따로 기억하지 않는다.
     *
     * STUCK 접미사를 붙이지 않는 이유는 상한 안내와 같다. 서버가 안 되어 화면이 그대로인
     * 것이지 사용자가 조작을 놓친 게 아니라서 "한 번 더 해 주세요"는 틀린 지시다.
     *
     * **같은 화면에 같은 오류가 이미 떠 있으면 null을 돌려준다.** 재시도는 백오프로 계속
     * 도는데 그때마다 같은 내용을 다시 그리면 조작 오버레이가 자기 창을 만져 스스로를
     * 깨운다(#153에서 정체 알림이 실제로 그랬다 — [ControlOverlay]의 `appliedX` 주석).
     * 오버레이가 지워졌다면([onCleared]) `visible`이 false라 다시 그린다.
     */
    fun showError(signature: String, text: String): Content? {
        if (
            !unreadableNoticeActive &&
            visible &&
            guideSignature == signature &&
            guide?.text == text
        ) {
            return null
        }
        clearUnreadableState()
        val content = Content(bounds = null, text = text)
        guide = content
        guideSignature = signature
        stuckSuffixAllowed = false
        markVisible()
        return content
    }

    /**
     * [signature] 화면에 완료 확인을 표시한다(GD-3, #139). 문구는 확신 있음/없음 두 갈래를
     * 호출자가 고른다 — 여기서는 확인쌍을 띄운다는 것만 안다.
     *
     * STUCK 접미사를 붙이지 않는 이유는 상한 안내와 같다. 화면이 안 바뀌는 것은 사용자가
     * 답을 고르는 동안 당연하고, "한 번 더 해 보세요"는 지금 시킬 행동이 아니다.
     *
     * **화면이 바뀌어도 살아남는다** — 재표시에서 서명 폐기 대상이 아니다([redisplay]).
     * 사용자가 어디로 가든 답할 자리가 따라가고, ○/×를 눌러야 사라진다.
     */
    fun showConfirm(signature: String, text: String): Content {
        clearUnreadableState()
        val content = Content(bounds = null, text = text, confirm = ConfirmKind.DONE)
        guide = content
        guideSignature = signature
        stuckSuffixAllowed = false
        markVisible()
        return content
    }

    /**
     * 종료 확인(GD-4, #193)을 띄운다. 중지 칩이 부른다.
     *
     * **직전 안내를 밀어 두고 덮는다.** ×("계속 볼래요")가 그 안내로 **돌아가야** 하기
     * 때문이다 — 완료 확인의 ×와 갈리는 지점이다(그쪽은 같은 질문 반복을 막으려 버린다).
     * 밀어 두지 않으면 확인 대기 동안 막혀 있던 요청이 풀리면서 **새 모델 호출**이 나간다.
     *
     * **[guideSignature]는 건드리지 않는다.** 확인 화면은 화면에 소속되지 않아 폐기 판정에
     * 걸리지 않으므로([redisplay]) 새 서명을 세울 이유가 없다. 밀어 둔 안내가 돌아올 때
     * 자기 화면 서명을 그대로 되찾는 편이 맞다.
     */
    fun showQuitConfirm(text: String): Content {
        // 중첩되지 않는다 — 이미 종료 확인 중이면 칩이 없어 다시 올 길이 없고, 그래도 온다면
        // 밀어 둔 것을 확인 화면으로 덮어써 되돌아갈 안내를 잃는다.
        if (guide?.confirm != ConfirmKind.QUIT) {
            stashed = Stash(guide, stuckSuffixAllowed)
        }
        val content = Content(bounds = null, text = text, confirm = ConfirmKind.QUIT)
        guide = content
        stuckSuffixAllowed = false
        markVisible()
        return content
    }

    /**
     * 종료 확인을 걷고 밀어 뒀던 안내를 되돌린다. 그릴 것이 남으면 돌려주고, 없으면 null이다
     * (호출자가 오버레이를 걷는다). null인 경우는 둘이다 — 칩을 누른 시점에 안내가 없었거나,
     * 아래의 화면 대조에서 폐기됐거나.
     *
     * **밀어 둔 안내가 [currentSignature] 화면의 것이 아니면 폐기한다.** 확인 화면 자신은 화면에
     * 소속되지 않지만([redisplay]) 되살아나는 것은 *조작* 안내라 그 규칙을 그대로 받는다 —
     * 칩 → 홈 → × 순으로 누르면 이전 화면의 강조 사각형이 런처 위에 그려지고, `visible`이
     * true라 [redisplay]가 걷지도 못한다("이전 화면의 안내를 새 화면 위에 그리지 않는다",
     * PR #63 2차 리뷰 P1). 폐기해도 그 화면의 새 요청은 다음 샘플이 어차피 낸다.
     */
    fun dismissQuitConfirm(currentSignature: String?): Content? {
        val restored = stashed ?: return null
        stashed = null
        if (restored.guide != null && guideSignature != currentSignature) {
            reset()
            return null
        }
        guide = restored.guide
        stuckSuffixAllowed = restored.stuckSuffixAllowed
        markVisible(restored.guide != null)
        return restored.guide
    }

    /**
     * 표시 여부를 바꿀 때 [targetHidden]도 함께 되돌린다. 강조를 숨긴 상태는 **지금 떠 있는
     * 그 안내**에만 매인다 — 안내가 새로 뜨거나 통째로 걷히면 숨김도 같이 풀려야 한다.
     * 두 필드가 갈라지면 강조가 영영 안 돌아오는 화면이 생긴다.
     */
    private fun markVisible(on: Boolean = true) {
        visible = on
        targetHidden = false
        pressConsumed = false
    }

    /**
     * 「바로 입력」이 채울 값과 그 값을 넣을 칸의 자리(#405). 값이 없으면 null.
     *
     * **서비스가 미러를 들지 않는다**(PR #407 리뷰 P1). 표시 깔때기에는 `input_text`가 빈
     * Content가 섞여 들어온다 — 종료 확인([showQuitConfirm])과 읽기 불가 안내가 그렇다. 그걸
     * 받아 적으면 **되살아난 안내에서 자리가 죽는다**: ✕ → 「계속 볼래요」로 돌아온 안내는
     * 버튼이 함께 돌아오는데 자리만 비어 입력 포커스 폴백으로 새고, 그 폴백이 안내한 칸이
     * 아닌 곳을 채울 수 있는 경로다(실기기 재현: `via=bounds` → `via=focus`).
     *
     * [guide]는 그 왕복에 안 죽는다 — [dismissQuitConfirm]이 원본을 그대로 되살리고,
     * [hideTarget]은 복사본만 지운다. [pressTargetBounds]가 같은 성질에 기대는 선례다.
     *
     * **[pressTargetBounds]와 달리 `visible`을 보지 않는다.** 저쪽은 시스템이 아무 때나 주는
     * 클릭 이벤트에서 불려 "지금 우리 안내가 떠 있는가"를 스스로 물어야 하지만, 이쪽은
     * **우리 버튼의 클릭 리스너에서만** 불린다 — 그 버튼은 조작 오버레이가 떠 있는 동안만
     * 존재하므로 창이 걷힌 뒤에는 호출 자체가 없다.
     */
    fun inputFill(): Pair<String, List<Int>?>? {
        val current = guide ?: return null
        val text = current.inputText ?: return null
        return text to current.bounds?.takeIf { it.size == 4 }
    }

    /**
     * 눌림을 받을 대상의 자리(#238). 눌림을 맞출 때와 파동을 그릴 때 같은 값을 쓴다.
     *
     * **탭 계열 안내에서만 돌려준다.** `scroll`도 컨테이너를 target으로 잡아 bounds가 있는데,
     * 그 안 아무 항목이나 눌러도 중심 포함이라 맞아 버린다 — 스크롤하라고 했는데 항목을 잘못
     * 누른 사용자에게 파동이 "잘 됐다"고 답하고, [hideTarget]이 손 시연까지 함께 내려 무엇을
     * 해야 하는지가 화면에서 사라진다(PR #264 리뷰 1).
     *
     * **[targetHidden]을 보지 않는다.** 화면 전환이 빠른 앱에서는 폴링 샘플의 WAIT이 클릭
     * 이벤트보다 먼저 들어와 강조가 이미 내려간다 — 그걸 조건으로 걸었더니 눌림을 통째로
     * 놓쳤다(2026-08-19 SM-S921N 실측). 파동은 "방금 누른 곳"이라 강조가 내려간 뒤에도 유효하다.
     *
     * 판정 자체는 [PressMatch]가 한다 — 이 클래스는 무엇이 떠 있는지만 안다.
     */
    fun pressTargetBounds(): List<Int>? {
        if (!visible || pressConsumed) return null
        val current = guide ?: return null
        if (current.actionType != ActionType.TAP && current.actionType != ActionType.TYPE) return null
        return current.bounds?.takeIf { it.size == 4 }
    }

    /**
     * 눌림을 하나 받았다고 표시한다(#238). **안내 하나당 한 번만** 받는다.
     *
     * 화면이 바뀌어도 `visible`은 안 내려간다 — [redisplay]의 서명 폐기가 `visible` 검사 뒤라,
     * 새 응답의 [show]가 덮을 때까지 [guide]는 이전 화면의 자리를 들고 있다. 그 사이(SETTLE +
     * 서버 왕복)에 사용자가 **같은 자리를 한 번 더** 누르면 아무것도 없는 곳에 파동이 뜬다
     * (PR #264 리뷰 2). 안내가 새로 뜨면 [markVisible]이 풀어 준다.
     */
    fun consumePress() {
        pressConsumed = true
    }

    /**
     * 화면이 흔들리는 동안(WAIT) **강조와 손 모션만** 내린다(#225). 그릴 내용을 돌려주고,
     * 내릴 것이 없거나 이미 내렸으면 null이다 — 샘플마다 같은 내용을 다시 그리면 조작
     * 오버레이가 자기 창을 만져 스스로를 깨운다([showError]의 같은 주의).
     *
     * **문구는 남긴다.** 화면이 바뀌면 틀리는 것은 강조의 *자리*지 문장이 아니고, 잠깐
     * 흔들렸을 뿐인 화면에서 안내가 통째로 깜빡이면 읽던 사람이 놓친다.
     *
     * 확인 화면은 가리킬 대상이 없어([Content.bounds]가 null) 여기서 할 일이 없다.
     *
     * 가장자리 제스처(#222)는 bounds·direction 없이 [Content.actionType]만으로 강조 선·손을
     * 그리므로, 내릴 것이 있는지의 판정에 그 표([EdgeGesture.specFor])도 함께 본다. 억제는
     * [Content.targetSuppressed]로 명시한다 — bounds만 지우면 그리는 쪽이 actionType에서
     * 다시 세운다(PR #343 리뷰 P1).
     */
    fun hideTarget(): Content? {
        val current = guide ?: return null
        if (!visible || targetHidden) return null
        if (current.bounds == null && current.direction == null &&
            EdgeGesture.specFor(current.actionType) == null
        ) {
            return null
        }
        targetHidden = true
        return current.copy(bounds = null, direction = null, targetSuppressed = true)
    }

    /**
     * 화면이 안정된 뒤 [hideTarget]이 내렸던 강조를 되돌린다. **같은 화면일 때만이다** —
     * 다른 화면으로 갔으면 그 화면의 새 안내가 나오므로 되살릴 이유가 없고, 되살리면
     * [redisplay]가 막는 것과 같은 사고("이전 화면의 강조를 새 화면 위에")가 난다.
     *
     * 다른 화면이면 숨김을 **푼 것으로 치지 않는다.** 새 안내가 오면 [markVisible]이 되돌리고,
     * 그때까지는 문구만 남는 편이 없는 대상을 가리키는 것보다 낫다.
     */
    fun restoreTarget(currentSignature: String): Content? {
        if (!targetHidden) return null
        val current = guide ?: return null
        if (guideSignature != currentSignature) return null
        targetHidden = false
        // **눌림도 함께 재무장한다**(PR #264 재리뷰 1). 눌렀는데 화면이 안 바뀌어 강조가 되살아난
        // 경우, 다시 눌러도 파동이 없고 내려가지도 않으면 "제대로 눌렀는지 모른 채 다시 누르는"
        // 바로 그 사용자가 아무 답을 못 받는다. 다른 화면에서는 위 서명 대조가 이미 막으므로
        // 여기서 푸는 것은 "같은 화면·같은 안내로 강조가 돌아왔다"일 때뿐이다.
        pressConsumed = false
        return current
    }

    /** 오버레이가 지워졌다(우리 앱이 앞에 오는 등 root=null 청소). 내용은 기억해 둔다. */
    fun onCleared() {
        markVisible(false)
    }

    /** 세션이 끝나거나 바뀌었다. 이전 세션의 안내를 재표시하면 안 된다. */
    fun reset() {
        guide = null
        guideSignature = null
        stuckSuffixAllowed = true
        clearUnreadableState()
        markVisible(false)
        // 밀어 둔 안내도 세션에 매인다. 남기면 다음 세션의 종료 확인에서 ×를 눌렀을 때
        // 이전 세션의 안내가 되살아난다.
        stashed = null
    }

    /**
     * [currentSignature] 화면에서 다시 그려야 할 내용. 지워진 안내가 **같은 화면의 것이면**
     * 돌려주고 "떠 있음"으로 되돌린다 — 상한 안내가 앱 전환 한 번에 영영 사라지는 구멍의
     * 회복 경로다(PR #63 리뷰 P1). **다른 화면이면 폐기한다** — 되살리면 이전 화면의 안내가
     * 새 화면 위에 그려지고, 그 화면의 새 요청은 어차피 나간다(2차 리뷰 P1).
     *
     * **그 "어차피 나간다"가 항상 참은 아니다**(#456) — 회전으로 접힌 화면에서는 재질의가
     * 나가지 않는다. 그래서 폐기를 [RedisplayResult.Discarded]로 알리고, 호출자가 걸쇠를
     * 풀어 다시 참으로 만든다. 이 파일에서 판정을 바꾸지 않는 이유는 폐기 규칙 자체는
     * 옳아서다 — 고쳐야 할 것은 그 뒤에 요청이 나가는가다.
     *
     * **확인 화면은 그 폐기에서 빠진다**(GD-3·GD-4 둘 다). 폐기 규칙이 막으려는 것은 *조작*
     * 안내가 엉뚱한 화면을 가리키는 것인데, 확인 질문에는 가리킬 대상이 없고([Content.bounds]가
     * null) 문구도 화면과 무관하다. 화면에 묶으면 사용자가 알림창을 잠깐 내리거나
     * 홈을 누른 것만으로 답할 자리가 사라지고, 대기 중에는 요청도 안 나가 세션이 갇힌다
     * (2026-08-16 SM-S921N 실측: 셰이드 왕복 한 번에 확인이 버려지고 셰이드 화면에 대고 요청이
     * 2회 나갔다 — PR #153 리뷰 P1). **답하기 전에는 사라지지 않는 것이 이 화면의 계약이다.**
     *
     * [screenStable]이 false(추적기 판정 WAIT·UNREADABLE)면 **아무것도 하지 않는다** — 전환 중
     * 덜 그려졌거나 label 없는 트리의 서명은 화면의 정체가 아니라서, 그걸 근거로 폐기하면 안정된
     * 원래 화면이 돌아왔을 때 되살릴 안내가 이미 없다(4차 리뷰 P1, PR #352 재리뷰).
     */
    fun redisplay(currentSignature: String, screenStable: Boolean): RedisplayResult {
        if (!screenStable) return RedisplayResult.Unchanged
        // 읽기 불가 안내에서 중지 확인으로 들어갈 수 있다. 그때 아래 unreadable 복구가 먼저
        // 돌면 화면 변화만으로 확인창을 걷어 버린다. 확인은 답하기 전까지 화면을 따라다닌다.
        guide?.takeIf { it.confirm != null }?.let { confirming ->
            if (visible) return RedisplayResult.Unchanged
            markVisible()
            return RedisplayResult.Show(confirming)
        }
        if (unreadableNoticeActive) {
            val preserved = unreadableStash
            clearUnreadableState()
            if (preserved != null && preserved.signature == currentSignature) {
                guide = preserved.guide
                guideSignature = preserved.signature
                stuckSuffixAllowed = preserved.stuckSuffixAllowed
                markVisible()
                return RedisplayResult.Show(preserved.guide)
            }
            reset()
            // 읽기 불가 안내는 visible=true인 채 실제 오버레이에 떠 있었다. 다른 안정 화면이면
            // 보존 상태뿐 아니라 그 문구도 함께 걷어야 한다(PR #352 재리뷰).
            return RedisplayResult.Clear
        }
        val current = guide ?: return RedisplayResult.Unchanged
        if (visible) return RedisplayResult.Unchanged
        if (current.confirm == null && guideSignature != currentSignature) {
            reset()
            return RedisplayResult.Discarded
        }
        markVisible()
        return RedisplayResult.Show(current)
    }

    private fun clearUnreadableState() {
        unreadableStash = null
        unreadableNoticeActive = false
    }

    /**
     * 정체(STUCK)를 알릴 내용. 일반 안내에는 [suffix]를 덧붙이고, 상한 안내는 원문 그대로
     * 유지한다 — 덮어쓰지도, 잃지도 않는다.
     *
     * 덧붙인 문구는 [guide]에 저장하고, 강조는 [currentSignature]가 안내의 화면과 같을 때만
     * 돌려준다. 둘 다 이 클래스의 다른 경로가 세운 규칙을 여기서도 지키려는 것이다.
     */
    fun stuckContent(suffix: String, currentSignature: String): Content? {
        val current = guide ?: return null
        val text = if (stuckSuffixAllowed) "${current.text}\n$suffix" else current.text
        val updated = current.copy(text = text)
        // **접미사를 상태에 남긴다.** 반환만 하면 뒤이은 [hideTarget]·[restoreTarget]이 접미사 없는
        // 원본을 그려 정체 문구가 사라지고, `stuckReported`가 true라 다시 붙지도 않는다(#226 리뷰).
        guide = updated
        stuckSuffixAllowed = false
        visible = true
        if (guideSignature == currentSignature) {
            targetHidden = false
            return updated
        }
        // 다른 화면이면 [restoreTarget]과 같은 규칙을 따른다 — 문구만 남기고 강조는 그리지 않는다.
        // 여기서 숨김을 풀면 restoreTarget이 막은 "이전 화면의 강조를 새 화면 위에"가 되살아난다.
        // 억제는 [Content.targetSuppressed]로 명시한다 — bounds·direction만 지우면 가장자리
        // 제스처의 선·손이 actionType에서 되살아나 그 사고가 이 경로로 돌아온다(PR #343 리뷰 P1).
        return updated.copy(bounds = null, direction = null, targetSuppressed = true)
    }
}
