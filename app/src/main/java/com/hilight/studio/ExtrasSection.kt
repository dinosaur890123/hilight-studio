package com.hilight.studio

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** The Extras tab: the LED map first, since it makes everything else line up, then the playful modes. */
@Composable
fun ExtrasScreen(store: Store) {
    val enabled by store.enabled.collectAsStateWithLifecycle()
    val status by store.status.collectAsStateWithLifecycle()
    val available = enabled && status.alive
    if (!available) {
        PixelCard(tone = 2) { Caption(stringResource(R.string.extras_need_control)) }
    }
    LedMapCard(store, available)
    PartySection(store, available)
    ExtrasSection(store, available)
    TestBenchCard(store, available)
}

/** Compass, breathing guide and shake-for-sparkles. */
@Composable
fun ExtrasSection(store: Store, available: Boolean) {
    CompassCard(store, available)
    BreathingCard(store, available)
    ShakeCard(store, available)
}

/** Shows a guard's reason the way every Test button does, or runs [start] when nothing blocks it. */
@Composable
internal fun rememberGuardedStart(store: Store): (() -> Unit) -> Unit {
    val ctx = LocalContext.current
    val res = LocalResources.current
    return remember(store, ctx, res) {
        { start ->
            val reason = store.previewSuppressionReason()
            if (reason == null) start()
            else Toast.makeText(
                ctx,
                res.getString(R.string.test_blocked_by_guard, res.getString(reason.shortRes)),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }
}

@Composable
private fun CompassCard(store: Store, available: Boolean) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val guarded = rememberGuardedStart(store)
    val calibration by store.compassCalibration.collectAsStateWithLifecycle()
    val latestCalibration by rememberUpdatedState(calibration)
    val sensors = remember { ctx.getSystemService(SensorManager::class.java) }
    val rotation = remember { sensors?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) }
    var running by remember { mutableStateOf(false) }
    var heading by remember { mutableStateOf<Double?>(null) }
    var remainingMs by remember { mutableIntStateOf(0) }
    var needsCalibrating by remember { mutableStateOf(false) }
    val directions = stringArrayResource(R.array.compass_directions)

    if (running && sensors != null && rotation != null) {
        DisposableEffect(Unit) {
            // Screen-down, nobody touches the screen, so keep it awake for the minute this runs.
            view.keepScreenOn = true
            val smoother = Compass.Smoother()
            val started = SystemClock.elapsedRealtime()
            val matrix = FloatArray(9)
            val orientation = FloatArray(3)
            var first = true
            var lastSent = Double.NaN
            var lastSentAt = 0L
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    if (!running) return
                    SensorManager.getRotationMatrixFromVector(matrix, event.values)
                    SensorManager.getOrientation(matrix, orientation)
                    val h = smoother.update(Math.toDegrees(orientation[0].toDouble()))
                    heading = h
                    val now = SystemClock.elapsedRealtime()
                    val left = (Compass.SESSION_MS - (now - started)).toInt()
                    remainingMs = left
                    val position = Compass.quantize(Compass.northPosition(h, latestCalibration))
                    if (first || (position != lastSent && now - lastSentAt >= 100)) {
                        if (!store.showCompassFrame(Compass.frame(position), left, first)) {
                            running = false
                            return
                        }
                        first = false
                        lastSent = position
                        lastSentAt = now
                    }
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                    needsCalibrating = accuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW
                }
            }
            sensors.registerListener(listener, rotation, SensorManager.SENSOR_DELAY_UI)
            onDispose {
                sensors.unregisterListener(listener)
                view.keepScreenOn = false
                store.stopCompass()
                heading = null
            }
        }
        LaunchedEffect(Unit) {
            delay(Compass.SESSION_MS.toLong())
            running = false
        }
    }

    PixelCard {
        SectionTitle(stringResource(R.string.compass_title))
        Caption(stringResource(R.string.compass_body))
        if (rotation == null) {
            Caption(stringResource(R.string.compass_no_sensor))
            return@PixelCard
        }
        heading?.let { h ->
            Text(
                stringResource(R.string.compass_heading, directions[Compass.octant(h)], h.roundToInt() % 360),
                style = MaterialTheme.typography.titleMedium,
            )
            Caption(stringResource(R.string.compass_remaining, (remainingMs.coerceAtLeast(0) + 999) / 1000))
        }
        if (needsCalibrating && running) {
            Text(
                stringResource(R.string.compass_figure_eight),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (running) {
                FilledTonalButton(onClick = { running = false }) { ButtonLabel(stringResource(R.string.extras_stop)) }
            } else {
                Button(onClick = { guarded { running = true } }, enabled = available) {
                    ButtonLabel(stringResource(R.string.compass_start))
                }
            }
        }
        Caption(stringResource(R.string.compass_calibrate_hint))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = { store.setCompassCalibration(calibration.nudge(-1)) }) {
                ButtonLabel(stringResource(R.string.compass_turn_left))
            }
            TextButton(onClick = { store.setCompassCalibration(calibration.nudge(1)) }) {
                ButtonLabel(stringResource(R.string.compass_turn_right))
            }
        }
        ToggleRow(stringResource(R.string.compass_reverse), calibration.reversed) {
            store.setCompassCalibration(calibration.copy(reversed = it))
        }
    }
}

