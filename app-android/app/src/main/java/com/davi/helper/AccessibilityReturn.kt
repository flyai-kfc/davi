package com.davi.helper

import android.content.Context
import androidx.core.content.edit

/**
 * 접근성을 켠 사람을 **다비로 되돌리는 표시**(#451).
 *
 * 지금은 온보딩이 사용자를 설정 화면으로 보내고, 스위치를 켠 뒤에는 **사용자가 직접 다비를
 * 다시 찾아와야 한다.** 시니어에게 이 왕복은 한 단계가 아니다 — 앱을 처음 설치하고 처음
 * 만나는 화면이 「설정에 가서 뭘 켜고 다시 돌아오세요」이고, 여기서 못 돌아오면 앱을 못 쓴다.
 *
 * 스위치를 켜는 순간 안드로이드가 [GuideAccessibilityService.onServiceConnected]를 부르므로
 * 그 자리에서 다비를 띄우면 된다. **실측으로 되는 것을 확인했다**(2026-08-26 SM-S918N ·
 * Android 15): 「허용」을 누른 지 1초 안에 다비가 앞으로 나왔고, 플랫폼이 이유까지 남겼다 —
 * `BAL_ALLOW_PERMISSION`. 백그라운드 액티비티 시작 제한의 예외 근거가 **「접근성 서비스로
 * 묶여 있다」는 것 자체**라, 따로 받아 둘 권한이 없어 갓 설치한 기기에서도 조건이 같다.
 *
 * ## 그런데 그 콜백은 다른 때도 불린다
 *
 * 부팅(접근성이 켜진 채 재부팅) · 앱 재설치 · 안내 도중 회수됐다 복구되는 갈래(#212).
 * **아무 때나 다비가 튀어나오면 그게 더 나쁘다** — 그래서 「온보딩에서 방금 보냈는가」를
 * 갈라야 하고, 이 표시가 그것을 가른다.
 *
 * ## 시계를 쓰지 않는다 — **한 번만 참이면 된다**
 *
 * 유효 시간을 두는 안을 검토했지만 시간이 가르는 것이 없다. [consume]이 표시를 **읽으면서
 * 지우므로** 위 세 경우가 전부 시간 없이 갈린다:
 *
 * | 언제 | 표시 | 결과 |
 * |---|---|---|
 * | 온보딩이 보낸 직후 켬 | 있음 → 소비 | 다비가 앞으로 나온다 |
 * | 재부팅 | 이미 소비돼 없음 | 아무 일 없음 |
 * | 재설치 | 앱 데이터가 지워져 없음 | 아무 일 없음 |
 * | 안내 중 회수→복구(#212) | 온보딩을 안 거쳤으니 없음 | 아무 일 없음 |
 *
 * 시간을 두면 오히려 **설정에서 헤매는 사람이 창을 넘겨 복귀를 못 받는다** — 이 기능이 가장
 * 필요한 사람이 그 사람이다. 값에 근거가 없는 상수를 하나 더 만들지 않는 쪽이기도 하다.
 *
 * **온보딩이 스스로 넘어갈 때도 표시를 지운다**([clear]). 사용자가 설정에서 손으로 돌아오면
 * `OnboardingActivity.onResume`이 먼저 대화 화면을 띄우는데, 그때 표시가 남아 있으면
 * **다음 부팅에서 소비되어 앱이 튀어나온다.**
 */
class AccessibilityReturnStore(private val storage: MarkStorage) {

    constructor(context: Context) : this(PrefsMarkStorage(context))

    /** 온보딩이 사용자를 접근성 설정으로 보냈다. */
    fun markSentToSettings() = storage.write(true)

    /**
     * 표시가 있으면 **지우고** true. 없으면 false.
     *
     * **읽기가 아니라 소비다** — 두 번째 호출은 반드시 false다. 이 성질 하나가 부팅·재설치에서
     * 앱이 안 튀어나오는 근거 전부라, 테스트가 그것을 직접 단정한다.
     */
    fun consume(): Boolean {
        if (!storage.read()) return false
        storage.write(false)
        return true
    }

    /** 온보딩이 스스로 다음 화면으로 넘어갔다 — 되돌릴 일이 없어졌다. */
    fun clear() = storage.write(false)

    /** 표시를 담는 곳. SharedPreferences 없이 JVM 테스트가 가능하도록 갈라 둔다. */
    interface MarkStorage {
        fun read(): Boolean
        fun write(value: Boolean)
    }

    private class PrefsMarkStorage(context: Context) : MarkStorage {
        private val prefs =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        override fun read(): Boolean = prefs.getBoolean(KEY_SENT_TO_SETTINGS, false)

        override fun write(value: Boolean) = prefs.edit { putBoolean(KEY_SENT_TO_SETTINGS, value) }
    }

    private companion object {
        const val PREFS_NAME = "davi_accessibility_return"
        const val KEY_SENT_TO_SETTINGS = "sent_to_settings"
    }
}
