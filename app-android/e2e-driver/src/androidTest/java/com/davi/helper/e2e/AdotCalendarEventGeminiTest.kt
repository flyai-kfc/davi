package com.davi.helper.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Davi 최신 화면에서 홈으로 나가 에이닷 캘린더 일정 추가·삭제까지 잇는 실제 Gemini 관통 테스트. */
@RunWith(AndroidJUnit4::class)
@LargeTest
class AdotCalendarEventGeminiTest {
    private lateinit var device: UiDevice

    @Before
    fun prepareEnvironment() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        LiveGeminiTestEnvironment.requireReady()
    }

    @Test
    fun followsGeminiToAddAndRemoveCalendarEvent() {
        GuidedScenarioRunner(device).run(AdotCalendarEventScenario())
    }
}
