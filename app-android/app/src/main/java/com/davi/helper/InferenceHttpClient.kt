package com.davi.helper

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/** 서버 추론 세 endpoint의 공용 전송 예산(#82). 로그인 HTTP에는 적용하지 않는다. */
internal object InferenceHttpClient {
    const val CONNECT_TIMEOUT_SECONDS = 5L
    const val CALL_TIMEOUT_SECONDS = 45L

    /**
     * 서버는 공급자 호출당 최대 20초, 논리 작업 전체 최대 40초다. 앱은 연결·쓰기·읽기를 모두
     * 포함한 [CALL_TIMEOUT_SECONDS]에서 끊어 서버가 분류된 성공/오류를 돌려줄 5초 여유를 둔다.
     * read timeout 기본값(10초)이 call timeout보다 먼저 끊지 않게 같은 상한으로 명시한다.
     */
    fun create(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
}
