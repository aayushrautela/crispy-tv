package com.crispy.tv.details

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crispy.tv.addons.model.MediaDetails
import com.crispy.tv.addons.util.normalizeRatingText
import com.crispy.tv.domain.optimistic.FieldSync
import com.crispy.tv.ui.components.CrispyIcon
import com.crispy.tv.ui.components.skeletonElement
import com.crispy.tv.ui.resources.Res
import com.crispy.tv.ui.resources.ic_bookmark
import com.crispy.tv.ui.resources.ic_bookmark_filled
import com.crispy.tv.ui.resources.ic_check_circle
import com.crispy.tv.ui.resources.ic_check_circle_filled
import com.crispy.tv.ui.resources.ic_keyboard_arrow_down_filled
import com.crispy.tv.ui.resources.ic_keyboard_arrow_up_filled
import com.crispy.tv.ui.resources.ic_play_arrow_filled
import com.crispy.tv.ui.resources.ic_replay
import com.crispy.tv.ui.resources.ic_share
import com.crispy.tv.ui.resources.ic_star_filled
import com.crispy.tv.ui.resources.ic_thumb_down
import com.crispy.tv.ui.resources.ic_thumb_down_filled
import com.crispy.tv.ui.resources.ic_thumb_up
import com.crispy.tv.ui.resources.ic_thumb_up_filled
import com.crispy.tv.ui.resources.ic_wand_stars
import com.crispy.tv.ui.theme.responsivePageHorizontalPadding
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

private val AiInsightsBorderColors =
    listOf(
        Color(0xFF4285F4),
        Color(0xFF34A853),
        Color(0xFFFBBC05),
        Color(0xFFEA4335),
        Color(0xFF4285F4),
    )

@Composable
private fun Modifier.aiInsightsBorderModifier(showBorder: Boolean): Modifier {
    if (!showBorder) return this

    val transition = rememberInfiniteTransition(label = "ai_insights_border")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "ai_insights_border_sweep",
    )
    val glow by transition.animateFloat(
        initialValue = 0.18f,
        targetValue = 0.42f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "ai_insights_border_glow",
    )

    return this.then(
        Modifier.drawWithContent {
            drawContent()

            val strokeWidth = 1.5.dp.toPx()
            val inset = strokeWidth / 2f
            val maxGlowWidth = 6.dp.toPx()
            val cornerRadius = CornerRadius(28.dp.toPx(), 28.dp.toPx())
            val brush = Brush.linearGradient(
                colors = AiInsightsBorderColors,
                start = Offset(
                    x = size.width * sweep,
                    y = size.height * sweep,
                ),
                end = Offset(
                    x = size.width * (sweep + 1f),
                    y = size.height * (sweep + 1f),
                ),
                tileMode = TileMode.Repeated,
            )

            val glowLevels = 5
            for (i in 1..glowLevels) {
                val w = maxGlowWidth * (glowLevels - i + 1) / glowLevels.toFloat()
                val off = -w / 2f
                drawRoundRect(
                    brush = brush,
                    topLeft = Offset(off, off),
                    size = Size(size.width - 2f * off, size.height - 2f * off),
                    cornerRadius = CornerRadius(28.dp.toPx() - off, 28.dp.toPx() - off),
                    style = Stroke(width = w),
                    alpha = (glow / glowLevels) * 1.5f,
                )
            }

            drawRoundRect(
                brush = brush,
                topLeft = Offset(inset, inset),
                size = Size(size.width - strokeWidth, size.height - strokeWidth),
                cornerRadius = cornerRadius,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                alpha = 0.55f,
            )
        },
    )
}

