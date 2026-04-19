package com.crispy.tv.playerui

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.crispy.tv.home.MediaDetails
import com.crispy.tv.home.MediaVideo
import com.crispy.tv.streams.AddonStream
import com.crispy.tv.streams.StreamProviderUiState
import com.crispy.tv.streams.StreamSelectorUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerStreamsSheet(
    visible: Boolean,
    details: MediaDetails?,
    state: StreamSelectorUiState,
    onDismiss: () -> Unit,
    onProviderSelected: (String?) -> Unit,
    onRetryProvider: (String) -> Unit,
    onStreamSelected: (AddonStream) -> Unit,
) {
    if (!visible) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
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

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        scrimColor = Color.Transparent,
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.92f),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    StreamSheetHeader(details = details, episode = state.headerEpisode)
                }

                item {
                    ProviderChipsRow(
                        state = state,
                        onProviderSelected = onProviderSelected,
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
                                onRetry = onRetryProvider,
                            )
                        }
                    }

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

                if (state.isLoading) {
                    item {
                        LoadingMoreStreamsRow()
                    }
                }
            }
        }
    }
}

@Composable
private fun StreamSheetHeader(
    details: MediaDetails?,
    episode: MediaVideo?,
) {
    if (details == null && episode == null) return

    val imageUrl =
        episode?.thumbnailUrl?.trim()?.takeIf { it.isNotBlank() }
            ?: details?.backdropUrl
            ?: details?.posterUrl
    val description =
        episode?.overview?.trim()?.takeIf { it.isNotBlank() }
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
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(width = 96.dp, height = 56.dp),
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val title = episode?.title?.trim()?.takeIf { it.isNotBlank() } ?: details?.title.orEmpty()
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
                        details?.title?.trim()?.takeIf { it.isNotBlank() }?.let { showTitle ->
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
    formatEpisodeDate(episode.released)?.let(parts::add)
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" • ")
}

@Composable
private fun ProviderChipsRow(
    state: StreamSelectorUiState,
    onProviderSelected: (String?) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = state.selectedProviderId == null,
            onClick = { onProviderSelected(null) },
            label = { Text("All ${state.totalStreamCount}") },
        )

        state.providers.forEach { provider ->
            FilterChip(
                selected = provider.providerId.equals(state.selectedProviderId, ignoreCase = true),
                onClick = { onProviderSelected(provider.providerId) },
                label = { Text("${provider.providerName} ${provider.streams.size}") },
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
private fun LoadingMoreStreamsRow() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(32.dp))
    }
}

@Composable
internal fun StaticTag(
    text: String,
    emphasized: Boolean = false,
) {
    val containerColor =
        if (emphasized) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        }
    val contentColor =
        if (emphasized) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }

    Surface(
        shape = RoundedCornerShape(999.dp),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
        )
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

    ElevatedCard(onClick = onClick) {
        ListItem(
            headlineContent = {
                Text(
                    text = stream.name ?: providerName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
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
            trailingContent = if (stream.cached) {
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
        )
    }
}
