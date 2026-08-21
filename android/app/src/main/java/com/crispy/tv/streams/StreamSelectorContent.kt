@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package com.crispy.tv.streams

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
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.SheetValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.crispy.tv.details.DetailsSkeletonColors
import com.crispy.tv.home.MediaDetails
import com.crispy.tv.home.MediaVideo
import com.crispy.tv.ui.components.rememberCrispyImageModel
import com.crispy.tv.ui.components.skeletonElement
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

data class SelectorCallbacks(
    val onDismiss: () -> Unit,
    val onProviderSelected: (String?) -> Unit,
    val onRetryProvider: (String) -> Unit,
    val onStreamSelected: (AddonStream) -> Unit,
)

private const val STREAM_SHEET_HEIGHT_FRACTION = 0.92f

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun StreamSelectorModal(
    state: StreamSelectorUiState,
    details: MediaDetails?,
    headerEpisode: MediaVideo?,
    chrome: SelectorChrome,
    callbacks: SelectorCallbacks,
) {
    if (!state.visible) return

    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)
    ModalBottomSheet(
        onDismissRequest = callbacks.onDismiss,
        sheetState = sheetState,
        scrimColor = chrome.scrimColor ?: BottomSheetDefaults.ScrimColor,
        modifier = Modifier.testTag("stream_sheet"),
    ) {
        CompositionLocalProvider(LocalOverscrollFactory provides null) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(STREAM_SHEET_HEIGHT_FRACTION),
            ) {
                StreamSelectorContent(
                    state = state,
                    details = details,
                    headerEpisode = headerEpisode,
                    chrome = chrome,
                    callbacks = callbacks,
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
    chrome: SelectorChrome,
    callbacks: SelectorCallbacks,
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

    val filteredProviders =
        remember(state.providers, state.selectedProviderId) {
            val selectedProvider = state.selectedProviderId
            if (selectedProvider.isNullOrBlank()) {
                state.providers
            } else {
                state.providers.filter { provider ->
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
                chrome = chrome,
            )
        }

        item {
            ProviderChipsRow(
                state = state,
                chrome = chrome,
                onProviderSelected = callbacks.onProviderSelected,
            )
        }

        if (
            !state.isLoading &&
            filteredProviders.all { provider -> provider.streams.isEmpty() && provider.errorMessage == null }
        ) {
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
            if (provider.errorMessage != null) {
                item(key = "provider_error_${provider.providerId}") {
                    ProviderErrorRow(
                        provider = provider,
                        onRetry = callbacks.onRetryProvider,
                    )
                }
            }

            if (provider.streams.isNotEmpty()) {
                items(items = provider.streams, key = { stream -> stream.stableKey }) { stream ->
                    StreamRow(
                        stream = stream,
                        providerName = provider.providerName,
                        onClick = { callbacks.onStreamSelected(stream) },
                    )
                }
            }
        }

        if (state.isLoading) {
            item {
                LoadingMoreStreamsRow(chrome.loadingIndicatorSize)
            }
        }
    }
}

@Composable
private fun StreamSheetHeader(
    details: MediaDetails?,
    episode: MediaVideo?,
    chrome: SelectorChrome,
) {
    if (details == null && episode == null) return

    val imageUrl =
        episode?.thumbnailUrl
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: details?.backdropUrl
            ?: details?.posterUrl
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
                        if (chrome.useCrispyImageModel && imageUrl != null) {
                            rememberCrispyImageModel(url = imageUrl, width = 96.dp, height = 56.dp)
                        } else {
                            null
                        }
                    val imageModifier =
                        if (chrome.useCrispyImageModel) {
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

private fun episodeHeaderMetadata(
    episode: MediaVideo?,
    details: MediaDetails?,
): String? {
    if (episode == null) {
        return details?.year?.trim()?.takeIf { it.isNotBlank() }
    }

    val parts = mutableListOf<String>()
    val season = episode.season
    val episodeNumber = episode.episode
    if (season != null && episodeNumber != null) {
        parts += "S$season E$episodeNumber"
    }
    formatEpisodeReleaseDate(episode.released)?.let(parts::add)
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" • ")
}

fun formatEpisodeReleaseDate(date: String?): String? {
    val raw = date?.trim().orEmpty()
    if (raw.isBlank()) return null

    val iso = if (raw.length >= 10) raw.take(10) else raw
    return try {
        val parsed = LocalDate.parse(iso)
        parsed.format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US))
    } catch (_: Throwable) {
        raw
    }
}

@Composable
private fun ProviderChipsRow(
    state: StreamSelectorUiState,
    chrome: SelectorChrome,
    onProviderSelected: (String?) -> Unit,
) {
    if (chrome.showSkeletonChips && state.isLoading && state.providers.isEmpty()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .testTag("stream_provider_chips"),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            repeat(4) { index ->
                val chipWidth =
                    when (index) {
                        0 -> 68.dp
                        1 -> 92.dp
                        2 -> 84.dp
                        else -> 100.dp
                    }
                Box(
                    modifier =
                        Modifier
                            .width(chipWidth)
                            .height(32.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .skeletonElement(color = DetailsSkeletonColors.Base),
                )
            }
        }
        return
    }

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
                    selectedContainerColor = chrome.accentColor,
                    selectedLabelColor = chrome.onAccentColor,
                ),
        )

        state.providers.forEach { provider ->
            FilterChip(
                selected = provider.providerId.equals(state.selectedProviderId, ignoreCase = true),
                onClick = { onProviderSelected(provider.providerId) },
                label = { Text("${provider.providerName} ${provider.streams.size}") },
                shape = RoundedCornerShape(16.dp),
                border = null,
                colors =
                    FilterChipDefaults.filterChipColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        labelColor = MaterialTheme.colorScheme.onSurface,
                        selectedContainerColor = chrome.accentColor,
                        selectedLabelColor = chrome.onAccentColor,
                    ),
            )
        }
    }
}

@Composable
private fun ProviderErrorRow(
    provider: StreamProviderUiState,
    onRetry: (String) -> Unit,
) {
    ElevatedCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${provider.providerName}: ${provider.errorMessage}",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = { onRetry(provider.providerId) }) {
                Text("Retry")
            }
        }
    }
}

@Composable
private fun LoadingMoreStreamsRow(indicatorSize: androidx.compose.ui.unit.Dp) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LoadingIndicator(modifier = Modifier.size(indicatorSize))
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
