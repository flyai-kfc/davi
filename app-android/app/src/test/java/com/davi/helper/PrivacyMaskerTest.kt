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

    // 아래 값은 전부 합성이다(실측 값은 이슈·테스트에 옮기지 않는다, #312).

    /**
     * JVM의 `\b`는 ASCII 기준이라 1차는 조사가 붙어도 원래 걸렸다 — 서버(파이썬)만 놓쳤다.
     * lookaround로 바꾼 뒤에도 그 동작이 유지되는지 고정한다(#312 ①).
     */
    @Test
    fun masksNumbersFollowedByKoreanParticles() {
        assertEquals("•••에게 전화", PrivacyMasker.maskText("010-1234-5678에게 전화"))
        assertEquals("연락처 •••번", PrivacyMasker.maskText("연락처 010-1234-5678번"))
        assertEquals("계좌 •••로 송금", PrivacyMasker.maskText("계좌 123-456-789012로 송금"))
        assertEquals("•••은 주민번호", PrivacyMasker.maskText("900101-1234567은 주민번호"))
        assertEquals("•••으로 결제", PrivacyMasker.maskText("1234-5678-9012-3456으로 결제"))
        assertEquals("•••으로 보내기", PrivacyMasker.maskText("hong@example.com으로 보내기"))
    }

    /** 블루투스 기기 목록의 MAC 주소가 그대로 서버에 도달했다(#312 ②). */
    @Test
    fun masksMacAddresses() {
        assertEquals("•••", PrivacyMasker.maskText("3C:86:9A:1B:2C:3D"))
        assertEquals("버즈 •••", PrivacyMasker.maskText("버즈 3C:86:9A:1B:2C:3D"))
        assertEquals("•••", PrivacyMasker.maskText("aa:bb:cc:dd:ee:ff"))
    }

    /** lookaround로 바꾸면서 오탐이 늘지 않았는지. 서버 테스트와 같은 목록이다. */
    @Test
    fun leavesNonPiiLabelsUntouched() {
        listOf(
            "내 갤럭시 버즈",
            "2만 3000원",
            "재생목록 1-2-3",
            "오후 3:45",
            "배터리 85%",
            "12:34",
            // MAC처럼 보이지만 6바이트가 아니다.
            "3C:86:9A",
            "3C:86:9A:1B:2C:3D:4E",
        ).forEach { assertEquals(it, PrivacyMasker.maskText(it)) }
    }
}
