package com.davi.helper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 접근성 복귀 표시(#451). **여기서 지키는 것은 「한 번만 참이다」 하나**다 — 그 성질이
 * 부팅·재설치에서 앱이 안 튀어나오는 근거 전부이고, 유효 시간을 두지 않기로 한 근거이기도 하다.
 *
 * [ConsentGateTest]와 같은 결로 SharedPreferences 없이 본다.
 */
class AccessibilityReturnStoreTest {

    private class FakeStorage(private var value: Boolean = false) :
        AccessibilityReturnStore.MarkStorage {
        override fun read(): Boolean = value
        override fun write(value: Boolean) {
            this.value = value
        }
    }

    @Test
    fun `온보딩이 보냈으면 한 번 앞으로 나온다`() {
        val store = AccessibilityReturnStore(FakeStorage())
        store.markSentToSettings()

        assertTrue(store.consume())
    }

    @Test
    fun `두 번째 호출은 반드시 거짓이다 — 부팅에서 안 튀어나오는 근거다`() {
        val store = AccessibilityReturnStore(FakeStorage())
        store.markSentToSettings()
        store.consume()

        // 재부팅으로 서비스가 다시 붙어 onServiceConnected가 또 불린 상황.
        assertFalse("소비된 표시가 되살아났다", store.consume())
        assertFalse("세 번째도 마찬가지다", store.consume())
    }

    @Test
    fun `온보딩을 안 거쳤으면 아무 일도 없다`() {
        // 갓 설치한 기기 · 안내 도중 권한이 회수됐다 복구되는 갈래(#212)가 이 모양이다.
        assertFalse(AccessibilityReturnStore(FakeStorage()).consume())
    }

    @Test
    fun `온보딩이 스스로 넘어가면 표시가 사라진다`() {
        val store = AccessibilityReturnStore(FakeStorage())
        store.markSentToSettings()

        // 사용자가 설정에서 손으로 돌아와 OnboardingActivity.onResume이 먼저 넘어간 경우.
        store.clear()

        assertFalse("지운 표시가 다음 부팅에서 소비됐다", store.consume())
    }
}
