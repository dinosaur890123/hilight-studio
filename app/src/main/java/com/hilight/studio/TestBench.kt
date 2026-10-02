package com.hilight.studio

import androidx.annotation.StringRes
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * The LED test bench: a power test and a heat-soak test that the app runs and measures by itself.
 *
 * Power is read from the battery's own current and voltage, comparing each LED step with the dark
 * steps either side of it, so whatever else the phone is drawing (the screen above all) cancels out.
 * Heat is the battery temperature and Android's thermal status, plus the one number only the user
 * can take: the camera-bar surface temperature at the ten-minute mark.
 *
 * This file is pure so the plan and the arithmetic are host-tested; [TestBenchRunner] drives it.
 */
object TestBench {

    // ------------------------------------------------------------------ power test

    enum class PowerStep(@StringRes val labelRes: Int, val colour: Int?, val leds: Int = LED_COUNT) {
        OFF(R.string.bench_step_off, null),
        RED(R.string.bench_step_red, 0xFFFF0000.toInt()),
        GREEN(R.string.bench_step_green, 0xFF00FF00.toInt()),
        BLUE(R.string.bench_step_blue, 0xFF0000FF.toInt()),
        WHITE_25(R.string.bench_step_white_25, 0xFF404040.toInt()),
        WHITE_50(R.string.bench_step_white_50, 0xFF808080.toInt()),
        WHITE_100(R.string.bench_step_white_100, 0xFFFFFFFF.toInt()),
        ONE_LED(R.string.bench_step_one_led, 0xFFFFFFFF.toInt(), leds = 1);

        fun frame(): List<Int> = List(LED_COUNT) { i ->
            if (colour != null && i < leds) colour else 0xFF000000.toInt()
        }
    }

    /** Lit steps separated by dark ones: the dark steps are the reference and reset the taper. */
    val POWER_PLAN: List<PowerStep> = buildList {
        add(PowerStep.OFF)
        listOf(
            PowerStep.RED, PowerStep.GREEN, PowerStep.BLUE,
            PowerStep.WHITE_25, PowerStep.WHITE_50, PowerStep.WHITE_100, PowerStep.ONE_LED,
        ).forEach { add(it); add(PowerStep.OFF) }
    }

    const val POWER_STEP_MS = 5_000L
    /** Readings in the first part of a step are dropped while the battery gauge settles. */
    const val POWER_SETTLE_MS = 1_500L
    const val POWER_SAMPLE_MS = 250L

    data class PowerResult(val step: PowerStep, val deltaMicroAmps: Double, val milliWatts: Double)

    /**
     * One result per lit step: its median current against the mean of the dark steps either side,
     * times the median voltage. The sign of the current differs between phones, so only the size of
     * the difference counts.
     */
    fun analysePower(stepCurrents: List<List<Int>>, voltageMilliVolts: Int): List<PowerResult> {
        require(stepCurrents.size == POWER_PLAN.size)
        val medians = stepCurrents.map { median(it.map(Int::toDouble)) }
        return POWER_PLAN.indices.filter { POWER_PLAN[it] != PowerStep.OFF }.mapNotNull { i ->
            val here = medians[i] ?: return@mapNotNull null
            val before = medians.getOrNull(i - 1)
            val after = medians.getOrNull(i + 1)
            val reference = listOfNotNull(before, after).takeIf { it.isNotEmpty() }?.average() ?: return@mapNotNull null
            val delta = abs(here - reference)
            PowerResult(POWER_PLAN[i], delta, delta * voltageMilliVolts / 1_000_000.0)
        }
    }

    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    // ------------------------------------------------------------------ heat soak

    enum class HeatRunType(@StringRes val labelRes: Int, val lit: Boolean, val experiment: Boolean) {
        NO_DIMMING(R.string.bench_run_no_dimming, lit = true, experiment = true),
        NORMAL_LIMITS(R.string.bench_run_normal, lit = true, experiment = false),
        CONTROL(R.string.bench_run_control, lit = false, experiment = false),
    }

    const val BASELINE_MS = 15_000L
    const val SOAK_MS = 10 * 60_000L
    const val WARNING_BEFORE_MS = 30_000L
    /** After the ten-minute alarm the LEDs stay on this long at most while the user measures. */
    const val MEASURE_GRACE_MS = 60_000L
    const val HEAT_SAMPLE_MS = 2_000L
    const val HEAT_LOG_MS = 10_000L

    /** The run stops early at this battery temperature, or at Android's "moderate" thermal status. */
    const val ABORT_BATTERY_C = 42.0
    const val ABORT_THERMAL_STATUS = 2

    /** Ready for the next run once this long has passed and the battery is back near its start. */
    const val COOL_MIN_MS = 15 * 60_000L
    const val COOL_MARGIN_C = 0.5

    data class Sample(
        val tMs: Long,
        val batteryC: Double,
        val microAmps: Int?,
        val milliVolts: Int,
        val thermalStatus: Int,
        val headroom: Float?,
        val ledsOn: Boolean,
    )

    enum class StopReason(@StringRes val labelRes: Int) {
        COMPLETED(R.string.bench_stop_completed),
        BATTERY_HOT(R.string.bench_stop_battery_hot),
        THERMAL(R.string.bench_stop_thermal),
        INTERRUPTED(R.string.bench_stop_interrupted),
        CANCELLED(R.string.bench_stop_cancelled),
    }

    /** Why a reading should end the soak early, or null to carry on. */
    fun abortReason(sample: Sample): StopReason? = when {
        sample.batteryC >= ABORT_BATTERY_C -> StopReason.BATTERY_HOT
        sample.thermalStatus >= ABORT_THERMAL_STATUS -> StopReason.THERMAL
        else -> null
    }

