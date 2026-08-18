package com.davi.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1차 마스킹 우회 플래그(#203)의 해석을 고정한다.
 *
 * `UiTreeSerializer`는 `AccessibilityNodeInfo`를 목킹할 수 없어 여기서 직렬화 전체를
 * 돌리지는 못한다. 대신 플래그 -> 정책 -> label 변환의 사슬을 값 수준에서 못박는다.
 */
class LabelMaskingTest {

    @Test
    fun `기본 정책은 서버와 같은 PII 패턴을 치환한다`() {
        val masker = labelMaskerFor(maskingDisabled = false)
        assertEquals("연락처 •••", masker.mask("연락처 hong@example.com"))
        assertEquals("휴대폰 •••", masker.mask("휴대폰 010-1234-5678"))
    }

    @Test
    fun `우회 정책은 원문을 그대로 둔다`() {
        val masker = labelMaskerFor(maskingDisabled = true)
        assertEquals("연락처 hong@example.com", masker.mask("연락처 hong@example.com"))
        assertEquals("휴대폰 010-1234-5678", masker.mask("휴대폰 010-1234-5678"))
    }

    @Test
    fun `플래그가 정확히 두 정책으로만 갈린다`() {
        assertSame(PrivacyMaskingLabelMasker, labelMaskerFor(maskingDisabled = false))
        assertSame(PassthroughLabelMasker, labelMaskerFor(maskingDisabled = true))
    }

    @Test
    fun `우회해도 PII가 아닌 텍스트의 결과는 기본 정책과 같다`() {
        // 비교 실험의 전제 — 달라지는 것은 PII 치환뿐이고 그 외 label은 동일해야 한다.
        val plain = listOf("카카오톡", "저장", "", "2 페이지 중 1 페이지입니다.")
        for (text in plain) {
            assertEquals(
                PrivacyMaskingLabelMasker.mask(text),
                PassthroughLabelMasker.mask(text),
            )
        }
    }

    @Test
    fun `release 산출물은 우회 플래그를 false로 고정한다`() {
        // 이 단위 테스트는 debug BuildConfig로 돈다. 기본 debug 빌드(-P 없이)에서도 꺼져
        // 있어야 한다는 것이 "기본값은 모든 variant에서 마스킹 활성화"의 실제 보증이다.
        // release 쪽 상수 false는 build.gradle.kts가 buildConfigField로 못박는다.
        assertTrue(
            "기본 debug 빌드에서 우회가 켜져 있으면 안 된다",
            !BuildConfig.PRIVACY_MASKING_DISABLED,
        )
    }
}
