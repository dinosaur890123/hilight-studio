package com.hilight.studio

import androidx.compose.ui.graphics.Color
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * On-screen mirror of the helper's renderer, so the UI can show what the LEDs will do without
 * touching the hardware. Kept deliberately in step with HiLightHelper.render().
 */
object Renderer {

    fun frame(pattern: Pattern, tMs: Long, cfg: Ambient, colorOverride: Int? = null): IntArray {
        val n = LED_COUNT
        val out = IntArray(n)
        val base = colorOverride ?: cfg.color
        val speed = max(60, cfg.speedMs).toLong()
        val t = tMs

        when (pattern) {
            Pattern.OFF -> Unit
            Pattern.SOLID -> for (i in 0 until n) out[i] = base
            Pattern.CUSTOM -> for (i in 0 until n) out[i] = cfg.perLed[i % cfg.perLed.size]
            Pattern.GRADIENT -> for (i in 0 until n)
                out[i] = mix(base, cfg.secondColor, i.toDouble() / (n - 1))

            Pattern.BREATHE -> {
                val phase = (t % speed) / speed.toDouble()
                val k = (1 - cos(phase * 2 * PI)) / 2
                for (i in 0 until n) out[i] = scale(base, 0.05 + 0.95 * k)
            }

            Pattern.BLINK -> {
                if ((t % speed) < speed / 2) for (i in 0 until n) out[i] = base
            }

            Pattern.PULSE -> {
                val phase = (t % speed) / speed.toDouble()
                val k = if (phase < 0.12) phase / 0.12 else exp(-(phase - 0.12) * 5)
                for (i in 0 until n) out[i] = scale(base, k)
            }

            Pattern.CHASE -> {
                val head = ((t / max(1, speed / n)) % n).toInt()
                for (i in 0 until n) out[i] = if (i == head) base else 0xFF000000.toInt()
            }

            Pattern.COMET -> {
                val pos = (t % speed) / speed.toDouble() * n
                for (i in 0 until n) {
                    var d = pos - i
                    if (d < 0) d += n
                    out[i] = scale(base, max(0.0, 1 - d / 3.0))
                }
            }

            Pattern.WAVE -> {
                val phase = (t % speed) / speed.toDouble()
                for (i in 0 until n) {
                    val k = (1 + sin(2 * PI * (phase + i.toDouble() / n))) / 2
                    out[i] = scale(base, 0.08 + 0.92 * k)
                }
            }

            Pattern.RAINBOW -> {
                val phase = (t % speed) / speed.toDouble()
                for (i in 0 until n) {
                    val h = (phase + if (cfg.rainbowSpread) i.toDouble() / n else 0.0) * 360.0
                    out[i] = hsvExact(h % 360, 1f, 1f)
                }
            }

            Pattern.METER -> {
                val phase = (t % speed) / speed.toDouble()
                if (phase < 0.75) {
                    val progress = (phase / 0.75) * n
                    val fullCount = floor(progress).toInt()
                    val partial = progress - fullCount
                    for (i in 0 until n) {
                        if (i < fullCount) {
                            out[i] = base
                        } else if (i == fullCount) {
                            out[i] = scale(base, partial)
                        } else {
                            out[i] = 0xFF000000.toInt()
                        }
                    }
                } else if (phase < 0.88) {
                    for (i in 0 until n) out[i] = base
                } else {
                    val fade = 1.0 - (phase - 0.88) / 0.12
                    for (i in 0 until n) out[i] = scale(base, fade)
                }
            }

            Pattern.STROBE -> {
                val phase = (t % speed) / speed.toDouble()
                if (phase < 0.45) {
                    val subPhase = (phase / 0.45) * 3.0
                    val frac = subPhase - floor(subPhase)
                    if (frac < 0.55) {
                        for (i in 0 until n) out[i] = base
                    }
                }
            }

            Pattern.HEARTBEAT -> {
                val phase = (t % speed) / speed.toDouble()
                val k = when {
                    phase < 0.06 -> (phase / 0.06) * 0.75
                    phase < 0.22 -> 0.75 * exp(-(phase - 0.06) * 16.0)
                    phase < 0.28 -> ((phase - 0.22) / 0.06)
                    phase < 0.60 -> exp(-(phase - 0.28) * 9.0)
                    else -> 0.0
                }
                for (i in 0 until n) out[i] = scale(base, k)
            }

            Pattern.BOUNCE -> {
                val phase = (t % speed) / speed.toDouble()
                val pos = (if (phase < 0.5) (phase * 2.0) else ((1.0 - phase) * 2.0)) * (n - 1)
                for (i in 0 until n) {
                    val dist = abs(pos - i)
                    out[i] = scale(base, max(0.0, 1.0 - dist / 1.5))
                }
            }

            Pattern.RADAR -> {
                val phase = (t % speed) / speed.toDouble()
                val head = phase * n
                for (i in 0 until n) {
                    var d = head - i
                    if (d < 0) d += n
                    out[i] = scale(base, exp(-d * 0.55))
                }
            }

            Pattern.CONVERGE -> {
                val phase = (t % speed) / speed.toDouble()
                val travel = if (phase < 0.5) (phase * 2.0) else ((1.0 - phase) * 2.0)
                val centerDist = (n - 1) / 2.0
                val p1 = travel * centerDist
                val p2 = (n - 1) - travel * centerDist
                val boost = if (travel > 0.85) (travel - 0.85) / 0.15 * 0.35 else 0.0
                for (i in 0 until n) {
                    val d1 = abs(p1 - i)
                    val d2 = abs(p2 - i)
                    val k = max(max(0.0, 1.0 - d1), max(0.0, 1.0 - d2)) + boost
                    out[i] = scale(base, min(1.0, k))
                }
            }

            Pattern.GLITCH -> {
                for (i in 0 until n) {
                    val seed = (i * 3 + 1) * 7
                    val ledPeriod = max(80L, speed / 2 + (seed % 5) * 80L)
                    val ledPhase = ((t + seed * 137L) % ledPeriod) / ledPeriod.toDouble()
                    val spike = if (ledPhase < 0.15) (ledPhase / 0.15) else exp(-(ledPhase - 0.15) * 12.0)
                    val jitter = if ((t / 40 + i * 5) % 3L == 0L && spike > 0.05) 0.3 else 0.0
                    val k = (spike * 0.85 + jitter).coerceIn(0.0, 1.0)
                    out[i] = scale(base, k)
                }
            }

            Pattern.AURORA -> {
                val b2 = cfg.secondColor
                val phase = (t % speed) / speed.toDouble()
                for (i in 0 until n) {
                    val m = (1 + sin(2 * PI * (phase + i.toDouble() / n))) / 2
                    val s = (1 + sin(2 * PI * 2 * phase + i * 1.3)) / 2
                    out[i] = scale(mix(base, b2, m), 0.35 + 0.65 * s)
                }
            }

            Pattern.CROSSFADE -> {
                val phase = (t % speed) / speed.toDouble()
                val c = mix(base, cfg.secondColor, (1 - cos(phase * 2 * PI)) / 2)
                for (i in 0 until n) out[i] = c
            }

            Pattern.MARQUEE -> {
                val phase = (t % speed) / speed.toDouble()
                for (i in 0 until n) {
                    val m = (0.5 + 1.2 * cos(2 * PI * (i.toDouble() / MARQUEE_BAND - phase))).coerceIn(0.0, 1.0)
                    out[i] = mix(cfg.secondColor, base, m)
                }
            }

            Pattern.TWINKLE -> {
                for (i in 0 until n) {
                    val period = max(120L, speed * (5 + (i * 3) % 4) / 6)
                    val offset = speed * ((i * 5) % 8) / 8
                    val ledPhase = ((t + offset) % period) / period.toDouble()
                    val k = if (ledPhase < 0.1) ledPhase / 0.1 else exp(-(ledPhase - 0.1) * 6.0)
                    val c = scale(base, 0.04 + 0.96 * k)
                    out[i] = if (k > 0.8) mix(c, 0xFFFFFFFF.toInt(), (k - 0.8) / 0.2 * 0.6) else c
                }
            }

            Pattern.CANDLE -> {
                val seg = max(40L, speed / 6)
                val step = t / seg
                val f = (t % seg) / seg.toDouble()
                val ease = f * f * (3 - 2 * f)
                val shared = noise(step, n) + (noise(step + 1, n) - noise(step, n)) * ease
                for (i in 0 until n) {
                    val own = noise(step, i) + (noise(step + 1, i) - noise(step, i)) * ease
                    out[i] = scale(base, 0.4 + 0.6 * (0.65 * shared + 0.35 * own))
                }
            }

            Pattern.PLASMA -> {
                val phase = (t % speed) / speed.toDouble()
                val len = PartyModes.WHEEL.size
                for (i in 0 until n) {
                    val x = i / n.toDouble()
                    val v = sin(2 * PI * (x + phase)) +
                        sin(2 * PI * (2 * x - phase)) +
                        sin(2 * PI * (3 * x + 2 * phase))
                    out[i] = wheelAt((v + 3) / 6 * len + phase * len)
                }
            }

            Pattern.ORBIT -> {
                val phase = (t % speed) / speed.toDouble()
                val p1 = phase * n
                val p2 = (1 - phase) * n
                for (i in 0 until n) {
                    var d1 = p1 - i
                    if (d1 < 0) d1 += n
                    var d2 = i - p2
                    while (d2 < 0) d2 += n
                    while (d2 >= n) d2 -= n
                    out[i] = add(scale(base, max(0.0, 1 - d1 / 3.0)), scale(cfg.secondColor, max(0.0, 1 - d2 / 3.0)))
                }
            }

            Pattern.FIREWORKS -> {
                val cycle = t / speed
                val phase = (t % speed) / speed.toDouble()
                val wheel = PartyModes.WHEEL
                val centre = floor(noise(cycle, 99) * n).toInt() % n
                val c = wheel[floor(noise(cycle, 98) * wheel.size).toInt() % wheel.size]
                val bright = mix(c, 0xFFFFFFFF.toInt(), 0.5)
                if (phase < 0.2) {
                    out[centre] = scale(bright, phase / 0.2 * 0.6)
                } else if (phase < 0.3) {
                    val reach = (phase - 0.2) / 0.1 * (n / 2.0)
                    for (i in 0 until n) if (ringDistance(i, centre, n) <= reach) out[i] = bright
                } else {
                    val fade = 1 - (phase - 0.3) / 0.7
                    val step = t / 70
                    for (i in 0 until n) {
                        val sparkle = if (noise(step, i) > 0.4) 1.0 else 0.35
                        out[i] = scale(c, fade * fade * sparkle)
                    }
                }
            }

            Pattern.RANDOM -> {
                // deterministic stand-in so the preview animates without flickering randomly
                val step = t / max(120, cfg.randomIntervalMs).toLong()
                for (i in 0 until n) {
                    val seed = if (cfg.randomPerLed) step * 31 + i else step
                    out[i] = hsv(((seed * 47) % 360).toFloat(), cfg.randomSaturation)
                }
            }
        }

        val b = cfg.brightness.toDouble()
        if (b < 1.0) for (i in 0 until n) out[i] = scale(out[i], b)
        return out
    }

