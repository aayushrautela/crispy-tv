@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package com.crispy.tv.playerui

import android.graphics.Rect
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.PlayerView
import com.crispy.tv.nativeengine.playback.NativePlaybackEngine
import kotlin.math.roundToInt

@Composable
fun PlayerRoute(
    session: PlayerSessionViewModel,
    isInPictureInPictureMode: Boolean,
    onPictureInPictureConfigChanged: (PictureInPictureConfig) -> Unit,
    onBack: () -> Unit,
) {
    val uiState by session.uiState.collectAsStateWithLifecycle()
    var videoBounds by remember { mutableStateOf<Rect?>(null) }
    val updateVideoBounds: (Rect) -> Unit = { bounds ->
        if (videoBounds != bounds) {
            Log.d(
                TAG,
                "videoBounds changed engine=${uiState.activeEngine} isInPip=$isInPictureInPictureMode bounds=$bounds",
            )
            videoBounds = bounds
        }
    }

    DisposableEffect(session) {
        onDispose {
            onPictureInPictureConfigChanged(PictureInPictureConfig())
        }
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
                                updateVideoBounds(
                                    Rect(
                                        bounds.left.roundToInt(),
                                        bounds.top.roundToInt(),
                                        bounds.right.roundToInt(),
                                        bounds.bottom.roundToInt(),
                                    ),
                                )
                            },
                    factory = { viewContext ->
                        PlayerView(viewContext).apply {
                            useController = false
                            session.bindExoPlayerView(this)
                        }
                    },
                    update = { playerView ->
                        session.bindExoPlayerView(playerView)
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
                                updateVideoBounds(
                                    Rect(
                                        bounds.left.roundToInt(),
                                        bounds.top.roundToInt(),
                                        bounds.right.roundToInt(),
                                        bounds.bottom.roundToInt(),
                                    ),
                                )
                            },
                    factory = { viewContext -> session.createVlcSurfaceView(viewContext) },
                    update = { surfaceView: SurfaceView ->
                        session.attachVlcSurface(surfaceView)
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
                    session.setPlaying(nextPlaying)
                },
                onSeekTo = session::seekTo,
                onEngineSelected = session::onEngineSelected,
                onRetry = session::retryPlayback,
            )
        }
    }
}

private const val TAG = "PlayerRoute"
