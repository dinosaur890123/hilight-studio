package com.hilight.studio

/**
 * The LED map: for each position around the ring (0 at the top, then clockwise as seen looking at
 * the back of the phone), the hardware LED that actually sits there.
 *
 * The renderer applies it to every frame, so with a map in place every effect, the compass and the
 * per-LED colours travel around the ring in the order the drawing shows. Without one, output is
 * exactly as before. Stored as a comma-separated list; anything but a complete permutation reads as
 * "not mapped".
 */
object LedMap {

    fun encode(order: List<Int>): String = order.joinToString(",")

    fun decode(raw: String?, n: Int = LED_COUNT): List<Int>? {
        val parts = raw?.split(',')?.map { it.trim().toIntOrNull() ?: return null } ?: return null
        return parts.takeIf { isPermutation(it, n) }?.takeUnless { isIdentity(it) }
    }

    fun isPermutation(order: List<Int>, n: Int = LED_COUNT): Boolean =
        order.size == n && order.sorted() == (0 until n).toList()

    fun isIdentity(order: List<Int>): Boolean = order.withIndex().all { (i, v) -> i == v }

    /** One hardware LED lit white, the rest dark, for the wizard to ask about while unmapped. */
    fun single(hardwareLed: Int, n: Int = LED_COUNT): List<Int> =
        List(n) { if (it == hardwareLed) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() }
}

/**
 * The wizard's progress: it lights hardware LED 1, 2, … in turn and the user taps the ring position
 * where each one appeared. [placed] holds, per ring position, the hardware LED put there.
 */
data class LedMapWizard(val placed: List<Int?> = List(LED_COUNT) { null }) {

    /** The hardware LED being asked about now, or [LED_COUNT] once every one has a place. */
    val step: Int get() = placed.count { it != null }

    val complete: Boolean get() = step == placed.size

    fun isFree(position: Int): Boolean = placed.getOrNull(position) == null

    fun place(position: Int): LedMapWizard {
        if (complete || !isFree(position)) return this
        return copy(placed = placed.toMutableList().also { it[position] = step })
    }

    fun undo(): LedMapWizard {
        if (step == 0) return this
        val last = step - 1
        return copy(placed = placed.map { if (it == last) null else it })
    }

    /** The finished map, position → hardware LED; null until every LED is placed. */
    fun order(): List<Int>? = if (complete) placed.map { it!! } else null
}
