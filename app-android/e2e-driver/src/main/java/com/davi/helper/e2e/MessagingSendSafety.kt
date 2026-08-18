package com.davi.helper.e2e

import java.util.Locale

/**
 * 문자 전송처럼 **기기 밖으로 나가면 되돌릴 수 없는** 최종 동작을 코드로 막는 순수 판정부.
 *
 * 왜 label 일치만으로 부족한가(#134):
 * - 모델이 고른 대상의 label이 "전송"이 아니어도, 그 대상의 **좌표가 전송 버튼 위**일 수 있다.
 *   시나리오가 하드코딩한 좌표도 레이아웃이 조금 바뀌면 옆 칸의 전송 버튼을 누른다.
 * - 실제로 오늘 실측에서 삼성 NumberPicker의 **선택된 칸 label이 빈 문자열**로 직렬화되는 것을
 *   확인했다. 전송 버튼도 같은 이유로 label이 빌 수 있고, 그러면 label 경계는 그냥 통과한다.
 *
 * 그래서 이 판정부는 **실행기가 실제로 누를 좌표**를 기준으로 본다. [GuidedActionExecutor]는
 * 대상 bounds의 중심을 누르므로, 안전 속성은 "그 중심점이 전송 컨트롤 위에 있으면 안 된다"로
 * 정확히 표현된다.
 *
 * Android API를 쓰지 않는다 — CI의 JVM 단위 테스트에서 매번 돌리기 위해서다. 화면을 읽어 이
 * 판정부에 넣는 어댑터만 기기 쪽에 둔다.
 */
object MessagingSendSafety {
    /** 모델 결정이 전송 컨트롤을 직접 가리켰다. */
    const val SEND_TARGET_BLOCKED = "sms_send_target_blocked"

    /** 대상은 전송 버튼이 아니지만 누를 좌표가 전송 컨트롤 위(또는 여백 안)다. */
    const val SEND_PROXIMITY_BLOCKED = "sms_send_proximity_blocked"

    /** 전송 컨트롤이 화면에 있는데 대상 좌표를 알 수 없어 안전을 증명할 수 없다. */
    const val SEND_TARGET_UNVERIFIABLE = "sms_send_target_unverifiable"

    /**
     * 전송 컨트롤이 **있어야 하는 단계**인데 하나도 식별하지 못했다(#211 리뷰).
     *
     * 전송 버튼의 text·contentDescription이 비었거나 예상 label과 다르면 우리는 그 버튼이 어디
     * 있는지 모른다. 그 상태에서 좌표 비교를 건너뛰고 통과시키면 **가장 확인이 필요한 순간**을
     * 안전하다고 판정하는 것이다.
     *
     * 수신자를 전용 회선으로 제한하는 것은 **원문 유출 방어**이지 "문자를 발송하지 않는다"는
     * 경계의 대체가 아니다. 둘은 다른 사고를 막는다.
     *
     * 다만 대화 목록처럼 전송 컨트롤이 아직 없는 화면까지 막으면 시나리오가 진행되지 않는다.
     * 그래서 "지금 전송 컨트롤을 볼 수 있어야 하는가"는 시나리오가 단계로 알려준다.
     */
    const val SEND_CONTROL_UNIDENTIFIED = "sms_send_control_unidentified"

    /**
     * 전송 컨트롤 사각형을 사방으로 넓히는 기본 여백(px).
     *
     * 삼성 메시지의 전송 버튼과 첨부 버튼은 세로로 붙어 있다. 레이아웃이 한 줄만 밀려도 중심점이
     * 이웃 칸으로 넘어가므로, 버튼 하나 크기에 못 미치는 이동은 여백 안에서 잡는다.
     * SM-S921N(1080x2340) 기준 약 4mm다. 더 좁히려면 근거를 남기고 시나리오에서 재정의한다.
     */
    const val DEFAULT_SAFETY_MARGIN_PX = 64

    /**
     * 전송 컨트롤로 취급할 label.
     *
     * 과하게 넓히면 "전송 옵션" 같은 설정 항목까지 막혀 시나리오가 진행되지 않는다. 정확히 이
     * 문자열일 때만 전송으로 본다. 넓은 그물은 label이 아니라 **좌표**가 담당한다.
     */
    val SEND_LABELS: Set<String> = setOf(
        "전송",
        "보내기",
        "메시지 전송",
        "전송 버튼",
        "send",
        "send message",
        "send sms",
    )

    /** 문자 전송 부작용이 있는 기본 메시지 앱. 시나리오가 추가할 수 있다. */
    val MESSAGING_PACKAGES: Set<String> = setOf(
        "com.samsung.android.messaging",
        "com.google.android.apps.messaging",
        "com.android.mms",
    )

    /** 화면 요소 하나. 앱이 서버로 보낸 snapshot과 기기 화면 스캔이 공유하는 최소 표현. */
    data class ScreenElement(
        val nodeId: String?,
        val label: String?,
        val bounds: Box?,
    )

    /** left/top/right/bottom 픽셀 사각형. `android.graphics.Rect`를 JVM 테스트에서 못 쓴다. */
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val centerX: Int get() = (left + right) / 2
        val centerY: Int get() = (top + bottom) / 2
        val isEmpty: Boolean get() = right <= left || bottom <= top

