package com.davi.helper.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class SamsungClockAlarmGeminiTest {
    private lateinit var device: UiDevice

    @Before
    fun prepareEnvironment() {
        // UiDevice가 먼저 기본 UiAutomation을 만든다. 그 뒤 DONT_SUPPRESS 플래그를 적용해야
        // UiDevice 초기화가 Davi 접근성 서비스를 다시 억제하지 않는다.
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        LiveGeminiTestEnvironment.requireReady()
    }

    @Test
    fun followsGeminiGuidanceToCreateAndRemoveAlarm() {
        GuidedScenarioRunner(device).run(SamsungClockAlarmScenario())
    }
}