/**
 * The second line under the watch CTA, or `null` when the CTA has nothing to add.
 *
 * This lived inside [HeaderInfoSection]'s body and appeared **twice**, byte for
 * byte, once per layout branch -- a wide screen and a narrow one. A `when` inside a
 * `@Composable` is not callable from a test, so both copies were unreachable: a
 * suite would have had to render the whole header to check which arm was chosen,
 * and deleting an arm would have failed nothing. Extracting it makes one decision
 * with one implementation, which is also the only reason the duplication can go --
 * the two branches legitimately differ in layout and must not be merged.
 *
 * The arms are **not** the same order as they look. A rewatch that also has a
 * running time resolves to the rewatch line, so `remainingMinutes` only decides
 * anything when the title has not been watched before. That ordering is the copy's
 * decision and it is pinned by `DetailsHeaderSubtextTest`.
 *
 * Both formatters and the clock are slots rather than calls to `java.text` and
 * `System.currentTimeMillis()`; the strings they build are unchanged, which is what
 * lets this function be pure enough to test.
 */
internal fun watchCtaSubtext(
    watchCta: WatchCta,
    dateFormat: (Long) -> String,
    timeFormat: (Long) -> String,
    clock: () -> Long,
): String? =
    when {
        watchCta.kind == WatchCtaKind.REWATCH && watchCta.lastWatchedAtEpochMs != null -> {
            val date = dateFormat(watchCta.lastWatchedAtEpochMs)
            "Last watched on $date"
        }
        watchCta.remainingMinutes != null -> {
            val endsAtMs = clock() + (watchCta.remainingMinutes * 60_000L)
            val time = timeFormat(endsAtMs)
            "Ends at $time"
        }
        else -> null
    }

