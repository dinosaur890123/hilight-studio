package com.hilight.studio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Finds the beat in live audio for music sync.
 *
 * Audio arrives as short blocks already split into a bass level and a level for everything above
 * it ([BandSplitter]). A jump in either is an onset (a kick, a snare, a strum); the tempo is the
 * beat length that best explains the gaps between the last few seconds of onsets; the beats fall
 * where those onsets line up on that grid; and the intensity is how loud the music is against the
 * loudest it has been lately.
 *
 * Pure, so it is host-tested with synthetic drum patterns; [MusicListener] feeds it real audio.
 */
class MusicAnalyzer {

    /** A tempo lock: beats [beatMs] apart, one of them at [anchorMs]. */
    data class Beat(val beatMs: Int, val anchorMs: Long, val confidence: Double) {
        val bpm: Int get() = (60_000.0 / beatMs).roundToInt()
    }

    private data class Onset(val tMs: Long, val strength: Double)

    private val onsets = ArrayDeque<Onset>()
    private var lastT = Long.MIN_VALUE
    /** Typical time between blocks; coarse blocks (phone audio) need a looser timing tolerance. */
    private var blockMs = 12.0
    private var prevBass = 0.0
    private var prevRest = 0.0
    private var noveltyMean = 0.0
    private var lastOnsetMs = Long.MIN_VALUE / 2
    private var lastEstimateMs = Long.MIN_VALUE / 2
    private var pendingBeatMs: Int? = null
    private var envelope = 0.0
    private var peak = PEAK_FLOOR
    private var loudSinceMs: Long? = null
    private var quietSinceMs: Long? = null

    /** The current tempo lock, or null until there is one. */
    var beat: Beat? = null
        private set

    /** How loud the music is now against the loudest it has been lately, 0..1. */
    var intensity: Double = 0.0
        private set

    /** True once the audio has been near silent for [SILENCE_MS]. */
    var silent: Boolean = true
        private set

    /** Onsets in the tempo window, for the "finding the beat" status. */
    val recentOnsets: Int get() = onsets.size

    /**
     * One block of audio at [tMs] (elapsed realtime of its middle), as the RMS of its bass and of
     * everything above the bass. Returns true when the block starts an onset.
     */
    fun onBlock(tMs: Long, bassRms: Double, restRms: Double): Boolean {
        val dt = if (lastT == Long.MIN_VALUE) 0L else (tMs - lastT).coerceIn(0, 1_000)
        val first = lastT == Long.MIN_VALUE
        lastT = tMs
        if (dt > 0) blockMs += (dt - blockMs) * 0.1

        val level = sqrt(bassRms * bassRms + restRms * restRms)
        trackLoudness(tMs, dt, level)

        val bass = ln(1 + COMPRESS * bassRms)
        val rest = ln(1 + COMPRESS * restRms)
        val novelty = if (first) 0.0 else max(0.0, bass - prevBass) + REST_WEIGHT * max(0.0, rest - prevRest)
        prevBass = bass
        prevRest = rest
        noveltyMean += (novelty - noveltyMean) * (1 - exp(-dt / NOVELTY_TAU_MS))

        while (onsets.isNotEmpty() && tMs - onsets.first().tMs > WINDOW_MS) onsets.removeFirst()
        val candidate = !first && !silent && level >= SILENCE_RMS &&
            novelty >= MIN_NOVELTY && novelty > ONSET_RATIO * noveltyMean
        val onset = when {
            !candidate -> false
            tMs - lastOnsetMs >= MIN_ONSET_GAP_MS -> {
                lastOnsetMs = tMs
                onsets.addLast(Onset(tMs, novelty))
                true
            }
            // A much stronger hit just after a small one (a click, then the kick) is the real
            // onset; the small one would otherwise hide it.
            onsets.isNotEmpty() && novelty > onsets.last().strength * REPLACE_RATIO -> {
                onsets.removeLast()
                lastOnsetMs = tMs
                onsets.addLast(Onset(tMs, novelty))
                true
            }
            else -> false
        }
        if (tMs - lastEstimateMs >= ESTIMATE_EVERY_MS) {
            lastEstimateMs = tMs
            estimate(tMs)
        }
        return onset
    }

    fun reset() {
        onsets.clear()
        lastT = Long.MIN_VALUE
        blockMs = 12.0
        prevBass = 0.0
        prevRest = 0.0
        noveltyMean = 0.0
        lastOnsetMs = Long.MIN_VALUE / 2
        lastEstimateMs = Long.MIN_VALUE / 2
        pendingBeatMs = null
        envelope = 0.0
        peak = PEAK_FLOOR
        loudSinceMs = null
        quietSinceMs = null
        beat = null
        intensity = 0.0
        silent = true
    }

