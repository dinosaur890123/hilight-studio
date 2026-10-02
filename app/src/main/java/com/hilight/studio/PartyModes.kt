package com.hilight.studio

import androidx.annotation.StringRes
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The party pack (spin the wheel, dice), tap tempo and the demo reel.
 *
 * Each is a renderer mode, so it animates smoothly at the LEDs' own frame rate. This object builds
 * their alert documents and mirrors their maths bit for bit for the on-screen strips; the parity is
 * pinned by tests against the real renderer.
 */
object PartyModes {

    /** One colour per LED for the wheel, matching the renderer's WHEEL table. */
    val WHEEL: List<Int> = listOf(
        0xFFFF1744, 0xFFFF6D00, 0xFFFFD600, 0xFF00E676,
        0xFF00E5FF, 0xFF2979FF, 0xFF7C4DFF, 0xFFFF4081,
    ).map { it.toInt() }

    /** Colours the tap-tempo pulses step through, one per beat. */
    val BEAT_COLOURS: List<Int> = listOf(
        0xFFFF1744, 0xFFFFD600, 0xFF00E676, 0xFF00E5FF, 0xFF7C4DFF, 0xFFFF4081,
    ).map { it.toInt() }

    val DICE_COLOUR: Int = 0xFFFFF3E0.toInt()

    const val SPIN_MS = 4_500
    const val SPIN_HOLD_MS = 3_000
    const val DICE_ROLL_MS = 1_400
    const val DICE_HOLD_MS = 4_000

    /** Longest tap-tempo session; well inside the renderer's half-of-ten-minutes duty limit. */
    const val BEAT_SESSION_MS = 300_000

    // ------------------------------------------------------------------ spin

    fun spinAlert(id: Long, target: Int, turns: Int, source: AlertSource = AlertSource.PREVIEW): JSONObject =
        JSONObject().apply {
            put("id", id)
            put("pattern", "spin")
            put("target", target)
            put("turns", turns)
            put("spinMs", SPIN_MS)
            put("brightness", 1.0)
            put("durationMs", SPIN_MS + SPIN_HOLD_MS)
            put("source", source.key)
        }

    fun spinFrame(target: Int, turns: Int, t: Long, n: Int = LED_COUNT): IntArray {
        val out = IntArray(n)
        val spinMs = SPIN_MS.toLong()
        val tgt = Math.floorMod(target, n)
        if (t < spinMs) {
            val x = t / spinMs.toDouble()
            val eased = 1 - (1 - x) * (1 - x) * (1 - x)
            val head = (turns * n.toDouble() + tgt) * eased % n
            for (i in 0 until n) {
                var d = head - i
                if (d < 0) d += n
                out[i] = Renderer.scale(WHEEL[i % WHEEL.size], max(0.0, 1 - d / 2.0))
            }
        } else {
            val k = 0.5 + 0.5 * cos(2 * Math.PI * ((t - spinMs) / 700.0))
            out[tgt] = Renderer.scale(WHEEL[tgt % WHEEL.size], 0.3 + 0.7 * k)
        }
        return out
    }

    // ------------------------------------------------------------------ dice

    fun diceAlert(id: Long, value: Int, seed: Long, source: AlertSource = AlertSource.PREVIEW): JSONObject =
        JSONObject().apply {
            put("id", id)
            put("pattern", "dice")
            put("value", value)
            put("seed", seed)
            put("rollMs", DICE_ROLL_MS)
            put("colors", JSONArray().put(DICE_COLOUR.toUInt().toLong()))
            put("brightness", 1.0)
            put("durationMs", DICE_ROLL_MS + DICE_HOLD_MS)
            put("source", source.key)
        }

    /** Where the pips of [value] sit around the ring: as evenly spaced as eight LEDs allow. */
    fun pips(value: Int, n: Int = LED_COUNT): List<Int> = (0 until value.coerceIn(1, 6)).map { (it * n) / value.coerceIn(1, 6) }

