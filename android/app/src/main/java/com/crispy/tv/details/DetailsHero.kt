package com.crispy.tv.details

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.drawable.toBitmap
import coil.compose.AsyncImagePainter
import coil.compose.AsyncImage
import coil.compose.rememberAsyncImagePainter
import com.crispy.tv.R
import com.crispy.tv.details.trailer.TrailerPlaybackSource
import com.crispy.tv.details.trailer.YouTubeTrailerExtractor
import com.crispy.tv.home.MediaDetails
import com.crispy.tv.metadata.tmdb.TmdbApi
import com.crispy.tv.ui.components.skeletonElement
import com.crispy.tv.ui.theme.responsivePageHorizontalPadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun detailsHeroBackdropSize(screenWidthPx: Float): String {
    return if (screenWidthPx > 1280f) "original" else "w1280"
}

internal fun detailsHeroImageUrl(details: MediaDetails?, backdropSize: String): String? {
    return details?.backdropUrl?.let { TmdbApi.resizedImageUrl(it, size = backdropSize) }
        ?: details?.posterUrl
}

@Composable
internal fun HeroSection(
    details: MediaDetails?,
    imageUrl: String?,
    palette: DetailsPaletteColors,
    trailerKey: String?,
    showTrailer: Boolean,
    isTrailerPlaying: Boolean,
    isTrailerMuted: Boolean,
    onHeroImageLoaded: (Bitmap) -> Unit,
    onHeroImageLoadFailed: () -> Unit,
    onToggleTrailer: () -> Unit,
) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val horizontalPadding = responsivePageHorizontalPadding()
    val heroHeight = (configuration.screenHeightDp.dp * 0.43f).coerceIn(300.dp, 440.dp)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(heroHeight)
    ) {
        val widthPx = with(density) { maxWidth.roundToPx() }
        val heightPx = with(density) { maxHeight.toPx() }

        if (details == null && imageUrl.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .skeletonElement(
                        shape = androidx.compose.ui.graphics.RectangleShape,
                        color = DetailsSkeletonColors.Base
                    )
            ) {
                // Bottom fade to merge hero into the page background.
                HeroBottomFade(
                    pageBackground = palette.pageBackground,
                    heightPx = heightPx,
                )

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = horizontalPadding)
                        .padding(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.64f)
                            .height(38.dp)
                            .skeletonElement(color = DetailsSkeletonColors.Elevated)
                    )
                }
            }
            return@BoxWithConstraints
        }

        val imagePainter = rememberAsyncImagePainter(model = imageUrl)
        LaunchedEffect(imageUrl, imagePainter.state) {
            when (val state = imagePainter.state) {
                is AsyncImagePainter.State.Success -> {
                    onHeroImageLoaded(state.result.drawable.toBitmap(width = 128, height = 128))
                }
                is AsyncImagePainter.State.Error -> onHeroImageLoadFailed()
                else -> Unit
            }
        }
        if (!imageUrl.isNullOrBlank()) {
            Image(
                painter = imagePainter,
                contentDescription = details?.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = palette.pillBackground
            ) {}
        }

        if (details == null) {
            HeroBottomFade(
                pageBackground = palette.pageBackground,
                heightPx = heightPx,
            )
            return@BoxWithConstraints
        }

        val hasTrailer = !trailerKey.isNullOrBlank()
        var trailerIsPlaying by remember(trailerKey) { mutableStateOf(false) }
        var trailerHasRenderedFirstFrame by remember(trailerKey) { mutableStateOf(false) }
        val shouldAttemptPlayback = showTrailer && hasTrailer && isTrailerPlaying

        if (showTrailer && hasTrailer) {
            HeroYouTubeTrailerLayer(
                modifier = Modifier.fillMaxSize(),
                trailerKey = trailerKey,
                viewportWidthPx = widthPx,
                viewportHeightPx = heightPx.toInt(),
                shouldPlay = shouldAttemptPlayback,
                isMuted = isTrailerMuted,
                onFirstFrameRendered = { trailerHasRenderedFirstFrame = true },
                onPlaybackState = { state, _ -> trailerIsPlaying = state == 1 || state == 3 },
            )
        }

        val coverAlpha by animateFloatAsState(
            targetValue = if (showTrailer && hasTrailer && trailerHasRenderedFirstFrame) 0f else 1f,
            animationSpec = tween(durationMillis = 380, easing = FastOutSlowInEasing),
            label = "hero_trailer_cover_alpha",
        )

        if (showTrailer && hasTrailer && coverAlpha > 0.001f) {
            if (!imageUrl.isNullOrBlank()) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(alpha = coverAlpha),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(alpha = coverAlpha),
                    color = palette.pillBackground,
                ) {}
            }
        }

        // Top scrim for app bar/buttons readability.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.55f),
                        0.38f to Color.Transparent
                    )
                )
        )

        // Bottom fade to merge hero into the page background.
        HeroBottomFade(
            pageBackground = palette.pageBackground,
            heightPx = heightPx,
        )

        if (hasTrailer) {
            val isActuallyPlaying = isTrailerPlaying && trailerIsPlaying
            val icon = if (isActuallyPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow
            val label = if (isActuallyPlaying) "Pause" else "Trailer"
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 136.dp)
                    .clip(MaterialTheme.shapes.extraLarge)
                    .clickable { onToggleTrailer() },
                color = Color.Black.copy(alpha = 0.34f),
                contentColor = Color.White
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(label, style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = horizontalPadding)
                .padding(bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val logoUrl = details.logoUrl?.trim().orEmpty()
            if (logoUrl.isNotBlank()) {
                AsyncImage(
                    model = logoUrl,
                    contentDescription = details.title,
                    modifier = Modifier
                        .fillMaxWidth(0.81f)
                        .height(104.dp),
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.Center
                )
            } else {
                Text(
                    text = details.title,
                    style = MaterialTheme.typography.headlineLarge,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun HeroBottomFade(
    pageBackground: Color,
    heightPx: Float,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colorStops =
                        arrayOf(
                            0f to Color.Transparent,
                            0.66f to Color.Transparent,
                            1f to pageBackground
                        ),
                    startY = 0f,
                    endY = heightPx
                )
            )
    )
}

@Composable
private fun HeroYouTubeTrailerLayer(
    modifier: Modifier,
    trailerKey: String,
    viewportWidthPx: Int,
    viewportHeightPx: Int,
    shouldPlay: Boolean,
    isMuted: Boolean,
    onFirstFrameRendered: () -> Unit,
    onPlaybackState: (state: Int, timeSeconds: Double) -> Unit,
) {
    val context = LocalContext.current

    val latestOnFirstFrameRendered = rememberUpdatedState(onFirstFrameRendered)
    val latestOnPlaybackState = rememberUpdatedState(onPlaybackState)
    val latestShouldPlay = rememberUpdatedState(shouldPlay)

    var source by remember(trailerKey) { mutableStateOf<TrailerPlaybackSource?>(null) }

    LaunchedEffect(trailerKey) {
        source = null
    }

    LaunchedEffect(trailerKey, shouldPlay) {
        if (!shouldPlay) return@LaunchedEffect
        if (source != null) return@LaunchedEffect
        source = withContext(Dispatchers.IO) {
            YouTubeTrailerExtractor.resolve(
                videoId = trailerKey,
                viewportWidthPx = viewportWidthPx,
                viewportHeightPx = viewportHeightPx,
            )
        }
    }

    val playbackSource = source ?: return

    val exoPlayer = remember(playbackSource.videoUrl, playbackSource.audioUrl) {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context))
            .build()
            .apply {
                repeatMode = Player.REPEAT_MODE_ONE
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                volume = if (isMuted) 0f else 1f
            }
    }

    DisposableEffect(exoPlayer) {
        onDispose {
            runCatching { exoPlayer.release() }
        }
    }

    var hasRenderedFirstFrame by remember(playbackSource.videoUrl, playbackSource.audioUrl) { mutableStateOf(false) }
    var lastSentState by remember(playbackSource.videoUrl, playbackSource.audioUrl) { mutableStateOf<Int?>(null) }

    fun sendState(state: Int) {
        if (lastSentState == state) return
        lastSentState = state
        latestOnPlaybackState.value(state, exoPlayer.currentPosition / 1000.0)
    }

    DisposableEffect(exoPlayer) {
        val listener =
            object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    hasRenderedFirstFrame = true
                    latestOnFirstFrameRendered.value()
                    if (latestShouldPlay.value) {
                        sendState(1)
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) {
                        if (hasRenderedFirstFrame) {
                            sendState(1)
                        }
                        return
                    }

                    when (exoPlayer.playbackState) {
                        Player.STATE_ENDED -> sendState(0)
                        Player.STATE_BUFFERING -> sendState(3)
                        else -> sendState(2)
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (exoPlayer.isPlaying) return
                    when (playbackState) {
                        Player.STATE_ENDED -> sendState(0)
                        Player.STATE_BUFFERING -> sendState(3)
                    }
                }
            }

        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    LaunchedEffect(exoPlayer, playbackSource.videoUrl, playbackSource.audioUrl) {
        val mediaSourceFactory = DefaultMediaSourceFactory(context)
        val videoSource = mediaSourceFactory.createMediaSource(MediaItem.fromUri(playbackSource.videoUrl))
        val mediaSource =
            playbackSource.audioUrl?.let { audioUrl ->
                MergingMediaSource(
                    videoSource,
                    mediaSourceFactory.createMediaSource(MediaItem.fromUri(audioUrl))
                )
            } ?: videoSource

        hasRenderedFirstFrame = false
        lastSentState = null

        exoPlayer.setMediaSource(mediaSource)
        exoPlayer.prepare()
    }

    SideEffect {
        exoPlayer.volume = if (isMuted) 0f else 1f
    }

    LaunchedEffect(exoPlayer, shouldPlay) {
        exoPlayer.playWhenReady = shouldPlay
        if (shouldPlay) {
            exoPlayer.play()
        } else {
            exoPlayer.pause()
        }
    }

    Box(modifier = modifier.clipToBounds()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                (LayoutInflater.from(ctx).inflate(R.layout.hero_trailer_player_view, null, false) as PlayerView).apply {
                    layoutParams =
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    useController = false
                    controllerAutoShow = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                    isEnabled = false
                    isClickable = false
                    isLongClickable = false
                    isFocusable = false
                    isFocusableInTouchMode = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    player = exoPlayer
                    videoSurfaceView?.apply {
                        isClickable = false
                        isLongClickable = false
                        isFocusable = false
                        isFocusableInTouchMode = false
                    }
                }
            },
            update = { view ->
                view.player = exoPlayer
            }
        )
    }
}
