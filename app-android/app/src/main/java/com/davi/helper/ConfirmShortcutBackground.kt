package com.davi.helper

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat

/**
 * MA-4 ② 「바로 가기 · {앱이름}」의 배경(#396 · 08-22 시안). **두 겹이고 본체 오른쪽이 뾰족하다.**
 *
 * **왜 XML 드로어블이 아닌가.** 오른쪽 끝이 사선이라 `<shape>`에 그 모양이 없고,
 * `VectorDrawable`은 **②만 높이가 변해서** 쓸 수 없다 — 라벨 두 줄에서 106dp까지 자라고
 * 짧은 화면에서는 50dp까지 눌리는데(#372 실측), viewport가 View 경계로 비균일 확대되면
 * **r16이 r23쯤으로, 사선 17dp가 25쯤으로 커진다.** r16 고정인 ①③과 나란히 서면
 * 「셋은 색만 다르고 나머지가 같다」가 깨진다.
 *
 * **그래서 매번 그린다** — 높이가 어떻게 변해도 반지름과 사선 깊이는 dp 그대로다.
 * 9-patch도 후보였지만 색이 이미지에 박혀 토큰 체계 밖으로 나간다(2026-08-24 결정).
 *
 * **한 Drawable에 두 겹을 다 담는다.** 뒤판이 오른쪽으로 14dp 삐져나와 단단한 경계를
 * 만드는데, 어르신은 **삐져나온 분홍까지를 버튼 끝으로 본다** — 별도 View로 깔면 거기를
 * 눌렀을 때 반응이 없고 그것을 고장으로 읽는다. 배경 하나면 터치 영역도 하나다.
 *
 * **뾰족한 끝은 「이 앱을 떠난다」를 형태로 말한다** — ①③은 앱 안에 머물러 뾰족하지 않다.
 * 셰브런이 하던 일을 이 모양이 대신하므로 화살표 아이콘을 함께 두지 않는다(같은 뜻이 두 번 선다).
 */
internal class ConfirmShortcutBackground(context: Context) : Drawable() {

    private val res = context.resources
    private val radius = res.getDimension(R.dimen.davi_confirm_choice_radius)
    private val notch = res.getDimension(R.dimen.davi_confirm_choice_notch)
    private val reveal = res.getDimension(R.dimen.davi_choice_backplate_reveal)
    private val strokeWidth = res.getDimension(R.dimen.davi_confirm_choice_stroke)

    private val backplatePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.davi_confirm_choice_backplate)
    }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.davi_surface_raised)
    }
    // **테두리는 안쪽으로 그린다.** `<stroke>`가 경계선 중앙 정렬이라 절반이 밖으로 나가던
    // 것과 달리, 여기서는 획 두께의 절반만큼 줄인 Path에 그려 시안의 INSIDE 2를 그대로 낸다.
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = this@ConfirmShortcutBackground.strokeWidth
        color = ContextCompat.getColor(context, R.color.davi_choice_border)
    }

    // **각 페인트의 기준 알파를 기억해 둔다.** [setAlpha]가 들어오면 이 값에 곱한다 —
    // 그냥 대입하면 `davi_choice_border`의 52% 반투명이 불투명 빨강이 된다
    // (`LayerDrawable.setAlpha`가 자식에 그대로 전파한다).
    private val baseAlpha = listOf(backplatePaint, bodyPaint, borderPaint).map { it.alpha }

    private val backplatePath = Path()
    private val bodyPath = Path()
    private val borderPath = Path()

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        val r = RectF(bounds)
        backplatePath.reset()
        backplatePath.addRoundRect(r, radius, radius, Path.Direction.CW)
        buildBody(bodyPath, RectF(r.left, r.top, r.right - reveal, r.bottom), 0f)
        buildBody(
            borderPath,
            RectF(r.left, r.top, r.right - reveal, r.bottom),
            strokeWidth / 2f,
        )
    }

    /**
     * 본체 윤곽. **왼쪽 두 모서리만 r16이고 오른쪽은 꼭짓점으로 모인다** — 오른쪽 끝에서
     * [notch]만큼이 사선이다. [inset]은 테두리를 안쪽으로 밀어 넣을 때 쓴다.
     */
    private fun buildBody(path: Path, box: RectF, inset: Float) {
        val left = box.left + inset
        val top = box.top + inset
        val right = box.right - inset
        val bottom = box.bottom - inset
        val r = (radius - inset).coerceAtLeast(0f)
        val tipStart = right - notch
        val centerY = (top + bottom) / 2f

        path.reset()
        path.moveTo(left + r, top)
        path.lineTo(tipStart, top)
        path.lineTo(right, centerY)
        path.lineTo(tipStart, bottom)
        path.lineTo(left + r, bottom)
        path.arcTo(RectF(left, bottom - 2 * r, left + 2 * r, bottom), 90f, 90f, false)
        path.lineTo(left, top + r)
        path.arcTo(RectF(left, top, left + 2 * r, top + 2 * r), 180f, 90f, false)
        path.close()
    }

    override fun draw(canvas: Canvas) {
        canvas.drawPath(backplatePath, backplatePaint)
        canvas.drawPath(bodyPath, bodyPaint)
        canvas.drawPath(borderPath, borderPaint)
    }

    /**
     * **그림자가 버튼 모양을 따르게 한다.** 안 주면 `elevation`이 사각형 그림자를 던져
     * 뾰족한 끝 밖에 그늘이 남는다. 이 모양은 볼록해서 `setConvexPath`로 충분하다.
     */
    override fun getOutline(outline: Outline) {
        @Suppress("DEPRECATION")
        outline.setConvexPath(backplatePath)
    }

    override fun setAlpha(alpha: Int) {
        listOf(backplatePaint, bodyPaint, borderPaint).forEachIndexed { i, paint ->
            paint.alpha = baseAlpha[i] * alpha / 255
        }
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        backplatePaint.colorFilter = colorFilter
        bodyPaint.colorFilter = colorFilter
        borderPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
