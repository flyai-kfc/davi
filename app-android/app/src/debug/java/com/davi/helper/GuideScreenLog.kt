package com.davi.helper

import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * 화면 상세 진단 로그의 **debug 구현**(#58).
 *
 * 요소 `label`과 `bounds`는 사용자가 보고 있는 화면의 내용 그 자체다. 1차 마스킹이
 * 패턴에 걸리는 개인정보를 지우지만 사람 이름·대화 내용처럼 패턴 밖의 텍스트는 남으므로,
 * **release 산출물에는 이 상세를 아예 넣지 않는다.** 같은 이름의 release 구현이 no-op이다.
 *
 * 상세 로그를 낼지 말지의 판정(`shouldLogDetailedScreen`)은 호출부에 그대로 둔다 —
 * 그 조건은 마스킹 우회·E2E 세션이라는 **정책**이고, 여기는 그 결정을 받아 적기만 한다.
 */
object GuideScreenLog {
    /** 한 화면에서 찍을 요소 수 상한. 목록 화면은 수십 개가 나와 로그가 화면을 덮는다. */
    private const val MAX_LOGGED_ELEMENTS = 30

    fun elements(screen: Screen) {
        screen.elements.take(MAX_LOGGED_ELEMENTS).forEach { e ->
            Log.d(
                GuideAccessibilityService.TAG,
                "  ${e.nodeId} ${if (e.clickable) "tap" else "text"} " +
                    "role=${e.role} bounds=${e.bounds}${scrollInfo(e)} label='${e.label}'",
            )
        }
        if (screen.elements.size > MAX_LOGGED_ELEMENTS) {
            Log.d(
                GuideAccessibilityService.TAG,
                "  ... ${screen.elements.size - MAX_LOGGED_ELEMENTS} more",
            )
        }
    }

    /**
     * 강조 대상과 좌표. bounds는 화면 위 어디를 짚었는지라 debug에서만 남긴다.
     * release 구현은 좌표 없이 node_id만 남긴다 — 어느 요소를 골랐는지는 비식별이다.
     */
    fun highlight(nodeId: String?, bounds: List<Int>) {
        // `at=`는 **CI 카나리아다**(ci.yml "release 산출물에 debug 전용 흔적이 없는지 확인").
        // 좌표를 찍는 자리가 요소 덤프와 독립이라 카나리아도 따로 있어야 한다 — 덤프 쪽
        // 카나리아(label='·scroll=)만 보면 여기서 좌표가 되살아나도 CI가 통과한다(PR #287 리뷰).
        // 요소 덤프의 `bounds=`를 그대로 쓰지 않는 이유는 그 문자열이 UiElement의 자동 생성
        // toString(`..., bounds=...`)과 겹쳐 release 산출물에도 남기 때문이다.
        // **이 리터럴을 바꾸면 ci.yml의 카나리아 목록도 함께 고친다.**
        Log.i(GuideAccessibilityService.TAG, "highlight ${nodeId ?: "-"} at=$bounds")
    }

    /**
     * 들어온 접근성 이벤트의 타입. "검증 앱이 클릭 이벤트를 안 쏘는가, 쏘는데 못 맞추는가"를
     * 가르려고 둔다 — `press on target`은 **맞았을 때만** 찍혀서 둘이 구분되지 않는다
     * (2026-08-21 실측: 눌림 감지 2건이 전부 런처였다).
     *
     * **이벤트 텍스트는 화면 원문이라 절대 찍지 않는다**(1차 마스킹은 직렬화 경로에만 걸려 있다).
     * 있는지 여부와 개수만 남긴다 — 라벨 대조가 왜 실패했는지는 그것으로 좁혀진다.
     *
     * **`event.source`는 읽지 않는다**(#356). 그 프로퍼티만 `AccessibilityInteractionClient`로
     * **동기 IPC**를 내는데, 이 함수는 `isInteresting` 필터 **앞에서** 불리므로 세션이 켜진 동안
     * 들어오는 **모든** 이벤트에 그 비용이 붙었다. `TYPE_WINDOW_CONTENT_CHANGED`가 쏟아지는
     * 화면에서 가장 크고(실측 구간 522건), 당겨온 노드를 회수하지도 않았다 — `recycle()`이
     * no-op이 되는 것은 API 33부터인데 `minSdk`가 26이다.
     *
     * 나머지 필드는 그대로 둔다 — 타입·패키지·텍스트 개수·`contentDescription` 유무는 전부
     * 이벤트 parcel에 이미 들어 있어 IPC가 없다. **필터 앞이라는 위치도 그대로다** — 그 이유는
     * 호출부 [GuideAccessibilityService.onAccessibilityEvent]의 주석에 있다.
     *
     * `source`가 간헐적으로 `null`이라는 관측은 `PressMatch`가 텍스트 폴백으로 받고 있다(#238).
     * 이 로그로 더 얻을 것이 없다.
     */
    fun event(event: AccessibilityEvent, interesting: Boolean) {
        val texts = event.text.count { !it.isNullOrBlank() }
        Log.d(
            GuideAccessibilityService.TAG,
            "ev=${eventName(event.eventType)} pkg=${event.packageName ?: "-"} " +
                "texts=$texts desc=${!event.contentDescription.isNullOrBlank()} kept=$interesting",
        )
    }

    /**
     * 폴링 한 번의 결과. `IDLE`이 지금 완전히 침묵해서 "화면이 안 바뀐 것"과 "우리가 못 본 것"이
     * 구분되지 않는다 — 팝업이 저절로 내려간 27초를 여기서 갈라야 한다.
     *
     * 시그니처는 라벨을 담으므로 **원문 대신 해시만** 남긴다. 같은지 다른지만 알면 된다.
     * 구조/내용 두 축을 따로 찍는다(#380) — 광고 화면은 `s=`가 고정된 채 `c=`만 도는 것으로,
     * 실제 전환은 `s=`가 바뀌는 것으로 로그에서 바로 갈린다.
     *
     * `gate=`는 인과 게이트가 그 순간 열려 있었는가다. 게이트의 근거가 클릭 이벤트뿐이라
     * (`ScreenStabilityTracker.markInteraction`), **그 앱이 클릭을 쏘는지 · 사용자가 손댄
     * 구간에만 열리는지를 재는 자리다.** 스크롤을 뺀 근거가 된 실측도 이 계측으로 잡았다.
     *
     * `anchor=`는 다른 쪽 문이다 — 내용만의 변화가 새 상황이 되는 길은 인과 게이트와 타깃
     * 앵커 **둘**이라, 게이트만 찍으면 스스로 갱신되는 목록(새 메시지가 계속 오는 대화 목록)이
     * 앵커만으로 재질의를 계속 내는 것이 로그에서 안 보인다.
     *
     * `v=`는 순회가 들어간 노드 수, `cut=`은 [UiTreeSerializer]의 사슬 상한이 끊은 서브트리
     * 수다(#367). **`n=`은 `ms=`의 분모가 못 된다** — `MAX_ELEMENTS`에서 포화하고, 비용이 방출이
     * 아니라 방문을 따른다. **`v=`도 `getChild()` 총 호출 수는 아니다** — 가림 판정과 라벨 병합이
     * 내는 IPC는 그 밖이라, 셋의 비율을 알아야 하면 보정 계측을 다시 붙여야 한다.
     *
     * **`cut=`은 0일 때만 강한 신호다.** 0이면 그 화면의 결과가 상한과 무관하다는 뜻이고, 0이 아닌
     * 것은 "봐야 할 수도 있다"까지다 — 잎은 빼지만 아무것도 방출 안 하는 서브트리는 여전히 세므로,
     * 오프스크린 ViewPager가 있는 화면에서는 0이 아닌 것이 정상이다. 자세한 것은
     * [UiTreeSerializer.WalkCost].
     */
    fun sample(
        decision: String,
        elementCount: Int,
        signature: ScreenSig,
        serializeMs: Long,
        gateOpen: Boolean,
        anchorMoved: Boolean,
        anchorSpent: Boolean,
        quietMs: Long,
        quietArmed: Boolean,
        quietColdOpens: Int,
        quietOpens: Int,
        clickAxisDead: Boolean,
        sendingBlocked: Boolean,
        cost: UiTreeSerializer.WalkCost,
    ) {
        // `capped`는 **앵커가 열려던 것을 상한이 막았다**는 뜻이다. 게이트가 열려 있으면
        // 재질의는 어차피 나갔으므로 막힌 것이 아니다 — 그때까지 `capped`로 찍으면 오독한다.
        val anchor = when {
            anchorMoved && anchorSpent && !gateOpen -> "capped"
            anchorMoved -> "moved"
            else -> "-"
        }
        Log.d(
            GuideAccessibilityService.TAG,
            "poll=$decision n=$elementCount ms=$serializeMs " +
                "s=${Integer.toHexString(signature.structure.hashCode())} " +
                "c=${Integer.toHexString(signature.content.hashCode())} " +
                "gate=${if (gateOpen) "open" else "-"} " +
                "anchor=$anchor " +
                "quiet=${quietMs}ms${if (quietArmed) "!" else ""}/$quietColdOpens+$quietOpens " +
                "cad=${if (clickAxisDead) "y" else "n"} " +
                "v=${cost.visits} cut=${cost.cuts}" +
                // 차단 구간을 눈에 남긴다(#456 팀 리뷰). `sendingBlocked`가 `SEND`를 `IDLE`로
                // 바꾸므로, 이 플래그가 없으면 "확인 대기로 막힌 폴링"과 "정말 같은 화면이라
                // IDLE"이 구분되지 않는다 — 이 줄을 만든 근거("IDLE이 침묵해서 화면이 안 바뀐
                // 것과 우리가 못 본 것이 구분되지 않는다")가 그대로 되살아난다.
                if (sendingBlocked) " blocked=confirm" else "",
        )
    }

    private fun eventName(type: Int): String = when (type) {
        AccessibilityEvent.TYPE_VIEW_CLICKED -> "VIEW_CLICKED"
        AccessibilityEvent.TYPE_VIEW_FOCUSED -> "VIEW_FOCUSED"
        AccessibilityEvent.TYPE_VIEW_SCROLLED -> "VIEW_SCROLLED"
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "WINDOW_STATE_CHANGED"
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "WINDOW_CONTENT_CHANGED"
        AccessibilityEvent.TYPE_WINDOWS_CHANGED -> "WINDOWS_CHANGED"
        AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> "VIEW_LONG_CLICKED"
        AccessibilityEvent.TYPE_VIEW_SELECTED -> "VIEW_SELECTED"
        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "VIEW_TEXT_CHANGED"
        else -> "OTHER($type)"
    }

    /** 스크롤 가능한 요소에만 붙는다. 방향 안내가 맞는지 로그에서 바로 보려고 찍는다. */
    private fun scrollInfo(e: UiElement): String {
        if (e.canScrollForward == null && e.canScrollBackward == null) return ""
        val fwd = if (e.canScrollForward == true) "+${e.nextSwipeDirection?.name?.lowercase()}" else ""
        val back = if (e.canScrollBackward == true) "-${e.prevSwipeDirection?.name?.lowercase()}" else ""
        return " scroll=${e.scrollAxis?.name?.lowercase()}$fwd$back"
    }
}
