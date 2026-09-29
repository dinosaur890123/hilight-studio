package com.hilight.studio

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Settings for the waiting-apps glow, with a preview built from the user's own rules so the card
 * shows the sections and colours they would actually get.
 */
@Composable
fun WaitingAppsSection(store: Store) {
    val ctx = LocalContext.current
    val res = LocalResources.current
    val enabled by store.waitingGlow.collectAsStateWithLifecycle()
    val durationMs by store.waitingGlowMs.collectAsStateWithLifecycle()
    val rules by store.rules.collectAsStateWithLifecycle()

    // The first rules that could take part, in rule order, exactly as a real glow would order them.
    val sample = remember(rules) {
        rules.filter { it.enabled && it.trigger == Trigger.NOTIFICATION }
            .distinctBy { it.id }
            .take(WaitingApps.MAX_SECTIONS)
    }
    val usingExample = sample.size < 2
    val sections = if (usingExample) EXAMPLE_COLOURS else sample.map { it.color }
    val clash = if (usingExample) null else sample.zipWithNext().firstOrNull { (a, b) ->
        WaitingApps.tooSimilar(a.color, b.color)
    }

    PixelCard {
        SectionTitle(stringResource(R.string.waiting_title))
        ToggleRow(stringResource(R.string.waiting_toggle), enabled) { store.setWaitingGlow(it) }
        Caption(stringResource(R.string.waiting_body))

        LedStrip(Pattern.CUSTOM, WaitingApps.previewLook(sections), active = true, heightDp = 34)
        if (usingExample) {
            Caption(stringResource(R.string.waiting_example))
        } else {
            sample.forEach { rule ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SwatchDots(listOf(rule.color))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        ruleLabel(rule),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Caption(stringResource(R.string.waiting_order_hint, WaitingApps.MAX_SECTIONS))
        }
        clash?.let { (a, b) ->
            Text(
                stringResource(R.string.waiting_similar, ruleLabel(a), ruleLabel(b)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Caption(stringResource(R.string.waiting_length))
        SegmentedSelector(
            options = WaitingApps.DURATION_CHOICES,
            selected = durationMs,
            label = { formatDuration(it) },
            onSelect = { store.setWaitingGlowMs(it) },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            FilledTonalButton(onClick = {
                store.previewWaitingGlow(sections)?.let { reason ->
                    Toast.makeText(
                        ctx,
                        res.getString(R.string.test_blocked_by_guard, res.getString(reason.shortRes)),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }) { ButtonLabel(stringResource(R.string.common_test)) }
        }
    }
}

/** Shown until there are two notification rules to preview with: green, blue and red, clearly apart. */
private val EXAMPLE_COLOURS = listOf(0xFF00E676.toInt(), 0xFF2979FF.toInt(), 0xFFFF1744.toInt())
