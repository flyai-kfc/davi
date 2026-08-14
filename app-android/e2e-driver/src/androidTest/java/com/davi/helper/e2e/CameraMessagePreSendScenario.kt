package com.davi.helper.e2e

import androidx.test.uiautomator.UiDevice

/** Davi에서 시작해 새 사진을 메시지에 첨부하되 실제 전송은 막는 실제 Gemini 시나리오. */
internal class CameraMessagePreSendScenario : GuidedScenario<CameraMessagePreSendScenario.State> {
    data class State(
        val recipient: String,
        val packages: CameraMessageRobot.Packages,
        val imageIdsBefore: Set<Long>,
        var pendingTarget: CameraMessageRobot.TargetKind = CameraMessageRobot.TargetKind.OTHER,
        var newMessageStarted: Boolean = false,
        var recipientEntered: Boolean = false,
        var captureExecuted: Boolean = false,
    )

    override val id: String = "camera-message-presend-full-journey"
    override val start: GuidedScenarioStart = GuidedScenarioStart.DAVI_TO_ANDROID_HOME
    override val maxSteps: Int = 32

    private lateinit var robot: CameraMessageRobot

    override fun prepare(device: UiDevice): State {
        robot = CameraMessageRobot(device)
        val packages = robot.resolvePackages()
        robot.stopMessageTask(packages.messages)
        return State(
            recipient = LiveGeminiTestEnvironment.requireSmsRecipient(),
            packages = packages,
            imageIdsBefore = robot.imageIds(),
        )
    }

    override fun goal(state: State): String =
        "메시지 앱에서 새 메시지를 만들고 테스트 수신자를 지정한 뒤 카메라로 사진 한 장을 " +
            "촬영해 첨부해 주세요. 실제로 보내지는 말고 전송 버튼을 누르기 직전 상태에서 완료해 주세요."

    override fun validateBeforeExecute(
        device: UiDevice,
        state: State,
        step: Int,
        decision: DisplayedDecision,
    ) {
        val packageName = decision.screenPackage
        val kind = robot.targetKind(
            packageName,
            decision.targetBounds,
            decision.requestTargetLabel(),
            state.recipient,
        )
        state.pendingTarget = kind

        when {
            packageName == state.packages.launcher -> validateLauncher(step, decision, kind)
            packageName == state.packages.messages &&
                !robot.isCameraSurface(packageName, state.packages.camera) ->
                validateMessages(state, step, decision, kind)
            packageName == state.packages.camera ||
                robot.isCameraSurface(packageName, state.packages.camera) ->
                validateCamera(state, step, decision, kind)
            else -> throw GuidedScenarioFailure("unexpected_package", step)
        }
    }

    override fun textFor(
        state: State,
        step: Int,
        decision: DisplayedDecision,
    ): String? {
        if (decision.action.actionType != GuidedActionType.TYPE) return null
        return when (state.pendingTarget) {
            CameraMessageRobot.TargetKind.SEARCH -> MESSAGE_SEARCH_TEXT
            CameraMessageRobot.TargetKind.RECIPIENT -> state.recipient
            else -> throw GuidedScenarioFailure("unsafe_message_type", step)
        }
    }

    override fun afterExecute(
        device: UiDevice,
        state: State,
        step: Int,
        decision: DisplayedDecision,
    ) {
        when (state.pendingTarget) {
            CameraMessageRobot.TargetKind.NEW_MESSAGE -> state.newMessageStarted = true
            CameraMessageRobot.TargetKind.RECIPIENT -> {
                if (decision.action.actionType == GuidedActionType.TYPE) state.recipientEntered = true
            }
            CameraMessageRobot.TargetKind.SHUTTER -> state.captureExecuted = true
            else -> Unit
        }
    }

    override fun isGoalSatisfied(device: UiDevice, state: State): Boolean = robot.isPreSendReady(state)

    override fun cleanup(device: UiDevice, state: State?) {
        if (state != null) {
            robot.discardActiveDraft(state)
            robot.deleteCreatedImages(state.imageIdsBefore)
        }
    }

    private fun validateLauncher(
        step: Int,
        decision: DisplayedDecision,
        kind: CameraMessageRobot.TargetKind,
    ) {
        val safe = when (decision.action.actionType) {
            GuidedActionType.OPEN_APP_DRAWER -> true
            GuidedActionType.SCROLL -> decision.action.direction != null
            GuidedActionType.TAP -> decision.targetClickable && kind in setOf(
                CameraMessageRobot.TargetKind.APP_DRAWER,
                CameraMessageRobot.TargetKind.SEARCH,
                CameraMessageRobot.TargetKind.MESSAGE_APP,
            )
            GuidedActionType.TYPE -> decision.targetEditable && kind == CameraMessageRobot.TargetKind.SEARCH
            else -> false
        }
        if (!safe) throw GuidedScenarioFailure("unsafe_launcher_target", step)
    }

    private fun validateMessages(
        state: State,
        step: Int,
        decision: DisplayedDecision,
        kind: CameraMessageRobot.TargetKind,
    ) {
        // pre-send는 성공 상태에서도 전송 버튼을 절대 실행하지 않는 하드 경계다.
        if (kind == CameraMessageRobot.TargetKind.SEND) {
            throw GuidedScenarioFailure("presend_send_blocked", step)
        }
        if (robot.isPreSendReady(state)) throw GuidedScenarioFailure("action_after_goal", step)

        val safe = when (decision.action.actionType) {
            GuidedActionType.TAP -> decision.targetClickable && when (kind) {
                // Samsung Messages는 새 메시지 → 1:1 대화의 두 단계 작성 메뉴를 쓴다.
                CameraMessageRobot.TargetKind.NEW_MESSAGE ->
                    !state.recipientEntered && !state.captureExecuted
                CameraMessageRobot.TargetKind.RECIPIENT -> state.newMessageStarted
                CameraMessageRobot.TargetKind.CONFIGURED_RECIPIENT -> state.recipientEntered
                CameraMessageRobot.TargetKind.ATTACHMENT,
                CameraMessageRobot.TargetKind.CAMERA,
                -> state.newMessageStarted &&
                    (state.recipientEntered || robot.recipientVisible(state.packages.messages, state.recipient))
                else -> false
            }
            GuidedActionType.TYPE -> decision.targetEditable &&
                kind == CameraMessageRobot.TargetKind.RECIPIENT && state.newMessageStarted
            GuidedActionType.SCROLL -> state.newMessageStarted && decision.action.direction != null
            else -> false
        }
        if (!safe) throw GuidedScenarioFailure("unsafe_message_target", step)
    }

    private fun validateCamera(
        state: State,
        step: Int,
        decision: DisplayedDecision,
        kind: CameraMessageRobot.TargetKind,
    ) {
        val safe = decision.action.actionType == GuidedActionType.TAP &&
            decision.targetClickable && when (kind) {
                CameraMessageRobot.TargetKind.SHUTTER -> !state.captureExecuted
                CameraMessageRobot.TargetKind.USE_PHOTO -> state.captureExecuted
                else -> false
            }
        if (!safe) throw GuidedScenarioFailure("unsafe_camera_target", step)
    }

    private companion object {
        const val MESSAGE_SEARCH_TEXT = "메시지"
    }
}
