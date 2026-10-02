package com.hilight.studio

import androidx.annotation.StringRes
import kotlin.random.Random

/**
 * A ready-made look that ships with the app.
 *
 * Featured looks are not stored with the user's presets: they are rebuilt from code on every launch,
 * so a later release can tune one without touching anything the user saved. Applying one copies the
 * look, and saving it afterwards makes an ordinary preset the user owns.
 */
data class FeaturedLook(val key: String, @StringRes val nameRes: Int, val ambient: Ambient)

object Looks {

    /** Curated starting points, one per mood, each showing off a different effect. */
    val featured: List<FeaturedLook> = listOf(
        FeaturedLook(
            "aurora", R.string.look_aurora,
            Ambient(pattern = Pattern.AURORA, color = 0xFF00E676.toInt(), secondColor = 0xFF7C4DFF.toInt(), speedMs = 6000),
        ),
        FeaturedLook(
            "candlelight", R.string.look_candlelight,
            Ambient(pattern = Pattern.CANDLE, color = 0xFFFF8F00.toInt(), speedMs = 1800),
        ),
        FeaturedLook(
            "starfield", R.string.look_starfield,
            Ambient(pattern = Pattern.TWINKLE, color = 0xFF40C4FF.toInt(), speedMs = 2400),
        ),
        FeaturedLook(
            "sunset", R.string.look_sunset,
            Ambient(pattern = Pattern.CROSSFADE, color = 0xFFFF6D00.toInt(), secondColor = 0xFFD500F9.toInt(), speedMs = 5000),
        ),
        FeaturedLook(
            "candy", R.string.look_candy,
            Ambient(pattern = Pattern.MARQUEE, color = 0xFFFF4081.toInt(), secondColor = 0xFF40C4FF.toInt(), speedMs = 1600),
        ),
        FeaturedLook(
            "ocean", R.string.look_ocean,
            Ambient(pattern = Pattern.WAVE, color = 0xFF00B8D4.toInt(), speedMs = 4000),
        ),
        FeaturedLook(
            "ember", R.string.look_ember,
            Ambient(pattern = Pattern.AURORA, color = 0xFFFF1744.toInt(), secondColor = 0xFFFFAB00.toInt(), speedMs = 4500),
        ),
        FeaturedLook(
            "calm", R.string.look_calm,
            Ambient(pattern = Pattern.BREATHE, color = 0xFF7C4DFF.toInt(), speedMs = 5000),
        ),
        FeaturedLook(
            "nebula", R.string.look_nebula,
            Ambient(pattern = Pattern.PLASMA, speedMs = 5000),
        ),
        FeaturedLook(
            "fireworks", R.string.look_fireworks,
            Ambient(pattern = Pattern.FIREWORKS, speedMs = 1800),
        ),
        FeaturedLook(
            "orbit", R.string.look_orbit,
            Ambient(pattern = Pattern.ORBIT, color = 0xFF00E5FF.toInt(), secondColor = 0xFFFF4081.toInt(), speedMs = 1800),
        ),
        FeaturedLook(
            "prism", R.string.look_prism,
            Ambient(pattern = Pattern.RAINBOW, speedMs = 3000),
        ),
    )

    /** Effects "Surprise me" draws from: the ones that look good in any colour. */
    private val surprisePatterns = listOf(
        Pattern.AURORA, Pattern.CROSSFADE, Pattern.MARQUEE, Pattern.TWINKLE, Pattern.CANDLE,
        Pattern.BREATHE, Pattern.WAVE, Pattern.COMET, Pattern.RADAR, Pattern.GRADIENT,
        Pattern.ORBIT, Pattern.PLASMA, Pattern.FIREWORKS,
    )

