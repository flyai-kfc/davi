package com.davi.helper

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.appcompat.app.AppCompatActivity
import com.davi.helper.databinding.ActivityMainBinding

/**
 * 온보딩 + 목표 입력 화면. 접근성·오버레이 두 특수 권한을 켜게 하고(SPEC §2, §5-7),
 * 두 권한이 모두 있으면 목표 하나로 안내 세션을 시작·중지한다.
 *
 * UI 컨벤션: 화면은 XML 레이아웃 + ViewBinding (app-android/CLAUDE.md).
 * 글자 크기·터치 대상은 res/values/dimens.xml에서 온다. 코드에서 px를 계산하지 않는다.
 *
 * 가족/본인 두 모드는 베타 범위다(SPEC §3 비목표). 권한 상태와 오류를 사용자 문구로 보여주는 것은
 * 관통 다음 작업이다 — 지금은 시작 버튼의 활성 여부로만 드러난다.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // 서버가 done을 주면 서비스 쪽에서 세션이 끝난다. 그때도 버튼이 따라 바뀌어야 한다.
    private val sessionListener = GuideSession.Listener { runOnUiThread { refreshUi() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnAccessibility.setOnClickListener { openAccessibilitySettings() }
        binding.btnOverlay.setOnClickListener { openOverlaySettings() }
        binding.btnStart.setOnClickListener { startGuide() }
        binding.btnStop.setOnClickListener { GuideSession.stop() }
    }

    /** 권한은 설정 화면에 다녀와야 켜진다. 돌아올 때마다 다시 읽는다. */
    override fun onResume() {
        super.onResume()
        GuideSession.addListener(sessionListener)
        refreshUi()
    }

    override fun onPause() {
        GuideSession.removeListener(sessionListener)
        super.onPause()
    }

    private fun startGuide() {
        val goal = binding.goalInput.text?.toString().orEmpty().trim()
        if (goal.isEmpty()) {
            binding.goalInput.error = getString(R.string.goal_required)
            binding.goalInput.requestFocus()
            return
        }
        GuideSession.start(goal)
    }

    private fun refreshUi() {
        val ready = hasAccessibilityPermission() && Settings.canDrawOverlays(this)
        val active = GuideSession.isActive

        // 안내 중에 목표를 바꾸면 화면과 서버 요청이 어긋난다. 중지한 뒤에 바꾸게 한다.
        // 입력 중인 글자는 건드리지 않는다.
        binding.goalInput.isEnabled = !active
        binding.btnStart.isEnabled = ready && !active
        binding.btnStop.isEnabled = active
    }

    /**
     * 접근성 서비스가 켜져 있는가.
     *
     * `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` 문자열을 직접 파싱하지 않는다 —
     * 콜론으로 이어붙인 목록이라 부분 문자열로 비교하면 다른 패키지에 오탐이 난다.
     */
    private fun hasAccessibilityPermission(): Boolean {
        val manager = getSystemService(AccessibilityManager::class.java) ?: return false
        return manager
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any {
                val info = it.resolveInfo.serviceInfo
                info.packageName == packageName &&
                    info.name == GuideAccessibilityService::class.java.name
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