        fun inflated(margin: Int): Box =
            Box(left - margin, top - margin, right + margin, bottom + margin)

        fun contains(x: Int, y: Int): Boolean = x in left..right && y in top..bottom

        companion object {
            /** [left, top, right, bottom] 네 값일 때만 사각형으로 인정한다. */
            fun fromValues(values: IntArray?): Box? {
                if (values == null || values.size != 4) return null
                return Box(values[0], values[1], values[2], values[3]).takeUnless { it.isEmpty }
            }
        }
    }

    /**
     * **실제 탭을 주입하는** action 종류.
     *
     * `GuidedActionExecutor.type()`은 텍스트를 넣기 전에 `tap()`을 불러 대상 bounds 중심을 실제로
     * 누른다. 따라서 `TAP`만 검사하면 잘못된 bounds의 `TYPE`이 전송 버튼을 먼저 눌러버린다
     * (#211 리뷰). `SCROLL`은 `device.swipe`라 click을 주입하지 않으므로 제외한다 —
     * `device.click`을 부르는 경로는 `tap()` 하나뿐이고, 그것을 부르는 것이 이 둘이다.
     */
    val TAP_INJECTING_ACTIONS: Set<GuidedActionType> =
        setOf(GuidedActionType.TAP, GuidedActionType.TYPE)

    fun isSendLabel(label: String?): Boolean = normalize(label) in SEND_LABELS

    /**
     * 결정 하나를 실행해도 되는가. package·action 종류 판정까지 포함한 전체 규칙이다.
     *
     * 어댑터는 입력만 모으고 판단은 전부 여기서 한다 — 그래야 기기 없이 회귀를 고정할 수 있다.
     */
    fun decisionFailureCode(
        actionType: GuidedActionType,
        screenPackage: String,
        messagingPackages: Set<String>,
        targetBox: Box?,
        targetLabel: String?,
        sendBoxes: List<Box>,
        sendControlRequired: Boolean,
        safetyMarginPx: Int = DEFAULT_SAFETY_MARGIN_PX,
    ): String? {
        if (actionType !in TAP_INJECTING_ACTIONS) return null
        if (screenPackage !in messagingPackages) return null
        return tapFailureCode(
            targetBox = targetBox,
            targetLabel = targetLabel,
            sendBoxes = sendBoxes,
            sendControlRequired = sendControlRequired,
            safetyMarginPx = safetyMarginPx,
        )
    }

    /** 화면 요소 중 전송 컨트롤로 판정되는 것들의 사각형. */
    fun sendControlBoxes(elements: List<ScreenElement>): List<Box> =
        elements.filter { isSendLabel(it.label) }.mapNotNull { it.bounds }

    /**
     * 이 탭을 실행해도 되는가. 막아야 하면 실패 코드를, 안전하면 `null`을 돌려준다.
     *
     * @param targetBox 실행기가 누를 대상 사각형. 알 수 없으면 `null`.
     * @param targetLabel 모델이 고른 대상의 label.
     * @param sendBoxes 이 시점에 식별한 전송 컨트롤 사각형 전부(화면 snapshot + 기기 스캔 합집합).
     * @param sendControlRequired 지금이 전송 컨트롤을 **볼 수 있어야 하는 단계**인가.
     *   `true`인데 [sendBoxes]가 비면 통과시키지 않는다(#211 리뷰). 대화 목록처럼 아직 전송
     *   컨트롤이 없는 화면에서는 `false`를 준다 — 시나리오가 단계로 판단한다.
     */
    fun tapFailureCode(
        targetBox: Box?,
        targetLabel: String?,
        sendBoxes: List<Box>,
        sendControlRequired: Boolean,
        safetyMarginPx: Int = DEFAULT_SAFETY_MARGIN_PX,
    ): String? {
        require(safetyMarginPx >= 0) { "safety margin must not be negative" }
        if (isSendLabel(targetLabel)) return SEND_TARGET_BLOCKED

        if (sendBoxes.isEmpty()) {
            return if (sendControlRequired) SEND_CONTROL_UNIDENTIFIED else null
        }

        // 전송 컨트롤이 화면에 있는데 어디를 누를지 모르면 안전을 증명할 수 없다. 통과시키지 않는다.
        if (targetBox == null) return SEND_TARGET_UNVERIFIABLE

        return if (blocksTapPoint(targetBox.centerX, targetBox.centerY, sendBoxes, safetyMarginPx)) {
            SEND_PROXIMITY_BLOCKED
        } else {
            null
        }
    }

    /**
     * 이 좌표를 눌러도 되는가. 시나리오가 직접 좌표를 누를 때(준비·정리 단계)도 같은 규칙을 쓴다.
     *
     * 여백을 넓힌 전송 사각형 안에 점이 들어가면 막는다.
     */
    fun blocksTapPoint(
        x: Int,
        y: Int,
        sendBoxes: List<Box>,
        safetyMarginPx: Int = DEFAULT_SAFETY_MARGIN_PX,
    ): Boolean {
        require(safetyMarginPx >= 0) { "safety margin must not be negative" }
        return sendBoxes.any { it.inflated(safetyMarginPx).contains(x, y) }
    }

    private fun normalize(label: String?): String = label?.trim()?.lowercase(Locale.ROOT).orEmpty()
}
