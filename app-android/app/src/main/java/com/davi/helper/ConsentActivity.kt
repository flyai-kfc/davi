package com.davi.helper

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.ViewGroup
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
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

        // **재생성 전에 채운 것을 되살린다**(PR #409 리뷰 P1). 이 화면에는 configChanges가
        // 없고 저장소가 재생성 경로를 의도적으로 열어 뒀다(매니페스트의 ConversationActivity
        // 주석 「fontScale·uiMode는 일부러 넣지 않는다」). 전문을 읽다 글자 배율을 올리고
        // 돌아오면 체크가 통째로 풀리는데 **왜 그런지는 아무 데도 안 나온다.**
        savedInstanceState?.getStringArrayList(STATE_CHECKED)?.let { saved ->
            saved.mapNotNullTo(checkedKeys) { name -> ConsentItemKey.entries.find { it.name == name } }
        }

        bindItems()
        bindNotices()
        bindAgreeAll()
        bindFooter()
        updateAgreeAllCheckbox()
        updateNextButtonAppearance()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // 펼침·확인창 표시는 뷰 상태라 시스템이 id로 되살린다. 체크는 메모리에만 있어 여기서 든다.
        outState.putStringArrayList(STATE_CHECKED, checkedKeys.mapTo(ArrayList()) { it.name })
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
        // **로그인이 안 되는 빌드에서는 카카오 카드를 감춘다**(PR #409 리뷰 P2).
        // release 기본값은 LOGIN_ENABLED=false라 ON-1이 카카오 버튼을 숨기고 SDK도 init되지
        // 않는데, 이 카드를 그대로 두면 **그 빌드에서 일어나지 않는 수집에 동의가 기록된다.**
        // ON-1이 버튼을 숨기는 것과 같은 규칙이다.
        binding.consentItemKakao.root.isVisible = BuildConfig.LOGIN_ENABLED
        if (BuildConfig.LOGIN_ENABLED) {
            bindItem(
                binding.consentItemKakao,
                ConsentItemKey.KAKAO,
                required = false,
                labelRes = R.string.consent_kakao_label,
                summaryRes = R.string.consent_kakao_summary,
                detailRes = R.string.consent_kakao_detail,
            )
        }
    }

    /**
     * 「모두 동의」가 켜는 항목. **화면에 선 것만이다** — 감춘 카카오 카드까지 켜면 보이지도
     * 않는 항목에 동의가 기록되고, `updateAgreeAllCheckbox`의 「다 켜졌나」 판정도 영원히
     * 거짓이 되어 체크가 안 들어온다.
     */
    private fun visibleKeys(): List<ConsentItemKey> = ConsentItemKey.entries.filter {
        it != ConsentItemKey.KAKAO || BuildConfig.LOGIN_ENABLED
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
            val label = getString(if (expanding) R.string.consent_detail_close else R.string.consent_view_detail)
            item.consentItemViewDetail.text = label
            // **contentDescription을 함께 갱신한다.** 있으면 TalkBack이 그것만 읽고 text를
            // 무시하므로, 안 바꾸면 「닫기」가 보이는데 「…자세한 내용 보기」로 읽힌다 —
            // 이미 펼친 것을 또 열라고 말하게 된다(PR #409 리뷰).
            // 펼친 뒤에는 라벨이 곧 설명이라 따로 문자열을 두지 않는다.
            item.consentItemViewDetail.contentDescription =
                if (expanding) label else getString(R.string.consent_view_detail_desc)
        }

        setItemChecked(item, checked = key in checkedKeys)
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
        // **root가 제목을 읽어야 한다**(PR #409 리뷰 P1). 제목은 importantForAccessibility=no,
        // 본문은 접힌 상태에서 gone, 토글은 별도 포커스라 **읽을 내용이 하나도 없었다** —
        // 제3자 고지는 법 제20조 통지 몫이라 특히 아픈 자리다. 항목 카드가 bindItem에서
        // root에 라벨을 얹는 것과 같게 맞춘다.
        notice.root.contentDescription = getString(titleRes)
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
        // **행 자체가 무엇인지·켜졌는지 읽히지 않았다**(PR #409 리뷰 P2). 자식 둘이 모두
        // importantForAccessibility=no라 라벨이 합산에서 빠지고, 상태는 ImageView 그림만
        // 바뀌었다. 두 번 눌러 넷을 전부 **해제해 놓고** 모른 채 「다음으로」로 갈 수 있었다.
        binding.consentAgreeAllRow.contentDescription =
            getString(R.string.consent_agree_all) + ". " + getString(R.string.consent_agree_all_note)
        binding.consentAgreeAllRow.setOnClickListener {
            val keys = visibleKeys()
            val allChecked = checkedKeys.size == keys.size
            checkedKeys.clear()
            if (!allChecked) checkedKeys.addAll(keys)
            keys.forEach { setItemChecked(itemBindingFor(it), it in checkedKeys) }
            updateAgreeAllCheckbox()
            updateNextButtonAppearance()
        }
    }

    private fun updateAgreeAllCheckbox() {
        val allChecked = checkedKeys.size == visibleKeys().size
        binding.consentAgreeAllCheckbox.setImageResource(
            if (allChecked) R.drawable.ic_davi_consent_check_checked else R.drawable.ic_davi_consent_check_unchecked,
        )
        ViewCompat.setStateDescription(
            binding.consentAgreeAllRow,
            getString(if (allChecked) R.string.consent_state_checked else R.string.consent_state_unchecked),
        )
    }

    private fun bindFooter() {
        bindPrivacyPolicyLink()

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

        // **뒤로가기도 확인창을 거친다**(PR #409 리뷰 P2). 확인창을 둔 근거가 「한 번 나가면
        // 앱을 아예 쓸 수 없어 되돌릴 길도 없다」인데, LoginActivity가 finish()하고 넘겨
        // 백스택에 이 화면뿐이라 **뒤로가기 한 번이면 앱이 그대로 닫혔다** — 가장 흔한 이탈
        // 경로에만 그 근거가 적용되지 않고 있었다. 제스처 내비에서 가장자리를 스치는 실수는
        // 시니어에게 흔하다. 확인창이 떠 있을 때만 닫고, 아니면 띄운다.
        onBackPressedDispatcher.addCallback(this) {
            binding.consentDeclineScrim.isVisible = !binding.consentDeclineScrim.isVisible
        }
    }

    /**
     * 처리방침 전문 링크. **주소가 비어 있으면 감춘다** — 게시 시점이 이 화면을 막지 않는다.
     *
     * **브라우저로 연다**(2026-08-24 결정). `strings.xml`이 원래 「앱 안에서 연다」였고 그
     * 근거(「브라우저로 튕기면 시니어가 돌아오지 못한다」)도 옳지만, 이 앱에 WebView를 쓰는
     * 자리가 하나도 없어 화면을 새로 세워야 하는데 주소조차 아직 없다 — 되돌리려면 그쪽
     * 주석부터 읽을 것.
     *
     * **주소가 비어 있어도 리스너는 지금 단다**: 값만 채우면 되는 것처럼 보이는 자리라,
     * 나중에 `consent_policy_url`만 채우고 여기를 안 보면 **눌러도 아무 일 없는 링크가
     * 나간다**(PR #409 리뷰가 그것을 예측했다).
     */
    private fun bindPrivacyPolicyLink() {
        val url = getString(R.string.consent_policy_url)
        binding.consentPrivacyPolicyLink.isVisible = url.isNotEmpty()
        if (url.isEmpty()) return
        binding.consentPrivacyPolicyLink.setOnClickListener {
            val opened = runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
            }.isSuccess
            // 브라우저가 없는 기기. 조용히 지나가되 왜 안 열렸는지는 남긴다 — 주소는 공개값이라
            // 로그에 담아도 되지만 굳이 적지 않는다(루트 CLAUDE.md의 로그 최소화).
            if (!opened) Log.i(TAG, "privacy policy link: no handler")
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

    private companion object {
        const val TAG = "GuideSvc"
        const val STATE_CHECKED = "consent_checked_keys"
    }
}
