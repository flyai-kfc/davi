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
     */
    data class Content(
        val bounds: List<Int>?,
        val text: String,
        val actionType: ActionType? = null,
        val direction: SwipeDirection? = null,
        val confirm: ConfirmKind? = null,
    )

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
     * 지금 떠 있는 안내에서 눌림을 이미 받았는가(#238). [markVisible]이 함께 되돌린다 —
     * 새 안내는 새 눌림을 받아야 하고, 옛 안내의 자리로 두 번 답하면 안 된다.
     */
    private var pressConsumed = false

    /** [showQuitConfirm]이 밀어 둔 직전 안내. ×를 누르면 여기서 되돌아온다. */
    private var stashed: Stash? = null

    private data class Stash(val guide: Content?, val stuckSuffixAllowed: Boolean)

    /** [signature] 화면에 서버 안내 하나를 표시한다. 그릴 내용을 돌려준다. */
    fun show(
        signature: String,
        bounds: List<Int>?,
        text: String,
        actionType: ActionType? = null,
        direction: SwipeDirection? = null,
    ): Content {
        val content = Content(bounds, text, actionType, direction)
        guide = content
        guideSignature = signature
        stuckSuffixAllowed = true
        markVisible()
        return content
    }

    /** [signature] 화면에 미검증 done 상한 안내를 표시한다(#55). STUCK 접미사를 붙이지 않는다. */
    fun showCapNotice(signature: String, text: String): Content {
        val content = Content(null, text)
        guide = content
        guideSignature = signature
        stuckSuffixAllowed = false
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
        if (visible && guideSignature == signature && guide?.text == text) return null
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
     */
    fun hideTarget(): Content? {
        val current = guide ?: return null
        if (!visible || targetHidden) return null
        if (current.bounds == null && current.direction == null) return null
        targetHidden = true
        return current.copy(bounds = null, direction = null)
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
     * **확인 화면은 그 폐기에서 빠진다**(GD-3·GD-4 둘 다). 폐기 규칙이 막으려는 것은 *조작*
     * 안내가 엉뚱한 화면을 가리키는 것인데, 확인 질문에는 가리킬 대상이 없고([Content.bounds]가
     * null) 문구도 화면과 무관하다. 화면에 묶으면 사용자가 알림창을 잠깐 내리거나
     * 홈을 누른 것만으로 답할 자리가 사라지고, 대기 중에는 요청도 안 나가 세션이 갇힌다
     * (2026-08-16 SM-S921N 실측: 셰이드 왕복 한 번에 확인이 버려지고 셰이드 화면에 대고 요청이
     * 2회 나갔다 — PR #153 리뷰 P1). **답하기 전에는 사라지지 않는 것이 이 화면의 계약이다.**
     *
     * [screenStable]이 false(추적기 판정 WAIT)면 **아무것도 하지 않는다** — 전환 중 덜 그려진
     * 과도기 트리(#32 실측)의 서명은 화면의 정체가 아니라서, 그걸 근거로 폐기하면 안정된
     * 원래 화면이 돌아왔을 때 되살릴 안내가 이미 없다(4차 리뷰 P1).
     */
    fun redisplay(currentSignature: String, screenStable: Boolean): Content? {
        if (!screenStable) return null
        val current = guide ?: return null
        if (visible) return null
        if (current.confirm == null && guideSignature != currentSignature) {
            reset()
            return null
        }
        markVisible()
        return current
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
        return updated.copy(bounds = null, direction = null)
    }
}
