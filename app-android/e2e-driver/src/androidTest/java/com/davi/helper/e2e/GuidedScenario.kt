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
 * `target_node_id -> bounds`를 사용한다. 앱별 구현은 잘못된 결정이 개인 데이터에 영향을 주지
 * 않도록 [validateBeforeExecute]에서 안전한 대상만 허용한다.
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

    /** Gemini 결정을 실제로 실행하기 직전의 시나리오별 안전·의미 검사. */
    fun validateBeforeExecute(
        device: UiDevice,
        state: State,
        step: Int,
        decision: DisplayedDecision,
    )

    /** TYPE 액션에서 사용자가 입력할 시나리오 값. 모델 문장을 파싱해 값을 지어내지 않는다. */
    fun textFor(
        state: State,
        step: Int,
        decision: DisplayedDecision,
    ): String? = null

    /** 실제 사용자 동작 주입이 성공한 뒤 시나리오 상태를 갱신한다. */
    fun afterExecute(
        device: UiDevice,
        state: State,
        step: Int,
        decision: DisplayedDecision,
    ) = Unit

    /** 완료 선언을 믿기 전에 다른 앱의 실제 UI에서 목표 상태를 독립 확인한다. */
    fun isGoalSatisfied(device: UiDevice, state: State): Boolean

    /** 성공·실패와 무관하게 이 시나리오가 만든 데이터만 제거한다. */
    fun cleanup(device: UiDevice, state: State?)
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