    fun diceFrame(value: Int, seed: Long, t: Long, n: Int = LED_COUNT): IntArray {
        val out = IntArray(n)
        val rollMs = DICE_ROLL_MS.toLong()
        if (t < rollMs) {
            val x = t / rollMs.toDouble()
            val step = floor(16 * (1 - (1 - x) * (1 - x))).toLong()
            for (i in 0 until n) if (Renderer.noise(step + seed * 31, i) > 0.55) out[i] = DICE_COLOUR
        } else {
            pips(value, n).forEach { out[it] = DICE_COLOUR }
        }
        return out
    }

    // ------------------------------------------------------------------ beat

    fun beatAlert(id: Long, beatMs: Int, offsetMs: Long, durationMs: Int): JSONObject = JSONObject().apply {
        put("id", id)
        put("pattern", "beat")
        put("beatMs", beatMs)
        put("timeOffsetMs", offsetMs)
        put("colors", JSONArray().also { a -> BEAT_COLOURS.forEach { a.put(it.toUInt().toLong()) } })
        put("brightness", 1.0)
        put("durationMs", durationMs)
        put("source", AlertSource.PREVIEW.key)
    }

    fun beatFrame(beatMs: Int, elapsedMs: Long, n: Int = LED_COUNT): IntArray {
        val out = IntArray(n)
        val ms = beatMs.toLong().coerceIn(250, 2000)
        val beat = elapsedMs / ms
        val phase = (elapsedMs % ms) / ms.toDouble()
        val env = if (phase < 0.08) phase / 0.08 else if (phase < 0.7) exp(-(phase - 0.08) * 5) else 0.0
        val c = BEAT_COLOURS[(beat % BEAT_COLOURS.size).toInt()]
        val accent = (beat % n).toInt()
        for (i in 0 until n) out[i] = Renderer.scale(c, (if (i == accent) 1.0 else 0.45) * env)
        return out
    }

    // ------------------------------------------------------------------ demo reel

    data class Scene(@StringRes val nameRes: Int, val look: Ambient, val ms: Int = 4_500)

    /** A tour of the best effects; every scene opens with a short dark gap, as the renderer plays it. */
    val DEMO: List<Scene> = listOf(
        Scene(R.string.pattern_rainbow, Ambient(pattern = Pattern.RAINBOW, speedMs = 2_500, brightness = 1f)),
        Scene(R.string.pattern_aurora, Ambient(pattern = Pattern.AURORA, color = 0xFF00E676.toInt(), secondColor = 0xFF7C4DFF.toInt(), speedMs = 3_500, brightness = 1f)),
        Scene(R.string.pattern_comet, Ambient(pattern = Pattern.COMET, color = 0xFF00E5FF.toInt(), speedMs = 1_100, brightness = 1f)),
        Scene(R.string.pattern_marquee, Ambient(pattern = Pattern.MARQUEE, color = 0xFFFF4081.toInt(), secondColor = 0xFF40C4FF.toInt(), speedMs = 1_400, brightness = 1f)),
        Scene(R.string.pattern_converge, Ambient(pattern = Pattern.CONVERGE, color = 0xFFFFAB00.toInt(), speedMs = 1_500, brightness = 1f)),
        Scene(R.string.pattern_twinkle, Ambient(pattern = Pattern.TWINKLE, color = 0xFF40C4FF.toInt(), speedMs = 1_500, brightness = 1f)),
        Scene(R.string.pattern_candle, Ambient(pattern = Pattern.CANDLE, color = 0xFFFF8F00.toInt(), speedMs = 1_600, brightness = 1f)),
        Scene(R.string.pattern_crossfade, Ambient(pattern = Pattern.CROSSFADE, color = 0xFFFF6D00.toInt(), secondColor = 0xFFD500F9.toInt(), speedMs = 2_200, brightness = 1f)),
        Scene(R.string.pattern_radar, Ambient(pattern = Pattern.RADAR, color = 0xFF7C4DFF.toInt(), speedMs = 900, brightness = 1f)),
    )

    const val DEMO_GAP_MS = 200L
    const val DEMO_FADE_MS = 350.0

    val demoTotalMs: Int get() = DEMO.sumOf { it.ms }

