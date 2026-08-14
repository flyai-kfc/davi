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
    /** X 칩을 눌렀을 때. 세션을 끝내는 것은 호출자 몫이다 — 이 창은 무엇이 끝나는지 모른다. */
    private val onStop: () -> Unit,
) {

    private var root: View? = null
    private var box: GuideBoxView? = null

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

    /** 안내창을 [rect] 자리에 [lines]로 띄운다. 창이 없으면 만들고, 있으면 옮긴다. */
    fun show(rect: Rect, lines: List<String>) {
        val current = root
        if (current == null) {
            if (create(rect) != null) box?.setLines(lines)
            return
        }
        box?.setLines(lines)
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
        created.findViewById<View>(R.id.btn_stop_chip).setOnClickListener { onStop() }
        return runCatching {
            windowManager.addView(created, params(rect))
            remember(rect)
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
