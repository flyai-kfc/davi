package com.davi.helper

/**
 * 화면이 "진짜" 바뀌었는지를 시간 축에서 판정한다.
 *
 * #32(덜 그려진 화면으로 요청이 나감)와 #39(이벤트가 안 와서 안내가 멈춤)는 **같은 기계의 양쪽 끝**이다.
 * 둘 다 필요한 부품이 같아서 한자리에 둔다 — 시그니처, 홀드, 정체 판정.
 *
 * 안드로이드 API에 기대지 않아 JVM 단위 테스트로 검증한다. 시각은 호출자가 넣는다.
 */
object ScreenSignature {

    /** 시계·배터리% 같은 volatile 숫자를 지운다(`HANDOFF.md` §4 — 홀드의 근간). */
    private val DIGITS = Regex("\\d+")

    /**
     * 요소의 **좌표와 node id는 넣지 않는다.** 전환 애니메이션 중 좌표가 미세하게 흔들리고,
     * node id는 직렬화 순서에서 생기는 값이라 화면의 정체성이 아니다.
     */
    fun of(screen: Screen): String = buildString {
        append(screen.packageName).append('|')
        // 제목은 label과 같은 취급이다. volatile 숫자를 지우고(안 읽은 메일 개수 같은 것),
        // 이 시그니처에는 포함한다 — **기기를 떠나지 않는 값이라서다.** 서버 서명은 label처럼
        // 제목도 뺀다(#288 리뷰). 두 서명의 규칙이 다른 것은 그 경계 때문이다.
        append(screen.windowTitle?.let { DIGITS.replace(it, "#") } ?: "-").append('|')
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
            append(state(element.checked, on = 'c', off = 'u'))
            append(state(element.selected, on = 's', off = 'n'))
            append(state(element.showingHintText, on = 'h', off = 'v'))
            append(element.role).append(':')
            append(stableLabel(element))
            append(';')
        }
    }

    /** 미관측(`null`)과 `false`를 다른 글자로 남긴다. 둘을 같은 글자로 접으면 세 상태가 죽는다. */
    private fun state(value: Boolean?, on: Char, off: Char): Char = when (value) {
        true -> on
        false -> off
        null -> '-'
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
 * 서버에 안내를 물을 만큼 화면 내용이 준비됐는지 판정한다.
 *
 * 창 제목은 요소 label이 아니다. 앱 전환 직후 제목·package만 잡히고 접근성 트리는 아직 비어 있는
 * 스플래시가 실재하므로, 실제 요소 label이 하나라도 있어야 준비된 화면으로 본다(#350).
 */
internal fun Screen.hasLabeledElement(): Boolean = elements.any { it.label.isNotBlank() }

/**
 * 샘플 하나가 들어올 때 무엇을 할지 정한다.
 *
 * - [Decision.WAIT] 아직 흔들린다. 요청을 보내지 않는다(#32).
 * - [Decision.SEND] 안정됐고 직전에 보낸 화면과 다르다. 서버에 물어본다.
 * - [Decision.STUCK] 안내를 했는데 화면이 그대로다. **서버를 다시 부르지 않고** 사용자에게 알린다(#39).
 * - [Decision.IDLE] 안정됐고 이미 처리한 화면이다. 아무것도 하지 않는다.
 *
 * 상태는 메인 스레드에서만 만진다. 응답이 다른 스레드로 오므로 [markFailed]는 반드시 메인으로 넘겨 부른다.
 */
class ScreenStabilityTracker(
    private val holdSamples: Int = HOLD_SAMPLES,
    private val stuckAfterMs: Long = STUCK_AFTER_MS,
) {

    enum class Decision { WAIT, SEND, STUCK, IDLE }

    private var lastSample: String? = null
    private var heldSamples = 0
    private var sentSignature: String? = null
    private var sentAtMs = 0L
    private var stuckReported = false
    private var failures = 0
    private var lastAttemptMs = 0L
    private var requestOutstanding = false

    fun onSample(
        signature: String,
        nowMs: Long,
        hasLabeledElement: Boolean = true,
    ): Decision {
        if (!hasLabeledElement) {
            // 빈 화면도 현재 화면으로는 관측한다. 그래야 직전 화면의 요청이 늦게 도착했을 때
            // currentSignature 대조가 그 응답을 버린다. 다만 몇 번 반복돼도 안정 화면으로
            // 승격하지 않는다 — 접근성 트리가 아직 채워지는 중인 실측이 있다(#350).
            lastSample = signature
            heldSamples = 0
            return Decision.WAIT
        }
        if (signature != lastSample) {
            lastSample = signature
            heldSamples = 1
            return Decision.WAIT
        }
        heldSamples++
        if (heldSamples < holdSamples) return Decision.WAIT

        if (signature != sentSignature) {
            // 진행 중인 요청이 있으면 보내지 않는다. 여기서 SEND를 내면 호출자가 "보냈다"고
            // 기록하는데 실제 요청은 in-flight 가드에 막혀 나가지 않는다 — 그 화면의 안내가
            // 영영 오지 않는다(PR #61 리뷰 P1). 응답이 끝나면 [markFinished]가 다시 열어 준다.
            if (requestOutstanding) return Decision.IDLE
            // 실패한 화면을 폴링 주기마다 다시 때리면 서버가 죽어 있을 때 초당 한 번씩 두드린다.
            // 무료 티어는 15 RPM이다(HANDOFF §10). 실기기에서 실제로 이 루프를 만들어 봤다.
            if (failures > 0 && nowMs - lastAttemptMs < backoffMs()) return Decision.IDLE
            return Decision.SEND
        }

        // 응답을 기다리는 중이면 멈춘 게 아니다. 느린 응답을 정체로 말하면 사용자를 헷갈리게 한다.
        if (requestOutstanding) return Decision.IDLE

        // 같은 화면을 다시 물어보지 않는다. 무료 티어 15 RPM이라 폴링이 곧 호출량이다(#39).
        if (!stuckReported && nowMs - sentAtMs >= stuckAfterMs) {
            stuckReported = true
            return Decision.STUCK
        }
        return Decision.IDLE
    }

    /** **요청이 실제로 나갔을 때만** 부른다. 보낸 셈 치면 그 화면의 안내가 영영 오지 않는다. */
    fun markSent(signature: String, nowMs: Long) {
        sentSignature = signature
        sentAtMs = nowMs
        lastAttemptMs = nowMs
        stuckReported = false
        requestOutstanding = true
    }

    /** 성공·실패·폐기와 무관하게 요청이 끝나면 부른다. 다음 화면이 다시 나갈 수 있게 연다. */
    fun markFinished() {
        requestOutstanding = false
    }

    /** 응답이 실제로 화면에 반영되면 백오프를 푼다. */
    fun markSucceeded() {
        failures = 0
    }

    /**
     * 마지막으로 뜬 샘플의 시그니처. 응답이 도착했을 때 **그 사이 화면이 바뀌었는지** 보는 데 쓴다.
     * 세대(generation)만 보면 같은 세션 안에서 A의 안내가 B 화면 위에 붙는다(PR #61 리뷰 P1).
     */
    fun currentSignature(): String? = lastSample

    /**
     * 요청이 실패하면 보낸 적 없는 것으로 되돌려 다시 시도하게 한다. 그러지 않으면 네트워크가
     * 한 번 끊긴 화면에서 안내가 영영 오지 않는다 — 이벤트 기반일 때는 다음 이벤트가 재시도를 해줬다.
     *
     * 대신 재시도는 점점 뜸해진다. [backoffMs] 참고.
     */
    fun markFailed() {
        failures++
        sentSignature = null
        sentAtMs = 0L
        stuckReported = false
    }

    /**
     * 화면이 다시 켜졌다. **꺼져 있던 구간은 정체가 아니다** — 사용자가 볼 수도 조작할 수도
     * 없었다. 그대로 두면 [sentAtMs]가 그 구간을 세서 잠금 해제 첫 샘플이 곧바로
     * [Decision.STUCK]이 되고, 사용자는 되살아난 안내와 "한 번 더 해 주세요"를 같은 프레임에
     * 본다(PR #216 3차 리뷰). 타이머만 지금으로 다시 찍는다.
     *
     * [stuckReported]를 함께 내리는 것은 **반대 방향도 닫기 위해서다** — 꺼지기 전에 이미
     * 정체가 떴다면 [stuckContent]는 접미사를 붙인 사본만 돌려주고 원본에 되쓰지 않아,
     * 복원된 안내는 접미사 없는 원문이다. 다시 셀 수 있어야 그 화면에서 정체가 또 뜬다.
     *
     * [requery]면 그 화면을 **보낸 적 없는 것으로** 되돌린다. 꺼진 동안 도착한 응답을 버렸을 때
     * 쓴다 — 버리기만 하면 [sentSignature]가 남아 같은 화면에 새 요청이 영영 안 나간다.
     * **실패가 아니므로 백오프는 건드리지 않는다**([markFailed]와 다른 점이다).
     */
    fun markResumed(nowMs: Long, requery: Boolean) {
        sentAtMs = nowMs
        stuckReported = false
        if (requery) sentSignature = null
    }

    /**
     * 안내한 대상이 눌렸다(#319). 정체 타이머를 지금으로 다시 찍는다 — "안내 후 화면 그대로
     * 7초"는 사용자가 방금 조작했으면 **그 조작부터** 세야 맞다. [stuckReported]를 함께 내리는
     * 이유는 정체가 이미 보고된 뒤 눌림이 와도 다음 정체(와 눌림 확인 재질의)가 뜰 수 있어야
     * 해서다 — 래치를 그대로 두면 그 화면의 재질의가 영영 없다.
     *
     * [markResumed]와 기계가 같지만 의미가 다르다 — 그쪽은 "꺼진 구간은 정체가 아니다"이고
     * 여기는 "조작 이후만 정체다". requery 되돌림은 없다: 화면이 그대로면 [sentSignature]가
     * 유효한 게 맞고, 재질의 여부는 토큰(`GuideSession.consumePressRequery`)이 정한다.
     */
    fun markPressed(nowMs: Long) = markResumed(nowMs, requery = false)

    /** 연속 실패마다 두 배로 늘어나고 [RETRY_MAX_MS]에서 멈춘다. */
    fun backoffMs(): Long {
        if (failures <= 0) return 0L
        val shift = (failures - 1).coerceAtMost(MAX_BACKOFF_SHIFT)
        return (RETRY_BASE_MS shl shift).coerceAtMost(RETRY_MAX_MS)
    }

    /** 세션이 바뀌면 이전 세션의 화면 기억을 끌고 가지 않는다. */
    fun reset() {
        lastSample = null
        heldSamples = 0
        sentSignature = null
        sentAtMs = 0L
        stuckReported = false
        failures = 0
        lastAttemptMs = 0L
        requestOutstanding = false
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

        /** 첫 재시도까지 기다리는 시간. 이후 실패마다 두 배. */
        const val RETRY_BASE_MS = 2_000L

        /** 서버가 오래 죽어 있어도 이 간격보다 자주 두드리지 않는다. */
        const val RETRY_MAX_MS = 30_000L

        private const val MAX_BACKOFF_SHIFT = 16
    }
}
