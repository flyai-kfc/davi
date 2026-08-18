package com.davi.helper.e2e

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry

/** [SmsTestRecipient]를 instrumentation 인자에서 읽는 기기 쪽 어댑터. 검증 규칙은 순수부에 있다. */
internal object SmsTestRecipients {
    const val FAILURE_CODE = "sms_test_recipient_missing"

    /**
     * `-e davi.smsTestRecipient <번호>`로 넘어온 전용 테스트 수신자.
     *
     * 설정하지 않으면 [GuidedScenarioFailure]로 시나리오를 세운다 — 기본값을 두지 않는 이유는
     * 아무 기기에서나 문자 시나리오가 돌기 시작하는 것을 막기 위해서다(#134).
     */
    fun require(): SmsTestRecipient {
        val raw = InstrumentationRegistry.getArguments().getString(SmsTestRecipient.ARGUMENT)
        return try {
            SmsTestRecipient.parse(raw)
        } catch (invalid: IllegalArgumentException) {
            // parse는 실패 메시지에 입력값을 넣지 않는다(SmsTestRecipientTest가 고정). 그래서
            // 거절 사유는 그대로 남겨도 번호가 새지 않는다.
            Log.i(TRACE_TAG, "sms_recipient_rejected reason=${invalid.message}")
            throw GuidedScenarioFailure(FAILURE_CODE)
        }
    }

    private const val TRACE_TAG = "DaviE2e"
}
