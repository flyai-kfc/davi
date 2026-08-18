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

    private val driver = MotionDriver { elapsed, delta ->
        onMotionFrame(elapsed, delta)
        invalidate()
    }

    private var motionRunning = false

    /**
     * 한 프레임분 상태를 갱신한다. 그리기는 하지 않는다 — 호출 뒤 [invalidate]가 따라온다.
     *
     * 모션이 꺼져 있으면 `(0f, 0f)`으로 **한 번만** 불린다. 그래서 이 함수는 "시간 0의 모습"이
     * 정지 프레임으로 쓸 만하도록 써야 하고, [onDraw]도 그 한 프레임만으로 그려낼 수 있어야 한다.
     */
    protected abstract fun onMotionFrame(elapsedSeconds: Float, deltaSeconds: Float)

    /**
     * 구동 조건(부착·가시·창 가시)이 참↔거짓으로 **바뀔 때** 한 번씩 불린다. 지연 타이머처럼
     * "다시 보이기 시작한 순간"에 되돌려야 하는 부수 상태가 여기 걸린다(#148).
     *
     * 기준이 driver의 실제 루프가 아니라 **조건**인 이유: 모션이 꺼져 있으면 루프는 돌지 않지만
     * (정지 프레임 한 장) 부수 상태는 여전히 전이를 따라가야 한다 — 안 그러면 모션 끄기에서
     * 지연이 영영 arm되지 않는다.
     */
    protected open fun onMotionLifecycleChanged(running: Boolean) {}

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncDriver()
    }

    override fun onDetachedFromWindow() {
        // syncDriver()를 못 쓴다 — 이 시점의 isAttachedToWindow는 아직 true라 조건이 안 걸린다.
        setRunning(false)
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
        setRunning(
            isAttachedToWindow &&
                visibility == VISIBLE &&
                windowVisibility == VISIBLE,
        )
    }

    private fun setRunning(shouldRun: Boolean) {
        // driver 먼저 — 모션 끄기의 정지 프레임 한 장이 흐른 뒤에 전이 훅이 온다.
        if (shouldRun) driver.start() else driver.stop()
        if (shouldRun != motionRunning) {
            motionRunning = shouldRun
            onMotionLifecycleChanged(shouldRun)
        }
    }
}
