package com.hilight.studio

import org.json.JSONArray
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LedMapTest {

    @Test
    fun `stored maps round trip and anything but a real permutation reads as unmapped`() {
        val order = listOf(3, 2, 1, 0, 7, 6, 5, 4)
        assertEquals(order, LedMap.decode(LedMap.encode(order)))
        assertNull(LedMap.decode(null))
        assertNull(LedMap.decode(""))
        assertNull(LedMap.decode("0,1,2"))
        assertNull(LedMap.decode("0,0,1,2,3,4,5,6"))
        assertNull(LedMap.decode("0,1,2,3,4,5,6,8"))
        assertNull(LedMap.decode("a,1,2,3,4,5,6,7"))
        assertNull("identity is the same as unmapped", LedMap.decode("0,1,2,3,4,5,6,7"))
    }

    @Test
    fun `the wizard asks about each LED in turn and builds the map`() {
        var w = LedMapWizard()
        assertEquals(0, w.step)
        // hardware LED k turns out to sit at ring position (k * 3) % 8
        repeat(LED_COUNT) { k -> w = w.place((k * 3) % 8) }
        assertTrue(w.complete)
        val order = w.order()!!
        repeat(LED_COUNT) { k -> assertEquals(k, order[(k * 3) % 8]) }
        assertTrue(LedMap.isPermutation(order))
    }

    @Test
    fun `taken positions are refused and undo steps back one LED`() {
        var w = LedMapWizard().place(5)
        assertSame(w, w.place(5))
        assertFalse(w.isFree(5))
        w = w.place(2)
        assertEquals(2, w.step)
        w = w.undo()
        assertEquals(1, w.step)
        assertTrue(w.isFree(2))
        assertFalse(w.isFree(5))
        assertNull(w.order())
        assertEquals(LedMapWizard(), LedMapWizard().undo())
    }

    @Test
    fun `the wizard lights exactly one white LED`() {
        val frame = LedMap.single(3)
        assertEquals(0xFFFFFFFF.toInt(), frame[3])
        assertEquals(7, frame.count { it == 0xFF000000.toInt() })
    }

    @Test
    fun `the renderer moves each position to its hardware LED and ignores bad maps`() {
        val order = listOf(3, 2, 1, 0, 7, 6, 5, 4)
        val parsed = com.hilight.core.LedOrderAccess.parse(JSONArray(order))
        val frame = IntArray(LED_COUNT) { it + 100 }
        val out = com.hilight.core.LedOrderAccess.apply(frame, parsed)
        order.forEachIndexed { position, led -> assertEquals(frame[position], out[led]) }

        for (bad in listOf("[0,1,2]", "[0,0,1,2,3,4,5,6]", "[0,1,2,3,4,5,6,9]", "[0,1,2,3,4,5,6,7]")) {
            val p = com.hilight.core.LedOrderAccess.parse(JSONArray(bad))
            assertNull(bad, p)
            assertArrayEquals(frame, com.hilight.core.LedOrderAccess.apply(frame, p))
        }
        assertArrayEquals(frame, com.hilight.core.LedOrderAccess.apply(frame, null))
    }
}
