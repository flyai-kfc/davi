package com.davi.helper

import android.content.Intent
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * 인텐트 하나에 대한 판정. [fillsField]는 입력칸을 채우는가다 — 채우지 않는 갈래는 화면이
 * 거짓말하지 않게 하려는 것이고, 이유는 [reason]으로 logcat에 남는다.
 */
enum class DebugGoalOutcome(val reason: String, val fillsField: Boolean, val starts: Boolean) {
    /** 이 진입점을 겨냥했는데(예: autostart만) 목표 자체가 없다. 키 오타가 여기로 온다. */
    NO_GOAL("no_goal", false, false),
    UNDECODABLE("undecodable", false, false),
    SESSION_ACTIVE("session_active", false, false),
    VOICE_BUSY("voice_busy", false, false),
    AUTOSTART_OFF("autostart_off", true, false),
    ACCESSIBILITY_NOT_READY("accessibility_not_ready", true, false),
    START("ok", true, true),
}

/** 목표가 어느 extra로 들어왔는가. 잘린 목표를 사후에 알아보려면 이게 로그에 남아야 한다. */
enum class DebugGoalSource { B64, RAW }

/** 인텐트로 들어온 목표. [autoStart]면 화면이 입력칸을 채운 뒤 안내 시작까지 간다. */
data class DebugGoalRequest(
    val goal: String,
    val autoStart: Boolean,
    val source: DebugGoalSource,
)

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
 *
 * **원문 extra(`davi.goal`)도 받지만 그 경로는 공백에서 잘린다.** 실측(2026-08-21, SM-S921N):
 * `--es davi.goal '검색 열어줘'`는 adb가 인자를 쪼개 `Intent { … pkg=열어줘 … }`가 되고
 * 값이 「검색」만 남는다. 따옴표를 두 번 씌우면(`"'검색 열어줘'"`) 정상이다. **한글 자체는
 * `am`을 통과한다 — 깨지는 것은 인코딩이 아니라 공백이다.** 잘린 목표는 유효한 문자열이라
 * 여기서 걸러낼 방법이 없고, 화면과 로그의 `source=raw`로만 사후에 알아볼 수 있다.
 *
 * 파싱은 [parse]가 하고 인텐트는 [fromIntent]가 벗긴다 — 안드로이드 타입에 닿지 않는 자리를
 * 남겨야 JVM 테스트로 덮을 수 있다. 디코드는 `java.util.Base64`를 쓴다(minSdk 26이라 쓸 수 있고,
 * `android.util.Base64`는 JVM 단위 테스트에서 못 부른다).
 */
object DebugGoalIntent {

    const val EXTRA_GOAL_B64 = "davi.goal.b64"
    const val EXTRA_GOAL_RAW = "davi.goal"
    const val EXTRA_AUTOSTART = "davi.autostart"

    /**
     * 이 진입점을 겨냥한 extra가 하나라도 실려 있는가. **`autostart`도 센다** — 목표 없이 플래그만
     * 던진 경우가 무음으로 끝나면 "인텐트가 안 갔나 / 앱이 안 읽었나"를 구분할 수 없다.
     * 일반 실행(`am start`만)에는 걸리지 않아 로그가 시끄러워지지도 않는다.
     */
    fun hasGoalExtra(intent: Intent?): Boolean = intent != null &&
        (hasGoalValue(intent) || intent.hasExtra(EXTRA_AUTOSTART))

    /** 목표 **값**이 실렸는가. `autostart`만 온 경우를 "못 읽었다"로 적지 않으려고 따로 본다. */
    fun hasGoalValue(intent: Intent?): Boolean =
        intent != null && (intent.hasExtra(EXTRA_GOAL_B64) || intent.hasExtra(EXTRA_GOAL_RAW))

    fun fromIntent(intent: Intent?): DebugGoalRequest? = parse(
        goalB64 = intent?.getStringExtra(EXTRA_GOAL_B64),
        goalRaw = intent?.getStringExtra(EXTRA_GOAL_RAW),
        autoStart = intent?.getBooleanExtra(EXTRA_AUTOSTART, false) ?: false,
    )

    /**
     * **읽은 extra는 소비한다.** 다만 이것만으로는 **구성 변경(회전)까지만** 막힌다 —
     * 프로세스가 살아 있어 우리가 고친 그 Intent 객체가 그대로 재사용되기 때문이다.
     *
     * **프로세스가 죽었다 복원되는 경우는 못 막는다.** 시스템이 들고 있는 `ActivityRecord.intent`는
     * `final`이라 우리 변형이 닿지 않고, 재시작 때 클라이언트로 오는 것은 그 **원본의 새 사본**이다
     * (AOSP `ActivityTaskSupervisor`가 `new Intent(r.intent)`로 만든다). 되살아나는 값이 마지막에
     * 던진 목표가 아니라 **최초 런치 인텐트의 목표**라는 점도 같은 이유다.
     * 그쪽은 `MainActivity`가 `savedInstanceState`로 막는다 — 근거는 그 호출부에 있다.
     */
    fun consume(intent: Intent?) {
        intent ?: return
        intent.removeExtra(EXTRA_GOAL_B64)
        intent.removeExtra(EXTRA_GOAL_RAW)
        intent.removeExtra(EXTRA_AUTOSTART)
    }

    /**
     * 인텐트 하나를 받고 화면이 무엇을 할지. **분기를 여기 두는 이유는 JVM 테스트로 덮기
     * 위해서다** — `MainActivity` 안에 있으면 안드로이드 타입에 묶여 한 갈래도 검증되지 않는다.
     */
    fun decide(
        request: DebugGoalRequest?,
        goalExtraPresent: Boolean,
        sessionActive: Boolean,
        voiceBusy: Boolean,
        canStartGuide: Boolean,
    ): DebugGoalOutcome = when {
        // "안 실렸다"와 "실렸는데 못 읽었다"를 가른다 — 로그를 읽는 쪽이 볼 곳이 다르다.
        request == null && !goalExtraPresent -> DebugGoalOutcome.NO_GOAL
        request == null -> DebugGoalOutcome.UNDECODABLE
        // 세션·대화 중에는 입력칸도 건드리지 않는다. 비활성 입력칸의 글자만 바뀌면 화면이
        // "지금 무엇을 안내 중인지"를 거짓말한다.
        sessionActive -> DebugGoalOutcome.SESSION_ACTIVE
        voiceBusy -> DebugGoalOutcome.VOICE_BUSY
        !request.autoStart -> DebugGoalOutcome.AUTOSTART_OFF
        !canStartGuide -> DebugGoalOutcome.ACCESSIBILITY_NOT_READY
        else -> DebugGoalOutcome.START
    }

    /**
     * 목표가 없거나 읽을 수 없으면 `null`이다.
     * 둘 다 오면 base64가 이긴다(그쪽이 권장 경로다).
     */
    fun parse(goalB64: String?, goalRaw: String?, autoStart: Boolean): DebugGoalRequest? {
        decodeBase64(goalB64)?.let { return DebugGoalRequest(it, autoStart, DebugGoalSource.B64) }
        val raw = goalRaw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return DebugGoalRequest(raw, autoStart, DebugGoalSource.RAW)
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
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull() ?: return null
        return decoded.trim().takeIf { it.isNotEmpty() }
    }
}
