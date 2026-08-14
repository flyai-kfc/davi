package com.davi.helper.e2e

import androidx.test.uiautomator.UiDevice

/** 안내를 어느 사용자 여정 지점에서 시작할지 정한다. */
enum class GuidedScenarioStart {
    /** 준비된 외부 앱 화면에서 안내 루프만 빠르게 검증한다. */
    PREPARED_SCREEN,

    /** Davi 최신 화면을 거쳐 안내 세션을 시작하고 Android 홈에서 전체 여정을 시작한다. */
    DAVI_TO_ANDROID_HOME,
}

/**
 * Davi에서 시작해 하나 이상의 외부 앱을 오갈 수 있는 안내 E2E 시나리오.
 *
 * 앱별 selector는 준비·최종 검증·정리에만 둔다. 본 흐름의 조작 대상은 항상 Gemini 결정의
 * `target_node_id -> bounds`를 사용한다. 전용 테스트 기기에서는 예상 경로 이탈을 관측하면서
 * 계속 실행하고, 설정 앱이나 기기 밖 부작용처럼 복구 범위를 벗어난 결정만 [assessBeforeExecute]에서
 * 하드 차단한다.
 */
interface GuidedScenario<State> {
    val id: String
    val start: GuidedScenarioStart
        get() = GuidedScenarioStart.PREPARED_SCREEN
    val maxSteps: Int
        get() = 20
    val decisionTimeoutMs: Long
        get() = 45_000L

    /** 기존 테스트 부산물을 정리하고 목표를 만들기 위한 안전한 초기 상태를 수집한다. */
    fun prepare(device: UiDevice): State

    fun goal(state: State): String

    /** Gemini 결정을 실제로 실행하기 직전의 비종료 이탈과 하드 경계를 분류한다. */
    fun assessBeforeExecute(
        device: UiDevice,
        state: State,
        step: Int,
        decision: DisplayedDecision,
    ): GuidedDecisionAssessment

    /** TYPE 액션에서 사용자가 입력할 시나리오 값. 모델 문장을 파싱해 값을 지어내지 않는다. */
    fun textFor(
        state: State,
        step: Int,
        decision: DisplayedDecision,
    ): String? = null

    /** 완료 선언을 믿기 전에 다른 앱의 실제 UI에서 목표 상태를 독립 확인한다. */
    fun isGoalSatisfied(device: UiDevice, state: State): Boolean

    /** 성공·실패와 무관하게 이 시나리오가 만든 데이터만 제거한다. */
    fun cleanup(device: UiDevice, state: State?)
}

/** 전용 테스트 기기에서 계속 관찰할 이탈과 실행하면 안 되는 경계를 분리한다. */
data class GuidedDecisionAssessment(
    val deviationCodes: List<String> = emptyList(),
    val hardFailureCode: String? = null,
)

/** 모델의 완료 선언도 최종 상태·검증이 부족하면 남은 단계에서 다시 안내받는다. */
internal object GuidedCompletionPolicy {
    fun deviations(goalSatisfied: Boolean, verified: Boolean): List<String> = buildList {
        if (!goalSatisfied) add("done_before_goal")
        if (!verified) add("done_unverified")
    }
}

class GuidedScenarioFailure(
    val code: String,
    val step: Int? = null,
) : AssertionError(
    buildString {
        append("guided scenario failed code=").append(code)
        if (step != null) append(" step=").append(step)
        append(" (화면/안내 원문 생략)")
    },
)
