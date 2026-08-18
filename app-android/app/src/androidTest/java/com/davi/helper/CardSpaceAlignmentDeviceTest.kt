package com.davi.helper

import android.content.Intent
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 카드 크기 배분과 넘침 처리의 실기기 고정(#207).
 *
 * **화면 크기가 결과를 정하므로 JVM 테스트로 대신할 수 없다** — 남는 공간을 카드가
 * 어떻게 나눠 갖는지가 이 이슈의 주제이고, 그 값은 실제 가용 높이에서만 나온다.
 * 상태는 서버 없이 setState로 직접 태운다(AppAvailabilityPreflightDeviceTest와 같은 방식).
 */
class CardSpaceAlignmentDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var activity: ConversationActivity? = null

    @After
    fun tearDown() {
        activity?.let { instrumentation.runOnMainSync { it.finish() } }
        activity = null
    }

    @Test
    fun 말하는쪽카드가남는공간을먹는다() {
        val a = launch()

        show(a, ConversationUiState.Listening(question = "누구에게 보내시겠어요?", partial = "손자에게"))
        val 듣는중다비 = a.findViewById<android.view.View>(R.id.card_question_wrap).height
        val 듣는중시니어 = a.findViewById<android.view.View>(R.id.card_utterance_wrap).height

        show(a, ConversationUiState.Speaking("누구에게 보내시겠어요?", generation = 1L))
        val 발화다비 = a.findViewById<android.view.View>(R.id.card_question_wrap).height
        val 발화시니어 = a.findViewById<android.view.View>(R.id.card_utterance_wrap).height

        val d = a.resources.displayMetrics.density
        fun dp(px: Int) = (px / d).toInt()
        println(
            "[#207] 듣는 중  다비=${dp(듣는중다비)}dp 시니어=${dp(듣는중시니어)}dp / " +
                "다비 발화  다비=${dp(발화다비)}dp 시니어=${dp(발화시니어)}dp",
        )

        assertTrue("듣는 중에는 시니어가 더 커야 한다", 듣는중시니어 > 듣는중다비)
        assertTrue("다비 발화에는 다비가 더 커야 한다", 발화다비 > 발화시니어)
        assertTrue("다비 카드가 발화 때 커져야 한다", 발화다비 > 듣는중다비)
    }

    @Test
    fun 배지가카드를파고들되문구를덮지않는다() {
        val a = launch()
        show(a, ConversationUiState.Speaking("누구에게 보내시겠어요?", generation = 1L))

        val logo = a.findViewById<android.view.View>(R.id.logo)
        val card = a.findViewById<android.view.View>(R.id.card_question_container)
        val text = a.findViewById<TextView>(R.id.card_question)

        val logoBottom = IntArray(2).also { logo.getLocationOnScreen(it) }[1] + logo.height
        val textTop = IntArray(2).also { text.getLocationOnScreen(it) }[1]
        val cardTop = IntArray(2).also { card.getLocationOnScreen(it) }[1]

        val d = a.resources.displayMetrics.density
        println("[#207] 배지하단=${(logoBottom / d).toInt()}dp 카드상단=${(cardTop / d).toInt()}dp 문구상단=${(textTop / d).toInt()}dp")

        assertTrue("배지가 카드 상단을 파고들어야 한다(시안)", logoBottom > cardTop)
        assertTrue("문구가 배지 아래에서 시작해야 한다", textTop >= logoBottom)
    }

    @Test
    fun 아주긴문구는잘리지않고스크롤로닿는다() {
        val a = launch()
        val 긴문구 = (1..40).joinToString(" ") { "카카오톡으로 손자에게 사진을 보내는 것이 맞나요" }
        show(a, ConversationUiState.Confirm(긴문구))

        val text = a.findViewById<TextView>(R.id.card_question)
        val 글전체 = text.layout?.height ?: 0
        val 보이는높이 = text.height - text.paddingTop - text.paddingBottom

        println("[#207] 긴 문구  글 전체=$글전체 px, 보이는 높이=$보이는높이 px")

        assertTrue("이 문구는 카드를 넘겨야 검증이 성립한다", 글전체 > 보이는높이)
        assertTrue(
            "넘친 글자에 스크롤로 닿을 수 있어야 한다 — 잘리면 못 읽고 승인하게 된다",
            text.movementMethod != null && text.canScrollVertically(1),
        )
    }

    @Test
    fun 흔한길이질문은스크롤없이들어온다() {
        val a = launch()
        val d = a.resources.displayMetrics.density
        var 마지막통과 = 0
        for (글자수 in intArrayOf(20, 30, 40, 50, 60, 80, 100)) {
            show(a, ConversationUiState.Speaking("가".repeat(글자수), generation = 1L))
            val t = a.findViewById<TextView>(R.id.card_question)
            val 넘침 = (t.layout?.height ?: 0) > t.height - t.paddingTop - t.paddingBottom
            val h = (a.findViewById<android.view.View>(R.id.card_question_wrap).height / d).toInt()
            println("[#207] ${글자수}자 → 카드 ${h}dp, 넘침=$넘침")
            if (!넘침) 마지막통과 = 글자수
        }
        println("[#207] 스크롤 없이 들어오는 최대 글자수 = $마지막통과")
        assertTrue("흔한 길이(40자)는 스크롤 없이 들어와야 한다", 마지막통과 >= 40)
    }

    private fun launch(): ConversationActivity {
        val intent = Intent(instrumentation.targetContext, ConversationActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return (instrumentation.startActivitySync(intent) as ConversationActivity).also {
            activity = it
            awaitLayout(it)
        }
    }

    private fun show(a: ConversationActivity, state: ConversationUiState) {
        instrumentation.runOnMainSync {
            ConversationActivity::class.java
                .getDeclaredMethod("setState", ConversationUiState::class.java)
                .apply { isAccessible = true }
                .invoke(a, state)
        }
        awaitLayout(a)
    }

    /**
     * **측정 전에 실제 레이아웃을 기다린다.** `startActivitySync`·`waitForIdleSync`만으로는
     * 첫 배치가 끝났다는 보장이 없어 높이가 전부 0으로 나온다(실측). 카드 상한도 `post`로
     * 잡히므로 프레임이 한 번 더 돌아야 한다.
     */
    private fun awaitLayout(a: ConversationActivity) {
        val 마감 = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < 마감) {
            instrumentation.waitForIdleSync()
            var 준비됨 = false
            instrumentation.runOnMainSync {
                준비됨 = a.findViewById<android.view.View>(R.id.card_column).height > 0
            }
            if (준비됨) {
                // 상한을 잡는 post가 한 번 더 돌 기회를 준다.
                instrumentation.runOnMainSync { }
                instrumentation.waitForIdleSync()
                return
            }
            Thread.sleep(50)
        }
        throw AssertionError("5초 안에 레이아웃이 끝나지 않았다 — 화면이 꺼져 있거나 잠겨 있는지 확인")
    }
}
