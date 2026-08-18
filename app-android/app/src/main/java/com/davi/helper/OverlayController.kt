package com.davi.helper

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager

/**
 * 다른 앱 위에 뜨는 오버레이 **둘**을 소유한다(#126).
 *
 * - **표시 오버레이**([HighlightOverlayView]) — 전체 화면, `FLAG_NOT_TOUCHABLE`. 안내 하이라이트를
 *   그리고 안내창 자리도 여기서 계산한다.
 * - **조작 오버레이**([ControlOverlay]) — 안내창 크기, 터치를 받는다. 안내창과 칩이 산다.
 *
 * **중요한 플래그 세 가지** (HANDOFF §7, §3-1):
 * - `FLAG_LAYOUT_IN_SCREEN` — 없으면 접근성 `bounds`와 좌표계가 상태표시줄 높이만큼 어긋난다.
 * - `FLAG_NOT_TOUCHABLE` — **표시 오버레이에만** 준다. 강조 영역을 눌렀을 때 터치가 아래 앱으로
 *   통과해야 한다 — 우리는 **안내만** 하고 대신 조작하지 않는다(SPEC §2). 조작 오버레이는 이
 *   플래그가 없어 자기 영역의 터치를 받지만, 그 터치로 끝내는 것은 **우리 세션뿐**이다.
 * - `FLAG_NOT_FOCUSABLE` — 둘 다 준다. 키 입력·IME 포커스를 가로채면 아래 앱에 글자를 못 친다.
 *
 * 설정 앱(`com.android.settings`) 위에는 오버레이가 **아예 그려지지 않는다**(탭재킹 방지, 우회 불가).
 * 그 화면에서는 헤드업 알림으로 떨어져야 하지만 그건 다음 작업이다.
 */
class OverlayController(
    private val context: Context,
    callbacks: Callbacks,
) {

    /**
     * 안내창의 칩을 눌렀을 때. 전부 메인 스레드에서 불린다.
     *
     * 람다 셋이 아니라 인터페이스인 이유는 호출부다 — 생성자에 이름 없는 람다가 늘어서면
     * 어느 것이 어느 칩인지 서비스 쪽에서 읽히지 않는다.
     */
    interface Callbacks {
        /**
         * '그만 볼래요'. **여기서 끝내지 않는다** — 종료 확인(GD-4)을 띄워 되묻는다(#193).
         * 세션을 끝내는 것은 그 화면의 ○ 응답뿐이다.
         */
        fun onStopRequested()

        /** 확인의 ○. 완료 확인이면 "다 됐다", 종료 확인이면 "그만 보겠다" — 둘 다 세션을 끝낸다. */
        fun onConfirmAccept(kind: GuideDisplayState.ConfirmKind)

        /** 확인의 ×. 완료 확인이면 "아직", 종료 확인이면 "계속" — 둘 다 같은 세션으로 잇는다. */
        fun onConfirmReject(kind: GuideDisplayState.ConfirmKind)
    }

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var view: HighlightOverlayView? = null
    private val control = ControlOverlay(context, windowManager, callbacks)

    /** 지금 그리는 확인 화면의 종류(없으면 null). 자리 계산 콜백이 조작 오버레이에 그대로 넘긴다. */
    private var confirmKind: GuideDisplayState.ConfirmKind? = null

    /** 오버레이 권한이 있는가. 없으면 온보딩에서 켜야 한다. */
    fun canDraw(): Boolean = Settings.canDrawOverlays(context)

    /**
     * 강조할 영역(없으면 null)과 안내문 한 줄을 함께 그린다.
     *
     * [avoid]는 안내창이 가리지 말아야 할 영역이다(키보드·다이얼로그). 강조 대상은 여기 넣지
     * 않는다 — 뷰가 이미 절대 회피로 다룬다.
     *
     * [confirm]이 있으면 확인 화면이다 — 컨트롤 행이 확인쌍이 되고 종류에 맞는 크롬이 함께
     * 켜진다(완료 확인은 성공 tone 테두리, 종료 확인은 전면 스크림). 읽는 중 테두리와 달리
     * **안내창과 한 몸이라** [clear]가 같이 지운다.
     */
    fun showGuide(
        bounds: Rect?,
        instruction: String,
        avoid: List<GuideBoxLayout.Avoid> = emptyList(),
        actionType: ActionType? = null,
        direction: SwipeDirection? = null,
        confirm: GuideDisplayState.ConfirmKind? = null,
    ) {
        if (!canDraw()) {
            Log.w(TAG, "overlay: 권한 없음 — 온보딩에서 '다른 앱 위에 표시'를 켜야 한다")
            return
        }
        // 자리 계산 콜백이 setGuide 안에서 곧바로 도므로 종류를 먼저 세운다.
        confirmKind = confirm
        val target = view ?: createView() ?: return
        target.setConfirmChrome(confirm)
        target.setGuide(bounds, instruction, avoid, actionType, direction)
    }

    fun clear() {
        confirmKind = null
        view?.setConfirmChrome(null)
        view?.clearTarget()
    }

    /**
     * 읽는 중 테두리(GD-1)를 켜고 끈다. 안내와 **독립 레이어**라 [clear]가 이것까지 지우지
     * 않는다 — 끄는 것은 호출자 책임이다. 테두리는 **표시 오버레이**에 그려지므로 조작
     * 오버레이와 무관하다(#126).
     *
     * 끌 때는 창을 만들지 않는다. 세션 종료·화면 소실 경로가 이 함수로 false를 반복해서
     * 보내는데, 거기서 창이 생기면 안내가 한 번도 없던 화면에도 오버레이가 붙는다.
     */
    fun setReading(on: Boolean) {
        // 뷰가 이미 있으면 권한을 다시 묻지 않는다 — 샘플마다 도는 경로라 canDraw()의
        // AppOps 바인더 왕복이 접근성 처리와 같은 메인 스레드에 그대로 쌓인다.
        // 창이 붙어 있다는 것이 곧 권한이 있었다는 뜻이고, 권한이 사라지면 시스템이 창을 걷는다.
        view?.let {
            it.setReading(on)
            return
        }
        if (!on || !canDraw()) return
        createView()?.setReading(true)
    }

    /**
     * 서비스가 내려갈 때 창을 반드시 걷는다. 안 그러면 유령 오버레이가 남는다.
     *
     * **조작 오버레이는 여기서 따로 걷어야 한다.** 평소에는 [clear] → `clearTarget` → 콜백 null로
     * 저절로 내려가지만, 이 경로만 `clearTarget`을 거치지 않아 콜백이 오지 않는다.
     */
    fun destroy() {
        control.hide()
        view?.let {
            runCatching { windowManager.removeView(it) }
                .onFailure { e -> Log.w(TAG, "overlay: removeView 실패", e) }
        }
        view = null
    }

    private fun createView(): HighlightOverlayView? {
        val created = HighlightOverlayView(context)
        // addView 전에 물린다 — 창 크기가 처음 잡힐 때(onSizeChanged) 도는 첫 계산도 놓치지 않는다.
        created.guideBoxListener =
            HighlightOverlayView.GuideBoxListener { rect, lines, reading, reserved ->
                if (rect == null) {
                    control.hide()
                } else {
                    control.show(rect, lines, confirmKind, reading, reserved)
                }
            }
        return runCatching {
            windowManager.addView(created, OverlayWindowParams.create(touchable = false))
            view = created
            created
        }.onFailure { Log.e(TAG, "overlay: addView 실패", it) }.getOrNull()
    }

    private companion object {
        const val TAG = GuideAccessibilityService.TAG
    }
}

