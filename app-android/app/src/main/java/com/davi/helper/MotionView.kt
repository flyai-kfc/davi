package com.davi.helper

import android.content.Context
import android.util.AttributeSet
import android.view.View

/**
 * 반복 애니메이션 뷰의 공통 뼈대(#132). [MotionDriver]에서 프레임을 받고, **화면에 보이지 않는
 * 동안에는 받지 않는다.**
 *
 * 멈춰야 할 순간이 넷이라 뷰마다 손으로 챙기면 하나씩 빠뜨린다.
 * - 창에서 떨어졌다(`onDetachedFromWindow`)
 * - 뷰가 숨겨졌다(`onVisibilityChanged` — renderModelOf가 끈 경우)
 * - **창이 안 보이게 됐다**(`onWindowVisibilityChanged` — 홈 버튼·다른 앱으로 전환)
 * - 아직 붙기 전이다
 *
 * 셋째가 특히 놓치기 쉽다. 대화 화면은 `onPause`에서 홈 상태로 되돌리는데 홈에서도 유도
 * 글로우는 계속 보이므로, 가시성만 보면 뒤로 넘어간 뒤에도 루프가 돈다 — 안 보이는 화면에
 * 배터리만 쓰는 상태다.
 *
 * 파도·글로우가 쓰고, 이후 애니메이션 작업(글자 등장·오버레이 강조·제스처 손동작 시연)도
 * 이걸 상속해 [onMotionFrame]만 채우면 된다.
 */
abstract class MotionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val driver = MotionDriver(context) { elapsed, delta ->
        onMotionFrame(elapsed, delta)
        invalidate()
    }

    /**
     * 한 프레임분 상태를 갱신한다. 그리기는 하지 않는다 — 호출 뒤 [invalidate]가 따라온다.
     *
     * 모션이 꺼져 있으면 `(0f, 0f)`으로 **한 번만** 불린다. 그래서 이 함수는 "시간 0의 모습"이
     * 정지 프레임으로 쓸 만하도록 써야 한다.
     */
    protected abstract fun onMotionFrame(elapsedSeconds: Float, deltaSeconds: Float)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncDriver()
    }

    override fun onDetachedFromWindow() {
        driver.stop()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        syncDriver()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        syncDriver()
    }

    private fun syncDriver() {
        val shouldRun = isAttachedToWindow &&
            visibility == VISIBLE &&
            windowVisibility == VISIBLE
        if (shouldRun) driver.start() else driver.stop()
    }
}
