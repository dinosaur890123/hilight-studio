package com.hilight.studio

import android.app.Activity
import android.content.Intent
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

private fun clock(ms: Long): String {
    val s = (ms.coerceAtLeast(0) / 1000).toInt()
    return "%d:%02d".format(s / 60, s % 60)
}

/** The LED test bench: a power test and heat runs the app measures by itself. */
@Composable
fun TestBenchCard(store: Store, available: Boolean) {
    val ctx = LocalContext.current
    val res = LocalResources.current
    val view = LocalView.current
    val runner = store.bench
    val ui by runner.ui.collectAsStateWithLifecycle()
    val power by runner.power.collectAsStateWithLifecycle()
    val runs by runner.runs.collectAsStateWithLifecycle()
    var runType by rememberSaveable { mutableStateOf(TestBench.HeatRunType.NO_DIMMING.name) }
    var surface by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var startSurface by rememberSaveable { mutableStateOf("") }

    val running = ui.phase in setOf(
        TestBenchRunner.Phase.POWER, TestBenchRunner.Phase.BASELINE,
        TestBenchRunner.Phase.SOAK, TestBenchRunner.Phase.MEASURE,
    )
    if (running) {
        // Screen on but as dim as it goes, so the display adds as little heat as possible.
        DisposableEffect(Unit) {
            val window = (ctx as? Activity)?.window
            view.keepScreenOn = true
            window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = 0.01f } }
            onDispose {
                view.keepScreenOn = false
                window?.let { w ->
                    w.attributes = w.attributes.apply {
                        screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    }
                }
                runner.onScreenLeft()
            }
        }
    }

    // While the LEDs do their work the screen shows pure black: on an OLED panel those pixels are off.
    if (ui.phase in setOf(TestBenchRunner.Phase.POWER, TestBenchRunner.Phase.BASELINE, TestBenchRunner.Phase.SOAK)) {
        BenchBlackout(ui) { runner.cancel() }
    }

    val report: (TestBenchRunner.Start) -> Unit = { result ->
        when (result) {
            TestBenchRunner.Start.Plugged ->
                Toast.makeText(ctx, res.getString(R.string.bench_unplug), Toast.LENGTH_LONG).show()
            is TestBenchRunner.Start.Blocked -> Toast.makeText(
                ctx,
                res.getString(R.string.test_blocked_by_guard, res.getString(result.reason.shortRes)),
                Toast.LENGTH_SHORT,
            ).show()
            TestBenchRunner.Start.Ok -> Unit
        }
    }
    val plugged = runner.isPlugged()
    val hasCurrent = runner.supportsCurrent
    val canStart = available && !running && !plugged

    PixelCard {
        SectionTitle(stringResource(R.string.bench_title))
        Caption(stringResource(R.string.bench_body))
        Caption(stringResource(R.string.bench_keep_open))
        if (plugged) {
            Text(stringResource(R.string.bench_unplug), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        // ---------------------------------------------------------------- power
        SectionTitle(stringResource(R.string.bench_power_title))
        if (!hasCurrent) {
            Caption(stringResource(R.string.bench_no_current))
        } else {
            Caption(stringResource(R.string.bench_power_body))
            if (ui.phase == TestBenchRunner.Phase.POWER) {
                val step = TestBench.POWER_PLAN[ui.powerStep]
                Text(
                    stringResource(R.string.bench_power_running, stringResource(step.labelRes), ui.powerStep + 1, TestBench.POWER_PLAN.size),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            power.forEach { r ->
                Text(stringResource(R.string.bench_power_row, stringResource(r.step.labelRes), r.milliWatts), style = MaterialTheme.typography.bodyMedium)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(onClick = { report(runner.startPower()) }, enabled = canStart) {
                    ButtonLabel(stringResource(R.string.bench_power_start))
                }
            }
        }

        // ---------------------------------------------------------------- heat
        SectionTitle(stringResource(R.string.bench_heat_title))
        Caption(stringResource(R.string.bench_heat_body))
        when (ui.phase) {
            TestBenchRunner.Phase.BASELINE -> Text(stringResource(R.string.bench_baseline), style = MaterialTheme.typography.titleMedium)
            TestBenchRunner.Phase.SOAK -> {
                Text(
                    "${clock(ui.soakElapsedMs)} / ${clock(TestBench.SOAK_MS)}",
                    style = MaterialTheme.typography.displaySmall,
                )
                Text(
                    stringResource(
                        R.string.bench_live,
                        ui.runType?.let { stringResource(it.labelRes) } ?: "",
                        ui.batteryC ?: 0.0,
                        ui.ledMilliWatts?.let { "%.0f mW".format(it) } ?: "–",
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (ui.getReady) {
                    Text(stringResource(R.string.bench_get_ready), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
            TestBenchRunner.Phase.MEASURE -> {
                val stop = ui.pendingStop
                Text(
                    if (stop == null || stop == TestBench.StopReason.COMPLETED) stringResource(R.string.bench_measure_now)
                    else stringResource(R.string.bench_measure_now) + " · " + stringResource(stop.labelRes),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Caption(stringResource(R.string.bench_measure_body))
                OutlinedTextField(
                    value = surface,
                    onValueChange = { v -> surface = v.filter { it.isDigit() || it == '.' || it == ',' }.take(5) },
                    label = { Text(stringResource(R.string.bench_surface_field)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(200) },
                    label = { Text(stringResource(R.string.bench_note_field)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    TextButton(onClick = {
                        runner.saveReading(null, note)
                        surface = ""; note = ""
                    }) { ButtonLabel(stringResource(R.string.bench_skip_reading)) }
                    Button(
                        onClick = {
                            runner.saveReading(surface.replace(',', '.').toDoubleOrNull(), note)
                            surface = ""; note = ""
                        },
                        enabled = surface.replace(',', '.').toDoubleOrNull() != null,
                    ) { ButtonLabel(stringResource(R.string.bench_save_reading)) }
                }
            }
            TestBenchRunner.Phase.COOLING -> Text(
                if (ui.cooled) stringResource(R.string.bench_ready)
                else stringResource(R.string.bench_cooling, clock(ui.coolSinceMs), ui.batteryC ?: 0.0, ui.coolStartC ?: 0.0),
                style = MaterialTheme.typography.titleMedium,
                color = if (ui.cooled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            else -> Unit
        }

        if (!running) {
            OutlinedTextField(
                value = startSurface,
                onValueChange = { v -> startSurface = v.filter { it.isDigit() || it == '.' || it == ',' }.take(5) },
                label = { Text(stringResource(R.string.bench_start_surface_field)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            TestBench.HeatRunType.entries.forEach { type ->
                Row(
                    Modifier.fillMaxWidth().clickable { runType = type.name },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = runType == type.name, onClick = { runType = type.name })
                    Text(stringResource(type.labelRes), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            if (running || ui.phase == TestBenchRunner.Phase.COOLING) {
                TextButton(onClick = { runner.cancel() }) { ButtonLabel(stringResource(R.string.bench_cancel)) }
            }
            if (!running) {
                Button(
                    onClick = {
                        report(runner.startHeat(TestBench.HeatRunType.valueOf(runType), startSurface.replace(',', '.').toDoubleOrNull()))
                        startSurface = ""
                    },
                    enabled = canStart,
                ) { ButtonLabel(stringResource(R.string.bench_heat_start)) }
            }
        }

        // ---------------------------------------------------------------- results
        if (runs.isNotEmpty() || power.isNotEmpty()) {
            SectionTitle(stringResource(R.string.bench_results))
            runs.forEachIndexed { i, r ->
                val rise = if (r.startC != null && r.maxC != null) "%.1f".format(r.maxC!! - r.startC!!) else "–"
                Text(
                    stringResource(
                        R.string.bench_result_row,
                        i + 1,
                        stringResource(r.type.labelRes),
                        when {
                            r.surfaceC != null && r.surfaceStartC != null -> "%.1f → %.1f °C".format(r.surfaceStartC, r.surfaceC)
                            r.surfaceC != null -> "%.1f °C".format(r.surfaceC)
                            else -> "–"
                        },
                        rise,
                        r.ledMilliWatts()?.let { "%.0f mW".format(it) } ?: "–",
                        stringResource(r.stop.labelRes),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = { runner.clearResults() }, enabled = !running) {
                    ButtonLabel(stringResource(R.string.bench_clear))
                }
                FilledTonalButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "hilight-led-test.csv")
                        putExtra(Intent.EXTRA_TEXT, runner.csv())
                    }
                    ctx.startActivity(Intent.createChooser(send, res.getString(R.string.bench_export_chooser)))
                }) { ButtonLabel(stringResource(R.string.bench_export)) }
            }
        }
    }
}

/**
 * A full-screen black layer with the system bars hidden, shown while a test runs so the display adds
 * as little light and heat as possible. A tap shows the progress and Cancel for a few seconds.
 */
@Composable
private fun BenchBlackout(ui: TestBenchRunner.Ui, onCancel: () -> Unit) {
    var showControls by remember { mutableStateOf(false) }
    LaunchedEffect(showControls) {
        if (showControls) {
            delay(5_000)
            showControls = false
        }
    }
    Dialog(
        onDismissRequest = { showControls = true },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnClickOutside = false,
        ),
    ) {
        val view = LocalView.current
        DisposableEffect(Unit) {
            (view.parent as? DialogWindowProvider)?.window?.let { w ->
                w.attributes = w.attributes.apply { screenBrightness = 0.01f }
                WindowCompat.getInsetsController(w, view).apply {
                    hide(WindowInsetsCompat.Type.systemBars())
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
            view.keepScreenOn = true
            onDispose { view.keepScreenOn = false }
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    showControls = true
                },
            contentAlignment = Alignment.Center,
        ) {
            if (showControls) {
                val dim = Color(0xFF707070)
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        when (ui.phase) {
                            TestBenchRunner.Phase.SOAK -> "${clock(ui.soakElapsedMs)} / ${clock(TestBench.SOAK_MS)}"
                            TestBenchRunner.Phase.BASELINE -> stringResource(R.string.bench_baseline)
                            else -> stringResource(
                                R.string.bench_power_running,
                                stringResource(TestBench.POWER_PLAN[ui.powerStep].labelRes),
                                ui.powerStep + 1,
                                TestBench.POWER_PLAN.size,
                            )
                        },
                        style = MaterialTheme.typography.titleLarge,
                        color = dim,
                    )
                    Text(stringResource(R.string.bench_blackout_hint), style = MaterialTheme.typography.bodySmall, color = dim)
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.bench_cancel), color = dim) }
                }
            }
        }
    }
}

