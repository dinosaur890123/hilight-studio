package com.hilight.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

/**
 * "Map your LEDs": lights each hardware LED in turn and asks where it is on the ring, then plays a
 * comet through the new map so the user can see it travel round before keeping it.
 */
@Composable
fun LedMapCard(store: Store, available: Boolean) {
    val guarded = rememberGuardedStart(store)
    val saved by store.ledOrder.collectAsStateWithLifecycle()
    var wizard by remember { mutableStateOf<LedMapWizard?>(null) }
    var checking by remember { mutableStateOf(false) }

    val current = wizard
    if (current != null) {
        // Leaving the tab or the screen ends the wizard and restores the saved map.
        DisposableEffect(Unit) { onDispose { store.endLedMapping() } }
        if (!current.complete) {
            LaunchedEffect(current.step) {
                while (true) {
                    store.showMappingLed(current.step)
                    delay(45_000)      // re-light before the one-minute hold runs out
                }
            }
        } else {
            LaunchedEffect(current) {
                current.order()?.let(store::tryLedMap)
                checking = true
            }
        }
    }

    PixelCard {
        SectionTitle(stringResource(R.string.ledmap_title))
        if (current == null) {
            Caption(stringResource(R.string.ledmap_body))
            Caption(
                stringResource(if (saved != null) R.string.ledmap_status_mapped else R.string.ledmap_status_default)
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (saved != null) {
                    TextButton(onClick = { store.resetLedMap() }) { ButtonLabel(stringResource(R.string.ledmap_reset)) }
                }
                Button(onClick = { guarded { checking = false; wizard = LedMapWizard() } }, enabled = available) {
                    ButtonLabel(stringResource(R.string.ledmap_start))
                }
            }
            return@PixelCard
        }

        if (!current.complete) {
            Text(
                stringResource(R.string.ledmap_step, current.step + 1, LED_COUNT),
                style = MaterialTheme.typography.titleMedium,
            )
            Caption(stringResource(R.string.ledmap_tip))
        } else {
            Text(stringResource(R.string.ledmap_check), style = MaterialTheme.typography.titleMedium)
        }

        LedRingPicker(current, enabled = !current.complete) { position -> wizard = current.place(position) }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TextButton(onClick = {
                wizard = null
                store.endLedMapping()
            }) { ButtonLabel(stringResource(R.string.common_cancel)) }
            TextButton(
                onClick = {
                    checking = false
                    wizard = current.undo()
                },
                enabled = current.step > 0,
            ) { ButtonLabel(stringResource(R.string.ledmap_undo)) }
            if (current.complete && checking) {
                FilledTonalButton(onClick = { current.order()?.let(store::tryLedMap) }) {
                    ButtonLabel(stringResource(R.string.ledmap_again))
                }
                Button(onClick = {
                    current.order()?.let(store::saveLedMap)
                    wizard = null
                }) { ButtonLabel(stringResource(R.string.common_save)) }
            }
        }
    }
}

/**
 * Eight tappable spots around a camera window, drawn as seen from the back with the top of the phone
 * up. Placed spots show which LED went there; free spots are outlined.
 */
@Composable
private fun LedRingPicker(wizard: LedMapWizard, enabled: Boolean, onPick: (Int) -> Unit) {
    val haptics = LocalHapticFeedback.current
    val radius = 82f
    Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(120.dp)
                .border(2.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
        )
        for (position in 0 until LED_COUNT) {
            val angle = Math.toRadians(position * 360.0 / LED_COUNT - 90.0)
            val led = wizard.placed[position]
            val free = led == null
            val label = if (free) stringResource(R.string.ledmap_spot_free, position + 1)
            else stringResource(R.string.ledmap_spot_taken, position + 1, led!! + 1)
            Box(
                Modifier
                    .offset((cos(angle) * radius).dp, (sin(angle) * radius).dp)
                    .size(44.dp)
                    .background(
                        if (free) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.primary,
                        CircleShape,
                    )
                    .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                    .semantics { contentDescription = label }
                    .clickable(enabled = enabled && free) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onPick(position)
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (!free) {
                    Text(
                        "${led!! + 1}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
    }
}
