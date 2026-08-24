package com.davi.helper

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/**
 * [PrivacyMasker]의 패턴이 **기기의 정규식 엔진에서** 컴파일되고 같게 동작하는지 본다(#400).
 *
 * **이 계열은 JVM 단위 테스트를 그대로 통과한다.** 앱 단위 테스트는 OpenJDK 정규식 위에서
 * 돌지만 기기(SM-S921N/SDK 36 실측)는 ICU 위에서 돈다. 인라인 `(?U)`를 ICU가 거부해
 * `<clinit>`이 죽고 화면 직렬화가 매 폴링 실패하는 동안, `PrivacyMaskerTest`는 전부 초록이었다.
 *
 * 그래서 여기는 **엔진이 다르다는 사실에 걸리는 것만** 둔다 — 전체 동작 목록은
 * `PrivacyMaskerTest`가 갖는다. 목록을 두 벌 두면 한쪽이 먼저 낡는다.
 */
@RunWith(AndroidJUnit4::class)
class PrivacyMaskerDeviceTest {

    /** 여섯 패턴을 한 번에 태운다. 컴파일이 깨지면 `<clinit>`이 죽어 첫 줄에서 멈춘다. */
    @Test
    fun compilesEveryPatternOnDevice() {
        assertEquals("•••", PrivacyMasker.maskText("hong@example.com"))
        assertEquals("•••", PrivacyMasker.maskText("3C:86:9A:1B:2C:3D"))
        assertEquals("•••", PrivacyMasker.maskText("900101-1234567"))
        assertEquals("•••", PrivacyMasker.maskText("1234-5678-9012-3456"))
        assertEquals("•••", PrivacyMasker.maskText("010-1234-5678"))
        assertEquals("•••", PrivacyMasker.maskText("110-123-456789"))
    }

    /**
     * 화면 직렬화가 실제로 지나가는 진입점(`LabelMasking.kt`)까지 태운다.
     *
     * 결함이 났을 때 로그에 남은 것이 이 자리였다 — `PrivacyMaskingLabelMasker.mask`가
     * `NoClassDefFoundError`로 죽어 `serialize failed`가 됐다.
     */
    @Test
    fun labelMaskerEntryPointSurvivesClassInitOnDevice() {
        assertEquals("연락처 •••", PrivacyMaskingLabelMasker.mask("연락처 010-1234-5678"))
    }

    /**
     * 이 엔진이 [Pattern.UNICODE_CHARACTER_CLASS]를 **거부한다**는 사실을 고정한다.
     *
     * [PrivacyMasker]가 플래그를 무조건 얹지 않고 런타임을 재는 유일한 이유가 이것이라, 재지
     * 않으면 근거가 주석 속 전언으로만 남는다. **이 줄이 빨개지면 고장이 아니라 단순화 신호다**
     * — 플래그가 받아들여지기 시작했다면 프로브를 지우고 무조건 얹으면 된다.
     */
    @Test
    fun unicodeCharacterClassFlagIsRejectedOnDevice() {
        assertThrows(IllegalArgumentException::class.java) {
            Pattern.compile("\\d", Pattern.UNICODE_CHARACTER_CLASS)
        }
    }

    /**
     * `\d`·`\s`의 유니코드 해석이 엔진과 무관하게 같은지 고정한다(#361 결정 유지).
     *
     * OpenJDK는 `UNICODE_CHARACTER_CLASS`를 얹어야 하고 ICU는 기본이 유니코드다. **얹는
     * 방법이 갈려도 전각 숫자·NBSP에서는 결과가 같아야 한다** — 앱이 놓치면 원본이 그대로
     * 기기를 떠난다(`SPEC.md` §2). 두 엔진의 `\s` 정의는 **문서상 완전히 같지는 않아**(U+0085 같은
     * 경계 문자에서 갈린다) 잰 범위를 전각 숫자·NBSP로 한정한다.
     */
    @Test
    fun masksUnicodeDigitAndSpaceVariantsOnDevice() {
        val nbsp = "\u00a0"
        assertEquals("•••", PrivacyMasker.maskText("010" + nbsp + "1234" + nbsp + "5678"))
        assertEquals("•••", PrivacyMasker.maskText("123456" + nbsp + "01" + nbsp + "123456"))
        assertEquals(
            "•••",
            PrivacyMasker.maskText("\uff11\uff12\uff13\uff14\uff15\uff16-01-123456"),
        )
    }

    /**
     * 넓힌 해석이 오탐으로 번지지 않는지. 패턴 밖 고유명·시각·날짜는 지우지 않는다
     * (`SPEC.md` §2, #360) — 지우면 안내가 죽는다.
     *
     * **ASCII 숫자·공백만으로 된 입력은 여기 두지 않는다** — 플래그가 0이든 아니든 결과가
     * 같아 엔진 축을 재지 못한다. 그 목록은 `PrivacyMaskerTest`가 갖는다. 아래 넷은 유니코드
     * 해석이 켜져야 비로소 패턴 문자로 읽히는 전각 숫자·NBSP를 담은 비-PII다.
     */
    @Test
    fun leavesNonPiiLabelsUntouchedOnDevice() {
        val nbsp = "\u00a0"
        listOf(
            // 전각 숫자로 쓴 페이지 안내. `eval/fixtures`의 label `"2 페이지 중 1 페이지입니다."`가
            // 원형이다(픽스처 label 123개 중 마스킹으로 바뀌는 것은 0개다).
            "\uff12 페이지 중 \uff11 페이지입니다.",
            // NBSP로 구분한 날짜. **유니코드 해석에서만** 계좌 패턴의 날짜 갈래에 걸리고, 걸린 뒤
            // 원문으로 되돌려져야 한다(#377의 되돌리기 규칙). 넷 중 그 경로를 타는 건 이것뿐이다.
            "2026" + nbsp + "08" + nbsp + "18",
            // 두 덩어리뿐이라 계좌가 아니다.
            "12" + nbsp + "34",
            "오후" + nbsp + "3:45",
        ).forEach { assertEquals(it, PrivacyMasker.maskText(it)) }
    }
}
