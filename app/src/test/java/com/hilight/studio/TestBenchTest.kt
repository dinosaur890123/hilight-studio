package com.hilight.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class TestBenchTest {

    @Test
    fun `the power plan alternates dark and lit steps and stays inside the normal limits`() {
        val plan = TestBench.POWER_PLAN
        assertEquals(TestBench.PowerStep.OFF, plan.first())
        assertEquals(TestBench.PowerStep.OFF, plan.last())
        plan.zipWithNext().forEach { (a, b) -> assertTrue(a == TestBench.PowerStep.OFF || b == TestBench.PowerStep.OFF) }
        // every lit step is shorter than the 10-second taper, so the measurement is never dimmed
        assertTrue(TestBench.POWER_STEP_MS < 10_000)
        assertEquals(1, TestBench.PowerStep.ONE_LED.frame().count { it == 0xFFFFFFFF.toInt() })
        assertEquals(LED_COUNT, TestBench.PowerStep.WHITE_100.frame().count { it == 0xFFFFFFFF.toInt() })
    }

    @Test
    fun `power is the lit step against the dark steps either side, whatever the sign`() {
        // the phone draws -300 mA idle (negative while discharging), white adds 120 mA
        val steps = TestBench.POWER_PLAN.mapIndexed { i, step ->
            val extra = when (step) {
                TestBench.PowerStep.OFF -> 0
                TestBench.PowerStep.WHITE_100 -> -120_000
                else -> -40_000
            }
            List(10) { j -> -300_000 + extra + (if (j % 2 == 0) 500 else -500) + i * 100 }
        }
        val results = TestBench.analysePower(steps, voltageMilliVolts = 4_000)
        assertEquals(7, results.size)
        val white = results.first { it.step == TestBench.PowerStep.WHITE_100 }
        assertEquals(120_000.0, white.deltaMicroAmps, 1.0)
        assertEquals(480.0, white.milliWatts, 0.5)          // 120 mA × 4 V
        assertEquals(160.0, results.first { it.step == TestBench.PowerStep.RED }.milliWatts, 0.5)
    }

    @Test
    fun `the soak stops on a hot battery or a thermal warning`() {
        fun s(c: Double, th: Int = 0) = TestBench.Sample(0, c, -300_000, 4_000, th, 0.3f, true)
        assertNull(TestBench.abortReason(s(38.0)))
        assertEquals(TestBench.StopReason.BATTERY_HOT, TestBench.abortReason(s(42.0)))
        assertEquals(TestBench.StopReason.THERMAL, TestBench.abortReason(s(36.0, th = 2)))
        assertNull(TestBench.abortReason(s(36.0, th = 1)))
    }

    @Test
    fun `cooling needs both time and a battery back near its start`() {
        assertFalse(TestBench.cooledDown(30.0, 30.2, 10 * 60_000))
        assertFalse(TestBench.cooledDown(30.0, 31.0, 20 * 60_000))
        assertTrue(TestBench.cooledDown(30.0, 30.4, 15 * 60_000))
    }

    @Test
    fun `runs keep their readings, summarise themselves and export as CSV`() {
        val samples = (0..70).map { i ->
            val on = i >= 2
            TestBench.Sample(i * 10_000L, 30.0 + i * 0.05, if (on) -420_000 else -300_000, 4_000, if (i > 60) 1 else 0, 0.4f, on)
        }
        val run = TestBench.HeatRun(TestBench.HeatRunType.NO_DIMMING, 1_700_000_000_000, samples, TestBench.StopReason.COMPLETED, 41.5, "on the desk", surfaceStartC = 29.0)
        val back = TestBench.HeatRun.fromJson(run.toJson())!!
        assertEquals(run, back)
        assertEquals(30.0, back.startC!!, 1e-9)
        assertEquals(33.5, back.maxC!!, 1e-9)
        assertEquals(480.0, back.ledMilliWatts()!!, 0.5)
        assertEquals(680_000L, back.litMs)
        assertEquals(12.5, back.surfaceRiseC!!, 1e-9)

        val previous = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            val csv = TestBench.csv(listOf(TestBench.PowerResult(TestBench.PowerStep.RED, 40_000.0, 160.0)), listOf(run))
            assertTrue(csv.contains("power,RED,40000,160.0"))
            assertTrue(csv.contains("run,1,NO_DIMMING,1700000000000,COMPLETED,30.0,33.5,33.5,29.0,41.5,12.5,680,480,\"on the desk\""))
            assertEquals(71, csv.lines().count { it.startsWith("sample,1,") })
        } finally {
            Locale.setDefault(previous)
        }
    }
}
