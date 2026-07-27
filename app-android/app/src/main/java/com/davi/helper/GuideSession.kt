package com.davi.helper

import android.content.Context

/**
 * 도그푸딩 한 번의 안내 세션 상태.
 *
 * 목표는 기기 안의 SharedPreferences에만 보관하고, 세션이 활성화됐을 때만 접근성 서비스가
 * 서버로 보낸다. 서버가 세션을 관리하거나 앱이 사용자를 대신 조작하지 않는다.
 */
data class GuideSession(
    val goal: String,
    val isActive: Boolean,
    val status: GuideSessionStatus,
)

enum class GuideSessionStatus {
    READY,
    GUIDING,
    COMPLETED,
    STOPPED,
    NETWORK_ERROR,
    SERVER_ERROR,
}

object GuideSessionStore {
    private const val PREFS = "guide_session"
    private const val KEY_GOAL = "goal"
    private const val KEY_ACTIVE = "active"
    private const val KEY_STATUS = "status"

    fun snapshot(context: Context): GuideSession {
        val prefs = prefs(context)
        val status = runCatching {
            GuideSessionStatus.valueOf(
                prefs.getString(KEY_STATUS, GuideSessionStatus.READY.name)
                    ?: GuideSessionStatus.READY.name,
            )
        }.getOrDefault(GuideSessionStatus.READY)
        return GuideSession(
            goal = prefs.getString(KEY_GOAL, "").orEmpty(),
            isActive = prefs.getBoolean(KEY_ACTIVE, false),
            status = status,
        )
    }

    fun start(context: Context, goal: String) {
        prefs(context).edit()
            .putString(KEY_GOAL, goal.trim())
            .putBoolean(KEY_ACTIVE, true)
            .putString(KEY_STATUS, GuideSessionStatus.GUIDING.name)
            .apply()
    }

    fun stop(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_ACTIVE, false)
            .putString(KEY_STATUS, GuideSessionStatus.STOPPED.name)
            .apply()
    }

    fun complete(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_ACTIVE, false)
            .putString(KEY_STATUS, GuideSessionStatus.COMPLETED.name)
            .apply()
    }

    fun updateStatus(context: Context, status: GuideSessionStatus) {
        prefs(context).edit().putString(KEY_STATUS, status.name).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
