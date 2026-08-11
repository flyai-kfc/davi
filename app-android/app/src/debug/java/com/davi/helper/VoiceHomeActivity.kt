package com.davi.helper

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import com.davi.helper.databinding.ActivityVoiceHomeBinding

/**
 * MA-1 홈 미리보기 (docs/SCREENS.md). debug 전용 — 릴리스에 포함되지 않는다.
 *
 * 화면·토큰 검증이 목적이라 음성 로직을 중복 구현하지 않고, 마이크 탭은 기존
 * MainActivity의 음성 진입으로 위임한다. MainActivity 교체 시점은 팀 결정(#100).
 */
class VoiceHomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVoiceHomeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVoiceHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // targetSdk 35+ edge-to-edge: 시스템 바 인셋을 마진으로 반영 (#78과 같은 패턴)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = bars.top
                bottomMargin = bars.bottom
            }
            WindowInsetsCompat.CONSUMED
        }

        binding.btnMic.setOnClickListener {
            startActivity(
                Intent(this, MainActivity::class.java).putExtra(EXTRA_START_VOICE, true),
            )
        }
    }
}
