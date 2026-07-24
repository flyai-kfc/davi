package com.davi.helper

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import com.davi.helper.databinding.ActivityMainBinding

/**
 * 온보딩 화면. 접근성·오버레이 두 특수 권한을 켜도록 안내한다(SPEC §2, §5-7).
 *
 * UI 컨벤션: 화면은 XML 레이아웃 + ViewBinding (app-android/CLAUDE.md).
 * 글자 크기·터치 대상은 res/values/dimens.xml에서 온다. 코드에서 px를 계산하지 않는다.
 *
 * TODO: 두 모드(가족 대신 설정 / 본인) 분기, 권한 상태 표시, 단계별 안내.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnAccessibility.setOnClickListener { openAccessibilitySettings() }
        binding.btnOverlay.setOnClickListener { openOverlaySettings() }
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun openOverlaySettings() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            ),
        )
    }
}
