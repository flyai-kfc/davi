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
     * **계측(임시 — #356에서 걷거나 간섭 없는 형태로 줄인다).** 들어온 접근성 이벤트의 타입과
     * 출처. "검증 앱이 클릭 이벤트를 안 쏘는가, 쏘는데 못 맞추는가"를 가르려고 둔다 — 지금
     * `press on target`은 **맞았을 때만** 찍혀서 둘이 구분되지 않는다
     * (2026-08-21 실측: 눌림 감지 2건이 전부 런처였다).
     *
     * **이벤트 텍스트는 화면 원문이라 절대 찍지 않는다**(1차 마스킹은 직렬화 경로에만 걸려 있다).
     * 있는지 여부와 길이만 남긴다 — 라벨 대조가 왜 실패했는지는 그것으로 좁혀진다.
     */
    /**
     * **`event.source`를 읽지 않는다**(#356). 그 프로퍼티만 `AccessibilityInteractionClient`로
     * **동기 IPC**를 내고, 이 함수는 `isInteresting` 필터 **앞에서** 불리므로 세션이 켜진 동안
     * 들어오는 **모든** 이벤트에 그 비용이 붙었다. `TYPE_WINDOW_CONTENT_CHANGED`가 쏟아지는
     * 화면에서 가장 크고(실측 구간 522건), 당겨온 노드를 회수하지도 않았다 — `recycle()`이
     * no-op이 되는 것은 API 33부터인데 `minSdk`가 26이다.
     *
     * **관측이 대상을 바꾸는 자리였다.** 폴링·안정 판정이 얹힌 같은 메인 스레드를 밀어내
     * #367이 재는 직렬화 시간이 계측 세금인지 진짜인지 갈리지 않았다.
     *
     * 나머지 필드는 그대로 둔다 — 타입·패키지·텍스트 개수·`contentDescription` 유무는 전부
     * 이벤트 parcel에 이미 들어 있어 IPC가 없다. **필터 앞이라는 위치도 그대로다**: 걸러진
     * 이벤트까지 봐야 "안 왔다"와 "걸렀다"가 갈린다(#336).
     *
     * `source`가 간헐적으로 `null`이라는 관측은 이미 `app-android/CLAUDE.md` 기기 함정에 있고
     * `PressMatch`가 텍스트 폴백으로 받고 있다(#238). 이 로그로 더 얻을 것이 없다.
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
     * **계측(임시 — #356에서 걷거나 간섭 없는 형태로 줄인다).** 폴링 한 번의 결과. `IDLE`이 지금
     * 완전히 침묵해서 "화면이 안 바뀐 것"과 "우리가 못 본 것"이 구분되지 않는다 — 팝업이 저절로
     * 내려간 27초를 여기서 갈라야 한다.
     *
     * 시그니처는 라벨을 담으므로 **원문 대신 해시만** 남긴다. 같은지 다른지만 알면 된다.
     */
    fun sample(decision: String, elementCount: Int, signature: String) {
        Log.d(
            GuideAccessibilityService.TAG,
            "poll=$decision n=$elementCount sig=${Integer.toHexString(signature.hashCode())}",
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
