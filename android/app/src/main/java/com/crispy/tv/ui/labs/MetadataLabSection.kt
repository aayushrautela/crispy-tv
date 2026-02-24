package com.crispy.tv.ui.labs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.player.PlaybackLabUiState
import com.crispy.tv.player.PlaybackLabViewModel

@Composable
internal fun MetadataLabSection(
    uiState: PlaybackLabUiState,
    viewModel: PlaybackLabViewModel
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Metadata Lab (Phase 3.2)", style = MaterialTheme.typography.titleMedium)
            Text(
                "Nuvio-style IDs + addon-first merge with TMDB enhancer bridge.",
                style = MaterialTheme.typography.bodyMedium
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    modifier = Modifier.testTag("metadata_movie_button"),
                    enabled = uiState.metadataMediaType != MetadataLabMediaType.MOVIE,
                    onClick = {
                        viewModel.onMetadataMediaTypeSelected(MetadataLabMediaType.MOVIE)
                    }
                ) {
                    Text("Movie")
                }

                Button(
                    modifier = Modifier.testTag("metadata_series_button"),
                    enabled = uiState.metadataMediaType != MetadataLabMediaType.SERIES,
                    onClick = {
                        viewModel.onMetadataMediaTypeSelected(MetadataLabMediaType.SERIES)
                    }
                ) {
                    Text("Series")
                }
            }

            OutlinedTextField(
                value = uiState.metadataInputId,
                onValueChange = viewModel::onMetadataInputChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("metadata_id_input"),
                label = { Text("Metadata ID") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
            )

            OutlinedTextField(
                value = uiState.metadataPreferredAddonId,
                onValueChange = viewModel::onMetadataPreferredAddonChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("metadata_preferred_addon_input"),
                label = { Text("Preferred addon (optional)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
            )

            Button(
                modifier = Modifier.testTag("resolve_metadata_button"),
                enabled = !uiState.isResolvingMetadata,
                onClick = {
                    viewModel.onResolveMetadataRequested()
                }
            ) {
                Text(if (uiState.isResolvingMetadata) "Resolving..." else "Resolve Metadata")
            }

            Text(
                modifier = Modifier.testTag("metadata_status_text"),
                text = uiState.metadataStatusMessage,
                style = MaterialTheme.typography.bodySmall
            )

            uiState.metadataResolution?.let { resolved ->
                Text(
                    modifier = Modifier.testTag("metadata_normalized_text"),
                    text = "content_id=${resolved.contentId} | video_id=${resolved.videoId ?: "-"}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    modifier = Modifier.testTag("metadata_primary_text"),
                    text = "primary=${resolved.primaryId} | title=${resolved.primaryTitle}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    modifier = Modifier.testTag("metadata_sources_text"),
                    text = "sources=${resolved.sources.joinToString()}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    modifier = Modifier.testTag("metadata_bridge_text"),
                    text = "bridge=${resolved.bridgeCandidateIds.joinToString()}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    modifier = Modifier.testTag("metadata_enrichment_text"),
                    text = "needs_enrichment=${resolved.needsEnrichment} | imdb=${resolved.mergedImdbId ?: "-"}",
                    style = MaterialTheme.typography.bodySmall
                )
                val transportText =
                    if (resolved.transportStats.isEmpty()) {
                        "Transport: none"
                    } else {
                        val totalStreams = resolved.transportStats.sumOf { stat -> stat.streamCount }
                        val totalSubtitles = resolved.transportStats.sumOf { stat -> stat.subtitleCount }
                        "Transport: streams=$totalStreams | subtitles=$totalSubtitles"
                    }
                Text(
                    modifier = Modifier.testTag("metadata_transport_text"),
                    text = transportText,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
