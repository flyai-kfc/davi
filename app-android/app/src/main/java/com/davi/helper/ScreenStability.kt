package com.davi.helper

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * 화면이 "진짜" 바뀌었는지를 시간 축에서 판정한다.
 *
 * #32(덜 그려진 화면으로 요청이 나감)와 #39(이벤트가 안 와서 안내가 멈춤)는 **같은 기계의 양쪽 끝**이다.
 * 둘 다 필요한 부품이 같아서 한자리에 둔다 — 시그니처, 홀드, 정체 판정.
 *
 * 안드로이드 API에 기대지 않아 JVM 단위 테스트로 검증한다. 시각은 호출자가 넣는다.
 */

/**
 * 화면 시그니처의 두 축(#380).
 *
 * 광고 배너 로테이션도 "진짜" 화면 변화라, 내용 전체 동일성 하나로는 의미 있는 변화와 광고를
 * 원리적으로 구분할 수 없다 — 둘 다 "글자가 바뀜"으로 똑같이 보인다. 그래서 축을 나눈다.
 *
 * - [structure] 뼈대: package·페이지·IME·요소 수·순서·role·행동 상태. label은 뺀다.
 *   화면 이동·팝업 등장·토글처럼 **지금 안내를 무효화하는 변화**는 전부 이 축에 나타난다.
 *   안내의 화면 소속(`GuideDisplayState`)·늦은 응답 폐기·스텝 `screen_changed`도 이 축으로
 *   판정한다 — 내용까지 대조하면 배너가 도는 사이 도착한 정상 응답을 버린다.
 * - [content] 내용: 요소 label 열(+창 제목). 구조가 그대로인 채 여기만 바뀌는 변화는
 *   광고·marquee 같은 자기발생이거나(무시), 균일 목록 스크롤처럼 사용자가 일으킨 것이다 —
 *   그 구분은 내용이 아니라 **인과**(조작 이벤트 관측)로 한다. [ScreenStabilityTracker] 참고.
 */
data class ScreenSig(val structure: String, val content: String)

object ScreenSignature {

    /**
     * 요소의 **좌표와 node id는 넣지 않는다.** 전환 애니메이션 중 좌표가 미세하게 흔들리고,
     * node id는 직렬화 순서에서 생기는 값이라 화면의 정체성이 아니다.
     */
    fun of(screen: Screen): ScreenSig {
        val structure = buildString {
            append(screen.packageName).append('|')
            append(screen.currentPage ?: "-").append('/').append(screen.totalPages ?: "-").append('|')
            append(screen.hasScrollableElement ?: "-").append('|')
            append(screen.imeVisible ?: "-").append('|')
            screen.elements.forEach { element ->
                append(if (element.clickable) 't' else '-')
                append(if (element.editable) 'e' else '-')
                append(if (element.focused) 'f' else '-')
                // 행동 상태 넷. **이게 없으면 스위치를 켜도 같은 화면으로 읽는다**(#271) —
                // 토글 상태는 label과 별개이므로, 이 자리가 유일한 변화 신호다.
                append(state(element.enabled, on = 'a', off = 'x'))
                append(checkState(element.checkState))
                append(state(element.selected, on = 's', off = 'n'))
                append(state(element.showingHintText, on = 'h', off = 'v'))
                // 모달이 뜨고 걷히면 같은 요소의 가림 여부가 뒤집힌다. 보통은 요소 수도 함께
                // 변해 서명이 어차피 바뀌지만, 투명 오버레이가 내용을 얻는 경우처럼 구조가
                // 그대로인 전환도 있어 상태로 남긴다(#271과 같은 결).
                append(state(element.obscured, on = 'o', off = 'p'))
                append(element.role)
                append(';')
            }
        }
        val content = buildString {
            // 제목은 label과 같은 취급이다. 일반 label과 제한 선택값은 **숫자를 포함한
            // 원문 그대로** 넣되(#427), 자유 editable 값은 serializer가 만든 고정 투영을 쓴다
            // (#462). 서버 서명은 label처럼 제목도 뺀다(#288 리뷰).
            // label과 같은 취급이므로 축도 내용이다(#380).
            //
            // **이 축의 salt 해시는 기기를 떠난다**(#410). 원문이 아니라 해시이고 salt가
            // 세션마다 새로 생겨 세션 밖 대조가 성립하지 않으므로 지금은 안전하다. 다만
            // "내용 축은 어차피 기기 안"으로 읽고 여기에 무언가를 더 넣으면 그때부터 그
            // 값이 해시로 나간다 — 넣기 전에 그것이 나가도 되는 값인지 본다.
            append(screen.windowTitle ?: "-").append('|')
            screen.elements.forEach { element ->
                append(element.signatureLabel ?: element.label)
                append(';')
            }
        }
        return ScreenSig(structure, content)
    }

    /**
     * 안내 대상 슬롯의 label 대조(타깃 앵커, #380)용. **내용 축과 달리 숫자를 지운다**(#427).
     * 숫자 정규화 생산자 규칙은 [UiTreeSerializer.normalizeTargetAnchorLabel] 한 곳에 둔다.
     *
     * 두 자리가 서로 다른 질문을 한다. 내용 축은 "화면 상태가 바뀌었나"라서 장바구니 수량
     * `2`→`3`을 봐야 하고, 앵커는 "강조가 아직 **같은 줄**을 가리키나"라서 그 줄에 붙은
     * 시계·개수가 도는 것을 다른 줄로 읽으면 안 된다.
     *
     * #427이 내용 축의 정규화를 걷을 때 이 자리도 함께 걷었더니 `안 읽음 12`→`안 읽음 13`
     * 한 번에 앵커가 **영구히** 어긋났다. 앵커 값은 응답 시점에 얼리므로 한 번 달라지면 계속
     * 다르고, 호출부는 그때 강조를 내린 뒤(`target slot moved`) 되돌리는 경로가 `else`
     * 가지에만 있어 **다음 안내까지 강조가 없다**(PR #445 리뷰).
     *
     * 내용 축에는 인과 게이트라는 방벽이 있지만 **앵커는 게이트를 우회한다** — 여기서는
     * 정규화가 아직 유일한 방벽이라 남긴다.
     *
     * **제한 선택값은 예외로 남긴다**(#117, #462). 그 값은 줄에 붙은
     * 장식이 아니라 **선택 상태 자체**라, 지우면 분 picker를 `00`→`30`으로 돌려도 앵커가 안
     * 움직인다 — 그런데 그 화면에서는 앵커가 **유일하게 열리는 문**이다. 시계 앱은 진입 탭이
     * 이미 클릭을 쏴 조용함 문이 닫혀 있고, picker 스크롤은 CLICKED가 아니라(#380이 SCROLLED를
     * 뺐다) 인과 게이트도 안 열린다. 「안 읽음 12→13」과는 serializer가 자유 입력과 다른
     * [UiElement.signatureLabel] 투영으로 가른다. 병합 행은 [UiElement.targetAnchorLabel]에서
     * 제한 선택 파트만 보존해 행의 다른 숫자까지 예외로 만들지 않는다. wire에서
     * 역직렬화했거나 테스트가 직접 만든 옛 요소에는 그 메타가 없으므로, 짧은 숫자 EditText의
     * 기존 예외도 호환 경계로 유지한다.
     */
    internal fun stableLabelOf(element: UiElement): String {
        element.targetAnchorLabel?.let { return it }
        val projected = element.signatureLabel ?: element.label
        return if (
            element.signatureTracksEditableValue ||
            (
                element.signatureLabel == null &&
                    element.editable &&
                    element.role.endsWith("EditText") &&
                    UiTreeSerializer.isShortNumericControlLabel(projected)
                )
        ) {
            projected
        } else {
            UiTreeSerializer.normalizeTargetAnchorLabel(projected)
        }
    }

    /** 미관측(`null`)과 `false`를 다른 글자로 남긴다. 둘을 같은 글자로 접으면 세 상태가 죽는다. */
    private fun state(value: Boolean?, on: Char, off: Char): Char = when (value) {
        true -> on
        false -> off
        null -> '-'
    }

