package com.davi.helper.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Davi 최신 화면에서 Android 홈으로 나가 삼성 시계 목표까지 이어지는 실제 Gemini 관통 테스트. */
@RunWith(AndroidJUnit4::class)
@LargeTest
class SamsungClockFullJourneyGeminiTest {
    private lateinit var device: UiDevice

    @Before
    fun prepareEnvironment() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        LiveGeminiTestEnvironment.requireReady()
    }

    @Test
    fun followsGeminiAcrossLauncherAndClockToCreateAndRemoveAlarm() {
        GuidedScenarioRunner(device).run(
            SamsungClockAlarmScenario(GuidedScenarioStart.DAVI_TO_ANDROID_HOME),
        )
    }
}