    private fun trackLoudness(tMs: Long, dt: Long, level: Double) {
        val tau = if (level > envelope) ATTACK_MS else RELEASE_MS
        envelope += (level - envelope) * (1 - exp(-dt / tau))
        peak = max(max(envelope, PEAK_FLOOR), peak * exp(-dt / PEAK_DECAY_MS))
        intensity = (envelope / peak).coerceIn(0.0, 1.0)
        if (level >= SILENCE_RMS) {
            quietSinceMs = null
            if (loudSinceMs == null) loudSinceMs = tMs
            if (tMs - loudSinceMs!! >= SOUND_MS) silent = false
        } else {
            loudSinceMs = null
            if (quietSinceMs == null) quietSinceMs = tMs
            if (tMs - quietSinceMs!! >= SILENCE_MS) silent = true
        }
    }

    private fun estimate(nowMs: Long) {
        if (onsets.size < MIN_ONSETS) {
            // A long break with no onsets loses the lock; a short one (a fill, a quiet bar) keeps it.
            if (beat != null && nowMs - lastOnsetMs > LOSE_LOCK_MS) beat = null
            return
        }
        val best = bestBeatMs() ?: return
        val current = beat?.beatMs
        val chosen = when {
            current == null -> best
            abs(best - current) <= current * SAME_TEMPO -> (current * 0.7 + best * 0.3).roundToInt()
            // A different tempo has to win twice running before the light switches to it.
            pendingBeatMs?.let { abs(best - it) <= it * SAME_TEMPO } == true -> best
            else -> {
                pendingBeatMs = best
                current
            }
        }
        if (chosen != current) pendingBeatMs = null
        val (anchor, confidence) = phase(chosen, nowMs)
        beat = if (current == null && confidence < MIN_CONFIDENCE) null else Beat(chosen, anchor, confidence)
    }

    /** The beat length whose multiples best match the gaps between recent onsets. */
    internal fun bestBeatMs(): Int? {
        val list = onsets.toList()
        var bestP = 0
        var bestScore = 0.0
        var p = MIN_BEAT_MS
        while (p <= MAX_BEAT_MS) {
            // Timing slop: a few percent of the beat, or half a block when blocks are coarse.
            val sigma = maxOf(MIN_SIGMA_MS, p * SIGMA, blockMs * 0.5)
            var score = 0.0
            for (i in list.indices) {
                for (j in i + 1 until list.size) {
                    val gap = (list[j].tMs - list[i].tMs).toDouble()
                    if (gap > PAIR_SPAN_MS) break
                    val k = (gap / p).roundToInt()
                    if (k == 0) continue
                    val err = abs(gap - k * p)
                    if (err < 3 * sigma) {
                        val z = err / sigma
                        score += list[i].strength * list[j].strength * exp(-0.5 * z * z) / k
                    }
                }
            }
            // A gentle preference for ordinary dance tempos settles half- and double-time ties.
            val octaves = log2(p / PREFERRED_BEAT_MS)
            score *= exp(-0.5 * (octaves / PRIOR_OCTAVES) * (octaves / PRIOR_OCTAVES))
            if (score > bestScore) {
                bestScore = score
                bestP = p
            }
            p += STEP_MS
        }
        return bestP.takeIf { bestScore > 0 }
    }

    /**
     * Where beats of length [beatMs] fall: the circular mean of recent onset times on that grid,
     * favouring strong and recent onsets. Returns the latest beat at or before [nowMs], and how
     * tightly the onsets agree (1 = all exactly on the grid).
     */
    private fun phase(beatMs: Int, nowMs: Long): Pair<Long, Double> {
        var sx = 0.0
        var sy = 0.0
        var total = 0.0
        for (o in onsets) {
            val w = o.strength * exp(-(nowMs - o.tMs) / PHASE_RECENCY_MS)
            val angle = 2 * PI * Math.floorMod(o.tMs, beatMs.toLong()) / beatMs
            sx += w * cos(angle)
            sy += w * sin(angle)
            total += w
        }
        if (total <= 0) return nowMs to 0.0
        var angle = atan2(sy, sx)
        if (angle < 0) angle += 2 * PI
        val phaseMs = (angle / (2 * PI) * beatMs).roundToInt().toLong()
        val anchor = nowMs - Math.floorMod(nowMs - phaseMs, beatMs.toLong())
        return anchor to sqrt(sx * sx + sy * sy) / total
    }

