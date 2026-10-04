package com.hilight.studio

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.sign

/**
 * Material 3 Expressive floating navigation: a pill that hovers over the content instead of a
 * full-width bar. The selected destination widens to show its label inside a contrasting pill while
 * the rest shrink to icons, all on springs, and the bar tucks away while the user scrolls down.
 */

/** Gap between the bar and the bottom of the screen, above the system navigation inset. */
val FloatingNavBarMargin = 16.dp

/** The bar's height plus its margin and some breathing room, so the last card can scroll clear. */
val FloatingNavBarClearance = 72.dp + FloatingNavBarMargin + 16.dp

class FloatingNavItem(val label: String, val icon: ImageVector)

/**
 * Hides the bar after a short scroll down and brings it back after a short scroll up, like the
 * expressive toolbars. Only watches; it never consumes, so the top app bar still collapses.
 */
class FloatingNavScrollState(private val thresholdPx: Float) : NestedScrollConnection {
    var visible by mutableStateOf(true)
        private set
    private var travel = 0f

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        val dy = available.y
        if (dy == 0f) return Offset.Zero
        // a change of direction starts a fresh count, so a small reversal is enough to react
        if (dy.sign != travel.sign) travel = 0f
        travel += dy
        if (travel < -thresholdPx) visible = false else if (travel > thresholdPx) visible = true
        return Offset.Zero
    }

    fun show() {
        visible = true
        travel = 0f
    }
}

@Composable
fun rememberFloatingNavScrollState(): FloatingNavScrollState {
    val threshold = with(LocalDensity.current) { 24.dp.toPx() }
    return remember(threshold) { FloatingNavScrollState(threshold) }
}

@Composable
fun FloatingNavBar(
    items: List<FloatingNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = slideInVertically(spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow)) { it * 2 } +
            scaleIn(spring(stiffness = Spring.StiffnessMediumLow), initialScale = 0.85f) +
            fadeIn(spring(stiffness = Spring.StiffnessMediumLow)),
        exit = slideOutVertically(spring(stiffness = Spring.StiffnessMedium)) { it * 2 } +
            scaleOut(spring(stiffness = Spring.StiffnessMedium), targetScale = 0.85f) +
            fadeOut(spring(stiffness = Spring.StiffnessMedium)),
        label = "navBar",
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shadowElevation = 8.dp,
        ) {
            Row(
                Modifier
                    .selectableGroup()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items.forEachIndexed { i, item ->
                    NavPill(item, selected = i == selectedIndex, onClick = { onSelect(i) })
                }
            }
        }
    }
}

@Composable
private fun NavPill(item: FloatingNavItem, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium),
        label = "navPress",
    )
    // fade the indicator through its own transparent tone, not through black
    val indicator by animateColorAsState(
        targetValue = if (selected) scheme.surfaceContainer else scheme.surfaceContainer.copy(alpha = 0f),
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "navIndicator",
    )
    val content by animateColorAsState(
        targetValue = if (selected) scheme.primary else scheme.onPrimaryContainer,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "navContent",
    )
    // the icon pops as its pill opens, the springy beat the expressive components use
    val pop = remember { Animatable(1f) }
    LaunchedEffect(selected) {
        if (selected) {
            pop.snapTo(0.72f)
            pop.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMedium))
        }
    }

    Box(
        Modifier
            .height(56.dp)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(CircleShape)
            .background(indicator)
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.Tab,
                interactionSource = interaction,
                indication = ripple(),
            )
            .semantics { contentDescription = item.label }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                item.icon,
                contentDescription = null,
                tint = content,
                modifier = Modifier
                    .size(24.dp)
                    .graphicsLayer {
                        scaleX = pop.value
                        scaleY = pop.value
                    },
            )
            AnimatedVisibility(
                visible = selected,
                enter = expandHorizontally(
                    spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow),
                    expandFrom = Alignment.Start,
                ) + fadeIn(spring(stiffness = Spring.StiffnessLow)),
                exit = shrinkHorizontally(
                    spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow),
                    shrinkTowards = Alignment.Start,
                ) + fadeOut(spring(stiffness = Spring.StiffnessMedium)),
                label = "navLabel",
            ) {
                Text(
                    item.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = content,
                    maxLines = 1,
                    softWrap = false,
                    // the pill already announces the label; reading the text too would repeat it
                    modifier = Modifier
                        .padding(start = 10.dp, end = 2.dp)
                        .clearAndSetSemantics { },
                )
            }
        }
    }
}
