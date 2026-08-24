package com.davi.helper

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * ON-1.5 개인정보 동의 결정의 영속 저장(#389). [AuthStore]와 같은 결 — 일반
 * SharedPreferences(MODE_PRIVATE), 서버 전송은 이번에 하지 않는다(이슈 본문 "로컬만이면
 * 앱을 지울 때 함께 사라진다").
 *
 * **최소형(동의함 불린 하나)으로는 부족하다** — 문안이 바뀌면 `consent_version`이 올라가고
 * 그 값으로만 재동의 여부를 가릴 수 있다. 저장하는 넷(동의 시각·항목·문안 버전·앱 버전)이
 * "동의받은 문안이 무엇이었는지 입증할 수단"이라고 strings.xml 머리 주석이 전제한다.
 */
class ConsentStore(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
    )

    fun saveDecision(
        agreedAtEpochSeconds: Long,
        checkedKeys: Set<ConsentItemKey>,
        version: String,
        appVersion: String,
    ) {
        prefs.edit {
            putLong(KEY_AGREED_AT, agreedAtEpochSeconds)
            putStringSet(KEY_ITEMS, checkedKeys.mapTo(mutableSetOf()) { it.name })
            putString(KEY_VERSION, version)
            putString(KEY_APP_VERSION, appVersion)
        }
    }

    /** 지금 문안(`currentVersion`)에 이미 동의했는가 — 다르면(또는 없으면) 화면을 다시 보여준다. */
    fun hasAgreedCurrentVersion(currentVersion: String): Boolean =
        !ConsentGate.needsConsent(prefs.getString(KEY_VERSION, null), currentVersion)

    private companion object {
        const val PREFS_NAME = "davi_consent"
        const val KEY_AGREED_AT = "consent_agreed_at_epoch_seconds"
        const val KEY_ITEMS = "consent_items"
        const val KEY_VERSION = "consent_version"
        const val KEY_APP_VERSION = "app_version"
    }
}
