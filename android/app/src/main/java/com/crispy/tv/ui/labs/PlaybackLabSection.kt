package com.crispy.tv.ui.labs

import android.content.Context
import android.view.SurfaceView
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import com.crispy.tv.introskip.IntroSkipButtonOverlay
import com.crispy.tv.introskip.IntroSkipInterval
import com.crispy.tv.player.PlaybackEngine
import com.crispy.tv.player.PlaybackLabUiState

@Composable
internal fun PlaybackLabSection(
    uiState: PlaybackLabUiState,
    mergedImdbId: String?,
    introSkipImdbIdInput: String,
    onIntroSkipImdbIdInputChanged: (String) -> Unit,
    introSkipSeasonInput: String,
    onIntroSkipSeasonInputChanged: (String) -> Unit,
    introSkipEpisodeInput: String,
    onIntroSkipEpisodeInputChanged: (String) -> Unit,
    introSkipMalIdInput: String,
    onIntroSkipMalIdInputChanged: (String) -> Unit,
    introSkipKitsuIdInput: String,
    onIntroSkipKitsuIdInputChanged: (String) -> Unit,
    introSkipLoading: Boolean,
    introSkipStatusMessage: String,
    onEngineSelected: (PlaybackEngine) -> Unit,
    onPlaySampleRequested: () -> Unit,
    onMagnetChanged: (String) -> Unit,
    onStartTorrentRequested: () -> Unit,
    skipIntroEnabled: Boolean,
    introSkipIntervals: List<IntroSkipInterval>,
    playbackPositionMs: Long,
    playerVisible: Boolean,
    bindExoPlayerView: (PlayerView) -> Unit,
    createVlcSurfaceView: (Context) -> SurfaceView,
    attachVlcSurface: (SurfaceView) -> Unit,
    onSkipRequested: (Long) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Playback Lab", style = MaterialTheme.typography.titleMedium)
            Text(
                "Material 3 shell with native engine orchestration. Exo is primary, VLC is fallback.",
                style = MaterialTheme.typography.bodyMedium
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    modifier = Modifier.testTag("engine_exo_button"),
                    enabled = uiState.activeEngine != PlaybackEngine.EXO,
                    onClick = { onEngineSelected(PlaybackEngine.EXO) }
                ) {
                    Text("Use Exo")
                }

                Button(
                    modifier = Modifier.testTag("engine_vlc_button"),
                    enabled = uiState.activeEngine != PlaybackEngine.VLC,
                    onClick = { onEngineSelected(PlaybackEngine.VLC) }
                ) {
                    Text("Use VLC")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    modifier = Modifier.testTag("play_sample_button"),
                    enabled = !uiState.isPreparingTorrentPlayback,
                    onClick = onPlaySampleRequested
                ) {
                    Text("Play Sample Video")
                }
            }

            OutlinedTextField(
                value = uiState.magnetInput,
                onValueChange = onMagnetChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("magnet_input"),
                label = { Text("Torrent magnet URI") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )

            Text(
                text = "Intro Skip Identity",
                style = MaterialTheme.typography.titleSmall
            )
            OutlinedTextField(
                value = introSkipImdbIdInput,
                onValueChange = onIntroSkipImdbIdInputChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("IMDb ID (tt1234567)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
            )

            if (introSkipImdbIdInput.isBlank() && mergedImdbId != null) {
                Text(
                    text = "Using resolved IMDb ID from Metadata Lab: $mergedImdbId",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            OutlinedTextField(
                value = introSkipSeasonInput,
                onValueChange = onIntroSkipSeasonInputChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Season") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            OutlinedTextField(
                value = introSkipEpisodeInput,
                onValueChange = onIntroSkipEpisodeInputChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Episode") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            OutlinedTextField(
                value = introSkipMalIdInput,
                onValueChange = onIntroSkipMalIdInputChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("MAL ID (optional)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            OutlinedTextField(
                value = introSkipKitsuIdInput,
                onValueChange = onIntroSkipKitsuIdInputChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Kitsu ID (optional)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            Text(
                text =
                    if (introSkipLoading) {
                        "Loading intro skip segments..."
                    } else {
                        introSkipStatusMessage
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("intro_skip_status_text")
            )

            Button(
                modifier = Modifier.testTag("start_torrent_button"),
                enabled = !uiState.isPreparingTorrentPlayback,
                onClick = onStartTorrentRequested
            ) {
                Text("Start Torrent")
            }

            Text(
                modifier = Modifier.testTag("engine_phase_text"),
                text = "Engine: ${uiState.activeEngine.name} | Phase: ${uiState.playerState.phase.name}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                modifier = Modifier.testTag("status_text"),
                text = uiState.statusMessage,
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text =
                    "Intro skip: ${if (skipIntroEnabled) "enabled" else "disabled"} " +
                        "| segments=${introSkipIntervals.size} | position=${playbackPositionMs / 1000}s",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (playerVisible) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                ) {
                    when (uiState.activeEngine) {
                        PlaybackEngine.EXO -> {
                            ExoPlayerSurface(
                                modifier = Modifier.fillMaxSize(),
                                bind = bindExoPlayerView
                            )
                        }

                        PlaybackEngine.VLC -> {
                            VlcPlayerSurface(
                                modifier = Modifier.fillMaxSize(),
                                create = createVlcSurfaceView,
                                attach = attachVlcSurface
                            )
                        }
                    }

                    IntroSkipButtonOverlay(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(12.dp),
                        enabled = skipIntroEnabled,
                        currentPositionMs = playbackPositionMs,
                        intervals = introSkipIntervals,
                        onSkipRequested = onSkipRequested
                    )
                }
            }
        }
    }
}

@Composable
private fun ExoPlayerSurface(
    modifier: Modifier = Modifier,
    bind: (PlayerView) -> Unit
) {
    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            PlayerView(viewContext).apply {
                useController = true
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                bind(this)
            }
        },
        update = { playerView ->
            bind(playerView)
        }
    )
}

@Composable
private fun VlcPlayerSurface(
    modifier: Modifier = Modifier,
    create: (Context) -> SurfaceView,
    attach: (SurfaceView) -> Unit
) {
    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            create(viewContext)
        },
        update = { surfaceView ->
            attach(surfaceView)
        }
    )
}
