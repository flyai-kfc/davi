package com.davi.helper.e2e

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.Telephony
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import java.util.Locale

/** 카메라→메시지 pre-send 시나리오의 package 식별·독립 검증·정리만 담당한다. */
internal class CameraMessageRobot(private val device: UiDevice) {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    data class Packages(
        val launcher: String,
        val camera: String?,
        val messages: String,
    )

    enum class TargetKind {
        APP_DRAWER,
        SEARCH,
        MESSAGE_APP,
        NEW_MESSAGE,
        RECIPIENT,
        CONFIGURED_RECIPIENT,
        ATTACHMENT,
        CAMERA,
        SHUTTER,
        USE_PHOTO,
        SEND,
        OTHER,
    }

    fun resolvePackages(): Packages {
        val launcher = device.launcherPackageName
            ?: throw GuidedScenarioFailure("launcher_package_missing")
        val messages = Telephony.Sms.getDefaultSmsPackage(context)
            ?.takeIf { it.isNotBlank() }
            ?: throw GuidedScenarioFailure("message_app_not_resolved")
        val camera = context.packageManager.resolveActivity(
            Intent(MediaStore.ACTION_IMAGE_CAPTURE),
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName
        return Packages(launcher, camera, messages)
    }

    /** 이전 작업 화면을 복원하지 않도록 task만 종료한다. 앱 데이터나 기존 대화는 지우지 않는다. */
    fun stopMessageTask(packageName: String) {
        shell("am force-stop $packageName")
    }

    /** 경로나 이름을 읽지 않고 MediaStore image ID만 기준선으로 보존한다. */
    fun imageIds(): Set<Long> {
        val output = shell(
            "content query --uri content://media/external/images/media --projection _id",
        )
        if (output.contains("Error", ignoreCase = true)) {
            throw GuidedScenarioFailure("media_store_query_failed")
        }
        return MEDIA_ID.findAll(output).mapNotNull { it.groupValues[1].toLongOrNull() }.toSet()
    }

    fun isCameraSurface(packageName: String, cameraPackage: String?): Boolean =
        packageName == cameraPackage || visibleNodes(packageName).any { node ->
            classify(node, null) == TargetKind.SHUTTER
        }

    fun targetKind(
        packageName: String,
        boundsValues: IntArray?,
        requestLabel: String?,
        recipient: String,
    ): TargetKind {
        val recipientDigits = digits(recipient)
        val nodes = nodesAt(packageName, boundsValues)
        nodes.forEach { node ->
            classify(node, recipientDigits)?.let { return it }
        }
        return classifyText(requestLabel.orEmpty(), recipientDigits)
    }

    fun recipientVisible(packageName: String, recipient: String): Boolean {
        val expected = digits(recipient)
        if (expected.isEmpty()) return false
        return visibleNodes(packageName).any { node ->
            nodeStrings(node).any { digits(it) == expected }
        }
    }

    fun sendButtonEnabled(packageName: String): Boolean = visibleNodes(packageName).any { node ->
        classify(node, null) == TargetKind.SEND && node.isEnabled
    }

    fun isPreSendReady(state: CameraMessagePreSendScenario.State): Boolean {
        val currentPackage = device.currentPackageName.orEmpty()
        if (currentPackage != state.packages.messages ||
            isCameraSurface(currentPackage, state.packages.camera)
        ) {
            return false
        }
        return state.newMessageStarted &&
            state.captureExecuted &&
            recipientVisible(currentPackage, state.recipient) &&
            sendButtonEnabled(currentPackage)
    }

    /** 활성 작성 화면만 닫고 명시적인 폐기 확인 버튼만 누른다. 기존 대화 목록은 건드리지 않는다. */
    fun discardActiveDraft(state: CameraMessagePreSendScenario.State) {
        repeat(MAX_BACK_ATTEMPTS) {
            val currentPackage = device.currentPackageName.orEmpty()
            if (currentPackage != state.packages.messages && currentPackage != state.packages.camera) return
            device.pressBack()
            device.waitForIdle(IDLE_TIMEOUT_MS)
            discardButton()?.let { button ->
                button.click()
                device.waitForIdle(IDLE_TIMEOUT_MS)
            }
        }
        discardButton()?.let {
            it.click()
            device.waitForIdle(IDLE_TIMEOUT_MS)
        }
        stopMessageTask(state.packages.messages)
    }

    /** 기준선 뒤 생긴 소수의 image ID만 정확히 삭제한다. 범위가 커지면 아무것도 지우지 않는다. */
    fun deleteCreatedImages(before: Set<Long>) {
        val created = imageIds() - before
        if (created.size > MAX_CREATED_IMAGES) {
            throw GuidedScenarioFailure("cleanup_media_scope_ambiguous")
        }
        created.forEach { id ->
            shell("content delete --uri content://media/external/images/media/$id")
        }
        if ((imageIds() - before).isNotEmpty()) {
            throw GuidedScenarioFailure("cleanup_photo_delete_failed")
        }
    }

    private fun discardButton(): UiObject2? = device.findObjects(By.clickable(true)).firstOrNull { node ->
        val normalized = nodeStrings(node).joinToString(" ").trim().lowercase(Locale.ROOT)
        normalized in DISCARD_WORDS
    }

    private fun nodesAt(packageName: String, boundsValues: IntArray?): List<UiObject2> {
        if (boundsValues == null || boundsValues.size != 4) return emptyList()
        val bounds = Rect(boundsValues[0], boundsValues[1], boundsValues[2], boundsValues[3])
        val x = bounds.centerX()
        val y = bounds.centerY()
        return visibleNodes(packageName)
            .filter { it.visibleBounds.contains(x, y) }
            .sortedBy { it.visibleBounds.width().toLong() * it.visibleBounds.height().toLong() }
    }

    private fun visibleNodes(packageName: String): List<UiObject2> =
        device.findObjects(By.pkg(packageName)).filter { it.isEnabled && it.visibleBounds.width() > 0 }

    private fun classify(node: UiObject2, recipientDigits: String?): TargetKind? {
        val resource = node.resourceName.orEmpty().lowercase(Locale.ROOT)
        val text = nodeStrings(node).joinToString(" ").lowercase(Locale.ROOT)
        val combined = "$resource $text"
        if (recipientDigits != null && nodeStrings(node).any { digits(it) == recipientDigits }) {
            return TargetKind.CONFIGURED_RECIPIENT
        }
        return when {
            SEND_WORDS.any(combined::contains) -> TargetKind.SEND
            SHUTTER_WORDS.any(combined::contains) -> TargetKind.SHUTTER
            USE_PHOTO_WORDS.any(combined::contains) -> TargetKind.USE_PHOTO
            NEW_MESSAGE_WORDS.any(combined::contains) -> TargetKind.NEW_MESSAGE
            node.className.endsWith("EditText") && RECIPIENT_WORDS.any(combined::contains) ->
                TargetKind.RECIPIENT
            CAMERA_WORDS.any(combined::contains) -> TargetKind.CAMERA
            ATTACHMENT_WORDS.any(combined::contains) -> TargetKind.ATTACHMENT
            SEARCH_WORDS.any(combined::contains) -> TargetKind.SEARCH
            APP_DRAWER_WORDS.any(combined::contains) -> TargetKind.APP_DRAWER
            MESSAGE_WORDS.any(combined::contains) -> TargetKind.MESSAGE_APP
            else -> null
        }
    }

    private fun classifyText(raw: String, recipientDigits: String?): TargetKind {
        val normalized = raw.trim().lowercase(Locale.ROOT)
        if (recipientDigits != null && digits(raw) == recipientDigits) {
            return TargetKind.CONFIGURED_RECIPIENT
        }
        return when {
            SEND_WORDS.any(normalized::contains) -> TargetKind.SEND
            SHUTTER_WORDS.any(normalized::contains) -> TargetKind.SHUTTER
            USE_PHOTO_WORDS.any(normalized::contains) -> TargetKind.USE_PHOTO
            NEW_MESSAGE_WORDS.any(normalized::contains) -> TargetKind.NEW_MESSAGE
            RECIPIENT_WORDS.any(normalized::contains) -> TargetKind.RECIPIENT
            CAMERA_WORDS.any(normalized::contains) -> TargetKind.CAMERA
            ATTACHMENT_WORDS.any(normalized::contains) -> TargetKind.ATTACHMENT
            SEARCH_WORDS.any(normalized::contains) -> TargetKind.SEARCH
            APP_DRAWER_WORDS.any(normalized::contains) -> TargetKind.APP_DRAWER
            MESSAGE_WORDS.any(normalized::contains) -> TargetKind.MESSAGE_APP
            else -> TargetKind.OTHER
        }
    }

    private fun nodeStrings(node: UiObject2): List<String> = listOfNotNull(
        node.text?.takeIf { it.isNotBlank() },
        node.contentDescription?.takeIf { it.isNotBlank() },
    )

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
    ).bufferedReader().use { it.readText() }