    fun cooledDown(startC: Double, nowC: Double, sinceEndMs: Long): Boolean =
        sinceEndMs >= COOL_MIN_MS && nowC <= startC + COOL_MARGIN_C

    data class HeatRun(
        val type: HeatRunType,
        val startedAtEpochMs: Long,
        val samples: List<Sample>,
        val stop: StopReason,
        val surfaceC: Double? = null,
        val note: String = "",
        /** The camera bar before the run, when the user took it, so the rise can be shown. */
        val surfaceStartC: Double? = null,
    ) {
        val surfaceRiseC: Double? get() = if (surfaceC != null && surfaceStartC != null) surfaceC - surfaceStartC else null

        val startC: Double? get() = samples.firstOrNull()?.batteryC
        val endC: Double? get() = samples.lastOrNull()?.batteryC
        val maxC: Double? get() = samples.maxOfOrNull { it.batteryC }
        val litMs: Long get() = samples.filter { it.ledsOn }.let { lit ->
            if (lit.isEmpty()) 0 else lit.last().tMs - lit.first().tMs
        }

        /** LED power over the soak: lit readings against the dark baseline before the LEDs came on. */
        fun ledMilliWatts(): Double? {
            val base = median(samples.filter { !it.ledsOn }.mapNotNull { it.microAmps?.toDouble() }) ?: return null
            val lit = median(samples.filter { it.ledsOn }.mapNotNull { it.microAmps?.toDouble() }) ?: return null
            val mv = median(samples.map { it.milliVolts.toDouble() }) ?: return null
            return abs(lit - base) * mv / 1_000_000.0
        }

        fun toJson(): JSONObject = JSONObject().apply {
            put("type", type.name)
            put("startedAt", startedAtEpochMs)
            put("stop", stop.name)
            surfaceC?.let { put("surfaceC", it) }
            surfaceStartC?.let { put("surfaceStartC", it) }
            put("note", note)
            put("samples", JSONArray().also { a ->
                samples.forEach { s ->
                    a.put(JSONObject().apply {
                        put("t", s.tMs)
                        put("c", s.batteryC)
                        s.microAmps?.let { put("ua", it) }
                        put("mv", s.milliVolts)
                        put("th", s.thermalStatus)
                        s.headroom?.let { put("hr", it.toDouble()) }
                        put("on", s.ledsOn)
                    })
                }
            })
        }

        companion object {
            fun fromJson(o: JSONObject): HeatRun? = runCatching {
                val a = o.getJSONArray("samples")
                HeatRun(
                    type = HeatRunType.valueOf(o.getString("type")),
                    startedAtEpochMs = o.getLong("startedAt"),
                    stop = StopReason.valueOf(o.getString("stop")),
                    surfaceC = if (o.has("surfaceC")) o.getDouble("surfaceC") else null,
                    surfaceStartC = if (o.has("surfaceStartC")) o.getDouble("surfaceStartC") else null,
                    note = o.optString("note", ""),
                    samples = (0 until a.length()).map { i ->
                        val s = a.getJSONObject(i)
                        Sample(
                            tMs = s.getLong("t"),
                            batteryC = s.getDouble("c"),
                            microAmps = if (s.has("ua")) s.getInt("ua") else null,
                            milliVolts = s.getInt("mv"),
                            thermalStatus = s.getInt("th"),
                            headroom = if (s.has("hr")) s.getDouble("hr").toFloat() else null,
                            ledsOn = s.getBoolean("on"),
                        )
                    },
                )
            }.getOrNull()
        }
    }

    /** Every result as one CSV document: a summary per run, then every logged reading. */
    fun csv(power: List<PowerResult>, runs: List<HeatRun>): String = buildString {
        appendLine("section,step,delta_uA,mW")
        power.forEach { appendLine("power,${it.step.name},${"%.0f".format(it.deltaMicroAmps)},${"%.1f".format(it.milliWatts)}") }
        appendLine()
        appendLine("section,run,type,started_epoch_ms,stop,start_C,end_C,max_C,surface_start_C,surface_C,surface_rise_C,lit_s,led_mW,note")
        runs.forEachIndexed { i, r ->
            appendLine(
                listOf(
                    "run", i + 1, r.type.name, r.startedAtEpochMs, r.stop.name,
                    r.startC?.let { "%.1f".format(it) } ?: "",
                    r.endC?.let { "%.1f".format(it) } ?: "",
                    r.maxC?.let { "%.1f".format(it) } ?: "",
                    r.surfaceStartC?.let { "%.1f".format(it) } ?: "",
                    r.surfaceC?.let { "%.1f".format(it) } ?: "",
                    r.surfaceRiseC?.let { "%.1f".format(it) } ?: "",
                    r.litMs / 1000,
                    r.ledMilliWatts()?.let { "%.0f".format(it) } ?: "",
                    "\"" + r.note.replace("\"", "'") + "\"",
                ).joinToString(",")
            )
        }
        appendLine()
        appendLine("section,run,t_s,battery_C,current_uA,voltage_mV,thermal_status,headroom,leds_on")
        runs.forEachIndexed { i, r ->
            r.samples.forEach { s ->
                appendLine(
                    "sample,${i + 1},${s.tMs / 1000},${"%.1f".format(s.batteryC)},${s.microAmps ?: ""}," +
                        "${s.milliVolts},${s.thermalStatus},${s.headroom?.let { "%.2f".format(it) } ?: ""},${s.ledsOn}"
                )
            }
        }
    }
}
