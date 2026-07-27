package com.davi.helper

import org.junit.Assert.assertEquals
import org.junit.Test

class PrivacyMaskerTest {

    @Test
    fun masksTheSameFivePiiPatternsAsTheServer() {
        assertEquals("연락처 •••", PrivacyMasker.maskText("연락처 hong@example.com"))
        assertEquals("주민 •••", PrivacyMasker.maskText("주민 900101-1234567"))
        assertEquals("카드 •••", PrivacyMasker.maskText("카드 1234-5678-9012-3456"))
        assertEquals("휴대폰 •••", PrivacyMasker.maskText("휴대폰 010-1234-5678"))
        assertEquals("계좌 •••", PrivacyMasker.maskText("계좌 123-456-789012"))
    }

    @Test
    fun leavesPlainTextAndEmptyTextUntouched() {
        assertEquals("카카오톡", PrivacyMasker.maskText("카카오톡"))
        assertEquals("", PrivacyMasker.maskText(""))
    }
}
