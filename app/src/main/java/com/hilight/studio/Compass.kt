package com.hilight.studio

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Compass mode: with the phone lying screen-down, the LED that points north glows red and the one
 * opposite glows faintly white, like a needle.
 *
 * Geometry. The heading is the azimuth of the phone's top edge, clockwise from magnetic north as seen
 * from above. Seen from above, a screen-down phone shows its back, and the app assumes (as its device
 * drawing does) that LED 1 sits at the top of the ring and the rest follow clockwise when looking at
 * the back. That order has not been confirmed on hardware, so [Calibration] can rotate the ring and
 * reverse its direction, and the user fixes it once by eye.
 *
 * Everything here is pure; the sensor and the bridge live in the screen that runs the session.
 */
object Compass {

    /** One compass session lasts at most as long as the renderer lets any single effect run. */
    const val SESSION_MS = 60_000

    /** The needle is resent only when it has moved by at least a quarter of an LED. */
    const val STEPS_PER_LED = 4

    val NEEDLE: Int = 0xFFFF1744.toInt()
    val TAIL: Int = 0xFFFFFFFF.toInt()
    const val TAIL_LEVEL = 0.18

    data class Calibration(val offsetSteps: Int = 0, val reversed: Boolean = false) {
        fun nudge(delta: Int) = copy(offsetSteps = Math.floorMod(offsetSteps + delta, LED_COUNT))
    }

    /** Where north falls around the ring, in LED positions from LED 1, in [0, n). */
    fun northPosition(headingDeg: Double, calibration: Calibration, n: Int = LED_COUNT): Double {
        // North sits at -heading clockwise from the phone's top, seen from the back.
        val steps = -headingDeg / 360.0 * n
        val signed = if (calibration.reversed) -steps else steps
        return wrap(signed + calibration.offsetSteps, n)
    }

    /** Snaps a position to the resend grid so small jitter does not flood the bridge. */
    fun quantize(position: Double, n: Int = LED_COUNT): Double =
        wrap((position * STEPS_PER_LED).roundToInt().toDouble() / STEPS_PER_LED, n)

    /** One colour per LED: the red needle split across the two nearest LEDs, the faint tail opposite. */
    fun frame(position: Double, n: Int = LED_COUNT): List<Int> {
        val out = MutableList(n) { 0xFF000000.toInt() }
        paint(out, wrap(position + n / 2.0, n), TAIL, TAIL_LEVEL)
        paint(out, position, NEEDLE, 1.0)
        return out
    }

    private fun paint(out: MutableList<Int>, position: Double, colour: Int, level: Double) {
        val n = out.size
        val first = floor(position).toInt()
        val f = position - first
        out[Math.floorMod(first, n)] = Renderer.scale(colour, level * (1 - f))
        if (f > 0.0) out[Math.floorMod(first + 1, n)] = Renderer.scale(colour, level * f)
    }

    /** Eight-way direction the phone's top points to, 0 = N, 1 = NE … 7 = NW. */
    fun octant(headingDeg: Double): Int = Math.floorMod((wrap(headingDeg, 360) / 45.0).roundToInt(), 8)

    /**
     * Circular low-pass filter for headings, so the needle does not twitch and never takes the long
     * way round when the reading crosses north.
     */
    class Smoother(private val alpha: Double = 0.25) {
        private var s = 0.0
        private var c = 0.0
        private var primed = false

        fun update(headingDeg: Double): Double {
            val r = Math.toRadians(headingDeg)
            if (!primed) {
                s = sin(r); c = cos(r); primed = true
            } else {
                s += alpha * (sin(r) - s)
                c += alpha * (cos(r) - c)
            }
            return wrap(Math.toDegrees(atan2(s, c)), 360)
        }
    }

    private fun wrap(v: Double, n: Int): Double = ((v % n) + n) % n
}
