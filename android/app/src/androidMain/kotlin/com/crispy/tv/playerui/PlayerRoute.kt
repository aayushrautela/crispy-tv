@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.crispy.tv.playerui

import android.util.Log
import android.view.SurfaceView
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
import com.crispy.tv.nativeengine.playback.PlayerResizeMode
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

    // In Zoom the surface is sized to cover the window, so its own bounds reach
    // past the window edge; the PiP source rect has to be the player area then,
    // because that is what the viewer actually sees.
    var playerAreaBounds by remember { mutableStateOf<PictureInPictureSourceRect?>(null) }
    // Zoom crops the picture in the UI layer: the surface grows until it covers
    // the window and the overflow is cropped by the window edge. That crop is
    // fullscreen-only -- a PiP window keeps the video's own ratio, and mpv's
    // crop option owns the framing there instead, because there the surface can
    // stay fitted to the video.
    val zoomCropActive =
        uiState.resizeMode == PlayerResizeMode.Zoom && !isInPictureInPictureMode
    val effectiveVideoBounds = if (zoomCropActive) playerAreaBounds else videoBounds
    // The PiP window is opened with the geometry on screen when the viewer
    // leaves, and after that it keeps it. Re-deriving the config inside the
    // window changed the source rect a beat after entry -- `zoomCropActive`
    // flips the moment the mode changes, so leaving Zoom moved the rect from
    // the whole player area to the letterboxed surface -- which reads as the
    // window jumping. The ratio itself never moves, because the window keeps
    // the video's own shape either way. The controller drops an unchanged
    // config, so re-sending the remembered one costs nothing and nothing
    // re-shapes on its own afterwards.
    var pipConfigBeforeEntry by remember { mutableStateOf<PictureInPictureConfig?>(null) }
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

    LaunchedEffect(
        effectiveVideoBounds,
        uiState.videoLayout,
        uiState.isPlaying,
        uiState.isBuffering,
        uiState.errorMessage,
        uiState.stableDurationMs,
        isInPictureInPictureMode,
    ) {
        // Wait for real video geometry before advertising PiP; using the window bounds
        // ratio while the first frames are still loading produces a wrong-aspect window.
        // Audio-only streams never report a layout, so a settled duration unlocks them.
        val playbackGeometryKnown = uiState.videoLayout != null || uiState.stableDurationMs > 0L
        // The PiP window keeps the video's own ratio, never the crop: a window
        // wider than the video letterboxes it inside the window, so the cropped
        // player-area ratio left permanent black bars in PiP. Cropping is a
        // fullscreen behaviour -- inside PiP the surface stays fitted to the
        // video, so there the engine's own crop option is what can move the
        // framing. The player-area ratio stays as the fallback for audio-only
        // streams, which never report a layout.
        val visibleBoundsAspect =
            effectiveVideoBounds
                ?.takeIf { it.width() > 0 && it.height() > 0 }
                ?.let { bounds -> PictureInPictureAspectRatio(bounds.width(), bounds.height()) }
        val aspectRatio = uiState.videoLayout.toPictureInPictureAspectRatio() ?: visibleBoundsAspect
        val pipEnabled =
            effectiveVideoBounds != null &&
                uiState.errorMessage == null &&
                playbackGeometryKnown &&
                (uiState.isPlaying || uiState.isBuffering || uiState.stableDurationMs > 0L)
        val config =
            PictureInPictureConfig(
                enabled = pipEnabled,
                sourceRect = effectiveVideoBounds,
                aspectRatio = aspectRatio,
            )
        // Enter the window with the shape and rect the fullscreen surface
        // actually had, then hold them there. The ratio is the video's own in
        // both modes, so only the source rect can move once inside:
        // `zoomCropActive` flips on entry, and the surface returns to the
        // letterboxed video rect -- which is exactly the "no change after we
        // are in pip" the viewer reported.
        if (isInPictureInPictureMode) {
            pipConfigBeforeEntry?.let(onPictureInPictureConfigChanged)
        } else {
            pipConfigBeforeEntry = config
            onPictureInPictureConfigChanged(config)
        }
        Log.d(
            TAG,
            "pip config videoBounds=$videoBounds aspectRatio=$aspectRatio enabled=$pipEnabled isPlaying=${uiState.isPlaying} isBuffering=${uiState.isBuffering} stableDurationMs=${uiState.stableDurationMs} error=${uiState.errorMessage} frozen=${pipConfigBeforeEntry != null}",
        )
    }

    LaunchedEffect(uiState.resizeMode) {
        session.applyResizeMode(uiState.resizeMode)
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onGloballyPositioned { coordinates ->
                    val bounds = coordinates.boundsInWindow()
                    val rect = PictureInPictureSourceRect(
                        bounds.left.roundToInt(),
                        bounds.top.roundToInt(),
                        bounds.right.roundToInt(),
                        bounds.bottom.roundToInt(),
                    )
                    if (playerAreaBounds != rect) playerAreaBounds = rect
                },
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // The tunneled mediacodec_embed vo renders at the video's native size and
            // the SurfaceView scales that buffer to the view, so a full-screen view
            // stretches any video whose aspect differs from the screen. Sizing the
            // view to the video's own aspect keeps that scaling uniform; the black
            // box behind letterboxes the remainder. Until the video reports its
            // size the view fills the screen as before.
            val videoAspect = uiState.videoLayout?.aspectRatioValue()
            val screenConfiguration = LocalConfiguration.current
            val screenAspect =
                (screenConfiguration.screenWidthDp / screenConfiguration.screenHeightDp)
            // Zoom morphs rather than pops: both ends are computed as sizes,
            // then this fraction walks between them, so the SurfaceView
            // animates like the controls instead of appearing in one frame.
            // In PiP the surface stays fitted, so the target is Fit there and
            // mpv's own panscan does the crop inside the window.
            val zoomFraction by animateFloatAsState(
                targetValue = if (zoomCropActive) 1f else 0f,
                animationSpec =
                    tween(durationMillis = ZoomCropDurationMillis, easing = FastOutSlowInEasing),
                label = "zoomCrop",
            )
            val surfaceSizeModifier =
                if (videoAspect == null || videoAspect <= 0f || maxHeight <= 0.dp) {
                    // Until the video reports its size the view fills the window.
                    Modifier.fillMaxSize()
                } else {
                    // Fitted: the view keeps the video's aspect and the black
                    // box behind letterboxes the remainder.
                    val fitSize =
                        if (videoAspect >= screenAspect) {
                            SizeDp(maxWidth, maxWidth / videoAspect)
                        } else {
                            SizeDp(maxHeight * videoAspect, maxHeight)
                        }
                    // Cover: the same aspect, grown until it spans the window,
                    // so the overflow is cropped by the window edge.
                    val windowAspect = maxWidth / maxHeight
                    val coverSize =
                        if (videoAspect >= windowAspect) {
                            SizeDp(maxHeight * videoAspect, maxHeight)
                        } else {
                            SizeDp(maxWidth, maxWidth / videoAspect)
                        }
                    Modifier
                        .align(Alignment.Center)
                        .requiredSize(
                            lerpDp(fitSize.width, coverSize.width, zoomFraction),
                            lerpDp(fitSize.height, coverSize.height, zoomFraction),
                        )
                }
            AndroidView(
                modifier = surfaceModifier.then(surfaceSizeModifier),
                factory = { viewContext -> session.createMpvSurfaceView(viewContext) },
                update = { surfaceView: SurfaceView ->
                    session.attachMpvSurface(surfaceView)
                },
            )
        }

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

/**
 * How long the surface takes to walk between its fitted size and its cover
 * crop. The controls use the same easing family, so the crop settles with
 * them instead of against them.
 */
private const val ZoomCropDurationMillis = 300

/** A size in density units, the pair `requiredSize` wants. */
private data class SizeDp(val width: Dp, val height: Dp)

/**
 * Linear interpolation between two sizes. `androidx.compose.ui.unit` ships no
 * `lerp` for `Dp` (only `Color` has one), and the two ends here are always
 * sizes in the same unit family, so the arithmetic is spelled out rather
 * than guessed at through a `Sp` conversion.
 */
private fun lerpDp(start: Dp, stop: Dp, fraction: Float): Dp =
    start + (stop - start) * fraction.coerceIn(0f, 1f)

private const val TAG = "PlayerRoute"
