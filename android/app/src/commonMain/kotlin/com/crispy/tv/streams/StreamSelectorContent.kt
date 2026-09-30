@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.crispy.tv.streams

import com.crispy.tv.addons.streams.StreamSelectorUiState
import com.crispy.tv.addons.streams.StreamProviderUiState
import com.crispy.tv.addons.streams.AddonStream
import com.crispy.tv.addons.streams.visibleProviders
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.SheetValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.crispy.tv.addons.model.MediaDetails
import com.crispy.tv.addons.model.MediaVideo
import com.crispy.tv.ui.components.rememberCrispyImageModel
import com.crispy.tv.playerui.episodeRowMeta

internal const val SHEET_HEIGHT_FRACTION = 0.92f

internal val SHEET_MAX_WIDTH = 420.dp

/**
 * The bottom sheet that picks a provider and then a stream.
 *
 * [isCompact] is a slot rather than a `LocalConfiguration` read for the reason
 * recorded when `DetailsHeader` moved: `LocalConfiguration` lives in
 * `ui-android`'s `AndroidCompositionLocals_androidKt`, so no `commonMain` file can
 * name it and **no jar-grep of the Compose artifacts finds it either**. The
 * question it answered -- "is this window narrower than 600dp?" -- is not a
 * platform fact, and all three callers already have the answer or can read it, so
 * the value crosses as data.
 *
 * It has **no default**, so a call site cannot forget it and silently get the
 * wrong sheet width. The threshold stays at the callers, deliberately: 600dp is a
 * design decision about this sheet's `sheetMaxWidth`, and a caller that measures
 * its own window differently should be visible rather than absorbed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamSelectorSheet(
    visible: Boolean,
    state: StreamSelectorUiState,
    details: MediaDetails?,
    headerEpisode: MediaVideo?,
    accentColor: Color,
    onAccentColor: Color,
    useCrispyImageModel: Boolean = false,
    scrimColor: Color? = null,
    isCompact: Boolean,
    onDismiss: () -> Unit,
    onProviderSelected: (String?) -> Unit,
    onStreamSelected: (AddonStream) -> Unit,
) {
    if (!visible) return

    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetMaxWidth = if (isCompact) Dp.Infinity else SHEET_MAX_WIDTH,
        scrimColor = scrimColor ?: BottomSheetDefaults.ScrimColor,
        modifier = Modifier.testTag("stream_sheet"),
    ) {
        CompositionLocalProvider(LocalOverscrollFactory provides null) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(SHEET_HEIGHT_FRACTION),
            ) {
                StreamSelectorContent(
                    state = state,
                    details = details,
                    headerEpisode = headerEpisode,
                    accentColor = accentColor,
                    onAccentColor = onAccentColor,
                    useCrispyImageModel = useCrispyImageModel,
                    onProviderSelected = onProviderSelected,
                    onStreamSelected = onStreamSelected,
                )
            }
        }
    }
}

@Composable
fun StreamSelectorContent(
    state: StreamSelectorUiState,
    details: MediaDetails?,
    headerEpisode: MediaVideo?,
    accentColor: Color,
    onAccentColor: Color,
    useCrispyImageModel: Boolean,
    onProviderSelected: (String?) -> Unit,
    onStreamSelected: (AddonStream) -> Unit,
) {
    val effectiveEpisode =
        headerEpisode
            ?: details?.videos?.firstOrNull { it.lookupId?.equals(state.lookupId, ignoreCase = true) == true }
            ?: run {
                val season = details?.seasonNumber
                val episode = details?.episodeNumber
                if (season != null && episode != null) {
                    details.videos.firstOrNull { it.season == season && it.episode == episode }
                } else {
                    null
                }
            }

    val visibleProviders = remember(state.providers) { state.providers.visibleProviders() }
    val filteredProviders =
        remember(visibleProviders, state.selectedProviderId) {
            val selectedProvider = state.selectedProviderId
            if (selectedProvider.isNullOrBlank()) {
                visibleProviders
            } else {
                visibleProviders.filter { provider ->
                    provider.providerId.equals(selectedProvider, ignoreCase = true)
                }
            }
        }

    LazyColumn(
        modifier =
            Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            StreamSheetHeader(
                details = details,
                episode = effectiveEpisode,
                accentColor = accentColor,
                onAccentColor = onAccentColor,
                useCrispyImageModel = useCrispyImageModel,
            )
        }

        item {
            ProviderChipsRow(
                state = state,
                accentColor = accentColor,
                onAccentColor = onAccentColor,
                onProviderSelected = onProviderSelected,
            )
        }

        if (!state.isFetching && filteredProviders.isEmpty()) {
            item {
                ElevatedCard {
                    Text(
                        text = "No streams found for this title.",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        filteredProviders.forEach { provider ->
            if (provider.streams.isNotEmpty()) {
                items(items = provider.streams, key = { stream -> stream.stableKey }) { stream ->
                    StreamRow(
                        stream = stream,
                        providerName = provider.providerName,
                        onClick = { onStreamSelected(stream) },
                    )
                }
            }
        }
    }
}

@Composable
private fun StreamSheetHeader(
    details: MediaDetails?,
    episode: MediaVideo?,
    accentColor: Color,
    onAccentColor: Color,
    useCrispyImageModel: Boolean,
) {
    if (details == null && episode == null) return

    val imageUrl =
        episode?.thumbnailUrl
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: details?.artworkUrl
    val description =
        episode?.overview
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: details?.description?.trim()?.takeIf { it.isNotBlank() }

    ElevatedCard {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    val imageModel =
                        if (useCrispyImageModel && imageUrl != null) {
                            rememberCrispyImageModel(url = imageUrl, width = 96.dp, height = 56.dp)
                        } else {
                            null
                        }
                    val imageModifier =
                        if (useCrispyImageModel) {
                            Modifier.size(width = 96.dp, height = 56.dp)
                        } else {
                            Modifier.size(width = 96.dp, height = 56.dp).clip(RoundedCornerShape(14.dp))
                        }
                    AsyncImage(
                        model = imageModel ?: imageUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = imageModifier,
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val title =
                        episode?.title
                            ?.trim()
                            ?.takeIf { it.isNotBlank() }
                            ?: details?.title.orEmpty()
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val metadata = episodeHeaderMetadata(episode = episode, details = details)
                    if (metadata != null) {
                        Text(
                            text = metadata,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (episode != null) {
                        details
                            ?.title
                            ?.trim()
                            ?.takeIf { it.isNotBlank() }
                            ?.let { showTitle ->
                                Text(
                                    text = showTitle,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                    }
                }
            }

            description?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The metadata line under a title in the provider sheet: the episode's
 * `S<n> E<m> • <release date>`, or the show's year when there is no episode.
 *
 * ## Why this delegates instead of repeating
 *
 * Lines 320-327 of this function used to be **byte-identical** to
 * [episodeRowMeta] in `playerui` — the same `S$season E$episodeNumber` prefix, the
 * same date, the same ` • ` join. Two copies of one decision is the same defect as
 * a decision no test can reach: it is not coverage, it is an invitation to change
 * one and forget the other. The episode half is now [episodeRowMeta], and what
 * remains here is the one question this function alone answers, which is what to
 * show when there is no episode at all.
 *
 * The `if` is load-bearing and is deliberately **not** an `?:` chain. When
 * `episode != null` but contributes nothing — no season/episode pair and no
 * parseable release date — the original returned **null**; an
 * `episode?.let { … } ?: details?.year…` would have fallen back to the show's
 * year and put a bare year where the player sheet shows nothing. So the shape is
 * `if (episode != null) episodeRowMeta(episode) else <year>`, which is exactly
 * equivalent, and `episodeRowMeta` is the only place that can return null for an
 * episode.
 *
 * `internal` rather than `private` so [EpisodeHeaderMetadataTest] can call it: a
 * `when`/branch no test can reach is a branch no test can cover.
 */
