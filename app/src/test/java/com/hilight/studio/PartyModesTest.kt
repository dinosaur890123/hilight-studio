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
    fun `beat pulses in time, steps colour and accent, and rests dark each beat`() {
        for (beatMs in listOf(300, 500, 1_200)) for (offset in listOf(0L, 50_000L)) {
            val json = PartyModes.beatAlert(1, beatMs, offset, 53_000)
            for (t in (0L..6_000L step 23L)) {
                assertArrayEquals("beat=$beatMs offset=$offset t=$t",
                    PartyModes.beatFrame(beatMs, offset + t), core.frame(json, t, LED_COUNT))
            }
        }
        val beat = 500L
        assertEquals(0, lit(PartyModes.beatFrame(500, (beat * 0.8).toLong())))
        val first = PartyModes.beatFrame(500, 40)
        val second = PartyModes.beatFrame(500, beat + 40)
        assertTrue(first[0] != first[1])
        assertTrue(second[1] != second[0])
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

    @Test
    fun `tap tempo takes the median of recent taps and starts fresh after a pause`() {
        val tempo = TapTempo()
        assertNull(tempo.tap(0))
        assertNull(tempo.tap(500))
        assertEquals(500, tempo.tap(1_000))
        assertEquals(500, tempo.tap(1_620))   // one sloppy tap barely moves it
        assertEquals(500, tempo.tap(2_100))
        assertNull("a long pause starts over", tempo.tap(10_000))
        assertEquals(120, TapTempo.bpm(500))
        assertEquals(TapTempo.MIN_BEAT_MS, TapTempo().run { tap(0); tap(100); tap(200) })
        assertEquals(TapTempo.MAX_BEAT_MS, TapTempo.scaled(1_000, 2.0))
        assertEquals(500, TapTempo.scaled(1_000, 0.5))
    }
}
