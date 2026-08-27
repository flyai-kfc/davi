package com.davi.helper

import android.content.Context
import android.graphics.Rect
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager

/**
 * 조작 오버레이 — 안내창과 칩이 사는 창. 표시 오버레이와 달리 **터치를 받는다**(#126).
 *
 * 창은 안내창 사각형 크기로 잡히고 위치도 거기서 온다. 좌표는 **화면 좌표**로 받아 그대로
 * 창 x·y에 넣는다 — 이 창은 크기가 지정된 창이라 부모 프레임이 `[0,0]`이다. 표시 오버레이의
 * 로컬 좌표를 받던 이전 구현은 그 창의 당시 부모 프레임(`[0,103]`)만큼 안내창을 위로 밀었다
 * (#139 `dumpsys window` 실측 — #222 뒤 API 30+에서는 표시 창도 `[0,0]`이지만 규약은
 * 그대로다, `HighlightOverlayView.GuideBoxListener` 참고).
 *
 * 컨트롤 행에는 **한 번에 하나**만 보인다 — 평소에는 중지 칩, 완료 확인 중에는 확인쌍(#139).
 *
 * **보여줄 것이 없으면 창을 걷는다.** 표시 오버레이는 비어 있어도 `FLAG_NOT_TOUCHABLE`이라
 * 무해하지만, 이 창은 남아 있는 동안 계속 터치를 가져간다.
 */