@Composable
internal fun HeaderInfoSection(
    details: MediaDetails?,
    isInWatchlist: Boolean,
    isWatched: Boolean,
    liked: Boolean?,
    optimisticSync: OptimisticSync,
    palette: DetailsPaletteColors,
    watchCta: WatchCta,
    aiInsightsIsLoading: Boolean,
    onAiInsightsClick: () -> Unit,
    onWatchNow: () -> Unit,
    onToggleWatchlist: () -> Unit,
    onToggleWatched: () -> Unit,
    onSetLiked: (Boolean?) -> Unit,
    // These four are capabilities the platform owns, not wiring, so they arrive as
    // slots with no default: a call site cannot forget one.
    //
    // `shareText` is the 5th instance of this shape in the repository (after
    // `openUrl` and `launchUrl`): `android.content.Intent` and `android.net.Uri`
    // cannot appear in a commonMain signature at all, so the slot carries the text
    // and the whole Intent/chooser construction lives in the androidMain side.
    //
    // `dateFormat` / `timeFormat` are locale-aware renderings, which is precisely
    // why this file could not be moved earlier. `formatIso8601LongDate` in
    // :core-domain is the *locale-invariant* formatter and would print a different
    // string than the user is used to, so the rendering stays a slot and only the
    // decision moves to commonMain.
    //
    // `clock` is injected rather than read from the system, so the countdown below
    // is a value the tests can pin.
    shareText: (String) -> Unit,
    dateFormat: (Long) -> String,
    timeFormat: (Long) -> String,
    clock: () -> Long,
    // The wide/narrow decision. It used to be read twice in this file from
    // `LocalConfiguration`, which is Android-only even under Compose Multiplatform
    // (it lives in `AndroidCompositionLocals_androidKt`) -- and this file's two
    // copies had drifted apart in indentation, one of them by four spaces.
    // `LocalWindowInfo.current.containerDpSize` is not the answer: that symbol does
    // not exist in the resolved Compose artifacts, so a "portable replacement" here
    // would have been a guess. The caller already reads `LocalConfiguration`, so the
    // value crosses as a slot and the duplicate reads go away with it.
    isWideScreen: Boolean,
    softFade: Modifier = Modifier,
) {
    val horizontalPadding = responsivePageHorizontalPadding()

    if (details == null) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding)
                .padding(top = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Genre skeleton
            Box(
                modifier = Modifier
                    .width(80.dp)
                    .height(18.dp)
                    .skeletonElement(color = DetailsSkeletonColors.Base)
            )

            // Rating/Meta row skeleton
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(modifier = Modifier.width(50.dp).height(18.dp).skeletonElement(color = DetailsSkeletonColors.Base))
                Box(modifier = Modifier.width(40.dp).height(18.dp).skeletonElement(color = DetailsSkeletonColors.Base))
                Box(modifier = Modifier.width(60.dp).height(18.dp).skeletonElement(color = DetailsSkeletonColors.Base))
            }

            // Description skeleton
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(modifier = Modifier.fillMaxWidth(0.9f).height(14.dp).skeletonElement(color = DetailsSkeletonColors.Base))
                Box(modifier = Modifier.fillMaxWidth(0.84f).height(14.dp).skeletonElement(color = DetailsSkeletonColors.Base))
                Box(modifier = Modifier.fillMaxWidth(0.6f).height(14.dp).skeletonElement(color = DetailsSkeletonColors.Base))
            }

            if (isWideScreen) {
                // Buttons skeleton (wide)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier.weight(2f),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp)
                                .skeletonElement(shape = RoundedCornerShape(28.dp), color = DetailsSkeletonColors.Base)
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp)
                                .skeletonElement(shape = RoundedCornerShape(28.dp), color = DetailsSkeletonColors.Elevated)
                        )
                    }
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        repeat(4) {
                            Box(modifier = Modifier.size(48.dp).skeletonElement(color = DetailsSkeletonColors.Base))
                        }
                    }
                }
            } else {
                // Buttons skeleton (compact)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .skeletonElement(shape = RoundedCornerShape(28.dp), color = DetailsSkeletonColors.Base)
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .skeletonElement(shape = RoundedCornerShape(28.dp), color = DetailsSkeletonColors.Elevated)
                )

                // Quick actions skeleton
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(4) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(vertical = 6.dp)
                        ) {
                            Box(modifier = Modifier.size(48.dp).skeletonElement(color = DetailsSkeletonColors.Base))
                            Box(modifier = Modifier.width(44.dp).height(10.dp).skeletonElement(color = DetailsSkeletonColors.Base))
                        }
                    }
                }
            }
        }
        return
    }


    val genre = details.genres.take(2).joinToString(" · ") { it.trim() }

    Column(
        modifier = softFade
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding)
            .padding(top = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (genre.isNotBlank()) {
            Text(
                text = genre,
                style = MaterialTheme.typography.labelLarge,
                color = palette.onPageBackground.copy(alpha = 0.86f),
            )
        }

        HeaderMetaRow(details = details, palette = palette)

        ExpandableDescription(
            text = details.description,
            modifier = Modifier.padding(bottom = 4.dp),
            textAlign = TextAlign.Center,
            textColor = palette.onPageBackground.copy(alpha = 0.9f),
            placeholderColor = Color(0xFF9E9E9E)
        )

        var showAiInsightsBorder by remember { mutableStateOf(false) }
        if (aiInsightsIsLoading) showAiInsightsBorder = true

        if (isWideScreen) {
            val watchCtaSubtext = watchCtaSubtext(watchCta, dateFormat, timeFormat, clock)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .weight(2f),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    FilledTonalButton(
                        onClick = {
                            showAiInsightsBorder = false
                            onAiInsightsClick()
                        },
                        enabled = !aiInsightsIsLoading,
                        modifier =
                            Modifier
                                .weight(1f)
                                .height(56.dp)
                                .aiInsightsBorderModifier(showAiInsightsBorder),
                        shape = MaterialTheme.shapes.extraLarge,
                        colors =
                            ButtonDefaults.filledTonalButtonColors(
                                containerColor = palette.pillBackgroundSolid,
                                contentColor = palette.onPillBackground,
                                disabledContainerColor = palette.pillBackgroundSolid.copy(alpha = 0.65f),
                                disabledContentColor = palette.onPillBackground.copy(alpha = 0.65f)
                            )
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier.width(34.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CrispyIcon(
                                    painter = painterResource(Res.drawable.ic_wand_stars),
                                    contentDescription = null,
                                )
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text("AI insights")
                            }
                            Spacer(modifier = Modifier.width(34.dp))
                        }
                    }

                    Button(
                        onClick = onWatchNow,
                        enabled = true,
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp),
                        shape = MaterialTheme.shapes.extraLarge,
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = palette.accent,
                                contentColor = palette.onAccent
                            )
                    ) {
                        val iconVector =
                            when (watchCta.icon) {
                                WatchCtaIcon.REPLAY -> Res.drawable.ic_replay
                                WatchCtaIcon.PLAY -> Res.drawable.ic_play_arrow_filled
                            }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier.width(34.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CrispyIcon(
                                    painter = painterResource(iconVector),
                                    contentDescription = null,
                                )
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    text = watchCta.label,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                )
                                if (watchCtaSubtext != null) {
                                    Text(
                                        text = watchCtaSubtext,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = palette.onAccent.copy(alpha = 0.85f),
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(34.dp))
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .weight(1f),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    DetailsQuickAction(
                        label = "Watchlist",
                        selected = isInWatchlist,
                        sync = optimisticSync.watchlist,
                        palette = palette,
                        icon = if (isInWatchlist) Res.drawable.ic_bookmark_filled else Res.drawable.ic_bookmark,
                        onClick = onToggleWatchlist
                    )

                    DetailsQuickAction(
                        label = "Watched",
                        selected = isWatched,
                        sync = optimisticSync.watched,
                        palette = palette,
                        icon = if (isWatched) Res.drawable.ic_check_circle_filled else Res.drawable.ic_check_circle,
                        onClick = onToggleWatched
                    )

                    DetailsQuickAction(
                        label = "Like",
                        selected = liked == true,
                        sync = optimisticSync.rating,
                        palette = palette,
                        icon = if (liked == true) Res.drawable.ic_thumb_up_filled else Res.drawable.ic_thumb_up,
                        onClick = { onSetLiked(if (liked == true) null else true) }
                    )

                    DetailsQuickAction(
                        label = "Dislike",
                        selected = liked == false,
                        sync = optimisticSync.rating,
                        palette = palette,
                        icon = if (liked == false) Res.drawable.ic_thumb_down_filled else Res.drawable.ic_thumb_down,
                        onClick = { onSetLiked(if (liked == false) null else false) }
                    )

                    DetailsQuickAction(
                        label = "Share",
                        selected = false,
                        sync = OptimisticSyncBadge(),
                        palette = palette,
                        icon = Res.drawable.ic_share,
                        onClick = {
                            val title = details.title
                            shareText("Check out $title on Crispy")
                        }
                    )
                }
            }
        } else {
            FilledTonalButton(
                onClick = {
                    showAiInsightsBorder = false
                    onAiInsightsClick()
                },
                enabled = !aiInsightsIsLoading,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .aiInsightsBorderModifier(showAiInsightsBorder),
                shape = MaterialTheme.shapes.extraLarge,
                colors =
                    ButtonDefaults.filledTonalButtonColors(
                        containerColor = palette.pillBackgroundSolid,
                        contentColor = palette.onPillBackground,
                        disabledContainerColor = palette.pillBackgroundSolid.copy(alpha = 0.65f),
                        disabledContentColor = palette.onPillBackground.copy(alpha = 0.65f)
                    )
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier.width(34.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CrispyIcon(
                            painter = painterResource(Res.drawable.ic_wand_stars),
                            contentDescription = null,
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("AI insights")
                    }
                    Spacer(modifier = Modifier.width(34.dp))
                }
            }

            val watchCtaSubtext = watchCtaSubtext(watchCta, dateFormat, timeFormat, clock)

            Button(
                onClick = onWatchNow,
                enabled = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = MaterialTheme.shapes.extraLarge,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = palette.accent,
                        contentColor = palette.onAccent
                    )
            ) {
                val iconVector =
                    when (watchCta.icon) {
                        WatchCtaIcon.REPLAY -> Res.drawable.ic_replay
                        WatchCtaIcon.PLAY -> Res.drawable.ic_play_arrow_filled
                    }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier.width(34.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CrispyIcon(
                            painter = painterResource(iconVector),
                            contentDescription = null,
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = watchCta.label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                        if (watchCtaSubtext != null) {
                            Text(
                                text = watchCtaSubtext,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.onAccent.copy(alpha = 0.85f),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(34.dp))
                }
            }

            DetailsQuickActionsRow(
                palette = palette,
                isInWatchlist = isInWatchlist,
                isWatched = isWatched,
                liked = liked,
                optimisticSync = optimisticSync,
                onToggleWatchlist = onToggleWatchlist,
                onToggleWatched = onToggleWatched,
                onSetLiked = onSetLiked,
                onShare = {
                    val title = details.title
                    shareText("Check out $title on Crispy")
                }
            )
        }

        Spacer(modifier = Modifier.height(2.dp))
    }
}

