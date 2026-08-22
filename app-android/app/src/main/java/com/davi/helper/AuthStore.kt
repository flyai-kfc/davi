package com.davi.helper

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * 로그인 결정(세션 토큰 또는 스킵)의 영속 저장.
 *
 * [GuideSession]의 "영속화하지 않는다" 규칙과는 대상이 다르다 — 그 규칙은 재바인딩 때
 * 사용자가 시작을 누르지 않았는데 화면을 읽는 문제를 막기 위한 **안내 세션** 얘기다.
 * 로그인 세션 토큰은 화면 읽기를 유발하지 않으며, 재실행마다 로그인을 다시 요구하지 않으려면
 * 영속돼야 한다.
 *
 * 저장소는 일반 SharedPreferences(MODE_PRIVATE)다. 앱 전용 내부 저장소가 Android의 격리
 * 경계이고, 저장 대상은 서버 TTL로 소멸하는 무상태 JWT라 별도 암호화 계층을 더하지 않는다
 * (androidx security-crypto는 deprecated, DataStore는 코루틴 의존 — 이 코드베이스 미사용).
 * 토큰·만료값은 어떤 로그에도 남기지 않는다.
 */
class AuthStore(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
    )

    fun saveSession(sessionToken: String, expiresInSeconds: Int, nowEpochSeconds: Long) {
        // 요청 왕복·기기 시계 오차 여유로 60초 일찍 만료시킨다.
        val expiresAt = nowEpochSeconds + expiresInSeconds - EXPIRY_MARGIN_SECONDS
        prefs.edit {
            putString(KEY_SESSION_TOKEN, sessionToken)
            putLong(KEY_SESSION_EXPIRES_AT, expiresAt)
            remove(KEY_LOGIN_SKIPPED)
        }
    }

    /** 유효(미만료)한 세션 토큰. 만료됐으면 null — 삭제는 하지 않고 판정만 한다. */
    fun sessionToken(nowEpochSeconds: Long): String? {
        val token = prefs.getString(KEY_SESSION_TOKEN, null) ?: return null
        val expiresAt = prefs.getLong(KEY_SESSION_EXPIRES_AT, Long.MIN_VALUE)
        return token.takeIf { LoginGate.sessionValid(expiresAt, nowEpochSeconds) }
    }

    fun markSkipped() {
        prefs.edit {
            remove(KEY_SESSION_TOKEN)
            remove(KEY_SESSION_EXPIRES_AT)
            putBoolean(KEY_LOGIN_SKIPPED, true)
        }
    }

    /** 서버가 세션 challenge를 돌려준 경우 토큰과 이전 스킵 결정을 함께 지운다. */
    fun clearAuthenticationDecision() {
        prefs.edit {
            remove(KEY_SESSION_TOKEN)
            remove(KEY_SESSION_EXPIRES_AT)
            remove(KEY_LOGIN_SKIPPED)
        }
    }

    /** ON-1을 다시 보여줄 필요가 없는가 — 유효한 세션이 있거나 스킵을 선택했다. */
    fun hasDecision(nowEpochSeconds: Long): Boolean = LoginGate.hasDecision(
        skipped = prefs.getBoolean(KEY_LOGIN_SKIPPED, false),
        hasToken = prefs.getString(KEY_SESSION_TOKEN, null) != null,
        expiresAtEpochSeconds = prefs.getLong(KEY_SESSION_EXPIRES_AT, Long.MIN_VALUE),
        nowEpochSeconds = nowEpochSeconds,
    )

    private companion object {
        const val PREFS_NAME = "davi_auth"
        const val KEY_SESSION_TOKEN = "session_token"
        const val KEY_SESSION_EXPIRES_AT = "session_expires_at_epoch_seconds"
        const val KEY_LOGIN_SKIPPED = "login_skipped"
        const val EXPIRY_MARGIN_SECONDS = 60L
    }
}

/** 로그인 관문 판정. SharedPreferences 없이 JVM 테스트가 가능하도록 순수 함수로 분리한다. */
object LoginGate {

    fun sessionValid(expiresAtEpochSeconds: Long, nowEpochSeconds: Long): Boolean =
        nowEpochSeconds < expiresAtEpochSeconds

    fun hasDecision(
        skipped: Boolean,
        hasToken: Boolean,
        expiresAtEpochSeconds: Long,
        nowEpochSeconds: Long,
    ): Boolean = skipped || (hasToken && sessionValid(expiresAtEpochSeconds, nowEpochSeconds))
}
