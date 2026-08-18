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
     * 오버레이에 그릴 내용. [bounds]는 `[left, top, right, bottom]`, 강조가 없으면 null.
     *
     * [actionType]은 안내창이 무엇을 피해야 하는지를 가른다 — 타이핑 안내면 키보드가 곧
     * 조작 대상이라 절대 가리면 안 된다(#38 5번). 우리가 만든 안내(정체·상한)는 null이다.
     *
     * [confirm]이면 안내창 컨트롤 행이 X칩 대신 확인쌍이 된다(GD-3). 무엇을 그릴지의 판정이라
     * 여기 있고, 어떻게 그릴지는 오버레이가 정한다.
     */
    data class Content(
        val bounds: List<Int>?,
        val text: String,
        val actionType: ActionType? = null,
        val confirm: Boolean = false,
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

    /** [signature] 화면에 서버 안내 하나를 표시한다. 그릴 내용을 돌려준다. */
    fun show(
        signature: String,
        bounds: List<Int>?,
        text: String,
        actionType: ActionType? = null,
    ): Content {
        val content = Content(bounds, text, actionType)
        guide = content
        guideSignature = signature
        stuckSuffixAllowed = true
        visible = true
        return content
    }

    /** [signature] 화면에 미검증 done 상한 안내를 표시한다(#55). STUCK 접미사를 붙이지 않는다. */
    fun showCapNotice(signature: String, text: String): Content {
        val content = Content(null, text)
        guide = content
        guideSignature = signature
        stuckSuffixAllowed = false
        visible = true
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
        visible = true
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
        val content = Content(bounds = null, text = text, confirm = true)
        guide = content
        guideSignature = signature
        stuckSuffixAllowed = false
        visible = true
        return content
    }

    /** 오버레이가 지워졌다(우리 앱이 앞에 오는 등 root=null 청소). 내용은 기억해 둔다. */
    fun onCleared() {
        visible = false
    }

    /** 세션이 끝나거나 바뀌었다. 이전 세션의 안내를 재표시하면 안 된다. */
    fun reset() {
        guide = null
        guideSignature = null
        stuckSuffixAllowed = true
        visible = false
    }

    /**
     * [currentSignature] 화면에서 다시 그려야 할 내용. 지워진 안내가 **같은 화면의 것이면**
     * 돌려주고 "떠 있음"으로 되돌린다 — 상한 안내가 앱 전환 한 번에 영영 사라지는 구멍의
     * 회복 경로다(PR #63 리뷰 P1). **다른 화면이면 폐기한다** — 되살리면 이전 화면의 안내가
     * 새 화면 위에 그려지고, 그 화면의 새 요청은 어차피 나간다(2차 리뷰 P1).
     *
     * **확인 화면(GD-3)은 그 폐기에서 빠진다.** 폐기 규칙이 막으려는 것은 *조작* 안내가 엉뚱한
     * 화면을 가리키는 것인데, 확인 질문에는 가리킬 대상이 없고([Content.bounds]가 null) 문구도
     * 화면과 무관하다("다 됐어요? 해결되셨나요?"). 화면에 묶으면 사용자가 알림창을 잠깐 내리거나
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
        if (!current.confirm && guideSignature != currentSignature) {
            reset()
            return null
        }
        visible = true
        return current
    }

    /**
     * 정체(STUCK)를 알릴 내용. 일반 안내에는 [suffix]를 덧붙이고, 상한 안내는 원문 그대로
     * 유지한다 — 덮어쓰지도, 잃지도 않는다.
     */
    fun stuckContent(suffix: String): Content? {
        val current = guide ?: return null
        visible = true
        return if (stuckSuffixAllowed) {
            Content(current.bounds, "${current.text}\n$suffix", current.actionType)
        } else {
            current
        }
    }
}
