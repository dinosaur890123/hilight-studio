package com.hilight.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Built-in looks, each previewed live, plus a "Surprise me" that deals a random harmonious look.
 *
 * The previews animate whether or not control is on: they are a menu, not the hardware.
 */
@Composable
fun FeaturedLooksCard(store: Store) {
    val ambient by store.ambient.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current

    PixelCard {
        SectionTitle(stringResource(R.string.style_featured)) {
            TextButton(onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                store.surpriseLook()
            }) {
                Icon(Icons.Rounded.Casino, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                ButtonLabel(stringResource(R.string.style_surprise))
            }
        }
        Caption(stringResource(R.string.style_featured_hint))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(Looks.featured, key = { it.key }) { look ->
                // Applying keeps the user's brightness, so compare with brightness taken out.
                val selected = ambient.copy(brightness = look.ambient.brightness) == look.ambient
                val shape = RoundedCornerShape(20.dp)
                Column(
                    Modifier
                        .width(124.dp)
                        .clip(shape)
                        .background(
                            if (selected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerHighest,
                        )
                        .border(
                            if (selected) 2.dp else 0.dp,
                            if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                            shape,
                        )
                        .clickable {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            store.applyFeaturedLook(look)
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    LedStrip(look.ambient.pattern, look.ambient, heightDp = 22)
                    Text(
                        stringResource(look.nameRes),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        stringResource(look.ambient.pattern.labelRes),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Both colours of a two-colour effect, with one-tap harmonies that derive the second from the first.
 *
 * Shared by the Style tab and both rule editors so every two-colour effect is edited the same way.
 */
@Composable
fun TwoColourEditor(
    first: Int,
    second: Int,
    // Both colours in one call: the swap changes both at once, and two separate writes would each
    // start from the same stale look and undo one another.
    onColours: (first: Int, second: Int) -> Unit,
    firstLabel: String = stringResource(R.string.style_first_colour),
    secondLabel: String = stringResource(R.string.style_second_colour),
) {
    val haptics = LocalHapticFeedback.current
    ColorPicker(first, { onColours(it, second) }, firstLabel)
    ColorPicker(second, { onColours(first, it) }, secondLabel)
    Caption(stringResource(R.string.harmony_title))
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Looks.Harmony.entries.forEach { harmony ->
            val derived = harmony.from(first)
            FilledTonalButton(onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onColours(first, derived)
            }) {
                SwatchDots(listOf(first, derived), dotSize = 10)
                Spacer(Modifier.width(6.dp))
                ButtonLabel(stringResource(harmony.labelRes))
            }
        }
        FilledTonalButton(onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onColours(second, first)
        }) { ButtonLabel(stringResource(R.string.harmony_swap)) }
    }
}

/** A few colour dots that summarise a look where a live strip would be too much. */
@Composable
fun SwatchDots(colors: List<Int>, modifier: Modifier = Modifier, dotSize: Int = 14) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        colors.forEach { c ->
            Box(
                Modifier
                    .size(dotSize.dp)
                    .background(Color(c), CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.surface, CircleShape),
            )
        }
    }
}

/**
 * The time-of-day switch, with the whole day's colours laid out as a bar so it is clear what the
 * array will do at any hour before turning it on.
 */
@Composable
fun TimeOfDayControls(store: Store, on: Boolean, pattern: Pattern) {
    ToggleRow(stringResource(R.string.tod_toggle), on) { store.setTimeOfDayColours(it) }
    if (!on) return
    val day = remember {
        (0..24).map { hour -> Color(TimeOfDay.colours(hour * 60).first) }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(14.dp)
            .clip(CircleShape)
            .background(Brush.horizontalGradient(day)),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        listOf("0", "6", "12", "18", "24").forEach { Caption(it) }
    }
    Caption(
        stringResource(
            if (TimeOfDay.appliesTo(pattern)) R.string.tod_body else R.string.tod_not_this_effect,
        )
    )
}