    /** Mirrors the renderer's wheel-palette lookup, blending between neighbouring colours. */
    fun wheelAt(u: Double): Int {
        val wheel = PartyModes.WHEEL
        val len = wheel.size
        val w = ((u % len) + len) % len
        val i = floor(w).toInt() % len
        return mix(wheel[i], wheel[(i + 1) % len], w - floor(w))
    }

    /** Mirrors the renderer's additive blend: channels add and saturate. */
    fun add(a: Int, b: Int): Int {
        val r = min(255, ((a shr 16) and 0xFF) + ((b shr 16) and 0xFF))
        val g = min(255, ((a shr 8) and 0xFF) + ((b shr 8) and 0xFF))
        val bl = min(255, (a and 0xFF) + (b and 0xFF))
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }

    fun ringDistance(i: Int, centre: Int, n: Int): Double {
        val d = abs(i - centre)
        return min(d, n - d).toDouble()
    }

    /** Mirrors the renderer's marquee band width. */
    private const val MARQUEE_BAND = 4

    /** Bit-for-bit mirror of the renderer's value noise, so the preview flickers like the LEDs. */
    internal fun noise(step: Long, lane: Int): Double {
        var x = step * 0x2545F4914F6CDD1DL + lane.toLong() * 0x5851F42D4C957F2DL
        x = x xor (x ushr 31)
        x *= 0x27BB2EE687B0B0FDL
        x = x xor (x ushr 29)
        return (x ushr 11).toDouble() / (1L shl 53).toDouble()
    }