    /** 체크 네 상태를 모두 다른 글자로 남긴다. `partial`을 꺼짐이나 미관측에 접지 않는다. */
    private fun checkState(value: CheckState): Char = when (value) {
        CheckState.CHECKED -> 'c'
        CheckState.UNCHECKED -> 'u'
        CheckState.PARTIAL -> 'p'
        CheckState.UNAVAILABLE -> '-'
    }
}

/**
 * 내용 축([ScreenSig.content])을 세션 salt로 해시한다(#410).
 *
 * 구조 축만으로는 서버가 두 경우를 못 가른다 — **구조는 같고 내용만 바뀐 화면**(다른 상품
 * 상세, 다른 대화방)과 **A→B→A 회귀**. 기기 시그니처는 이웃 샘플과 비교한 뒤 버려지므로 그
 * 역할을 못 한다. 서버가 이력 간 비교로 판정하려면 세션 안에서 비교되는 값이 이력에 남아야 한다.
 *
 * **원문은 나가지 않는다.** 나가는 것은 해시뿐이고, salt는 세션 generation마다 새로 만들어
 * 프로세스 밖에 저장하지 않는다(#81과 같은 결) — 세션 안 동일성 비교는 되고 장기 역추정
 * 가치는 없다. salt를 쥐는 것은 [GuideSession]이다.
 *
 * 입력은 [ScreenSignature]가 만든 내용 축 **그대로**다. 여기서 다시 정규화하면 기기와 서버가
 * 같은 구분을 다르게 정의하게 된다 — 이 값이 #380을 기다린 이유가 그것이다.
 */
object ContentSignature {

    /**
     * salt 길이. 세션 하나를 살고 버리는 값이라 `SecureRandom` 16바이트면 충분하다 —
     * 세션 안 충돌도, 흔한 label 문자열의 사전 대입도 실질적으로 막힌다.
     */
    private const val SALT_BYTES = 16

