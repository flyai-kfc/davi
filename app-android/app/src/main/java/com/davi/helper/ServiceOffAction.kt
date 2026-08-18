package com.davi.helper

/**
 * 안내 중 접근성 서비스가 꺼졌을 때 무엇을 할지(#190).
 *
 * 판정을 [GuideAccessibilityService.onUnbind]에 조건으로 쌓지 않고 여기 모으는 이유는 셋 다
 * **근거가 다르기** 때문이다. 하나는 정상 조작이고, 하나는 플랫폼 면제에 기대며, 하나는 그
 * 면제가 없을 때의 폴백이다.
 */
enum class ServiceOffAction {
    /**
     * 다비 도우미를 앞으로 가져온다. 안내 기능을 다시 켜려면 어차피 앱으로 돌아와야 한다.
     *
     * **오버레이 권한이 있을 때만 성립한다.** Android 10+는 백그라운드 activity 실행을 막고,
     * 우리가 통과하는 면제는 둘 다 그 권한에서 나온다 — 권한 자체(`BAL_ALLOW_SAW_PERMISSION`)와
     * 그 권한으로 띄운 창(`BAL_ALLOW_NON_APP_VISIBLE_WINDOW`). SM-S921N 실측에서 둘 다 관측됐다:
     * 오버레이 창이 떠 있으면 후자로, 아니면 전자로 통과한다(#190). **어느 쪽이든 권한이 조건이라
     * 게이트는 하나면 된다.**
     */
    RETURN_TO_APP,

    /**
     * 복귀가 막혔다(오버레이 권한 없음). 토스트로만 알린다.
     *
     * 조용히 지나가면 사용자는 안내가 왜 멈췄는지 알 길이 없다 — 설정 앱 위라 오버레이도 못 쓴다.
     *
     * **지금은 그 토스트도 안 보인다**(SM-S921N 실측, #190). 이 갈래는 우리 앱이 백그라운드일 때만
     * 생기는데, 알림이 막힌 앱의 백그라운드 토스트를 시스템이 억제한다
     * (`Suppressing toast from package com.davi.helper by user request`). `POST_NOTIFICATIONS`는
     * 매니페스트에 선언돼 있지만 **런타임 요청을 아직 안 한다** — 권한을 부여하고 다시 재니 같은
     * 자리에서 토스트가 떴다.
     *
     * 그 요청은 **#212**(오버레이 권한 재판정 + 알림 인프라)가 들여온다. **여기서 알림 인프라를
     * 만들지 않는 이유가 그것이다** — 권한이 생기는 순간 이 갈래도 함께 살아난다. 원래는 #56이
     * 받기로 했으나 그쪽이 창 타입 교체로 좁혀지며 근거가 옮겨졌다(2026-08-18). **그때까지 이
     * 경로는 로그로만 남는다.**
     */
    NOTICE_ONLY,

    /** 알릴 것이 없다. 안내 중이 아닐 때 서비스를 끄는 건 정상 조작이다. */
    SILENT,
}

/**
 * @param sessionActive 안내 세션이 돌고 있었는가.
 * @param canDrawOverlays 오버레이 권한이 있는가 — 복귀의 **성립 조건**이다([ServiceOffAction.RETURN_TO_APP]).
 */
fun serviceOffAction(sessionActive: Boolean, canDrawOverlays: Boolean): ServiceOffAction = when {
    !sessionActive -> ServiceOffAction.SILENT
    canDrawOverlays -> ServiceOffAction.RETURN_TO_APP
    else -> ServiceOffAction.NOTICE_ONLY
}
