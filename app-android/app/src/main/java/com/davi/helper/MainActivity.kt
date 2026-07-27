package com.davi.helper

import android.content.Intent
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityManager
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
 * 가족/본인 두 모드는 베타 범위다. 이번 화면은 내부 도그푸딩에서 권한을 확인하고,
 * 목표 하나를 입력해 안내 세션을 시작·중지하는 데만 집중한다(SPEC §3).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val sessionListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            // 접근성 서비스의 네트워크 스레드에서도 상태를 갱신한다.
            runOnUiThread { refreshUi() }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnAccessibility.setOnClickListener { openAccessibilitySettings() }
        binding.btnOverlay.setOnClickListener { openOverlaySettings() }
        binding.btnStart.setOnClickListener { startGuide() }
        binding.btnStop.setOnClickListener {
            GuideSessionStore.stop(this)
        }
    }

    override fun onResume() {
        super.onResume()
        getSharedPreferences("guide_session", MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(sessionListener)
        refreshUi()
    }

    override fun onPause() {
        getSharedPreferences("guide_session", MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(sessionListener)
        super.onPause()
    }

    private fun startGuide() {
        val goal = binding.goalInput.text?.toString()?.trim().orEmpty()
        if (goal.isEmpty()) {
            binding.goalInput.error = getString(R.string.goal_required)
            binding.goalInput.requestFocus()
            return
        }
        if (!hasAccessibilityPermission() || !Settings.canDrawOverlays(this)) return
        GuideSessionStore.start(this, goal)
    }

    private fun refreshUi() {
        val hasAccessibility = hasAccessibilityPermission()
        val hasOverlay = Settings.canDrawOverlays(this)
        val session = GuideSessionStore.snapshot(this)

        binding.accessibilityStatus.text = getString(
            if (hasAccessibility) R.string.permission_allowed else R.string.permission_required,
        )
        binding.overlayStatus.text = getString(
            if (hasOverlay) R.string.permission_allowed else R.string.permission_required,
        )
        binding.goalInput.isEnabled = !session.isActive
        if (!session.isActive && binding.goalInput.text?.toString() != session.goal) {
            binding.goalInput.setText(session.goal)
        }
        binding.btnStart.isEnabled = hasAccessibility && hasOverlay && !session.isActive
        binding.btnStop.isEnabled = session.isActive
        binding.guideStatus.text = getString(statusText(session.status))
    }

    private fun statusText(status: GuideSessionStatus): Int = when (status) {
        GuideSessionStatus.READY -> R.string.guide_ready
        GuideSessionStatus.GUIDING -> R.string.guide_guiding
        GuideSessionStatus.COMPLETED -> R.string.guide_completed
        GuideSessionStatus.STOPPED -> R.string.guide_stopped
        GuideSessionStatus.NETWORK_ERROR -> R.string.guide_network_error
        GuideSessionStatus.SERVER_ERROR -> R.string.guide_server_error
    }

    private fun hasAccessibilityPermission(): Boolean {
        val manager = getSystemService(AccessibilityManager::class.java)
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { service ->
                service.resolveInfo.serviceInfo.packageName == packageName &&
                    service.resolveInfo.serviceInfo.name == GuideAccessibilityService::class.java.name
            }
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
