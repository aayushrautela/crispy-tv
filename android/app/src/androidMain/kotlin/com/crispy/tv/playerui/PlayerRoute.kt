@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.crispy.tv.playerui

import android.util.Log
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.details.rememberSeedColor
import com.crispy.tv.details.detailsPaletteFromScheme
import com.crispy.tv.details.rememberDetailsColorScheme
import com.crispy.tv.nativeengine.playback.NativeVideoLayout
import kotlin.math.roundToInt

@Composable
fun PlayerRoute(
    session: PlayerSessionViewModel,
    isInPictureInPictureMode: Boolean,
    onPictureInPictureConfigChanged: (PictureInPictureConfig) -> Unit,
    onBack: () -> Unit,
    onOpenTitle: (CatalogItem) -> Unit = {},
) {
    val uiState by session.uiState.collectAsStateWithLifecycle()
    // Selector state is read by the sheet only, so per-provider emissions
    // recompose the sheet without touching the rest of the player tree.
    val selectorState by session.selectorState.collectAsStateWithLifecycle()
    // Passed down unread so a 500ms position tick only recomposes the seekbar/time-pill
    // leaves, not the whole overlay tree.
    val positionMsState = session.playbackPositionMs.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val imageUrl = if (uiState.isMetadataLoaded) uiState.artworkUrl else null
    val fallbackSeed = Color.White
    val rawSeed by rememberSeedColor(imageUrl = imageUrl, fallbackSeed = fallbackSeed)
    val seedColor = rawSeed ?: fallbackSeed

    val colorScheme = rememberDetailsColorScheme(seedColor = seedColor)
    val palette = remember(colorScheme) { detailsPaletteFromScheme(colorScheme) }
    var videoBounds by remember { mutableStateOf<PictureInPictureSourceRect?>(null) }
    val updateVideoBounds: (PictureInPictureSourceRect) -> Unit = { bounds ->
        if (videoBounds != bounds) {
            Log.d(
                TAG,
                "videoBounds changed engine=${uiState.activeEngine} isInPip=$isInPictureInPictureMode bounds=$bounds",
            )
            videoBounds = bounds
        }
    }

    val gestureController = remember(context) { tryCreateGestureController(context.findActivity()) }
    val gestureFeedback = rememberGestureFeedback()
    val scope = rememberCoroutineScope()

    DisposableEffect(gestureController) {
        onDispose {
            gestureController?.restoreBrightness()
        }
    }

    DisposableEffect(session) {
        onDispose {
            onPictureInPictureConfigChanged(PictureInPictureConfig())
        }
    }

    val surfaceModifier = Modifier
        .playerVerticalDragGestures(
            gestureController = gestureController,
            onBrightnessChange = { level ->
                gestureFeedback.show(
                    scope,
                    GestureFeedbackMessage(
                        text = formatGestureBrightness(level),
                        icon = GestureIcons.Brightness,
                    ),
                    holdMs = 800,
                )
            },
            onVolumeChange = { level ->
                gestureFeedback.show(
                    scope,
                    GestureFeedbackMessage(
                        text = if (level.isMuted) "Muted" else formatGestureVolume(level),
                        icon = if (level.isMuted) GestureIcons.VolumeMuted else GestureIcons.VolumeUp,
                        autoMirror = true,
                    ),
                    holdMs = 800,
                )
            },
        )
        .onGloballyPositioned { coordinates ->
            val bounds = coordinates.boundsInWindow()
            updateVideoBounds(
                PictureInPictureSourceRect(
                    bounds.left.roundToInt(),
                    bounds.top.roundToInt(),
                    bounds.right.roundToInt(),
                    bounds.bottom.roundToInt(),
                ),
            )
        }

    LaunchedEffect(videoBounds, uiState.videoLayout, uiState.isPlaying, uiState.isBuffering, uiState.errorMessage, uiState.stableDurationMs) {
        // Wait for real video geometry before advertising PiP; using the window bounds
        // ratio while the first frames are still loading produces a wrong-aspect window.
        // Audio-only streams never report a layout, so a settled duration unlocks them.
        val playbackGeometryKnown = uiState.videoLayout != null || uiState.stableDurationMs > 0L
        val aspectRatio =
            uiState.videoLayout.toPictureInPictureAspectRatio()
                ?: videoBounds
                    ?.takeIf { it.width() > 0 && it.height() > 0 }
                    ?.let { bounds -> PictureInPictureAspectRatio(bounds.width(), bounds.height()) }
        val pipEnabled =
            videoBounds != null &&
                uiState.errorMessage == null &&
                playbackGeometryKnown &&
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

    LaunchedEffect(uiState.resizeMode) {
        session.applyResizeMode(uiState.resizeMode)
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black),
    ) {
        // The tunneled mediacodec_embed vo renders at the video's native size and
        // the SurfaceView scales that buffer to the view, so a full-screen view
        // stretches any video whose aspect differs from the screen. Sizing the
        // view to the video's own aspect keeps that scaling uniform; the black
        // box behind letterboxes the remainder. Until the video reports its
        // size the view fills the screen as before.
        val videoAspect = uiState.videoLayout?.aspectRatioValue()
        val screenConfiguration = LocalConfiguration.current
        val screenAspect =
            screenConfiguration.screenWidthDp.value / screenConfiguration.screenHeightDp.value
        val surfaceSizeModifier =
            if (videoAspect != null && videoAspect > 0f && screenAspect > 0f) {
                val fitted = Modifier.align(Alignment.Center).aspectRatio(videoAspect)
                if (videoAspect >= screenAspect) fitted.fillMaxWidth() else fitted.fillMaxHeight()
            } else {
                Modifier.fillMaxSize()
            }
        AndroidView(
            modifier = surfaceModifier.then(surfaceSizeModifier),
            factory = { viewContext -> session.createMpvSurfaceView(viewContext) },
            update = { surfaceView: SurfaceView ->
                session.attachMpvSurface(surfaceView)
            },
        )

        if (!isInPictureInPictureMode) {
            PlayerOverlay(
                uiState = uiState,
                selectorState = selectorState,
                positionMsState = positionMsState,
                palette = palette,
                // `LocalConfiguration` is android-only even under Compose Multiplatform: it
                // is absent from `ui`'s common metadata and lives in `ui-android`. This is
                // therefore the only place the width can be read, and the overlay receives
                // the decision's result rather than a way to re-make it. Same shape as
                // `HomeRoute`/`DetailsRoute`, which already take `isCompact: Boolean` for
                // an identical `< 600` read.
                isCompact = LocalConfiguration.current.screenWidthDp < 600,
                // `::englishDisplayNameForTag` is the Android implementation of a slot the
                // shared track sheets already take with no default, so it needs no import --
                // same package. The overlay is moving to commonMain and this line is the one
                // that stays behind to say which of the implementations it is.
                displayName = ::englishDisplayNameForTag,
                // This is the one line that stays behind. `androidx.activity.compose`
                // publishes no KMP variant and is declared only in `androidMain`, so
                // the overlay takes the registration as a slot rather than naming the
                // platform type. The overlay still decides *when* to register
                // (`isSurfaceOpen`); only the act of registering is handed over.
                registerBackHandler = { enabled, onBack ->
                    BackHandler(enabled = enabled, onBack = onBack)
                },
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
                onShowInfo = session::showInfo,
                onShowEpisodes = session::showEpisodes,
                onShowStreams = session::showStreams,
                onShowAudio = session::showAudioTracks,
                onShowSubtitles = session::showSubtitles,
                onCloseSurface = session::closeActiveSurface,
                onProviderSelected = session::onProviderSelected,
                onStreamSelected = session::onStreamSelected,
                onRetryPlayback = session::retryPlayback,
                onSelectAudioTrack = session::selectAudioTrack,
                onSelectSubtitleTrack = session::selectSubtitleTrack,
                onRefreshAddonSubtitles = session::refreshAddonSubtitles,
                onSelectEpisode = session::showStreamsForEpisode,
                onSeasonSelected = session::onSeasonSelected,
                onShowMore = session::showMore,
                onOpenTitle = onOpenTitle,
                onCycleResizeMode = {
                    val next = uiState.resizeMode.next()
                    session.setResizeMode(next)
                    gestureFeedback.show(
                        scope,
                        GestureFeedbackMessage(
                            text = next.label,
                            icon = GestureIcons.Resize,
                        ),
                        holdMs = 900,
                    )
                },
                onCommitSeek = { targetMs, _ ->
                    session.seekTo(targetMs)
                },
            )

            GestureFeedbackOverlay(feedback = gestureFeedback, palette = palette)
        }
    }
}

private fun NativeVideoLayout?.toPictureInPictureAspectRatio(): PictureInPictureAspectRatio? {
    val layout = this ?: return null
    val aspectRatio = layout.aspectRatioValue() ?: return null
    val denominator = 10_000
    val numerator = (aspectRatio * denominator.toFloat()).roundToInt()
    return if (numerator > 0) {
        PictureInPictureAspectRatio(numerator, denominator)
    } else {
        null
    }
}

private const val TAG = "PlayerRoute"