/**
 * 두 오버레이의 창 속성을 **한 곳에서** 만든다. 차이는 `FLAG_NOT_TOUCHABLE`과 크기뿐이다.
 *
 * **flags를 맞춰도 두 창의 부모 프레임은 같아지지 않는다**(#139 `dumpsys window` 실측) —
 * MATCH_PARENT인 표시 창은 부모가 `[0,103]`(상태표시줄 인셋)인데 크기가 지정된 조작 창은
 * `[0,0]`이었다. 그래서 창 사이에 좌표를 넘길 때는 **화면 좌표**를 쓴다. 이 대칭은 좌표
 * 보장이 아니라 **동작 대칭**(터치·포커스·인셋 정책)을 위한 것이다.
 *
 * **조작 창이 `[0,0]`이라는 가정의 검증된 범위**(#169, 2026-08-17·18 SM-S921N / One UI 8.5):
 * 런처 홈 · 시계 앱 · 펼친 셰이드 · 3버튼 내비에서 안내창이 계산된 자리에 그대로 앉았다.
 * 두 사이클에서 어긋남을 재현하지 못했다. 멀티윈도우·커버 디스플레이는 **대응하지 않는다** —
 * 커버 디스플레이가 검증 기기에 없고, 근거 없이 좌표를 흔드는 쪽이 위험하다(#139가 실측으로
 * 맞춰 놓은 자리다). 그 구성에서 어긋남이 실제로 보고되면 다시 연다.
 */
internal object OverlayWindowParams {

    fun create(
        touchable: Boolean,
        width: Int = WindowManager.LayoutParams.MATCH_PARENT,
        height: Int = WindowManager.LayoutParams.MATCH_PARENT,
    ): WindowManager.LayoutParams {
        @Suppress("DEPRECATION")
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE

        return WindowManager.LayoutParams(width, height, type, flags, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
    }
}
