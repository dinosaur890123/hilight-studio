package com.hilight.studio

import android.Manifest
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlin.random.Random

/** Demo reel, party pack and music sync, for the Extras tab. */
@Composable
fun PartySection(store: Store, available: Boolean) {
    DemoReelCard(store, available)
    PartyPackCard(store, available)
    MusicSyncCard(store, available)
}

/** Elapsed time since [startedAtMs], ticking at the LEDs' frame rate while [running]. */
@Composable
private fun rememberElapsed(startedAtMs: Long?, running: Boolean): Long {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(startedAtMs, running) {
        while (running && startedAtMs != null) {
            now = SystemClock.elapsedRealtime()
            delay(33)
        }
    }
    return if (startedAtMs == null) 0 else (now - startedAtMs).coerceAtLeast(0)
}

/** Shows the guard's reason in a toast when [result] is one. */
@Composable
private fun rememberBlockedToast(): (Suppression?) -> Unit {
    val ctx = LocalContext.current
    val res = LocalResources.current
    return remember(ctx, res) {
        { reason ->
            if (reason != null) Toast.makeText(
                ctx,
                res.getString(R.string.test_blocked_by_guard, res.getString(reason.shortRes)),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }
}

private fun opaque(frame: IntArray) = IntArray(frame.size) { frame[it] or 0xFF000000.toInt() }

@Composable
private fun DemoReelCard(store: Store, available: Boolean) {
    val blocked = rememberBlockedToast()
    val party by store.party.collectAsStateWithLifecycle()
    val demo = party?.takeIf { it.kind == PartySession.Kind.DEMO }
    val elapsed = rememberElapsed(demo?.startedAtMs, demo != null)
    val playing = demo != null && elapsed < demo.totalMs

    PixelCard {
        SectionTitle(stringResource(R.string.demo_title))
        Caption(stringResource(R.string.demo_body, PartyModes.DEMO.size, PartyModes.demoTotalMs / 1000))
        if (playing) {
            LedFrameStrip(opaque(PartyModes.demoFrame(elapsed)), stringResource(R.string.demo_title), heightDp = 40)
            PartyModes.demoSceneAt(elapsed)?.let { index ->
                Text(
                    stringResource(
                        R.string.demo_now,
                        index + 1,
                        PartyModes.DEMO.size,
                        stringResource(PartyModes.DEMO[index].nameRes),
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (playing) {
                FilledTonalButton(onClick = { store.stopParty() }) { ButtonLabel(stringResource(R.string.extras_stop)) }
            } else {
                Button(
                    onClick = {
                        val session = PartySession(
                            PartySession.Kind.DEMO, SystemClock.elapsedRealtime(), PartyModes.demoTotalMs,
                        )
                        blocked(store.playParty(session, PartyModes.demoAlert(Bridge.nextAlertId()), PartyModes.DEMO.first().look))
                    },
                    enabled = available,
                ) { ButtonLabel(stringResource(R.string.demo_play)) }
            }
        }
    }
}

@Composable
private fun PartyPackCard(store: Store, available: Boolean) {
    val ctx = LocalContext.current
    val blocked = rememberBlockedToast()
    val haptics = LocalHapticFeedback.current
    val party by store.party.collectAsStateWithLifecycle()
    val current = party?.takeIf { it.kind != PartySession.Kind.DEMO }
    val elapsed = rememberElapsed(current?.startedAtMs, current != null)
    val showing = current != null && elapsed < current.totalMs
    var shakeToRoll by remember { mutableStateOf(false) }

    val spin = {
        val target = Random.nextInt(LED_COUNT)
        val turns = 3 + Random.nextInt(3)
        val session = PartySession(
            PartySession.Kind.SPIN, SystemClock.elapsedRealtime(),
            PartyModes.SPIN_MS + PartyModes.SPIN_HOLD_MS, target = target, turns = turns,
        )
        blocked(store.playParty(session, PartyModes.spinAlert(Bridge.nextAlertId(), target, turns),
            Ambient(pattern = Pattern.RAINBOW, speedMs = 1_000, brightness = 1f)))
    }
    val roll = {
        val value = 1 + Random.nextInt(6)
        val seed = Random.nextLong(1_000_000)
        val session = PartySession(
            PartySession.Kind.DICE, SystemClock.elapsedRealtime(),
            PartyModes.DICE_ROLL_MS + PartyModes.DICE_HOLD_MS, value = value, seed = seed,
        )
        blocked(store.playParty(session, PartyModes.diceAlert(Bridge.nextAlertId(), value, seed),
            Ambient(pattern = Pattern.CUSTOM, perLed = PartyModes.diceFrame(value, seed, PartyModes.DICE_ROLL_MS.toLong()).map { it or 0xFF000000.toInt() }, brightness = 1f)))
    }

    // Shake to roll, only while this card is on screen and the switch is on.
    if (shakeToRoll && available) {
        DisposableEffect(Unit) {
            val sensors = ctx.getSystemService(SensorManager::class.java)
            val accel = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            val detector = ShakeDetector()
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    if (event.values.size >= 3 &&
                        detector.onSample(event.values[0], event.values[1], event.values[2], SystemClock.elapsedRealtime())
                    ) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        roll()
                    }
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            store.diceShakeListening = true
            if (sensors != null && accel != null) {
                sensors.registerListener(listener, accel, SensorManager.SENSOR_DELAY_GAME)
            }
            onDispose {
                sensors?.unregisterListener(listener)
                store.diceShakeListening = false
            }
        }
    }

    PixelCard {
        SectionTitle(stringResource(R.string.party_title))
        Caption(stringResource(R.string.party_body))
        if (showing && current != null) {
            LedFrameStrip(opaque(current.frame(elapsed)), stringResource(R.string.party_title), heightDp = 40)
            if (current.kind == PartySession.Kind.DICE && elapsed >= PartyModes.DICE_ROLL_MS) {
                Text(
                    stringResource(R.string.party_rolled, current.value),
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { spin() }, enabled = available, modifier = Modifier.weight(1f)) {
                ButtonLabel(stringResource(R.string.party_spin))
            }
            Button(onClick = { roll() }, enabled = available, modifier = Modifier.weight(1f)) {
                ButtonLabel(stringResource(R.string.party_roll))
            }
        }
        ToggleRow(stringResource(R.string.party_shake_to_roll), shakeToRoll) { shakeToRoll = it }
    }
}

@Composable
private fun MusicSyncCard(store: Store, available: Boolean) {
    val ctx = LocalContext.current
    val res = LocalResources.current
    val view = LocalView.current
    val blocked = rememberBlockedToast()
    val music = store.music
    val ui by music.ui.collectAsStateWithLifecycle()
    val session by store.beat.collectAsStateWithLifecycle()
    var source by rememberSaveable { mutableStateOf(MusicListener.Source.PHONE) }
    val current = session
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }

    val listen = {
        when (val result = music.start(source)) {
            MusicListener.Start.Ok, MusicListener.Start.NeedsPermission -> Unit
            is MusicListener.Start.Blocked -> blocked(result.reason)
        }
    }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) listen()
        else Toast.makeText(ctx, res.getString(R.string.music_need_permission), Toast.LENGTH_LONG).show()
    }

    if (ui.listening) {
        DisposableEffect(Unit) {
            view.keepScreenOn = true
            onDispose { view.keepScreenOn = false }
        }
        LaunchedEffect(Unit) {
            while (true) {
                now = SystemClock.elapsedRealtime()
                delay(33)
            }
        }
    }

    PixelCard {
        SectionTitle(stringResource(R.string.music_title))
        Caption(stringResource(R.string.music_body))
        if (ui.listening) {
            val grid = current?.grid
            val clock = grid?.clockAt(now) ?: 0L
            LedFrameStrip(
                opaque(if (grid != null) PartyModes.beatFrame(grid.beatMs, clock, grid.energy) else IntArray(LED_COUNT)),
                stringResource(R.string.music_title),
                heightDp = 40,
            )
            LinearProgressIndicator(
                progress = { ui.intensity.toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                when (ui.status) {
                    MusicListener.Status.DANCING -> ui.bpm?.let { stringResource(R.string.music_bpm, it) }
                        ?: stringResource(R.string.music_status_finding)
                    MusicListener.Status.FINDING_BEAT -> stringResource(R.string.music_status_finding)
                    else -> stringResource(R.string.music_status_waiting)
                },
                style = MaterialTheme.typography.headlineSmall,
            )
            if (grid != null && ui.status == MusicListener.Status.DANCING) {
                val beat = clock / grid.beatMs
                Caption(
                    stringResource(
                        R.string.music_now,
                        stringResource(
                            when (PartyModes.beatMove(beat)) {
                                0 -> R.string.music_move_pulse
                                1 -> R.string.music_move_chase
                                2 -> R.string.music_move_sweep
                                else -> R.string.music_move_split
                            }
                        ),
                        (beat % 4 + 1).toInt(),
                    )
                )
            }
            if (ui.fellBack) Caption(stringResource(R.string.music_fell_back))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { music.nudge(-MusicListener.NUDGE_STEP_MS) }) {
                    ButtonLabel(stringResource(R.string.music_earlier))
                }
                Text(
                    stringResource(R.string.music_timing, "%+d".format(ui.nudgeMs)),
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { music.nudge(MusicListener.NUDGE_STEP_MS) }) {
                    ButtonLabel(stringResource(R.string.music_later))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                FilledTonalButton(onClick = { music.stop() }) { ButtonLabel(stringResource(R.string.extras_stop)) }
            }
        } else {
            SegmentedSelector(
                options = MusicListener.Source.entries,
                selected = source,
                label = {
                    stringResource(
                        if (it == MusicListener.Source.PHONE) R.string.music_source_phone else R.string.music_source_mic
                    )
                },
                onSelect = { source = it },
            )
            Caption(
                stringResource(
                    if (source == MusicListener.Source.PHONE) R.string.music_source_phone_hint
                    else R.string.music_source_mic_hint
                )
            )
            when (ui.status) {
                MusicListener.Status.FINISHED -> Caption(stringResource(R.string.music_status_finished))
                MusicListener.Status.FAILED -> Text(
                    stringResource(R.string.music_status_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                else -> Unit
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(
                    onClick = {
                        if (music.hasPermission()) listen()
                        else askPermission.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    enabled = available,
                ) { ButtonLabel(stringResource(R.string.music_listen)) }
            }
        }
    }
}
