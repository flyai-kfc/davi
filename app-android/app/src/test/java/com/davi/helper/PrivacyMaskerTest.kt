package com.davi.helper

import org.junit.Assert.assertEquals
import org.junit.Test

class PrivacyMaskerTest {

    @Test
    // 이름에 개수를 넣지 않는다 — 패턴이 늘 때마다 이름이 낡는다(#312에서 실제로 낡았다).
    fun masksTheSamePiiPatternsAsTheServer() {
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

    /**
     * 앞 라벨이 콜론으로 붙어 공백이 없는 표기. 경계가 콜론을 금지하면 통째로 샌다
     * (PR #357 리뷰에서 실측). 라벨 단어 자체는 보존돼야 한다. 서버 테스트와 같은 목록이다.
     */
    @Test
    fun masksMacAddressesAttachedToALabel() {
        assertEquals("MAC:•••", PrivacyMasker.maskText("MAC:3C:86:9A:1B:2C:3D"))
        assertEquals("주소:•••", PrivacyMasker.maskText("주소:3C:86:9A:1B:2C:3D"))
        assertEquals("MAC: •••", PrivacyMasker.maskText("MAC: 3C:86:9A:1B:2C:3D"))
        assertEquals("•••:", PrivacyMasker.maskText("3C:86:9A:1B:2C:3D:"))
        // 7바이트 이상은 앞 6바이트만 지우지 않고 통째로 삼킨다.
        assertEquals("•••", PrivacyMasker.maskText("3C:86:9A:1B:2C:3D:4E"))
    }

    /**
     * 오탐이 늘지 않았는지, 그리고 **일부러 지우지 않는 것**이 그대로인지. 서버와 같은 목록이다.
     *
     * 패턴 밖 고유명(사람 이름·기기명·대화방 제목)은 마스킹 대상이 아니라고 `SPEC.md` §2가
     * 정했다(#360) — 일반 규칙으로 잡으면 앱 서랍의 앱 이름까지 지워져 안내가 죽는다.
     * 이 줄들이 빨개지면 그 결정을 뒤집는 것이므로 패턴을 늘리기 전에 SPEC부터 고친다.
     */
    @Test
    fun leavesNonPiiLabelsUntouched() {
        listOf(
            // 숫자가 섞인 형태를 넣는다 — 숫자 없는 이름은 형태 기반 패턴이 늘어도 스치지
            // 않아 가드가 되지 못한다(PR #369 리뷰).
            "지훈의 Galaxy Buds2",
            "Buds2 Pro 3C86",
            "거실 TV 65",
            "내 갤럭시 버즈",
            "2만 3000원",
            "재생목록 1-2-3",
            "오후 3:45",
            "배터리 85%",
            "12:34",
            "자막 01:02:03",
            // MAC처럼 보이지만 6바이트에 못 미친다.
            "3C:86:9A",
            "3C:86:9A:1B:2C",
            // IPv6는 4자리 그룹이라 걸리지 않는다.
            "2001:0db8:85a3:0000:0000:8a2e",
        ).forEach { assertEquals(it, PrivacyMasker.maskText(it)) }
    }
}
