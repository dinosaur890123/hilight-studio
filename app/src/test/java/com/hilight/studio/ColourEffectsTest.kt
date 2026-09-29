package com.hilight.studio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColourEffectsTest {
    private val effects = listOf(Pattern.AURORA, Pattern.CROSSFADE, Pattern.MARQUEE, Pattern.TWINKLE, Pattern.CANDLE)
    private val core = com.hilight.core.Renderer()
    private val first = 0xFFFF1744.toInt()
    private val second = 0xFF00E5FF.toInt()

    private fun look(pattern: Pattern, speed: Int = 1000, brightness: Float = 1f) =
        Ambient(pattern = pattern, color = first, secondColor = second, speedMs = speed, brightness = brightness)

    private fun device(look: Ambient, time: Long) = core.frame(look.toJson(), time, LED_COUNT)

    private fun rgb(c: Int) = Triple((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)

    @Test
    fun `new effects render the same preview and device frames across timing and brightness`() {
        for (pattern in effects) for (speed in listOf(60, 1000, 3370, 8000)) {
            for (brightness in listOf(0f, 0.4f, 1f)) {
                val look = look(pattern, speed, brightness)
                for (time in (0L..9000L step 29L) + listOf(60_000L, 8_000_000_000L)) {
                    assertArrayEquals("$pattern speed=$speed brightness=$brightness time=$time",
                        device(look, time), Renderer.frame(pattern, time, look))
                }
                assertEquals(look, Ambient.fromJson(look.toPrefsJson()))
            }
        }
    }

    @Test
    fun `two-colour effects send both colours and one-colour effects send one`() {
        for (pattern in Pattern.entries.filter { it != Pattern.CUSTOM }) {
            val json = look(pattern).toJson()
            if (pattern.usesSecondColor) {
                val colours = json.getJSONArray("colors")
                assertEquals(2, colours.length())
                assertEquals(first.toUInt().toLong(), colours.getLong(0))
                assertEquals(second.toUInt().toLong(), colours.getLong(1))
            } else {
                assertFalse("$pattern", json.has("colors"))
                assertEquals(first.toUInt().toLong(), json.getLong("color"))
            }
        }
        assertEquals(
            setOf(Pattern.GRADIENT, Pattern.AURORA, Pattern.CROSSFADE, Pattern.MARQUEE),
            Pattern.entries.filter { it.usesSecondColor }.toSet(),
        )
    }

    @Test
    fun `crossfade moves the whole array between both colours`() {
        val look = look(Pattern.CROSSFADE)
        assertTrue(device(look, 0).all { it == first })
        assertTrue(device(look, 500).all { it == second })
        val middle = device(look, 250)
        assertTrue(middle.distinct().size == 1)
        assertNotEquals(first, middle[0])
        assertNotEquals(second, middle[0])
    }

    @Test
    fun `marquee shows both colours in bands and repeats each cycle`() {
        val look = look(Pattern.MARQUEE)
        val frame = device(look, 0)
        assertTrue(frame.contains(first))
        assertTrue(frame.contains(second))
        // bands are two LEDs wide, so the pattern repeats every four LEDs (to within rounding)
        for (i in 0 until LED_COUNT - 4) {
            val (r1, g1, b1) = rgb(frame[i])
            val (r2, g2, b2) = rgb(frame[i + 4])
            assertTrue(Math.abs(r1 - r2) <= 1 && Math.abs(g1 - g2) <= 1 && Math.abs(b1 - b2) <= 1)
        }
        assertArrayEquals(frame, device(look, 1000))
        assertFalse(frame.contentEquals(device(look, 250)))
    }

    @Test
    fun `aurora blends the two colours and never goes dark`() {
        val look = look(Pattern.AURORA)
        for (time in 0L..2000L step 17L) {
            for (c in device(look, time)) {
                val (r, g, b) = rgb(c)
                assertTrue("dark at $time", maxOf(r, g, b) > 40)
            }
        }
        val mixes = (0L..1000L step 50L).flatMap { device(look, it).toList() }.toSet()
        assertTrue("aurora should produce a spread of blended colours", mixes.size > 40)
    }

    @Test
    fun `twinkle keeps a faint glow and flares towards white at its peaks`() {
        val look = Ambient(pattern = Pattern.TWINKLE, color = 0xFF2040FF.toInt(), speedMs = 1200, brightness = 1f)
        var flared = false
        for (time in 0L..6000L step 7L) {
            for (c in device(look, time)) {
                val (r, g, b) = rgb(c)
                assertTrue("fully dark at $time", r + g + b > 0)
                // the base colour has little red, so strong red means the white flare
                if (r > 120 && g > 120) flared = true
            }
        }
        assertTrue(flared)
        // LEDs run on different rhythms, so they are not all at the same level
        assertTrue(device(look, 333).distinct().size > 2)
    }

    @Test
    fun `candle flickers within a warm band and is deterministic`() {
        val amber = 0xFFFF8F00.toInt()
        val look = Ambient(pattern = Pattern.CANDLE, color = amber, speedMs = 1800, brightness = 1f)
        val levels = mutableSetOf<Int>()
        for (time in 0L..10_000L step 13L) {
            for (c in device(look, time)) {
                val (r, g, b) = rgb(c)
                assertEquals(0, b)
                assertTrue("too dim at $time", r >= (255 * 0.4).toInt())
                levels += r
            }
        }
        assertTrue("candle should flicker", levels.size > 30)
        assertArrayEquals(device(look, 4321), com.hilight.core.Renderer().frame(look.toJson(), 4321, LED_COUNT))
    }

    @Test
    fun `noise stays in the unit interval`() {
        for (step in listOf(0L, 1L, 99L, 123_456_789L, Long.MAX_VALUE / 3)) for (lane in 0..8) {
            val v = Renderer.noise(step, lane)
            assertTrue(v >= 0.0 && v < 1.0)
        }
    }

    @Test
    fun `rules carry the second colour of a two-colour effect to the renderer`() {
        val rule = AppRule(pkg = "com.example", label = "Example", pattern = Pattern.AURORA, color = first)
            .let { it.withLook(it.effectiveLook(first).copy(secondColor = second)) }
        val colours = rule.effectiveLook().toJson().getJSONArray("colors")
        assertEquals(first.toUInt().toLong(), colours.getLong(0))
        assertEquals(second.toUInt().toLong(), colours.getLong(1))
        assertEquals(rule, AppRule.fromJson(rule.toPrefsJson()))
    }
}
