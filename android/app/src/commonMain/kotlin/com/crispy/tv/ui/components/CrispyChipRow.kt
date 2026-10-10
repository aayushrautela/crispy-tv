package com.crispy.tv.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/** One choice in a [CrispyChipRow]. */
@Stable
data class CrispyChip(
    val id: String,
    val label: String,
    val icon: DrawableResource?,
    val selected: Boolean,
    val onClick: () -> Unit,
    /**
     * Drawn after the label. Carries the affordance for a chip that opens something rather than
     * selecting: the discover page's type/genre/sort chips are dropdown triggers, and a chip with no
     * chevron reads as a filter.
     */
    val trailingIcon: DrawableResource? = null,
)

/** Material 3 Expressive's `ButtonGroupDefaults.ExpandedRatio`. */
private const val DefaultExpandRatio = 0.15f

/**
 * A scrollable row of standalone filter chips, each hugging its own label so nothing truncates.
 *
 * ## The press animation
 *
 * The chip being pressed grows horizontally and the chip beside it gives up exactly the width the
 * pressed one took, so the row's total width does not change and nothing after them reflows. The
 * growth is a fraction of the pressed chip's width, which is Material 3 Expressive's own `ButtonGroup`
 * behaviour -- `ButtonGroupDefaults.ExpandedRatio`, applied by `Modifier.animateWidth`.
 *
 * That modifier cannot be used here: it is a member of `ButtonGroupScope`, so it resolves only
 * inside a `ButtonGroup`, and `ButtonGroup` is the *connected* group -- one control with a continuous
 * shape, which is a different component from a row of separate pills. It also has no scrolling of
 * its own: it measures to fit and squeezes. So the effect is reproduced over a scrolling `Row`.
 *
 * **It is reproduced through `contentPadding`, not through a width.** `FilterChip` lays its content
 * out with `Modifier.width(IntrinsicSize.Max)`, which pins the chip to its content's own maximum
 * intrinsic width and ignores any width constraint from a parent. A `Layout` around the chip
 * therefore cannot widen or narrow it. The padding the chip is handed *is* part of that content, so
 * animating it is what actually moves the edges -- and it moves both the neighbour's edges at the
 * same time, which is the half of the effect a per-chip layout cannot reach.
 *
 * **The ratio is of the chip's measured width, not of its padding.** `ExpandedRatio` is a fraction
 * of the interacted child's width, so applying it to the padding instead makes a 12dp padding grow
 * by 1.8dp and the effect is invisible. Each chip's resting width is recorded by `onSizeChanged` and
 * the padding moves by half of `width * expandRatio` on each side, so the chip's total width changes
 * by exactly that fraction.
 *
 * @param chips the choices in display order, each carrying its own selected state and click.
 * @param chipHeight applied to every chip, and the pill's corner radius. `FilterChip` defaults to
 *   32dp, short for a chip carrying an icon and a TV tap target; callers pass their own.
 */
@Composable
fun CrispyChipRow(
    chips: List<CrispyChip>,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 0.dp,
    chipHeight: Dp = FilterChipDefaults.Height,
    spacing: Dp = 8.dp,
    expandRatio: Float = DefaultExpandRatio,
) {
    if (chips.isEmpty()) return
    // Keyed by id rather than remembered once: the chip list is rebuilt from loaded data, and a
    // source list frozen at the first composition's size would be the wrong length once it changes.
    val interactionSources: List<MutableInteractionSource> =
        chips.map { chip -> key(chip.id) { remember { MutableInteractionSource() } } }
    val pressedFlags = interactionSources.map { source -> source.collectIsPressedAsState().value }
    val pressedIndex = pressedFlags.indexOfFirst { pressed -> pressed }
    // Each chip's resting width, so the growth can be a fraction of the chip rather than of its
    // padding. Recorded per chip id and only while not pressed, so the value a chip is measured
    // against is its own unpressed width and does not feed back into itself.
    val restingWidths = remember { mutableStateMapOf<String, Int>() }

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = horizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        chips.forEachIndexed { index, chip ->
            CrispyChipInRow(
                chip = chip,
                interactionSource = interactionSources[index],
                chipHeight = chipHeight,
                // Zero until the chip has been measured once, so the first press cannot animate from
                // a width nobody knows yet.
                restingWidthDp = with(LocalDensity.current) { restingWidths[chip.id]?.toDp() ?: 0.dp },
                onRestingWidthMeasured = { pixels -> restingWidths[chip.id] = pixels },
                expandRatio = expandRatio,
                isPressed = index == pressedIndex,
                isNeighbourOfPressed =
                    pressedIndex >= 0 && (index == pressedIndex - 1 || index == pressedIndex + 1),
            )
        }
    }
}

