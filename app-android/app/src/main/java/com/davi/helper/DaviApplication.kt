package com.davi.helper

import android.app.Application
import com.kakao.sdk.common.KakaoSdk

/**
 * 카카오 SDK 초기화만 담당한다. 계정 로그인 웹 복귀(AuthCodeHandlerActivity)와 카카오톡 복귀
 * 모두 init을 전제하므로 Activity가 아니라 여기서 한다. 다른 초기화는 넣지 않는다.
 */
class DaviApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // 키가 빈 빌드(키 미설정 개발 환경·현 release)는 init 자체를 건너뛴다.
        // 그 경우 LoginActivity가 카카오 버튼을 숨기므로 SDK 경로는 실행되지 않는다.
        BuildConfig.KAKAO_NATIVE_APP_KEY.takeIf { it.isNotEmpty() }
            ?.let { KakaoSdk.init(this, it) }
    }
}
