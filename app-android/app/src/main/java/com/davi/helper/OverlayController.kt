package com.davi.helper

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
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
 * **창 타입은 `TYPE_ACCESSIBILITY_OVERLAY`다**(#56). 설정 앱이 탭재킹 방지로 숨기는 것은
 * `SYSTEM_ALERT_WINDOW` 계열이고 접근성 서비스가 띄우는 창은 그 대상이 아니다 — 설정 앱
 * 위에서도 강조·안내창·칩이 그려지고 터치 계약도 그대로다(2026-08-18 SM-S921N 실측).
 * 그래서 오버레이 불가 화면용 헤드업 폴백은 만들지 않는다.
 *
 * 이 타입을 쓰는 이상 **"안내만 한다"는 선택이 아니라 전제다** — 시스템이 가려 둔 자리에
 * 그리는 것이므로 `performAction` 금지(루트 `CLAUDE.md`)가 그 조건이다.
 *
 * **오버레이 권한 게이트는 없다**(#212). `SYSTEM_ALERT_WINDOW`를 아예 선언하지 않으므로
 * 그릴 수 있는지 물을 것이 없다 — 창 타입이 그 권한을 타지 않는다.
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

        /**
         * 「바로 입력」 버튼(C21 · #405). 무엇을 어디에 채울지는 서비스가 안다 — 이 창은
         * 버튼이 눌렸다는 사실만 전한다(중지 칩과 같은 분업).
         */
        fun onInputFillRequested()
    }

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var view: HighlightOverlayView? = null
    private val control = ControlOverlay(context, windowManager, callbacks)

    /** 지금 그리는 확인 화면의 종류(없으면 null). 자리 계산 콜백이 조작 오버레이에 그대로 넘긴다. */
    private var confirmKind: GuideDisplayState.ConfirmKind? = null

    /** 「바로 입력」 버튼이 채울 글자(없으면 null). [confirmKind]와 같은 경로로 조작 오버레이에 넘긴다. */
    private var inputText: String? = null

    /**
     * 강조할 영역(없으면 null)과 안내문 한 줄을 함께 그린다.
     *
     * [avoid]는 안내창이 가리지 말아야 할 영역이다(키보드·다이얼로그). 강조 대상은 여기 넣지
     * 않는다 — 뷰가 이미 절대 회피로 다룬다.
     *
     * [confirm]이 있으면 확인 화면이다 — 컨트롤 행이 확인쌍이 되고 종류에 맞는 크롬이 함께
     * 켜진다(완료 확인은 성공 tone 테두리, 종료 확인은 전면 스크림). 읽는 중 테두리와 달리
     * **안내창과 한 몸이라** [clear]가 같이 지운다.
     *
     * [targetSuppressed]는 강조·손 시연을 그리지 말라는 명시 신호다
     * ([GuideDisplayState.Content.targetSuppressed], PR #343 리뷰 P1).
     */
    fun showGuide(
        bounds: Rect?,
        instruction: String,
        avoid: List<GuideBoxLayout.Avoid> = emptyList(),
        actionType: ActionType? = null,
        direction: SwipeDirection? = null,
        confirm: GuideDisplayState.ConfirmKind? = null,
        targetSuppressed: Boolean = false,
        inputText: String? = null,
    ) {
        // 자리 계산 콜백이 setGuide 안에서 곧바로 도므로 종류를 먼저 세운다.
        confirmKind = confirm
        this.inputText = inputText
        val target = view ?: createView() ?: return
        target.setConfirmChrome(confirm)
        target.setGuide(bounds, instruction, avoid, actionType, direction, targetSuppressed, inputText)
    }

    fun clear() {
        confirmKind = null
        inputText = null
        view?.setConfirmChrome(null)
        view?.clearTarget()
    }

    /**
     * 안내한 대상을 눌렀다는 신호를 그 자리에 한 번 준다(#238).
     *
     * **창이 없으면 만들지 않는다** — [setReading]과 같은 이유다. 안내가 한 번도 없던 화면에
     * 파동만으로 오버레이가 붙으면 안 된다. 모션 끄기 판정은 뷰가 한다.
     */
    fun pressFeedback(bounds: Rect) {
        view?.pressFeedback(bounds)
    }

    /**
     * 읽는 중 테두리(GD-1·GD-2r)를 켜고 끈다. 안내와 **독립 레이어**라 [clear]가 이것까지 지우지
     * 않는다 — 끄는 것은 호출자 책임이다. 테두리는 **표시 오버레이**에 그려지므로 조작
     * 오버레이와 무관하다(#126).
     *
     * 끌 때는 창을 만들지 않는다. 세션 종료·화면 소실 경로가 이 함수로 false를 반복해서
     * 보내는데, 거기서 창이 생기면 안내가 한 번도 없던 화면에도 오버레이가 붙는다.
     */
    fun setReading(on: Boolean) {
        view?.let {
            it.setReading(on)
            return
        }
        if (!on) return
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
                    control.show(rect, lines, confirmKind, reading, reserved, inputText)
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
 * **표시 창 프레임의 이력** — 구 창 타입(`TYPE_APPLICATION_OVERLAY`)과 타입 교체(#56) 후
 * 재실측(2026-08-18) 모두 `frame=[0,103][1080,2340]`(SM-S921N `dumpsys window`, 상태표시줄
 * 인셋 반영)이었고, **#222가 컷아웃 모드를 ALWAYS로 올린 뒤 API 30+에서는 `[0,0]`이다**
 * (2026-08-21 실측 — 가장자리 선이 y=0에 붙었다). 크기가 지정된 조작 창은 내내 `[0,0]`이다.
 *
 * **두 창의 프레임이 지금 검증 기기에서 같은 것은 우연이지 보장이 아니다.** 프레임은 창마다
 * 시스템이 정하는 값이고(#139 실측 — flags를 맞춰도 같아지지 않았다), 컷아웃 게이트 밖인
 * API 28~29에서는 표시 창이 여전히 `[0,103]`이다(아래 `create`의 R 게이트). 그래서 창 사이에
 * 좌표를 넘길 때는 **화면 좌표**를 쓴다는 규약(#139)은 그대로다 — 로컬 좌표로 되돌리면
 * 프레임이 갈리는 구성에서 안내창이 그 차이만큼 밀린다. 이 대칭은 좌표 보장이 아니라
 * **동작 대칭**(터치·포커스·인셋 정책)을 위한 것이다.
 *
 * **조작 창이 `[0,0]`이라는 가정의 검증된 범위**(#169, 2026-08-17·18 SM-S921N / One UI 7.0):
 * 런처 홈 · 시계 앱 · 펼친 셰이드 · 3버튼 내비에서 안내창이 계산된 자리에 그대로 앉았다.
 * 두 사이클에서 어긋남을 재현하지 못했다. **타입 교체 후 재확인(2026-08-18)**: 설정 앱의 화면
 * 중앙 요소로 강조 좌표를 대조했고 런처 홈 · 펼친 셰이드 · 시계 앱은 회귀로 봤다.
 * **3버튼 내비는 이 사이클에서 재지 않았다.** 위 프레임 재실측은 표시 창의 것이고 그 창은
 * `MATCH_PARENT` + `FLAG_LAYOUT_NO_LIMITS`라 내비 모드와 무관하게 같은 값이 나온다 — 이
 * 문단의 주제인 **조작 창**을 대신 덮지 못한다(하단 인셋이 3버튼 162px / 제스처 51px로 3배
 * 차이라는 것이 `app-android/CLAUDE.md`에 있다). #169 회귀가 3버튼 특유의 실패였다. 멀티윈도우·커버 디스플레이는 **대응하지 않는다** —
 * 커버 디스플레이가 검증 기기에 없고, 근거 없이 좌표를 흔드는 쪽이 위험하다(#139가 실측으로
 * 맞춰 놓은 자리다). 그 구성에서 어긋남이 실제로 보고되면 다시 연다.
 */
internal object OverlayWindowParams {

    fun create(
        touchable: Boolean,
        width: Int = WindowManager.LayoutParams.MATCH_PARENT,
        height: Int = WindowManager.LayoutParams.MATCH_PARENT,
    ): WindowManager.LayoutParams {
        // 접근성 오버레이는 설정 앱 위에서도 살아남는다(#56, 머리 주석). minSdk가 26이라
        // 이전의 O 분기는 이미 죽은 코드였고 함께 걷었다.
        val type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY

        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE

        return WindowManager.LayoutParams(width, height, type, flags, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            // **표시 창만 컷아웃 위까지 올린다**(#222). 기본 모드에서는 세로 방향에서 창이
            // 컷아웃을 피해 잡혀 프레임이 `[0,103][1080,2340]`이었다(SM-S921N 실측) — 위만
            // 103px 잘리고 아래는 안 잘린다. 가장자리 제스처의 상단 선이 "화면 맨 위"를
            // 가리켜야 하는데 그 자리가 상태표시줄 아래로 밀렸다. ALWAYS로 두면 `[0,0]`이 되고
            // 2026-08-21 실측에서 선이 y=0에 붙었다.
            //
            // **조작 창에는 주지 않는다.** 저쪽 프레임이 움직이면 안내창 자리가 통째로 103px
            // 밀린다 — 두 창의 부모 프레임이 다르다는 것이 화면 좌표를 넘기는 이유였다
            // ([HighlightOverlayView.GuideBoxListener]).
            //
            // **안내창이 시스템 바를 침범하지는 않는다**(#169 유지). 안내창이 앉을 범위는
            // 창 프레임이 아니라 디스플레이 인셋에서 오므로(`boxBounds`) 이 변경과 무관하다.
            //
            // **곁가지로 읽는 중 테두리·종료 스크림도 화면 끝까지 간다.** 시안이 전면인 쪽이라
            // 되돌릴 이유가 없지만, 그 확정은 #178 몫이고 여기서 앞당긴 것은 아니다.
            //
            // **API 30(R)부터만 올린다**(PR #343 리뷰 P3). 컷아웃 모드 자체는 P부터 있지만,
            // 안내창을 상태표시줄 밖에 잡아 두는 가드(`safeBounds`)가 API 30 미만에서는 null이라
            // 그 시절엔 **창 프레임이 컷아웃을 피해 잡히는 것 자체가 상단 가드**였다. 28~29
            // 컷아웃 기기에서 이 창을 위로 올리면 두 가드가 동시에 사라져, 하단 강조로 위로
            // 밀린 안내창이 상태표시줄에 걸치고 그 밴드의 스와이프를 조작 창이 흡수한다(#169와
            // 같은 모양 — 셰이드 내리기를 가르치는 앱이 셰이드 내리기를 막는다). 그 기기에서는
            // 상단 선이 상태표시줄 아래(#222 이전 동작)로 남는 쪽을 감수한다.
            if (!touchable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
    }
}