@Composable
private fun BreathingCard(store: Store, available: Boolean) {
    val view = LocalView.current
    val guarded = rememberGuardedStart(store)
    val session by store.breathing.collectAsStateWithLifecycle()
    var rhythmKey by rememberSaveable { mutableStateOf(BreathingRhythm.BOX.key) }
    var lengthMs by rememberSaveable { mutableIntStateOf(BreathingGuide.SESSION_CHOICES_MS.first()) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }

    val current = session
    if (current != null) {
        DisposableEffect(current) {
            view.keepScreenOn = true
            onDispose { view.keepScreenOn = false }
        }
        LaunchedEffect(current) {
            while (true) {
                now = SystemClock.elapsedRealtime()
                delay(33)
            }
        }
    }

    PixelCard {
        SectionTitle(stringResource(R.string.breath_title))
        Caption(stringResource(R.string.breath_body))
        if (current != null) {
            val elapsed = (now - current.startedAtMs).coerceAtLeast(0)
            val (phase, phaseLeft) = BreathingGuide.phaseAt(current.rhythm, elapsed)
            val frame = BreathingGuide.frame(current.rhythm, elapsed).map { it or 0xFF000000.toInt() }.toIntArray()
            LedFrameStrip(frame, stringResource(phase.labelRes), heightDp = 46)
            Text(
                stringResource(R.string.breath_phase, stringResource(phase.labelRes), (phaseLeft + 999) / 1000),
                style = MaterialTheme.typography.headlineSmall,
            )
            Caption(stringResource(R.string.breath_left, formatDuration((current.totalMs - elapsed).coerceAtLeast(0).toInt())))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                FilledTonalButton(onClick = { store.stopBreathing() }) { ButtonLabel(stringResource(R.string.extras_stop)) }
            }
        } else {
            SegmentedSelector(
                options = BreathingRhythm.entries,
                selected = BreathingRhythm.of(rhythmKey),
                label = { stringResource(it.labelRes) },
                onSelect = { rhythmKey = it.key },
            )
            Caption(stringResource(BreathingRhythm.of(rhythmKey).let {
                when (it) {
                    BreathingRhythm.BOX -> R.string.breath_box_hint
                    BreathingRhythm.RELAX -> R.string.breath_relax_hint
                    BreathingRhythm.CALM -> R.string.breath_calm_hint
                }
            }))
            SegmentedSelector(
                options = BreathingGuide.SESSION_CHOICES_MS,
                selected = lengthMs,
                label = { formatDuration(it) },
                onSelect = { lengthMs = it },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(
                    onClick = { guarded { store.startBreathing(BreathingRhythm.of(rhythmKey), lengthMs) } },
                    enabled = available,
                ) { ButtonLabel(stringResource(R.string.breath_start)) }
            }
        }
    }
}

@Composable
private fun ShakeCard(store: Store, available: Boolean) {
    val launchPreview = rememberPreviewLauncher(store)
    val on by store.shakeSparkles.collectAsStateWithLifecycle()
    PixelCard {
        SectionTitle(stringResource(R.string.shake_title))
        ToggleRow(stringResource(R.string.shake_toggle), on) { store.setShakeSparkles(it) }
        Caption(stringResource(R.string.shake_body))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            FilledTonalButton(
                onClick = {
                    val look = ShakeDetector.sparkleLook(kotlin.random.Random.nextInt(360).toFloat())
                    launchPreview(look.pattern, look.color, look.speedMs, look.brightness, ShakeDetector.SPARKLE_MS, look)
                },
                enabled = available,
            ) { ButtonLabel(stringResource(R.string.common_test)) }
        }
    }
}
