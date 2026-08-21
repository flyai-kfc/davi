package com.davi.helper

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.WindowInsetsCompat
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * 표시 오버레이의 루트 뷰. 레이어 둘을 그린다 — **읽는 중 테두리**(화면 가장자리, 브리딩)와
 * **강조 사각형**(대상 영역). 터치는 아래 앱으로 통과한다.
 *
 * **안내창은 여기서 그리지 않는다**(#126). 자리 계산만 하고([GuideBoxLayout]), 결과를
 * [guideBoxListener]로 넘기면 조작 오버레이의 [GuideBoxView]가 그린다. 계산이 여기 남는 이유는
 * 그릴 수 있는 범위·강조 대상·회피 영역을 아는 곳이 이 뷰뿐이라서다.
 *
 * 강조 리플·방향 스와이프 애니메이션은 아직 없다(HANDOFF §4의 `HighlightOverlayView` /
 * `SwipeGuideView` 참고).
 *
 * 좌표는 **화면 좌표 그대로** 받는다. 오버레이 윈도우가 `FLAG_LAYOUT_IN_SCREEN`으로 전체 화면을
 * 차지하므로 접근성 `bounds`와 캔버스 좌표계가 일치한다. 이 플래그가 없으면 상태표시줄 높이만큼
 * 어긋난다(HANDOFF §7 "왜 조금씩 아래로 밀리지?"의 정답).
 */
class HighlightOverlayView(context: Context) : View(context) {

    /** 안내창 자리가 정해질 때마다 받는다. 조작 오버레이가 이 자리로 창을 옮긴다(#126). */
    fun interface GuideBoxListener {
        /**
         * @param box **화면 좌표**의 안내창 사각형. 그릴 안내가 없으면 null.
         *
         * 로컬 좌표가 아니라 화면 좌표인 이유는 실측이다(#139). 두 창의 flags를 맞춰도 **부모
         * 프레임까지 같아지지는 않는다** — `dumpsys window`에서 MATCH_PARENT인 표시 창은 부모가
         * `[0,103]`(상태표시줄 인셋 반영)인데 크기가 지정된 조작 창은 `[0,0]`이었다. 로컬 좌표를
         * 넘기면 조작 창이 그 차이만큼(103px) 위로 밀린다.
         *
         * #222가 표시 창을 컷아웃 위까지 올려 API 30+에서는 표시 창도 `[0,0]`이지만(2026-08-21
         * 실측), 그 일치는 이 기기의 우연이다 — API 28~29는 여전히 `[0,103]`이고(컷아웃 게이트,
         * `OverlayWindowParams`), 프레임은 창마다 시스템이 정한다. 화면 좌표 규약은 그대로다.
         *
         * @param lines 이미 접힌 줄들. 받는 쪽은 다시 접지 않는다.
         *
         * @param reading 이 창이 "읽는 중"인가(GD-1·GD-2r 둘 다). 글자 크기가 달라서(20 / 18) 받는 쪽도
         * 알아야 한다. **판정은 여기 한 곳에서만 한다** — 그리는 쪽이 따로 판정하면 접은
         * 폭과 그린 폭이 어긋나고, 화면만 봐서는 어느 쪽이 틀렸는지 알 수 없다.
         *
         * @param reserved 상자 아래에 비워 둔 높이(확인쌍 또는 점 셋의 자리). **계산이 쓴 값을
         * 그대로 넘긴다** — 참/거짓으로 넘겼더니 값이 셋이 된 순간 두 쪽이 갈렸다(PR #229 리뷰).
         */
        fun onGuideBox(box: Rect?, lines: List<String>, reading: Boolean, reserved: Float)
    }

    var guideBoxListener: GuideBoxListener? = null

    private val target = RectF()
    private var hasTarget = false
    private var instruction = ""

    /** 안내창이 가리지 말아야 할 영역(키보드·다이얼로그). 창 목록은 서비스만 볼 수 있어 받아 온다. */
    private var avoid: List<GuideBoxLayout.Avoid> = emptyList()

    /** 이 뷰가 화면에서 실제로 어디에 놓였는지. 좌표 보정에 쓴다. */
    private val locationOnScreen = IntArray(2)

    /** 계산해 둔 안내창. 안내가 바뀌거나 창이 옮겨질 때만 다시 만든다. */
    private var layout: GuideBoxLayout.Layout? = null
    private var layoutOriginX = Int.MIN_VALUE
    private var layoutOriginY = Int.MIN_VALUE

    // 색·크기 전부 디자인 토큰(colors.xml·dimens.xml)에서 온다 — 하드코딩·px 계산 금지(#100).
    // 검증 기기(3x 밀도)에서 기존 raw px(12/24)와 dp 토큰(4dp/8dp)이 같은 픽셀값이라 시인성은 보존된다.
    private val highlightStroke = resources.getDimension(R.dimen.davi_overlay_highlight_stroke)
    private val highlightCorner = resources.getDimension(R.dimen.davi_overlay_target_radius)
    private val highlightExpand = resources.getDimension(R.dimen.davi_overlay_target_expand)

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = highlightStroke
        color = ContextCompat.getColor(context, R.color.davi_highlight)
    }

    /**
     * 강조 사각형의 바깥 글로우(#147). 안쪽 선([strokePaint])은 불투명을 유지하고 이것만
     * 숨 쉰다 — 강조를 투명도로 흔들면 읽는 중 테두리와의 층 구분이 무너진다([HighlightMotion]).
     * 프레임마다 새로 만들지 않는다.
     */
    private val highlightGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = ContextCompat.getColor(context, R.color.davi_highlight)
    }
    private val highlightGlowSpread = resources.getDimension(R.dimen.davi_overlay_highlight_glow)

    /**
     * 눌림 파동(#238). 안내한 대상을 실제로 눌렀을 때 그 자리에 한 번 퍼진다.
     *
     * 색은 오버레이 tone 별칭을 거친다 — 완료 확인(GD-3)과 같은 원색이지만 확정 디자인이 오면
     * 이 tone만 따로 바뀔 수 있다. `colors.xml`의 davi_success 금지 주석은 **버튼 배경** 대비
     * 얘기라 테두리·파동에는 걸리지 않는다.
     */
    private val pressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = ContextCompat.getColor(context, R.color.davi_overlay_press)
    }

    /** 파동을 그릴 자리. 강조가 이미 내려간 뒤에도 그려야 해서 [target]과 따로 둔다. */
    private val pressTarget = RectF()

    /** 진행도 0..1. 음수면 파동이 없다. */
    private var pressProgress = -1f

    // 그리지는 않지만 줄바꿈을 재려면 같은 글자여야 한다 — 그리는 쪽과 한 곳에서 만든다.
    private val instructionPaint = GuideBoxStyle.textPaint(context)
    private val readingPaint = GuideBoxStyle.readingTextPaint(context)
    private val instructionPadding = GuideBoxStyle.padding(context)
    private val instructionPaddingV = GuideBoxStyle.paddingVertical(context)
    private val instructionMargin = GuideBoxStyle.margin(context)
    private val instructionMarginTop = GuideBoxStyle.marginTop(context)
    private val instructionMarginBottom = GuideBoxStyle.marginBottom(context)
    private val instructionAvoidGap = GuideBoxStyle.avoidGap(context)
    private val controlRow = GuideBoxStyle.controlRow(context)
    private val dotsRow = resources.getDimension(R.dimen.davi_overlay_dots_row)

    // ── 읽는 중 테두리(GD-1·GD-2r) ────────────────────────────────────────────
    private val readingInset = resources.getDimension(R.dimen.davi_overlay_reading_inset)
    private val readingStroke = resources.getDimension(R.dimen.davi_overlay_reading_stroke)
    private val readingGlow = resources.getDimension(R.dimen.davi_overlay_reading_glow)
    private val readingRadius = resources.getDimension(R.dimen.davi_overlay_reading_radius)

    // 프레임마다 새로 만들지 않는다 — 애니메이션이 도는 동안 매초 60번 할당된다.
    private val readingGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val readingCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val readingRect = RectF()

    private val readingColor = ContextCompat.getColor(context, R.color.davi_overlay_reading)
    private val confirmColor = ContextCompat.getColor(context, R.color.davi_overlay_confirm)
    private val scrimColor = ContextCompat.getColor(context, R.color.davi_overlay_scrim)

    // ── 방향 제스처 시연(GD-2, #152) ──────────────────────────────────────────
    /** 시연할 방향. null이면 그리지 않는다 — 판정은 [GestureMotion.isDemonstrated]가 한다. */
    private var gestureDirection: SwipeDirection? = null

    /**
     * 가장자리 제스처면 그 사양(#222). null이면 스크롤 시연이거나 시연이 없다.
     *
     * **이 값이 곧 두 경로의 분기다** — 서 있으면 손이 화면 변에서 출발하고 강조는 앱이 만든
     * 가장자리 선이며, 없으면 지금까지대로 화면 가운데에서 안내창을 피해 그린다.
     */
    private var edgeGesture: EdgeGesture.Spec? = null

    private val edgeLine = resources.getDimension(R.dimen.davi_overlay_edge_line)

    private val handSize = resources.getDimension(R.dimen.davi_overlay_hand_size)
    private val handTravel = resources.getDimension(R.dimen.davi_overlay_hand_travel)
    private val handShadowOffset = resources.getDimension(R.dimen.davi_overlay_hand_shadow_offset)
    private val handOutline = resources.getDimension(R.dimen.davi_overlay_hand_outline)

    /**
     * 손 아이콘. **직접 그리지 않고 그림을 쓴다** — 검지를 세운 손을 경로로 그리면 각도에 따라
     * 무례한 손짓으로 읽힐 수 있어(#152 실기기 확인) 디자인이 준 아이콘으로 바꿨다.
     *
     * 디자인이 준 래스터 그림을 그대로 쓴다. 저장소의 다른 아이콘은 벡터지만, 이 그림을
     * 벡터로 다시 그리면 선 굵기·곡률이 미묘하게 달라져 준 그림과 어긋난다.
     */
    private val handBitmap by lazy(LazyThreadSafetyMode.NONE) {
        ContextCompat.getDrawable(context, R.drawable.ic_davi_hand)?.toBitmap()
    }
    private val handPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /**
     * 손 뒤에 까는 그림자. 오버레이는 **아래에 어떤 앱이 깔릴지 알 수 없어**, 밝은 화면에서는
     * 흰 손이 통째로 묻힌다. 같은 그림을 어둡게 칠해 살짝 어긋나게 먼저 깔아 깊이를 준다.
     *
     * **밝은 배경 대비는 이 어두운 색이 맡는다**(PR #159 2차 리뷰). 분홍은 [handOutlinePaint]다.
     */
    private val handShadowPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = PorterDuffColorFilter(
            ContextCompat.getColor(context, R.color.davi_overlay_hand_shadow),
            PorterDuff.Mode.SRC_IN,
        )
    }

    /**
     * 손 둘레를 감싸는 강조색 윤곽선. 색만 마이크 글로우에서 가져왔고 **글로우처럼 움직이지는
     * 않는다**(2026-08-17 요청).
     *
     * 그림자는 한쪽으로만 어긋나 두 변에만 생기므로, 흰 화면에서 손의 좌상단 실루엣이 사라진다.
     * 같은 그림을 사방으로 조금씩 어긋나게 깔아 둘레를 채운다.
     */
    private val handOutlinePaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = PorterDuffColorFilter(
            ContextCompat.getColor(context, R.color.davi_overlay_hand_outline),
            PorterDuff.Mode.SRC_IN,
        )
    }

    /**
     * 그림자·윤곽선·손을 **한 장으로 미리 합성해 둔** 그림. 화면에 그릴 때는 이것만 한 번 그린다.
     *
     * 손은 크기도 모양도 프레임마다 변하지 않으므로 매 프레임 열 번 그릴 이유가 없다
     * (PR #159 3차 리뷰 nit). 세션 내내 다른 앱 위에서 도는 뷰라 프레임당 비용이 그대로 남는다.
     *
     * **미리 합성하면 `saveLayer`도 필요 없다.** 레이어를 썼던 이유는 반투명해진 흰 손 너머로
     * 그림자가 비쳐 색이 오가는 것이었는데, 여기서 이미 겹침이 평탄화되므로 그릴 때 알파를
     * 한 번 거는 것으로 같은 결과가 나온다.
     *
     * 좌표 규약: 손 그림의 **중심**이 [handCenterInComposed]만큼 들어간 자리에 있다. 그리는 쪽은
     * 손 중심을 원하는 자리에 맞추기 위해 이 값을 빼야 한다.
     */
    private val handComposed by lazy(LazyThreadSafetyMode.NONE) { composeHand() }

    /** [handComposed] 안에서 손 그림 중심이 놓인 위치(왼쪽 위 기준). */
    private var handCenterInComposed = PointF()

    private val handDst = RectF()
    // ─────────────────────────────────────────────────────────────────────────

    /** 서비스가 요청한 상태. 실제 표시는 onset 지연 뒤라 [readingVisible]과 다를 수 있다. */
    private var reading = false
    private var readingVisible = false
    private var readingPhase = ReadingBorder.STATIC_PHASE

    /**
     * 확인 대기 중이면 그 종류. 읽는 중과 **동시에 켜지지 않는다** — [ReadingBorder.isReading] 참고.
     *
     * 종류마다 크롬이 다르고 **배타적이다**(v2 시안) — 완료 확인(GD-3)은 성공 tone 테두리,
     * 종료 확인(GD-4)은 전면 스크림이다. 한 화면에 둘을 겹치지 않는다.
     */
    private var confirmKind: GuideDisplayState.ConfirmKind? = null

    private val confirmFrame get() = confirmKind == GuideDisplayState.ConfirmKind.DONE

    /**
     * 지금 안내창을 **읽는 중 표시가 가져갔는가.** 서버 안내가 도착하면 [setGuide]가 덮으므로
     * 저절로 내려간다.
     *
     * **문구가 하나가 아니다**(#314) — GD-1은 [R.string.overlay_reading], GD-2r은
     * [R.string.overlay_reading_mid_guide]다. "이 플래그가 서면 문구는 overlay_reading"으로
     * 읽으면 GD-2r에서 조용히 어긋난다.
     */
    private var readingWindow = false

    /**
     * 읽는 중이 덮기 전의 안내. 읽는 중이 끝났는데 **새 안내가 아직 안 왔을 때** 되돌린다.
     *
     * 읽는 중을 "창이 비어 있을 때만"으로 막았던 #229의 제약을 #295가 뒤집었다. 그때 근거는
     * "보고 따라 하던 안내가 사라지면 안 된다"였는데, **읽는 구간에 들어갔다는 것은 화면이
     * 이미 움직였다는 뜻**이라 그 안내는 그 시점에 유효하지 않다.
     *
     * **문구만 든다.** 강조·제스처는 되돌리지 않는다 — 위 근거가 복귀 시점에도 그대로
     * 성립하므로(화면이 움직였다), 자리를 되살리면 엉뚱한 곳을 가리킨다. 문구는 "무엇을
     * 하려던 중이었나"라 자리와 달리 화면이 바뀌어도 읽을 값이 남는다.
     *
     * **[GuideDisplayState]가 아니라 여기 있는 이유** — 저쪽이 미는 것은 *서버 안내*이고
     * 되살릴 때마다 서명을 댄다(`redisplay`·`restoreTarget`·`dismissQuitConfirm`, PR #63
     * 2차 리뷰 P1). 여기서 미는 것은 **읽는 중이라는 뷰 계층의 표시가 잠깐 창을 가져간 것**
     * 뿐이고, 그동안 display는 자기 안내를 계속 들고 있다(`visible`이 true다). 같은 이름의
     * 두 기계가 생긴 셈이라, "밀어 둔 안내"를 찾는 사람은 **미는 주체가 다르다**는 것부터 본다.
     *
     * **순서 의존이 하나 있다.** onset이 걷는 `hasTarget`을 [GuideDisplayState]는 모른다 —
     * 저쪽의 `targetHidden`은 그대로라 `restoreTarget()`이 강조를 되살릴 수 없다. 지금
     * 안전한 이유는 **화면이 움직여 읽는 중이 켜지는 경우 같은 샘플에서 `hideTarget()`이
     * 먼저 돌아 `targetHidden`을 세우고, onset은 250ms 뒤라 언제나 그 뒤**이기 때문이다.
     * 그 순서를 바꾸면 강조가 다음 안내까지 안 돌아온다(PR #298 3차 리뷰).
     */
    private var stashed: String? = null

    /** [readingOnset]이 큐에 있는가. [reading]과 별개다 — [setReading] 주석 참고. */
    private var onsetPending = false

    private val readingOnset = Runnable {
        onsetPending = false
        readingVisible = true
        if (!readingWindow) {
            // 떠 있던 안내는 밀어 둔다 — 읽는 중이 안내 없이 끝나면 되돌린다.
            // **이 판정이 곧 GD-1/GD-2r 구분이다**(#314) — 밀어 둘 안내가 있으면 사용자가
            // 방금 무언가를 누른 뒤이고(GD-2r), 없으면 첫 안내 전이다(GD-1). 새 조건을 만들지 않는다.
            val midGuide = ReadingWindow.isMidGuide(hasPendingGuide = instruction.isNotEmpty())
            if (midGuide) stashed = instruction
            instruction = context.getString(
                if (midGuide) R.string.overlay_reading_mid_guide else R.string.overlay_reading,
            )
            readingWindow = true
            // **강조도 함께 걷는다**(#295). 화면이 바뀐 뒤에도 남으면 엉뚱한 자리를 가리킨다.
            hasTarget = false
            gestureDirection = null
            // 가장자리 선도 함께 걷는다. 변 자체는 안 움직이지만 **읽는 중이라는 것은 셰이드가
            // 이미 내려오는 중이라는 뜻**이라, 그 위에 "여기서 시작하세요"가 남으면 이미 한
            // 동작을 다시 시키는 그림이 된다.
            edgeGesture = null
            // **[avoid]는 걷지 않는다** — 셋 중 이것만 남는 이유가 있다. 위 둘은 *떠난 화면의
            // 좌표를 가리키는* 값이라 되살리면 엉뚱한 곳을 가리키지만, avoid는 가리키는 값이
            // 아니라 "이 영역을 덮지 마라"다. 특히 타이핑 안내의 IME는 hard 회피고(#38 5번:
            // 키보드가 조작 대상 자체다), 비우면 창이 기본 자리(아랫변 55dp)로 내려앉아
            // **키보드 위에 앉아 그 터치를 먹는다**(창 전체가 터치를 흡수한다).
            // 낡은 avoid는 무해하다 — 이미 내려간 키보드를 피해 창이 괜히 위로 갈 뿐이다.
            relayout()
        }
        syncBreathing()
        invalidate()
    }

    private val breathing = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = ReadingBorder.PERIOD_MS
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            readingPhase = it.animatedValue as Float
            invalidate()
        }
    }

    /**
     * 눌림 파동은 **1회 재생**이라 [breathing]과 같은 애니메이터를 쓸 수 없다(#238).
     * 저쪽은 무한 반복이고 읽는 중 테두리와 위상을 공유하는데, 파동은 시작 시점이 사용자
     * 조작에 매여 있어 공유 위상에 얹으면 시작점을 정할 수 없다.
     *
     * **오버레이 안의 구동부는 늘리지 않는다** — 앱 화면([MotionDriver])과 오버레이가 서로 다른
     * 방식을 쓰는 것이 #148의 정리 대상이라, 여기서 세 번째 방식을 들이지 않고 이 뷰가 이미
     * 쓰는 `ValueAnimator`를 그대로 쓴다.
     */
    private val pressWave = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = HighlightMotion.PRESS_DURATION_MS
        interpolator = LinearInterpolator()
        addUpdateListener {
            pressProgress = it.animatedValue as Float
            invalidate()
        }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                pressProgress = -1f
                invalidate()
            }
        })
    }
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 읽는 중 표시를 켜고 끈다. 서비스가 **폴링마다** 부른다 — 켜진 상태로도 true가 다시 오고,
     * 화면을 못 읽는 동안 false가 계속 온다. 그래서 겹쳐 들어와도 안전해야 한다.
     *
     * **가드가 켜짐/꺼짐에서 서로 다르다.** 끄는 쪽은 전이(`reading`)로 막으면 되지만, 켜는
     * 쪽은 **"창을 아직 안 바꿨고 예약도 없을 때"** 로 막는다 — 전이로 막으면 두 번째 읽는
     * 구간부터 문구가 안 바뀐다(#295, 아래 주석).
     */
    fun setReading(on: Boolean) {
        if (on) {
            reading = true
            // **`on == reading`으로 막지 않는다** — 근거는 [ReadingWindow.shouldArmOnset].
            if (ReadingWindow.shouldArmOnset(windowTaken = readingWindow, onsetPending = onsetPending)) {
                onsetPending = true
                // 응답이 빠르면 러너블이 아예 실행되지 않아 한 프레임도 그리지 않는다.
                postDelayed(readingOnset, ReadingBorder.ONSET_DELAY_MS)
            }
        } else {
            if (!reading) return
            reading = false
            removeCallbacks(readingOnset)
            onsetPending = false
            readingVisible = false
            // 읽는 중이 만든 창은 읽는 중이 걷는다. 서버 안내가 이미 덮었으면 남긴다.
            if (readingWindow) restoreOrClear()
            syncBreathing()
            invalidate()
        }
    }

    /**
     * 읽는 중이 끝났다. 밀어 둔 안내가 있으면 되돌리고, 없으면 창을 걷는다.
     *
     * **되돌림이 흔한 경우는 아니다** — 보통은 새 안내가 [setGuide]로 먼저 덮는다. 이 경로는
     * 읽는 중이 **안내 없이** 끝난 때(요청 실패·백오프 종료)를 위한 것이고, 그때 빈 창을
     * 남기면 따라 하던 사람이 근거를 통째로 잃는다.
     */
    private fun restoreOrClear() {
        val text = stashed
        if (text == null) {
            clearTarget()
            return
        }
        stashed = null
        readingWindow = false
        instruction = text
        relayout()
    }

    /**
     * 확인 화면의 크롬을 켜고 끈다(없으면 null).
     *
     * 완료 확인(GD-3)은 화면 가장자리 테두리다 — 읽는 중 테두리와 **같은 자리·같은 두께**이고
     * 색만 다르다. 서버 판단이 아니라 사용자 답을 기다리는 중임을 화면 전체로 알린다.
     * 종료 확인(GD-4)은 전면 스크림이다 — 테두리는 켜지 않는다.
     *
     * **브리딩하지 않는다.** 숨 쉬는 것은 "지금 처리 중"의 표현이고 확인은 멈춰 선 상태다.
     * 그래서 [syncBreathing]의 조건에도 넣지 않는다 — 확인 중에 강조도 읽는 중도 없으면
     * 애니메이터는 멈춰 있는 것이 맞다. 지연(onset)도 없다: 답할 자리라 곧바로 보여야 한다.
     */
    fun setConfirmChrome(kind: GuideDisplayState.ConfirmKind?) {
        if (kind == confirmKind) return
        confirmKind = kind
        invalidate()
    }

    /**
     * 브리딩 애니메이터를 켜고 끈다. **읽는 중 테두리와 강조 사각형이 이 하나를 공유한다**(#147).
     *
     * 구동부를 하나로 두는 이유는 둘이 동시에 뜰 수 있어서다 — 각자 애니메이터를 가지면 같은
     * 뷰가 한 프레임에 두 번 무효화되고, 주기가 어긋나면 두 테두리가 따로 논다. 공유하면 위상이
     * 자동으로 맞는다.
     *
     * 둘 다 없으면 멈춘다. 오버레이는 세션 내내 다른 앱 위에 떠 있으므로, 그릴 것이 없는데
     * 도는 것은 그대로 배터리다.
     *
     * 애니메이터가 시스템 설정(개발자 옵션·절전)으로 꺼진 기기에서는 [ValueAnimator]가 즉시
     * 끝나 표시가 사라진다. 그때는 브리딩 없이 가장 진한 한 장을 그린다.
     */
    private fun syncBreathing() {
        val needed = readingVisible || hasTarget || gestureDirection != null
        if (!needed) {
            breathing.cancel()
            return
        }
        if (!ValueAnimator.areAnimatorsEnabled()) {
            readingPhase = ReadingBorder.STATIC_PHASE
            return
        }
        if (!breathing.isStarted) breathing.start()
    }

    /**
     * 강조할 영역(없으면 null)과 안내문. 서버가 화면에 없는 대상을 골라도 안내문은 보여준다.
     *
     * [targetSuppressed]가 서면 **강조·손 시연을 무엇도 그리지 않는다** — 문구만 남는 억제
     * 상태다([GuideDisplayState.hideTarget]·`stuckContent`). bounds·direction이 지워진 것만
     * 믿으면 가장자리 제스처(#222)가 [actionType]에서 되살아나므로 신호를 직접 본다
     * (PR #343 리뷰 P1). [actionType] 자체는 남긴다 — 안내창 회피(IME hard 판정)가 쓴다.
     */
    fun setGuide(
        bounds: Rect?,
        instruction: String,
        avoid: List<GuideBoxLayout.Avoid> = emptyList(),
        actionType: ActionType? = null,
        direction: SwipeDirection? = null,
        targetSuppressed: Boolean = false,
    ) {
        hasTarget = bounds != null && !targetSuppressed
        // 방향 제스처만 시연한다(#152). 판정은 GestureMotion이 하고 여기서는 결과만 든다.
        //
        // **가장자리 제스처는 서버 값을 거치지 않는다**(#222). 방향이 액션 종류로 정해져 있어
        // [GestureMotion.fingerDirectionFor]를 태울 것이 없고, 태우면 없는 값을 뒤집게 된다.
        edgeGesture = if (targetSuppressed) null else EdgeGesture.specFor(actionType)
        gestureDirection = if (targetSuppressed) {
            null
        } else {
            edgeGesture?.fingerDirection
                ?: direction.takeIf { GestureMotion.isDemonstrated(actionType, direction) }
        }
        if (bounds != null) target.set(bounds)
        // 가장자리 선는 서버 target을 덮는다 — 이 액션에 노드가 올 일은 없지만, 와도 가리킬 것은
        // "손을 대는 자리"지 그 노드가 아니다.
        syncEdgeTarget()
        this.instruction = instruction.trim()
        readingWindow = false
        // 새 안내가 왔으면 밀어 둔 옛 안내는 버린다 — 되돌릴 이유가 사라졌다(#295).
        stashed = null
        this.avoid = avoid
        // 강조가 생기거나 사라지면 브리딩이 필요한지도 함께 바뀐다(#147).
        syncBreathing()
        relayout()
        invalidate()
    }

    /**
     * 안내한 대상을 눌렀다는 신호를 그 자리에 한 번 준다(#238).
     *
     * **모션을 끈 사용자에게는 그리지 않는다.** 시스템 접근성 설정을 덮어쓰지 않는다 — 파동이
     * 빠져도 강조는 그대로 내려가므로 안내는 이어진다. 시작 판정은 여기서 하고 **그리는 시점에
     * 다시 본다**([drawPressWave]) — 콜드 스타트 캐시 때문에 시작 판정만으로는 부족하다(#148).
     *
     * 절전 모드는 이 판정에 잡히지 않는다(#148 실측: `low_power=1`에서도 모션이 돌았다).
     * "절전 = 파동 없음"을 전제하지 말 것.
     */
    fun pressFeedback(bounds: Rect) {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        pressTarget.set(bounds)
        pressWave.cancel()
        pressWave.start()
    }

    fun clearTarget() {
        hasTarget = false
        readingWindow = false
        // 창을 통째로 걷을 때는 되돌릴 것도 없다. 남기면 다음 읽는 중이 끝날 때 이전
        // 세션의 안내가 되살아난다(#295).
        stashed = null
        pressWave.cancel()
        gestureDirection = null
        edgeGesture = null
        instruction = ""
        avoid = emptyList()
        syncBreathing()
        applyLayout(null)
        invalidate()
    }

    /**
     * 화면 가장자리 테두리. 굵고 옅은 글로우 위에 안쪽 선을 얹는다. 어느 것도 대상 영역을
     * 가리키지 않는다 — 화면 전체에 깔리는 상태 표시다.
     *
     * 두 tone이 이 한 자리를 나눠 쓴다 — 읽는 중(숨 쉬듯 밝기가 오간다)과 완료 확인(정지).
     * 둘은 동시에 켜지지 않으므로 확인 쪽을 먼저 본다.
     */
    private fun drawReadingBorder(canvas: Canvas) {
        if (!confirmFrame && !readingVisible) return
        if (width == 0 || height == 0) return
        val phase = if (confirmFrame) ReadingBorder.STATIC_PHASE else readingPhase
        val color = if (confirmFrame) confirmColor else readingColor
        val frame = ReadingBorder.frame(phase, readingStroke, readingGlow)
        // 글로우가 두꺼워져도 화면 밖으로 새지 않게 가장 두꺼운 절반만큼 안으로 물린다.
        val margin = readingInset + (readingStroke + readingGlow) / 2f
        readingRect.set(margin, margin, width - margin, height - margin)
        // 색을 먼저 넣는다 — setColor가 alpha까지 덮으므로 순서가 뒤집히면 투명도가 사라진다.
        // **두께가 0이면 후광 자체를 건너뛴다.** 안 건너뛰면 코어와 같은 자리에 alpha 20~50
        // 한 겹이 더 깔려, 값이 0인데도 테두리가 의도보다 진해진다(PR #229 리뷰).
        if (readingGlow > 0f) {
            readingGlowPaint.color = color
            readingGlowPaint.strokeWidth = frame.glowStroke
            readingGlowPaint.alpha = frame.glowAlpha
            canvas.drawRoundRect(readingRect, readingRadius, readingRadius, readingGlowPaint)
        }
        readingCorePaint.color = color
        readingCorePaint.strokeWidth = frame.coreStroke
        readingCorePaint.alpha = frame.coreAlpha
        canvas.drawRoundRect(readingRect, readingRadius, readingRadius, readingCorePaint)
    }

    /**
     * 방향 제스처 시연 — 손이 **손가락 방향**으로 밀린다.
     *
     * **자리를 정하는 규칙이 둘이고 [edgeGesture]가 가른다**(#222).
     * - 스크롤: 화면 정중앙이 기본이되 안내창이 그 자리를 쓰면 위/아래 남은 공간 중 넓은 쪽으로
     *   옮긴다([GestureMotion.verticalCenterFor], 2026-08-17 요청).
     * - 가장자리(퀵세팅·앱 서랍): 강조한 선 위에서 출발한다. 여기서는 손이 비키지 않고
     *   **안내창이 비킨다** — 시작 지점이 고정인 것 자체가 가르치려는 내용이라서다.
     *
     * **방향을 여기서 보정하지 않는다.** [gestureDirection]은 이미 보정을 마친 손가락 방향이다
     * (보정 자리는 `GuideAccessibilityService`, 규칙은 [GestureMotion.fingerDirectionFor]).
     * 여기서 한 번 더 손대면 보정이 두 번 걸려 아무 일도 안 한 것과 같아진다.
     */
    private fun drawGestureHand(canvas: Canvas) {
        val direction = gestureDirection ?: return
        if (width == 0 || height == 0) return
        val composed = handComposed ?: return

        val animating = ValueAnimator.areAnimatorsEnabled()
        val phase = if (animating) readingPhase else GestureMotion.STATIC_PHASE
        val alpha = if (animating) GestureMotion.alphaAt(phase) else GestureMotion.staticAlpha()
        if (alpha <= 0f) return

        val travel = GestureMotion.travelFor(direction, handTravel)
        val spec = edgeGesture
        val centerX: Float
        val centerY: Float
        if (spec == null) {
            // 스크롤 — 어디서 시작해도 되므로 화면 가운데에서, 안내창만 세로로 피해 그린다.
            // 손이 훑는 세로 폭 — 세로 이동이면 이동 거리까지, 가로 이동이면 손 높이만.
            val bandHeight = abs(travel.dy) + handSize
            // 안내창은 화면 좌표라 이 뷰의 로컬 좌표로 옮겨서 본다.
            val box = layout?.box
            centerX = GestureMotion.horizontalCenterFor(0f, width.toFloat(), handSize)
            centerY = GestureMotion.verticalCenterFor(
                canvasTop = 0f,
                canvasBottom = height.toFloat(),
                bandHeight = bandHeight,
                boxTop = box?.let { it.top - layoutOriginY },
                boxBottom = box?.let { it.bottom - layoutOriginY },
            )
        } else {
            // 가장자리 — 시작 지점이 고정인 것이 이 제스처의 전부라 **안내창을 피해 옮기지 않는다.**
            // 비키는 쪽은 안내창이다([handSweep]가 회피로 넘긴다).
            //
            // 선을 로컬 좌표로 다시 잡는다 — [target]은 화면 좌표라 원점 보정을 한 번 더 타야
            // 하는데, 같은 비율에서 다시 세우면 그 의존이 아예 없어진다.
            val band = EdgeGesture.bandFor(spec, 0f, 0f, width.toFloat(), height.toFloat(), edgeLine)
            centerX = GestureMotion.horizontalCenterFor(
                canvasLeft = 0f,
                canvasRight = width.toFloat(),
                handWidth = handSize,
                anchorLeft = band.left,
                anchorRight = band.right,
            )
            centerY = GestureMotion.edgeCenterFor(
                EdgeGesture.startEdgeY(spec, 0f, height.toFloat()),
                travel,
            )
        }

        // 애니메이터가 꺼진 기기에서는 출발점에 잔상을 함께 깔아 방향을 읽히게 한다
        // (PR #159 리뷰 5번 — 한 장만으로는 어느 쪽으로 가라는 것인지 알 수 없다).
        if (!animating) {
            drawHandAt(
                canvas,
                composed,
                travel,
                centerX,
                centerY,
                GestureMotion.STATIC_GHOST_PROGRESS,
                GestureMotion.staticGhostAlpha(),
            )
        }
        drawHandAt(canvas, composed, travel, centerX, centerY, GestureMotion.progressAt(phase), alpha)
    }

    /**
     * 이동 구간의 [progress] 지점에 미리 합성해 둔 손([handComposed])을 한 번 그린다.
     *
     * 그림자·윤곽선이 이미 이 그림 안에 평탄화돼 있으므로 알파를 그냥 걸면 된다 — 색이 서로
     * 비치지 않는다([handComposed] 문서).
     */
    private fun drawHandAt(
        canvas: Canvas,
        composed: Bitmap,
        travel: GestureMotion.Travel,
        centerX: Float,
        centerY: Float,
        progress: Float,
        alpha: Float,
    ) {
        // 이동 구간의 절반을 중심에 두어, 손이 ([centerX], [centerY])를 지나가며 움직인다.
        val cx = centerX - travel.dx / 2f + travel.dx * progress
        val cy = centerY - travel.dy / 2f + travel.dy * progress

        // 합성 그림 안에서 손 중심이 들어가 있는 만큼 빼야 손 중심이 (cx, cy)에 온다.
        val left = cx - handCenterInComposed.x
        val top = cy - handCenterInComposed.y
        handDst.set(left, top, left + composed.width, top + composed.height)
        handPaint.alpha = (alpha * 255).toInt().coerceIn(0, 255)
        canvas.drawBitmap(composed, null, handDst, handPaint)
    }

    /**
     * 그림자 → 윤곽선 → 손 순서로 한 장에 합성한다. 크기가 프레임마다 변하지 않으므로 한 번만 돈다.
     *
     * 합성은 **화면에 그릴 크기 그대로** 한다 — 그래야 윤곽선 두께와 그림자 offset이 dp대로 남는다.
     * 원본 해상도에서 합성한 뒤 축소하면 그 둘이 함께 줄어든다.
     */
    private fun composeHand(): Bitmap? {
        val source = handBitmap ?: return null
        // **그림 비율을 지킨다.** 손 아이콘은 세로로 길어서 정사각형에 맞추면 찌그러진다.
        val drawH = handSize
        val drawW = handSize * source.width / source.height
        // 윤곽선은 사방으로, 그림자는 오른쪽·아래로만 삐져나온다.
        val padStart = handOutline
        val padEnd = maxOf(handShadowOffset, handOutline)
        val composed = createBitmap(
            ceil(drawW + padStart + padEnd).toInt(),
            ceil(drawH + padStart + padEnd).toInt(),
        )
        val canvas = Canvas(composed)
        val handLeft = padStart
        val handTop = padStart

        // 1) 어두운 그림자 — 밝은 배경에서의 대비를 맡는다.
        handDst.set(
            handLeft + handShadowOffset,
            handTop + handShadowOffset,
            handLeft + drawW + handShadowOffset,
            handTop + drawH + handShadowOffset,
        )
        handShadowPaint.alpha = SHADOW_ALPHA_255
        canvas.drawBitmap(source, null, handDst, handShadowPaint)

        // 2) 강조색 윤곽선 — 사방으로 조금씩 어긋나게 깔아 둘레를 채운다.
        handOutlinePaint.alpha = 255
        OUTLINE_OFFSETS.forEach { (ox, oy) ->
            val dx = ox * handOutline
            val dy = oy * handOutline
            handDst.set(handLeft + dx, handTop + dy, handLeft + drawW + dx, handTop + drawH + dy)
            canvas.drawBitmap(source, null, handDst, handOutlinePaint)
        }

        // 3) 손.
        handDst.set(handLeft, handTop, handLeft + drawW, handTop + drawH)
        val opaqueHand = Paint(Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(source, null, handDst, opaqueHand)

        handCenterInComposed = PointF(handLeft + drawW / 2f, handTop + drawH / 2f)
        return composed
    }

    /** 창 크기가 잡히기 전에 온 안내는 자리를 못 잡는다. 크기가 생기면 그때 계산한다. */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        relayout()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(readingOnset)
        onsetPending = false
        breathing.cancel()
        pressWave.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 스크림(GD-4)도 테두리와 같은 이유로 translate 앞이다 — 뷰가 차지한 범위를 덮는다.
        // 그 범위는 API 30+에서 화면 전체다 — #222가 이 창을 컷아웃 위까지 올려 `[0,0]`부터
        // 시작하므로(2026-08-21 실측) **상태표시줄도 덮인다.** 시안(전면)과 같은 방향이지만
        // 그 확정은 #178 몫이다. API 28~29는 컷아웃 게이트 밖이라(`OverlayWindowParams`의 R
        // 게이트) 여전히 상태표시줄 아래(`[0,103]`)에서 시작해 안 덮인다.
        // **터치는 막지 않는다.** 이 창이 FLAG_NOT_TOUCHABLE이고, 뒤집으면 "대신 조작하지
        // 않는다"(SPEC §2)와 부딪친다. 막을 이유도 없다 — 이 화면은 ○/× 말고는 사라지지
        // 않으므로 아래 앱을 만져도 답할 자리가 남는다.
        if (confirmKind == GuideDisplayState.ConfirmKind.QUIT) canvas.drawColor(scrimColor)
        // 읽는 중 테두리는 **뷰 로컬 좌표**라 아래 translate 앞에서 그린다. 창이 밀려 놓이는
        // 기기에서도 뷰가 실제로 차지한 사각형을 따라가야 잘리지 않는다.
        drawReadingBorder(canvas)
        // 손은 **화면 기준**으로 그린다(#152 결정) — 스크롤이면 화면 중앙, 가장자리 제스처면
        // 화면 변이고, 어느 쪽도 대상 노드 기준이 아니다. 그래서 읽는 중 테두리와 같이 뷰 로컬
        // 좌표를 쓴다. 아래 translate 앞이어야 하는 이유가 같다.
        drawGestureHand(canvas)
        if (!hasTarget && instruction.isEmpty() && pressProgress < 0f) return

        // 화면 좌표 -> 캔버스 좌표 보정.
        //
        // FLAG_LAYOUT_IN_SCREEN을 줘도 뷰가 항상 (0,0)에 놓이지는 않는다. 실측에서 전화 앱은
        // 맞는데 런처에서만 상태표시줄 높이(약 96px)만큼 아래로 밀렸다 — 창마다 인셋이 다르게
        // 적용된다. 그래서 "어느 창이든 (0,0)일 것"이라 가정하지 않고, 뷰가 실제로 놓인 위치를
        // 물어보고 그만큼 되돌린다. 뷰가 (0,0)이면 이 보정은 아무 일도 하지 않는다.
        getLocationOnScreen(locationOnScreen)
        // 창이 옮겨졌으면 자리를 다시 잡는다. 평소에는 좌표 두 개를 비교할 뿐,
        // 매 프레임 줄바꿈을 다시 접지 않는다(#38 2번).
        if (locationOnScreen[0] != layoutOriginX || locationOnScreen[1] != layoutOriginY) relayout()
        canvas.translate(-locationOnScreen[0].toFloat(), -locationOnScreen[1].toFloat())

        if (hasTarget && edgeGesture != null) {
            // 가장자리 제스처는 **면이 아니라 선**이다(2026-08-21 요청). 아래 사각형 경로와
            // 배타적이다 — 둘 다 그리면 선 위에 테두리가 겹쳐 도로 상자가 된다.
            drawEdgeLine(canvas)
        } else if (hasTarget) {
            // 자리는 [highlightBounds]가 정한다 — **레이아웃도 같은 사각형을 피한다.**
            // 근거(후광을 안 섞는 이유·가장자리 클램프)는 그 KDoc에 있다.
            //
            // 그 함수가 주는 것은 **바깥변**이고 drawRoundRect는 중심선에 칠하므로,
            // 여기서 획 절반을 안으로 물려야 잉크의 바깥이 그 변에 맞는다.
            val frame = HighlightMotion.frame(readingPhase, highlightStroke, highlightGlowSpread)
            val rect = highlightBounds()
            val half = highlightStroke / 2f
            val left = rect.left + half
            val top = rect.top + half
            val right = rect.right - half
            val bottom = rect.bottom - half

            // 후광은 겹을 쌓아 번지게 한다 — 굵은 선 하나로는 이중 테두리로 보인다(실기기 실측).
            // 바깥으로 갈수록 빠르게 옅어져 마이크 뒤 글로우와 같은 인상을 준다(#147).
            if (frame.glowBaseAlpha > 0) {
                highlightGlowPaint.strokeWidth =
                    HighlightMotion.ringStrokeFor(frame.glowSpread, highlightStroke)
                for (ring in 0 until HighlightMotion.GLOW_RING_COUNT) {
                    val alpha = HighlightMotion.ringAlphaAt(ring, frame.glowBaseAlpha)
                    if (alpha <= 0) continue
                    val offset = HighlightMotion.ringOffsetAt(ring, frame.glowSpread)
                    highlightGlowPaint.alpha = alpha
                    canvas.drawRoundRect(
                        left - offset,
                        top - offset,
                        right + offset,
                        bottom + offset,
                        highlightCorner + offset,
                        highlightCorner + offset,
                        highlightGlowPaint,
                    )
                }
            }
            strokePaint.strokeWidth = frame.coreStroke
            canvas.drawRoundRect(
                left,
                top,
                right,
                bottom,
                highlightCorner,
                highlightCorner,
                strokePaint,
            )
        }

        // 강조와 독립이다 — 강조가 내려간 뒤에도 남은 파동은 마저 퍼진다.
        drawPressWave(canvas)
    }

    /**
     * 시작 지점을 **변에 걸린 선 하나**로 그린다(#222, 2026-08-21 요청).
     *
     * **사각형이 아닌 이유는 뜻이 다르기 때문이다.** 강조 사각형은 "이 안을 눌러라"인데 여기서
     * 가리키는 것은 누를 영역이 아니라 손을 대고 끌기 시작하는 **경계**다. 면으로 그리면
     * 시니어가 그 안을 탭하고, 탭으로는 셰이드가 안 열린다.
     *
     * **색·후광·브리딩은 강조와 같은 것을 쓴다**([HighlightMotion]) — 배워야 할 기호를 늘리지
     * 않으려는 것이라, 달라지는 것은 겹을 쌓는 방향뿐이다. 사각형은 바깥으로 넓혀 가며 쌓지만
     * 선은 **제자리에서 굵어지며** 쌓는다. 그래서 획의 절반이 화면 밖으로 나가 잘리고, 남은
     * 절반이 화면 안쪽으로 번진다 — 화면 밖에서 손이 들어온다는 뜻이 그림에 그대로 남는다.
     */
    private fun drawEdgeLine(canvas: Canvas) {
        val spec = edgeGesture ?: return
        val frame = HighlightMotion.frame(readingPhase, edgeLine, highlightGlowSpread)
        // 끝을 둥글게 — 퀵세팅은 화면 절반에서 끊기므로 각지게 자르면 잘린 상자로 되읽힌다.
        // 사각형 경로는 drawRoundRect라 cap을 보지 않으므로 되돌릴 필요가 없다.
        //
        // **cap은 선 구간 밖으로 획 절반만큼 나간다 — 의도된 범위다**(PR #343 리뷰). 퀵세팅의
        // 안쪽 끝(x = 폭 × 0.5)에서 후광까지 12px이 알림창 절반으로 넘어간 것을 실측했다
        // (2026-08-21, 획 528~ / 구간 경계 540). 잘라내면 각진 끝으로 되돌아가고, 12px은
        // 시작 위치를 오독시킬 크기가 아니다. [highlightBounds]·안내창 회피가 이 돌출을 안
        // 치는 것도 같은 크기라 무해하다. 화면 변 쪽 끝의 돌출은 화면 밖이라 캔버스가 자른다.
        strokePaint.strokeCap = Paint.Cap.ROUND
        highlightGlowPaint.strokeCap = Paint.Cap.ROUND
        // 획은 중심선에 칠해지므로 절반을 안으로 물려야 잉크의 바깥이 변에 맞는다.
        val y = when (spec.edge) {
            EdgeGesture.Edge.TOP -> target.top + edgeLine / 2f
            EdgeGesture.Edge.BOTTOM -> target.bottom - edgeLine / 2f
        }
        if (frame.glowBaseAlpha > 0) {
            for (ring in 0 until HighlightMotion.GLOW_RING_COUNT) {
                val alpha = HighlightMotion.ringAlphaAt(ring, frame.glowBaseAlpha)
                if (alpha <= 0) continue
                // 바깥으로 옮기는 대신 **그 자리에서 굵힌다.** 옮기면 변 밖으로 통째로 나가
                // 후광이 통째로 잘린다.
                highlightGlowPaint.strokeWidth =
                    edgeLine + 2f * HighlightMotion.ringOffsetAt(ring, frame.glowSpread)
                highlightGlowPaint.alpha = alpha
                canvas.drawLine(target.left, y, target.right, y, highlightGlowPaint)
            }
        }
        strokePaint.strokeWidth = frame.coreStroke
        canvas.drawLine(target.left, y, target.right, y, strokePaint)
    }

    /**
     * 눌림 파동. 강조 후광과 같은 겹 쌓기를 쓴다 — `BlurMaskFilter`를 피하는 이유가 같다
     * ([HighlightMotion.GLOW_RING_COUNT]).
     *
     * **화면 전환에 매지 않는다.** 강조는 "다음에 누를 곳"이라 화면이 바뀌면 거짓이 되지만,
     * 파동은 "방금 누른 곳"이라 화면이 바뀌어도 거짓이 아니다. 도는 중에 끊으면 디졸브가
     * 뚝 잘려 오히려 깜빡임으로 읽힌다.
     */
    private fun drawPressWave(canvas: Canvas) {
        if (pressProgress < 0f) return
        // **그리는 시점에 다시 본다.** 콜드 스타트에서는 `areAnimatorsEnabled`의 프로세스 캐시가
        // 첫 창 부착보다 늦게 동기화돼, 시작 판정만 믿으면 배율 0인데도 한 번 돈다(#148 실측).
        // 이 파일의 손 모션([drawGestureHand])이 같은 이유로 같은 자리에서 재확인한다.
        //
        // 손과 달리 **정지 프레임을 그리지 않는다** — 파동은 한 번 퍼지고 사라지는 것이라
        // 멈춘 링이 남으면 "여기가 대상"이라는 잘못된 신호가 된다.
        if (!ValueAnimator.areAnimatorsEnabled()) {
            pressWave.cancel()
            return
        }
        val frame = HighlightMotion.pressFrame(
            pressProgress,
            highlightGlowSpread * HighlightMotion.PRESS_SPREAD_RATIO,
        )
        if (frame.alpha <= 0) return
        pressPaint.strokeWidth = HighlightMotion.ringStrokeFor(frame.spread, highlightStroke)
        for (ring in 0 until HighlightMotion.GLOW_RING_COUNT) {
            val alpha = HighlightMotion.ringAlphaAt(ring, frame.alpha)
            if (alpha <= 0) continue
            val offset = HighlightMotion.ringOffsetAt(ring, frame.spread)
            pressPaint.alpha = alpha
            canvas.drawRoundRect(
                pressTarget.left - offset,
                pressTarget.top - offset,
                pressTarget.right + offset,
                pressTarget.bottom + offset,
                highlightCorner + offset,
                highlightCorner + offset,
                pressPaint,
            )
        }
    }

    /**
     * 이 뷰가 화면에서 차지한 사각형. **아무것도 물리지 않는다** — 무엇을 뺄지는 호출자가 정한다.
     *
     * 한 자리에 모으는 이유는 이 파일이 반복해서 세운 원칙이다 — 두 곳이 각자 세우면 원점
     * 보정이 한 번 더 바뀔 때 한쪽만 고쳐진다([reservedHeight] KDoc, PR #229 리뷰).
     * 예전 `drawableBounds()`가 이 역할을 겸했는데, 물림이 잘못 붙어 있어 지우면서 역할까지
     * 같이 사라졌었다(PR #255 리뷰).
     */
    private fun viewBounds() = GuideBoxLayout.Bounds(
        left = locationOnScreen[0].toFloat(),
        top = locationOnScreen[1].toFloat(),
        right = (locationOnScreen[0] + width).toFloat(),
        bottom = (locationOnScreen[1] + height).toFloat(),
    )

    /**
     * 강조가 화면에서 차지하는 **바깥변** 사각형 — 획의 바깥 가장자리다.
     *
     * **레이아웃이 피해야 하는 것은 대상이 아니라 이것이다.** 레이아웃에 원본 bounds를
     * 넘기면 회피 여백이 그 차이만큼 조용히 줄고 겹침 판정도 헐거워진다 — 값을 안 건드리고
     * **재는 기준을 바꿔서** 여백이 주는 자리다(PR #239 리뷰 1, #229 리뷰 B와 같은 모양).
     * 확장이 0인 지금은 차이가 4dp뿐이지만, 확장을 다시 넣는 순간 되풀이된다.
     * **그리기와 계산이 이 함수 하나를 본다** — 그리는 쪽은 여기서 획 절반을 안으로 물린다.
     *
     * **화면 밖으로 나가면 뷰 사각형 안으로 당긴다** — 실측에서 내비게이션 바 버튼처럼
     * bounds 아래가 화면 높이와 같으면 아랫변이 잘렸다. **시스템 바를 빼지 않는 이유**는
     * 강조 대상이 내비바 버튼일 수 있어서다 — 안내창이 앉을 범위([boxBounds])는 그걸 뺀다.
     *
     * **후광 번짐은 이 계산에 섞지 않는다**(#147). 섞었더니 화면 가장자리에 붙은 대상(런처
     * 독의 카메라)에서 사각형이 아이콘 안쪽으로 밀려 찌그러져 보였다 — 선은 대상에 붙어야
     * 하고 후광은 화면 밖으로 나가면 캔버스가 알아서 자른다. 같은 이유로 **글로우가 가장
     * 두꺼워졌을 때가 아니라 고정 두께로** 잡는다: 매 프레임 두께에 맞춰 물리면 숨 쉬는 동안
     * 사각형이 커졌다 작아졌다 해서 대상이 흔들려 보인다. 자리는 고정하고 두께·투명도만
     * 변해야 "숨 쉰다"로 읽힌다.
     */
    private fun highlightBounds(): GuideBoxLayout.Bounds {
        // **자를 때 선 두께를 물리지 않는다.** 이 함수가 돌려주는 것이 바깥변이라, 잉크의
        // 바깥이 화면 끝에 딱 맞아야 한다. 획 절반을 물린 범위에 대고 자르면 절반을 두 번
        // 빼서 가장자리 대상에서 강조가 2dp 안으로 밀린다(PR #239 재리뷰 1) — 위 KDoc이
        // 인용한 #147의 찌그러짐과 같은 방향이다.
        // 중심선으로 되돌리는 것은 그리는 쪽의 `+ half`가 한다.
        // 자를 범위는 [viewBounds]가 준다 — 화면 좌표와 View 속성을 같은 식에서 섞지 않는다
        // (PR #239 리뷰가 짚은 `View.left` 가림이 구조적으로 사라진다).
        val view = viewBounds()
        val out = highlightExpand + highlightStroke
        return GuideBoxLayout.Bounds(
            left = (target.left - out).coerceAtLeast(view.left),
            top = (target.top - out).coerceAtLeast(view.top),
            right = (target.right + out).coerceAtMost(view.right),
            bottom = (target.bottom + out).coerceAtMost(view.bottom),
        )
    }

    /**
     * 상자 아래에 비워 둘 높이. **계산과 그리기가 이 함수 하나를 본다** — 두 곳이 각자
     * 조건을 세면 값이 셋이 되는 순간 갈린다(PR #229 리뷰).
     *
     * 확인쌍은 컨트롤 행, 읽는 중(GD-1·GD-2r)은 문구 아래 점 셋의 자리다. 조작 안내는 종료 X가
     * 모서리로 가서 아무것도 비우지 않는다(v2, #178).
     */
    private fun reservedHeight(): Float = when {
        confirmKind != null -> controlRow
        readingWindow -> dotsRow
        else -> 0f
    }

    /**
     * 가장자리 제스처의 강조 사각형을 앱이 만들어 [target]에 넣는다(#222).
     *
     * **서버가 줄 수 없는 좌표다.** 이 액션들에는 가리킬 노드가 애초에 없어 `highlight miss`로
     * 빠졌다(HANDOFF §9의 "유일한 MISS"). 그래서 앱이 화면 변에서 좌표를 세운다.
     *
     * **표현은 기존 강조를 그대로 재사용한다**(#222 정하고 갈 것 1). 같은 색·같은 후광·같은
     * 브리딩이고 자리만 다르다 — 새 표현을 하나 더 두면 시니어가 배워야 할 기호가 늘고, "빨간
     * 것이 지금 볼 곳"이라는 규칙은 대상이 노드든 변이든 그대로 성립한다. 강조로 흘려보내면
     * 안내창 회피([GuideBoxLayout]의 `target`)도 공짜로 따라온다.
     *
     * 좌표계는 [target]의 규약대로 **화면 좌표**다.
     */
    private fun syncEdgeTarget() {
        val spec = edgeGesture ?: return
        if (width == 0 || height == 0) return
        val view = viewBounds()
        val band = EdgeGesture.bandFor(spec, view.left, view.top, view.right, view.bottom, edgeLine)
        target.set(band.left, band.top, band.right, band.bottom)
        hasTarget = true
    }

    /**
     * 손이 훑고 지나가는 자리. 가장자리 제스처에서만 있고, **안내창이 이만큼을 비켜 간다**(#222).
     *
     * 스크롤은 손이 안내창을 피해 움직이지만([GestureMotion.verticalCenterFor]) 가장자리 제스처는
     * 시작 지점이 고정이라 손이 비킬 수 없다. 회피의 방향이 뒤집힌 셈이고, 그래서 **절대 회피**다 —
     * 가리면 "어디서 시작하는가"라는 이 안내의 알맹이가 통째로 안 보인다.
     *
     * 손 그림은 세로로 길어 실제 폭이 [handSize]보다 좁다. 넉넉히 잡아 두는 쪽이 안전하므로
     * 정사각으로 친다 — 회피는 넓게 잡아 손해 볼 것이 여백뿐이다.
     */
    private fun handSweep(): GuideBoxLayout.Avoid? {
        val spec = edgeGesture ?: return null
        if (width == 0 || height == 0) return null
        val view = viewBounds()
        val band = EdgeGesture.bandFor(spec, view.left, view.top, view.right, view.bottom, edgeLine)
        val centerX = GestureMotion.horizontalCenterFor(
            canvasLeft = view.left,
            canvasRight = view.right,
            handWidth = handSize,
            anchorLeft = band.left,
            anchorRight = band.right,
        )
        val edgeY = EdgeGesture.startEdgeY(spec, view.top, view.bottom)
        val travel = GestureMotion.travelFor(spec.fingerDirection, handTravel)
        val half = handSize / 2f
        val reach = edgeY + travel.dy
        return GuideBoxLayout.Avoid(
            GuideBoxLayout.Bounds(
                left = (centerX - half).coerceAtLeast(view.left),
                top = (minOf(edgeY, reach) - half).coerceAtLeast(view.top),
                right = (centerX + half).coerceAtMost(view.right),
                bottom = (maxOf(edgeY, reach) + half).coerceAtMost(view.bottom),
            ),
            hard = true,
        )
    }

    private fun relayout() {
        getLocationOnScreen(locationOnScreen)
        layoutOriginX = locationOnScreen[0]
        layoutOriginY = locationOnScreen[1]
        // 창 크기·자리가 바뀌면 가장자리 선도 따라가야 한다 — 아래 계산이 그 사각형을 회피한다.
        syncEdgeTarget()

        // 크기가 없으면 폭을 모르니 접을 수 없다. onSizeChanged가 다시 부른다.
        applyLayout(
            if (width == 0 || height == 0) {
                null
            } else {
                val paint = if (readingWindow) readingPaint else instructionPaint
                val metrics = paint.fontMetrics
                GuideBoxLayout.compute(
                    instruction = instruction,
                    // 강조 클램프와 달리 **시스템 바를 뺀** 범위다(#169) — 안내창이 내비게이션
                    // 바를 덮으면 그 밴드의 탭까지 조작 오버레이가 흡수한다.
                    canvas = boxBounds(),
                    target = if (hasTarget) highlightBounds() else null,
                    margin = instructionMargin,
                    marginTop = instructionMarginTop,
                    marginBottom = instructionMarginBottom,
                    avoidGap = instructionAvoidGap,
                    padding = instructionPadding,
                    paddingVertical = instructionPaddingV,
                    lineHeight = metrics.bottom - metrics.top,
                    measure = { text, maxWidth ->
                        paint.breakText(text, true, maxWidth, null)
                    },
                    // 손이 훑는 자리를 회피에 얹는다(#222). 가장자리 제스처가 아니면 없다.
                    avoid = avoid + listOfNotNull(handSweep()),
                    // **종료 X가 모서리로 갔으므로 조작 안내에서는 칩 자리를 비우지 않는다**
                    // (v2, #178). 그대로 두면 글자 아래에 72dp가 빈 채로 남는다. 확인쌍은
                    // 여전히 창 아래에 앉으므로 확인 화면에서만 비운다.
                    controlHeight = reservedHeight(),
                    centered = confirmKind == GuideDisplayState.ConfirmKind.QUIT,
                )
            },
        )
    }

    /**
     * 계산 결과를 반영하고 알린다. **[layout]에 대입하는 곳은 여기 하나뿐이다** — [clearTarget]은
     * [relayout]을 거치지 않으므로, 통지를 계산 쪽에만 걸면 지울 때 안내창이 화면에 남는다.
     */
    private fun applyLayout(next: GuideBoxLayout.Layout?) {
        layout = next
        val listener = guideBoxListener ?: return
        if (next == null) {
            listener.onGuideBox(null, emptyList(), false, 0f)
            return
        }
        // 화면 좌표 그대로 넘긴다 — 창 원점 보정은 이 뷰의 캔버스에만 필요하다(onDraw의 translate).
        // 두 창의 부모 프레임이 다르다는 실측이 있어 로컬 좌표로 바꾸면 조작 창이 밀린다
        // ([GuideBoxListener] 참고). 폭·높이는 올림한다 — 내림하면 마지막 글자가 1px 차이로 잘린다.
        val box = next.box
        listener.onGuideBox(
            Rect(
                floor(box.left).toInt(),
                floor(box.top).toInt(),
                ceil(box.right).toInt(),
                ceil(box.bottom).toInt(),
            ),
            next.lines,
            readingWindow,
            reservedHeight(),
        )
    }

    /**
     * 안내창이 앉을 수 있는 범위 — **뷰 사각형에서 시스템 바 인셋을 뺀다**(#169).
     *
     * **강조 선 두께를 물리지 않는다**(#250). 예전에는 `drawableBounds()`를 거쳤는데 거기
     * 물려 있던 `highlightStroke / 2`는 강조 선의 두께였다 — 안내창은 자기 획
     * (`davi_overlay_window_stroke` 1.5dp)을 따로 쓰므로 그 물림이 안내창 캔버스를 이유 없이
     * 좌우 2dp씩 줄이고 있었다. 위아래는 시스템 바 인셋이 더 안쪽이라 영향이 없었다 —
     * **인셋을 읽는 API 30+ 기준이다.** 그 아래(minSdk 26~29)는 [safeBounds]가 null이라 원래
     * 아무것도 안 빼므로 위아래도 2dp 넓어지지만, 하단 여백 55dp가 그 시절 3버튼 내비바
     * (48 안팎)보다 커서 겹치지는 않는다(여유가 7 → 5dp).
     *
     * 안 빼면 `bottomAnchored`가 안내창을 내비게이션 바 안으로 밀어 넣는다. 그 자체는 시각
     * 문제로 보이지만, 안내창 사각형이 곧 **조작 오버레이 창의 터치 영역**이라(#126) 겹친
     * 밴드의 탭을 우리 창이 흡수한다 — 실측에서 내비바 162px 중 상단 88px의 뒤로·홈·최근 앱이
     * 죽었다(2026-08-17 SM-S921N, `dumpsys`의 z-order상 우리 창이 `NavigationBar0` 위).
     * 시니어 대상 앱에서 시스템 버튼 절반이 안 눌리는 자리다.
     *
     * **상수로 박지 않는다.** 하단 인셋이 3버튼 162px / 제스처 51px로 3배 차이라(같은 실측)
     * 런타임에 읽어야 한다. 앱 화면은 이미 같은 처리를 한다(`ConversationActivity`, #78 패턴) —
     * 오버레이만 빠져 있었다.
     *
     * 인셋은 **창이 아니라 디스플레이**에서 읽는다. 이 창은 `FLAG_LAYOUT_NO_LIMITS`라
     * `getRootWindowInsets()`가 `top=0 bottom=0`을 준다(2026-08-17 실측) — 시스템이 "이 창은
     * 인셋에 안 눌린다"고 답하는 것이라, 그걸 믿으면 아무것도 안 빼게 된다.
     * `currentWindowMetrics`는 창 flags와 무관하게 화면 기준 인셋을 준다.
     *
     * 인셋은 화면 기준이고 이 창은 이미 상태표시줄 아래에서 시작할 수 있다. 두 번 빼지 않도록
     * **화면 좌표에서 겹치는 만큼만** 자른다.
     */
    private fun boxBounds(): GuideBoxLayout.Bounds {
        val view = viewBounds()
        val safe = safeBounds() ?: return view
        return GuideBoxLayout.Bounds(
            left = maxOf(view.left, safe.left),
            top = maxOf(view.top, safe.top),
            right = minOf(view.right, safe.right),
            bottom = minOf(view.bottom, safe.bottom),
        )
    }

    /**
     * 화면 좌표에서 시스템 바를 뺀 범위. 읽을 수 없으면 null이고 그때는 아무것도 빼지 않는다
     * (지금 동작 그대로 — 검증 기기 밖에서 근거 없이 자리를 흔들지 않는다).
     *
     * **네 변을 다 자른다.** 세로만 자르던 첫 구현은 가로 화면에서 그대로 뚫렸다 — 안내 대상
     * 앱은 우리 액티비티와 달리 세로 고정이 아니다(`AndroidManifest`의 `screenOrientation`은
     * 우리 화면에만 걸려 있다). 2026-08-18 SM-S921N 실측: 가로(`ROTATION_90`)에서 내비게이션
     * 바가 **오른쪽 변으로 가고** 안내창이 그 위를 덮어, 같은 좌표의 홈 버튼이 오버레이가
     * 있을 때만 죽었다(없을 때 `LauncherActivity`, 있을 때 브라우저 그대로).
     */
    private fun safeBounds(): GuideBoxLayout.Bounds? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val windowManager = context.getSystemService(WindowManager::class.java) ?: return null
        val metrics = windowManager.currentWindowMetrics
        val insets = WindowInsetsCompat
            .toWindowInsetsCompat(metrics.windowInsets)
            .getInsets(WindowInsetsCompat.Type.systemBars())
        return GuideBoxLayout.Bounds(
            left = (metrics.bounds.left + insets.left).toFloat(),
            top = (metrics.bounds.top + insets.top).toFloat(),
            right = (metrics.bounds.right - insets.right).toFloat(),
            bottom = (metrics.bounds.bottom - insets.bottom).toFloat(),
        )
    }

    private fun RectF.toLayoutBounds() = GuideBoxLayout.Bounds(left, top, right, bottom)

    private companion object {
        /** 그림자가 손보다 얼마나 옅은가. 진하면 손이 아니라 얼룩으로 보인다. */
        const val SHADOW_ALPHA_RATIO = 0.45f

        /** 레이어 안에서 쓰는 고정 그림자 알파. 모션의 진하기는 레이어가 따로 건다. */
        const val SHADOW_ALPHA_255 = (255 * SHADOW_ALPHA_RATIO).toInt()

        /**
         * 윤곽선을 그릴 여덟 방향의 단위 벡터. 대각선은 √2로 나눠 사방 두께를 고르게 만든다 —
         * 나누지 않으면 모서리만 굵어져 손이 팔각형처럼 보인다.
         */
        val OUTLINE_OFFSETS = listOf(
            1f to 0f, -1f to 0f, 0f to 1f, 0f to -1f,
            DIAGONAL to DIAGONAL, DIAGONAL to -DIAGONAL,
            -DIAGONAL to DIAGONAL, -DIAGONAL to -DIAGONAL,
        )

        const val DIAGONAL = 0.7071f
    }
}