@Composable
private fun DetailsQuickActionsRow(
    palette: DetailsPaletteColors,
    isInWatchlist: Boolean,
    isWatched: Boolean,
    liked: Boolean?,
    optimisticSync: OptimisticSync,
    modifier: Modifier = Modifier,
    onToggleWatchlist: () -> Unit,
    onToggleWatched: () -> Unit,
    onSetLiked: (Boolean?) -> Unit,
    onShare: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        DetailsQuickAction(
            label = "Watchlist",
            selected = isInWatchlist,
            sync = optimisticSync.watchlist,
            palette = palette,
            icon = if (isInWatchlist) Res.drawable.ic_bookmark_filled else Res.drawable.ic_bookmark,
            onClick = onToggleWatchlist
        )

        DetailsQuickAction(
            label = "Watched",
            selected = isWatched,
            sync = optimisticSync.watched,
            palette = palette,
            icon = if (isWatched) Res.drawable.ic_check_circle_filled else Res.drawable.ic_check_circle,
            onClick = onToggleWatched
        )

        DetailsQuickAction(
            label = "Like",
            selected = liked == true,
            sync = optimisticSync.rating,
            palette = palette,
            icon = if (liked == true) Res.drawable.ic_thumb_up_filled else Res.drawable.ic_thumb_up,
            onClick = { onSetLiked(if (liked == true) null else true) }
        )

        DetailsQuickAction(
            label = "Dislike",
            selected = liked == false,
            sync = optimisticSync.rating,
            palette = palette,
            icon = if (liked == false) Res.drawable.ic_thumb_down_filled else Res.drawable.ic_thumb_down,
            onClick = { onSetLiked(if (liked == false) null else false) }
        )

        DetailsQuickAction(
            label = "Share",
            selected = false,
            sync = OptimisticSyncBadge(),
            palette = palette,
            icon = Res.drawable.ic_share,
            onClick = onShare
        )
    }
}

