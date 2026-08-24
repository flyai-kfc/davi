package com.davi.helper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ON-1.5 동의 판정(#389). [LoginGateTest]와 같은 결 — SharedPreferences 없이 순수 함수만 본다.
 */
class ConsentGateTest {

    @Test
    fun `nothing checked cannot proceed`() {
        assertFalse(ConsentGate.canProceed(emptySet()))
    }

    @Test
    fun `all three required checked can proceed`() {
        assertTrue(ConsentGate.canProceed(ConsentGate.REQUIRED_KEYS))
    }

    /** 「(필수)라고 적힌 **세 가지**」 — 문구가 개수를 말하므로 둘로는 못 넘어간다. */
    @Test
    fun `two of three required cannot proceed`() {
        assertFalse(ConsentGate.canProceed(setOf(ConsentItemKey.SCREEN, ConsentItemKey.VOICE)))
    }

    /** 선택(카카오)은 개수를 채우지 못한다 — 필수 자리를 대신할 수 없다. */
    @Test
    fun `optional does not substitute for a required one`() {
        assertFalse(
            ConsentGate.canProceed(
                setOf(ConsentItemKey.SCREEN, ConsentItemKey.VOICE, ConsentItemKey.KAKAO),
            ),
        )
    }

    @Test
    fun `optional on top of all required still proceeds`() {
        assertTrue(ConsentGate.canProceed(ConsentGate.REQUIRED_KEYS + ConsentItemKey.KAKAO))
    }

    /** 필수 셋이 실제로 그 셋인지 — 카카오가 섞여 들어오면 선택이 필수가 된다. */
    @Test
    fun `required keys are screen voice usage`() {
        assertTrue(
            ConsentGate.REQUIRED_KEYS ==
                setOf(ConsentItemKey.SCREEN, ConsentItemKey.VOICE, ConsentItemKey.USAGE),
        )
    }

    @Test
    fun `no stored version needs consent`() {
        assertTrue(ConsentGate.needsConsent(storedVersion = null, currentVersion = "1"))
    }

    @Test
    fun `same version does not need consent`() {
        assertFalse(ConsentGate.needsConsent(storedVersion = "1", currentVersion = "1"))
    }

    /** 문안을 고치면 버전이 올라가고, 값이 다른 사람에게 다시 묻는다. */
    @Test
    fun `bumped version needs consent again`() {
        assertTrue(ConsentGate.needsConsent(storedVersion = "1", currentVersion = "2"))
    }

    /** 되돌린 경우에도 「다르다」로 본다 — 같은지만 보고 크기를 비교하지 않는다. */
    @Test
    fun `rolled back version also needs consent`() {
        assertTrue(ConsentGate.needsConsent(storedVersion = "2", currentVersion = "1"))
    }
}
