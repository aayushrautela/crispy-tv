@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package com.crispy.tv.playerui

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.util.Rational
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.ui.PlayerView
import com.crispy.tv.PlaybackDependencies
import com.crispy.tv.nativeengine.playback.NativePlaybackEngine
import com.crispy.tv.nativeengine.playback.NativePlaybackEvent
import com.crispy.tv.player.PlaybackIdentity
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun PlayerRoute(
    playbackUrl: String,
    title: String,
    subtitle: String?,
    artworkUrl: String?,
    identity: PlaybackIdentity?,
    restorePlaybackIntent: Intent,
    isInPictureInPictureMode: Boolean,
    onPictureInPictureConfigChanged: (PictureInPictureConfig) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val coroutineScope = rememberCoroutineScope()

    val watchHistoryService =
        remember(appContext) {
            PlaybackDependencies.watchHistoryServiceFactory(appContext)
        }
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

    val mediaSessionManager =
        remember(appContext, playbackController, restorePlaybackIntent) {
            PlayerMediaSessionManager(
                context = appContext,
                playbackController = playbackController,
                restorePlaybackIntent = restorePlaybackIntent,
            )
        }

    val lastMetrics =
        remember {
            PlaybackMetricsHolder()
        }
    var videoBounds by remember { mutableStateOf<Rect?>(null) }
    val latestUiState by rememberUpdatedState(uiState)
    val latestSubtitle by rememberUpdatedState(subtitle)
    val latestArtworkUrl by rememberUpdatedState(artworkUrl)

    DisposableEffect(playbackUrl, title, identity) {
        Log.d(
            TAG,
            "compose enter title=$title hasIdentity=${identity != null} playbackUrlHash=${playbackUrl.hashCode()}",
        )
        onDispose {
            Log.d(TAG, "compose dispose title=$title playbackUrlHash=${playbackUrl.hashCode()}")
        }
    }

    DisposableEffect(playbackController, identity, watchHistoryService, mediaSessionManager) {
        onDispose {
            Log.d(
                TAG,
                "player resources dispose engine=${latestUiState.activeEngine} isInPip=$isInPictureInPictureMode lastPositionMs=${lastMetrics.positionMs} lastDurationMs=${lastMetrics.durationMs} lastIsPlaying=${lastMetrics.isPlaying}",
            )
            val lastDurationMs = lastMetrics.durationMs
            if (identity != null && lastDurationMs > 0L) {
                coroutineScope.launch(Dispatchers.IO) {
                    watchHistoryService.onPlaybackStopped(
                        identity = identity,
                        positionMs = lastMetrics.positionMs,
                        durationMs = lastDurationMs,
                    )
                }
            }

            onPictureInPictureConfigChanged(PictureInPictureConfig())
            mediaSessionManager.release()
            playbackController.release()
        }
    }

    LaunchedEffect(uiState.playbackRequestVersion, uiState.playbackUrl, uiState.activeEngine) {
        if (uiState.playbackUrl.isNotBlank()) {
            Log.d(
                TAG,
                "play request version=${uiState.playbackRequestVersion} engine=${uiState.activeEngine} playbackUrlHash=${uiState.playbackUrl.hashCode()}",
            )
            playbackController.play(uiState.playbackUrl, uiState.activeEngine)
        }
    }

    LaunchedEffect(
        uiState.activeEngine,
        uiState.isBuffering,
        uiState.isPlaying,
        uiState.statusMessage,
        uiState.errorMessage,
    ) {
        Log.d(
            TAG,
            "uiState engine=${uiState.activeEngine} buffering=${uiState.isBuffering} playing=${uiState.isPlaying} status=${uiState.statusMessage} error=${uiState.errorMessage}",
        )
    }

    LaunchedEffect(playbackController) {
        var hasStarted = false
        var lastSyncAtElapsedMs = 0L

        while (true) {
            val positionMs = playbackController.currentPositionMs()
            val durationMs = playbackController.durationMs()
            val isPlaying = playbackController.isPlaying()

            lastMetrics.positionMs = positionMs
            lastMetrics.durationMs = durationMs
            lastMetrics.isPlaying = isPlaying

            viewModel.onPlaybackMetrics(positionMs = positionMs, durationMs = durationMs, isPlaying = isPlaying)
            mediaSessionManager.updatePlayback(
                title = latestUiState.title,
                subtitle = latestSubtitle,
                artworkUrl = latestArtworkUrl,
                isPlaying = isPlaying,
                isBuffering = latestUiState.isBuffering,
                positionMs = positionMs,
                durationMs = durationMs,
            )

            if (identity != null && durationMs > 0L) {
                val nowElapsedMs = SystemClock.elapsedRealtime()
                if (!hasStarted && isPlaying) {
                    hasStarted = true
                    coroutineScope.launch(Dispatchers.IO) {
                        watchHistoryService.onPlaybackStarted(
                            identity = identity,
                            positionMs = positionMs,
                            durationMs = durationMs,
                        )
                    }
                    lastSyncAtElapsedMs = nowElapsedMs
                } else if (nowElapsedMs - lastSyncAtElapsedMs >= PROGRESS_SYNC_INTERVAL_MS) {
                    lastSyncAtElapsedMs = nowElapsedMs
                    coroutineScope.launch(Dispatchers.IO) {
                        watchHistoryService.onPlaybackProgress(
                            identity = identity,
                            positionMs = positionMs,
                            durationMs = durationMs,
                            isPlaying = isPlaying,
                        )
                    }
                }
            }

            delay(250)
        }
    }

    LaunchedEffect(uiState.title, subtitle, artworkUrl) {
        mediaSessionManager.updateMetadata(
            title = uiState.title,
            subtitle = subtitle,
            artworkUrl = artworkUrl,
        )
    }

    LaunchedEffect(videoBounds, uiState.isPlaying, uiState.isBuffering, uiState.errorMessage, uiState.stableDurationMs) {
        val aspectRatio =
            videoBounds
                ?.takeIf { it.width() > 0 && it.height() > 0 }
                ?.let { bounds -> Rational(bounds.width(), bounds.height()) }
        val pipEnabled =
            videoBounds != null &&
                uiState.errorMessage == null &&
                (uiState.isPlaying || uiState.isBuffering || uiState.stableDurationMs > 0L)
        Log.d(
            TAG,
            "pip config videoBounds=$videoBounds aspectRatio=$aspectRatio enabled=$pipEnabled isPlaying=${uiState.isPlaying} isBuffering=${uiState.isBuffering} stableDurationMs=${uiState.stableDurationMs} error=${uiState.errorMessage}",
        )
        onPictureInPictureConfigChanged(
            PictureInPictureConfig(
                enabled = pipEnabled,
                sourceRect = videoBounds,
                aspectRatio = aspectRatio,
            ),
        )
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black),
    ) {
        when (uiState.activeEngine) {
            NativePlaybackEngine.EXO -> {
                AndroidView(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .onGloballyPositioned { coordinates ->
                                val bounds = coordinates.boundsInWindow()
                                videoBounds =
                                    Rect(
                                        bounds.left.roundToInt(),
                                        bounds.top.roundToInt(),
                                        bounds.right.roundToInt(),
                                        bounds.bottom.roundToInt(),
                                    )
                            },
                    factory = { viewContext ->
                        PlayerView(viewContext).apply {
                            useController = false
                            Log.d(TAG, "create Exo PlayerView viewHash=${System.identityHashCode(this)}")
                            playbackController.bindExoPlayerView(this)
                        }
                    },
                    update = { playerView ->
                        Log.d(TAG, "update Exo PlayerView viewHash=${System.identityHashCode(playerView)}")
                        playbackController.bindExoPlayerView(playerView)
                    },
                )
            }

            NativePlaybackEngine.VLC -> {
                AndroidView(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .onGloballyPositioned { coordinates ->
                                val bounds = coordinates.boundsInWindow()
                                videoBounds =
                                    Rect(
                                        bounds.left.roundToInt(),
                                        bounds.top.roundToInt(),
                                        bounds.right.roundToInt(),
                                        bounds.bottom.roundToInt(),
                                    )
                    },
                    factory = { viewContext ->
                        playbackController.createVlcSurfaceView(viewContext).also { surfaceView ->
                            Log.d(TAG, "create VLC SurfaceView viewHash=${System.identityHashCode(surfaceView)}")
                        }
                    },
                    update = { surfaceView: SurfaceView ->
                        Log.d(TAG, "update VLC SurfaceView viewHash=${System.identityHashCode(surfaceView)}")
                        playbackController.attachVlcSurface(surfaceView)
                    },
                )
            }
        }

        if (!isInPictureInPictureMode) {
            PlayerOverlay(
                title = uiState.title,
                statusMessage = uiState.statusMessage,
                errorMessage = uiState.errorMessage,
                isBuffering = uiState.isBuffering,
                isPlaying = uiState.isPlaying,
                positionMs = uiState.positionMs,
                durationMs = uiState.durationMs,
                stableDurationMs = uiState.stableDurationMs,
                activeEngine = uiState.activeEngine,
                onBack = {
                    Log.d(TAG, "overlay back pressed")
                    onBack()
                },
                onTogglePlayPause = {
                    val nextPlaying = !uiState.isPlaying
                    Log.d(TAG, "toggle play requested nextPlaying=$nextPlaying engine=${uiState.activeEngine}")
                    playbackController.setPlaying(nextPlaying)
                    viewModel.onUserSetPlaying(nextPlaying)
                },
                onSeekTo = playbackController::seekTo,
                onEngineSelected = viewModel::onEngineSelected,
                onRetry = viewModel::retryPlayback,
            )
        }
    }
}

private class PlaybackMetricsHolder {
    var positionMs: Long = 0L
    var durationMs: Long = 0L
    var isPlaying: Boolean = false
}

private const val TAG = "PlayerRoute"
private const val PROGRESS_SYNC_INTERVAL_MS = 5_000L