/**
 * One chip, with the padding that carries the press animation.
 *
 * The pressed chip and its neighbour animate towards opposite paddings by the same amount, so one
 * grows by exactly what the other loses. The amount is half of `restingWidthDp * expandRatio` on
 * each side, because padding is applied to both edges while `ExpandedRatio` is a fraction of the
 * chip's *total* width -- halving is what makes the widths trade exactly rather than by twice the
 * ratio. [paddingFloor] stops the neighbour going flat if it is already narrower than the growth.
 *
 * [onRestingWidthMeasured] reports this chip's width, and is only called while it is not pressed, so
 * the width the growth is computed from never includes the growth itself.
 */
@Composable
private fun RowScope.CrispyChipInRow(
    chip: CrispyChip,
    interactionSource: MutableInteractionSource,
    chipHeight: Dp,
    restingWidthDp: Dp,
    onRestingWidthMeasured: (Int) -> Unit,
    expandRatio: Float,
    isPressed: Boolean,
    isNeighbourOfPressed: Boolean,
    paddingBase: Dp = ChipPaddingBase,
    paddingFloor: Dp = ChipPaddingFloor,
) {
    val halfGrowth =
        (restingWidthDp * expandRatio / 2f).coerceIn(0.dp, (paddingBase - paddingFloor))
    val target =
        when {
            isPressed -> paddingBase + halfGrowth
            isNeighbourOfPressed -> paddingBase - halfGrowth
            else -> paddingBase
        }
    val horizontalPadding by
        animateDpAsState(
            targetValue = target,
            animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow),
            label = "chipPressPadding",
        )

    FilterChip(
        selected = chip.selected,
        onClick = chip.onClick,
        label = { Text(text = chip.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = chip.icon?.let { icon -> { CrispyIcon(painter = painterResource(icon), contentDescription = null) } },
        trailingIcon =
            chip.trailingIcon?.let { icon ->
                {
                    CrispyIcon(
                        painter = painterResource(icon),
                        contentDescription = null,
                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                    )
                }
            },
        modifier =
            Modifier
                .height(chipHeight)
                .onSizeChanged { size ->
                    // Unpressed only: measuring while growing would record the grown width and the
                    // next press would be computed from it, compounding on every press.
                    if (!isPressed) onRestingWidthMeasured(size.width)
                },
        // A pill is a stadium: every corner is half the height, so the ends are semicircles.
        // `FilterChipDefaults.shape` is `CornerSmall` (8dp), which reads as a rounded rectangle at
        // any height worth calling a chip. Half the *height* is named rather than written as
        // `RoundedCornerShape(percent = 50)` because that percentage is a fraction of the smaller
        // of width and height, so it stops being a pill the moment a chip is ever taller than it is
        // wide -- and it makes the intent unreadable at the call site.
        shape = RoundedCornerShape(chipHeight / 2),
        border = null,
        contentPadding =
            PaddingValues(
                horizontal = horizontalPadding,
                // Half the height the icon does not fill, so the content reaches the chip's own top
                // and bottom instead of sitting short inside a taller box.
                vertical = (chipHeight - FilterChipDefaults.IconSize) / 2,
            ),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            labelColor = MaterialTheme.colorScheme.onSurface,
            iconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
            // Both icon slots are named, not just the leading one: every colour in
            // `filterChipColors` defaults to `Color.Unspecified` and falls through to the theme
            // individually, so omitting the trailing slot leaves a chevron resolving by a different
            // path from the leading icon on a selected chip. `iconColor` already covers the
            // unselected case for both slots.
            selectedTrailingIconColor = MaterialTheme.colorScheme.onPrimary,
        ),
        interactionSource = interactionSource,
    )
}

private val ChipPaddingBase = 12.dp
private val ChipPaddingFloor = 4.dp