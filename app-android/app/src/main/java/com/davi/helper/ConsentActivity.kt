package com.davi.helper

import android.content.Intent
import android.os.Bundle
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.ViewGroup
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import com.davi.helper.databinding.ActivityConsentBinding
import com.davi.helper.databinding.ViewDaviConsentItemBinding
import com.davi.helper.databinding.ViewDaviConsentNoticeBinding

/**
 * ON-1.5 개인정보 동의(#389). ON-1(로그인)과 ON-2(권한 설정) 사이에 선다 —
 * [LoginActivity.proceedToOnboarding]가 [ConsentStore]로 판정해 이 화면을 끼워 넣는다.
 *
 * **권한 화면과 합치지 않는다** — 접근성 켜기 버튼과 동의 체크박스가 한 화면에 있으면
 * 시니어가 "버튼 하나 누른 것으로 동의까지 끝났다"고 인지한다(이슈 본문).
 *
 * 항목 카드 넷은 RecyclerView 없이 `<include>` 인클루드 각각을 고유 id로 접근한다(이 앱은
 * 어디에도 RecyclerView를 쓰지 않는다 — activity_consent.xml 머리 주석).
 */
class ConsentActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConsentBinding
    private lateinit var consentStore: ConsentStore
    private val checkedKeys = mutableSetOf<ConsentItemKey>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consentStore = ConsentStore(this)
        binding = ActivityConsentBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // targetSdk 35+ edge-to-edge: 시스템 바 인셋을 마진으로(다른 화면과 같은 패턴).
        ViewCompat.setOnApplyWindowInsetsListener(binding.consentInsets) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = bars.left
                topMargin = bars.top
                rightMargin = bars.right
                bottomMargin = bars.bottom
            }
            WindowInsetsCompat.CONSUMED
        }

        bindItems()
        bindNotices()
        bindAgreeAll()
        bindFooter()
        updateNextButtonAppearance()
    }

    private fun bindItems() {
        bindItem(
            binding.consentItemScreen,
            ConsentItemKey.SCREEN,
            required = true,
            labelRes = R.string.consent_screen_label,
            summaryRes = R.string.consent_screen_summary,
            detailRes = R.string.consent_screen_detail,
        )
        bindItem(
            binding.consentItemVoice,
            ConsentItemKey.VOICE,
            required = true,
            labelRes = R.string.consent_voice_label,
            summaryRes = R.string.consent_voice_summary,
            detailRes = R.string.consent_voice_detail,
        )
        bindItem(
            binding.consentItemUsage,
            ConsentItemKey.USAGE,
            required = true,
            labelRes = R.string.consent_usage_label,
            summaryRes = R.string.consent_usage_summary,
            detailRes = R.string.consent_usage_detail,
        )
        bindItem(
            binding.consentItemKakao,
            ConsentItemKey.KAKAO,
            required = false,
            labelRes = R.string.consent_kakao_label,
            summaryRes = R.string.consent_kakao_summary,
            detailRes = R.string.consent_kakao_detail,
        )
    }

    private fun bindItem(
        item: ViewDaviConsentItemBinding,
        key: ConsentItemKey,
        required: Boolean,
        labelRes: Int,
        summaryRes: Int,
        detailRes: Int,
    ) {
        item.consentItemBadge.setText(if (required) R.string.consent_badge_required else R.string.consent_badge_optional)
        item.consentItemBadge.setTextAppearance(
            if (required) R.style.TextAppearance_Davi_ConsentBadge_Required else R.style.TextAppearance_Davi_ConsentBadge_Optional,
        )
        item.consentItemLabel.setText(labelRes)
        item.consentItemSummary.setText(summaryRes)
        item.consentItemDetail.setText(detailRes)

        val label = getString(labelRes)
        item.root.contentDescription = getString(
            if (required) R.string.consent_item_required_desc else R.string.consent_item_optional_desc,
            label,
        )
        item.root.setOnClickListener { toggleItem(key) }

        // 열지 않아도 체크는 된다(consent_view_detail_desc) — root 클릭과 독립된 토글.
        item.consentItemViewDetail.setOnClickListener {
            val expanding = !item.consentItemDetail.isVisible
            item.consentItemDetail.isVisible = expanding
            item.consentItemViewDetail.text = getString(
                if (expanding) R.string.consent_detail_close else R.string.consent_view_detail,
            )
        }

        setItemChecked(item, checked = false)
    }

    private fun toggleItem(key: ConsentItemKey) {
        val item = itemBindingFor(key)
        val checked = key !in checkedKeys
        if (checked) checkedKeys.add(key) else checkedKeys.remove(key)
        setItemChecked(item, checked)
        updateAgreeAllCheckbox()
        updateNextButtonAppearance()
    }

    private fun itemBindingFor(key: ConsentItemKey): ViewDaviConsentItemBinding = when (key) {
        ConsentItemKey.SCREEN -> binding.consentItemScreen
        ConsentItemKey.VOICE -> binding.consentItemVoice
        ConsentItemKey.USAGE -> binding.consentItemUsage
        ConsentItemKey.KAKAO -> binding.consentItemKakao
    }

    private fun setItemChecked(item: ViewDaviConsentItemBinding, checked: Boolean) {
        item.consentItemCheckbox.setImageResource(
            if (checked) R.drawable.ic_davi_consent_check_checked else R.drawable.ic_davi_consent_check_unchecked,
        )
        ViewCompat.setStateDescription(
            item.root,
            getString(if (checked) R.string.consent_state_checked else R.string.consent_state_unchecked),
        )
    }

    /**
     * 고지 카드 셋. **제목은 셋 다 Pretendard다**(2026-08-24 시안 확인) — 제목·부제가 Cafe24인
     * 것과 갈리는 자리다. 고지는 다비가 건네는 말이 아니라 **읽어야 하는 문서**라 C1의
     * 「다비가 입으로 하는 말」에 들지 않는다. 항목 카드의 라벨이 Pretendard인 것과 같은 결이다.
     *
     * 여기서 서체를 코드로 만지지 않는다 — 테마 기본이 이미 Pretendard다.
     */
    private fun bindNotices() {
        bindNotice(
            binding.consentNoticeThirdParty,
            titleRes = R.string.consent_third_party_title,
            openLabel = getString(R.string.consent_third_party_more),
            body = highlightedThirdPartyBody(),
        )
        bindNotice(
            binding.consentNoticeNeverCollect,
            titleRes = R.string.consent_never_collect_title,
            openLabel = getString(R.string.consent_view_detail),
            body = getString(R.string.consent_never_collect_body),
        )
        bindNotice(
            binding.consentNoticeNoExclusion,
            titleRes = R.string.consent_no_exclusion_notice,
            openLabel = getString(R.string.consent_view_detail),
            body = getString(R.string.consent_no_exclusion_body),
        )
    }

    private fun bindNotice(notice: ViewDaviConsentNoticeBinding, titleRes: Int, openLabel: String, body: CharSequence) {
        notice.consentNoticeTitle.setText(titleRes)
        notice.consentNoticeBody.text = body
        notice.consentNoticeToggle.text = openLabel

        val toggle = {
            val expanding = !notice.consentNoticeBody.isVisible
            notice.consentNoticeBody.isVisible = expanding
            notice.consentNoticeToggle.text = if (expanding) getString(R.string.consent_detail_close) else openLabel
        }
        notice.root.setOnClickListener { toggle() }
        notice.consentNoticeToggle.setOnClickListener { toggle() }
    }

    /**
     * 「지우지 못합니다」 문단에 davi_highlight를 입힌다(strings.xml 주석, 자문 요구
     * "중요 부분 붉은색 표시"). 문단 구분자는 본문의 `\n\n` 그대로 쓴다.
     */
    private fun highlightedThirdPartyBody(): CharSequence {
        val body = getString(R.string.consent_third_party_body)
        val marker = "지우지 못합니다"
        val markerIndex = body.indexOf(marker)
        if (markerIndex == -1) return body
        val paragraphStart = body.lastIndexOf("\n\n", markerIndex).let { if (it == -1) 0 else it + 2 }
        val paragraphEnd = body.indexOf("\n\n", markerIndex).let { if (it == -1) body.length else it }
        return SpannableString(body).apply {
            setSpan(
                ForegroundColorSpan(ContextCompat.getColor(this@ConsentActivity, R.color.davi_highlight)),
                paragraphStart,
                paragraphEnd,
                SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }

    private fun bindAgreeAll() {
        binding.consentAgreeAllRow.setOnClickListener {
            val allChecked = checkedKeys.size == ConsentItemKey.entries.size
            checkedKeys.clear()
            if (!allChecked) checkedKeys.addAll(ConsentItemKey.entries)
            ConsentItemKey.entries.forEach { setItemChecked(itemBindingFor(it), it in checkedKeys) }
            updateAgreeAllCheckbox()
            updateNextButtonAppearance()
        }
    }

    private fun updateAgreeAllCheckbox() {
        val allChecked = checkedKeys.size == ConsentItemKey.entries.size
        binding.consentAgreeAllCheckbox.setImageResource(
            if (allChecked) R.drawable.ic_davi_consent_check_checked else R.drawable.ic_davi_consent_check_unchecked,
        )
    }

    private fun bindFooter() {
        binding.consentPrivacyPolicyLink.isVisible = getString(R.string.consent_policy_url).isNotEmpty()

        binding.btnConsentNext.setOnClickListener {
            if (ConsentGate.canProceed(checkedKeys)) {
                binding.consentRequiredMissing.isVisible = false
                consentStore.saveDecision(
                    nowEpochSeconds(),
                    checkedKeys,
                    getString(R.string.consent_version),
                    BuildConfig.VERSION_NAME,
                )
                startActivity(Intent(this, OnboardingActivity::class.java))
                finish()
            } else {
                binding.consentRequiredMissing.isVisible = true
            }
        }

        binding.btnConsentDecline.setOnClickListener { binding.consentDeclineScrim.isVisible = true }
        binding.btnConsentDeclineCancel.setOnClickListener { binding.consentDeclineScrim.isVisible = false }
        binding.btnConsentDeclineAccept.setOnClickListener { finishAffinity() }

        // 확인창이 떠 있으면 뒤로가기는 그것만 닫는다 — 화면째 빠져나가면 「그만두시겠어요?」에
        // 답하지 않고 나간 것이 되고, 그 답이 곧 동의 여부다.
        onBackPressedDispatcher.addCallback(this) {
            if (binding.consentDeclineScrim.isVisible) {
                binding.consentDeclineScrim.isVisible = false
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }
    }

    /**
     * 필수가 덜 채워졌을 때 **비활성으로 보이되 눌리면 이유를 말한다**(strings.xml 주석) —
     * 진짜 `isEnabled = false`로 두면 클릭 자체가 안 와서 그 이유를 말할 기회가 없다.
     */
    private fun updateNextButtonAppearance() {
        val canProceed = ConsentGate.canProceed(checkedKeys)
        binding.btnConsentNext.alpha = if (canProceed) 1f else 0.5f
        if (canProceed) binding.consentRequiredMissing.isVisible = false
    }

    private fun nowEpochSeconds(): Long = System.currentTimeMillis() / 1000
}
