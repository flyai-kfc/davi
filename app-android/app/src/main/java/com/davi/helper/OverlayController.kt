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
 * 다른 앱 위에 강조를 띄우는 창을 관리한다.
 *
 * **중요한 플래그 세 가지** (HANDOFF §7, §3-1):
 * - `FLAG_LAYOUT_IN_SCREEN` — 없으면 접근성 `bounds`와 좌표계가 상태표시줄 높이만큼 어긋난다.
 * - `FLAG_NOT_TOUCHABLE` — 강조 영역을 눌렀을 때 터치가 아래 앱으로 통과해야 한다.
 *   우리는 **안내만** 하고 대신 조작하지 않으므로, 사용자가 직접 누를 수 있어야 한다(SPEC §2).
 * - `FLAG_NOT_FOCUSABLE` — 키 입력을 가로채지 않는다.
 *
 * 설정 앱(`com.android.settings`) 위에는 오버레이가 **아예 그려지지 않는다**(탭재킹 방지, 우회 불가).
 * 그 화면에서는 헤드업 알림으로 떨어져야 하지만 그건 다음 작업이다.
 */
class OverlayController(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var view: HighlightOverlayView? = null

    /** 오버레이 권한이 있는가. 없으면 온보딩에서 켜야 한다. */
    fun canDraw(): Boolean = Settings.canDrawOverlays(context)

    fun showGuide(bounds: Rect?, instruction: String) {
        if (!canDraw()) {
            Log.w(TAG, "overlay: 권한 없음 — 온보딩에서 '다른 앱 위에 표시'를 켜야 한다")
            return
        }
        val target = view ?: createView() ?: return
        target.setGuide(bounds, instruction)
    }

    fun clear() {
        view?.clearTarget()
    }

    /** 서비스가 내려갈 때 창을 반드시 걷는다. 안 그러면 유령 오버레이가 남는다. */
    fun destroy() {
        view?.let {
            runCatching { windowManager.removeView(it) }
                .onFailure { e -> Log.w(TAG, "overlay: removeView 실패", e) }
        }
        view = null
    }

    private fun createView(): HighlightOverlayView? {
        val created = HighlightOverlayView(context)
        return runCatching {
            windowManager.addView(created, layoutParams())
            view = created
            created
        }.onFailure { Log.e(TAG, "overlay: addView 실패", it) }.getOrNull()
    }

    private fun layoutParams(): WindowManager.LayoutParams {
        @Suppress("DEPRECATION")
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
    }

    private companion object {
        const val TAG = GuideAccessibilityService.TAG
    }
}
