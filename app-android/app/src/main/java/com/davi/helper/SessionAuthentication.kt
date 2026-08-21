package com.davi.helper

import okhttp3.Request
import okhttp3.Response

/** 다비 세션 JWT의 HTTP 경계. 세 클라이언트가 헤더 이름과 재로그인 판정을 공유한다. */
internal object SessionAuthentication {
    private const val AUTHORIZATION_HEADER = "Authorization"
    private const val CHALLENGE_HEADER = "WWW-Authenticate"
    private const val BEARER_SCHEME = "Bearer"

    /** 로그인·만료로 값이 바뀌므로 요청을 만들 때마다 supplier를 다시 읽는다. */
    fun attach(builder: Request.Builder, sessionToken: () -> String?) {
        sessionToken()?.trim()?.takeIf(String::isNotEmpty)?.let { token ->
            builder.header(AUTHORIZATION_HEADER, "$BEARER_SCHEME $token")
        }
    }

    /**
     * development 접근 토큰의 401과 세션 만료를 구별한다.
     *
     * 상태 코드나 `ApiError.kind=AUTH`만 보면 둘이 같으므로, 외부 프로필의 세션 경계가 보내는
     * 표준 challenge까지 맞아야 재로그인한다. challenge 파라미터가 나중에 붙어도 scheme은
     * 첫 토큰으로 안정적으로 판정한다.
     */
    fun isRequired(response: Response): Boolean =
        response.code == 401 &&
            response.header(CHALLENGE_HEADER)
                ?.trim()
                ?.substringBefore(' ')
                ?.equals(BEARER_SCHEME, ignoreCase = true) == true
}
