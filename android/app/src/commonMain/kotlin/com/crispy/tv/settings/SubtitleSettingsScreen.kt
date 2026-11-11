package com.crispy.tv.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.crispy.tv.playerui.PlayerSubtitleCue
import com.crispy.tv.ui.components.CrispyIcon
import com.crispy.tv.ui.components.StandardTopAppBar
import com.crispy.tv.ui.edge_to_edge.safeBottomPadding
import com.crispy.tv.ui.resources.Res
import com.crispy.tv.ui.resources.ic_arrow_back
import com.crispy.tv.ui.theme.Dimensions
import com.crispy.tv.ui.utils.appBarScrollBehavior
import org.jetbrains.compose.resources.painterResource

/**
 * The subtitle appearance settings: the four boxes, the three text colours, the
 * size and the position.
 *
 * The preview is [PlayerSubtitleCue] itself rather than a second drawing of the
 * cue. A preview that could drift from what the player renders is worse than no
 * preview, and the composable already scales its type off the surface it is
 * given, so the card shows the same geometry, only smaller.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubtitleSettingsScreen(
    appearance: SubtitleAppearance,
    onBoxStyleSelected: (SubtitleBoxStyle) -> Unit,
    onTextColorSelected: (SubtitleTextColor) -> Unit,
    onSizePercentChanged: (Int) -> Unit,
    onPositionPercentChanged: (Int) -> Unit,
    onBack: () -> Unit,
) {
    val scrollBehavior = appBarScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            StandardTopAppBar(
                title = "Subtitles",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        CrispyIcon(
                            painter = painterResource(Res.drawable.ic_arrow_back),
                            contentDescription = "Back",
                            autoMirror = true,
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(Dimensions.ListItemPadding)
                    .padding(bottom = safeBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SubtitlePreview(appearance)

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    SettingsSectionHeader(
                        title = "Box",
                        description = "The background drawn behind each line of text.",
                    )
                    SubtitleBoxStyle.entries.forEach { box ->
                        ListItem(
                            leadingContent = { SubtitleBoxSwatch(box) },
                            trailingContent = {
                                RadioButton(
                                    selected = appearance.matchesBox(box),
                                    onClick = { onBoxStyleSelected(box) },
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(box.label)
                        }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    SettingsSectionHeader(
                        title = "Text",
                        description = "Picking a box restores the text colour that reads on it.",
                    )
                    SubtitleTextColor.entries.forEach { textColor ->
                        ListItem(
                            leadingContent = {
                                Box(
                                    modifier =
                                        Modifier
                                            .size(Dimensions.AvatarSize)
                                            .clip(CircleShape)
                                            .background(Color(textColor.argb)),
                                )
                            },
                            trailingContent = {
                                RadioButton(
                                    selected = appearance.matchesTextColor(textColor),
                                    onClick = { onTextColorSelected(textColor) },
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(textColor.label)
                        }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    SettingsSectionHeader(
                        title = "Size",
                        description = "Scales the cue's text with the screen.",
                    )
                    PercentStepperRow(
                        label = "Text size",
                        percent = appearance.sizePercent,
                        minPercent = SubtitleAppearance.MIN_SIZE_PERCENT,
                        maxPercent = SubtitleAppearance.MAX_SIZE_PERCENT,
                        step = SubtitleAppearance.SIZE_STEP,
                        onPercentChanged = onSizePercentChanged,
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    SettingsSectionHeader(
                        title = "Position",
                        description = "How far above the bottom edge the cue sits.",
                    )
                    PercentStepperRow(
                        label = "Bottom offset",
                        percent = appearance.positionPercent,
                        minPercent = SubtitleAppearance.MIN_POSITION_PERCENT,
                        maxPercent = SubtitleAppearance.MAX_POSITION_PERCENT,
                        step = SubtitleAppearance.POSITION_STEP,
                        onPercentChanged = onPositionPercentChanged,
                    )
                }
            }
        }
    }
}

/** The cue over a black frame, so the box, the size and the position all show. */
@Composable
private fun SubtitlePreview(appearance: SubtitleAppearance) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(SubtitlePreviewHeight)
                .clip(MaterialTheme.shapes.large)
                .background(Color.Black),
    ) {
        PlayerSubtitleCue(
            text = SubtitlePreviewCue,
            appearance = appearance,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun SettingsSectionHeader(title: String, description: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
    Text(
        text = description,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
    )
}

/** The style's own box and text, so a swatch says what the row means. */
@Composable
private fun SubtitleBoxSwatch(box: SubtitleBoxStyle) {
    Box(
        modifier =
            Modifier
                .size(width = SubtitleSwatchWidth, height = SubtitleSwatchHeight)
                .clip(MaterialTheme.shapes.extraSmall)
                .background(Color(SubtitleHex.parse(box.backgroundHex, 0L))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Aa",
            color = Color(SubtitleHex.parse(box.textColorHex, 0xFF000000L)),
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * A percentage row stepped by [step], clamped at both ends.
 *
 * The buttons are glyphs rather than icons because there is no minus icon in
 * the design set, and an icon invented for one settings screen is a res nobody
 * else can name. The buttons disable at their bounds so the displayed percent
 * never disagrees with what the repository accepted.
 */
@Composable
private fun PercentStepperRow(
    label: String,
    percent: Int,
    minPercent: Int,
    maxPercent: Int,
    step: Int,
    onPercentChanged: (Int) -> Unit,
) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = { Text("$percent%") },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(
                    onClick = { onPercentChanged((percent - step).coerceAtLeast(minPercent)) },
                    enabled = percent > minPercent,
                ) {
                    Text(text = "−", style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    text = "$percent%",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                FilledTonalIconButton(
                    onClick = { onPercentChanged((percent + step).coerceAtMost(maxPercent)) },
                    enabled = percent < maxPercent,
                ) {
                    Text(text = "+", style = MaterialTheme.typography.titleMedium)
                }
            }
        },
    )
}

private val SubtitlePreviewHeight = 132.dp
private val SubtitleSwatchWidth = 52.dp
private val SubtitleSwatchHeight = 30.dp

private const val SubtitlePreviewCue =
    "Then what are we waiting for?\nLet's get out of here."
