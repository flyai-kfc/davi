package com.davi.helper.e2e

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 전용 테스트 수신자 격리 회귀.
 *
 * 지키는 성질 둘 — **설정하지 않으면 시작하지 못한다**, 그리고 **원문이 로그로 새지 않는다**.
 */
class SmsTestRecipientTest {
    private val valid = "010-1234-5678"

    @Test
    fun 설정하지_않으면_시작하지_못한다() {
        listOf(null, "", "   ").forEach { raw ->
            val failure = assertThrows(IllegalArgumentException::class.java) {
                SmsTestRecipient.parse(raw)
            }
            assertTrue(
                "raw=$raw 메시지=${failure.message}",
                failure.message!!.contains(SmsTestRecipient.ARGUMENT),
            )
        }
    }

    @Test
    fun toString은_원문을_돌려주지_않는다() {
        val recipient = SmsTestRecipient.parse(valid)

        assertEquals(SmsTestRecipient.REDACTED, recipient.toString())
        assertEquals(SmsTestRecipient.REDACTED, "$recipient")
        assertFalse("$recipient".contains("1234"))
        assertFalse("$recipient".contains("5678"))
    }

    /** 문자열 보간은 로그가 값을 흘리는 가장 흔한 경로다. 그 경로가 막혔는지 직접 고정한다. */
    @Test
    fun 로그_문장에_넣어도_숫자가_남지_않는다() {
        val recipient = SmsTestRecipient.parse(valid)
        val line = "step=3 recipient=$recipient action=type"

        assertFalse(line.contains("1234"))
        assertFalse(line.contains("5678"))
        assertFalse(line.contains("01012345678"))
        assertTrue(line.contains(SmsTestRecipient.REDACTED))
    }

    @Test
    fun 원문은_입력_주입_경로에서만_나온다() {
        assertEquals("01012345678", SmsTestRecipient.parse(valid).valueForInput())
        assertEquals("01012345678", SmsTestRecipient.parse("010 1234 5678").valueForInput())
        assertEquals("01012345678", SmsTestRecipient.parse(" (010) 1234-5678 ").valueForInput())
    }

    @Test
    fun 비식별_힌트는_끝_두_자리만_남긴다() {
        val hint = SmsTestRecipient.parse(valid).redactedHint()

        assertEquals("[redacted:••78]", hint)
        assertFalse(hint.contains("1234"))
    }

    /**
     * 손으로 시험할 때 수신자가 우연히 `000`이라 사고가 안 났던 적이 있다. 그건 운이 좋았던 것이지
     * 안전한 설정이 아니다 — 그런 값으로는 시작하지 못한다.
     */
    @Test
    fun 짧은_값은_거부한다() {
        listOf("000", "0", "1234", "010-1234", "(02) 123").forEach { raw ->
            assertThrows("raw=$raw", IllegalArgumentException::class.java) {
                SmsTestRecipient.parse(raw)
            }
        }
    }

    @Test
    fun 부호만_있고_숫자가_없으면_거부한다() {
        listOf("-", "()", " - ( ) ").forEach { raw ->
            assertThrows("raw=$raw", IllegalArgumentException::class.java) {
                SmsTestRecipient.parse(raw)
            }
        }
    }

    /**
     * #134 안전 경계 — 수신자는 앱 `PrivacyMasker`가 **실제로 가리는** 형식이어야 한다.
     *
     * 자릿수 검증이 아니다. 마스킹이 못 알아보는 형식이면 번호가 화면 label에 원문으로 남아
     * 서버를 거쳐 모델까지 나간다. 상세 보고서를 꺼도 이 경로는 막히지 않는다.
     */
    @Test
    fun 앱_마스킹이_가리지_못하는_형식은_거부한다() {
        listOf(
            "+82 10 1234 5678", // 국제 표기
            "02-123-4567", // 유선
            "070-1234-5678", // 인터넷 전화
            "0155-123-4567", // 옛 번호대
            "015-1234-5678",
        ).forEach { raw ->
            assertThrows("raw=$raw", IllegalArgumentException::class.java) {
                SmsTestRecipient.parse(raw)
            }
        }
    }

    /** 허용하는 형식은 `PrivacyMasker`의 전화번호 정규식이 실제로 잡는 것과 같아야 한다. */
    @Test
    fun 마스킹되는_국내_휴대폰_형식만_받는다() {
        val masker = Regex("\\b01[016789][-\\s]?\\d{3,4}[-\\s]?\\d{4}\\b")

        listOf("010-1234-5678", "01012345678", "011 234 5678", "016-123-4567").forEach { raw ->
            val accepted = SmsTestRecipient.parse(raw)
            assertTrue(
                "raw=$raw 를 받았지만 PrivacyMasker가 가리지 못한다",
                masker.containsMatchIn(accepted.valueForInput()),
            )
        }
    }

    @Test
    fun 긴급_특수_번호는_거부한다() {
        SmsTestRecipient.FORBIDDEN.forEach { number ->
            assertThrows(
                "number=$number",
                IllegalArgumentException::class.java,
            ) { SmsTestRecipient.parse(number) }
        }
    }

    @Test
    fun 형식이_아닌_값은_거부한다() {
        listOf("abc", "010-1234-56ab", "*100#", "010;1234;5678", "sms:01012345678").forEach { raw ->
            assertThrows("raw=$raw", IllegalArgumentException::class.java) {
                SmsTestRecipient.parse(raw)
            }
        }
    }

    @Test
    fun 너무_긴_값은_거부한다() {
        assertThrows(IllegalArgumentException::class.java) {
            SmsTestRecipient.parse("010-1234-56789")
        }
    }

    /** 실패 메시지가 곧 번호 유출이 되면 안 된다. */
    @Test
    fun 실패_메시지에_입력값을_넣지_않는다() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            SmsTestRecipient.parse("010-9999-8888!")
        }

        assertFalse(failure.message!!.contains("9999"))
        assertFalse(failure.message!!.contains("8888"))
    }
}
