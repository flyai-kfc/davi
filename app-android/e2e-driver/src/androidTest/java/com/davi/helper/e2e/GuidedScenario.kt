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

    /** 공통 설정 경계 다음으로 실제 손동작 전에 평가할 시나리오 전용 안전 경계. */
    val hardBoundaries: List<GuidedHardBoundary>
        get() = emptyList()

    /**
     * 이 시나리오가 화면에 개인정보를 띄우는가(#134).
     *
     * `true`면 실행기가 **상세 trace를 강제로 끈다.** 예를 들어 문자 수신자 번호는 입력한 순간부터
     * 이후 모든 화면 snapshot의 label에 들어가고, 상세 보고서는 그 label을 그대로 담는다.
     * 시나리오 코드가 번호를 로그에 안 찍는 것만으로는 막을 수 없는 경로다.
     *
     * 인자(`davi.detailedReport`)로 켤 수 없다 — 끄는 방향으로만 작동한다.
     */
    val handlesPersonalData: Boolean
        get() = false

    /** 기존 테스트 부산물을 정리하고 목표를 만들기 위한 안전한 초기 상태를 수집한다. */
    fun prepare(device: UiDevice): State

    fun goal(state: State): String

    /** Gemini 결정을 실제로 실행하기 직전의 비종료 이탈을 분류한다. */
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
)

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
