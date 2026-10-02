package com.hilight.studio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PartyModesTest {
    private val core = com.hilight.core.Renderer()
    private fun lit(frame: IntArray) = frame.count { (it shr 16 and 0xFF) + (it shr 8 and 0xFF) + (it and 0xFF) > 12 }

    @Test
    fun `spin renders the same on screen and on the LEDs and lands on its target`() {
        for (target in 0 until LED_COUNT) for (turns in listOf(3, 5)) {
            val json = PartyModes.spinAlert(1, target, turns)
            for (t in (0L..8_000L step 41L)) {
                assertArrayEquals("target=$target turns=$turns t=$t",
                    PartyModes.spinFrame(target, turns, t), core.frame(json, t, LED_COUNT))
            }
            val after = PartyModes.spinFrame(target, turns, PartyModes.SPIN_MS + 10L)
            assertEquals(1, lit(after))
            assertTrue(lit(intArrayOf(after[target])) == 1)
        }
    }

    @Test
    fun `dice tumbles then shows evenly spaced pips`() {
        for (value in 1..6) for (seed in listOf(0L, 7L, 123_456L)) {
            val json = PartyModes.diceAlert(1, value, seed)
            for (t in (0L..6_000L step 37L)) {
                assertArrayEquals("value=$value seed=$seed t=$t",
                    PartyModes.diceFrame(value, seed, t), core.frame(json, t, LED_COUNT))
            }
            val shown = PartyModes.diceFrame(value, seed, PartyModes.DICE_ROLL_MS + 100L)
            assertEquals(value, lit(shown))
        }
        assertEquals(listOf(0, 4), PartyModes.pips(2))
        assertEquals(listOf(0, 2, 4, 6), PartyModes.pips(4))
        assertEquals(6, PartyModes.pips(6).toSet().size)
    }

    @Test
    fun `beat dances a new move each bar, changes colour each beat and rests dark`() {
        for (beatMs in listOf(300, 500, 1_200)) for (offset in listOf(0L, 50_000L)) for (energy in listOf(1.0, 0.65, 0.3, 0.0)) {
            val json = PartyModes.beatAlert(1, beatMs, offset, 53_000, energy)
            for (t in (0L..6_000L step 23L)) {
                assertArrayEquals("beat=$beatMs offset=$offset energy=$energy t=$t",
                    PartyModes.beatFrame(beatMs, offset + t, energy), core.frame(json, t, LED_COUNT))
            }
        }
        // energy dims the dance, and zero is dark
        assertEquals(0, lit(PartyModes.beatFrame(500, 40, 0.0)))
        val loud = PartyModes.beatFrame(500, 40).sumOf { it and 0xFF00 shr 8 }
        val quiet = PartyModes.beatFrame(500, 40, 0.5).sumOf { it and 0xFF00 shr 8 }
        assertTrue(quiet in 1 until loud)
        // the last fifth of every beat is dark, which keeps resetting the brightness taper
        for (b in 0L until 32L) assertEquals(0, lit(PartyModes.beatFrame(500, b * 500 + 420)))
        // each bar of four beats dances a different move
        assertEquals(listOf(0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 0), (0L..16L).map { PartyModes.beatMove(it) })
        // colour changes every beat
        val colours = (0L until 6L).map { b -> PartyModes.beatFrame(500, b * 500 + 40).maxByOrNull { (it shr 16 and 0xFF) + (it shr 8 and 0xFF) + (it and 0xFF) } }
        assertEquals(6, colours.toSet().size)
        // the chase glides: mid-beat differs from the start of the beat
        assertTrue(!PartyModes.beatFrame(500, 4 * 500 + 30).contentEquals(PartyModes.beatFrame(500, 4 * 500 + 250)))
    }

    @Test
    fun `rainbow now matches the LEDs exactly on screen too`() {
        for (spread in listOf(true, false)) for (speed in listOf(700, 2_500)) {
            val look = Ambient(pattern = Pattern.RAINBOW, speedMs = speed, rainbowSpread = spread, brightness = 1f)
            for (t in 0L..6_000L step 13L) {
                assertArrayEquals("spread=$spread t=$t", Renderer.frame(Pattern.RAINBOW, t, look), core.frame(look.toJson(), t, LED_COUNT))
            }
        }
    }

    @Test
    fun `plasma, orbit and fireworks match on screen and on the LEDs`() {
        for (pattern in listOf(Pattern.PLASMA, Pattern.ORBIT, Pattern.FIREWORKS)) for (speed in listOf(60, 1_500, 4_000)) {
            for (brightness in listOf(0.3f, 1f)) {
                val look = Ambient(pattern = pattern, color = 0xFF00E5FF.toInt(), secondColor = 0xFFFF4081.toInt(), speedMs = speed, brightness = brightness)
                for (t in (0L..9_000L step 31L) + listOf(8_000_000_000L)) {
                    assertArrayEquals("$pattern speed=$speed t=$t", Renderer.frame(pattern, t, look), core.frame(look.toJson(), t, LED_COUNT))
                }
            }
        }
        // fireworks end each burst dark and burst somewhere different
        val fw = Ambient(pattern = Pattern.FIREWORKS, speedMs = 1_000, brightness = 1f)
        assertEquals(0, lit(Renderer.frame(Pattern.FIREWORKS, 999, fw)))
        assertEquals(LED_COUNT, lit(Renderer.frame(Pattern.FIREWORKS, 300, fw)))
        // orbit's comets cross: at half a cycle they meet and their light adds up
        val orbit = Ambient(pattern = Pattern.ORBIT, color = 0xFFFF0000.toInt(), secondColor = 0xFF0000FF.toInt(), speedMs = 1_000, brightness = 1f)
        val meet = Renderer.frame(Pattern.ORBIT, 500, orbit)
        assertTrue(meet.any { (it shr 16 and 0xFF) > 200 && (it and 0xFF) > 200 })
    }

    @Test
    fun `demo reel matches on screen, plays every scene and goes dark between them`() {
        val json = PartyModes.demoAlert(1)
        val total = PartyModes.demoTotalMs.toLong()
        for (t in (0L..total + 500L step 29L)) {
            assertArrayEquals("t=$t", PartyModes.demoFrame(t), core.frame(json, t, LED_COUNT))
        }
        var start = 0L
        PartyModes.DEMO.forEachIndexed { index, scene ->
            assertEquals(index, PartyModes.demoSceneAt(start + 1000))
            // the dark gap at the head of each scene resets the brightness taper
            assertEquals(0, lit(PartyModes.demoFrame(start + 50)))
            assertTrue("scene $index lights up", (start + 600 until start + scene.ms step 200L).any { lit(PartyModes.demoFrame(it)) > 0 })
            // no scene is long enough to reach the 10-second taper on its own
            assertTrue(scene.ms < 10_000)
            start += scene.ms
        }
        assertNull(PartyModes.demoSceneAt(total))
        assertEquals(0, lit(PartyModes.demoFrame(total + 10)))
        assertTrue("fits in one alert", total <= 60_000)
    }
}