    fun scale(color: Int, k: Double): Int {
        val kk = k.coerceIn(0.0, 1.0)
        val r = (((color shr 16) and 0xFF) * kk).toInt()
        val g = (((color shr 8) and 0xFF) * kk).toInt()
        val b = ((color and 0xFF) * kk).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun mix(a: Int, b: Int, k: Double): Int {
        val kk = k.coerceIn(0.0, 1.0)
        val r = (((a shr 16) and 0xFF) * (1 - kk) + ((b shr 16) and 0xFF) * kk).toInt()
        val g = (((a shr 8) and 0xFF) * (1 - kk) + ((b shr 8) and 0xFF) * kk).toInt()
        val bl = ((a and 0xFF) * (1 - kk) + (b and 0xFF) * kk).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }

    /** The renderer's own double-precision HSV, so the rainbow on screen matches the LEDs exactly. */
    fun hsvExact(h: Double, s: Float, v: Float): Int {
        val c = (v * s).toDouble()
        val x = c * (1 - abs((h / 60) % 2 - 1))
        val m = v.toDouble() - c
        val (r, g, b) = when ((h / 60).toInt() % 6) {
            0 -> Triple(c, x, 0.0)
            1 -> Triple(x, c, 0.0)
            2 -> Triple(0.0, c, x)
            3 -> Triple(0.0, x, c)
            4 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }
        return (0xFF shl 24) or
            (((r + m) * 255).toInt() shl 16) or
            (((g + m) * 255).toInt() shl 8) or
            ((b + m) * 255).toInt()
    }

    fun hsv(h: Float, s: Float = 1f, v: Float = 1f): Int {
        val c = v * s
        val x = c * (1 - abs((h / 60f) % 2 - 1))
        val m = v - c
        val (r, g, b) = when (((h / 60).toInt()) % 6) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return (0xFF shl 24) or
            (((r + m) * 255).toInt() shl 16) or
            (((g + m) * 255).toInt() shl 8) or
            ((b + m) * 255).toInt()
    }
}
