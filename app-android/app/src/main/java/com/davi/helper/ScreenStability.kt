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
 * - [structure] 뼈대: package·페이지·IME·요소 수·순서·행동 상태. label과 **role은 뺀다**(#456).
 *   화면 이동·팝업 등장·토글처럼 **지금 안내를 무효화하는 변화**는 전부 이 축에 나타난다.
 *   안내의 화면 소속(`GuideDisplayState`)·늦은 응답 폐기·스텝 `screen_changed`도 이 축으로
 *   판정한다 — 내용까지 대조하면 배너가 도는 사이 도착한 정상 응답을 버린다.
 * - [content] 내용: 요소 label 열(+창 제목). 구조가 그대로인 채 여기만 바뀌는 변화는
 *   광고·marquee 같은 자기발생이거나(무시), 균일 목록 스크롤처럼 사용자가 일으킨 것이다 —
 *   그 구분은 내용이 아니라 **인과**(조작 이벤트 관측)로 한다. [ScreenStabilityTracker] 참고.
 */
data class ScreenSig(val structure: String, val content: String)

object ScreenSignature {

    /** 시계·배터리% 같은 volatile 숫자를 지운다(`HANDOFF.md` §4 — 홀드의 근간). */
    private val DIGITS = Regex("\\d+")

    /**
     * 요소의 **좌표와 node id는 넣지 않는다.** 전환 애니메이션 중 좌표가 미세하게 흔들리고,
     * node id는 직렬화 순서에서 생기는 값이라 화면의 정체성이 아니다.
     *
     * **`role`도 넣지 않는다(#456).** 광고 캐러샐이 같은 슬롯의 위젯 종류를 소재마다 바꾼다 —
     * 이미지 한 장짜리 소재는 `ImageView`, 텍스트 조합 소재는 `View`다. 2026-08-25 SM-S921N
     * 배민 홈 실측: 무조작 90초에 `SEND` 16회가 나갔고 그 16회의 구조 차이는 **전부 슬롯 하나의
     * `role`**이었다(앞 8플래그까지 동일). 응답의 절반은 `screen_changed`로 폐기됐다.
     *
     * 빼도 되는 이유는 role이 담던 구분이 대부분 **이미 플래그에 있어서**다 — 눌림은
     * `clickable`, 입력칸은 `editable`, 스위치·체크는 `checkState`, 선택은 `selected`가
     * 각각 담는다. role의 남은 판별력은 컨테이너 클래스 이름이고 그것이 바로 회전이 흔드는
     * 자리다. **role은 서버 프롬프트에는 그대로 나간다** — 여기서 빼는 것은 기기 로컬의
     * "같은 화면인가" 판정뿐이다.
     *
     * 요소 종류만 바뀌고 수·순서·플래그·label이 전부 그대로인 전환은 이제 같은 화면으로 접힌다.
     * 그런 전환이 실재하면 여기가 아니라 픽스처로 잡는다 — 산문으로 되돌리지 말 것.
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
                // label에 입력 원문도 토글 상태도 안 들어가므로, 이 자리가 유일한 변화 신호다.
                append(state(element.enabled, on = 'a', off = 'x'))
                append(checkState(element.checkState))
                append(state(element.selected, on = 's', off = 'n'))
                append(state(element.showingHintText, on = 'h', off = 'v'))
                // 모달이 뜨고 걷히면 같은 요소의 가림 여부가 뒤집힌다. 보통은 요소 수도 함께
                // 변해 서명이 어차피 바뀌지만, 투명 오버레이가 내용을 얻는 경우처럼 구조가
                // 그대로인 전환도 있어 상태로 남긴다(#271과 같은 결).
                append(state(element.obscured, on = 'o', off = 'p'))
                append(';')
            }
        }
        val content = buildString {
            // 제목은 label과 같은 취급이다. volatile 숫자를 지우고(안 읽은 메일 개수 같은 것),
            // 이 시그니처에는 포함한다 — 원문이 기기를 떠나지 않아서다. 서버 서명은 label처럼
            // 제목도 뺀다(#288 리뷰). 두 서명의 규칙이 다른 것은 그 경계 때문이다.
            // label과 같은 취급이므로 축도 내용이다(#380).
            //
            // **이 축의 salt 해시는 기기를 떠난다**(#410). 원문이 아니라 해시이고 salt가
            // 세션마다 새로 생겨 세션 밖 대조가 성립하지 않으므로 지금은 안전하다. 다만
            // "내용 축은 어차피 기기 안"으로 읽고 여기에 무언가를 더 넣으면 그때부터 그
            // 값이 해시로 나간다 — 넣기 전에 그것이 나가도 되는 값인지 본다.
            append(screen.windowTitle?.let { DIGITS.replace(it, "#") } ?: "-").append('|')
            screen.elements.forEach { element ->
                append(stableLabel(element))
                append(';')
            }
        }
        return ScreenSig(structure, content)
    }

    /**
     * 안내 대상 슬롯의 label 대조(타깃 앵커, #380)용. 시그니처와 **같은 정규화**를 쓴다 —
     * 다른 정규화면 안 읽은 개수 숫자 같은 volatile 값이 앵커만 다르게 흔든다.
     */
    internal fun stableLabelOf(element: UiElement): String = stableLabel(element)

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

    /**
     * 일반 숫자는 계속 volatile 값으로 취급한다. 다만 serializer가 직접 조작 가능한 NumberPicker에서만
     * 보존하는 editable 짧은 숫자 label은 현재 선택 상태 자체이므로 숫자를 남긴다. 그래야 사용자가
     * 분 picker를 스크롤해 `00, 분`을 `30, 분`으로 바꿨을 때 새 화면으로 판단해 다시 요청한다.
     */
    private fun stableLabel(element: UiElement): String =
        if (
            element.editable &&
            element.role.endsWith("EditText") &&
            UiTreeSerializer.isShortNumericControlLabel(element.label)
        ) {
            element.label
        } else {
            DIGITS.replace(element.label, "#")
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
 * 정확히 그 경우뿐이다 — 구조가 다르면 구조 축이 재질의를 열고 강조도 새로 그려진다
 * (회전 복귀로 접히는 경우만 예외다, #456).
 *
 * 대조는 [ScreenSignature]와 **같은 정규화**로 한다. 원문 label로 비교하면 대상에 붙은
 * 시계·개수가 앵커를 매 초 흔들어 게이트가 무의미해진다.
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
 *   호출자가 요청을 막는 중일 때도([sendingBlocked]) 여기로 온다 — 보내지 못할 `SEND`를
 *   내면 [markSent]가 안 불려 판정이 영영 가라앉지 않는다(#456).
 *
 * "직전에 보낸 화면과 다르다"의 판정이 2계층이다(#380) — **구조가 바뀌면** 새 화면이고,
 * **내용만 바뀌면 인과가 있어야** 새 화면이다(최근 조작 이벤트 또는 안내 대상 슬롯의 변화).
 * 광고 배너처럼 스스로 도는 내용 변화는 어느 쪽도 아니라서 같은 화면으로 접힌다 — 그래야
 * 로테이션마다 재질의가 나가던 무한 호출이 끊긴다.
 *
 * **구조 축의 "무조건"에는 예외가 하나 있다(#456)** — 전에 물어본 구조로 되돌아오는 것이
 * 반복되면 회전으로 보고 접는다. 회전이 내용 축만 흔든다는 #380의 전제가 광고 소재에 따라
 * 깨지기 때문이다(`sentStructures` 참고).
 *
 * 상태는 메인 스레드에서만 만진다. 응답이 다른 스레드로 오므로 [markFailed]는 반드시 메인으로 넘겨 부른다.
 */
class ScreenStabilityTracker(
    private val holdSamples: Int = HOLD_SAMPLES,
    private val stuckAfterMs: Long = STUCK_AFTER_MS,
    private val unreadableAfterMs: Long = UNREADABLE_AFTER_MS,
    private val contentSettleMs: Long = CONTENT_SETTLE_MS,
    private val interactionWindowMs: Long = INTERACTION_WINDOW_MS,
) {

    enum class Decision { WAIT, SEND, STUCK, UNREADABLE, IDLE }

    private var lastSample: ScreenSig? = null
    private var heldSamples = 0
    private var structureStableSinceMs: Long? = null
    private var sentSignature: ScreenSig? = null
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

    /**
     * 최근 **실제로 물어본** 구조들(#456). 회전 감지의 유일한 근거다.
     *
     * `role` 제거(#456)는 배민 홈의 오늘 소재 조합을 끊지만 **필드별 대응이라 두더지잡기다** —
     * 같은 회전이 `role`·요소 수·`obscured` 셋을 함께 흔들었고, 그중 `role`만 요청을 만든 것은
     * 두 슬라이드의 **안정** 요소 수가 우연히 둘 다 40이었기 때문이다(과도기엔 41이었다).
     * 소재가 바뀌어 요소 수가 안정적으로 갈리면 같은 폭주가 그 축으로 재현된다.
     *
     * 그래서 필드를 고르지 않고 **되돌아옴**을 본다 — 진짜 화면 전환은 되돌아오지 않고,
     * 회전은 되돌아온다.
     */
    private val sentStructures = ArrayDeque<Pair<String, Long>>()

    fun onSample(
        signature: ScreenSig,
        nowMs: Long,
        hasLabeledElement: Boolean,
        guidedTargetChanged: Boolean = false,
        sendingBlocked: Boolean = false,
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
            val since = unreadableSinceMs ?: nowMs.also { unreadableSinceMs = it }
            return if (nowMs - since >= unreadableAfterMs) {
                Decision.UNREADABLE
            } else {
                Decision.WAIT
            }
        }
        unreadableSinceMs = null
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

        // 새 상황인가(#380). 구조 변화가 기본값이다 — 화면 이동·팝업·토글은 지금 안내를
        // 무효화한다(회전 복귀만 예외, #456). 내용만의 변화는 인과로 가른다: 최근 조작이
        // 관측됐거나(균일 목록 스크롤은
        // 구조가 같은 채 label만 밀린다), 안내가 가리키는 슬롯 자체가 바뀐 경우(목록 재정렬로
        // 강조가 엉뚱한 줄을 가리키게 되는 것)만 새 상황이고, 아니면 자기발생(광고)이다.
        val sent = sentSignature
        val contentOnly = sent != null &&
            signature.structure == sent.structure &&
            signature.content != sent.content
        // 앵커는 인과 게이트를 우회하지만 게이트와 달리 시간 상한이 없다. **진행 신호가
        // 있기 전까지 한 번만** 연다([anchorRequeryUsed]).
        val anchorOpened = contentOnly && guidedTargetChanged && !anchorRequeryUsed
        // 구조가 바뀌었는데 **전에 물어본 구조로 되돌아온** 것인가(#456).
        val rotating = sent != null &&
            signature.structure != sent.structure &&
            !interactedRecently(nowMs) &&
            returnsSeen(signature.structure, nowMs) >= ROTATION_RETURNS
        val novel = when {
            sent == null -> true
            signature.structure != sent.structure -> !rotating
            !contentOnly -> false
            else -> anchorOpened || interactedRecently(nowMs)
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
        if (novel) {
            // 진행 중인 요청이 있으면 보내지 않는다. 여기서 SEND를 내면 호출자가 "보냈다"고
            // 기록하는데 실제 요청은 in-flight 가드에 막혀 나가지 않는다 — 그 화면의 안내가
            // 영영 오지 않는다(PR #61 리뷰 P1). 응답이 끝나면 [markFinished]가 다시 열어 준다.
            //
            // [sendingBlocked]도 같은 이유로 여기서 받는다(#456). 호출자가 확인 대기(#139)로
            // 요청을 막는 동안 `SEND`를 내면 [markSent]가 불리지 않아 [sentSignature]가 영영
            // 낡은 채로 남고, **모든 샘플이 다시 `novel`이 되어 폴링 주기마다 `SEND`가
            // 반복된다** — 2026-08-25 SM-S921N에서 중지 칩을 누른 뒤 7분 30초 동안
            // 1.3초 간격으로 이 줄이 찍히는 것을 실측했다. 요청은 안 나가지만 결정이 영영
            // 가라앉지 않아 화면이 정말 그대로인지를 아무도 못 본다.
            if (requestOutstanding || sendingBlocked) return Decision.IDLE
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
        if (sentSignature != null && signature.structure != sentSignature?.structure) {
            anchorRequeryUsed = false
        }
        if (sendWasAnchorOpened) anchorRequeryUsed = true
        rememberSentStructure(signature.structure, nowMs)
        sentSignature = signature
        sentAtMs = nowMs
        stuckReported = false
        requestOutstanding = true
    }

    /**
     * 늦은 응답을 **내용 축 근거로** 버렸다(#380). 그 화면을 보낸 적 없는 것으로 되돌린다.
     *
     * 구조 축 폐기(`screen_changed`)에는 이 처리가 필요 없다 — 구조가 달라 다음 샘플이 대개
     * 새 상황이다. **회전 복귀로 접히는 경우(#456)는 예외인데 그것이 옳다** — 폐기의 원인이
     * 화면 이동이 아니라 배너 회전이었다는 뜻이고, 다시 물어도 같은 화면이다. 그때 안내가
     * 비어 있으면 정체 판정(7초)이 받는다.
     *
     * 내용 축 폐기는 다르다: 버린 근거가 "요청~응답 사이 조작"인데 그 응답이
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
     * 안내 대상 앱에서 **클릭** 이벤트가 관측됐다(#380). 내용만의 변화를 사용자 유발로
     * 인정하는 유일한 근거다.
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

    /**
     * [structure]를 최근 [ROTATION_WINDOW_MS] 안에 **물어본 횟수**. 회전 감지의 세는 자리다.
     *
     * **왜 1회가 아니라 [ROTATION_RETURNS]회인가.** A→B→A까지는 회전과 뒤로 가기가
     * 원리적으로 구분되지 않는다 — 보낸 구조 열이 `[A, B]`이고 A가 다시 오는 것까지 똑같다.
     * 구분은 **네 번째에** 생긴다: 회전은 계속 돌고 복귀는 거기서 멈춘다. 첫 복귀에 접으면
     * 뒤로 가서 돌아온 화면의 안내가 안 나오고, 클릭 이벤트를 안 쏘는 앱에서는 인과 게이트도
     * 그것을 못 살린다(#394). 그래서 접는 자리를 두 번째 복귀로 둔다 — 회전하는 화면의
     * 요청은 **화면당 4회에서 멈추고**, 지금은 무한이다.
     */
    private fun returnsSeen(structure: String, nowMs: Long): Int =
        sentStructures.count { (sent, at) -> sent == structure && nowMs - at <= ROTATION_WINDOW_MS }

    /**
     * 이력에 **방문 하나**를 적는다. 창을 벗어난 것은 버리고, 상한은 폭주 화면의 무한 증가를 막는다.
     *
     * **직전과 같은 구조면 시각만 갱신한다.** 이력이 세려는 것은 전송 횟수가 아니라 "몇 번째로
     * 이 화면에 와 있는가"다. 같은 화면을 다시 묻는 경로가 여럿이라(실패 재시도는 [markFailed]가
     * [sentSignature]를 비워 곧바로 `novel`이 되고, 눌림 확인 재질의(#319)와 앵커 재질의(#380)도
     * 화면을 옮기지 않은 채 [markSent]를 다시 부른다) 그대로 쌓으면 **한 번도 떠난 적 없는
     * 화면이 이력에 두 줄**로 남는다. 그러면 [returnsSeen]이 첫 복귀에서 이미
     * [ROTATION_RETURNS]에 닿아, 뒤로 가기를 접지 않겠다는 [returnsSeen]의 계약이 깨진다 —
     * 요청 하나가 실패한 화면으로 되돌아오기만 해도 안내가 안 나가고 7초 뒤 거짓 정체 문구가 뜬다.
     */
    private fun rememberSentStructure(structure: String, nowMs: Long) {
        if (sentStructures.lastOrNull()?.first == structure) {
            sentStructures.removeLast()
        }
        sentStructures.addLast(structure to nowMs)
        while (
            sentStructures.size > MAX_SENT_STRUCTURES ||
            (sentStructures.isNotEmpty() && nowMs - sentStructures.first().second > ROTATION_WINDOW_MS)
        ) {
            sentStructures.removeFirst()
        }
    }

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
     */
    fun markResumed(nowMs: Long, requery: Boolean) {
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
        sentStructures.clear()
    }

    companion object {
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

        /**
         * 회전 감지가 이력을 보는 창(#456). **첫 추정치다.**
         *
         * **무엇을 전제한 값인가**: 구조 둘이 교대하는 회전(실측 주기 5.8초)에서
         * [ROTATION_RETURNS]회째 복귀까지 약 23초가 걸리고, 그것이 창 안에 남아야 한다.
         * **구조가 셋 이상 도는 회전은 이 창을 넘긴다** — 서로 다른 구조가 N개면 2회 복귀에
         * 약 `5.8 × 2N`초가 필요해 N이 3만 돼도 35초다. 그런 화면에서는 접기가 아예 발화하지
         * 않고 폭주가 남는다. 실측이 나오면 창이 아니라 상한으로 푸는 쪽을 먼저 본다.
         *
         * 길수록 회전을 잘 잡지만, 사용자가 두 화면을 오래 오가는 정상 왕복까지 접을 수 있다.
         * 조작이 관측되면 그 왕복은 접히지 않는데(`interactedRecently`), **이 안전판은 이
         * 수정이 겨냥한 앱에서 서지 않는다** — 근거가 클릭 이벤트뿐이고 배민은 세션 전체에서
         * 클릭을 0건 낸다(#394). 그 앱에서 오탐을 실제로 막는 것은 안전판이 아니라 창의 길이다:
         * 시니어가 두 화면을 30초 안에 네 번 오가기는 어렵다는 가정이고, 그 가정이 이 상수의
         * 진짜 근거다.
         */
        const val ROTATION_WINDOW_MS = 30_000L

        /**
         * 같은 구조로 이만큼 되돌아오면 회전으로 본다. **1이면 뒤로 가기와 구분되지 않는다** —
         * 근거는 `returnsSeen`의 주석에 있다.
         */
        const val ROTATION_RETURNS = 2

        /** 회전 감지 이력의 길이 상한. 창이 이미 거르지만 폭주 화면에서 무한히 늘지 않게 한다. */
        private const val MAX_SENT_STRUCTURES = 8

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
