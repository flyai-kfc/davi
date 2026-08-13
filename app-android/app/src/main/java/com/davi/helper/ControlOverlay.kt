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
 * 창은 안내창 사각형 크기로 잡히고 위치도 거기서 온다. 좌표는 표시 오버레이의 **로컬 좌표**
 * (창 프레임 기준)로 받는다 — 두 창이 같은 flags를 쓰므로 프레임 원점이 같고, 그래서 로컬 좌표를
 * 그대로 넣으면 안내창이 계산된 자리에 정확히 앉는다.
 *
 * **보여줄 것이 없으면 창을 걷는다.** 표시 오버레이는 비어 있어도 `FLAG_NOT_TOUCHABLE`이라
 * 무해하지만, 이 창은 남아 있는 동안 계속 터치를 가져간다.
 */
class ControlOverlay(
    private val context: Context,
    private val windowManager: WindowManager,
) {

    private var root: View? = null
    private var box: GuideBoxView? = null

    /** 안내창을 [rect] 자리에 [lines]로 띄운다. 창이 없으면 만들고, 있으면 옮긴다. */
    fun show(rect: Rect, lines: List<String>) {
        val current = root
        if (current == null) {
            if (create(rect) != null) box?.setLines(lines)
            return
        }
        box?.setLines(lines)
        runCatching {
            windowManager.updateViewLayout(current, params(rect))
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
    }

    private fun create(rect: Rect): View? {
        // 서비스 Context에는 테마가 없다. 테마 속성을 참조하는 리소스가 하나라도 끼면 inflate가 터진다.
        val themed = ContextThemeWrapper(context, R.style.Theme_Davi)
        val created = LayoutInflater.from(themed).inflate(R.layout.overlay_control, null)
        return runCatching {
            windowManager.addView(created, params(rect))
            root = created
            box = created.findViewById(R.id.guide_box)
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
