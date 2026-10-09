package com.crispy.tv.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/** One choice in a [CrispySegmentedButtonRow]. */
@Immutable
data class CrispySegmentedButton(
    val id: String,
    val label: String,
    /**
     * Drawn before the label at [ToggleButtonDefaults.IconSize]. `null` renders the label alone,
     * which is what a page whose choices are already words -- rather than words plus a glyph --
     * wants.
     */
    val icon: DrawableResource? = null,
)

private val SegmentedButtonHeight = 48.dp

/**
 * The glyph a [CrispySegmentedButton] carries, sized by the button rather than by this file: an
 * icon slot already places its content in a box of [ToggleButtonDefaults.IconSize].
 */
@Composable
private fun CrispySegmentedButtonIcon(icon: DrawableResource) {
    CrispyIcon(painter = painterResource(icon), contentDescription = null)
}

/**
 * A row of connected toggle buttons: one control whose shape is continuous across the group, with
 * the checked choice carrying `Primary`. It replaces the standalone filter chips the app used for
 * section and genre pickers, which read as a different component from every other control.
 *
 * ## The position decides the shape
 *
 * Leading, middle and trailing shapes, chosen by index, are what make the buttons read as one
 * segmented control rather than several. A **single** option cannot use the leading shape -- it
 * would render as a box rounded on one side only -- so it takes [ToggleButtonDefaults.shapesFor] at
 * this row's height, which is the standalone shape. This is reachable, not hypothetical: a picker
 * whose choices come from loaded data has none to show until that data arrives.
 *
 * ## [fillWidth] is the one difference between callers
 *
 * `true` shares the row's width evenly (`Modifier.weight(1f)` each), which is right for a fixed,
 * short, known set of labels. `false` sizes each button to its own label and scrolls the row
 * sideways, which is what a variable-length set needs -- genre names run to `Documentary`, and at
 * four choices an equal share of a phone's width truncates them.
 *
 * @param onSelect called with the chosen [CrispySegmentedButton.id] on every click, including a
 *   click on the already-checked button. The row does not model a "no selection" state; a caller
 *   that wants one puts it in the list as an ordinary option.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CrispySegmentedButtonRow(
    options: List<CrispySegmentedButton>,
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    fillWidth: Boolean = true,
) {
    if (options.isEmpty()) return
    val lastIndex = options.lastIndex
    Row(
        modifier =
            if (fillWidth) {
                modifier.fillMaxWidth()
            } else {
                modifier.horizontalScroll(rememberScrollState())
            },
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        options.forEachIndexed { index, option ->
            val checked = option.id == selectedId
            val icon = option.icon
            // Bound to a local and given an explicit type because `ToggleButton`'s `icon` slot is
            // `@Composable (() -> Unit)?`: building that value inline reads as a call made inside
            // the row rather than as the slot's own content.
            val iconSlot: (@Composable () -> Unit)? =
                if (icon == null) {
                    null
                } else {
                    { CrispySegmentedButtonIcon(icon) }
                }
            ToggleButton(
                checked = checked,
                onCheckedChange = { onSelect(option.id) },
                modifier =
                    if (fillWidth) {
                        Modifier.weight(1f).height(SegmentedButtonHeight)
                    } else {
                        Modifier.height(SegmentedButtonHeight)
                    },
                icon = iconSlot,
                shapes =
                    when {
                        options.size == 1 -> ToggleButtonDefaults.shapesFor(SegmentedButtonHeight)
                        index == 0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        index == lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    },
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Text(
                    text = option.label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (checked) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}