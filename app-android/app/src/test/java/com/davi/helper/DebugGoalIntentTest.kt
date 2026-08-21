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
    fun `둘 다 오면 base64가 이긴다`() {
        // 권장 경로가 base64다. 원문은 인코딩이 통과한다는 데 기대는 경로라 뒤로 둔다.
        val parsed = DebugGoalIntent.parse(b64("알람 맞춰줘"), "설정 열어줘", autoStart = false)
        assertEquals("알람 맞춰줘", parsed?.goal)
    }

    @Test
    fun `base64가 깨졌으면 원문으로 떨어진다`() {
        // 디코드 실패를 "목표 없음"으로 끝내면 원문을 함께 준 사람이 이유 없이 막힌다.
        assertEquals("설정 열어줘", DebugGoalIntent.parse("!!!", "설정 열어줘", autoStart = false)?.goal)
    }
}