@Composable
private fun DetailsQuickAction(
    label: String,
    selected: Boolean,
    sync: OptimisticSyncBadge,
    palette: DetailsPaletteColors,
    selectedAccent: Color? = null,
    icon: DrawableResource,
    onClick: () -> Unit
) {
    val accent = selectedAccent ?: palette.accent
    val container = if (selected) lerp(palette.pillBackgroundSolid, accent, 0.28f) else palette.pillBackgroundSolid
    val isError = sync.status == FieldSync.ERROR
    val iconTint =
        when {
            isError -> Color(0xFFE5484D)
            selected -> accent
            else -> palette.onPillBackground.copy(alpha = 0.92f)
        }
    val labelAlpha = if (isError) 0.9f else 0.9f

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(vertical = 6.dp)
    ) {
        Surface(
            modifier = Modifier
                .size(48.dp)
                .clip(MaterialTheme.shapes.extraLarge)
                .clickable { onClick() },
            color = container,
            contentColor = palette.onPillBackground
        ) {
            androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                CrispyIcon(
                    painter = painterResource(icon),
                    contentDescription = label,
                    tint = iconTint,
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        Text(
            text = if (isError) "Retry" else label,
            style = MaterialTheme.typography.labelSmall,
            color = palette.onPageBackground.copy(alpha = labelAlpha)
        )
    }
}

@Composable
private fun HeaderMetaRow(
    details: MediaDetails,
    palette: DetailsPaletteColors,
    modifier: Modifier = Modifier
) {
    val rating = normalizeRatingText(details.rating)
    val certification = details.certification?.trim().takeIf { !it.isNullOrBlank() }
    val year = details.year?.trim().takeIf { !it.isNullOrBlank() }
    val runtime = formatRuntimeForHeader(details.runtime)

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (rating != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                CrispyIcon(
                    painter = painterResource(Res.drawable.ic_star_filled),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = Color(0xFFFFD54F)
                )
                Text(
                    text = rating,
                    style = MaterialTheme.typography.titleSmall,
                    color = palette.onPageBackground
                )
            }
        }

        if (year != null) {
            Text(
                text = year,
                style = MaterialTheme.typography.labelLarge,
                color = palette.onPageBackground.copy(alpha = 0.86f)
            )
        }

        if (certification != null) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = palette.pillBackground,
                contentColor = palette.onPillBackground,
                border = BorderStroke(1.dp, palette.onPillBackground.copy(alpha = 0.3f))
            ) {
                Text(
                    text = certification,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }

        if (runtime != null) {
            Text(
                text = runtime,
                style = MaterialTheme.typography.labelLarge,
                color = palette.onPageBackground.copy(alpha = 0.86f)
            )
        }
    }
}

