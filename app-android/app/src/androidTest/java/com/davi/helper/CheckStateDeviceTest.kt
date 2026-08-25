package com.davi.helper

import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** API 36 런타임에서 새 체크 상태 API와 제품 직렬화 경계를 함께 검증한다(#297). */
@RunWith(AndroidJUnit4::class)
class CheckStateDeviceTest {

    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.BAKLAVA)
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    fun partialAccessibilityStateSurvivesTheProductionReader() {
        val node = AccessibilityNodeInfo().apply {
            isCheckable = true
            setChecked(AccessibilityNodeInfo.CHECKED_STATE_PARTIAL)
        }

        assertEquals(CheckState.PARTIAL, UiTreeSerializer.checkStateOf(node))
    }
}
