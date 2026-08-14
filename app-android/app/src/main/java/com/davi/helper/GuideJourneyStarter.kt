package com.davi.helper

import android.content.Context
import android.content.Intent

/** 목표를 활성 세션으로 만들고 Android 홈에서 안내를 시작하는 제품 공용 경계. */
internal object GuideJourneyStarter {
    fun start(
        context: Context,
        goal: String,
        afterSessionStarted: () -> Unit = {},
    ) {
        val normalizedGoal = goal.trim()
        if (normalizedGoal.isEmpty()) return
        GuideSession.start(normalizedGoal)
        afterSessionStarted()
        context.startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
