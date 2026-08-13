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

    /** 안내문 글자. 흰 배경 + 진한 글자(#111 확정 디자인). */
    fun textPaint(context: Context): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.davi_overlay_on_box)
        textSize = context.resources.getDimension(R.dimen.text_overlay_instruction)
        // Canvas 직접 그리기라 테마 fontFamily가 닿지 않는다 — 서체를 손으로 물린다(C1).
        typeface = ResourcesCompat.getFont(context, R.font.pretendard)
    }

    /** 상자 안쪽 여백. 줄바꿈 폭 계산과 그리기 시작점이 이 값을 공유한다. */
    fun padding(context: Context): Float =
        context.resources.getDimension(R.dimen.overlay_instruction_padding)

    /** 상자와 화면 가장자리 사이 여백. */
    fun margin(context: Context): Float =
        context.resources.getDimension(R.dimen.overlay_instruction_margin)

    /**
     * 상자 안에 칩을 앉히려고 비워 두는 컨트롤 행의 높이(#126).
     *
     * 계산 쪽은 이만큼 상자를 키우고, 그리기 쪽은 이 자리에 글자를 쓰지 않는다. 값이 갈리면
     * 칩이 글자를 덮거나 상자 아래가 비어 보인다.
     */
    fun controlRow(context: Context): Float =
        context.resources.getDimension(R.dimen.davi_overlay_control_row)
}
