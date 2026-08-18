package com.davi.helper

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import com.davi.helper.databinding.ActivityOnboardingBinding

/**
 * 온보딩 — **접근성 권한 하나**를 켜게 안내한다(#123). 릴리스 진입점.
 * 권한은 설정 화면에 다녀와야 켜지므로 onResume마다 판정해, 갖춰지면 대화 화면으로 넘어가고
 * 자신은 백스택에서 빠진다.
 *
 * `SYSTEM_ALERT_WINDOW`는 여기서 사라졌다(#212) — 창 타입이 그 권한을 타지 않고(#56), 남아 있던
 * 유일한 쓰임인 자동 복귀는 알림 탭이 대신한다(`ServiceOffAction` KDoc).
 *
 * 안내 중 접근성이 꺼지면 서비스가 알림을 띄우고, 그 알림을 누르면 이 화면으로 온다.
 * **왜 왔는지는 이 화면이 말한다**(#190, `EXTRA_GUIDE_STOPPED`).
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // targetSdk 35+ edge-to-edge: 시스템 바 인셋을 마진으로 반영(#78 패턴)
        // **루트가 아니라 인셋 층에 준다**(#236). 루트에 주면 그 안에 든 배경 메시까지 함께
        // 밀려 화면 끝까지 못 가고, 시스템 바 뒤로 windowBackground가 흰 띠로 드러난다.
        // 대화 화면이 conversation_insets를 따로 둔 것과 같은 이유다(PR #237 리뷰).
        ViewCompat.setOnApplyWindowInsetsListener(binding.onboardingInsets) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = bars.left
                topMargin = bars.top
                rightMargin = bars.right
                bottomMargin = bars.bottom
            }
            WindowInsetsCompat.CONSUMED
        }

        // 서비스가 되돌린 복귀다(#190). 여기서 띄우는 이유는 이 화면이 이미 떠 있어서다 —
        // onUnbind에서 띄우면 뒤따르는 화면 전환이 토스트를 삼킨다(SM-S921N 실측: Toast.show()는
        // 불렸는데 0.7·1.4·2.1초 캡처 어디에도 없었다).
        // extra를 지우는 이유는 회전 등으로 onCreate가 다시 돌 때 또 뜨지 않게 하려는 것이다.
        if (intent?.getBooleanExtra(EXTRA_GUIDE_STOPPED, false) == true) {
            Toast.makeText(this, R.string.guide_service_off, Toast.LENGTH_LONG).show()
            intent.removeExtra(EXTRA_GUIDE_STOPPED)
        }

        binding.btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    /**
     * 안내가 멈췄을 때 알릴 유일한 수단이 알림이라 여기서 한 번 묻는다(#212).
     *
     * **특수 권한 둘과 나란히 두지 않는다** — 설정 앱에 다녀올 필요가 없는 일반 런타임 권한이라
     * 화면에 줄을 늘릴 값이 없다. **거부해도 진행을 막지 않는다**: 거부하면 그 갈래가 조용해질
     * 뿐이고(지금도 그렇다), 사용자가 앱을 열면 아래 onResume이 여전히 잡아낸다.
     */
    private val requestPostNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private fun askPostNotificationsOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        // 이미 있거나 사용자가 한 번 거부했으면 다시 묻지 않는다 — 시스템이 두 번째 요청을
        // 조용히 무시하므로 다시 부르는 것은 의미가 없고, 화면만 한 번 더 깜빡인다.
        if (granted || shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) return
        requestPostNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onResume() {
        super.onResume()
        askPostNotificationsOnce()
        if (canStartGuide()) {
            startActivity(Intent(this, ConversationActivity::class.java))
            finish()
        }
    }

    companion object {
        /** 안내 중 접근성이 꺼져 서비스가 되돌린 복귀인가(#190). */
        const val EXTRA_GUIDE_STOPPED = "guide_stopped"
    }
}