    fun demoAlert(id: Long, scenes: List<Scene> = DEMO): JSONObject = JSONObject().apply {
        put("id", id)
        put("pattern", "demo")
        put("scenes", JSONArray().also { a -> scenes.forEach { a.put(it.look.toJson().put("sceneMs", it.ms)) } })
        put("brightness", 1.0)
        put("durationMs", scenes.sumOf { it.ms })
        put("source", AlertSource.PREVIEW.key)
    }

    /** The scene playing at [t], or null once the reel is over. */
    fun demoSceneAt(t: Long, scenes: List<Scene> = DEMO): Int? {
        var start = 0L
        scenes.forEachIndexed { index, scene ->
            if (t < start + scene.ms) return index
            start += scene.ms
        }
        return null
    }

    fun demoFrame(t: Long, scenes: List<Scene> = DEMO, n: Int = LED_COUNT): IntArray {
        val out = IntArray(n)
        var start = 0L
        for (scene in scenes) {
            val ms = scene.ms.toLong()
            if (t < start + ms) {
                val ts = t - start
                if (ts < DEMO_GAP_MS) return out
                val env = min(1.0, min((ts - DEMO_GAP_MS) / DEMO_FADE_MS, (ms - ts) / DEMO_FADE_MS))
                val f = Renderer.frame(scene.look.pattern, ts - DEMO_GAP_MS, scene.look)
                for (i in 0 until n) out[i] = Renderer.scale(f[i], env)
                return out
            }
            start += ms
        }
        return out
    }
}

/** What the on-screen strip needs to replay a party mode; [startedAtMs] is elapsed realtime. */
data class PartySession(
    val kind: Kind,
    val startedAtMs: Long,
    val totalMs: Int,
    val target: Int = 0,
    val turns: Int = 0,
    val value: Int = 0,
    val seed: Long = 0,
) {
    enum class Kind { SPIN, DICE, DEMO }

    fun frame(elapsedMs: Long): IntArray = when (kind) {
        Kind.SPIN -> PartyModes.spinFrame(target, turns, elapsedMs)
        Kind.DICE -> PartyModes.diceFrame(value, seed, elapsedMs)
        Kind.DEMO -> PartyModes.demoFrame(elapsedMs)
    }
}

/** A running tap-tempo session; [startedAtMs] (elapsed realtime) is beat one. */
data class BeatSession(val beatMs: Int, val startedAtMs: Long)

/**
 * Turns taps into a tempo: the median gap between the last few taps, so one sloppy tap does not throw
 * it off. A pause of more than two seconds starts a fresh count.
 */
class TapTempo {
    private val taps = ArrayDeque<Long>()

    /** Records a tap; returns the beat length in ms once there are at least three taps, else null. */
    fun tap(nowMs: Long): Int? {
        if (taps.isNotEmpty() && nowMs - taps.last() > RESET_MS) taps.clear()
        taps.addLast(nowMs)
        while (taps.size > MAX_TAPS) taps.removeFirst()
        return beatMs()
    }

    fun beatMs(): Int? {
        if (taps.size < 3) return null
        val gaps = taps.zipWithNext { a, b -> b - a }.sorted()
        val median = if (gaps.size % 2 == 1) gaps[gaps.size / 2].toDouble()
        else (gaps[gaps.size / 2 - 1] + gaps[gaps.size / 2]) / 2.0
        return median.toInt().coerceIn(MIN_BEAT_MS, MAX_BEAT_MS)
    }

    fun reset() = taps.clear()

    companion object {
        const val RESET_MS = 2_000L
        const val MAX_TAPS = 8
        /** 200 BPM at the fast end, 40 BPM at the slow end. */
        const val MIN_BEAT_MS = 300
        const val MAX_BEAT_MS = 1_500

        fun bpm(beatMs: Int): Int = Math.round(60_000f / beatMs)

        /** Halving or doubling the tempo, kept inside the supported range. */
        fun scaled(beatMs: Int, factor: Double): Int = (beatMs * factor).toInt().coerceIn(MIN_BEAT_MS, MAX_BEAT_MS)
    }
}
