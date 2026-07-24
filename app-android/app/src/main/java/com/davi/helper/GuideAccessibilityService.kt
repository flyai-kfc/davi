package com.davi.helper

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * 닫힌 루프의 본체(골격). 기기측 책임만 여기 있다(app-android/CLAUDE.md):
 *  - 화면 트리 직렬화 + 1차 마스킹
 *  - 폴링/정체 감지(시간 기반)
 *  - 오버레이 렌더링, 서버가 준 "다음 안내 하나" 실행(안내만, 대신 조작 금지)
 *
 * 추론(프롬프트·모델·완료 검증)은 서버에 있다. 이 서비스는 상태를 모아 서버에 보내고
 * 받은 안내를 그린다. 로그 태그는 GuideSvc로 통일한다(디버깅 1순위, CLAUDE.md).
 *
 * TODO: UiTreeSerializer, 1차 마스킹, 서버 호출(BuildConfig.SERVER_URL), 오버레이.
 *       검증된 국소 구현은 HANDOFF.md §4 인덱스를 보고 필요할 때만 참고한다.
 */
class GuideAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "connected server=${BuildConfig.SERVER_URL}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // TODO: 활성 화면 루트 선택(셰이드/우리 앱 제외) -> 직렬화 -> 1차 마스킹 -> 서버 -> 오버레이
    }

    override fun onInterrupt() {
        Log.w(TAG, "interrupted")
    }

    companion object {
        const val TAG = "GuideSvc"
    }
}