    private fun digits(value: String): String = value.filter(Char::isDigit)

    private companion object {
        val SEND_WORDS = listOf("send_button", "button_send", "보내기", "전송", " send")
        val SHUTTER_WORDS = listOf("shutter", "capture_button", "촬영", "셔터")
        val USE_PHOTO_WORDS = listOf("use_photo", "done_button", "사진 사용", "사용", "완료", "확인")
        val NEW_MESSAGE_WORDS = listOf("new_message", "compose", "새 메시지", "새 대화", "메시지 작성")
        val RECIPIENT_WORDS = listOf("recipient", "recipients_editor", "받는 사람", "수신자", "전화번호")
        val CAMERA_WORDS = listOf("camera", "카메라", "사진 촬영")
        val ATTACHMENT_WORDS = listOf(
            "attach",
            "attachment",
            "add_attachment",
            "content_picker",
            "plus_button",
            "첨부",
        )
        val SEARCH_WORDS = listOf("search", "검색")
        val APP_DRAWER_WORDS = listOf("app_drawer", "apps", "앱 서랍", "앱스")
        val MESSAGE_WORDS = listOf("message", "메시지", "문자")
        val DISCARD_WORDS = setOf("삭제", "버리기", "discard", "임시 메시지 삭제")
        val MEDIA_ID = Regex("(?:^|[, ])_id=(\\d+)")
        const val MAX_CREATED_IMAGES = 3
        const val MAX_BACK_ATTEMPTS = 4
        const val IDLE_TIMEOUT_MS = 1_000L
    }
}
