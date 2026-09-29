package com.hilight.studio

import org.json.JSONArray
import org.json.JSONObject

/**
 * The "waiting apps" glow: when a notification arrives, one soft glow shows every app still waiting,
 * each in its own section of the array in its rule's colour, instead of only the newest one.
 *
 * Everything here is pure so the choices it makes (which apps, in which order, which LED gets which
 * colour) can be pinned down by host tests. [NotificationTrigger] gathers the notifications and
 * [Store.fireWaitingGlow] applies the usual guards before anything reaches the renderer.
 */
object WaitingApps {

    /** Sections beyond this get narrower than two LEDs, and a one-LED section is hard to read. */
    const val MAX_SECTIONS = 4

    const val DEFAULT_DURATION_MS = 5_000
    val DURATION_CHOICES = listOf(3_000, 5_000, 8_000)

    /**
     * One notification that is still waiting.
     *
     * Sections are per rule rather than per package, so a per-contact rule gets its own section and the
     * catch-all rule is one "everything else" section. [pkg] is the newest app behind the rule, used
     * when the rule takes its colour from the app icon.
     */
    data class Entry(val ruleId: String, val pkg: String, val postedAtMs: Long)

    /**
     * The rules to show, in the user's rule order so each app keeps its place from one glow to the next.
     *
     * At most [MAX_SECTIONS]; when more are waiting the earliest rules win, except that the rule behind
     * the notification that just arrived always keeps a section. For each rule the newest entry is kept.
     */
    fun pick(entries: List<Entry>, ruleOrder: List<String>, newestRuleId: String): List<Entry> {
        val rank = ruleOrder.withIndex().associate { (i, id) -> id to i }
        val perRule = entries
            .groupBy { it.ruleId }
            .map { (_, group) -> group.maxBy { it.postedAtMs } }
            .sortedWith(compareBy({ rank[it.ruleId] ?: Int.MAX_VALUE }, { it.ruleId }))
        if (perRule.size <= MAX_SECTIONS) return perRule
        val first = perRule.take(MAX_SECTIONS)
        if (first.any { it.ruleId == newestRuleId }) return first
        val newest = perRule.firstOrNull { it.ruleId == newestRuleId } ?: return first
        return (perRule.take(MAX_SECTIONS - 1) + newest)
            .sortedWith(compareBy({ rank[it.ruleId] ?: Int.MAX_VALUE }, { it.ruleId }))
    }

    /**
     * One colour per LED: equal sections, each softly blended into the next.
     *
     * Each section is solid around its centre and eases into its neighbour over the middle half of the
     * gap between centres, so the array reads as distinct bands joined by short gradients. With four
     * sections the centres sit exactly two LEDs apart and the bands stay crisp, which is what keeps four
     * apps readable.
     */
    fun ledColours(sections: List<Int>, n: Int = LED_COUNT): List<Int> {
        if (sections.isEmpty()) return List(n) { 0xFF000000.toInt() }
        if (sections.size == 1) return List(n) { sections[0] }
        val width = n.toDouble() / sections.size
        return List(n) { i ->
            val x = i + 0.5
            val k = ((x - width / 2) / width).coerceIn(0.0, sections.size - 1.0)
            val left = k.toInt().coerceAtMost(sections.size - 2)
            val t = k - left
            val eased = ((t - 0.25) / 0.5).coerceIn(0.0, 1.0).let { it * it * (3 - 2 * it) }
            Renderer.mix(sections[left], sections[left + 1], eased)
        }
    }

    /**
     * The alert document for one glow.
     *
     * It is a single Breathe whose cycle is the whole duration: the array rises from a dim glow to full
     * and back down once, then the alert ends. The renderer already gives Breathe one colour per LED
     * when it is handed a list, so no new renderer code is involved.
     */
    fun glowAlert(
        id: Long,
        leds: List<Int>,
        durationMs: Int,
        brightness: Float,
        source: AlertSource = AlertSource.NOTIFICATION,
    ): JSONObject =
        JSONObject().apply {
            put("id", id)
            put("pattern", Pattern.BREATHE.key)
            put("colors", JSONArray().also { a -> leds.forEach { a.put(it.toUInt().toLong()) } })
            put("speedMs", durationMs)
            put("brightness", brightness.coerceIn(0.05f, 1f).toDouble())
            put("durationMs", durationMs)
            put("source", source.key)
        }

    /**
     * True when two section colours are close enough to be mistaken for each other on the LEDs, so the
     * settings card can say which rules to recolour.
     */
    fun tooSimilar(a: Int, b: Int): Boolean {
        val (ha, sa, va) = Looks.toHsv(a)
        val (hb, sb, vb) = Looks.toHsv(b)
        // Near-greys are told apart by value alone; everything else mainly by hue.
        if (sa < 0.25f && sb < 0.25f) return kotlin.math.abs(va - vb) < 0.2f
        if (sa < 0.25f || sb < 0.25f) return false
        val dh = kotlin.math.abs(ha - hb).let { minOf(it, 360f - it) }
        return dh < 25f
    }

    /** The same sections as a static per-LED look, for on-screen previews of the glow. */
    fun previewLook(sections: List<Int>): Ambient =
        Ambient(pattern = Pattern.CUSTOM, perLed = ledColours(sections), brightness = 1f)

    fun safeDurationMs(ms: Int): Int = ms.coerceIn(DURATION_CHOICES.first(), DURATION_CHOICES.last())
}