    /**
     * A random, harmonious look.
     *
     * The second colour is always a pleasing relative of the first rather than another random hue,
     * and brightness is left where the user set it — a surprise should never be a brighter one.
     */
    fun surprise(current: Ambient, random: Random = Random.Default): Ambient {
        val pattern = surprisePatterns.filter { it != current.pattern }.random(random)
        val hue = random.nextInt(360).toFloat()
        val first = Renderer.hsv(hue, 0.85f + random.nextFloat() * 0.15f, 1f)
        val second = when (random.nextInt(3)) {
            0 -> Harmony.COMPLEMENT.from(first)
            1 -> Harmony.TRIAD.from(first)
            else -> Harmony.ANALOGOUS.from(first)
        }
        return current.copy(
            pattern = pattern,
            color = first,
            secondColor = second,
            speedMs = when (pattern) {
                Pattern.TWINKLE, Pattern.CANDLE, Pattern.MARQUEE, Pattern.ORBIT, Pattern.FIREWORKS -> 1200 + random.nextInt(1800)
                else -> 2500 + random.nextInt(4000)
            },
        )
    }

    /** Colours that sit well together, derived by rotating hue and keeping saturation and value. */
    enum class Harmony(@StringRes val labelRes: Int, private val degrees: Float) {
        COMPLEMENT(R.string.harmony_complement, 180f),
        ANALOGOUS(R.string.harmony_analogous, 35f),
        TRIAD(R.string.harmony_triad, 120f);

        fun from(color: Int): Int = rotateHue(color, degrees)
    }

    /** Rotates a colour's hue, keeping its saturation and value; pure maths so host tests can run it. */
    fun rotateHue(color: Int, degrees: Float): Int {
        val (h, s, v) = toHsv(color)
        return Renderer.hsv(((h + degrees) % 360f + 360f) % 360f, s, v)
    }

    internal fun toHsv(color: Int): Triple<Float, Float, Float> {
        val r = ((color shr 16) and 0xFF) / 255f
        val g = ((color shr 8) and 0xFF) / 255f
        val b = (color and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val d = max - min
        val h = when {
            d == 0f -> 0f
            max == r -> 60f * (((g - b) / d) % 6f)
            max == g -> 60f * (((b - r) / d) + 2f)
            else -> 60f * (((r - g) / d) + 4f)
        }
        return Triple((h + 360f) % 360f, if (max == 0f) 0f else d / max, max)
    }

    /**
     * Up to four colours that summarise a look for a chip, so a preset can be recognised at a glance
     * without animating a strip for every one of them.
     */
    fun swatches(look: Ambient): List<Int> = when (look.pattern) {
        Pattern.OFF -> emptyList()
        Pattern.RAINBOW -> listOf(0f, 90f, 180f, 270f).map { Renderer.hsv(it) }
        Pattern.PLASMA, Pattern.FIREWORKS -> listOf(0, 2, 4, 6).map { PartyModes.WHEEL[it] }
        Pattern.RANDOM -> listOf(30f, 150f, 270f).map { Renderer.hsv(it, look.randomSaturation) }
        Pattern.CUSTOM -> look.perLed.distinct().take(4)
        else -> if (look.pattern.usesSecondColor) {
            listOf(look.color, look.secondColor).distinct()
        } else {
            listOf(look.color)
        }
    }

    /** Presets arrive from import too, so names are trimmed and bounded before they are stored. */
    fun cleanName(name: String): String = name.trim().take(MAX_NAME_LENGTH)

    const val MAX_NAME_LENGTH = 40
}

/** List edits behind the presets card, kept free of Android so host tests can pin them down. */
object PresetOps {

    /** Null when [newName] is empty or taken by a different preset; names are presets' identity. */
    fun rename(list: List<Preset>, preset: Preset, newName: String): List<Preset>? {
        val clean = Looks.cleanName(newName)
        if (clean.isEmpty()) return null
        if (clean == preset.name) return list
        if (list.any { it.name == clean }) return null
        return list.map { if (it.name == preset.name) it.copy(name = clean) else it }
    }

    fun update(list: List<Preset>, preset: Preset, look: Ambient): List<Preset> =
        list.map { if (it.name == preset.name) it.copy(ambient = look) else it }

    fun move(list: List<Preset>, preset: Preset, delta: Int): List<Preset> {
        val from = list.indexOfFirst { it.name == preset.name }
        if (from < 0) return list
        val to = (from + delta).coerceIn(0, list.lastIndex)
        if (to == from) return list
        return list.toMutableList().apply { add(to, removeAt(from)) }
    }
}
