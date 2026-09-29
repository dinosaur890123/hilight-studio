package com.hilight.studio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtrasTest {

    // ---------------------------------------------------------------- compass

    private val none = Compass.Calibration()

    @Test
    fun `north lands on the LED the phone's top would have to turn towards`() {
        // Top pointing north: north is straight up the ring, at LED 1.
        assertEquals(0.0, Compass.northPosition(0.0, none), 1e-9)
        // Top pointing east: north is a quarter turn anticlockwise seen from the back, LED 7.
        assertEquals(6.0, Compass.northPosition(90.0, none), 1e-9)
        assertEquals(4.0, Compass.northPosition(180.0, none), 1e-9)
        assertEquals(2.0, Compass.northPosition(270.0, none), 1e-9)
        assertEquals(7.5, Compass.northPosition(22.5, none), 1e-9)
    }

    @Test
    fun `calibration rotates and reverses the ring`() {
        assertEquals(1.0, Compass.northPosition(0.0, none.nudge(1)), 1e-9)
        assertEquals(7.0, Compass.northPosition(0.0, none.nudge(-1)), 1e-9)
        assertEquals(2.0, Compass.northPosition(90.0, Compass.Calibration(reversed = true)), 1e-9)
        assertEquals(0, none.nudge(8).offsetSteps)
    }

    @Test
    fun `the needle is red on the nearest LEDs and the tail faint opposite`() {
        val exact = Compass.frame(2.0)
        assertEquals(Compass.NEEDLE, exact[2])
        assertEquals(Renderer.scale(Compass.TAIL, Compass.TAIL_LEVEL), exact[6])
        assertEquals(6, exact.count { it == 0xFF000000.toInt() })

        val between = Compass.frame(7.5)
        assertEquals(Renderer.scale(Compass.NEEDLE, 0.5), between[7])
        assertEquals(Renderer.scale(Compass.NEEDLE, 0.5), between[0])
        assertEquals(Renderer.scale(Compass.TAIL, Compass.TAIL_LEVEL * 0.5), between[3])
        assertEquals(Renderer.scale(Compass.TAIL, Compass.TAIL_LEVEL * 0.5), between[4])
    }

    @Test
    fun `quantising snaps to quarter LEDs and wraps`() {
        assertEquals(2.25, Compass.quantize(2.3), 1e-9)
        assertEquals(0.0, Compass.quantize(7.9), 1e-9)
    }

    @Test
    fun `octants and smoothing handle the wrap at north`() {
        assertEquals(0, Compass.octant(359.0))
        assertEquals(2, Compass.octant(92.0))
        assertEquals(7, Compass.octant(-40.0))
        val s = Compass.Smoother(alpha = 0.5)
        s.update(350.0)
        val next = s.update(10.0)
        // halfway between 350 and 10 the short way round is 0, not 180
        assertTrue(next < 5.0 || next > 355.0)
    }

    // ---------------------------------------------------------------- breathing

    @Test
    fun `breathing guide renders the same on screen and on the LEDs across segments`() {
        val core = com.hilight.core.Renderer()
        for (rhythm in BreathingRhythm.entries) {
            for (offset in listOf(0L, 50_000L, 100_000L)) {
                val json = BreathingGuide.alert(1, rhythm, offset, 53_000).put("brightness", 1.0)
                for (t in (0L..53_000L step 97L)) {
                    assertArrayEquals(
                        "$rhythm offset=$offset t=$t",
                        BreathingGuide.frame(rhythm, offset + t),
                        core.frame(json, t, LED_COUNT),
                    )
                }
            }
        }
    }

    @Test
    fun `a breath fills, holds, empties and rests dark`() {
        val box = BreathingRhythm.BOX
        val lit = { t: Long -> BreathingGuide.frame(box, t).count { it and 0xFFFFFF != 0 } }
        assertEquals(0, lit(0))
        assertTrue(lit(2_000) in 3..5)
        assertEquals(8, lit(6_000))
        assertTrue(lit(10_000) in 3..5)
        assertEquals(0, lit(14_000))
        assertEquals(BreathPhase.INHALE to 4_000L, BreathingGuide.phaseAt(box, 0))
        assertEquals(BreathPhase.HOLD to 1_000L, BreathingGuide.phaseAt(box, 7_000))
        assertEquals(BreathPhase.EXHALE to 4_000L, BreathingGuide.phaseAt(box, 8_000))
        assertEquals(BreathPhase.REST to 500L, BreathingGuide.phaseAt(box, 15_500))
        assertEquals(BreathPhase.INHALE, BreathingGuide.phaseAt(BreathingRhythm.CALM, 11_000).first)
        // every rhythm rests dark, so the renderer's brightness taper resets once per breath
        assertTrue(BreathingRhythm.entries.all { it.restMs >= 1_000 })
    }

    @Test
    fun `sessions are split into overlapping segments that never exceed the alert cap`() {
        for (total in BreathingGuide.SESSION_CHOICES_MS) {
            val segs = BreathingGuide.segments(total)
            assertEquals(0L, segs.first().first)
            assertTrue(segs.all { it.second <= 60_000 })
            // each segment's alert outlasts the gap to the next one
            segs.zipWithNext().forEach { (a, b) ->
                assertEquals(b.first, a.first + a.third!!)
                assertTrue(a.second > a.third!!)
            }
            val last = segs.last()
            assertEquals(null, last.third)
            assertEquals(total.toLong(), last.first + last.second)
        }
        assertEquals(1, BreathingGuide.segments(53_000).size)
    }

    // ---------------------------------------------------------------- shake

    private fun shake(d: ShakeDetector, start: Long, times: Int, gapMs: Long, g: Float = 3f): Boolean {
        var fired = false
        repeat(times) { i ->
            val t = start + i * gapMs
            fired = d.onSample(g * ShakeDetector.EARTH_G, 0f, 0f, t) || fired
            fired = d.onSample(0f, 0f, ShakeDetector.EARTH_G, t + gapMs / 2) || fired
        }
        return fired
    }

    @Test
    fun `three sharp jolts in a second are a shake, then it rests`() {
        val d = ShakeDetector()
        assertTrue(shake(d, 0, 3, 200))
        assertFalse("cooldown", shake(d, 1_000, 3, 200))
        assertTrue(shake(d, 5_000, 3, 200))
    }

    @Test
    fun `gravity, single bumps and slow sways are not shakes`() {
        val d = ShakeDetector()
        repeat(100) { assertFalse(d.onSample(0f, 0f, ShakeDetector.EARTH_G, it * 20L)) }
        assertFalse(shake(d, 10_000, 2, 200))
        assertFalse(shake(d, 20_000, 3, 600))
        assertFalse(shake(d, 30_000, 5, 150, g = 1.8f))
    }

    @Test
    fun `one long jolt is counted once`() {
        val d = ShakeDetector()
        var fired = false
        for (t in 0L..200L step 10L) fired = d.onSample(30f, 0f, 0f, t) || fired
        assertFalse(fired)
    }

    @Test
    fun `sparkles are bright twinkles in varied colours`() {
        val a = ShakeDetector.sparkleLook(0f)
        val b = ShakeDetector.sparkleLook(200f)
        assertEquals(Pattern.TWINKLE, a.pattern)
        assertNotEquals(a.color, b.color)
        assertEquals(ShakeDetector.sparkleLook(-160f).color, b.color)
    }

    // ---------------------------------------------------------------- time of day

    @Test
    fun `keyframes are exact and the day blends smoothly with a wrap at midnight`() {
        for (k in TimeOfDay.keyframes) assertEquals(k.first to k.second, TimeOfDay.colours(k.minute))
        assertEquals(TimeOfDay.colours(0), TimeOfDay.colours(TimeOfDay.DAY))
        assertEquals(TimeOfDay.colours(30), TimeOfDay.colours(30 - TimeOfDay.DAY))
        // no jump anywhere in the day: neighbouring minutes differ by at most a few levels per channel
        for (m in 0 until TimeOfDay.DAY) {
            val (a, _) = TimeOfDay.colours(m)
            val (b, _) = TimeOfDay.colours(m + 1)
            for (shift in listOf(16, 8, 0)) {
                assertTrue("minute $m", Math.abs((a shr shift and 0xFF) - (b shr shift and 0xFF)) <= 3)
            }
        }
        // late evening is between dusk and midnight
        val late = TimeOfDay.colours(23 * 60).first
        assertNotEquals(TimeOfDay.keyframes.last().first, late)
        assertNotEquals(TimeOfDay.keyframes.first().first, late)
    }

    @Test
    fun `time of day recolours single and two colour effects only`() {
        val noon = TimeOfDay.colours(12 * 60)
        val aurora = TimeOfDay.apply(Ambient(pattern = Pattern.AURORA), 12 * 60)
        assertEquals(noon.first, aurora.color)
        assertEquals(noon.second, aurora.secondColor)
        for (p in listOf(Pattern.RAINBOW, Pattern.RANDOM, Pattern.CUSTOM, Pattern.OFF)) {
            val look = Ambient(pattern = p)
            assertEquals(look, TimeOfDay.apply(look, 12 * 60))
        }
    }
}
