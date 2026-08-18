package com.davi.helper

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import com.davi.helper.databinding.ActivityOnboardingBinding

/**
 * 온보딩 — 접근성·오버레이 권한 안내 (#123). 릴리스 진입점.
 * 권한은 설정 화면에 다녀와야 켜지므로 onResume마다 판정해, 둘 다 있으면
 * 대화 화면으로 넘어가고 자신은 백스택에서 빠진다.
 *
 * 안내 중 접근성이 꺼지면 서비스가 이 화면을 앞으로 가져오고([ServiceOffAction.RETURN_TO_APP]),
 * **왜 왔는지는 이 화면이 말한다**(#190) — 서비스에서 띄운 토스트는 화면 전환에 삼켜진다.
 *
 * 접근성 권한으로 보내기 전에 **고지·동의 면을 거친다**(#227). 무엇을 읽고 왜 읽는지 알린 뒤
 * 명시적 동의를 받아야 설정으로 보낸다 — Play가 접근성 API 사용 앱에 요구하는 순서이자,
 * `SPEC.md` §2·§4의 데이터 정책을 사용자 쪽에서 말이 되게 만드는 자리다.
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding

    /** 고지 면이 떠 있을 때만 켜지는 뒤로가기 가로채기(#227). */
    private val noticeBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = showNotice(false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // targetSdk 35+ edge-to-edge: 시스템 바 인셋을 마진으로 반영(#78 패턴)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
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

        // 설정으로 바로 가지 않는다(#227). 고지·동의 면을 먼저 띄우고, 동의를 받은 뒤에 보낸다.
        binding.btnAccessibility.setOnClickListener { showNotice(true) }
        binding.btnOverlay.setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
        }

        binding.btnNoticeDetail.setOnClickListener { toggleNoticeDetail() }
        binding.btnNoticeLater.setOnClickListener { showNotice(false) }
        binding.btnNoticeAgree.setOnClickListener {
            // 면을 먼저 접는 이유: 설정에 다녀와 돌아왔을 때 권한 면이 보여야 한다. 권한이
            // 켜졌으면 onResume이 대화 화면으로 넘기므로 이 면은 어느 쪽이든 남지 않는다.
            showNotice(false)
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // 고지 면에서의 뒤로가기는 "고지를 접는다"이지 "온보딩을 떠난다"가 아니다.
        onBackPressedDispatcher.addCallback(this, noticeBackCallback)
    }

    /**
     * 고지·동의 면을 띄우거나 접는다.
     *
     * 접을 때 전문도 함께 접는 이유는 다음에 열었을 때 짧은 요약부터 보이게 하려는 것이다 —
     * 시니어 대상 화면이라 첫 화면의 글 분량이 곧 읽힐 확률이다.
     */
    private fun showNotice(show: Boolean) {
        binding.onboardingNoticePane.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) showNoticeDetail(false)
        noticeBackCallback.isEnabled = show
    }

    private fun toggleNoticeDetail() =
        showNoticeDetail(binding.noticeDetail.visibility != View.VISIBLE)

    private fun showNoticeDetail(show: Boolean) {
        binding.noticeDetail.visibility = if (show) View.VISIBLE else View.GONE
        binding.btnNoticeDetail.setText(
            if (show) R.string.btn_a11y_notice_less else R.string.btn_a11y_notice_more,
        )
    }

    override fun onResume() {
        super.onResume()
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
