package com.crispy.tv.watchhistory

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.crispy.tv.domain.watch.ContinueWatchingPlanItem
import com.crispy.tv.ui.components.CardStyle
import com.crispy.tv.ui.theme.CrispyRewriteTheme
import com.crispy.tv.ui.theme.Dimensions
import com.crispy.tv.ui.theme.responsivePageHorizontalPadding
import kotlin.math.roundToInt

/** The app's near-black window background, as used on Android. */
private val SCREEN_BACKGROUND = Color(0xFF0E0E11)

/**
 * The Continue Watching rail, rendered from real planner output.
 *
 * ## Why this is public
 *
 * It is the first `:app` composable a non-Android target renders, and it is public
 * because that is the whole point: `:android:desktopApp` reaches this file through
 * `:app`'s `desktop` JVM variant. Every other screen assembly in `:app` is
 * `internal` and stays that way -- a screen's caller lives in the same module, and
 * widening them all would be an API surface nobody asked for. This one has a caller
 * outside the module, so it is the one that has to be public.
 *
 * ## Why it is parameterised over plain values
 *
 * `ContinueWatchingPlanItem` is a *ranking*, not catalogue data: `contentType`,
 * `contentId`, `episodeKey`, `progressPercent`, `lastUpdatedMs`,
 * `isUpNextPlaceholder` -- and no title, artwork, year or genre. So this rail
 * cannot render the `LandscapeCard` that `:app`'s other rails use, and there is no
 * mapping to `CatalogItem` that could make it, because `CatalogItem` requires a
 * non-blank title and there is no title here to map *from*. The planner's job is
 * ordering, filtering and deduplication; everything a card needs arrives later, from
 * the catalogue.
 *
 * That is also why there is no image loading. Coil's portable network engine is
 * Phase 2 work, and `AsyncImage` would make this the one composable in the rail
 * that cannot resolve on desktop. The backdrop is a deterministic gradient, so
 * layout, spacing and text-over-backdrop contrast are genuinely exercised without
 * dragging an image pipeline onto a target that does not have one yet.
 *
 * ## What is real here
 *
 * The ordering, dedupe and filtering come from `:android:core-domain`'s
 * `planContinueWatching`, the exact function the contract suite pins against
 * fixtures; the spacing from `:android:sharedUI`'s `Dimensions`; the colours and
 * typography from `CrispyRewriteTheme`; and the corner radius from `CardStyle`,
 * the same constant `LandscapeCard` uses. Change the planner and this rail changes
 * with it, and the desktop test fails in the same run as the contract suite.
 */
@Composable
fun ContinueWatchingRail(
    items: List<ContinueWatchingPlanItem>,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    CrispyRewriteTheme {
        LazyColumn(
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(Dimensions.SectionSpacing),
            modifier = modifier
                .fillMaxSize()
                .background(SCREEN_BACKGROUND),
        ) {
            item(key = "header") {
                Text(
                    text = "Continue watching",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    modifier = Modifier.padding(
                        top = Dimensions.PageTopPadding,
                        start = responsivePageHorizontalPadding(),
                        end = responsivePageHorizontalPadding(),
                    ),
                )
            }

            items(items, key = { "${it.contentType}:${it.contentId}:${it.episodeKey}" }) { item ->
                ContinueWatchingCard(
                    item = item,
                    modifier = Modifier.padding(horizontal = responsivePageHorizontalPadding()),
                )
            }
        }
    }
}

@Composable
private fun ContinueWatchingCard(
    item: ContinueWatchingPlanItem,
    modifier: Modifier = Modifier,
) {
    val percent = item.progressPercent.roundToInt()
    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = describe(item) },
        shape = RoundedCornerShape(CardStyle.CardCornerRadiusDp.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1B1B1F)),
    ) {
        Row(
            modifier = Modifier.padding(Dimensions.CardInternalPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Backdrop(contentId = item.contentId)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = Dimensions.CardInternalPadding),
                verticalArrangement = Arrangement.spacedBy(Dimensions.SectionSpacing / 2),
            ) {
                Text(
                    text = item.contentId,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                item.episodeKey?.let { episodeKey ->
                    Text(
                        text = episodeKey,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFB9B9C6),
                    )
                }
                LinearProgressIndicator(
                    progress = { (item.progressPercent / 100.0).toFloat() },
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color(0xFF2E2E36),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "$percent% watched",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF8F8F9E),
                )
            }
        }
    }
}

/**
 * A deterministic colour derived from the content id.
 *
 * A real screen shows artwork; this shows a gradient so that layout, spacing and
 * the text-over-backdrop contrast are all genuinely exercised without pulling an
 * image pipeline onto the desktop target.
 */
@Composable
private fun Backdrop(contentId: String) {
    val hue = (contentId.hashCode().toFloat() % 360f + 360f) % 360f
    Box(
        modifier = Modifier
            .width(Dimensions.WideCardWidth)
            .aspectRatio(Dimensions.WideCardAspectRatio)
            .clip(RoundedCornerShape(CardStyle.CardCornerRadiusDp.dp))
            .background(
                Brush.linearGradient(
                    listOf(Color.hsl(hue, 0.45f, 0.32f), Color.hsl(hue, 0.55f, 0.18f)),
                ),
            ),
    )
}

/**
 * What an assistive technology announces for [item].
 *
 * Public rather than private so the test asserts against the same string the rail
 * publishes. It was private at first and the test reimplemented it, and the two
 * drifted immediately: the rail rounds `progressPercent` and the test truncated
 * it, so `61.5` produced "62 percent watched" on screen and "61 percent watched"
 * in the assertion. One definition is the only thing that cannot drift.
 */
fun describe(item: ContinueWatchingPlanItem): String = buildString {
    append(item.contentId)
    item.episodeKey?.let { append(", $it") }
    append(", ${item.progressPercent.roundToInt()} percent watched")
}