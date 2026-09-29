package com.hilight.studio

import androidx.annotation.StringRes
import org.json.JSONArray
import org.json.JSONObject

/** A breathing rhythm. Every rhythm ends in a dark rest, which also lets the brightness taper reset. */
enum class BreathingRhythm(
    val key: String,
    @StringRes val labelRes: Int,
    val inhaleMs: Int,
    val holdMs: Int,
    val exhaleMs: Int,
    val restMs: Int,
) {
    BOX("box", R.string.breath_box, 4_000, 4_000, 4_000, 4_000),
    RELAX("relax", R.string.breath_relax, 4_000, 7_000, 8_000, 1_000),
    CALM("calm", R.string.breath_calm, 5_000, 0, 5_000, 1_000);

    val cycleMs: Int get() = inhaleMs + holdMs + exhaleMs + restMs

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key } ?: BOX
    }
}

/** A running breathing session; [startedAtMs] is on the elapsed-realtime clock. */
data class BreathingSession(val rhythm: BreathingRhythm, val startedAtMs: Long, val totalMs: Int)

enum class BreathPhase(@StringRes val labelRes: Int) {
    INHALE(R.string.breath_in),
    HOLD(R.string.breath_hold),
    EXHALE(R.string.breath_out),
    REST(R.string.breath_rest),
}

/**
 * The breathing guide: the ring fills LED by LED while you breathe in, stays full while you hold,
 * empties (easing towards the second colour) while you breathe out, then rests dark.
 *
 * The renderer draws it itself (mode "breathing") so it stays smooth at the LEDs' own frame rate; this
 * object mirrors that maths for the on-screen strip and builds the alert documents. A session longer
 * than one alert may last is sent as overlapping segments, each carrying the time already elapsed, so
 * the rhythm carries on across them without a jump.
 */
object BreathingGuide {

    val INHALE_COLOUR: Int = 0xFF26C6DA.toInt()
    val EXHALE_COLOUR: Int = 0xFF7C4DFF.toInt()
    const val BRIGHTNESS = 0.7f

    val SESSION_CHOICES_MS = listOf(60_000, 120_000, 180_000)

    /** Each segment is sent this often; its alert runs [SEGMENT_OVERLAP_MS] longer so none can lapse. */
    const val SEGMENT_MS = 50_000
    const val SEGMENT_OVERLAP_MS = 3_000

    /** Which phase [elapsedMs] falls in, and how many milliseconds of it are left. */
    fun phaseAt(rhythm: BreathingRhythm, elapsedMs: Long): Pair<BreathPhase, Long> {
        val p = Math.floorMod(elapsedMs, rhythm.cycleMs.toLong())
        var edge = rhythm.inhaleMs.toLong()
        if (p < edge) return BreathPhase.INHALE to edge - p
        edge += rhythm.holdMs
        if (p < edge) return BreathPhase.HOLD to edge - p
        edge += rhythm.exhaleMs
        if (p < edge) return BreathPhase.EXHALE to edge - p
        return BreathPhase.REST to rhythm.cycleMs - p
    }

    /** Renderer-equivalent frame, before brightness, for the on-screen strip. */
    fun frame(rhythm: BreathingRhythm, elapsedMs: Long, n: Int = LED_COUNT): IntArray {
        // Zero, not opaque black, for the dark LEDs: the renderer starts from an empty frame too.
        val out = IntArray(n)
        val inhale = rhythm.inhaleMs.toLong()
        val hold = rhythm.holdMs.toLong()
        val exhale = rhythm.exhaleMs.toLong()
        val p = Math.floorMod(elapsedMs, rhythm.cycleMs.toLong())
        if (p < inhale) {
            fill(out, INHALE_COLOUR, ease(p / inhale.toDouble()) * n)
        } else if (p < inhale + hold) {
            for (i in 0 until n) out[i] = INHALE_COLOUR
        } else if (p < inhale + hold + exhale) {
            val e = ease((p - inhale - hold) / exhale.toDouble())
            fill(out, Renderer.mix(INHALE_COLOUR, EXHALE_COLOUR, e), (1 - e) * n)
        }
        return out
    }

    private fun ease(f: Double): Double = f * f * (3 - 2 * f)

    private fun fill(out: IntArray, colour: Int, level: Double) {
        for (i in out.indices) out[i] = Renderer.scale(colour, (level - i).coerceIn(0.0, 1.0))
    }

    /** The alert document for one segment, starting [offsetMs] into the session. */
    fun alert(id: Long, rhythm: BreathingRhythm, offsetMs: Long, durationMs: Int): JSONObject =
        JSONObject().apply {
            put("id", id)
            put("pattern", "breathing")
            put("colors", JSONArray().put(INHALE_COLOUR.toUInt().toLong()).put(EXHALE_COLOUR.toUInt().toLong()))
            put("inhaleMs", rhythm.inhaleMs)
            put("holdMs", rhythm.holdMs)
            put("exhaleMs", rhythm.exhaleMs)
            put("restMs", rhythm.restMs)
            put("timeOffsetMs", offsetMs)
            put("brightness", BRIGHTNESS.toDouble())
            put("durationMs", durationMs)
            put("source", AlertSource.PREVIEW.key)
        }

    /**
     * The segments of a session: (offset, alert duration, time until the next segment is sent). The
     * last segment runs exactly to the end of the session and schedules nothing.
     */
    fun segments(totalMs: Int): List<Triple<Long, Int, Int?>> {
        val out = mutableListOf<Triple<Long, Int, Int?>>()
        var offset = 0L
        while (offset < totalMs) {
            val remaining = (totalMs - offset).toInt()
            if (remaining <= SEGMENT_MS + SEGMENT_OVERLAP_MS) {
                out += Triple(offset, remaining, null)
                break
            }
            out += Triple(offset, SEGMENT_MS + SEGMENT_OVERLAP_MS, SEGMENT_MS)
            offset += SEGMENT_MS
        }
        return out
    }
}
