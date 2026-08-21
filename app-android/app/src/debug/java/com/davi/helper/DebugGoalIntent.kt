package com.davi.helper

import android.content.Intent
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64

/** 인텐트로 들어온 목표. [autoStart]면 화면이 입력칸을 채운 뒤 안내 시작까지 간다. */
data class DebugGoalRequest(val goal: String, val autoStart: Boolean)

/**
 * 개발용 백업 화면에 **목표를 인텐트로 넣는 debug 전용 경로**(#323).
 *
 * 실기기 검증에서 안내를 시작하려면 목표를 텍스트로 넣어야 하는데 **adb로 한글을 넣을 방법이
 * 없다**. `input text`가 한글을 못 넣는 것은 버전이 아니라 구조이고(AOSP `InputShellCommand`가
 * `KeyCharacterMap`으로 변환 — 가상 키보드 kcm에 없는 글자는 이벤트가 안 난다),
 * `cmd clipboard`는 AOSP에 아예 없다. 그래서 자판 자모 좌표를 16번 탭해 왔고, 그 좌표표는
 * 자판 높이·모드·숫자열·자판 종류·회전에 통째로 종속이다.
 *
 * **딥링크가 아니라 인텐트 extra인 이유**: 이 화면은 이미 `exported=true`라 명시적 component로
 * 열면서 extra를 붙이면 **매니페스트를 건드릴 필요가 없다**. 커스텀 scheme을 등록하면 아무 앱이나
 * 매칭할 수 있는 URI가 생기는데, 얻는 것 없이 노출면만 넓어진다. 릴리스 배제도 이쪽이 자동이다 —
 * 이 클래스가 debug 소스셋에만 있어 릴리스 산출물에 아예 들어가지 않는다.
 *
 * ```
 * adb shell am start -n com.davi.helper.debug/com.davi.helper.MainActivity \
 *   --es davi.goal.b64 <base64> [--ez davi.autostart true]
 * ```
 *
 * **base64를 권하는 이유는 전송 바이트를 전부 ASCII로 만들기 위해서다.** 한글이 `am`을 통과하는지
 * 여부가 논점에서 사라지고, 공백이 든 값에 따옴표를 두 번 씌워야 하는 adb 인자 규칙도 비껴간다.
 * 원문 extra(`davi.goal`)도 받지만 그건 **인코딩이 통과한다는 데 기대는 경로**다.
 *
 * 파싱은 [parse]가 하고 인텐트는 [fromIntent]가 벗긴다 — 안드로이드 타입에 닿지 않는 자리를
 * 남겨야 JVM 테스트로 덮을 수 있다. 디코드는 `java.util.Base64`를 쓴다(minSdk 26이라 쓸 수 있고,
 * `android.util.Base64`는 JVM 단위 테스트에서 못 부른다).
 */
object DebugGoalIntent {

    const val EXTRA_GOAL_B64 = "davi.goal.b64"
    const val EXTRA_GOAL_RAW = "davi.goal"
    const val EXTRA_AUTOSTART = "davi.autostart"

    fun fromIntent(intent: Intent?): DebugGoalRequest? = parse(
        goalB64 = intent?.getStringExtra(EXTRA_GOAL_B64),
        goalRaw = intent?.getStringExtra(EXTRA_GOAL_RAW),
        autoStart = intent?.getBooleanExtra(EXTRA_AUTOSTART, false) ?: false,
    )

    /**
     * 목표가 없거나 읽을 수 없으면 `null`이다 — 화면은 아무것도 하지 않는다.
     * 둘 다 오면 base64가 이긴다(그쪽이 권장 경로다).
     */
    fun parse(goalB64: String?, goalRaw: String?, autoStart: Boolean): DebugGoalRequest? {
        val goal = decodeBase64(goalB64) ?: goalRaw?.trim()?.takeIf { it.isNotEmpty() }
        return goal?.let { DebugGoalRequest(it, autoStart) }
    }

    /**
     * **깨진 값은 조용히 통과시키지 않는다.** 표준·URL-safe 알파벳을 차례로 시도하고, 바이트가
     * 올바른 UTF-8이 아니면 `null`이다. 관대한 디코더(MIME)는 아무 문자나 무시하고 지나가서
     * 목표 자리에 깨진 글자가 들어앉는데, 그건 "안내가 이상하다"로만 보이고 원인이 안 드러난다.
     */
    private fun decodeBase64(value: String?): String? {
        val trimmed = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val bytes = runCatching { Base64.getDecoder().decode(trimmed) }
            .recoverCatching { Base64.getUrlDecoder().decode(trimmed) }
            .getOrNull() ?: return null
        val decoded = runCatching {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull() ?: return null
        return decoded.trim().takeIf { it.isNotEmpty() }
    }
}
