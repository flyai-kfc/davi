package com.davi.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
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
    fun `마스킹 우회 중에는 dogfood raw 저장 식별자를 보내지 않는다`() {
        assertFalse(
            shouldAttachDogfoodDeviceId(
                dogfoodOptIn = true,
                maskingDisabled = true,
            ),
        )
        assertFalse(
            shouldAttachDogfoodDeviceId(
                dogfoodOptIn = false,
                maskingDisabled = true,
            ),
        )
        assertTrue(
            shouldAttachDogfoodDeviceId(
                dogfoodOptIn = true,
                maskingDisabled = false,
            ),
        )
    }

    @Test
    fun `마스킹 우회 중에는 화면 상세 로그를 남기지 않는다`() {
        assertFalse(
            shouldLogDetailedScreen(
                maskingDisabled = true,
                e2eSuppressesDetailedLogs = false,
            ),
        )
        assertFalse(
            shouldLogDetailedScreen(
                maskingDisabled = false,
                e2eSuppressesDetailedLogs = true,
            ),
        )
        assertTrue(
            shouldLogDetailedScreen(
                maskingDisabled = false,
                e2eSuppressesDetailedLogs = false,
            ),
        )
    }

    @Test
    fun `기본 debug 빌드는 우회가 꺼져 있다`() {
        // local.properties 또는 -P로 의도적으로 우회 실험을 하는 빌드에서는 이 기본값 검사를
        // 건너뛴다. 기준선은 -PdaviDisablePrivacyMasking=false로 명시해 다시 실행할 수 있다.
        assumeFalse(
            "마스킹 우회 실험 빌드에서는 기본값 검사를 건너뜁니다",
            BuildConfig.PRIVACY_MASKING_DISABLED,
        )
        assertSame(
            PrivacyMaskingLabelMasker,
            labelMaskerFor(BuildConfig.PRIVACY_MASKING_DISABLED),
        )
    }
}
