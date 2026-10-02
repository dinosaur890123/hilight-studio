package com.hilight.studio

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlin.random.Random

/** Demo reel, party pack and tap tempo, for the Extras tab. */
@Composable
fun PartySection(store: Store, available: Boolean) {
    DemoReelCard(store, available)
    PartyPackCard(store, available)
    TapTempoCard(store, available)
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
private fun TapTempoCard(store: Store, available: Boolean) {
    val blocked = rememberBlockedToast()
    val haptics = LocalHapticFeedback.current
    val session by store.beat.collectAsStateWithLifecycle()
    val tempo = remember { TapTempo() }
    var taps by remember { mutableIntStateOf(0) }
    val current = session
    val elapsed = rememberElapsed(current?.startedAtMs, current != null)

    PixelCard {
        SectionTitle(stringResource(R.string.tempo_title))
        Caption(stringResource(R.string.tempo_body))
        Box(
            Modifier
                .fillMaxWidth()
                .height(120.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(
                    if (available) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHighest,
                )
                .clickable(enabled = available) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    taps++
                    tempo.tap(SystemClock.elapsedRealtime())?.let { beatMs -> blocked(store.startBeat(beatMs)) }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (current != null) stringResource(R.string.tempo_bpm, TapTempo.bpm(current.beatMs))
                else stringResource(R.string.tempo_tap_here),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        if (current != null) {
            LedFrameStrip(opaque(PartyModes.beatFrame(current.beatMs, elapsed)), stringResource(R.string.tempo_title), heightDp = 34)
            val beat = elapsed / current.beatMs
            Text(
                stringResource(
                    R.string.tempo_now,
                    stringResource(
                        when (PartyModes.beatMove(beat)) {
                            0 -> R.string.tempo_move_pulse
                            1 -> R.string.tempo_move_chase
                            2 -> R.string.tempo_move_sweep
                            else -> R.string.tempo_move_split
                        }
                    ),
                    (beat % 4 + 1).toInt(),
                ),
                style = MaterialTheme.typography.titleMedium,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = { blocked(store.startBeat(TapTempo.scaled(current.beatMs, 2.0))) }) {
                    ButtonLabel(stringResource(R.string.tempo_half))
                }
                TextButton(onClick = { blocked(store.startBeat(TapTempo.scaled(current.beatMs, 0.5))) }) {
                    ButtonLabel(stringResource(R.string.tempo_double))
                }
                FilledTonalButton(onClick = {
                    tempo.reset()
                    store.stopBeat()
                }) { ButtonLabel(stringResource(R.string.extras_stop)) }
            }
        } else if (taps > 0) {
            Caption(stringResource(R.string.tempo_keep_tapping))
        }
    }
}
