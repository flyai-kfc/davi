package com.davi.helper

import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

/**
 * 안내창 자체 — 상자·테두리·안내문을 그리고 **그 영역의 터치를 삼킨다**(#126).
 *
 * 조작 오버레이 창이 안내창 크기로 잡히므로 이 뷰는 **자기 로컬 좌표**에만 그린다. 어디에 놓을지는
 * [GuideBoxLayout]이 정하고 창이 그리로 옮겨간다.
 *
 * **터치를 삼키는 것이 이 뷰의 두 번째 일이다.** 칩만 터치를 받으면 안내창의 나머지 영역은 그대로
 * 통과해, 칩을 겨냥하다 빗나갔을 때 불투명한 안내창에 가려 보이지도 않는 아래 앱 버튼이 눌린다.
 * `isClickable`을 빼면 창이 이미 터치를 가로챈 뒤라 **아래 앱에도 안 가고 우리도 안 받는** 상태가
 * 되는데, 조용히 먹혀서 실기기에서 눌러 보기 전에는 드러나지 않는다.
 */
class GuideBoxView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var lines: List<String> = emptyList()

    private val instructionPaint = GuideBoxStyle.textPaint(context)
    private val readingPaint = GuideBoxStyle.readingTextPaint(context)

    /**
     * "읽는 중"(GD-1·GD-2r)이면 글자가 20sp다(그 외 18sp). **스스로 판정하지 않는다** — 줄을 접은
     * 쪽([HighlightOverlayView])이 알려준다. 두 곳이 각자 판정하면 접은 폭과 그린 폭이 어긋난다.
     */
    private var reading = false

    private val textPaint: Paint get() = if (reading) readingPaint else instructionPaint

    fun setReading(on: Boolean) {
        if (reading == on) return
        reading = on
        invalidate()
    }
    private val padding = GuideBoxStyle.padding(context)
    private val paddingVertical = GuideBoxStyle.paddingVertical(context)
    private val corner = resources.getDimension(R.dimen.davi_overlay_window_radius)

    /**
     * 채움은 **세로 그라디언트**다(v2, #178) — 위 `#FDF6F8` 90% → 아래 `#FADCE6` 80%.
     * 구 버전은 흰 단색이었다. 셰이더는 크기를 알아야 만들 수 있어 [onSizeChanged]에서 건다.
     */
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillTop = ContextCompat.getColor(context, R.color.davi_overlay_window_top)
    private val fillBottom = ContextCompat.getColor(context, R.color.davi_overlay_window_bottom)

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.getDimension(R.dimen.davi_overlay_window_stroke)
        color = ContextCompat.getColor(context, R.color.davi_overlay_window_border)
    }

    /** 윗변 안쪽 흰 띠(시안 INNER `#FFFFFF` 95% y1 blur1)의 근사. */
    private val innerHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.getDimension(R.dimen.davi_overlay_window_stroke)
        color = ContextCompat.getColor(context, R.color.davi_overlay_window_highlight)
    }

    private val boxRect = RectF()
    private val boxPath = android.graphics.Path()
    private val closePath = android.graphics.Path()
    private val closeArc = android.graphics.RectF()

    /**
     * 창의 오른쪽 위 **일부가 종료 X 영역**이다(v2, #178).
     *
     * **창 위에 얹은 별개 원이 아니다** — 흰 곡면이 모서리에 물려 창 밖으로 나가는 부분은
     * 잘리고, 남은 부분이 창의 일부로 읽힌다. 원을 통째로 창 안에 그렸더니 글자를 덮는
     * 흰 덩어리가 됐다(2026-08-18 실기기).
     *
     * X 글리프와 터치 대상은 `overlay_control.xml`이 같은 자리에 얹는다 — 여기서는 면만 그린다.
     */
    private val closeRegion = resources.getDimension(R.dimen.davi_overlay_close_region)
    private val closeMark = resources.getDimension(R.dimen.davi_overlay_close_mark)
    private val closeMarkDx = resources.getDimension(R.dimen.davi_overlay_close_mark_dx)
    private val closeMarkDy = resources.getDimension(R.dimen.davi_overlay_close_mark_dy)

    /**
     * ✕ 글리프를 **여기서 직접 그린다**(2026-08-18).
     *
     * 벡터 드로어블(`ic_davi_x`)로 두면 두께가 파일에 박혀 있어 `davi_overlay_close_stroke`를
     * 올려도 아무 일이 없다 — 실제로 그 토큰이 쓰이지 않고 있었다. 여기서 그리면 두께·위치가
     * 둘 다 토큰으로 잡힌다. `overlay_control.xml`의 버튼은 **터치 대상으로만** 남는다.
     */
    private val closeMarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = resources.getDimension(R.dimen.davi_overlay_close_stroke)
        color = ContextCompat.getColor(context, R.color.davi_overlay_close_mark)
    }
    private val closePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.davi_overlay_close_face)
    }
    private var closeVisible = false

    /**
     * 상자 아래에 비워 둔 높이. 확인쌍이면 컨트롤 행, 읽는 중이면 점 셋의 자리다.
     *
     * **값을 그대로 받는다 — 참/거짓이 아니다.** 참/거짓으로 받으면 그리기 쪽이 "72냐 0이냐"
     * 두 값만 표현할 수 있어서, 계산 쪽이 셋째 값(점 셋 27)을 쓰기 시작한 순간 조용히
     * 갈렸다(PR #229 리뷰). 계산이 쓴 값을 그대로 흘려보내면 갈릴 수가 없다.
     */
    private var reservedHeight = 0f

    fun setReservedHeight(px: Float) {
        if (reservedHeight == px) return
        reservedHeight = px
        invalidate()
    }

    /** 종료 X 영역을 그릴지. 확인 화면에서는 칩이 숨으므로 면도 함께 숨는다. */
    fun setCloseVisible(on: Boolean) {
        if (closeVisible == on) return
        closeVisible = on
        invalidate()
    }

    init {
        // 안내창 영역의 터치를 여기서 끝낸다. 위 주석 참조 — 빼면 조용히 깨진다.
        isClickable = true
        // **clickable인데 접근성에서는 뺀다** — 모순처럼 보이지만 둘은 다른 축이다.
        // 트리에 남으면 TalkBack이 "라벨 없는 버튼"으로 읽고 사용자가 아무 일도 일어나지 않는
        // 영역에 포커스한다. 터치 처리에는 영향이 없다. 칩은 라벨이 있어 그대로 노출된다
        // (PR #133 리뷰).
        //
        // **#450이 이 면에 실제 click 동작(안내 다시 듣기)을 달았지만 판정은 그대로다.** 낭독은
        // 낭독 스크린리더가 켜져 있으면 아예 억제되므로([GuideSpeaker]), TalkBack 사용자에게
        // 이 노드를 열어 주면 **눌러도 아무 일이 없는 자리**를 하나 주는 것이 된다.
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (h <= 0) return
        boxPaint.shader = android.graphics.LinearGradient(
            0f, 0f, 0f, h.toFloat(), fillTop, fillBottom, android.graphics.Shader.TileMode.CLAMP,
        )
    }

    /**
     * 이미 접힌 줄들. [GuideBoxLayout]이 정한 그대로 그린다 — 여기서 다시 접지 않는다.
     *
     * **문구가 실제로 바뀔 때만 떠오른다**(#147). 서비스는 폴링마다 상태를 다시 보내고 화면이
     * 안 바뀌면 같은 안내가 계속 도착하므로, 호출마다 등장을 다시 틀면 안내창이 끝없이 깜빡인다
     * (`setReading`을 멱등하게 짠 것과 같은 이유). 위의 조기 반환이 그 가드를 겸한다.
     */
    fun setLines(next: List<String>) {
        if (lines == next) return
        val hadContent = lines.isNotEmpty()
        lines = next
        invalidate()
        // 이미 문구가 있던 상태에서 내용만 바뀌는 것은 "새로 나타남"이 아니다 — 그때 투명도를
        // 되돌리면 읽던 사람 눈앞에서 안내창이 한 번 사라졌다 온다.
        if (!hadContent && next.isNotEmpty()) fadeIn()
    }

    /**
     * 안내창이 떠오르며 나타난다. **투명도만 바꾼다 — 자리·크기는 건드리지 않는다**(#147).
     *
     * 창 자체가 안내창 크기로 잡혀 있어([ControlOverlay]) 뷰를 움직이면 창 밖으로 잘리고,
     * 창을 움직이면 그 영역의 터치도 함께 움직여 X 칩을 겨냥하다 빗맞힌다. 무엇보다 창을
     * 건드릴 때마다 `TYPE_WINDOWS_CHANGED`가 떠서 서비스의 샘플 예약에 걸리므로
     * ([ControlOverlay]의 `appliedX` 주석), 프레임마다 창을 옮기면 안내 루프 자체를 흔든다.
     */
    private fun fadeIn() {
        if (!ValueAnimator.areAnimatorsEnabled()) {
            alpha = 1f
            return
        }
        animate().cancel()
        alpha = 0f
        // 감속 곡선 — 앞에서 빠르게 올라가 글자가 먼저 읽히고, 끝에서 부드럽게 잦아든다.
        // 선형이면 같은 시간이라도 "그냥 켜졌다"에 가깝게 느껴진다.
        animate()
            .alpha(1f)
            .setDuration(FADE_IN_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (lines.isEmpty()) return

        // 채움은 창 전체, 테두리는 선 두께의 절반만큼 안으로. 창이 상자에 딱 맞게 잡혀 있어
        // 테두리를 가장자리에 걸치면 바깥 절반이 창 밖으로 잘린다.
        val half = borderPaint.strokeWidth / 2f
        boxRect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(boxRect, corner, corner, boxPaint)
        boxRect.set(half, half, width - half, height - half)
        canvas.drawRoundRect(boxRect, corner, corner, borderPaint)
        // 종료 X 면 — **창 모양으로 잘라내고 그린다.** 자르지 않으면 모서리 밖으로 나간
        // 부분이 사각형 창 안에 그대로 남아 창이 아니라 원이 얹힌 것처럼 보인다.
        if (closeVisible) {
            canvas.save()
            boxPath.reset()
            boxRect.set(0f, 0f, width.toFloat(), height.toFloat())
            boxPath.addRoundRect(boxRect, corner, corner, android.graphics.Path.Direction.CW)
            canvas.clipPath(boxPath)
            // **창 위에 원을 얹는 게 아니라 오른쪽 위 모서리를 X 구역으로 떼어낸다.**
            // 창 꼭짓점을 중심으로 한 1/4 원이라 바깥 두 변은 창 변과 그대로 겹치고
            // 안쪽 한 변만 볼록한 곡선으로 갈린다. 위 clipPath가 둥근 모서리를 맞춰 깎는다.
            val r = closeRegion
            val right = width.toFloat()
            closePath.reset()
            closePath.moveTo(right - r, 0f)
            closeArc.set(right - r, -r, right + r, r)
            closePath.arcTo(closeArc, 180f, -90f)
            closePath.lineTo(right, 0f)
            closePath.close()
            canvas.drawPath(closePath, closePaint)
            // 가르는 선은 **안쪽 곡선만**. 바깥 두 변에도 그으면 창 테두리 위에 선이
            // 겹쳐 그 부분만 두꺼워 보인다.
            closePath.reset()
            closePath.moveTo(right - r, 0f)
            closePath.arcTo(closeArc, 180f, -90f)
            canvas.drawPath(closePath, borderPaint)
            // ✕는 구역 무게중심(0.42r)보다 바깥으로 밀어 대각선 위쪽에 앉힌다.
            val mx = right - closeMarkDx
            val my = closeMarkDy
            val h = closeMark / 2f
            canvas.drawLine(mx - h, my - h, mx + h, my + h, closeMarkPaint)
            canvas.drawLine(mx + h, my - h, mx - h, my + h, closeMarkPaint)
            canvas.restore()
        }

        // 윗변 안쪽 흰 띠 — 아래로 반 두께 내려 윗변에만 걸치게 한다.
        boxRect.set(half, half + half, width - half, height - half)
        canvas.drawRoundRect(boxRect, corner, corner, innerHighlightPaint)

        // **가운데 정렬이다**(v2, #178) — textAlign이 CENTER라 x는 창의 중앙이다.
        // 세로 시작점은 좌우(44)가 아니라 위아래 여백(18)을 쓴다.
        // **세로도 가운데다**(2026-08-18). 위 여백에서 시작하면 컨트롤 행이 없는 화면에서
        // 글자가 위로 몰려 아래가 비어 보인다.
        val metrics = textPaint.fontMetrics
        val lineHeight = metrics.bottom - metrics.top
        val blockHeight = lines.size * lineHeight
        val available = height - reservedHeight
        var baseline = (available - blockHeight) / 2f - metrics.top
        val centerX = width / 2f
        lines.forEach { line ->
            canvas.drawText(line, centerX, baseline, textPaint)
            baseline += metrics.bottom - metrics.top
        }
    }

    private companion object {
        // ReadingBorder의 조정 지점 규약을 따른다 — dp가 아니라 시간이라 dimens.xml로 빼지 않는다.
        //
        // **180ms는 실기기에서 아예 인지되지 않았다**(#147, 작성자 확인). "빨리 읽히게"를
        // 우선해 짧게 잡았는데, 느껴지지 않으면 애초에 등장을 부드럽게 한 목적이 사라진다.
        // 감속 곡선과 함께 쓰므로 앞부분에서 이미 대부분 올라와 읽기는 늦어지지 않는다.
        const val FADE_IN_MS = 420L
    }
}
