package com.davi.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * `DebugGoalIntent`는 debug 소스셋에 있고 `:app:testDebugUnitTest`가 그 소스셋을 포함한다
 * (CI의 `test` 태스크가 그대로 커버한다).
 *
 * 여기서 지키려는 것은 **깨진 값이 조용히 목표 자리에 앉지 않는 것**이다. 목표는 그대로 안내
 * 요청으로 나가므로, 잘못 디코드된 문자열은 "안내가 이상하다"로만 보이고 원인이 안 드러난다.
 */
class DebugGoalIntentTest {

    private fun b64(text: String): String =
        Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

    private fun req(autoStart: Boolean = false) =
        DebugGoalRequest("알람 맞춰줘", autoStart, DebugGoalSource.B64)

    // --- decide: 화면이 무엇을 할지. MainActivity 안에 두면 한 갈래도 검증되지 않는다 ---

    @Test
    fun `목표가 아예 안 실린 경우와 못 읽은 경우를 가른다`() {
        // autostart만 던졌거나 키를 오타낸 경우가 "못 읽었다"로 찍히면 볼 곳이 달라진다.
        assertEquals(
            DebugGoalOutcome.NO_GOAL,
            DebugGoalIntent.decide(null, goalValuePresent = false, sessionActive = false, canStartGuide = true),
        )
    }

    @Test
    fun `못 읽은 목표는 입력칸도 안 채운다`() {
        val o = DebugGoalIntent.decide(null, goalValuePresent = true, sessionActive = false, canStartGuide = true)
        assertEquals(DebugGoalOutcome.UNDECODABLE, o)
        assertEquals(false, o.fillsField)
        assertEquals(false, o.starts)
    }

    @Test
    fun `세션 중에는 입력칸을 건드리지 않는다`() {
        // 비활성 입력칸의 글자만 바뀌면 화면이 "지금 무엇을 안내 중인지"를 거짓말한다.
        val o = DebugGoalIntent.decide(req(autoStart = true), goalValuePresent = true, sessionActive = true, canStartGuide = true)
        assertEquals(DebugGoalOutcome.SESSION_ACTIVE, o)
        assertEquals(false, o.fillsField)
    }

    @Test
    fun `autostart가 없으면 채우기만 한다`() {
        val o = DebugGoalIntent.decide(req(autoStart = false), goalValuePresent = true, sessionActive = false, canStartGuide = true)
        assertEquals(DebugGoalOutcome.AUTOSTART_OFF, o)
        assertEquals(true, o.fillsField)
        assertEquals(false, o.starts)
    }

    @Test
    fun `접근성이 준비 안 됐으면 채우되 시작하지 않는다`() {
        val o = DebugGoalIntent.decide(req(autoStart = true), goalValuePresent = true, sessionActive = false, canStartGuide = false)
        assertEquals(DebugGoalOutcome.ACCESSIBILITY_NOT_READY, o)
        assertEquals(true, o.fillsField)
        assertEquals(false, o.starts)
    }

    @Test
    fun `준비됐고 autostart면 시작한다`() {
        val o = DebugGoalIntent.decide(req(autoStart = true), goalValuePresent = true, sessionActive = false, canStartGuide = true)
        assertEquals(DebugGoalOutcome.START, o)
        assertEquals(true, o.starts)
        assertEquals("ok", o.reason)
    }

    @Test
    fun `세션 중이면 접근성 준비 여부보다 먼저 걸린다`() {
        // 순서가 뒤집히면 "세션 중인데 접근성 미준비"가 accessibility_not_ready로 찍혀
        // 로그를 읽는 쪽이 엉뚱한 곳을 본다.
        val o = DebugGoalIntent.decide(req(autoStart = true), goalValuePresent = true, sessionActive = true, canStartGuide = false)
        assertEquals(DebugGoalOutcome.SESSION_ACTIVE, o)
    }

    @Test
    fun `base64 한글을 그대로 돌려준다`() {
        val parsed = DebugGoalIntent.parse(b64("알람 맞춰줘"), null, autoStart = false)
        assertEquals("알람 맞춰줘", parsed?.goal)
        assertEquals(false, parsed?.autoStart)
    }

    @Test
    fun `autostart 플래그를 나른다`() {
        assertTrue(DebugGoalIntent.parse(b64("알람 맞춰줘"), null, autoStart = true)!!.autoStart)
    }

    @Test
    fun `URL-safe 알파벳도 받는다`() {
        // 표준 알파벳의 +와 /가 -와 _로 바뀐 형태. adb 인자로 실어 나르다 보면 섞인다.
        val urlSafe = Base64.getUrlEncoder().encodeToString("설정 열어줘".toByteArray(Charsets.UTF_8))
        assertEquals("설정 열어줘", DebugGoalIntent.parse(urlSafe, null, autoStart = false)?.goal)
    }

    @Test
    fun `base64가 아니면 null이다`() {
        assertNull(DebugGoalIntent.parse("!!! not base64 !!!", null, autoStart = false))
    }

    @Test
    fun `UTF-8이 아닌 바이트는 null이다`() {
        // 0xFF는 UTF-8 선행 바이트가 될 수 없다. 관대한 디코더면 U+FFFD로 통과했을 값이다.
        val invalid = Base64.getEncoder().encodeToString(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))
        assertNull(DebugGoalIntent.parse(invalid, null, autoStart = false))
    }

    @Test
    fun `빈 값과 공백만 있는 값은 null이다`() {
        assertNull(DebugGoalIntent.parse(null, null, autoStart = false))
        assertNull(DebugGoalIntent.parse("", "", autoStart = false))
        assertNull(DebugGoalIntent.parse(b64("   "), null, autoStart = false))
        assertNull(DebugGoalIntent.parse(null, "   ", autoStart = false))
    }

    @Test
    fun `원문 extra도 받고 앞뒤 공백을 턴다`() {
        assertEquals("알람 맞춰줘", DebugGoalIntent.parse(null, "  알람 맞춰줘  ", autoStart = false)?.goal)
    }

    @Test
    fun `어느 extra로 들어왔는지 남긴다`() {
        // 원문 경로는 공백에서 잘릴 수 있다(adb 인자 규칙). 잘린 목표도 유효한 문자열이라
        // 걸러낼 수 없고, 이 source가 사후에 알아볼 유일한 단서다.
        assertEquals(DebugGoalSource.B64, DebugGoalIntent.parse(b64("알람"), null, autoStart = false)?.source)
        assertEquals(DebugGoalSource.RAW, DebugGoalIntent.parse(null, "알람", autoStart = false)?.source)
    }

    @Test
    fun `둘 다 오면 base64가 이긴다`() {
        // 권장 경로가 base64다. 원문은 인코딩이 통과한다는 데 기대는 경로라 뒤로 둔다.
        val parsed = DebugGoalIntent.parse(b64("알람 맞춰줘"), "설정 열어줘", autoStart = false)
        assertEquals("알람 맞춰줘", parsed?.goal)
    }

    @Test
    fun `base64가 깨졌으면 원문으로 떨어진다`() {
        // 디코드 실패를 "목표 없음"으로 끝내면 원문을 함께 준 사람이 이유 없이 막힌다.
        val parsed = DebugGoalIntent.parse("!!!", "설정 열어줘", autoStart = false)
        assertEquals("설정 열어줘", parsed?.goal)
        assertEquals(DebugGoalSource.RAW, parsed?.source)
    }
}