@Composable
internal fun ExpandableDescription(
    text: String?,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Start,
    textColor: Color = androidx.compose.material3.MaterialTheme.colorScheme.onBackground,
    placeholderColor: Color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
) {
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    val content = text?.trim().orEmpty()
    if (content.isBlank()) {
        Text(
            text = "No description available.",
            style = MaterialTheme.typography.bodyMedium,
            color = placeholderColor,
            textAlign = textAlign
        )
        return
    }

    var textLayoutResult by remember(content) { mutableStateOf<TextLayoutResult?>(null) }
    val canExpand = (textLayoutResult?.hasVisualOverflow == true) || expanded
    val toggleDescriptionLabel = if (expanded) "Collapse description" else "Expand description"
    val inlineToggleId = "description_toggle"
    val inlineText =
        remember(content, canExpand) {
            buildAnnotatedString {
                append(content)
                if (canExpand) {
                    append(' ')
                    appendInlineContent(inlineToggleId, toggleDescriptionLabel)
                }
            }
        }
    val inlineToggleContent =
        if (canExpand) {
            mapOf(
                inlineToggleId to
                    androidx.compose.foundation.text.InlineTextContent(
                        Placeholder(
                            width = 18.sp,
                            height = 18.sp,
                            placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                        )
                    ) {
                        Icon(
                            painter = if (expanded) painterResource(Res.drawable.ic_keyboard_arrow_up_filled) else painterResource(Res.drawable.ic_keyboard_arrow_down_filled),
                            contentDescription = toggleDescriptionLabel,
                            modifier = Modifier.size(18.dp),
                            tint = textColor.copy(alpha = 0.82f),
                        )
                    }
            )
        } else {
            emptyMap()
        }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize()
            .padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = inlineText,
            style = MaterialTheme.typography.bodyMedium,
            color = textColor,
            modifier =
                if (canExpand) {
                    Modifier
                        .clip(MaterialTheme.shapes.small)
                        .clickable(role = Role.Button) { expanded = !expanded }
                } else {
                    Modifier
                },
            textAlign = textAlign,
            maxLines = if (expanded) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
            inlineContent = inlineToggleContent,
            onTextLayout = { textLayoutResult = it }
        )
    }
}
