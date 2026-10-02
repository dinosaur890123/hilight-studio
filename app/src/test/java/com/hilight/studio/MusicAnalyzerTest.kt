package com.hilight.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

class MusicAnalyzerTest {

    /**
     * A synthetic drum track as band levels: a kick on every beat, a snare on two and four, hi-hats
     * on the eighths, a quiet bass-and-pad bed, a little timing slop, and the odd missed kick.
     */
    private class Track(
        val bpm: Double,
        val startMs: Long = 0,
        val gain: Double = 1.0,
        val seed: Int = 1,
        val missEvery: Int = 7,
    ) {
        val beatMs = 60_000.0 / bpm
        private val rnd = Random(seed)
        private val slop = DoubleArray(4_000) { rnd.nextDouble(-8.0, 8.0) }

        fun beatTime(k: Int): Double = startMs + k * beatMs + slop[k % slop.size]

        fun levels(tMs: Long): Pair<Double, Double> {
            var bass = 0.02 + 0.01 * sin(tMs / 170.0)
            var rest = 0.025 + 0.008 * sin(tMs / 53.0)
            val k = ((tMs - startMs) / beatMs).toInt()
            for (b in maxOf(0, k - 2)..k) {
                val dt = tMs - beatTime(b)
                if (dt < 0) continue
                if (b % missEvery != 3) bass += 0.30 * exp(-dt / 90.0)
                if (b % 2 == 1) rest += 0.18 * exp(-dt / 60.0)
                val half = dt - beatMs / 2
                if (half >= 0) rest += 0.06 * exp(-half / 25.0)
                rest += 0.06 * exp(-dt / 25.0)
            }
            return bass * gain to rest * gain
        }
    }

    private fun run(analyzer: MusicAnalyzer, track: Track, fromMs: Long, toMs: Long, blockMs: Long) {
        var t = fromMs
        while (t < toMs) {
            val (b, r) = track.levels(t)
            analyzer.onBlock(t, b, r)
            t += blockMs
        }
    }

    private fun circularMs(a: Double, b: Double, period: Double): Double {
        val d = Math.floorMod(Math.round(a - b), Math.round(period)).toDouble()
        return min(d, period - d)
    }

    @Test
    fun `locks to common tempos within a few seconds, from the microphone and from phone audio`() {
        for (blockMs in listOf(12L, 50L)) for (bpm in listOf(90.0, 100.0, 120.0, 128.0, 140.0, 160.0)) {
            val track = Track(bpm, seed = bpm.toInt())
            val analyzer = MusicAnalyzer()
            run(analyzer, track, 0, 5_000, blockMs)
            val beat = analyzer.beat
            assertNotNull("bpm=$bpm block=$blockMs should lock in 5 s", beat)
            assertEquals("bpm=$bpm block=$blockMs", bpm, 60_000.0 / beat!!.beatMs, bpm * 0.015)
            // the anchor sits on a kick
            assertTrue(
                "bpm=$bpm block=$blockMs phase off by ${circularMs(beat.anchorMs.toDouble(), track.beatTime(0), track.beatMs)}",
                circularMs(beat.anchorMs.toDouble(), track.beatTime(0), track.beatMs) <= blockMs + 25,
            )
        }
    }

    @Test
    fun `fast drum and bass dances at the beat or at half time, never a wrong tempo`() {
        val track = Track(174.0)
        val analyzer = MusicAnalyzer()
        run(analyzer, track, 0, 6_000, 12)
        val bpm = 60_000.0 / analyzer.beat!!.beatMs
        assertTrue("got $bpm", abs(bpm - 174) < 3 || abs(bpm - 87) < 2)
    }

    @Test
    fun `follows a tempo change and keeps the lock through a short break`() {
        val analyzer = MusicAnalyzer()
        run(analyzer, Track(120.0), 0, 8_000, 12)
        assertEquals(500.0, analyzer.beat!!.beatMs.toDouble(), 8.0)
        run(analyzer, Track(140.0, startMs = 8_000), 8_000, 18_000, 12)
        assertEquals(60_000 / 140.0, analyzer.beat!!.beatMs.toDouble(), 6.0)
        // two near-silent seconds (a pause) keep the beat; a long silence drops it
        val quiet = Track(140.0, startMs = 18_000, gain = 0.01)
        run(analyzer, quiet, 18_000, 20_000, 12)
        assertNotNull(analyzer.beat)
        run(analyzer, quiet, 20_000, 26_000, 12)
        assertNull(analyzer.beat)
        assertTrue(analyzer.silent)
    }

