package com.crispy.tv.playerui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.crispy.tv.settings.SubtitleAppearance

/**
 * The app-drawn subtitle cue. mpv's tunneled mediacodec_embed surface cannot
 * carry mpv-rendered subtitles, so the player reads `sub-text` and draws it here
 * instead; the text arrives already sanitised ([PlayerUiState.currentSubtitleText]).
 *
 * ## Why the box hugs its text instead of spanning the screen
 *
 * A wide black bar behind two words reads as a caption *track*, not as a
 * subtitle -- and the previous `fillMaxWidth()` box did exactly that. Every line
 * is its own row here, sized to its own text (a `Box` around a `Text`, so the
 * background wraps the glyphs), the way the web player's
 * `moviSubtitles.css` does it (`width: fit-content` per line, the block
 * `inline-block`). The square corners are deliberate: the web renderer sets
 * `border-radius: 0`, so a rounded box would be a third style nobody chose.
 *
 * ## Why the font scales with the surface rather than with the text size
 *
 * `TextScale` follows the user's system font, so a large-font device would push
 * the cue off the picture while a default-font device showed a tiny one. The
 * size is therefore taken from the *surface* (3.2% of the width, clamped
 * 20.dp-40.dp, times the chosen percentage) which is how the web player's
 * `clamp(20px, player-width * 0.032, 40px)` behaves, and the appearance's
 * percentage is the only thing the viewer's setting can change.
 *
 * ## Why the outline is dropped when a background is present
 *
 * A shadow around glyphs already sitting on a visible box adds a halo the web
 * renderer deliberately avoids (`text-shadow: none` when the background is
 * visible). The rule is read off the appearance, not duplicated here, so the
 * one precedence lives in `SubtitleAppearance.hasBackground`.
 */
@Composable
internal fun PlayerSubtitleCue(
    text: String?,
    appearance: SubtitleAppearance,
    modifier: Modifier = Modifier,
    controlsLift: Dp = 0.dp,
) {
    val lines =
        remember(text) {
            text
                ?.lines()
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?: emptyList()
        }
    if (lines.isEmpty()) return

    BoxWithConstraints(modifier = modifier) {
        // Measured off the surface in Dp, used as Sp: `TextStyle` takes a
        // `TextUnit` and the two unit families do not meet, so the measurement
        // crosses once, here, rather than at each of the three fields.
        val fontDp = (maxWidth * SubtitleFontWidthPercent).coerceIn(MinSubtitleFontSize, MaxSubtitleFontSize)
        val fontSizeValue = fontDp / 1.dp * (appearance.sizePercent / 100f)
        val fontSize = fontSizeValue.sp
        val bottomInset = maxHeight * (appearance.positionPercent / 100f) + controlsLift
        val textColor = Color(appearance.textColor)
        val borderColor = Color(appearance.borderColor)
        val baseStyle = MaterialTheme.typography.bodyLarge

        Column(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = bottomInset),
            verticalArrangement = Arrangement.spacedBy(LineGapDp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            for (line in lines) {
                Box(
                    modifier =
                        Modifier
                            .background(
                                color = if (appearance.hasBackground) Color(appearance.background) else Color.Transparent,
                                shape = RectangleShape,
                            )
                            .padding(horizontal = RowPaddingHorizontal, vertical = RowPaddingVertical),
                ) {
                    Text(
                        text = line,
                        color = textColor,
                        style =
                            baseStyle.copy(
                                fontSize = fontSize,
                                lineHeight = (fontSizeValue * SubtitleLineHeightMultiplier).sp,
                                fontWeight = FontWeight.Medium,
                                letterSpacing = (fontSizeValue * SubtitleLetterSpacingFraction).sp,
                                // No outline when the row already carries a
                                // background; see the KDoc above.
                                shadow =
                                    if (appearance.hasBackground) {
                                        null
                                    } else {
                                        Shadow(
                                            color = borderColor,
                                            offset = Offset(1f, 1f),
                                            blurRadius = OutlineBlurPx,
                                        )
                                    },
                            ),
                    )
                }
            }
        }
    }
}

/** 3.2% of the surface width, the web renderer's `player-width * 0.032`. */
private const val SubtitleFontWidthPercent = 0.032f
private val MinSubtitleFontSize = 20.dp
private val MaxSubtitleFontSize = 40.dp
private const val SubtitleLineHeightMultiplier = 1.35f
private const val SubtitleLetterSpacingFraction = 0.01f
private val LineGapDp = 2.dp
private val RowPaddingHorizontal = 12.dp
private val RowPaddingVertical = 2.dp
private const val OutlineBlurPx = 2f
