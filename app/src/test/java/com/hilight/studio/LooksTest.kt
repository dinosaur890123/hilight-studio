package com.hilight.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class LooksTest {

    @Test
    fun `featured looks are distinct, visible and survive a preset round trip`() {
        val keys = Looks.featured.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        for (look in Looks.featured) {
            assertNotEquals(Pattern.OFF, look.ambient.pattern)
            assertEquals(look.ambient, Preset.fromJson(Preset("x", look.ambient).toJson()).ambient)
        }
        // every new effect is shown off by at least one featured look
        val shown = Looks.featured.map { it.ambient.pattern }.toSet()
        assertTrue(shown.containsAll(listOf(Pattern.AURORA, Pattern.CROSSFADE, Pattern.MARQUEE, Pattern.TWINKLE, Pattern.CANDLE)))
    }

    @Test
    fun `surprise changes the effect but never the brightness`() {
        val random = Random(42)
        var current = Ambient(pattern = Pattern.AURORA, brightness = 0.23f)
        repeat(200) {
            val next = Looks.surprise(current, random)
            assertNotEquals(current.pattern, next.pattern)
            assertEquals(0.23f, next.brightness)
            assertTrue(next.speedMs in 150..8000)
            assertNotEquals(next.color, next.secondColor)
            current = next
        }
    }

    @Test
    fun `harmonies rotate hue and keep saturation and value`() {
        assertEquals(0xFF00FFFF.toInt(), Looks.Harmony.COMPLEMENT.from(0xFFFF0000.toInt()))
        assertEquals(0xFF0000FF.toInt(), Looks.rotateHue(0xFF00FF00.toInt(), 120f))
        assertEquals(0xFFFF0000.toInt(), Looks.rotateHue(0xFF0000FF.toInt(), 120f))
        val (_, s, v) = Looks.toHsv(Looks.Harmony.ANALOGOUS.from(0xFF804020.toInt()))
        val (_, s0, v0) = Looks.toHsv(0xFF804020.toInt())
        assertEquals(s0, s, 0.02f)
        assertEquals(v0, v, 0.02f)
    }

    @Test
    fun `swatches summarise a look`() {
        assertEquals(emptyList<Int>(), Looks.swatches(Ambient(pattern = Pattern.OFF)))
        assertEquals(listOf(1, 2), Looks.swatches(Ambient(pattern = Pattern.AURORA, color = 1, secondColor = 2)))
        assertEquals(listOf(1), Looks.swatches(Ambient(pattern = Pattern.TWINKLE, color = 1, secondColor = 2)))
        assertEquals(4, Looks.swatches(Ambient(pattern = Pattern.CUSTOM, perLed = (1..8).toList())).size)
    }

    private val a = Preset("A", Ambient(color = 1))
    private val b = Preset("B", Ambient(color = 2))
    private val c = Preset("C", Ambient(color = 3))
    private val list = listOf(a, b, c)

    @Test
    fun `rename keeps position and refuses empty or taken names`() {
        assertEquals(listOf("A", "Bee", "C"), PresetOps.rename(list, b, "  Bee ")!!.map { it.name })
        assertNull(PresetOps.rename(list, b, "   "))
        assertNull(PresetOps.rename(list, b, "C"))
        assertEquals(list, PresetOps.rename(list, b, "B"))
        assertEquals(Looks.MAX_NAME_LENGTH, PresetOps.rename(list, b, "x".repeat(99))!![1].name.length)
    }

    @Test
    fun `update overwrites only the chosen preset's look`() {
        val look = Ambient(pattern = Pattern.CANDLE, color = 9)
        val updated = PresetOps.update(list, b, look)
        assertEquals(listOf(a.ambient, look, c.ambient), updated.map { it.ambient })
        assertEquals(listOf("A", "B", "C"), updated.map { it.name })
    }

    @Test
    fun `move shifts one place and stops at the ends`() {
        assertEquals(listOf("B", "A", "C"), PresetOps.move(list, b, -1).map { it.name })
        assertEquals(listOf("A", "C", "B"), PresetOps.move(list, b, +1).map { it.name })
        assertEquals(list, PresetOps.move(list, a, -1))
        assertEquals(list, PresetOps.move(list, c, +1))
        assertEquals(list, PresetOps.move(list, Preset("missing", Ambient()), +1))
    }
}
