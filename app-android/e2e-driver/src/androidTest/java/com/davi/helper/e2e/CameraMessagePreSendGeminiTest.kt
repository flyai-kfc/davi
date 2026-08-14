package com.davi.helper.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Davi→홈→메시지→카메라→메시지를 잇되 실제 전송은 막는 실제 Gemini 관통 테스트. */
@RunWith(AndroidJUnit4::class)
@LargeTest
class CameraMessagePreSendGeminiTest {
    private lateinit var device: UiDevice

    @Before
    fun prepareEnvironment() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        LiveGeminiTestEnvironment.requireReady()
    }

    @Test
    fun followsGeminiToAttachNewPhotoWithoutSendingMessage() {
        GuidedScenarioRunner(device).run(CameraMessagePreSendScenario())
    }
}