    fun newSalt(): ByteArray = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }

    /**
     * `sha256(salt ‖ content)`의 hex 64자.
     *
     * 형식을 서버 `structure_signature`(`^[0-9a-f]{64}$`)와 맞춘다 — 같은 레코드에 나란히 서는
     * 두 값이라 한쪽만 형식이 다르면 스키마와 읽는 눈이 갈린다.
     *
     * [salt]는 [newSalt]가 만든 **고정 길이**여야 한다. 길이가 섞이면 `salt ‖ content`의 경계가
     * 모호해져 서로 다른 쌍이 같은 입력이 된다.
     */
    fun of(salt: ByteArray, content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        digest.update(content.toByteArray(Charsets.UTF_8))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/**
 * 서버에 안내를 물을 만큼 화면 내용이 준비됐는지 판정한다.
 *
 * 창 제목은 요소 label이 아니다. 앱 전환 직후 제목·package만 잡히고 접근성 트리는 아직 비어 있는
 * 스플래시가 실재하므로, 실제 요소 label이 하나라도 있어야 준비된 화면으로 본다(#350).
 */
internal fun Screen.hasLabeledElement(): Boolean = elements.any { it.label.isNotBlank() }

/**
 * 안내가 가리키는 슬롯의 label이 응답 시점과 달라졌는가(타깃 앵커, #380).
 *
 * node id는 직렬화 순서에서 나온 값이라 **구조가 같은 동안만** 같은 슬롯을 뜻한다. 쓰는 자리도
 * 정확히 그 경우뿐이다 — 구조가 다르면 구조 축이 이미 재질의를 열고 강조도 새로 그려진다.
 *
 * 대조는 [ScreenSignature.stableLabelOf]로 한다 — **내용 축과 달리 숫자를 지운 값**이다
 * (#427). 원문 label로 비교하면 대상에 붙은 시계·개수가 앵커를 매 초 흔들어, 강조가 내려간
 * 채 다음 안내까지 돌아오지 않는다.
 *
 * 슬롯이 사라진 경우는 `false`다 — 그건 구조 변화라 구조 축이 맡는다.
 *
 * [capturedStructure]는 앵커를 잡은 화면의 구조 축이다. 지금 화면의 [currentStructure]와
 * 다르면 **앵커를 아예 보지 않는다** — 다른 화면에서 우연히 같은 node id가 다른 label을 갖는
 * 것만으로 참이 되면, 구조 축이 이미 맡은 자리를 앵커가 겹쳐 판정한다.
 */
internal fun Screen.guidedSlotLabelChanged(
    nodeId: String?,
    expectedStableLabel: String?,
    capturedStructure: String?,
    currentStructure: String,
): Boolean {
    if (nodeId == null || expectedStableLabel == null) return false
    if (capturedStructure != currentStructure) return false
    val element = elements.firstOrNull { it.nodeId == nodeId } ?: return false
    return ScreenSignature.stableLabelOf(element) != expectedStableLabel
}

/**
 * 샘플 하나가 들어올 때 무엇을 할지 정한다.
 *
 * - [Decision.WAIT] 아직 흔들린다. 요청을 보내지 않는다(#32).
 * - [Decision.SEND] 안정됐고 직전에 보낸 화면과 다르다. 서버에 물어본다.
 * - [Decision.STUCK] 안내를 했는데 화면이 그대로다. **서버를 다시 부르지 않고** 사용자에게 알린다(#39).
 * - [Decision.UNREADABLE] label 없는 상태가 오래 이어졌다. 서버 대신 로컬 안내를 표시한다(#350).
 * - [Decision.IDLE] 안정됐고 이미 처리한 화면이다. 아무것도 하지 않는다.
 *
 * "직전에 보낸 화면과 다르다"의 판정이 2계층이다(#380) — **구조가 바뀌면 무조건** 새 화면이고,
 * **내용만 바뀌면 인과가 있어야** 새 화면이다(최근 조작 이벤트 또는 안내 대상 슬롯의 변화).
 * 광고 배너처럼 스스로 도는 내용 변화는 어느 쪽도 아니라서 같은 화면으로 접힌다 — 그래야
 * 로테이션마다 재질의가 나가던 무한 호출이 끊긴다.
 *
 * 상태는 메인 스레드에서만 만진다. 응답이 다른 스레드로 오므로 [markFailed]는 반드시 메인으로 넘겨 부른다.
 */
class ScreenStabilityTracker(
    private val holdSamples: Int = HOLD_SAMPLES,
    private val stuckAfterMs: Long = STUCK_AFTER_MS,
    private val unreadableAfterMs: Long = UNREADABLE_AFTER_MS,
    private val contentSettleMs: Long = CONTENT_SETTLE_MS,
    private val interactionWindowMs: Long = INTERACTION_WINDOW_MS,
    private val quietMs: Long = QUIET_MS,
    private val quietActivityMs: Long = QUIET_ACTIVITY_MS,
) {

    enum class Decision { WAIT, SEND, STUCK, UNREADABLE, IDLE }

    private var lastSample: ScreenSig? = null
    private var heldSamples = 0
    private var structureStableSinceMs: Long? = null
    private var sentSignature: ScreenSig? = null

    /** 직전 샘플의 내용 축. 내용이 "방금" 바뀌었는지 보려고 든다(#427). */
    private var lastContent: String? = null

    /**
     * 내용 축이 마지막으로 바뀐 시각. 화면이 얼마나 조용했는지의 척도다.
     *
     * **샘플 수가 아니라 시간이다**(PR #445 리뷰). 폴링 주기는 `POLL_MS`에 처리 시간을 더한
     * 값이라 앱마다 다르고, 하필 이 문을 쓰는 앱(캐시를 끄는 앱)이 가장 느리다 — 샘플 수로
     * 재면 같은 상수가 어떤 앱에서는 4초, 어떤 앱에서는 7.5초가 되어 [STUCK_AFTER_MS]를 넘긴다.
     */
    private var contentChangedAtMs: Long? = null

    /**
     * **조용하던 화면이 방금 움직였다**(#427 2단계). 클릭 이벤트를 안 쏘는 앱에서 인과를
     * 대신하는 표시다 — 자기발생 변화는 조용할 틈이 없다는 실측에 근거한다(아래 [QUIET_MS]).
     * 내용이 바뀔 때마다 다시 판정한다. **요청이 나가도 소진하지 않는다**(PR #445 리뷰가
     * 주석-코드 불일치로 잡았다) — 다음 샘플은 `contentOnly = false`라 이 값이 참이어도
     * novel이 서지 않고, 다음 내용 변화가 어차피 다시 판정한다. 지우는 자리는 관측이 끊긴
     * 곳뿐이다(빈 화면·[markResumed]·[reset]) — 그 구간의 정지는 조용함이 아니라서다.
     */
    private var quietChangeArmed = false

    /**
     * 조용함으로 재질의를 연 마지막 시각. 이 뒤 [QUIET_ACTIVITY_MS] 안의 내용 변화는 조용함을
     * 다시 요구하지 않는다(PR #445 리뷰).
     *
     * **연속 조작 때문이다.** 코레일 인원 선택은 `+`를 여러 번 누르는 화면인데, 매번 조용함을
     * 요구하면 두 번째 탭부터 인정되지 않아 "화면이 그대로예요"가 뜬다 — 이 이슈가 없애려던
     * 그 거짓 문구가 같은 자리에 다시 선다. 첫 탭이 조용함으로 인과를 세우고 나면 뒤이은
     * 변화는 같은 조작의 연속으로 본다.
     */
    private var quietOpenedAtMs: Long? = null

    /**
     * 조용함만으로(연속 창 없이) 연 재질의 수. 화면(구조)마다 [QUIET_COLD_OPENS]까지다.
     *
     * **분 단위로 도는 숫자 때문이다**(PR #445 리뷰). 「자기발생은 조용할 틈이 없다」는 실측은
     * 초 단위로 도는 배너·검색어 값의 이야기이고, 인앱 시계나 「N분 전」 목록은 정반대다 —
     * 1분을 가만히 있다 한 번 바뀌므로 조용함 조건을 **매번** 만족한다. 클릭을 안 쏘는 앱에서
     * 그런 숫자를 가르는 신호가 우리에게 없으므로, 가르는 대신 **비용에 천장을 씌운다**.
     * 조작은 연속 창을 타고 흐르므로 이 상한에 걸리지 않는다.
     *
     * **접힌 상태표시줄의 시계는 해당 없다** — [ActiveWindowResolver]의 크기 필터가 그 창을
     * 애초에 안 고르므로 트리에 없다(2026-08-26 실측: 무조작 81초가 분 경계를 넘는 동안
     * 내용 해시 불변). **한정어가 필요하다**(PR #445 리뷰 3라운드): 펼친 셰이드는 전체 화면이라
     * 크기 필터를 통과하고 **일부러 후보에 넣는 정책**이며(루트 `CLAUDE.md`), 후보가 전부
     * 크기 미달이면 `fallbackRoot`가 크기 검사 없이 `rootInActiveWindow`를 돌려준다. 그 두
     * 경로에서는 시계·「N분 전」이 내용 축에 들어오고, 그때 받는 것이 이 천장이다.
     */
    private var quietColdOpens = 0

    /**
     * 조용함이 연 재질의 **전부**. 연속 창을 탄 것까지 센다. 화면(구조)마다 [QUIET_OPENS]까지다.
     *
     * **연속 창이 [quietColdOpens]를 우회할 수 있어서 둔다**(PR #445 자체 리뷰, 실행으로 재현).
     * 연속 창은 열릴 때마다 스스로 갱신되는데 그 경로는 cold를 안 올린다. 그래서 주기가
     * [QUIET_MS]와 [QUIET_ACTIVITY_MS] **사이**인 자기발생 변화(5초 배너)는 첫 회전에서 cold로
     * 문을 연 뒤 이후 모든 회전이 창을 타고 **무한히** 나갔다 — 5초 주기면 12 RPM으로 무료 티어
     * 15 RPM에 붙는다. #380이 닫은 폭주가 그대로 되살아나는 자리다.
     *
     * 창을 없애면 연속 조작이 죽고(그게 창을 만든 이유다), 창을 두면 cold 천장이 무의미해진다 —
     * 그래서 **창은 두되 총량에 따로 천장을 씌운다.**
     */
    private var quietOpens = 0

    /**
     * 위 두 예산이 누구 몫인가(화면의 구조 축).
     *
     * **[sentSignature]로 판정하면 안 된다**(PR #445 자체 리뷰, 실행으로 재현). 실패
     * ([markFailed])와 늦은 응답 폐기([markResponseDiscarded])가 그 값을 `null`로 만들어서,
     * 그 뒤 **다른 화면**에서 나가는 첫 요청이 초기화 조건을 건너뛴다 — 화면 A에서 태운 천장을
     * 화면 B가 물려받아, B에서 조용함 뒤 사용자가 눌러도 「화면이 그대로예요」로 떨어졌다.
     */
    private var quietBudgetStructure: String? = null

    /** 이번 판정이 조용함으로 열렸나. 예산은 **요청이 실제로 나갈 때** 태운다([markSent]). */
    private var sendWasQuietOpened = false

    /** 그중 연속 창 없이(cold) 열렸나. */
    private var sendWasQuietCold = false
    private var sentAtMs = 0L
    private var stuckReported = false
    private var failures = 0
    private var retryNotBeforeMs = 0L
    private var requestOutstanding = false
    private var minRetryAfterMs = 0L
    private var unreadableSinceMs: Long? = null
    private var lastInteractionMs: Long? = null
    private var sendWasContentOnly = false
    /**
     * 앵커가 여는 재질의의 상한(#380 리뷰). **진행 신호가 있기 전까지 한 번만** 연다.
     *
     * 앵커(안내 대상 슬롯의 label 변화)는 인과 게이트를 우회하는데 게이트의
     * [INTERACTION_WINDOW_MS] 같은 시간 상한이 없다. 안내 대상이 스스로 갱신되는 영역 안에
     * 있으면 응답이 도착할 때마다 슬롯이 또 어긋나 **왕복당 한 번씩 재질의가 도는 루프**가
     * 된다 — 이 이슈가 배너에 대해 닫은 것과 같은 폭주다.
     *
     * **안내마다 여는 것으로는 못 막는다.** 그 루프의 주기가 바로 "응답 하나 = 안내 하나"라
     * 상한이 매 왕복 초기화되고, 왕복당 1회는 [requestOutstanding] 가드가 이미 하던 일이다.
     * **안내 대상의 정체성으로도 못 막는다.** node id는 직렬화 순서라 사실상 자리 번호이고,
     * 대상이 슬롯을 옮기면(새 메시지가 대화방 순서를 밀면) 그 자리가 매번 달라진다.
     *
     * 그래서 **진행 신호**로 연다 — 요청이 실제로 나간 구조 전환([markSent])과 조작 관측
     * 둘뿐이다. raw 샘플의 구조 변화는 진행이 아니다 — 자동 회전의 과도기 트리가 한 샘플만
     * 다른 구조로 잡히는 것을 홀드(#32)가 버리는데, 그것으로 열면 회전마다 되살아난다.
     * 앵커가 답하는 질문이
     * "강조가 엉뚱한 줄을 가리킨다"이고 그 해법은 새 안내 하나인데, 진행 없이 또 어긋나는
     * 화면은 다시 물어서 나아지지 않는다. 막히면 강조를 내리고(호출자) 정체 문구로 떨어진다.
     * 눌림 확인 재질의가 눌림당 1회인 것과 같은 결(#319).
     */
    private var anchorRequeryUsed = false
    private var sendWasAnchorOpened = false

    fun onSample(
        signature: ScreenSig,
        nowMs: Long,
        hasLabeledElement: Boolean,
        guidedTargetChanged: Boolean = false,
        clickAxisDead: Boolean = false,
    ): Decision {
        if (!hasLabeledElement) {
            // 빈 화면도 현재 화면으로는 관측한다. 그래야 직전 화면의 요청이 늦게 도착했을 때
            // currentSignature 대조가 그 응답을 버린다. 서버에 보낼 안정 화면으로는 승격하지
            // 않되, 영구 빈 WebView·카메라에서 읽는 중 표시가 무한히 돌지 않도록 연속 빈 화면
            // 전체를 시간으로 묶는다. 시그니처가 A↔B로 흔들려도 타이머를 새로 주지 않는다.
            lastSample = signature
            heldSamples = 0
            // label 없는 화면은 내용 흔들림 승격의 대상도 아니다(#350) — 타이머를 세우지 않는다.
            structureStableSinceMs = null
            // 읽을 수 없던 구간은 "화면이 조용했던" 구간이 아니다. 조용함 시계를 지금으로
            // 다시 찍어, 빈 트리를 사이에 둔 변화가 조작으로 읽히지 않게 한다.
            lastContent = null
            contentChangedAtMs = nowMs
            quietChangeArmed = false
            // 연속 창도 함께 닫는다(PR #445 자체 리뷰). 안 닫으면 스켈레톤이 몇 샘플 끼었다
            // 돌아온 화면이 **인과 근거 없이** 창을 타고 나간다 — 조용함 시계만 되감고 창을
            // 살려 두면 되감은 의미가 없다.
            //
            // **천장([quietColdOpens]·[quietOpens])은 일부러 안 비운다.** 여기는 세션 재개
            // ([markResumed])와 달리 같은 화면의 과도기라, 비우면 로딩이 끼는 화면이 스켈레톤
            // 한 번마다 예산을 새로 받아 총량 천장이 무의미해진다.
            quietOpenedAtMs = null
            val since = unreadableSinceMs ?: nowMs.also { unreadableSinceMs = it }
            return if (nowMs - since >= unreadableAfterMs) {
                Decision.UNREADABLE
            } else {
                Decision.WAIT
            }
        }
        unreadableSinceMs = null
        // 내용 축이 방금 바뀌었는가, 그리고 그 직전까지 얼마나 조용했는가(#427 2단계).
        //
        // **홀드 조기 반환보다 앞이다**(PR #445 리뷰). 뒤에 두면 카운터가 "직전 N초"가 아니라
        // "우리가 본 직전 N초"가 된다 — 토스트로 구조가 한 샘플 어긋나면 [CONTENT_SETTLE_MS]
        // 동안 회전 샘플이 전부 WAIT로 빠지고, 그 사이 시계가 blip 이전 값에 얼어붙어
        // **내내 회전 중이던 화면이 「조용했다」로 판정**된다.
        val contentJustChanged = lastContent != null && signature.content != lastContent
        if (contentJustChanged) {
            val quietFor = contentChangedAtMs?.let { nowMs - it } ?: 0L
            quietChangeArmed = quietFor >= quietMs
            contentChangedAtMs = nowMs
        } else if (contentChangedAtMs == null) {
            contentChangedAtMs = nowMs
        }
        lastContent = signature.content
        if (signature.structure != lastSample?.structure || structureStableSinceMs == null) {
            structureStableSinceMs = nowMs
        }
        // 구조는 안정인데 내용만 계속 흔들리는 화면(광고·marquee)은 홀드가 영영 안 끝난다 —
        // 이 승격이 없으면 그런 화면에서 안내가 아예 못 나간다(#380).
        //
        // 조작 직후를 억제하는 것은 [markInteraction]이 **구조 안정 시계를 되감는** 방식이다.
        // 여기서 `!interactedRecently`로 억제하면 아래 `novel`이 요구하는 값과 정확히 반대가
        // 되어 두 조건이 상호배타가 된다 — 샘플마다 내용이 바뀌는 화면에서 사용자가 스크롤하면
        // 창이 열린 동안은 승격이 막혀 WAIT이고, 창이 닫히는 순간 재질의 조건도 함께 꺼져
        // 안내가 영영 안 온다(그 화면은 7초 뒤 "화면이 안 바뀌었어요"를 본다).
        // 되감기로 표현하면 억제가 [CONTENT_SETTLE_MS]에 끝나고 인과 창은
        // [INTERACTION_WINDOW_MS]까지 열려 있어, 그 사이에 재질의가 나간다.
        val promoted = contentSettled(nowMs)
        if (signature != lastSample) {
            lastSample = signature
            heldSamples = 1
            if (!promoted) return Decision.WAIT
        } else {
            heldSamples++
            if (heldSamples < holdSamples && !promoted) return Decision.WAIT
        }

        // 새 상황인가(#380). 구조 변화는 무조건이다 — 화면 이동·팝업·토글은 지금 안내를
        // 무효화한다. 내용만의 변화는 인과로 가른다: 최근 조작이 관측됐거나(균일 목록 스크롤은
        // 구조가 같은 채 label만 밀린다), 안내가 가리키는 슬롯 자체가 바뀐 경우(목록 재정렬로
        // 강조가 엉뚱한 줄을 가리키게 되는 것)만 새 상황이고, 아니면 자기발생(광고)이다.
        val sent = sentSignature
        val contentOnly = sent != null &&
            signature.structure == sent.structure &&
            signature.content != sent.content
        // 앵커는 인과 게이트를 우회하지만 게이트와 달리 시간 상한이 없다. **진행 신호가
        // 있기 전까지 한 번만** 연다([anchorRequeryUsed]).
        val anchorOpened = contentOnly && guidedTargetChanged && !anchorRequeryUsed
        // 클릭 축이 죽은 앱에서만 여는 세 번째 문(#427 2단계). ①②는 항상 요구하고, 인과의
        // 근거는 ③-a와 ③-b 둘 중 하나다:
        // ① 이 앱이 세션 내내 클릭 이벤트를 한 번도 안 쐈다(호출자가 관측해 넘긴다),
        // ② 구조는 같고 내용만 바뀌었다,
        // ③-a 바뀌기 직전까지 [QUIET_MS]만큼 조용했다(첫 조작을 세우는 근거), 또는
        // ③-b 조용함으로 연 재질의가 [QUIET_ACTIVITY_MS] 안에 있다(연속 조작).
        //
        // ①은 **「클릭을 쏘는 앱」이 아니라 「아직 클릭을 못 본 앱」이다**(PR #445 자체 리뷰).
        // 앱을 여는 탭은 런처 package라, 어느 앱이든 **진입~그 앱에서의 첫 클릭까지는** 이 문이
        // 열려 있다. 그 구간의 배너 로테이션은 아래 두 천장이 받는다. 첫 클릭 뒤로는 배민 같은
        // 앱이 이 문을 아예 안 거친다. 화면 단위로 좁히는 것은 #394 영역이다.
        // ③-a가 자기발생과 가른다:
        // 2026-08-25 SM-S921N 실측에서 회전하는 검색 화면은 **최대 3샘플(약 2.3초)**밖에
        // 정지하지 못했고, 코레일 인원 선택은 조작 전까지 80샘플 넘게 고정이었다.
        //
        // ③-a는 **분 단위로 도는 숫자를 못 가른다**(PR #445 리뷰). 그건 [quietColdOpens]가
        // 비용에 천장을 씌워 맡는다 — 가르는 신호가 없으므로 가르는 척하지 않는다.
        //
        // ③-b(연속 창)는 **총량 천장 아래에서만** 산다([quietOpens]). 창은 열릴 때마다 갱신돼
        // 스스로를 연장하므로, 총량이 없으면 주기가 4~15초인 자기발생 변화가 cold를 한 번만
        // 태우고 영원히 창을 타고 흐른다(PR #445 자체 리뷰에서 실행으로 재현).
        val quietActive = quietOpenedAtMs?.let { nowMs - it <= quietActivityMs } ?: false
        val quietOpened = clickAxisDead && contentOnly && quietOpens < QUIET_OPENS &&
            (quietActive || (quietChangeArmed && quietColdOpens < QUIET_COLD_OPENS))
        val novel = when {
            sent == null -> true
            signature.structure != sent.structure -> true
            !contentOnly -> false
            else -> anchorOpened || interactedRecently(nowMs) || quietOpened
        }
        // **조건부로 적지 않는다.** `if (novel)`로 두면 STUCK 샘플(정의상 novel=false)이
        // 갱신을 건너뛰어 직전 SEND의 값이 남고, 눌림 확인 재질의가 그 옛 값을 실어
        // `performed=true` + `screen_changed=true`라는 모순된 이력을 만든다 — 재질의의
        // 존재 이유("눌렀는데 그대로")가 페이로드에서 지워진다.
        sendWasContentOnly = novel && contentOnly
        // 상한은 **요청이 실제로 나갔을 때만** 쓴다([markSent]). `SEND`는 그 보장이 아니다 —
        // 호출자가 확인 대기(GD-4)나 in-flight CAS에 막혀 요청 없이 돌아설 수 있고, 그때
        // 소진하면 그 안내의 앵커 재질의가 나가지도 못한 채 끝난다.
        //
        // 게이트가 이미 열려 있으면 태우지 않는다 — 그 재질의는 조작이 만든 것이라 앵커가
        // 없어도 나갔다. 태우면 뒤이은 자기발생 변화에 쓸 앵커가 없다.
        sendWasAnchorOpened = novel && anchorOpened && !interactedRecently(nowMs)
        // 연속 창은 **조용함이 실제로 연 재질의**에서만 이어진다. 게이트가 이미 열려 있었다면
        // 그 재질의는 조작이 만든 것이라 조용함을 태울 이유가 없다(앵커 상한과 같은 결).
        //
        // **여기서 태우지 않고 [markSent]로 미룬다**(PR #445 자체 리뷰). 바로 위 앵커 상한이
        // 세운 규칙("요청이 실제로 나갔을 때만 쓴다 — `SEND`는 그 보장이 아니다")을 이 자리가
        // 어기고 있었다: `quietColdOpens++`가 in-flight 조기 반환보다 앞이라, 요청이 한 건도
        // 안 나가는데 화면당 예산 3 중 1이 사라졌다.
        sendWasQuietOpened = novel && quietOpened && !interactedRecently(nowMs)
        sendWasQuietCold = sendWasQuietOpened && !quietActive
        if (novel) {
            // 진행 중인 요청이 있으면 보내지 않는다. 여기서 SEND를 내면 호출자가 "보냈다"고
            // 기록하는데 실제 요청은 in-flight 가드에 막혀 나가지 않는다 — 그 화면의 안내가
            // 영영 오지 않는다(PR #61 리뷰 P1). 응답이 끝나면 [markFinished]가 다시 열어 준다.
            if (requestOutstanding) return Decision.IDLE
            // 실패한 화면을 폴링 주기마다 다시 때리면 서버가 죽어 있을 때 초당 한 번씩 두드린다.
            // 무료 티어는 15 RPM이다(HANDOFF §10). 실기기에서 실제로 이 루프를 만들어 봤다.
            if (failures > 0 && nowMs < retryNotBeforeMs) return Decision.IDLE
            return Decision.SEND
        }

        // 응답을 기다리는 중이면 멈춘 게 아니다. 느린 응답을 정체로 말하면 사용자를 헷갈리게 한다.
        if (requestOutstanding) return Decision.IDLE

        // 같은 화면을 다시 물어보지 않는다. 무료 티어 15 RPM이라 폴링이 곧 호출량이다(#39).
        // 광고가 도는 화면도 이제 여기로 접히므로, 무조작 7초의 정체 알림과 눌림 확인 재질의가
        // 그런 화면에서도 정상 동작한다(#380 이전에는 로테이션마다 SEND라 영영 못 왔다).
        if (!stuckReported && nowMs - sentAtMs >= stuckAfterMs) {
            stuckReported = true
            return Decision.STUCK
        }
        return Decision.IDLE
    }

    /**
     * **요청이 실제로 나갔을 때만** 부른다. 보낸 셈 치면 그 화면의 안내가 영영 오지 않는다.
     *
     * 앵커 상한도 여기서 태운다 — 취지는 "안내를 만들지 못한 재질의는 태우지 않는다"다.
     * **예외 하나를 수용한다**: 앵커 재질의가 나간 뒤 응답 도착 전에 중지 칩이 눌리면 그
     * 응답은 `awaiting_confirm`으로 버려지는데, 상한은 이미 태워졌다. 되돌리지 않는 이유는
     * 「계속 볼래요」로 돌아온 시점이면 `sentAtMs`가 이미 정체 시간을 넘겨 곧바로 정체 문구가
     * 뜨기 때문이다 — 안내 없이 매달리는 구간이 생기지 않는다.
     */
    fun markSent(signature: ScreenSig, nowMs: Long) {
        // 구조가 다른 화면을 **실제로 물었다** — 진행이다. 앵커 상한을 다시 연다.
        // raw 샘플의 구조 변화로 열면 안 된다(#399 리뷰): 자동 회전 UI의 전환 중간 트리가
        // 한 샘플만 다른 구조로 관측되면, 그 샘플은 홀드가 WAIT로 버리는데 상한만 열려
        // 회전마다 앵커 재질의가 되살아난다 — #32 때문에 버린 과도기 트리를 진행으로
        // 인정하는 셈이다. 홀드를 통과하고 요청까지 나간 구조 전환이 여기다.
        val structureProgressed =
            sentSignature != null && signature.structure != sentSignature?.structure
        if (structureProgressed) {
            anchorRequeryUsed = false
        }
        // 조용함 쪽 구조 전환은 **[sentSignature]가 아니라 [quietBudgetStructure]로 판정한다.**
        // 실패·폐기가 `sentSignature`를 비우고 나면 위 조건이 거짓이 되어, 옮겨 간 화면이 앞
        // 화면에서 태운 천장을 물려받았다(PR #445 자체 리뷰). **연속 창도 같은 근거를 써야
        // 한다**(PR #445 리뷰 3라운드) — [markFailed]·[markResumed]`(requery = true)`·
        // [markResponseDiscarded] 뒤의 구조 전환 SEND가 창을 못 열어, 이 PR이 없애려던 거짓
        // 정체 문구가 실패·화면꺼짐 경로에서 그대로 섰다(JVM 재현 3건).
        //
        // 경계는 그대로다: [quietBudgetStructure]는 세션 첫 요청에서 `null`이라 「첫 진입은 안
        // 연다」가 유지되고, 여기서만 갱신되므로 raw 샘플의 구조 변화로도 안 열린다.
        val budgetStructureProgressed =
            quietBudgetStructure != null && signature.structure != quietBudgetStructure
        if (signature.structure != quietBudgetStructure) {
            quietBudgetStructure = signature.structure
            quietColdOpens = 0
            quietOpens = 0
            quietOpenedAtMs = null
        }
        // 예산은 여기서 태운다 — 판정이 아니라 **요청이 실제로 나간 것**이 기준이다.
        if (sendWasQuietOpened) {
            quietOpens++
            if (sendWasQuietCold) quietColdOpens++
            quietOpenedAtMs = nowMs
        }
        // **요청이 실제로 나간 구조 전환도 연속 창을 연다**(PR #445 리뷰 확인 2). 앵커 상한을
        // 다시 여는 것과 같은 진행 신호다. 안 열면 클릭 없는 앱에서 0↔1 경계(코레일 인원
        // 시트의 「인원감소」 활성화가 구조를 바꾸는 자리) 직후의 연속 조작이 조용함 미달로
        // 막혀, 이 PR이 없애려던 거짓 정체 문구가 이웃 행에서 그대로 선다 — 어린이·유아처럼
        // 0에서 시작하는 행이 정확히 그 경로다(JVM 재현: 두 번째 탭이 IDLE→STUCK).
        //
        // 이 창을 탄 재질의도 [quietOpens]에 세이므로 **한 구조에 머무는 동안은**
        // [QUIET_OPENS]회가 상한이다 — 클릭 없는 앱에서 구조 churn 뒤 내용 churn이 그만큼 탈
        // 수 있다는 뜻이고, 그 값을 받아들이는 것이 이 결정이다. 클릭을 쏘는 앱은 문 자체를
        // 안 쓰므로 무관하다.
        //
        // **되돌리는 것은 화면 켜짐 하나뿐이다**(#481, [markResumed]의 `clearQuietBudget`).
        // root 복구는 안 비운다 — 예산은 인과 근거가 아니라 비용 상한이라 "관측 못 한 구간"
        // 논거가 안 걸리고, root는 우리 앱이 잠깐 앞에 서기만 해도 깜빡인다.
        if (budgetStructureProgressed) quietOpenedAtMs = nowMs
        if (sendWasAnchorOpened) anchorRequeryUsed = true
        sentSignature = signature
        sentAtMs = nowMs
        stuckReported = false
        requestOutstanding = true
    }

    /**
     * 서버가 준 **새 행동 안내를 실제로 표시한 시각**부터 정체 시간을 다시 센다(#494).
     *
     * [markSent]는 요청이 나간 시각이라 느린 서버 왕복까지 [STUCK_AFTER_MS]에 포함한다. 응답을
     * 기다리는 동안은 [requestOutstanding]이 정체 판정을 막지만, 그 요청이 끝나는 순간에는 이미
     * 시간이 소진돼 새 안내를 보여 준 직후 곧바로 [Decision.STUCK]이 될 수 있었다.
     *
     * 호출 범위는 서버 응답의 새 행동 안내를 `display.show(...)`로 그린 한 자리뿐이다. 완료·오류·
     * 확인·백오프 대기 문구와 기존 안내 재표시는 사용자에게 새 조작 시간을 주는 행동 안내가
     * 아니므로 이 시계를 연장하지 않는다. TTS 종료도 기다리지 않는다 — 화면에 표시된 순간부터
     * 사용자는 시각적으로 따라 할 수 있다.
     */
    fun markActionGuideDisplayed(nowMs: Long) {
        sentAtMs = nowMs
        stuckReported = false
    }

    /**
     * 늦은 응답을 **내용 축 근거로** 버렸다(#380). 그 화면을 보낸 적 없는 것으로 되돌린다.
     *
     * 구조 축 폐기(`screen_changed`)에는 이 처리가 필요 없다 — 구조가 달라 다음 샘플이 무조건
     * 새 상황이다. 내용 축 폐기는 다르다: 버린 근거가 "요청~응답 사이 조작"인데 그 응답이
     * 인과 창(5초)의 끝에 도착하면, 다음 샘플에서 창이 이미 닫혀 `novel`이 서지 않는다.
     * **다른 화면으로 옮겼다고 판정해 버려 놓고 그 새 화면을 안 묻는** 구멍이 된다.
     * 화면 꺼짐 폐기가 [markResumed]의 `requery`로 같은 처리를 하는 것과 같은 결이고,
     * 실패가 아니므로 백오프는 건드리지 않는다.
     */
    fun markResponseDiscarded() {
        sentSignature = null
    }

    /** 성공·실패·폐기와 무관하게 요청이 끝나면 부른다. 다음 화면이 다시 나갈 수 있게 연다. */
    fun markFinished() {
        requestOutstanding = false
    }

    /** 응답이 실제로 화면에 반영되면 백오프를 푼다. */
    fun markSucceeded() {
        failures = 0
        minRetryAfterMs = 0L
        retryNotBeforeMs = 0L
    }

    /**
     * 마지막으로 뜬 샘플의 시그니처. 응답이 도착했을 때 **그 사이 화면이 바뀌었는지** 보는 데 쓴다.
     * 세대(generation)만 보면 같은 세션 안에서 A의 안내가 B 화면 위에 붙는다(PR #61 리뷰 P1).
     *
     * 폐기 판정은 [ScreenSig.structure]로 한다(#380) — 내용까지 대조하면 응답 대기 2~5초 사이
     * 배너가 한 번 도는 것만으로 정상 응답이 버려져, 광고 화면에서 안내가 영영 못 뜬다.
     * 같은 구조의 **다른** 화면으로 이동한 경우는 [interactionAfter]가 마저 거른다.
     */
    fun currentSignature(): ScreenSig? = lastSample

    /**
     * 앵커 상한이 소진됐는가. **계측 전용**(`poll=`의 `anchor=capped`) — 상한에 막혀 IDLE인
     * 것과 그냥 안 바뀐 것을 실기기 로그에서 갈라야 이 상한이 도는지 볼 수 있다.
     */
    fun anchorRequerySpent(): Boolean = anchorRequeryUsed

    /**
     * 조용함 문의 상태(#427 2단계). **계측 전용** — 내용이 바뀌었는데도 IDLE인 것이
     * "조용하지 않아서"인지, "클릭 축이 살아 있어서"인지, "천장에 걸려서"인지 로그에서 갈라야 한다.
     *
     * **총량([QuietState.opens])까지 싣는다**(PR #445 자체 리뷰). cold 천장을 우회하는 폭주를
     * 막는 최후 방벽이 총량이라, 로그에 안 나오면 그 방벽이 도는지 실기기에서 볼 수 없다.
     */
    fun quietState(nowMs: Long): QuietState = QuietState(
        forMs = contentChangedAtMs?.let { nowMs - it } ?: 0L,
        armed = quietChangeArmed,
        coldOpens = quietColdOpens,
        opens = quietOpens,
    )

    /** [quietState]의 한 묶음. 네 값이 **같은 시점**의 것이어야 로그가 서로를 설명한다. */
    data class QuietState(
        val forMs: Long,
        val armed: Boolean,
        val coldOpens: Int,
        val opens: Int,
    )

    /**
     * 안내 대상 앱에서 **클릭 또는 선택** 이벤트가 관측됐다(#380·#482). 내용만의 변화를
     * 사용자 유발로 인정하는 근거이고, 어느 이벤트가 여기 오는지는 호출자가 정한다
     * ([GuideAccessibilityService.isCausalEvent]).
     *
     * **스크롤은 근거가 아니다.** 자동으로 도는 목록이 사용자가 손대지 않아도
     * `TYPE_VIEW_SCROLLED`를 내고, 이벤트만으로 사용자 스크롤과 구분할 방법이 없다.
     * 2026-08-24 SM-S921N 실측 — 인기 검색어가 도는 검색 화면에서 무조작 50초에 92건
     * (초당 1.8건)이 왔고, 게이트가 영구히 열려 로테이션마다 재질의가 나갔다(50초에 SEND
     * 20회). 이 이슈가 막으려던 폭주 그대로다. **확실한 오탐보다 좁은 미탐이 낫다.**
     *
     * 그 미탐은 세 겹이 받는다 — 스크롤은 대개 구조를 바꿔 구조 축이 잡고, 구조가 그대로인
     * 균일 목록은 타깃 앵커가 잡고, 둘 다 비껴가면 재질의가 한 박자 늦고 정체 알림(7초)이
     * 받친다. `performed`의 null 강등과 같은 결(`../CLAUDE.md` 정체 감지).
     *
     * **클릭 축이 아예 죽은 앱도 있다** — 같은 실측에서 그 앱은 세션 전체에서
     * `TYPE_VIEW_CLICKED`를 0건 냈다(실제로 눌렀는데도). 사용자 조작을 접근성 이벤트 밖에서
     * 찾는 것은 #394에서 정한다.
     */
    fun markInteraction(nowMs: Long) {
        lastInteractionMs = nowMs
        // 조작은 진행 신호다 — 앵커 상한을 다시 연다(그 뒤의 슬롯 이동은 사용자가 만든 것일
        // 수 있다). 상한이 열리는 자리는 여기와 [markSent]의 구조 전환 둘뿐이다.
        //
        // 조용함 예산은 여기서 건드리지 않는다(PR #445 리뷰). **되돌릴 것이 없어서다** —
        // 예산은 조용함이 연 재질의에서만 타는데(`sendWasQuietOpened`가 `!interactedRecently`를
        // 요구한다), 이 함수가 연 구간의 SEND는 정의상 그쪽이 아니다.
        //
        // **「클릭이 조용함 문을 닫으니까」가 아니다**(#482로 갈렸다). 선택 이벤트는 여기 오지만
        // `packagesWithClicks`에는 안 들어가므로, 조용함 문이 열린 채로 이 함수가 불리는 조합이
        // 실재한다 — 삼성 picker 화면이 그것이다.
        anchorRequeryUsed = false
        // 사용자가 방금 움직였으면 화면은 "안정된 지 오래"가 아니다. 되감아서 [CONTENT_SETTLE_MS]
        // 동안 승격을 막는다 — 그 사이의 내용 변화는 사용자가 만드는 중일 수 있어(스크롤)
        // 홀드로 가라앉혀야 한다(#32). 억제를 이 자리에 두는 이유는 [onSample]의 주석에 있다.
        structureStableSinceMs = nowMs
    }

    /**
     * 인과 게이트가 지금 열려 있는가. **계측 전용이다** — 판정은 [onSample] 안에서 하고,
     * 이 값은 게이트가 열린 구간을 실기기 로그(`poll=`의 `gate=`)에서 보기 위한 창이다
     * (#380). 판정에 쓰지 말 것.
     */
    fun interactionGateOpen(nowMs: Long): Boolean = interactedRecently(nowMs)

    private fun interactedRecently(nowMs: Long): Boolean {
        val at = lastInteractionMs ?: return false
        return nowMs - at <= interactionWindowMs
    }

    /**
     * [sinceMs] 이후 조작이 관측됐는가. 늦은 응답 폐기가 쓴다(#380) — 구조가 같아도 요청과
     * 응답 사이에 사용자가 움직였으면 같은 구조의 **다른** 화면(다른 상품 상세, 다른 대화방)일
     * 수 있어, 내용까지 다르면 버리고 다시 묻는 쪽이 안전하다.
     */
    fun interactionAfter(sinceMs: Long): Boolean {
        val at = lastInteractionMs ?: return false
        return at >= sinceMs
    }

    /**
     * 직전 판정이 **구조는 그대로이고 내용만 바뀐** 화면의 SEND였는가(#380).
     *
     * **홀드를 통과한 샘플에서만 확정된다.** 대입이 홀드 단계의 조기 반환보다 뒤라
     * WAIT·UNREADABLE 샘플에서는 갱신되지 않는다 — 그 시점에 읽지 말 것. 지금 읽는 자리는
     * `sendStableScreen` 하나뿐이고 그것은 SEND·STUCK 분기에서만 불린다.
     * 스텝 이력의 `screen_changed`가 이 값을 쓴다 — 구조 축만 보면 균일 목록 스크롤이
     * "화면 안 바뀜"으로 적혀, 서버가 안내가 안 먹혔다고 읽는다.
     */
    fun lastSendWasContentOnly(): Boolean = sendWasContentOnly

    /** 구조가 이만큼 안정됐으면 내용 흔들림을 홀드에서 면제한다(#380). */
    private fun contentSettled(nowMs: Long): Boolean {
        val since = structureStableSinceMs ?: return false
        return nowMs - since >= contentSettleMs
    }

    /**
     * 요청이 실패하면 보낸 적 없는 것으로 되돌려 다시 시도하게 한다. 그러지 않으면 네트워크가
     * 한 번 끊긴 화면에서 안내가 영영 오지 않는다 — 이벤트 기반일 때는 다음 이벤트가 재시도를 해줬다.
     *
     * 대신 재시도는 점점 뜸해진다. [backoffMs] 참고.
     *
     * [failedAtMs]는 요청을 시작한 때가 아니라 실패 응답·timeout이 **끝난 때**다. 오래 기다린
     * 시간을 백오프가 이미 지난 것으로 치면 timeout 직후 같은 요청이 즉시 겹친다(#82).
     *
     * [retryAfterMs]는 서버가 안 값(QUOTA의 `retry_after_seconds`)이다 — 원인을 모르는 일반
     * 실패는 추측(지수 백오프)만 할 수 있지만, 이건 Gemini가 준 실제 재시도 가능 시각이라
     * 있으면 [backoffMs]가 그 값을 우선한다. 없으면(네트워크·완료검증 재질의 등) 기존 로직 그대로다.
     */
    fun markFailed(failedAtMs: Long, retryAfterMs: Long? = null) {
        failures++
        sentSignature = null
        sentAtMs = 0L
        stuckReported = false
        minRetryAfterMs = retryAfterMs?.coerceAtLeast(0L) ?: 0L
        val delayMs = backoffMs()
        retryNotBeforeMs = if (failedAtMs > Long.MAX_VALUE - delayMs) {
            Long.MAX_VALUE
        } else {
            failedAtMs + delayMs
        }
    }

    /**
     * 화면이 다시 켜졌거나 root가 복구돼 관측을 재개했다. **관측할 수 없던 구간은 정체도,
     * 읽기 불가도 아니다.** 그대로 두면 잠금 해제·root 복구 첫 샘플이 곧바로 [Decision.STUCK]
     * 또는 [Decision.UNREADABLE]이 된다. 두 타이머를 지금으로 다시 찍는다.
     *
     * [stuckReported]를 함께 내리는 것은 **반대 방향도 닫기 위해서다** — 꺼지기 전에 이미
     * 정체가 떴다면 [stuckContent]는 접미사를 붙인 사본만 돌려주고 원본에 되쓰지 않아,
     * 복원된 안내는 접미사 없는 원문이다. 다시 셀 수 있어야 그 화면에서 정체가 또 뜬다.
     *
     * [requery]면 그 화면을 **보낸 적 없는 것으로** 되돌린다. 꺼진 동안 도착한 응답을 버렸을 때
     * 쓴다 — 버리기만 하면 [sentSignature]가 남아 같은 화면에 새 요청이 영영 안 나간다.
     * **실패가 아니므로 백오프는 건드리지 않는다**([markFailed]와 다른 점이다).
     *
     * 같은 규칙이 #380이 들여온 두 시계에도 걸린다 — 승격 타이머와 조작 관측이다(본문 주석).
     *
     * **[clearQuietBudget]은 그 규칙을 따르지 않는다**(#481). 위 시계들은 전부 **인과 근거**라
     * "관측할 수 없던 구간은 근거가 아니다"가 그대로 걸리지만, [quietColdOpens]·[quietOpens]는
     * 근거가 아니라 **비용 상한**이다 — 같은 논거가 안 걸린다. root 복구는 우리 앱이 잠깐 앞에
     * 서거나 권한 대화상자가 뜨기만 해도 나므로, 거기서 비우면 예산이 그때마다 새로 채워져
     * 「한 구조에 머무는 동안은 [QUIET_OPENS]가 상한」이 참이 아니게 된다. 그래서 **화면
     * 켜짐만** 비운다 — 그쪽은 사용자가 폰을 덮었다 다시 여는 실제 세션 경계다.
     */
    fun markResumed(nowMs: Long, requery: Boolean, clearQuietBudget: Boolean) {
        sentAtMs = nowMs
        stuckReported = false
        if (unreadableSinceMs != null) unreadableSinceMs = nowMs
        // 승격 타이머도 관측 불가 구간을 세면 안 된다(#380). 꺼져 있던 시간은 "구조가 안정했던
        // 시간"이 아니다. 그대로 두면 잠금 해제 첫 샘플에서 [contentSettled]가 곧바로 참이 되고,
        // [requery]가 [sentSignature]를 비우는 경우와 겹치면 홀드 없이 그 자리에서 SEND가 나간다
        // — 덜 그려진 화면을 보내지 않는다는 #32가 되살아난다. **null로 두어 다음 샘플부터**
        // 다시 세게 한다: 재개 시점과 첫 샘플 사이도 관측 밖이라 지금으로 찍으면 그만큼을 센다.
        //
        // **막는 것은 승격 경로뿐이다.** [heldSamples]는 그대로 두므로, 재개 전후 화면이
        // 똑같으면 첫 샘플이 홀드를 채워 곧바로 나간다 — 그건 맞다. 잠그기 전에 이미 안정으로
        // 관측한 화면이라 다시 재는 값이 없다.
        structureStableSinceMs = null
        // 조작 관측도 마찬가지다. 잠그기 직전의 탭이 해제 뒤 [INTERACTION_WINDOW_MS]까지 게이트를
        // 열어 두면 그동안 배너가 한 번 도는 것만으로 재질의가 나간다. 관측 불가 구간을 사이에
        // 둔 조작은 "방금 한 조작"이 아니다.
        lastInteractionMs = null
        // 조용함도 같은 성격의 인과 근거다(PR #445 리뷰). 화면이 꺼져 있던 5분은 "화면이
        // 조용했던" 5분이 아니다 — 그대로 두면 해제 뒤 첫 내용 변화가 조작으로 판정된다.
        // [contentChangedAtMs]를 null로 두어 다음 샘플이 지금부터 다시 재게 한다.
        contentChangedAtMs = null
        lastContent = null
        quietChangeArmed = false
        quietOpenedAtMs = null
        if (clearQuietBudget) {
            quietColdOpens = 0
            quietOpens = 0
        }
        if (requery) sentSignature = null
    }

    /**
     * 안내한 대상이 눌렸다(#319). 정체 타이머를 지금으로 다시 찍는다 — "안내 후 화면 그대로
     * 7초"는 사용자가 방금 조작했으면 **그 조작부터** 세야 맞다. [stuckReported]를 함께 내리는
     * 이유는 정체가 이미 보고된 뒤 눌림이 와도 다음 정체(와 눌림 확인 재질의)가 뜰 수 있어야
     * 해서다 — 래치를 그대로 두면 그 화면의 재질의가 영영 없다.
     *
     * [markResumed]와 의미가 다르다 — 그쪽은 관측 불가 구간을 정체·읽기 불가 양쪽에서 빼고,
     * 여기는 조작 이후의 정체 시간만 다시 센다. requery 되돌림은 없다: 화면이 그대로면
     * [sentSignature]가 유효한 게 맞고, 재질의 여부는 토큰(`GuideSession.consumePressRequery`)이
     * 정한다.
     */
    fun markPressed(nowMs: Long) {
        sentAtMs = nowMs
        stuckReported = false
    }

    /**
     * 연속 실패마다 두 배로 늘어나고 [RETRY_MAX_MS]에서 멈춘다.
     *
     * 다만 [markFailed]가 서버의 `retry_after_seconds`를 받았으면 그 값이 하한이 된다 — 이
     * 하한만 [RETRY_MAX_MS] 상한을 넘어설 수 있다. 상한의 원래 취지("원인 모를 침묵이 길어도
     * 이 간격보다 뜸해지지 않는다")는 **기다리면 풀리는지조차 모를 때**의 추측이었는데, QUOTA는
     * 서버가 정확한 재개 시각을 알려준 경우라 그 답을 무시하고 더 일찍 두드리면 아직 안 풀린
     * 창에 다시 걸려 같은 429가 반복된다(2026-08-18 실기기 재현 — 5 RPM 모델에서 서버가 60초를
     * 알려줬는데 30초 상한으로 재시도해 계속 막힘).
     */
    fun backoffMs(): Long {
        if (failures <= 0) return 0L
        val shift = (failures - 1).coerceAtMost(MAX_BACKOFF_SHIFT)
        val exponential = (RETRY_BASE_MS shl shift).coerceAtMost(RETRY_MAX_MS)
        return exponential.coerceAtLeast(minRetryAfterMs)
    }

    /** 세션이 바뀌면 이전 세션의 화면 기억을 끌고 가지 않는다. */
    fun reset() {
        lastSample = null
        heldSamples = 0
        structureStableSinceMs = null
        sentSignature = null
        lastContent = null
        contentChangedAtMs = null
        quietChangeArmed = false
        quietOpenedAtMs = null
        quietColdOpens = 0
        quietOpens = 0
        quietBudgetStructure = null
        sendWasQuietOpened = false
        sendWasQuietCold = false
        sentAtMs = 0L
        stuckReported = false
        failures = 0
        retryNotBeforeMs = 0L
        requestOutstanding = false
        minRetryAfterMs = 0L
        unreadableSinceMs = null
        lastInteractionMs = null
        sendWasContentOnly = false
        anchorRequeryUsed = false
        sendWasAnchorOpened = false
    }

    companion object {
        /**
         * 내용이 이만큼 조용해야 "조용한 화면"으로 본다(#427 2단계).
         *
         * 스스로 도는 화면은 이만큼 멈추지 못한다 — 2026-08-25 SM-S921N 실측에서 회전하는
         * 검색 화면(무조작 63초·65샘플)의 **최대 연속 정지가 3샘플, 약 2.3초**였다. 여유를
         * 두고 4초로 잡되 [STUCK_AFTER_MS]보다는 짧게 둔다 — 길면 문이 무장되기 전에 정체
         * 문구가 먼저 뜨는 것이 상시 순서가 된다(PR #445 리뷰).
         *
         * **샘플 수가 아니라 시간인 이유**는 [contentChangedAtMs]에 적었다.
         */
        const val QUIET_MS = 4_000L

        /**
         * 조용함이 연 재질의 뒤 이만큼은 조용함을 다시 요구하지 않는다(연속 조작, PR #445 리뷰).
         *
         * 코레일 인원 선택처럼 같은 버튼을 여러 번 누르는 화면이 근거다. 시니어가 한 번 누르고
         * 화면을 확인한 뒤 다시 누르는 간격을 덮되, **분 단위로 도는 숫자는 못 타게** 1분보다
         * 훨씬 짧게 잡는다 — 이 창이 초 단위 시계와 분 단위 시계를 가르는 자리이기도 하다.
         */
        const val QUIET_ACTIVITY_MS = 15_000L

        /**
         * 연속 창 없이 조용함만으로 열 수 있는 재질의 수(화면당, PR #445 리뷰).
         *
         * 분 단위로 도는 숫자를 조작과 가를 신호가 없어서 두는 천장이다. 무조작 화면의
         * 최악이 이 횟수의 모델 호출로 끝나고, 조작은 연속 창을 타므로 여기 걸리지 않는다.
         */
        const val QUIET_COLD_OPENS = 3

        /**
         * 조용함이 열 수 있는 재질의 **총량**(화면당, 연속 창을 탄 것 포함).
         *
         * [QUIET_COLD_OPENS]만으로는 폭주가 안 막힌다 — 연속 창이 스스로를 연장해 cold를
         * 우회하기 때문이다([quietOpens]). 여기가 그 최후 방벽이라 기준은 **호출량**이다:
         * 무료 티어가 15 RPM인데(`HANDOFF.md` §10) 화면 하나가 그 예산을 통째로 먹으면 안 된다.
         *
         * 조작 쪽 근거는 실측이다 — 2026-08-25 코레일 220샘플 세션의 조작이 **6회**였고, 그중
         * 한 화면에 몰린 것은 그보다 적다. 12면 그 두 배가 넘어 정상 조작이 여기 닿지 않는다.
         */
        const val QUIET_OPENS = 12

        /** 같은 시그니처가 이만큼 연속으로 나와야 "안정"으로 본다. */
        const val HOLD_SAMPLES = 2

        /**
         * 안내한 뒤 화면이 이만큼 그대로면 사용자에게 알린다.
         *
         * PoC의 `STALE_POLLS=2 × POLL_MS=3500`과 같은 값이다. `HANDOFF.md` §6이 못 박듯
         * **이 상수들은 "잘 동작한 값"이 아니라 첫 추정치 그대로**다. 실사용 로그로 다시 정할 것.
         */
        const val STUCK_AFTER_MS = 7_000L

        /**
         * label 없는 화면을 서버에 보내지 않고 기다릴 최대 시간. 정체와 같은 첫 추정치를 쓰되,
         * 이 시간이 지나면 로컬 안내만 띄운다. 서버 호출은 여전히 0회다.
         */
        const val UNREADABLE_AFTER_MS = STUCK_AFTER_MS

        /**
         * 구조는 안정인데 내용만 계속 흔들리면 이 시간 뒤 홀드 없이 승격한다(#380).
         * 스켈레톤 로딩(label이 늦게 채워짐)은 이보다 먼저 안정되고 marquee·광고만 남는
         * 경계로 골랐다 — 첫 추정치다. 실기기 픽스처로 다시 정할 것.
         */
        const val CONTENT_SETTLE_MS = 3_000L

        /**
         * 이 시간 안의 클릭 이벤트가 있어야 내용만의 변화를 사용자 유발로 본다(#380).
         * 조작 → 화면 반영 → settle 샘플(350ms)의 한 사이클을 여유 있게 덮는 첫 추정치다.
         *
         * **[CONTENT_SETTLE_MS]보다 길어야 한다.** 조작은 승격 시계를 되감으므로, 이 창이 더
         * 짧으면 승격이 열리는 시점에 인과 창이 이미 닫혀 있어 재질의가 영영 안 나간다.
         * 두 값 사이의 구간이 "사용자가 만든 내용 변화를 묻는" 창이다.
         */
        const val INTERACTION_WINDOW_MS = 5_000L

        /** 첫 재시도까지 기다리는 시간. 이후 실패마다 두 배. */
        const val RETRY_BASE_MS = 2_000L

        /** 서버가 오래 죽어 있어도 이 간격보다 자주 두드리지 않는다. */
        const val RETRY_MAX_MS = 30_000L

        private const val MAX_BACKOFF_SHIFT = 16
    }
}

/** WAIT와 UNREADABLE은 화면 정체성을 확정하지 못한 상태라 보존 안내의 재표시·폐기 근거가 아니다. */
internal fun ScreenStabilityTracker.Decision.isStableForGuideRedisplay(): Boolean = when (this) {
    ScreenStabilityTracker.Decision.WAIT,
    ScreenStabilityTracker.Decision.UNREADABLE,
    -> false

    ScreenStabilityTracker.Decision.SEND,
    ScreenStabilityTracker.Decision.STUCK,
    ScreenStabilityTracker.Decision.IDLE,
    -> true
}
