package com.davi.helper

import android.content.Context
import android.graphics.Paint
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat

/**
 * 안내창 텍스트의 서체·크기·여백. **한 곳에서만 만든다.**
 *
 * 줄바꿈 계산은 표시 오버레이([HighlightOverlayView])가, 그리기는 조작 오버레이의
 * [GuideBoxView]가 한다(#126). 두 클래스가 각자 Paint를 만들면 서체나 크기가 한쪽만 바뀌었을 때
 * "계산한 폭"과 "그린 폭"이 조용히 어긋난다 — 글자가 상자 밖으로 삐져나오거나 마지막 글자가
 * 잘리는데, 어느 쪽이 틀렸는지 화면만 봐서는 알 수 없다.
 */
object GuideBoxStyle {

    /**
     * 안내문 글자(v2, #178). **Pretendard Bold 18sp · `#3A1C22` · 가운데 정렬**이다.
     *
     * 구 버전은 22sp `#1A1A1A` 왼쪽 정렬이었다. 색이 순검정이 아닌 이유는 채움이 핑크
     * 그라디언트라서다 — 시안이 분홍 계열 먹색으로 잡았다.
     *
     * **읽는 중(GD-1)만 20sp**다. 그쪽은 문구가 짧고 점과 함께 서므로 시안이 키웠다 —
     * 크기를 바꾸는 것은 [readingTextPaint].
     */
    fun textPaint(context: Context): Paint = basePaint(context).apply {
        textSize = context.resources.getDimension(R.dimen.davi_overlay_text_instruction)
    }

    /** GD-1 "화면을 읽고 있어요" 전용. 위와 색·서체는 같고 크기만 20sp다. */
    fun readingTextPaint(context: Context): Paint = basePaint(context).apply {
        textSize = context.resources.getDimension(R.dimen.davi_overlay_text_reading)
    }

    private fun basePaint(context: Context): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.davi_overlay_on_window)
        textAlign = Paint.Align.CENTER
        // Canvas 직접 그리기라 테마 fontFamily가 닿지 않는다 — 서체를 손으로 물린다(C1).
        typeface = ResourcesCompat.getFont(context, R.font.pretendard_bold)
    }

    /**
     * 상자 안쪽 여백. 줄바꿈 폭 계산과 그리기 시작점이 이 값을 공유한다.
     *
     * **좌우가 위아래보다 훨씬 넓다**(18 / 44) — 좌우 44는 로딩 점과 종료 X가 앉는 자리다.
     * 여기서는 글자가 차지할 폭을 정하므로 좌우 값을 돌려준다.
     */
    fun padding(context: Context): Float =
        context.resources.getDimension(R.dimen.davi_overlay_window_padding_h)

    /** 상자 위아래 여백. 줄 높이 계산과 그리기 시작점이 쓴다. */
    fun paddingVertical(context: Context): Float =
        context.resources.getDimension(R.dimen.davi_overlay_window_padding_v)

    /**
     * 상자와 화면 **좌우** 가장자리 사이 여백.
     *
     * **하단 여백과 다른 값이다**(좌우 16 / 하단 55). 하나로 묶었더니 창이 좌우로 110dp
     * 좁아져 확인쌍 라벨이 두 줄로 접혔다(2026-08-18 실기기).
     */
    fun margin(context: Context): Float =
        context.resources.getDimension(R.dimen.davi_overlay_window_margin_h)

    /**
     * 상자 안에 칩을 앉히려고 비워 두는 컨트롤 행의 높이(#126).
     *
     * 계산 쪽은 이만큼 상자를 키우고, 그리기 쪽은 이 자리에 글자를 쓰지 않는다. 값이 갈리면
     * 칩이 글자를 덮거나 상자 아래가 비어 보인다.
     */
    fun controlRow(context: Context): Float =
        context.resources.getDimension(R.dimen.davi_overlay_control_row)
}