    @Test
    fun `silence finds no beat and the intensity follows the loudness`() {
        val analyzer = MusicAnalyzer()
        for (t in 0L until 4_000L step 12L) analyzer.onBlock(t, 0.0005, 0.0005)
        assertNull(analyzer.beat)
        assertTrue(analyzer.silent)
        assertEquals(0.0, MusicSync.energyFor(analyzer.intensity, analyzer.silent), 0.0)

        run(analyzer, Track(120.0, startMs = 4_000), 4_000, 10_000, 12)
        assertTrue(!analyzer.silent)
        var loud = 0.0
        for (t in 10_000L until 12_000L step 12L) {
            val (b, r) = Track(120.0, startMs = 4_000).levels(t)
            analyzer.onBlock(t, b, r)
            loud = maxOf(loud, analyzer.intensity)
        }
        var soft = 0.0
        for (t in 12_000L until 14_000L step 12L) {
            val (b, r) = Track(120.0, startMs = 4_000, gain = 0.3).levels(t)
            analyzer.onBlock(t, b, r)
            soft = maxOf(soft, analyzer.intensity)
        }
        assertTrue("loud=$loud soft=$soft", loud > 0.8 && soft < loud * 0.6)
        assertTrue(MusicSync.energyFor(soft, false) < MusicSync.energyFor(loud, false))
        assertTrue(MusicSync.energyFor(0.0, false) >= 0.3)
    }

    @Test
    fun `the band splitter puts a low tone in the bass and a high one above it`() {
        val rate = 44_100
        val low = FloatArray(4_410) { (0.5 * sin(2 * Math.PI * 60 * it / rate)).toFloat() }
        val high = FloatArray(4_410) { (0.5 * sin(2 * Math.PI * 3_000 * it / rate)).toFloat() }
        val (lb, lr) = BandSplitter(rate).split(low)
        val (hb, hr) = BandSplitter(rate).split(high)
        assertTrue("low: bass=$lb rest=$lr", lb > lr * 1.5)
        assertTrue("high: bass=$hb rest=$hr", hr > hb * 10)
    }

    @Test
    fun `the light's beat clock starts on the beat and re-syncs only when it drifts`() {
        val beat = MusicAnalyzer.Beat(beatMs = 500, anchorMs = 10_000, confidence = 0.9)
        val first = MusicSync.plan(null, beat, 0.8, nowMs = 10_120, leadMs = 0, lastSentMs = 0)!!
        // 120 ms after the beat, the light is 120 ms into beat zero
        assertEquals(120L, first.offsetMs)
        assertEquals(120L, first.clockAt(10_120))
        assertEquals(1_000L, first.clockAt(11_000))

        // on the grid and at the same loudness: leave it alone
        assertNull(MusicSync.plan(first, beat.copy(anchorMs = 11_000), 0.8, 11_200, 0, 10_120))
        // too soon after the last send: leave it alone even if it drifted
        assertNull(MusicSync.plan(first, beat.copy(anchorMs = 11_100), 0.8, 10_300, 0, 10_120))
        // the beat moved by 80 ms: re-sync, keeping the beat number
        val moved = MusicSync.plan(first, beat.copy(anchorMs = 11_080), 0.8, 11_200, 0, 10_120)!!
        assertEquals(1_000L + 120, moved.clockAt(11_200))
        // louder music re-syncs the brightness without moving the beat
        val louder = MusicSync.plan(first, beat.copy(anchorMs = 11_000), 1.0, 11_200, 0, 10_120)!!
        assertEquals(first.clockAt(11_200), louder.clockAt(11_200))
        assertEquals(1.0, louder.energy, 0.0)
        // a faster tempo carries the beat count on at the new length
        val faster = MusicSync.plan(first, beat.copy(beatMs = 400, anchorMs = 11_000), 0.8, 11_200, 0, 10_120)!!
        assertEquals(400, faster.beatMs)
        assertEquals(2L, faster.clockAt(11_200) / 400)
        assertEquals(200L, faster.clockAt(11_200) % 400)
        // a positive lead lights later
        val later = MusicSync.plan(null, beat, 0.8, 10_120, leadMs = 50, lastSentMs = 0)!!
        assertEquals(70L, later.offsetMs)
        // silence goes dark
        assertNotNull(MusicSync.plan(first, beat.copy(anchorMs = 11_000), 0.0, 11_200, 0, 10_120))
    }
}
