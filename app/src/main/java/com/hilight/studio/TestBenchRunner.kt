package com.hilight.studio

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.PowerManager
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Drives the [TestBench] plans on the main thread: sends LED frames through [Store], reads the
 * battery and thermal sensors, cues the ten-minute mark on the LEDs, then watches the phone cool down.
 *
 * Every frame is a Test-style preview, so leaving the app ends a run (it is then recorded as
 * interrupted) and the LEDs go dark within one held frame even if this process stops.
 */
class TestBenchRunner internal constructor(
    private val app: Context,
    private val store: Store,
    private val main: Handler,
) {
    enum class Phase { IDLE, POWER, BASELINE, SOAK, MEASURE, COOLING }

    data class Ui(
        val phase: Phase = Phase.IDLE,
        val powerStep: Int = 0,
        val runType: TestBench.HeatRunType? = null,
        val soakElapsedMs: Long = 0,
        val batteryC: Double? = null,
        val ledMilliWatts: Double? = null,
        val getReady: Boolean = false,
        val pendingStop: TestBench.StopReason? = null,
        val coolStartC: Double? = null,
        val coolSinceMs: Long = 0,
        val cooled: Boolean = false,
    )

    sealed interface Start {
        data object Ok : Start
        data object Plugged : Start
        data class Blocked(val reason: Suppression) : Start
    }

    private val prefs = app.getSharedPreferences("hilight", Context.MODE_PRIVATE)
    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()
    private val _power = MutableStateFlow(loadPower())
    val power: StateFlow<List<TestBench.PowerResult>> = _power.asStateFlow()
    private val _runs = MutableStateFlow(loadRuns())
    val runs: StateFlow<List<TestBench.HeatRun>> = _runs.asStateFlow()

    private var tick: Runnable? = null
    private var cueRestore: Runnable? = null
    private var stepStarted = 0L
    private var stepCurrents = mutableListOf<MutableList<Int>>()
    private var voltages = mutableListOf<Int>()

    private var runStartedEpoch = 0L
    private var runSurfaceStartC: Double? = null
    private var runStartedElapsed = 0L
    private var soakStartedElapsed = 0L
    private var lastFrameSent = 0L
    private var lastLogged = Long.MIN_VALUE
    private var lastThermalRead = 0L
    private var samples = mutableListOf<TestBench.Sample>()
    private var lastSample: TestBench.Sample? = null
    private var coolEndedElapsed = 0L

    val supportsCurrent: Boolean
        get() = readCurrent() != null

    fun isPlugged(): Boolean = batteryIntent()?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0

    // ------------------------------------------------------------------ power test

    fun startPower(): Start {
        if (_ui.value.phase !in setOf(Phase.IDLE, Phase.COOLING)) return Start.Ok
        if (isPlugged()) return Start.Plugged
        store.previewSuppressionReason()?.let { return Start.Blocked(it) }
        cancelTick()
        stepCurrents = MutableList(TestBench.POWER_PLAN.size) { mutableListOf() }
        voltages = mutableListOf()
        _ui.value = Ui(phase = Phase.POWER)
        beginPowerStep(0)
        return Start.Ok
    }

    private fun beginPowerStep(index: Int) {
        if (index >= TestBench.POWER_PLAN.size) return finishPower()
        val step = TestBench.POWER_PLAN[index]
        if (!store.showBenchFrame(step.frame(), (TestBench.POWER_STEP_MS + 2_000).toInt(), experiment = false, first = index == 0)) {
            return abortPower()
        }
        stepStarted = SystemClock.elapsedRealtime()
        _ui.value = _ui.value.copy(powerStep = index)
        schedule(TestBench.POWER_SAMPLE_MS) { powerTick(index) }
    }

    private fun powerTick(index: Int) {
        if (!store.benchOwnsOutput()) return abortPower()
        val inStep = SystemClock.elapsedRealtime() - stepStarted
        if (inStep >= TestBench.POWER_SETTLE_MS) {
            readCurrent()?.let { stepCurrents[index].add(it) }
            batteryIntent()?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)?.takeIf { it > 0 }?.let(voltages::add)
        }
        if (inStep >= TestBench.POWER_STEP_MS) beginPowerStep(index + 1)
        else schedule(TestBench.POWER_SAMPLE_MS) { powerTick(index) }
    }

    private fun finishPower() {
        store.stopBenchOutput()
        val mv = TestBench.median(voltages.map { it.toDouble() })?.toInt() ?: 0
        if (mv > 0 && stepCurrents.all { it.isNotEmpty() }) {
            val results = TestBench.analysePower(stepCurrents, mv)
            _power.value = results
            savePower(results)
        }
        _ui.value = Ui()
    }

    private fun abortPower() {
        cancelTick()
        store.stopBenchOutput()
        _ui.value = Ui()
    }

    // ------------------------------------------------------------------ heat test

    fun startHeat(type: TestBench.HeatRunType, surfaceStartC: Double? = null): Start {
        if (_ui.value.phase !in setOf(Phase.IDLE, Phase.COOLING)) return Start.Ok
        if (isPlugged()) return Start.Plugged
        store.previewSuppressionReason()?.let { return Start.Blocked(it) }
        cancelTick()
        if (!store.showBenchFrame(DARK, HOLD_MS, experiment = false, first = true)) return Start.Ok
        samples = mutableListOf()
        lastSample = null
        lastLogged = Long.MIN_VALUE
        runSurfaceStartC = surfaceStartC
        runStartedEpoch = System.currentTimeMillis()
        runStartedElapsed = SystemClock.elapsedRealtime()
        lastFrameSent = runStartedElapsed
        _ui.value = Ui(phase = Phase.BASELINE, runType = type)
        schedule(TestBench.HEAT_SAMPLE_MS) { heatTick() }
        return Start.Ok
    }

    private fun heatTick() {
        val ui = _ui.value
        val type = ui.runType ?: return
        val now = SystemClock.elapsedRealtime()
        if (!store.benchOwnsOutput()) {
            // After the ten-minute mark the run is complete; losing the LEDs then only ends the hold.
            if (ui.phase == Phase.MEASURE) return
            return endHeat(TestBench.StopReason.INTERRUPTED)
        }

        val ledsOn = ui.phase != Phase.BASELINE && type.lit
        val sample = readSample(now - runStartedElapsed, ledsOn)
        lastSample = sample
        if (lastLogged == Long.MIN_VALUE || now - lastLogged >= TestBench.HEAT_LOG_MS) {
            samples += sample
            lastLogged = now
        }

        when (ui.phase) {
            Phase.BASELINE -> if (now - runStartedElapsed >= TestBench.BASELINE_MS) {
                soakStartedElapsed = now
                // The soak always starts with fresh frames so the experiment flag, if any, is set.
                if (!store.showBenchFrame(if (type.lit) WHITE else DARK, HOLD_MS, type.experiment, first = false)) {
                    return endHeat(TestBench.StopReason.INTERRUPTED)
                }
                lastFrameSent = now
                _ui.value = ui.copy(phase = Phase.SOAK, soakElapsedMs = 0)
            } else {
                resendIfDue(now, DARK, experiment = false)
            }
            Phase.SOAK -> {
                val elapsed = now - soakStartedElapsed
                TestBench.abortReason(sample)?.let { reason ->
                    _ui.value = _ui.value.copy(batteryC = sample.batteryC)
                    return promptMeasure(reason, keepLit = false)
                }
                resendIfDue(now, if (type.lit) WHITE else DARK, type.experiment)
                val getReady = elapsed >= TestBench.SOAK_MS - TestBench.WARNING_BEFORE_MS
                if (getReady && !ui.getReady) {
                    showCue(CUE_GET_READY, CUE_GET_READY_MS, back = if (type.lit) WHITE else DARK)
                }
                _ui.value = ui.copy(
                    soakElapsedMs = elapsed,
                    batteryC = sample.batteryC,
                    ledMilliWatts = liveLedMilliWatts(),
                    getReady = getReady,
                )
                if (elapsed >= TestBench.SOAK_MS) return promptMeasure(TestBench.StopReason.COMPLETED, keepLit = type.lit)
            }
            Phase.MEASURE -> {
                // LEDs held while the user measures, but never past the grace period.
                if (now - soakStartedElapsed >= TestBench.SOAK_MS + TestBench.MEASURE_GRACE_MS) {
                    store.stopBenchOutput()
                    return
                }
                resendIfDue(now, if (type.lit) WHITE else DARK, type.experiment)
            }
            else -> return
        }
        _ui.value = _ui.value.copy(batteryC = sample.batteryC)
        schedule(TestBench.HEAT_SAMPLE_MS) { heatTick() }
    }

    private fun resendIfDue(now: Long, frame: List<Int>, experiment: Boolean) {
        if (cueRestore != null) return
        if (now - lastFrameSent >= RESEND_MS && store.showBenchFrame(frame, HOLD_MS, experiment, first = false)) {
            lastFrameSent = now
        }
    }

    private fun promptMeasure(reason: TestBench.StopReason, keepLit: Boolean) {
        val type = _ui.value.runType
        lastSample?.let { if (samples.lastOrNull() !== it) samples += it }
        _ui.value = _ui.value.copy(phase = Phase.MEASURE, pendingStop = reason, getReady = false)
        // A completed run says "measure now" with a short blue cue on the LEDs themselves. An early
        // safety stop goes dark at once instead: LEDs going dark before the blue cue is the signal.
        if (reason == TestBench.StopReason.COMPLETED && type != null) {
            showCue(CUE_MEASURE, CUE_MEASURE_MS, back = if (keepLit) WHITE else null)
        } else {
            store.stopBenchOutput()
        }
        if (keepLit) schedule(TestBench.HEAT_SAMPLE_MS) { heatTick() }
    }

    /**
     * Shows [colour] on every LED for [ms], then returns to [back], or turns the LEDs off when
     * [back] is null.
     *
     * The cue frames themselves use the experiment allowance, so they show even in a normal-limits
     * run whose LEDs are resting under the duty limit at that moment. That is a few seconds of light
     * per run; the run's own frames keep the run's own limits.
     */
    private fun showCue(colour: Int, ms: Long, back: List<Int>?) {
        val type = _ui.value.runType ?: return
        if (!store.showBenchFrame(List(LED_COUNT) { colour }, HOLD_MS, experiment = true, first = false)) return
        lastFrameSent = SystemClock.elapsedRealtime()
        cancelCue()
        val r = Runnable {
            cueRestore = null
            if (back == null) {
                if (store.benchOwnsOutput()) store.stopBenchOutput()
            } else if (_ui.value.phase == Phase.SOAK || _ui.value.phase == Phase.MEASURE) {
                if (store.showBenchFrame(back, HOLD_MS, type.experiment, first = false)) {
                    lastFrameSent = SystemClock.elapsedRealtime()
                }
            }
        }
        cueRestore = r
        main.postDelayed(r, ms)
    }

    private fun cancelCue() {
        cueRestore?.let { main.removeCallbacks(it) }
        cueRestore = null
    }

    /** Records the surface reading (or none) and moves on to cooling down. */
    fun saveReading(surfaceC: Double?, note: String) {
        val ui = _ui.value
        if (ui.phase != Phase.MEASURE) return
        finishRun(ui.pendingStop ?: TestBench.StopReason.COMPLETED, surfaceC, note)
    }

    /** Stops a run in progress, keeping what was measured so far. */
    fun cancel() {
        when (_ui.value.phase) {
            Phase.POWER -> abortPower()
            Phase.BASELINE, Phase.SOAK -> endHeat(TestBench.StopReason.CANCELLED)
            Phase.MEASURE -> finishRun(_ui.value.pendingStop ?: TestBench.StopReason.CANCELLED, null, "")
            Phase.COOLING -> {
                cancelTick()
                _ui.value = Ui()
            }
            Phase.IDLE -> Unit
        }
    }

    /** Called when leaving the screen: a run cannot continue without its frames. */
    fun onScreenLeft() {
        when (_ui.value.phase) {
            Phase.POWER -> abortPower()
            Phase.BASELINE, Phase.SOAK -> endHeat(TestBench.StopReason.INTERRUPTED)
            else -> Unit
        }
    }

    private fun endHeat(reason: TestBench.StopReason) {
        cancelTick()
        cancelCue()
        store.stopBenchOutput()
        if (samples.size >= 2) finishRun(reason, null, "") else _ui.value = Ui()
    }

    private fun finishRun(reason: TestBench.StopReason, surfaceC: Double?, note: String) {
        cancelTick()
        cancelCue()
        store.stopBenchOutput()
        val type = _ui.value.runType ?: return
        val run = TestBench.HeatRun(
            type, runStartedEpoch, samples.toList(), reason, surfaceC, note.trim().take(200), runSurfaceStartC,
        )
        _runs.value = (_runs.value + run).takeLast(MAX_RUNS)
        saveRuns(_runs.value)
        coolEndedElapsed = SystemClock.elapsedRealtime()
        _ui.value = Ui(phase = Phase.COOLING, coolStartC = run.startC, batteryC = lastSample?.batteryC)
        schedule(COOL_TICK_MS) { coolTick() }
    }

    private fun coolTick() {
        if (_ui.value.phase != Phase.COOLING) return
        val c = batteryC() ?: return schedule(COOL_TICK_MS) { coolTick() }
        val since = SystemClock.elapsedRealtime() - coolEndedElapsed
        val start = _ui.value.coolStartC ?: c
        val cooled = TestBench.cooledDown(start, c, since)
        if (cooled && !_ui.value.cooled) flashReady()
        _ui.value = _ui.value.copy(batteryC = c, coolSinceMs = since, cooled = cooled)
        if (!cooled) schedule(COOL_TICK_MS) { coolTick() }
    }

    fun clearResults() {
        _power.value = emptyList()
        _runs.value = emptyList()
        prefs.edit().remove(KEY_POWER).remove(KEY_RUNS).apply()
    }

    fun csv(): String = TestBench.csv(_power.value, _runs.value)

    // ------------------------------------------------------------------ readings

    private fun liveLedMilliWatts(): Double? =
        TestBench.HeatRun(TestBench.HeatRunType.CONTROL, 0, samples, TestBench.StopReason.COMPLETED)
            .ledMilliWatts()

    private fun readSample(tMs: Long, ledsOn: Boolean): TestBench.Sample {
        val intent = batteryIntent()
        val pm = app.getSystemService(PowerManager::class.java)
        val now = SystemClock.elapsedRealtime()
        // Thermal headroom must not be polled faster than about once a second.
        val headroom = if (now - lastThermalRead >= 1_000) {
            lastThermalRead = now
            pm?.getThermalHeadroom(0)?.takeUnless { it.isNaN() }
        } else null
        return TestBench.Sample(
            tMs = tMs,
            batteryC = (intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0,
            microAmps = readCurrent(),
            milliVolts = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0,
            thermalStatus = pm?.currentThermalStatus ?: 0,
            headroom = headroom,
            ledsOn = ledsOn,
        )
    }

    private fun batteryC(): Double? =
        batteryIntent()?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }?.let { it / 10.0 }

    private fun readCurrent(): Int? =
        app.getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            ?.takeIf { it != Int.MIN_VALUE && it != 0 }

    private fun batteryIntent(): Intent? =
        app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

    /** Two seconds of green on the LEDs: cooled down, ready for the next run. No sound. */
    private fun flashReady() {
        if (store.showBenchFrame(List(LED_COUNT) { CUE_READY }, CUE_READY_MS.toInt() + 1_000, experiment = false, first = true)) {
            main.postDelayed({ if (store.benchOwnsOutput()) store.stopBenchOutput() }, CUE_READY_MS)
        }
    }

    private fun schedule(delayMs: Long, block: () -> Unit) {
        cancelTick()
        val r = Runnable { runCatching(block) }
        tick = r
        main.postDelayed(r, delayMs)
    }

    private fun cancelTick() {
        tick?.let { main.removeCallbacks(it) }
        tick = null
    }

    // ------------------------------------------------------------------ persistence

    private fun savePower(results: List<TestBench.PowerResult>) {
        val a = JSONArray()
        results.forEach { r ->
            a.put(JSONObject().put("step", r.step.name).put("ua", r.deltaMicroAmps).put("mw", r.milliWatts))
        }
        prefs.edit().putString(KEY_POWER, a.toString()).apply()
    }

    private fun loadPower(): List<TestBench.PowerResult> = runCatching {
        val a = JSONArray(prefs.getString(KEY_POWER, "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            TestBench.PowerResult(TestBench.PowerStep.valueOf(o.getString("step")), o.getDouble("ua"), o.getDouble("mw"))
        }
    }.getOrDefault(emptyList())

    private fun saveRuns(runs: List<TestBench.HeatRun>) {
        val a = JSONArray()
        runs.forEach { a.put(it.toJson()) }
        prefs.edit().putString(KEY_RUNS, a.toString()).apply()
    }

    private fun loadRuns(): List<TestBench.HeatRun> = runCatching {
        val a = JSONArray(prefs.getString(KEY_RUNS, "[]"))
        (0 until a.length()).mapNotNull { TestBench.HeatRun.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private companion object {
        val DARK = List(LED_COUNT) { 0xFF000000.toInt() }
        val WHITE = List(LED_COUNT) { 0xFFFFFFFF.toInt() }
        /** Amber 30 seconds before the mark: get the thermometer ready. */
        val CUE_GET_READY = 0xFFFFAB00.toInt()
        const val CUE_GET_READY_MS = 3_000L
        /** Blue at the ten-minute mark: measure now. */
        val CUE_MEASURE = 0xFF2979FF.toInt()
        const val CUE_MEASURE_MS = 2_000L
        /** Green when the phone has cooled down: ready for the next run. */
        val CUE_READY = 0xFF00E676.toInt()
        const val CUE_READY_MS = 2_000L
        /** Each frame is held this long and resent every [RESEND_MS], inside the one-minute alert cap. */
        const val HOLD_MS = 55_000
        const val RESEND_MS = 45_000L
        const val COOL_TICK_MS = 10_000L
        const val MAX_RUNS = 20
        const val KEY_POWER = "benchPower"
        const val KEY_RUNS = "benchRuns"
    }
}
