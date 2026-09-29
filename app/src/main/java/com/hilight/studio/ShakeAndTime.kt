package com.hilight.studio

import kotlin.math.sqrt

/**
 * Recognises a deliberate shake from raw accelerometer samples.
 *
 * A shake is [PEAKS] strong jolts (over [THRESHOLD_G] of total acceleration) inside [WINDOW_MS],
 * each at least [MIN_GAP_MS] apart so one jolt is not counted twice. Walking, a bump on a table or
 * putting the phone down produce one or two peaks at most. After a shake the detector is deaf for
 * [COOLDOWN_MS], so one long shake is one burst of sparkles.
 */
class ShakeDetector {
    private val peaks = ArrayDeque<Long>()
    private var quietUntilMs = Long.MIN_VALUE
    private var above = false

    /** Feeds one sample in m/s²; returns true exactly when a shake completes. */
    fun onSample(x: Float, y: Float, z: Float, nowMs: Long): Boolean {
        val g = sqrt(x * x + y * y + z * z) / EARTH_G
        // A peak is a crossing into the strong band, so one long push counts once however many
        // samples it spans.
        val rising = g >= THRESHOLD_G && !above
        above = g >= THRESHOLD_G
        if (nowMs < quietUntilMs || !rising) return false
        if (peaks.isNotEmpty() && nowMs - peaks.last() < MIN_GAP_MS) return false
        peaks.addLast(nowMs)
        while (peaks.isNotEmpty() && nowMs - peaks.first() > WINDOW_MS) peaks.removeFirst()
        if (peaks.size < PEAKS) return false
        peaks.clear()
        quietUntilMs = nowMs + COOLDOWN_MS
        return true
    }

    fun reset() {
        peaks.clear()
        quietUntilMs = Long.MIN_VALUE
        above = false
    }

    companion object {
        const val EARTH_G = 9.80665f
        const val THRESHOLD_G = 2.3f
        const val PEAKS = 3
        const val WINDOW_MS = 1_000L
        const val MIN_GAP_MS = 90L
        const val COOLDOWN_MS = 4_000L

        const val SPARKLE_MS = 2_500
        const val OWNER = "shake"

        /** A bright, random-hued Twinkle: every shake sparkles a little differently. */
        fun sparkleLook(hue: Float): Ambient = Ambient(
            pattern = Pattern.TWINKLE,
            color = Renderer.hsv(((hue % 360f) + 360f) % 360f, 0.55f, 1f),
            speedMs = 900,
            brightness = 1f,
        )
    }
}

/**
 * Colours that follow the day: deep indigo at night, rose at dawn, sky blue in the morning, warm
 * white at midday, gold then red at sunset, violet at dusk. Each keyframe is a pair, so two-colour
 * effects get a matching second colour; between keyframes the colours blend minute by minute.
 */
object TimeOfDay {

    data class Keyframe(val minute: Int, val first: Int, val second: Int)

    val keyframes = listOf(
        Keyframe(0, 0xFF1A237E.toInt(), 0xFF4A148C.toInt()),        // midnight: indigo and plum
        Keyframe(5 * 60 + 30, 0xFFFF6E7F.toInt(), 0xFFFFB74D.toInt()), // dawn: rose and apricot
        Keyframe(8 * 60, 0xFF40C4FF.toInt(), 0xFF80D8FF.toInt()),      // morning: sky blues
        Keyframe(12 * 60, 0xFFFFE57F.toInt(), 0xFF40C4FF.toInt()),     // midday: warm white and sky
        Keyframe(17 * 60, 0xFFFFAB00.toInt(), 0xFFFF6D00.toInt()),     // golden hour
        Keyframe(19 * 60 + 30, 0xFFFF3D00.toInt(), 0xFFD500F9.toInt()), // sunset: red and magenta
        Keyframe(21 * 60 + 30, 0xFF7C4DFF.toInt(), 0xFF304FFE.toInt()), // dusk: violet and blue
    )

    /** The colour pair for [minuteOfDay] (0 until 1440), blended between the surrounding keyframes. */
    fun colours(minuteOfDay: Int): Pair<Int, Int> {
        val m = Math.floorMod(minuteOfDay, DAY)
        val nextIndex = keyframes.indexOfFirst { it.minute > m }
        val before = if (nextIndex <= 0) keyframes.last() else keyframes[nextIndex - 1]
        val after = if (nextIndex < 0) keyframes.first() else keyframes[nextIndex]
        val span = Math.floorMod(after.minute - before.minute, DAY).takeIf { it > 0 } ?: DAY
        val t = Math.floorMod(m - before.minute, DAY) / span.toDouble()
        return Renderer.mix(before.first, after.first, t) to Renderer.mix(before.second, after.second, t)
    }

    /** Effects with colours of their own (rainbow, random, per-LED) are left exactly as they are. */
    fun appliesTo(pattern: Pattern): Boolean =
        pattern !in setOf(Pattern.OFF, Pattern.RAINBOW, Pattern.RANDOM, Pattern.CUSTOM)

    fun apply(look: Ambient, minuteOfDay: Int): Ambient {
        if (!appliesTo(look.pattern)) return look
        val (first, second) = colours(minuteOfDay)
        return look.copy(color = first, secondColor = second)
    }

    const val DAY = 24 * 60
}
