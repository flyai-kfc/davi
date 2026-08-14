package com.davi.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 파도·글로우 움직임 계산의 고정(#132).
 *
 * **눈으로만 확인 가능한 것과 값으로 확인 가능한 것을 갈라 둔다.** "일렁여 보이는가"는 실기기
 * 확인 항목이고, 여기서는 그 일렁임이 가능하려면 반드시 참이어야 하는 성질을 지킨다 —
 * 세 겹이 서로 다르게 움직이는가, 무음에서도 바닥이 남는가, 음량이 범위를 벗어나도 접히는가.
 */
class WaveMotionTest {

    // --- 세 겹이 서로 어긋나는가 -------------------------------------------------

    @Test
    fun `파도 세 겹의 속도가 서로 다르다`() {
        val speeds = WaveMotion.LAYERS.map { it.speed }
        assertEquals("겹마다 속도가 달라야 한다", speeds.size, speeds.distinct().size)
    }

    @Test
    fun `파도 세 겹의 속도비가 정수배가 아니다`() {
        // 정수배면 일정 주기마다 세 겹이 같은 배치로 되돌아와 규칙적인 맥동이 눈에 띈다.
        val speeds = WaveMotion.LAYERS.map { abs(it.speed) }
        for (i in speeds.indices) {
            for (j in speeds.indices) {
                if (i == j) continue
                val ratio = speeds[i] / speeds[j]
                val nearestInteger = ratio.roundToInt()
                assertTrue(
                    "속도비 ${speeds[i]}/${speeds[j]} = $ratio 가 정수($nearestInteger)에 너무 가깝다",
                    nearestInteger == 0 || abs(ratio - nearestInteger) > 0.1f,
                )
            }
        }
    }

    @Test
    fun `파도 세 겹의 위상과 파장이 서로 다르다`() {
        val phases = WaveMotion.LAYERS.map { it.phase }
        assertEquals(phases.size, phases.distinct().size)
        val lengths = WaveMotion.LAYERS.map { it.waveLengthFraction }
        assertEquals(lengths.size, lengths.distinct().size)
    }

    @Test
    fun `같은 시각 같은 자리에서 세 겹의 높이가 서로 다르다`() {
        // 세 겹이 같은 값이면 한 덩어리로 붙어 움직인다.
        val offsets = WaveMotion.LAYERS.map {
            WaveMotion.offsetAt(it, xFraction = 0.5f, baseTurns = 0.31f, rippleTurns = 0.5f, amplitude = 100f)
        }
        assertEquals(offsets.size, offsets.distinct().size)
    }

    @Test
    fun `한 겹은 시간이 흐르면 모양이 바뀐다`() {
        val layer = WaveMotion.LAYERS.first()
        val at0 = WaveMotion.offsetAt(layer, 0.25f, baseTurns = 0f, rippleTurns = 0f, amplitude = 100f)
        val at1 = WaveMotion.offsetAt(layer, 0.25f, baseTurns = 0.25f, rippleTurns = 0.4f, amplitude = 100f)
        assertNotEquals(at0, at1)
    }

    @Test
    fun `가운데 겹은 다른 겹과 반대 방향으로 흐른다`() {
        // 마주 지나가야 붙어 다니는 인상이 깨진다.
        val signs = WaveMotion.LAYERS.map { it.speed > 0f }
        assertTrue("한 겹은 반대 방향이어야 한다", signs.distinct().size > 1)
    }

    @Test
    fun `진폭이 0이면 모든 겹이 평평하다`() {
        WaveMotion.LAYERS.forEach {
            assertEquals(0f, WaveMotion.offsetAt(it, 0.5f, baseTurns = 0.4f, rippleTurns = 0.6f, amplitude = 0f), TOLERANCE)
        }
    }

    @Test
    fun `주기가 0이면 위상이 흐르지 않는다`() {
        // 모션 끄기에서 정지 프레임을 그릴 때 0으로 나누는 사고가 없어야 한다.
        WaveMotion.LAYERS.forEach {
            assertEquals(0f, WaveMotion.baseTurnsPerSecond(it, periodSeconds = 0f, level = 1f), TOLERANCE)
            assertEquals(0f, WaveMotion.rippleTurnsPerSecond(it, periodSeconds = 0f, level = 1f), TOLERANCE)
        }
    }

    // --- 위상 누적 (요동) ---------------------------------------------------------

    @Test
    fun `말하면 위상이 흐른다`() {
        WaveMotion.LAYERS.forEach {
            assertNotEquals(0f, WaveMotion.baseTurnsPerSecond(it, 4.2f, level = 1f))
        }
    }

    @Test
    fun `말하지 않으면 파도가 멈춘다`() {
        // 2026-08-14 결정 — 파도가 떠 있다는 것 자체가 "말할 차례"라는 신호이므로,
        // 멈춰 있어도 안 듣고 있는 것으로 읽히지 않는다.
        assertEquals(0f, WaveMotion.flowSpeed(0f), TOLERANCE)
        WaveMotion.LAYERS.forEach {
            assertEquals(0f, WaveMotion.baseTurnsPerSecond(it, 4.2f, level = 0f), TOLERANCE)
            assertEquals(0f, WaveMotion.rippleTurnsPerSecond(it, 4.2f, level = 0f), TOLERANCE)
        }
    }

    @Test
    fun `조용한 방의 잡음으로는 흐르지 않는다`() {
        // 문턱 아래는 완전히 0 — 안 그러면 아무도 말하지 않는데 파도가 실실 흐른다.
        assertEquals(0f, WaveMotion.flowSpeed(0.01f), TOLERANCE)
        assertEquals(0f, WaveMotion.flowSpeed(0.05f), TOLERANCE)
    }

    @Test
    fun `문턱을 넘으면 0에서 이어 올라간다`() {
        // 문턱에서 곧장 최대 속도로 튀면 말을 시작하는 순간 파도가 홱 움직인다.
        val justOver = WaveMotion.flowSpeed(0.07f)
        assertTrue("문턱 바로 위는 움직여야 한다", justOver > 0f)
        assertTrue("문턱 바로 위가 이미 빠르면 튄다", justOver < WaveMotion.flowSpeed(1f) * 0.1f)
    }

    @Test
    fun `흐르는 빠르기는 음량에 따라 단조 증가한다`() {
        val samples = listOf(0.1f, 0.3f, 0.6f, 1f).map(WaveMotion::flowSpeed)
        samples.zipWithNext { low, high -> assertTrue("$low < $high 이어야 한다", low < high) }
    }

    @Test
    fun `말을 멈춰도 파도 모양은 남는다`() {
        // 진폭까지 0이 되면 파도가 사라진 것으로 보여 "말할 차례" 신호가 함께 사라진다.
        assertTrue(WaveMotion.amplitudeFraction(0f) > 0f)
    }

    @Test
    fun `음량이 클수록 위상이 빨리 흐른다`() {
        // 말할 때 요동치는 인상의 절반이 빠르기에서 나온다.
        WaveMotion.LAYERS.forEach {
            val soft = abs(WaveMotion.baseTurnsPerSecond(it, 4.2f, level = 0.3f))
            val loud = abs(WaveMotion.baseTurnsPerSecond(it, 4.2f, level = 1f))
            assertTrue("음량이 크면 더 빨리 흘러야 한다", loud > soft)
        }
    }

    @Test
    fun `위상은 한 바퀴마다 접혀 무한히 커지지 않는다`() {
        // 반대로 흐르는 겹이 있으므로 음수로도 발산하지 않아야 한다.
        WaveMotion.LAYERS.forEach { layer ->
            var base = 0f
            var ripple = 0f
            val baseRate = WaveMotion.baseTurnsPerSecond(layer, 4.2f, level = 1f)
            val rippleRate = WaveMotion.rippleTurnsPerSecond(layer, 4.2f, level = 1f)
            repeat(5000) {
                base = WaveMotion.advanceTurns(base, baseRate, 0.5f)
                ripple = WaveMotion.advanceTurns(ripple, rippleRate, 0.5f)
            }
            assertTrue("기본파 위상이 $base 로 발산했다", base in 0f..1f)
            assertTrue("잔물결 위상이 $ripple 로 발산했다", ripple in 0f..1f)
        }
    }

    @Test
    fun `모든 겹이 위상을 접어도 기본파와 잔물결이 이어진다`() {
        // PR #136 리뷰 회귀. 이전 구현은 공유 위상 하나를 접은 뒤 겹마다 비정수 배수를
        // 곱해서, 접히는 순간 순간이동했다. 최대 음량에서 약 2초마다 눈에 띄는 점프가 생겼다.
        //
        // **세 겹이 모두 끊겼다.** -0.62·1.37인 두 겹은 기본파에서, 속도 1.00이라 기본파는
        // 이어지던 첫 겹도 잔물결(위상 x RIPPLE_SPEED=1.6)에서 끊겼다. 프레임당 최대 변화량이
        // 각각 31.2 / 39.1 / 37.7px였고 수정 뒤 4.4 / 2.1 / 3.6px가 됐다.
        //
        // 겹마다 도는 것과 잔물결을 함께 보는 것이 핵심이다 — 옛 테스트는 first()의 기본파만
        // 검사해서, 그 겹이 잔물결에서 끊기는 것까지 함께 놓쳤다.
        WaveMotion.LAYERS.forEachIndexed { index, layer ->
            val baseRate = WaveMotion.baseTurnsPerSecond(layer, 4.2f, level = 1f)
            val rippleRate = WaveMotion.rippleTurnsPerSecond(layer, 4.2f, level = 1f)
            val step = 1f / 60f

            // 접히기 직전 상태를 만든다.
            var base = if (baseRate > 0f) 1f - abs(baseRate) * step / 2f else abs(baseRate) * step / 2f
            var ripple = if (rippleRate > 0f) 1f - abs(rippleRate) * step / 2f else abs(rippleRate) * step / 2f

            val x = 0.5f
            val before = WaveMotion.offsetAt(layer, x, base, ripple, amplitude = 50f, choppiness = 1f)
            base = WaveMotion.advanceTurns(base, baseRate, step)
            ripple = WaveMotion.advanceTurns(ripple, rippleRate, step)
            val after = WaveMotion.offsetAt(layer, x, base, ripple, amplitude = 50f, choppiness = 1f)

            // 한 프레임 사이의 정상 변화량. 이보다 크게 벌어지면 순간이동이다.
            val normalStep = 50f * TWO_PI_APPROX * (abs(baseRate) + abs(rippleRate)) * step * 2f
            assertTrue(
                "겹 $index 이 접히는 순간 ${abs(after - before)} 만큼 튀었다(정상 $normalStep)",
                abs(after - before) <= normalStep,
            )
        }
    }

    @Test
    fun `접힌 위상은 한 바퀴 전과 같은 값을 준다`() {
        // 위상 0과 1은 같은 지점이어야 한다 — 겹마다 확인한다.
        WaveMotion.LAYERS.forEachIndexed { index, layer ->
            val at0 = WaveMotion.offsetAt(layer, 0.5f, 0f, 0f, 50f, choppiness = 1f)
            val at1 = WaveMotion.offsetAt(layer, 0.5f, 1f, 1f, 50f, choppiness = 1f)
            assertEquals("겹 $index", at0, at1, 0.01f)
        }
    }

    @Test
    fun `반대로 흐르는 겹도 위상이 음수로 새지 않는다`() {
        val backward = WaveMotion.LAYERS.first { it.speed < 0f }
        val rate = WaveMotion.baseTurnsPerSecond(backward, 4.2f, level = 1f)
        assertTrue("이 겹은 반대로 흘러야 한다", rate < 0f)
        assertTrue(WaveMotion.advanceTurns(0f, rate, 0.5f) in 0f..1f)
    }

    // --- 잔물결 (요동) -----------------------------------------------------------

    @Test
    fun `조용하면 잔물결이 없다`() {
        val layer = WaveMotion.LAYERS.first()
        val plain = WaveMotion.offsetAt(layer, 0.4f, 0.2f, 0.3f, 50f, choppiness = 0f)
        val default = WaveMotion.offsetAt(layer, 0.4f, 0.2f, 0.3f, 50f)
        assertEquals("기본값은 잔물결 없음이어야 한다", plain, default, TOLERANCE)
    }

    @Test
    fun `말하면 잔물결이 얹혀 결이 달라진다`() {
        val layer = WaveMotion.LAYERS.first()
        val smooth = WaveMotion.offsetAt(layer, 0.4f, 0.2f, 0.3f, 50f, choppiness = 0f)
        val choppy = WaveMotion.offsetAt(layer, 0.4f, 0.2f, 0.3f, 50f, choppiness = 1f)
        assertNotEquals(smooth, choppy)
    }

    @Test
    fun `잔물결이 최대여도 진폭이 과하게 넘치지 않는다`() {
        // 잔물결이 큰 물결을 압도하면 파도가 아니라 노이즈가 된다.
        val layer = WaveMotion.LAYERS.first()
        val amplitude = 50f
        val worst = (0..200).maxOf { i ->
            abs(WaveMotion.offsetAt(layer, i / 200f, 0.37f, 0.62f, amplitude, choppiness = 1f))
        }
        val ceiling = amplitude * layer.amplitudeScale * 1.5f
        assertTrue("최대 $worst 가 상한 $ceiling 을 넘었다", worst <= ceiling)
    }

    @Test
    fun `잔물결 세기는 범위를 벗어나도 접힌다`() {
        val layer = WaveMotion.LAYERS.first()
        val over = WaveMotion.offsetAt(layer, 0.4f, 0.2f, 0.3f, 50f, choppiness = 5f)
        val max = WaveMotion.offsetAt(layer, 0.4f, 0.2f, 0.3f, 50f, choppiness = 1f)
        assertEquals(max, over, TOLERANCE)
    }

    // --- 음량 --------------------------------------------------------------------

    @Test
    fun `무음에서도 최소 진폭이 남는다`() {
        // 평평해지면 "멈췄다 = 안 듣고 있다"로 읽힌다. 이 표시의 목적은 음량계가 아니다.
        assertEquals(WaveMotion.IDLE_AMPLITUDE, WaveMotion.amplitudeFraction(0f), TOLERANCE)
        assertTrue(WaveMotion.amplitudeFraction(0f) > 0f)
    }

    @Test
    fun `최대 음량에서 진폭이 가득 찬다`() {
        assertEquals(1f, WaveMotion.amplitudeFraction(1f), TOLERANCE)
    }

    @Test
    fun `진폭은 음량에 따라 단조 증가한다`() {
        val samples = listOf(0f, 0.25f, 0.5f, 0.75f, 1f).map(WaveMotion::amplitudeFraction)
        samples.zipWithNext { low, high -> assertTrue("$low < $high 이어야 한다", low < high) }
    }

    @Test
    fun `범위를 벗어난 음량도 접힌다`() {
        // 기기·마이크마다 RMS 범위가 달라 문서 범위 밖 값이 실제로 온다.
        assertEquals(0f, WaveMotion.normalizeRms(-100f), TOLERANCE)
        assertEquals(1f, WaveMotion.normalizeRms(100f), TOLERANCE)
        assertEquals(WaveMotion.IDLE_AMPLITUDE, WaveMotion.amplitudeFraction(-5f), TOLERANCE)
        assertEquals(1f, WaveMotion.amplitudeFraction(5f), TOLERANCE)
    }

    @Test
    fun `NaN 음량은 무음으로 본다`() {
        assertEquals(0f, WaveMotion.normalizeRms(Float.NaN), TOLERANCE)
    }

    @Test
    fun `정규화된 음량은 단조 증가한다`() {
        val samples = listOf(-2f, 0f, 3f, 6f, 8f).map(WaveMotion::normalizeRms)
        samples.zipWithNext { low, high -> assertTrue("$low <= $high 이어야 한다", low <= high) }
    }

    // --- 수렴 --------------------------------------------------------------------

    @Test
    fun `수렴은 목표를 향해 다가가되 한 번에 닿지 않는다`() {
        val next = WaveMotion.approach(current = 0f, target = 1f, deltaSeconds = 0.016f, tauSeconds = 0.12f)
        assertTrue("움직여야 한다", next > 0f)
        assertTrue("한 프레임에 다 가면 튄다", next < 1f)
    }

    @Test
    fun `수렴을 반복하면 목표에 도달한다`() {
        var value = 0f
        repeat(120) { value = WaveMotion.approach(value, 1f, 0.016f, 0.12f) }
        assertEquals(1f, value, 0.01f)
    }

    @Test
    fun `프레임 간격이 길수록 더 많이 다가간다`() {
        // 지수 수렴이라 프레임 드롭에서 속도가 변하지 않는다.
        val short = WaveMotion.approach(0f, 1f, 0.008f, 0.12f)
        val long = WaveMotion.approach(0f, 1f, 0.032f, 0.12f)
        assertTrue(long > short)
    }

    @Test
    fun `시간 상수가 0이면 즉시 목표값이다`() {
        assertEquals(1f, WaveMotion.approach(0f, 1f, 0.016f, tauSeconds = 0f), TOLERANCE)
    }

    @Test
    fun `프레임 간격이 0이면 목표값으로 둔다`() {
        // 모션 끄기에서 정지 프레임 한 장을 그릴 때 (0f, 0f)로 불린다.
        assertEquals(1f, WaveMotion.approach(0f, 1f, deltaSeconds = 0f, tauSeconds = 0.12f), TOLERANCE)
    }

    private companion object {
        const val TOLERANCE = 0.0001f
        const val TWO_PI_APPROX = 6.2832f
    }
}