class ControlOverlay(
    private val context: Context,
    private val windowManager: WindowManager,
    /** 칩을 눌렀을 때. 세션을 어떻게 할지는 호출자 몫이다 — 이 창은 무엇이 끝나는지 모른다. */
    private val callbacks: OverlayController.Callbacks,
) {

    private var root: View? = null
    private var box: GuideBoxView? = null
    private var readingDots: LoadingDotsView? = null
    private var stopChip: View? = null
    private var confirmPair: View? = null
    private var inputFillButton: View? = null

    /**
     * 지금 컨트롤 행에 앉아 있는 확인의 종류. 확인쌍 리스너가 이 값을 실어 콜백에 넘긴다 —
     * 서비스가 같은 상태를 따로 들면 두 벌이 어긋난다.
     */
    private var kind: GuideDisplayState.ConfirmKind? = null

    /**
     * 지금 창이 「읽는 중」 문구를 들고 있는가(#450). 다시 듣기 탭을 그 구간에만 막는다 —
     * 그때 창에 보이는 문장은 「화면을 읽고 있어요」인데(#229·#295) 낭독이 들고 있는 것은
     * 그 앞 안내라, 탭하면 **화면에 없는 문장**이 나온다.
     */
    private var reading = false

    /**
     * 직전에 창에 실제로 넣은 자리. 같은 자리면 [show]가 `updateViewLayout`을 건너뛴다.
     *
     * 아끼려는 것은 CPU가 아니라 **우리가 우리를 깨우는 일**이다 — 창을 건드릴 때마다
     * `TYPE_WINDOWS_CHANGED`가 뜨고 그게 샘플 예약에 걸린다([GuideAccessibilityService]의
     * `isInterestingEvent`). 재표시·같은 안내 재요청·오류 문구 반복처럼 자리가 그대로인
     * 호출이 흔하다.
     *
     * [root]가 null이 되면 함께 무의미해지므로 따로 리셋하지 않는다 — 창이 없으면 어차피
     * [create]가 자리를 새로 넣는다.
     */
    private var appliedX = Int.MIN_VALUE
    private var appliedY = Int.MIN_VALUE
    private var appliedWidth = 0
    private var appliedHeight = 0

    /**
     * 안내창을 [rect] 자리에 [lines]로 띄운다. 창이 없으면 만들고, 있으면 옮긴다.
     *
     * [confirm]이 있으면 컨트롤 행이 확인쌍이 된다(GD-3·GD-4). 안내창 자체는 같은 창이라
     * 확인으로 넘어갈 때 창을 다시 만들지 않는다.
     *
     * [waiting]이면 완료 재확인 백오프 대기다(#480) — 점 셋을 **느린 리듬**으로 켠다.
     * [reading]과 배타이고 읽는 중이 이긴다(둘이 동시에 참일 일은 없다 —
     * [ReadingBorder.isReading]이 백오프에서 false다).
     */
    fun show(
        rect: Rect,
        lines: List<String>,
        confirm: GuideDisplayState.ConfirmKind? = null,
        reading: Boolean = false,
        reserved: Float = 0f,
        inputText: String? = null,
        waiting: Boolean = false,
    ) {
        this.reading = reading
        val current = root
        if (current == null) {
            if (create(rect) != null) {
                box?.setReading(reading)
                box?.setReservedHeight(reserved)
                box?.setLines(lines)
                applyMode(confirm)
                applyDots(reading, waiting)
                applyInputFill(inputText, confirm, reading || waiting)
            }
            return
        }
        box?.setReading(reading)
        box?.setReservedHeight(reserved)
        box?.setLines(lines)
        applyMode(confirm)
        applyDots(reading, waiting)
        applyInputFill(inputText, confirm, reading || waiting)
        if (isApplied(rect)) return
        runCatching {
            windowManager.updateViewLayout(current, params(rect))
            remember(rect)
        }.onFailure { Log.w(TAG, "control overlay: updateViewLayout 실패", it) }
    }

    /** 창을 걷는다. 안내가 사라졌거나 서비스가 내려갈 때. */
    fun hide() {
        root?.let {
            runCatching { windowManager.removeView(it) }
                .onFailure { e -> Log.w(TAG, "control overlay: removeView 실패", e) }
        }
        root = null
        box = null
        stopChip = null
        confirmPair = null
        inputFillButton = null
        readingDots = null
        kind = null
        reading = false
    }

    /**
     * 컨트롤 행의 점 셋을 켜고 끈다. 가시성만 바꾸면 된다 — GONE→VISIBLE이 곧 구동 조건
     * 전이라, 전이 훅이 지연·주기를 알아서 처음부터 되돌린다(#148 `onMotionLifecycleChanged`).
     * 대화 화면도 같은 방식이다.
     *
     * **같은 뷰가 두 상태를 그린다**(#480) — 읽는 중(GD-1·GD-2r)과 완료 재확인 대기다.
     * 리듬으로 가르고, 리듬은 **보이기 직전에** 갈아 끼운다. 순서가 뒤바뀌면 전이 훅이
     * 옛 주기로 한 프레임을 그린다.
     *
     * 둘이 함께 참이면 **읽는 중이 이긴다.** 실제로 겹치지는 않는다 —
     * [ReadingBorder.isReading]이 백오프에서 false이기 때문이고, 그 규칙이 바뀌면 여기가
     * 조용히 틀리는 게 아니라 읽는 중 쪽으로 붙는다.
     */
    private fun applyDots(reading: Boolean, waiting: Boolean) {
        val dots = readingDots ?: return
        val on = reading || waiting
        if (on) dots.setRhythm(slow = waiting && !reading)
        dots.visibility = if (on) View.VISIBLE else View.GONE
    }

    /**
     * 「바로 입력」 버튼(C21 · #405)을 켜고 끈다. 확인쌍·점 셋과 **같은 자리**(창 바닥
     * 컨트롤 행)를 쓰므로 그 둘이 있는 동안은 값이 있어도 숨긴다 — 확인은 답할 자리고, 읽는
     * 중의 안내는 이미 유효하지 않은 화면의 것이라 그 값을 채우게 두면 안 된다.
     *
     * [reading]에는 **대기(#480)도 접어서 온다** — 자리를 다투는 것은 점 셋이지 그 점이 무슨
     * 뜻인지가 아니다. 대기 문구에는 `input_text`가 없어 지금은 값으로도 걸리지 않는다.
     */
    private fun applyInputFill(
        inputText: String?,
        confirm: GuideDisplayState.ConfirmKind?,
        reading: Boolean,
    ) {
        val button = inputFillButton ?: return
        button.visibility =
            if (inputText != null && confirm == null && !reading) View.VISIBLE else View.GONE
    }

    /**
     * 컨트롤 행에 무엇을 앉힐지. 둘이 함께 보이지 않게 한쪽만 켠다.
     *
     * 확인쌍은 아이콘 전용이라 화면이 달라도 그림이 같다. **뜻은 다르므로**
     * `contentDescription`을 종류마다 갈아 끼운다 — 안 갈면 TalkBack이 종료 확인에서
     * "네, 다 됐어요"를 읽는다. 눈에 보이는 라벨은 두지 않는다 — ×/○ 두 그림이면 서고, 글자를 얹으면 본문과 같은 말을 두 번 한다.
     */
    private fun applyMode(confirm: GuideDisplayState.ConfirmKind?) {
        kind = confirm
        stopChip?.visibility = if (confirm != null) View.GONE else View.VISIBLE
        // 칩이 숨으면 창이 그리는 흰 면도 함께 숨는다 — 안 그러면 빈 곡면만 남는다.
        box?.setCloseVisible(confirm == null)
        confirmPair?.visibility = if (confirm != null) View.VISIBLE else View.GONE
        val pair = confirmPair ?: return
        pair.findViewById<View>(R.id.btn_confirm_accept).contentDescription =
            context.getString(
                when (confirm) {
                    GuideDisplayState.ConfirmKind.QUIT -> R.string.overlay_quit_accept_desc
                    else -> R.string.overlay_confirm_accept_desc
                },
            )
        pair.findViewById<View>(R.id.btn_confirm_reject).contentDescription =
            context.getString(
                when (confirm) {
                    GuideDisplayState.ConfirmKind.QUIT -> R.string.overlay_quit_reject_desc
                    else -> R.string.overlay_confirm_reject_desc
                },
            )
    }

    private fun isApplied(rect: Rect) =
        rect.left == appliedX && rect.top == appliedY &&
            rect.width() == appliedWidth && rect.height() == appliedHeight

    private fun remember(rect: Rect) {
        appliedX = rect.left
        appliedY = rect.top
        appliedWidth = rect.width()
        appliedHeight = rect.height()
    }

    private fun create(rect: Rect): View? {
        // 서비스 Context에는 테마가 없다. 테마 속성을 참조하는 리소스가 하나라도 끼면 inflate가 터진다.
        val themed = ContextThemeWrapper(context, R.style.Theme_Davi)
        val created = LayoutInflater.from(themed).inflate(R.layout.overlay_control, null)
        created.findViewById<View>(R.id.btn_stop_chip)
            .setOnClickListener { callbacks.onStopRequested() }

        created.findViewById<View>(R.id.btn_input_fill)
            .setOnClickListener { callbacks.onInputFillRequested() }

        // **안내창 면 전체가 다시 듣기 탭이다**(#450). 칩을 하나 더 두지 않은 이유는 자리다 —
        // 컨트롤 행에 얹으면 창이 커져 안내 대상 앱을 그만큼 더 가린다. 이 면은 **이미** 이 창이
        // 터치를 삼키던 자리라([GuideBoxView]) 아래 앱에서 새로 뺏는 것이 없고, 강조 사각형은
        // 표시 오버레이에 남아 계속 통과하므로 "안내 대상을 누른 것"과 헷갈릴 자리도 아니다.
        // 중지 칩·확인쌍·「바로 입력」은 이 뷰 **위에** 얹혀 있어 그쪽 탭을 뺏지 않는다.
        created.findViewById<View>(R.id.guide_box)
            .setOnClickListener { if (!reading) callbacks.onSpeechRepeatRequested() }

        val pair = created.findViewById<View>(R.id.confirm_pair)
        // 확인쌍은 대화 화면과 공유하는 컴포넌트라 XML 라벨이 그 화면 문구다. 오버레이에서는 뜻이
        // 달라 [applyMode]가 덮는다 — 안 덮으면 TalkBack이 "이대로 안내 시작"을 읽는다.
        // 응답에 [kind]를 실어 보낸다. 확인쌍이 떠 있는 동안의 종류는 이 창이 알고 있는 값이다.
        pair.findViewById<View>(R.id.btn_confirm_accept).setOnClickListener {
            kind?.let(callbacks::onConfirmAccept)
        }
        pair.findViewById<View>(R.id.btn_confirm_reject).setOnClickListener {
            kind?.let(callbacks::onConfirmReject)
        }

        return runCatching {
            windowManager.addView(created, params(rect))
            remember(rect)
            root = created
            box = created.findViewById(R.id.guide_box)
            stopChip = created.findViewById(R.id.btn_stop_chip)
            inputFillButton = created.findViewById(R.id.btn_input_fill)
            readingDots = created.findViewById(R.id.reading_dots)
            confirmPair = pair
            created
        }.onFailure { Log.e(TAG, "control overlay: addView 실패", it) }.getOrNull()
    }

    private fun params(rect: Rect) = OverlayWindowParams.create(
        touchable = true,
        width = rect.width(),
        height = rect.height(),
    ).apply {
        x = rect.left
        y = rect.top
    }

    private companion object {
        const val TAG = GuideAccessibilityService.TAG
    }
}
