package com.davi.helper

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

/**
 * 다비가 말하는 동안 카드 뒤에 깔리는 분홍 글로우(#170).
 * 시안 값은 `davi_glow_card`(#E0708F) · y18 · blur36이다.
 *
 * **컬러 그림자를 `elevation`으로 만들 수 없다** — `setOutlineSpotShadowColor`가 API 28+인데
 * minSdk는 26이다. 인계문은 9-patch를 굽는 방안을 적었으나 여기서는
 * [Paint.setShadowLayer]를 쓴다. 시안의 세 수치(색·y·blur)를 **그대로** 넣을 수 있고
 * 바이너리 에셋이 없어 값이 바뀌어도 다시 굽지 않는다.
 *
 * `setShadowLayer`는 하드웨어 가속에서 무시되므로 **소프트웨어 레이어가 필수**다.
 * 정적인 그림 한 장이고 발화자 전환은 이 뷰의 `alpha`만 바꾸므로 비용이 프레임마다 들지 않는다.
 *
 * 그림자만 남기려고 채움을 [Color.TRANSPARENT]로 두면 안 된다 — 투명한 도형은 그림자도
 * 만들지 않는다. 그래서 카드 색으로 칠하고 **그 위를 카드가 덮게** 배치한다.
 */
class CardGlowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val glowColor = ContextCompat.getColor(context, R.color.davi_glow_card)
    private val radius = resources.getDimension(R.dimen.davi_radius_xl)
    private val offsetY = resources.getDimension(R.dimen.davi_glow_card_offset_y)
    private val blur = resources.getDimension(R.dimen.davi_glow_card_blur)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = glowColor
    }

    private val bounds = RectF()

    init {
        // setShadowLayer는 하드웨어 가속 캔버스에서 그려지지 않는다.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        paint.setShadowLayer(blur, 0f, offsetY, glowColor)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        // 그림자가 뷰 밖으로 번질 자리를 남긴다 — 도형을 blur+offset만큼 안쪽으로 들인다.
        val inset = blur
        bounds.set(inset, inset, width - inset, height - inset - offsetY)
        if (bounds.width() <= 0f || bounds.height() <= 0f) return
        canvas.drawRoundRect(bounds, radius, radius, paint)
    }
}
