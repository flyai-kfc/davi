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
     * **읽는 중만 20sp**다(GD-1·GD-2r 둘 다). 그쪽은 문구가 짧고 점과 함께 서므로 시안이 키웠다 —
     * 크기를 바꾸는 것은 [readingTextPaint].
     */
    fun textPaint(context: Context): Paint = basePaint(context).apply {
        textSize = context.resources.getDimension(R.dimen.davi_overlay_text_instruction)
    }

    /**
     * **읽는 중 창 전용**(GD-1·GD-2r). 위와 색·서체는 같고 크기만 20sp다.
     *
     * **문구를 이름으로 박지 않는다**(#314) — 두 자리가 문구는 다르고 페인트는 같다.
     * `davi_overlay_text_reading`을 GD-1 문구 길이만 보고 조정하면 **더 긴 GD-2r 문구의
     * 접힘까지 함께 바뀐다.**
     */
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
     * **위·아래와 모두 다른 값이다**(좌우 16 / 위 20 / 아래 55). 하나로 묶었더니 창이 좌우로
     * 110dp 좁아져 확인쌍이 접혔다(2026-08-18 실기기). 반대로 이 값을 세로에도 쓰면 세로 여백이
     * 20에서 16으로 조용히 줄어든다(PR #229 리뷰) — 그래서 [marginTop]·[marginBottom]이 따로 있다.
     */
    fun margin(context: Context): Float =
        context.resources.getDimension(R.dimen.davi_overlay_window_margin_h)

    /**
     * 상자와 화면 **윗변** 사이 여백.
     *
     * **아랫변과 다른 값이다**(위 20 / 아래 55). 시안은 창 하단만 정했고(세 화면이 574를
     * 공유한다) 위쪽 근거가 없어, 위는 구 값 20을 그대로 지킨다. 하나로 묶으면 둘 중 하나가
     * 근거 없는 값이 된다.
     */
    fun marginTop(context: Context): Float =
        context.resources.getDimension(R.dimen.overlay_instruction_margin)

    /**
     * 상자와 화면 **아랫변** 사이 여백(v2, #234).
     *
     * 시안 근거가 있다 — GD-1·2·3 세 화면이 창 하단 574를 공유하고 프레임 620에서 46이다.
     */
    fun marginBottom(context: Context): Float =
        context.resources.getDimension(R.dimen.davi_overlay_window_margin_bottom)

    /**
     * **겹침 회피 여백**(#234). 대상이 안내창 자리와 겹칠 때 대상 위나 아래로 이만큼 떨어진다.
     *
     * **"대상과 안내창 사이 간격"이 아니다** — 시안에 그런 개념이 없다(Figma 담당자 확인).
     * 안내창은 하단 고정이고 강조는 대상을 따라가므로, 둘이 멀리 떨어진 것은 간격이 아니라
     * 각자 자기 자리에 있는 것이다. 시안이 그린 것은 대상이 위쪽인 경우뿐이라 겹치는 경우의
     * 답이 없고, 값 20에는 아직 근거가 없다.
     */
    fun avoidGap(context: Context): Float =
        context.resources.getDimension(R.dimen.davi_overlay_window_avoid_gap)

    /**
     * 상자 안에 칩을 앉히려고 비워 두는 컨트롤 행의 높이(#126).
     *
     * 계산 쪽은 이만큼 상자를 키우고, 그리기 쪽은 이 자리에 글자를 쓰지 않는다. 값이 갈리면
     * 칩이 글자를 덮거나 상자 아래가 비어 보인다.
     */
    fun controlRow(context: Context): Float =
        context.resources.getDimension(R.dimen.davi_overlay_control_row)
}
