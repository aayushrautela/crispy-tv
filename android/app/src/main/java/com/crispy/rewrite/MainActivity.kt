package com.crispy.rewrite

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.ui.PlayerView
import com.crispy.rewrite.nativeengine.playback.NativePlaybackEngine
import com.crispy.rewrite.nativeengine.playback.NativePlaybackEvent
import com.crispy.rewrite.player.PlaybackEngine
import com.crispy.rewrite.player.PlaybackLabViewModel
import com.crispy.rewrite.ui.theme.CrispyRewriteTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CrispyRewriteTheme {
                AppRoot()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot(viewModel: PlaybackLabViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    val playbackController = remember(context) {
        PlaybackLabDependencies.playbackControllerFactory(context) { event ->
            when (event) {
                NativePlaybackEvent.Buffering -> viewModel.onNativeBuffering()
                NativePlaybackEvent.Ready -> viewModel.onNativeReady()
                NativePlaybackEvent.Ended -> viewModel.onNativeEnded()
                is NativePlaybackEvent.Error -> {
                    if (event.codecLikely) {
                        viewModel.onNativeCodecError(event.message)
                    } else {
                        viewModel.onPlaybackLaunchFailed(event.message)
                    }
                }
            }
        }
    }

    val torrentResolver = remember(context) {
        PlaybackLabDependencies.torrentResolverFactory(context)
    }

    var playerVisible by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            viewModel.onPlaybackLaunchFailed("Notification permission denied. Torrent service may fail on Android 13+.")
        }
    }

    DisposableEffect(playbackController, torrentResolver) {
        onDispose {
            torrentResolver.stopAndClear()
            torrentResolver.close()
            playbackController.release()
        }
    }

    LaunchedEffect(uiState.playbackRequestVersion) {
        val streamUrl = uiState.playbackUrl ?: return@LaunchedEffect

        runCatching {
            playbackController.play(
                url = streamUrl,
                engine = uiState.activeEngine.toNativeEngine()
            )
        }.onSuccess {
            playerVisible = true
        }.onFailure { error ->
            viewModel.onPlaybackLaunchFailed(error.message ?: "unknown playback launch error")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Crispy Rewrite") })
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(12.dp)
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
                            modifier = Modifier.testTag("play_sample_button"),
                            enabled = !uiState.isPreparingTorrentPlayback,
                            onClick = {
                                viewModel.onPlaySampleRequested()
                            }
                        ) {
                            Text("Play Sample Video")
                        }
                    }

                    OutlinedTextField(
                        value = uiState.magnetInput,
                        onValueChange = viewModel::onMagnetChanged,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("magnet_input"),
                        label = { Text("Torrent magnet URI") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                    )

                    Button(
                        modifier = Modifier.testTag("start_torrent_button"),
                        enabled = !uiState.isPreparingTorrentPlayback,
                        onClick = {
                            if (
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.POST_NOTIFICATIONS
                                ) != PackageManager.PERMISSION_GRANTED
                            ) {
                                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                return@Button
                            }

                            val magnet = viewModel.onStartTorrentRequested() ?: return@Button

                            playerVisible = false
                            playbackController.stop()

                            scope.launch {
                                runCatching {
                                    torrentResolver.resolveStreamUrl(
                                        magnetLink = magnet,
                                        sessionId = "playback-lab"
                                    )
                                }.onSuccess { streamUrl ->
                                    viewModel.onTorrentStreamResolved(streamUrl)
                                }.onFailure { error ->
                                    viewModel.onTorrentPlaybackFailed(error.message ?: "unknown torrent error")
                                }
                            }
                        }
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

                    if (playerVisible) {
                        when (uiState.activeEngine) {
                            PlaybackEngine.EXO -> {
                                ExoPlayerSurface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(240.dp),
                                    bind = playbackController::bindExoPlayerView
                                )
                            }

                            PlaybackEngine.VLC -> {
                                VlcPlayerSurface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(240.dp),
                                    create = playbackController::createVlcSurfaceView,
                                    attach = playbackController::attachVlcSurface
                                )
                            }
                        }
                    }
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
    create: (android.content.Context) -> android.view.SurfaceView,
    attach: (android.view.SurfaceView) -> Unit
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

private fun PlaybackEngine.toNativeEngine(): NativePlaybackEngine {
    return when (this) {
        PlaybackEngine.EXO -> NativePlaybackEngine.EXO
        PlaybackEngine.VLC -> NativePlaybackEngine.VLC
    }
}
