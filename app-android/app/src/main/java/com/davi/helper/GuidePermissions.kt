package com.davi.helper

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.view.accessibility.AccessibilityManager

/**
 * 안내에 필요한 **특수 권한 하나**(접근성) 판정. 온보딩과 대화 화면이 같은 기준을 봐야 해서
 * 한자리에 둔다(기준이 갈리면 온보딩은 통과시키고 안내는 조용히 실패하는 상태가 생긴다).
 *
 * **`SYSTEM_ALERT_WINDOW`는 걷어냈다**(#212). 창 타입이 `TYPE_ACCESSIBILITY_OVERLAY`라 오버레이
 * 표시에 그 권한이 필요 없고(#56), 남아 있던 유일한 쓰임인 안내 중 앱 복귀는 알림 탭이 대신한다
 * (`ServiceOffAction` KDoc에 근거가 있다). **되살리려면 그 KDoc부터 읽을 것.**
 *
 * 접근성은 설정 화면에 다녀와야 켜지고 **밖에서 꺼질 수도 있다** — 앱 업데이트로 자동으로
 * 꺼지는 경우가 있다. 진입 시 한 번이 아니라 화면이 살아날 때마다 다시 본다.
 */
internal fun Context.hasGuideAccessibilityService(): Boolean {
    val manager = getSystemService(AccessibilityManager::class.java) ?: return false
    return manager
        .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        .any {
            val info = it.resolveInfo.serviceInfo
            info.packageName == packageName &&
                info.name == GuideAccessibilityService::class.java.name
        }
}

/** 안내를 시작할 수 있는 상태인가. 없으면 안내가 조용히 실패한다. */
internal fun Context.canStartGuide(): Boolean = hasGuideAccessibilityService()