internal fun episodeHeaderMetadata(
    episode: MediaVideo?,
    details: MediaDetails?,
): String? = if (episode != null) {
    episodeRowMeta(episode)
} else {
    details?.year?.trim()?.takeIf { it.isNotBlank() }
}

@Composable
private fun ProviderChipsRow(
    state: StreamSelectorUiState,
    accentColor: Color,
    onAccentColor: Color,
    onProviderSelected: (String?) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .testTag("stream_provider_chips"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = state.selectedProviderId == null,
            onClick = { onProviderSelected(null) },
            label = { Text("All ${state.totalStreamCount}") },
            shape = RoundedCornerShape(16.dp),
            border = null,
            colors =
                FilterChipDefaults.filterChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    labelColor = MaterialTheme.colorScheme.onSurface,
                    selectedContainerColor = accentColor,
                    selectedLabelColor = onAccentColor,
                ),
        )

        state.providers.visibleProviders().forEach { provider ->
            FilterChip(
                selected = provider.providerId.equals(state.selectedProviderId, ignoreCase = true),
                onClick = { onProviderSelected(provider.providerId) },
                label = {
                    Text("${provider.providerName} ${provider.streams.size}")
                },
                shape = RoundedCornerShape(16.dp),
                border = null,
                colors =
                    FilterChipDefaults.filterChipColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        labelColor = MaterialTheme.colorScheme.onSurface,
                        selectedContainerColor = accentColor,
                        selectedLabelColor = onAccentColor,
                    ),
            )
        }

        if (state.isFetching) {
            FilterChip(
                selected = false,
                onClick = {},
                enabled = false,
                label = { Text("Loading") },
                modifier = Modifier.width(84.dp),
                shape = RoundedCornerShape(16.dp),
                border = null,
                colors =
                    FilterChipDefaults.filterChipColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
            )
        }
    }
}

@Composable
private fun StreamRow(
    stream: AddonStream,
    providerName: String,
    onClick: () -> Unit,
) {
    val detailsText =
        remember(stream.title, stream.description) {
            val title = stream.title?.trim()?.takeIf { it.isNotBlank() }
            val description = stream.description?.trim()?.takeIf { it.isNotBlank() }
            if (description != null && description.contains('\n') && description.length > (title?.length ?: 0)) {
                description
            } else {
                title ?: description
            }
        }

    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.testTag("stream_row_${stream.stableKey}"),
    ) {
        ListItem(
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    detailsText?.let { text ->
                        Text(text = text)
                    }
                    Text(
                        text = providerName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            trailingContent =
                if (stream.cached) {
                    {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Text(
                                text = "Cached",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                } else {
                    null
                },
        ) {
            Text(
                text = stream.name ?: providerName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
