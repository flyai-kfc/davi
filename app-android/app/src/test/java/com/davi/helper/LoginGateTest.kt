package com.davi.helper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginGateTest {

    @Test
    fun `no decision when nothing stored`() {
        assertFalse(LoginGate.hasDecision(skipped = false, hasToken = false, 0L, NOW))
    }

    @Test
    fun `skip alone is a decision`() {
        assertTrue(LoginGate.hasDecision(skipped = true, hasToken = false, 0L, NOW))
    }

    @Test
    fun `valid token is a decision`() {
        assertTrue(LoginGate.hasDecision(skipped = false, hasToken = true, NOW + 1, NOW))
    }

    @Test
    fun `expired token is not a decision`() {
        assertFalse(LoginGate.hasDecision(skipped = false, hasToken = true, NOW - 1, NOW))
    }

    @Test
    fun `expiry boundary counts as expired`() {
        assertFalse(LoginGate.hasDecision(skipped = false, hasToken = true, NOW, NOW))
    }

    @Test
    fun `skip wins even with expired token`() {
        assertTrue(LoginGate.hasDecision(skipped = true, hasToken = true, NOW - 1, NOW))
    }

    private companion object {
        const val NOW = 1_700_000_000L
    }
}
