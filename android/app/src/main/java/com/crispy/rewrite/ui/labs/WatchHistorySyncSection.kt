package com.crispy.rewrite.ui.labs

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
import com.crispy.rewrite.player.MetadataLabMediaType
import com.crispy.rewrite.player.PlaybackLabUiState
import com.crispy.rewrite.player.PlaybackLabViewModel

@Composable
internal fun WatchHistorySyncSection(
    uiState: PlaybackLabUiState,
    viewModel: PlaybackLabViewModel
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Watch History + Sync", style = MaterialTheme.typography.titleMedium)
            Text(
                "Local watched ledger with optional Trakt/Simkl history sync.",
                style = MaterialTheme.typography.bodyMedium
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    modifier = Modifier.testTag("watch_movie_button"),
                    enabled = uiState.watchContentType != MetadataLabMediaType.MOVIE,
                    onClick = {
                        viewModel.onWatchContentTypeSelected(MetadataLabMediaType.MOVIE)
                    }
                ) {
                    Text("Movie")
                }

                Button(
                    modifier = Modifier.testTag("watch_series_button"),
                    enabled = uiState.watchContentType != MetadataLabMediaType.SERIES,
                    onClick = {
                        viewModel.onWatchContentTypeSelected(MetadataLabMediaType.SERIES)
                    }
                ) {
                    Text("Series")
                }
            }

            OutlinedTextField(
                value = uiState.watchContentId,
                onValueChange = viewModel::onWatchContentIdChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("watch_content_id_input"),
                label = { Text("Content ID") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
            )

            OutlinedTextField(
                value = uiState.watchRemoteImdbId,
                onValueChange = viewModel::onWatchRemoteImdbIdChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("watch_remote_imdb_input"),
                label = { Text("Remote IMDb ID (optional)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
            )

            OutlinedTextField(
                value = uiState.watchTitle,
                onValueChange = viewModel::onWatchTitleChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("watch_title_input"),
                label = { Text("Title (optional)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
            )

            OutlinedTextField(
                value = uiState.watchSeasonInput,
                onValueChange = viewModel::onWatchSeasonChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("watch_season_input"),
                label = { Text("Season (series only)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            OutlinedTextField(
                value = uiState.watchEpisodeInput,
                onValueChange = viewModel::onWatchEpisodeChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("watch_episode_input"),
                label = { Text("Episode (series only)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            OutlinedTextField(
                value = uiState.watchTraktToken,
                onValueChange = viewModel::onWatchTraktTokenChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("watch_trakt_token_input"),
                label = { Text("Trakt access token") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
            )

            OutlinedTextField(
                value = uiState.watchSimklToken,
                onValueChange = viewModel::onWatchSimklTokenChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("watch_simkl_token_input"),
                label = { Text("Simkl access token") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    modifier = Modifier.testTag("watch_save_tokens_button"),
                    enabled = !uiState.isUpdatingWatchHistory,
                    onClick = {
                        viewModel.onSaveWatchTokensRequested()
                    }
                ) {
                    Text("Save Tokens")
                }

                Button(
                    modifier = Modifier.testTag("watch_refresh_button"),
                    enabled = !uiState.isUpdatingWatchHistory,
                    onClick = {
                        viewModel.onRefreshWatchHistoryRequested()
                    }
                ) {
                    Text(if (uiState.isUpdatingWatchHistory) "Working..." else "Refresh")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    modifier = Modifier.testTag("watch_mark_button"),
                    enabled = !uiState.isUpdatingWatchHistory,
                    onClick = {
                        viewModel.onMarkWatchedRequested()
                    }
                ) {
                    Text(if (uiState.isUpdatingWatchHistory) "Working..." else "Mark Watched")
                }

                Button(
                    modifier = Modifier.testTag("watch_unmark_button"),
                    enabled = !uiState.isUpdatingWatchHistory,
                    onClick = {
                        viewModel.onUnmarkWatchedRequested()
                    }
                ) {
                    Text(if (uiState.isUpdatingWatchHistory) "Working..." else "Unmark")
                }
            }

            Text(
                modifier = Modifier.testTag("watch_auth_text"),
                text =
                    "trakt_auth=${uiState.watchAuthState.traktAuthenticated} | " +
                        "simkl_auth=${uiState.watchAuthState.simklAuthenticated}",
                style = MaterialTheme.typography.bodySmall
            )

            Text(
                modifier = Modifier.testTag("watch_status_text"),
                text = uiState.watchStatusMessage,
                style = MaterialTheme.typography.bodySmall
            )

            val historyText =
                if (uiState.watchEntries.isEmpty()) {
                    "history=none"
                } else {
                    "history=" + uiState.watchEntries.take(6).joinToString(separator = " | ") { entry ->
                        val suffix =
                            if (entry.season != null && entry.episode != null) {
                                ":${entry.season}:${entry.episode}"
                            } else {
                                ""
                            }
                        "${entry.contentId}$suffix"
                    }
                }
            Text(
                modifier = Modifier.testTag("watch_history_text"),
                text = historyText,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
