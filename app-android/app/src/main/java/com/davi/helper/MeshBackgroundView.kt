package com.davi.helper

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils

/**
 * v2 앱 배경 "핑크 메시"(#161). 바탕색 위에 흐릿한 블롭 6개를 얹고, 그 위에 하단 세로
 * 그라디언트를 덮는다.
 *
 * **정적이다.** 움직이지 않으므로 [MotionView]가 아니라 평범한 [View]이고, 프레임마다 다시
 * 그리지 않는다. 셰이더는 크기가 정해질 때 한 번 만들고 하드웨어 레이어로 캐시한다.
 *
 * 디자인 근거: Figma SSoT Foundations `v2/bg/pink`. 원본은 타원 + `LAYER_BLUR 75~85`인데
 * **블러 반지름이 블롭 반지름의 절반이라 사실상 방사 그라디언트**여서 [RadialGradient]로 옮겼다.
 * 블러 API를 쓰지 않는 이유는 알파가 0으로 떨어지는 방사 그라디언트가 블러와 구분되지 않으면서
 * 훨씬 싸고, minSdk 26에서 분기가 없기 때문이다.
 *
 * 원본 타원을 원으로 근사했다(가로가 조금 더 넓다). 블러가 강해 육안 차이가 없다 —
 * 정확히 맞추려면 [Shader.setLocalMatrix]로 세로 스케일을 준다.
 */
class MeshBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /**
     * 블롭 하나. **좌표와 반지름은 dp가 아니라 뷰 크기 대비 비율이다** — 시안 프레임이
     * 300×620이라 그 숫자를 dp로 쓰면 좁은 화면에서 블롭이 화면 밖으로 나간다.
     * 비율이라 `dimens.xml`에 넣을 수 없어 여기 상수로 둔다(코드에서 px을 계산하지 말라는
     * 컨벤션의 예외이며, 그 이유가 이것이다).
     */
    private class Blob(
        val colorRes: Int,
        val alpha: Float,
        val centerXFraction: Float,
        val centerYFraction: Float,
        val radiusFraction: Float,
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    }

    private val baseColor = ContextCompat.getColor(context, R.color.davi_bg_mesh)

    private val blobs = listOf(
        Blob(R.color.davi_bg_blob_1, 0.34f, 0.23f, 0.09f, 0.60f),
        Blob(R.color.davi_bg_blob_2, 0.40f, 0.92f, 0.10f, 0.55f),
        Blob(R.color.davi_bg_blob_3, 0.32f, 1.00f, 0.32f, 0.50f),
        Blob(R.color.davi_bg_blob_4, 0.28f, 0.23f, 0.31f, 0.53f),
        // 5·6은 아래 veil에 거의 덮인다(veil이 0.52H에서 이미 58% 불투명이고 두 중심이
        // 0.46H·0.68H다). 정적 캐시라 런타임 비용이 0이고 Figma와 1:1이어야 배경 조정이
        // 1:1로 대응되므로 그대로 둔다.
        Blob(R.color.davi_bg_blob_5, 0.55f, 0.60f, 0.46f, 0.53f),
        Blob(R.color.davi_bg_blob_6, 0.50f, 0.40f, 0.68f, 0.60f),
    )

    /** 블롭 위에 덮는 하단 세로 그라디언트. 시안의 "노이즈 레이어"(흰색 2%)는 안 보여서 뺐다. */
    private val veilPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val veilMid = ContextCompat.getColor(context, R.color.davi_bg_veil_mid)
    private val veilEnd = ContextCompat.getColor(context, R.color.davi_bg_veil_end)

    init {
        // 정적이라 한 번 그린 결과를 GPU에 남긴다. 위에 얹히는 파도·글로우가 매 프레임
        // 다시 그려도 이 뷰는 다시 그려지지 않는다.
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return

        val width = w.toFloat()
        val height = h.toFloat()

        blobs.forEach { blob ->
            val color = ContextCompat.getColor(context, blob.colorRes)
            blob.paint.shader = RadialGradient(
                blob.centerXFraction * width,
                blob.centerYFraction * height,
                blob.radiusFraction * width,
                intArrayOf(
                    withAlpha(color, blob.alpha),
                    withAlpha(color, blob.alpha * MID_STOP_ALPHA_SCALE),
                    Color.TRANSPARENT,
                ),
                BLOB_STOPS,
                Shader.TileMode.CLAMP,
            )
        }

        veilPaint.shader = LinearGradient(
            0f,
            0f,
            0f,
            height,
            intArrayOf(
                Color.TRANSPARENT,
                Color.TRANSPARENT,
                withAlpha(veilMid, VEIL_MID_ALPHA),
                withAlpha(veilEnd, VEIL_END_ALPHA),
            ),
            VEIL_STOPS,
            Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        canvas.drawColor(baseColor)
        blobs.forEach { canvas.drawPaint(it.paint) }
        canvas.drawPaint(veilPaint)
    }

    private fun withAlpha(color: Int, alpha: Float): Int =
        ColorUtils.setAlphaComponent(color, (alpha * MAX_ALPHA).toInt().coerceIn(0, MAX_ALPHA))

    private companion object {
        const val MAX_ALPHA = 255

        /** 블롭 3스톱: [0: 색@불투명도, 0.45: 색@불투명도×0.55, 1: 투명]. */
        val BLOB_STOPS = floatArrayOf(0f, 0.45f, 1f)
        const val MID_STOP_ALPHA_SCALE = 0.55f

        /** 하단 veil: 0.00~0.19H 투명 → 0.52H 58% → 1.00H 94%. */
        val VEIL_STOPS = floatArrayOf(0f, 0.19f, 0.52f, 1f)
        const val VEIL_MID_ALPHA = 0.58f
        const val VEIL_END_ALPHA = 0.94f
    }
}
