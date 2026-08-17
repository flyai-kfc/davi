package com.davi.helper

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
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
 * [Paint.setShadowLayer]를 쓴다. 색과 y는 시안 값을 그대로 넣고 바이너리 에셋이 없어
 * 값이 바뀌어도 다시 굽지 않는다.
 *
 * **단 blur는 그대로 넘기지 않는다.** Figma는 sigma = r/2이고 Skia는 sigma = r x 0.57735 + 0.5라,
 * 시안의 36을 그대로 주면 sigma 21.3으로 시안(18)보다 18% 넓게 번진다. 그래서
 * `davi_glow_card_blur`는 **30dp**이고 sigma 17.8로 맞는다. 환산 근거는 그 토큰 주석에 있다.
 *
 * `setShadowLayer`는 하드웨어 가속에서 무시되므로 **소프트웨어 레이어가 필수**다.
 * 정적인 그림 한 장이고 발화자 전환은 이 뷰의 `alpha`만 바꾸므로 비용이 프레임마다 들지 않는다.
 *
 * **채움을 지워서 그림자만 남긴다.** 투명한 도형은 그림자도 만들지 않으므로 일단 불투명하게
 * 그린 뒤, 같은 자리를 [PorterDuff.Mode.CLEAR]로 도려낸다.
 *
 * 처음에는 "카드가 위를 덮으니 채움이 안 보인다"고 두었는데 **틀렸다** — 글로우 도형이
 * 그림자를 낼 자리만큼 카드 밖으로 삐져나오고, 그 부분이 진한 분홍 덩어리로 보였다
 * (실기기에서 다비 발화 상태에 처음 도달했을 때 발견). 덮이는 것에 기대지 않는다.
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

    /** 그린 도형을 도려내 그림자만 남기는 붓. */
    private val cutoutPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    private val bounds = RectF()

    init {
        // setShadowLayer는 하드웨어 가속 캔버스에서 그려지지 않는다.
        // CLEAR 합성도 오프스크린 레이어가 있어야 뒤 화면을 지우지 않는다.
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
        // 1) 도형 + 그림자를 그리고 2) 도형만 도려낸다. 투명한 도형은 그림자를 못 만들어
        // 한 번에 끝낼 수 없다.
        canvas.drawRoundRect(bounds, radius, radius, paint)
        canvas.drawRoundRect(bounds, radius, radius, cutoutPaint)
    }
}
