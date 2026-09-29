package com.hilight.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WaitingAppsTest {
    private val green = 0xFF00E676.toInt()
    private val blue = 0xFF2979FF.toInt()
    private val red = 0xFFFF1744.toInt()
    private val amber = 0xFFFFAB00.toInt()

    private fun e(rule: String, pkg: String = rule, at: Long = 0) = WaitingApps.Entry(rule, pkg, at)

    @Test
    fun `sections follow rule order and keep the newest app per rule`() {
        val picked = WaitingApps.pick(
            listOf(e("gmail", at = 5), e("any", "com.a", 1), e("whatsapp", at = 3), e("any", "com.b", 9)),
            ruleOrder = listOf("whatsapp", "gmail", "any"),
            newestRuleId = "gmail",
        )
        assertEquals(listOf("whatsapp", "gmail", "any"), picked.map { it.ruleId })
        assertEquals("com.b", picked.last().pkg)
    }

    @Test
    fun `at most four sections and the app that just notified always keeps one`() {
        val order = listOf("a", "b", "c", "d", "e", "f")
        val entries = order.map { e(it) }
        assertEquals(listOf("a", "b", "c", "d"), WaitingApps.pick(entries, order, "b").map { it.ruleId })
        assertEquals(listOf("a", "b", "c", "f"), WaitingApps.pick(entries, order, "f").map { it.ruleId })
        // a rule missing from the order (edited meanwhile) sorts last rather than failing
        assertEquals(listOf("a", "zz"), WaitingApps.pick(listOf(e("zz"), e("a")), listOf("a"), "zz").map { it.ruleId })
    }

    @Test
    fun `one app fills the array and two apps meet in a soft blend`() {
        assertEquals(List(LED_COUNT) { green }, WaitingApps.ledColours(listOf(green)))
        val two = WaitingApps.ledColours(listOf(green, blue))
        assertEquals(listOf(green, green, green), two.take(3))
        assertEquals(listOf(blue, blue, blue), two.takeLast(3))
        assertTrue(two[3] != green && two[3] != blue)
        assertTrue(two[4] != green && two[4] != blue)
        assertNotEquals(two[3], two[4])
    }

    @Test
    fun `three apps keep pure ends and four apps stay crisp pairs`() {
        val three = WaitingApps.ledColours(listOf(green, blue, red))
        assertEquals(green, three.first())
        assertEquals(red, three.last())
        assertTrue(three.contains(blue))
        assertEquals(
            listOf(green, green, blue, blue, red, red, amber, amber),
            WaitingApps.ledColours(listOf(green, blue, red, amber)),
        )
    }

    @Test
    fun `the glow rises to the exact section colours once and fades`() {
        val leds = WaitingApps.ledColours(listOf(green, blue, red))
        val json = WaitingApps.glowAlert(id = 1, leds = leds, durationMs = 5000, brightness = 1f)
        assertEquals("breathe", json.getString("pattern"))
        assertEquals(5000, json.getInt("durationMs"))
        assertEquals("notification", json.getString("source"))
        val core = com.hilight.core.Renderer()
        assertEquals(leds, core.frame(json, 2500, LED_COUNT).toList())
        val start = core.frame(json, 0, LED_COUNT)
        start.forEachIndexed { i, c -> assertTrue((c and 0xFF) <= ((leds[i] and 0xFF) * 0.06).toInt() + 1) }
        val quarter = core.frame(json, 1250, LED_COUNT)
        assertTrue(quarter.indices.all { (quarter[it] shr 16 and 0xFF) <= (leds[it] shr 16 and 0xFF) })
        assertEquals(WaitingApps.previewLook(listOf(green, blue, red)).perLed, leds)
    }

    @Test
    fun `glow brightness follows the rule and stays within renderer bounds`() {
        assertEquals(0.4, WaitingApps.glowAlert(1, List(8) { green }, 3000, 0.4f).getDouble("brightness"), 1e-6)
        assertEquals(0.05, WaitingApps.glowAlert(1, List(8) { green }, 3000, 0f).getDouble("brightness"), 1e-6)
        assertEquals(3_000, WaitingApps.safeDurationMs(10))
        assertEquals(8_000, WaitingApps.safeDurationMs(60_000))
    }

    @Test
    fun `similar colours are flagged and distinct ones are not`() {
        assertTrue(WaitingApps.tooSimilar(green, 0xFF00C853.toInt()))
        assertTrue(WaitingApps.tooSimilar(red, 0xFFFF0033.toInt()))
        assertFalse(WaitingApps.tooSimilar(green, blue))
        assertFalse(WaitingApps.tooSimilar(red, amber))
        assertTrue(WaitingApps.tooSimilar(0xFFFFFFFF.toInt(), 0xFFF0F0F0.toInt()))
        assertFalse(WaitingApps.tooSimilar(0xFFFFFFFF.toInt(), red))
    }
}
