@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.crispy.tv.playerui

import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.ui.PlayerView
import com.crispy.tv.PlaybackDependencies
import com.crispy.tv.nativeengine.playback.NativePlaybackEngine
import com.crispy.tv.nativeengine.playback.NativePlaybackEvent

@Composable
fun PlayerRoute(
    playbackUrl: String,
    title: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val viewModel: PlayerViewModel =
        viewModel(
            key = "$playbackUrl|$title",
            factory = remember(playbackUrl, title) { PlayerViewModel.factory(playbackUrl, title) },
        )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val playbackController =
        remember(appContext, viewModel) {
            PlaybackDependencies.playbackControllerFactory(appContext) { event ->
                when (event) {
                    NativePlaybackEvent.Buffering -> viewModel.onNativeBuffering()
                    NativePlaybackEvent.Ready -> viewModel.onNativeReady()
                    NativePlaybackEvent.Ended -> viewModel.onNativeEnded()
                    is NativePlaybackEvent.Error -> {
                        viewModel.onNativeError(event.message, codecLikely = event.codecLikely)
                    }
                }
            }
        }

    DisposableEffect(playbackController) {
        onDispose {
            playbackController.release()
        }
    }

    LaunchedEffect(uiState.playbackRequestVersion, uiState.playbackUrl, uiState.activeEngine) {
        if (uiState.playbackUrl.isNotBlank()) {
            playbackController.play(uiState.playbackUrl, uiState.activeEngine)
        }
    }

    PlayerScreen(
        state = uiState,
        onBack = onBack,
        onRetry = viewModel::retryPlayback,
        onEngineSelected = viewModel::onEngineSelected,
        exoSurface = {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { viewContext ->
                    PlayerView(viewContext).apply {
                        useController = true
                        playbackController.bindExoPlayerView(this)
                    }
                },
                update = { playerView ->
                    playbackController.bindExoPlayerView(playerView)
                },
            )
        },
        vlcSurface = {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { viewContext ->
                    playbackController.createVlcSurfaceView(viewContext)
                },
                update = { surfaceView: SurfaceView ->
                    playbackController.attachVlcSurface(surfaceView)
                },
            )
        },
    )
}

@Composable
private fun PlayerScreen(
    state: PlayerUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onEngineSelected: (NativePlaybackEngine) -> Unit,
    exoSurface: @Composable () -> Unit,
    vlcSurface: @Composable () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(Color.Black),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color.Black),
            ) {
                when (state.activeEngine) {
                    NativePlaybackEngine.EXO -> exoSurface()
                    NativePlaybackEngine.VLC -> vlcSurface()
                }

                if (state.isBuffering) {
                    LinearProgressIndicator(
                        modifier =
                            Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxWidth(),
                    )
                }
            }

            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = state.activeEngine == NativePlaybackEngine.EXO,
                        onClick = { onEngineSelected(NativePlaybackEngine.EXO) },
                        label = { Text("Exo") },
                    )
                    FilterChip(
                        selected = state.activeEngine == NativePlaybackEngine.VLC,
                        onClick = { onEngineSelected(NativePlaybackEngine.VLC) },
                        label = { Text("VLC") },
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = onRetry) {
                        Text("Retry")
                    }
                }

                Text(
                    text = state.errorMessage ?: state.statusMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color =
                        if (state.errorMessage == null) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                )
            }
        }
    }
}
