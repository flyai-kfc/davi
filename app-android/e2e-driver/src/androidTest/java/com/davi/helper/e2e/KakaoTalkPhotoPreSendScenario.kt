package com.davi.helper.e2e

import androidx.test.uiautomator.UiDevice

/**
 * 카카오톡 나와의 채팅방에서 사진 한 장을 골라 **전송 직전 상태**까지 가는 실제 Gemini 시나리오.
 *
 * 전송은 성공 상태에서도 절대 실행하지 않는 하드 경계다 — label(부분 문자열)과 **누를 좌표**를
 * 함께 본다(#134). 대화 상대가 없는 나와의 채팅방을 쓰는 이유는 제3자 데이터 노출과 잘못된
 * 수신자 위험을 함께 줄이기 위해서다.
 */
internal class KakaoTalkPhotoPreSendScenario : GuidedScenario<KakaoTalkPhotoPreSendScenario.State> {
    data class State(
        val launcherPackage: String,
        var attachSurfaceSeen: Boolean = false,
    )

    override val id: String = "kakaotalk-photo-presend-full-journey"
    override val start: GuidedScenarioStart = GuidedScenarioStart.DAVI_TO_ANDROID_HOME
    override val maxSteps: Int = 30

    /** 채팅 목록·대화 내용이 화면 label로 서버에 간다. 상세 trace를 강제로 끈다(#134). */
    override val handlesPersonalData: Boolean = true

    private lateinit var robot: KakaoTalkRobot
    private var boundaryState: State? = null

    override val hardBoundaries: List<GuidedHardBoundary>
        get() = listOf(
            GuardedControlGuard.tapBoundary(
                codePrefix = "kakao_send",
                packages = setOf(KakaoTalkRobot.PACKAGE),
                labelFragments = KakaoTalkRobot.SEND_FRAGMENTS,
                // 사진 첨부 화면에 들어온 뒤부터는 전송 컨트롤이 식별돼야 한다.
                controlRequired = { boundaryState?.attachSurfaceSeen == true },
            ),
        )

    override fun prepare(device: UiDevice): State {
        robot = KakaoTalkRobot(device)
        robot.forceStop()
        val launcherPackage = device.launcherPackageName
            ?: throw GuidedScenarioFailure("launcher_package_missing")
        return State(launcherPackage).also { boundaryState = it }
    }

    override fun goal(state: State): String =
        "카카오톡에서 나와의 채팅방을 열고 앨범에서 사진 한 장을 선택해 주세요. " +
            "실제로 보내지는 말고, 전송 버튼을 누르기 직전 상태에서 완료해 주세요."

    override fun assessBeforeExecute(
        device: UiDevice,
        state: State,
        step: Int,
        decision: DisplayedDecision,
    ): GuidedDecisionAssessment {
        if (decision.screenPackage == state.launcherPackage) {
            return GuidedLauncherPolicy.assess(decision, LAUNCHER_APP_FRAGMENTS)
        }
        if (decision.screenPackage != KakaoTalkRobot.PACKAGE) {
            return GuidedDecisionAssessment(deviationCodes = listOf("unexpected_package"))
        }
        if (GuardedControlSafety.containsBlockedLabel(decision.requestTargetLabel(), ATTACH_FRAGMENTS)) {
            state.attachSurfaceSeen = true
        }
        return GuidedDecisionAssessment()
    }

    override fun textFor(
        state: State,
        step: Int,
        decision: DisplayedDecision,
    ): String? {
        if (decision.action.actionType != GuidedActionType.TYPE) return null
        return if (decision.screenPackage == state.launcherPackage) {
            LAUNCHER_SEARCH_TEXT
        } else {
            // 카카오톡 안의 유일한 입력 용도는 나와의 채팅 검색이다. 대화 입력칸에 들어가도
            // 전송 경계가 발송을 막으므로 기기 밖으로 나가지 않는다.
            CHAT_SEARCH_TEXT
        }
    }

    override fun isGoalSatisfied(device: UiDevice, state: State): Boolean =
        state.attachSurfaceSeen && robot.isPreSendReady()

    override fun cleanup(device: UiDevice, state: State?) {
        // 전송 전이라 만든 데이터가 없다. 앱을 내려 사진 선택·입력 상태만 버린다.
        val cleanupRobot = if (::robot.isInitialized) robot else KakaoTalkRobot(device)
        cleanupRobot.forceStop()
        boundaryState = null
    }

    private companion object {
        const val LAUNCHER_SEARCH_TEXT = "카카오톡"
        const val CHAT_SEARCH_TEXT = "나와의 채팅"
        val LAUNCHER_APP_FRAGMENTS = setOf("카카오톡", "kakaotalk")

        /** 사진 첨부 진입으로 보는 대상 label 조각 — + 첨부, 앨범, 사진, 전체보기. */
        val ATTACH_FRAGMENTS = setOf("앨범", "사진", "첨부", "전체보기", "더보기")
    }
}
