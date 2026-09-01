package com.crispy.tv.details

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.keepScreenOn
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil3.compose.AsyncImage
import com.crispy.tv.ui.components.SharedImageMemoryKeys
import com.crispy.tv.R
import com.crispy.tv.details.trailer.TrailerPlaybackSource
import com.crispy.tv.details.trailer.YouTubeTrailerExtractor
import com.crispy.tv.addons.model.MediaDetails
import com.crispy.tv.ui.components.CardStyle
import com.crispy.tv.ui.components.crispyImageRequest
import com.crispy.tv.ui.components.rememberCrispyImageModel
import com.crispy.tv.ui.components.skeletonElement
import com.crispy.tv.ui.navigation.LocalNavAnimatedContentScope
import com.crispy.tv.ui.navigation.LocalSharedTransitionScope
import com.crispy.tv.ui.navigation.animateHeroCornerRadius
import com.crispy.tv.ui.theme.responsivePageHorizontalPadding
import com.crispy.tv.details.trailer.TrailerSource
import com.crispy.tv.PlaybackDependencies
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun detailsHeroImageUrl(details: MediaDetails?): String? {
    return details?.artworkUrl
}

internal data class HeroTrailerSource(
    val id: String,
    val source: TrailerSource,
)

@Composable
internal fun HeroSection(
    details: MediaDetails?,
    imageUrl: String?,
    palette: DetailsPaletteColors,
    trailer: List<HeroTrailerSource> = emptyList(),
    showTrailer: Boolean,
    isTrailerPlaying: Boolean,
    isTrailerMuted: Boolean,
    onHeroImageLoaded: () -> Unit,
    onHeroImageLoadFailed: () -> Unit,
    onToggleTrailer: () -> Unit,
    onFocusLossPause: () -> Unit,
    itemId: String? = null,
    sharedElementKey: String? = null,
) {
    val sharedTransitionScope = LocalSharedTransitionScope.current
    val animatedVisibilityScope = LocalNavAnimatedContentScope.current
    val resolvedKey = sharedElementKey?.takeIf { it.isNotBlank() } ?: itemId
    val backdropKey = resolvedKey?.let { "backdrop-$it" }
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val horizontalPadding = responsivePageHorizontalPadding()
    val heroHeight = (configuration.screenHeightDp.dp * 0.43f).coerceIn(300.dp, 440.dp)

    val hasTrailer = trailer.isNotEmpty()
    var trailerIsPlaying by remember(trailer) { mutableStateOf(false) }
    var trailerHasRenderedFirstFrame by remember(trailer) { mutableStateOf(false) }
    val isActuallyPlaying = isTrailerPlaying && trailerIsPlaying

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(heroHeight)
            .then(if (isActuallyPlaying) Modifier.keepScreenOn() else Modifier)
    ) {
        val heroMaxWidth = maxWidth
        val widthPx = with(density) { heroMaxWidth.roundToPx() }
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

        if (!imageUrl.isNullOrBlank()) {
            val cardCacheKey = backdropKey?.let { SharedImageMemoryKeys.getCardKey(it) }
            val heroRequest = crispyImageRequest(
                url = imageUrl,
                width = heroMaxWidth,
                height = heroHeight,
                memoryCacheKey = backdropKey,
                placeholderMemoryCacheKey = cardCacheKey,
            )
            val backdropModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null && backdropKey != null) {
                val cornerRadius = with(animatedVisibilityScope) {
                    animateHeroCornerRadius(CardStyle.CardCornerRadiusDp.dp)
                }
                with(sharedTransitionScope) {
                    Modifier
                        .sharedElement(
                            rememberSharedContentState(key = backdropKey),
                            animatedVisibilityScope = animatedVisibilityScope,
                        )
                        .clip(RoundedCornerShape(cornerRadius))
                        .fillMaxSize()
                }
            } else {
                Modifier.fillMaxSize()
            }
            AsyncImage(
                model = heroRequest ?: imageUrl,
                contentDescription = details?.title,
                modifier = backdropModifier,
                contentScale = ContentScale.Crop,
                onSuccess = { result ->
                    onHeroImageLoaded()
                },
                onError = { error ->
                    onHeroImageLoadFailed()
                },
            )
        } else {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = palette.pillBackground
            ) {}
        }

        if (details == null) {
            return@BoxWithConstraints
        }

        val shouldAttemptPlayback = showTrailer && hasTrailer && isTrailerPlaying

        if (showTrailer && hasTrailer) {
            HeroTrailerLayer(
                modifier = Modifier.fillMaxSize(),
                trailer = trailer,
                viewportWidthPx = widthPx,
                viewportHeightPx = heightPx.toInt(),
                shouldPlay = shouldAttemptPlayback,
                isMuted = isTrailerMuted,
                onFirstFrameRendered = { trailerHasRenderedFirstFrame = true },
                onPlaybackState = { state, _ -> trailerIsPlaying = state == 1 || state == 3 },
                onFocusLossPause = onFocusLossPause,
            )
        }

        val coverAlpha by animateFloatAsState(
            targetValue = if (showTrailer && hasTrailer && trailerHasRenderedFirstFrame && !isActuallyPlaying) 1f else 0f,
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

        if (hasTrailer) {
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
            val resolvedLogoUrl = details?.logoUrl?.trim()?.takeIf { it.isNotEmpty() }
            if (resolvedLogoUrl != null) {
                val logoModel = rememberCrispyImageModel(
                    url = resolvedLogoUrl,
                    width = heroMaxWidth * 0.81f,
                    height = 104.dp,
                )
                AsyncImage(
                    model = logoModel ?: resolvedLogoUrl,
                    contentDescription = details?.title,
                    modifier = Modifier
                        .fillMaxWidth(0.81f)
                        .height(104.dp),
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.Center
                )
            } else {
                Text(
                    text = details?.title ?: "",
                    style = MaterialTheme.typography.headlineLarge,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }

        // Bottom blur overlay for dissolve effect
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(120.dp)
                .blur(60.dp)
                .background(palette.pageBackground)
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun HeroTrailerLayer(
    modifier: Modifier,
    trailer: List<HeroTrailerSource>,
    viewportWidthPx: Int,
    viewportHeightPx: Int,
    shouldPlay: Boolean,
    isMuted: Boolean,
    onFirstFrameRendered: () -> Unit,
    onPlaybackState: (state: Int, timeSeconds: Double) -> Unit,
    onFocusLossPause: () -> Unit,
) {
    val context = LocalContext.current
    val audioFocusManager = PlaybackDependencies.getAudioFocusManager(context)

    val latestOnFirstFrameRendered = rememberUpdatedState(onFirstFrameRendered)
    val latestOnPlaybackState = rememberUpdatedState(onPlaybackState)
    val latestShouldPlay = rememberUpdatedState(shouldPlay)

    var currentIndex by remember(trailer) { mutableStateOf(0) }
    var source by remember(trailer) { mutableStateOf<TrailerPlaybackSource?>(null) }
    var advanceRequests by remember(trailer) { mutableStateOf(0) }

    LaunchedEffect(trailer) {
        currentIndex = 0
        source = null
        advanceRequests = 0
    }

    val currentEntry = trailer.getOrNull(currentIndex)

    LaunchedEffect(currentEntry, shouldPlay) {
        if (!shouldPlay) return@LaunchedEffect
        if (source != null) return@LaunchedEffect
        val entry = currentEntry ?: return@LaunchedEffect
        source = when (entry.source) {
            TrailerSource.DIRECT -> TrailerPlaybackSource(videoUrl = entry.id)
            TrailerSource.YOUTUBE -> withContext(Dispatchers.IO) {
                YouTubeTrailerExtractor.resolve(
                    videoId = entry.id,
                    viewportWidthPx = viewportWidthPx,
                    viewportHeightPx = viewportHeightPx,
                )
            }
        }
        if (source == null) advanceRequests++
    }

    LaunchedEffect(advanceRequests) {
        if (advanceRequests <= 0) return@LaunchedEffect
        val next = currentIndex + 1
        if (next < trailer.size) {
            currentIndex = next
            source = null
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
        audioFocusManager.registerSource("trailer", pauseHandler = onFocusLossPause)
        onDispose {
            audioFocusManager.unregisterSource("trailer")
            audioFocusManager.release("trailer")
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
        var errored = false
        val listener =
            object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    hasRenderedFirstFrame = true
                    latestOnFirstFrameRendered.value()
                    if (latestShouldPlay.value) {
                        sendState(1)
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (!errored) {
                        errored = true
                        advanceRequests++
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
            audioFocusManager.acquire("trailer")
        } else {
            exoPlayer.pause()
            audioFocusManager.release("trailer")
        }
    }

    Box(
        modifier = modifier.clipToBounds()
    ) {
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