    companion object {
        /** 200 BPM to 60 BPM; anything slower dances at double time, which looks better anyway. */
        const val MIN_BEAT_MS = 300
        const val MAX_BEAT_MS = 1_000
        const val STEP_MS = 5
        const val PREFERRED_BEAT_MS = 520.0
        const val PRIOR_OCTAVES = 1.0

        const val WINDOW_MS = 8_000L
        const val PAIR_SPAN_MS = 2_100.0
        const val MIN_SIGMA_MS = 12.0
        const val SIGMA = 0.03
        const val MIN_ONSETS = 6
        const val ESTIMATE_EVERY_MS = 400L
        const val SAME_TEMPO = 0.04
        const val MIN_CONFIDENCE = 0.3
        const val LOSE_LOCK_MS = 4_000L
        const val PHASE_RECENCY_MS = 3_000.0

        const val COMPRESS = 200.0
        const val REST_WEIGHT = 0.6
        const val NOVELTY_TAU_MS = 1_500.0
        const val ONSET_RATIO = 1.8
        const val MIN_NOVELTY = 0.06
        const val MIN_ONSET_GAP_MS = 180L
        const val REPLACE_RATIO = 2.0

        /** Below this RMS (about -48 dBFS) the audio counts as silence. */
        const val SILENCE_RMS = 0.004
        const val SILENCE_MS = 1_500L
        const val SOUND_MS = 150L
        const val ATTACK_MS = 40.0
        const val RELEASE_MS = 450.0
        const val PEAK_DECAY_MS = 8_000.0
        const val PEAK_FLOOR = 0.02
    }
}

/**
 * Splits audio into a bass level (below about 150 Hz, the kick and bass line) and a level for
 * everything above it, with a one-pole low-pass that carries its state from block to block.
 */
class BandSplitter(sampleRateHz: Int, cutoffHz: Double = 150.0) {
    private val alpha = 1 - exp(-2 * PI * cutoffHz / sampleRateHz)
    private var low = 0.0

    /** RMS of the bass and of the rest over [count] samples in the range -1..1. */
    fun split(samples: FloatArray, count: Int = samples.size): Pair<Double, Double> {
        if (count <= 0) return 0.0 to 0.0
        var bass = 0.0
        var rest = 0.0
        for (i in 0 until count) {
            val x = samples[i].toDouble()
            low += (x - low) * alpha
            bass += low * low
            val r = x - low
            rest += r * r
        }
        return sqrt(bass / count) to sqrt(rest / count)
    }
}

/** The light's beat clock, and when to re-sync it to what the analyser hears. */
object MusicSync {

    /** The beat clock the renderer is playing: its "timeOffsetMs" was [offsetMs] at [startedAtMs]. */
    data class Grid(val beatMs: Int, val startedAtMs: Long, val offsetMs: Long, val energy: Double) {
        fun clockAt(nowMs: Long): Long = nowMs - startedAtMs + offsetMs
    }

    const val MIN_RESYNC_GAP_MS = 300L
    const val PHASE_SLOP_MS = 40L
    const val ENERGY_STEP = 0.1

    /** Brightness for an intensity: never fully dim while the music plays, dark when it stops. */
    fun energyFor(intensity: Double, silent: Boolean): Double =
        if (silent) 0.0 else Math.round((0.3 + 0.7 * intensity.coerceIn(0.0, 1.0)) * 20) / 20.0

    /**
     * The grid to send now, or null to leave the light alone. Beats should light at the heard beat
     * times, [beat]'s anchor plus [leadMs] (positive when the light should come later). A re-sync
     * keeps the running beat count, so the colours and moves carry on rather than starting over.
     */
    fun plan(current: Grid?, beat: MusicAnalyzer.Beat, energy: Double, nowMs: Long, leadMs: Long, lastSentMs: Long): Grid? {
        val p = beat.beatMs.toLong()
        val wantFrac = Math.floorMod(nowMs - (beat.anchorMs + leadMs), p)
        if (current == null) return Grid(beat.beatMs, nowMs, wantFrac, energy)
        if (nowMs - lastSentMs < MIN_RESYNC_GAP_MS) return null

        val curP = current.beatMs.toLong()
        val clock = current.clockAt(nowMs)
        val curIndex = clock / curP
        val curFrac = clock % curP
        val tempoMoved = abs(p - curP) > max(3L, p / 100)
        var phaseErr = abs(wantFrac - curFrac * p / curP)
        phaseErr = minOf(phaseErr, p - phaseErr)
        val energyMoved = abs(energy - current.energy) >= ENERGY_STEP - 1e-9 ||
            (energy == 0.0) != (current.energy == 0.0)
        if (!tempoMoved && phaseErr <= PHASE_SLOP_MS && !energyMoved) return null

        // Keep the beat number: pick the grid position nearest where the light already is.
        val here = curIndex * p + curFrac * p / curP
        val offset = listOf(curIndex - 1, curIndex, curIndex + 1)
            .map { it * p + wantFrac }
            .filter { it >= 0 }
            .minByOrNull { abs(it - here) } ?: wantFrac
        return Grid(beat.beatMs, nowMs, offset, energy)
    }
}
