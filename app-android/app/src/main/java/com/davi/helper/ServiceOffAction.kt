package com.davi.helper

/**
 * 안내 중 접근성 서비스가 꺼졌을 때 무엇을 할지(#190).
 *
 * 판정을 [GuideAccessibilityService.onUnbind]에 조건으로 쌓지 않고 여기 모으는 이유는 근거가
 * 다르기 때문이다 — 하나는 사용자에게 알려야 할 사고이고, 하나는 정상 조작이다.
 *
 * ## 여기 있던 자동 복귀(`RETURN_TO_APP`)를 왜 버렸나 — 되살리기 전에 읽을 것
 *
 * 안내 중 접근성이 꺼지면 서비스가 `startActivity`로 온보딩을 앞으로 가져왔다. 그건 Android
 * 10+의 백그라운드 activity 실행 금지에서 **`SYSTEM_ALERT_WINDOW`가 주는 면제**로만 성립했다.
 *
 * **2026-08-18 실측(SM-S921N / One UI 8.5)** — 그 권한을 `deny`로 두고 재니 복귀가 조용히
 * 막혔다. 로그(`service unbound during session, returning to app`)는 나가는데
 * `topResumedActivity`는 `com.android.settings/.SubSettings` 그대로였고 BAL 차단 로그도 안 남았다.
 * **unbind 시점에 접근성 오버레이 창이 아직 붙어 있는데도** 막혔으므로,
 * `BAL_ALLOW_NON_APP_VISIBLE_WINDOW`가 권한 없이 대신 통과시켜 주지 않는다는 뜻이다.
 *
 * 즉 그 권한이 지키던 것은 **자동 복귀 하나**였다. [NOTIFY]의 알림을 누르면 같은 곳으로 가고,
 * **탭은 사용자 시작이라 BAL에 막히지 않는다.** 그래서 권한을 걷어내고 복귀를 알림 탭으로
 * 바꿨다(#212) — 시니어가 설정 앱에서 "다른 앱 위에 표시"를 찾아 켜는 걸음이 온보딩에서 통째로
 * 사라진다. 드문 실패 경로의 자동화를 모든 사용자가 치르던 비용과 맞바꾼 것이다.
 *
 * **복귀를 되살리려면 그 권한이 다시 필요하고, 그러면 그 비용도 함께 돌아온다.**
 */
enum class ServiceOffAction {
    /**
     * 안내가 멈췄다는 것을 알린다. 알림을 누르면 온보딩으로 가서 다시 켤 수 있다.
     *
     * **토스트가 아니라 알림인 이유**는 이 갈래가 우리 앱이 백그라운드일 때만 생기는데, 알림이
     * 막힌 앱의 백그라운드 토스트를 시스템이 억제하기 때문이다(SM-S921N 실측, #190 —
     * `Suppressing toast from package com.davi.helper by user request`). 토스트를 보이게 하려
     * 해도 결국 `POST_NOTIFICATIONS`가 필요했다. 같은 권한이면 **남아 있고 누를 수 있는 쪽**이 낫다.
     */
    NOTIFY,

    /** 알릴 것이 없다. 안내 중이 아닐 때 서비스를 끄는 건 정상 조작이다. */
    SILENT,
}

/**
 * @param sessionActive 안내 세션이 돌고 있었는가. **이것 하나가 판정의 전부다** — 오버레이 권한을
 * 걷어내면서 축이 하나 사라졌다(위 KDoc).
 */
fun serviceOffAction(sessionActive: Boolean): ServiceOffAction =
    if (sessionActive) ServiceOffAction.NOTIFY else ServiceOffAction.SILENT
