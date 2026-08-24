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

    /**
     * 지금 문안(`currentVersion`)에 이미 동의했는가 — 아니면 화면을 다시 보여준다.
     *
     * **버전만 보지 않는다**(PR #409 리뷰 P2). [ConsentGate.REQUIRED_KEYS]는 문안이 아니라
     * **코드**라 「문구를 바꾸면 버전을 올린다」 규칙에 걸리지 않고 바뀔 수 있다 — #375가
     * 풀려 개선 기록이 필수가 되거나, `strings.xml`의 카카오 블록이 스스로 예고한 대로
     * ON-1의 「로그인 없이 시작하기」가 사라져 KAKAO가 필수가 되는 경우다. 그때 문구는
     * 그대로라 버전을 안 올리면 **동의한 적 없는 필수 항목에 동의한 것으로 취급된다.**
     * 이미 저장하고 있는 값이라 함께 보는 데 비용이 없다.
     */
    fun hasAgreedCurrentVersion(currentVersion: String): Boolean = ConsentGate.hasAgreed(
        storedVersion = prefs.getString(KEY_VERSION, null),
        storedItems = prefs.getStringSet(KEY_ITEMS, null).orEmpty(),
        currentVersion = currentVersion,
    )

    private companion object {
        const val PREFS_NAME = "davi_consent"
        const val KEY_AGREED_AT = "consent_agreed_at_epoch_seconds"
        const val KEY_ITEMS = "consent_items"
        const val KEY_VERSION = "consent_version"
        const val KEY_APP_VERSION = "app_version"
    }
}
