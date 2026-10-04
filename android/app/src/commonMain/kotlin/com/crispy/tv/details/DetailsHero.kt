package com.crispy.tv.details

import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.keepScreenOn
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.crispy.tv.addons.model.MediaDetails
import com.crispy.tv.details.trailer.TrailerSource
import com.crispy.tv.ui.components.CardStyle
import com.crispy.tv.ui.components.SharedImageMemoryKeys
import com.crispy.tv.ui.components.crispyImageRequest
import com.crispy.tv.ui.components.rememberCrispyImageModel
import com.crispy.tv.ui.components.skeletonElement
import com.crispy.tv.ui.navigation.LocalNavAnimatedContentScope
import com.crispy.tv.ui.navigation.LocalSharedTransitionScope
import com.crispy.tv.ui.navigation.animateHeroCornerRadius
import com.crispy.tv.ui.resources.Res
import com.crispy.tv.ui.resources.ic_pause_filled
import com.crispy.tv.ui.resources.ic_play_arrow_filled
import com.crispy.tv.ui.theme.responsivePageHorizontalPadding
import org.jetbrains.compose.resources.painterResource

@Composable
internal fun HeroSection(
    details: MediaDetails?,
    imageUrl: String?,
    palette: DetailsPaletteColors,
    trailer: List<HeroTrailerSource> = emptyList(),
    screenHeightDp: Int,
    heroTrailerLayer: @Composable (HeroTrailerLayerArgs) -> Unit,
    showTrailer: Boolean,
    isTrailerPlaying: Boolean,
    isTrailerMuted: Boolean,
    onHeroImageLoaded: () -> Unit,
    onHeroImageLoadFailed: () -> Unit,
    onToggleTrailer: () -> Unit,
    onFocusLossPause: () -> Unit,
    itemId: String? = null,
    sharedElementKey: String? = null,
    softFade: Modifier = Modifier,
) {
    val sharedTransitionScope = LocalSharedTransitionScope.current
    val animatedVisibilityScope = LocalNavAnimatedContentScope.current
    val resolvedKey = sharedElementKey?.takeIf { it.isNotBlank() } ?: itemId
    val backdropKey = resolvedKey?.let { "backdrop-$it" }
    val density = LocalDensity.current
    val horizontalPadding = responsivePageHorizontalPadding()
    // Not named `heroHeight`: the top-level function has that name, and a `val`
    // of the same name is ambiguous inside its own initialiser -- which is a
    // different failure from the local shadowing the function at every use.
    val heroBoxHeight = heroHeight(screenHeightDp)

    val hasTrailer = trailer.isNotEmpty()
    var trailerIsPlaying by remember(trailer) { mutableStateOf(false) }
    var trailerHasRenderedFirstFrame by remember(trailer) { mutableStateOf(false) }
    val isActuallyPlaying = isTrailerPlaying && trailerIsPlaying

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(heroBoxHeight)
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
                            .fillMaxWidth(0.4f)
                            .height(48.dp)
                            .skeletonElement(shape = RoundedCornerShape(8.dp), color = DetailsSkeletonColors.Elevated, pulse = false)
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.64f)
                            .height(38.dp)
                            .skeletonElement(color = DetailsSkeletonColors.Elevated, pulse = false)
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colorStops = arrayOf(
                                    0.0f to Color.Transparent,
                                    0.5f to Color.Transparent,
                                    0.7f to palette.pageBackground.copy(alpha = 0.2f),
                                    0.85f to palette.pageBackground.copy(alpha = 0.6f),
                                    1.0f to palette.pageBackground,
                                )
                            )
                        )
                )
            }
            return@BoxWithConstraints
        }

        if (!imageUrl.isNullOrBlank()) {
            val cardCacheKey = backdropKey?.let { SharedImageMemoryKeys.getCardKey(it) }
            val heroRequest = crispyImageRequest(
                url = imageUrl,
                width = heroMaxWidth,
                height = heroBoxHeight,
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
            heroTrailerLayer(
                HeroTrailerLayerArgs(
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
            val icon = if (isActuallyPlaying) Res.drawable.ic_pause_filled else Res.drawable.ic_play_arrow_filled
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
                        painter = painterResource(icon),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(label, style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        val scrimAlpha = remember { Animatable(0f) }
        LaunchedEffect(Unit) {
            scrimAlpha.animateTo(1f, tween(durationMillis = 200, easing = FastOutSlowInEasing))
        }
        if (scrimAlpha.value > 0.001f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(alpha = scrimAlpha.value)
                    .background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0.0f to Color.Transparent,
                                0.5f to Color.Transparent,
                                0.7f to palette.pageBackground.copy(alpha = 0.2f),
                                0.85f to palette.pageBackground.copy(alpha = 0.6f),
                                1.0f to palette.pageBackground,
                            )
                        )
                    )
            )
        }

        Column(
            modifier = softFade
                .align(Alignment.BottomCenter)
                .padding(horizontal = horizontalPadding)
                .padding(bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val resolvedLogoUrl = details.logoUrl?.trim()?.takeIf { it.isNotEmpty() }
            if (resolvedLogoUrl != null) {
                val logoModel = rememberCrispyImageModel(
                    url = resolvedLogoUrl,
                    width = heroMaxWidth * 0.81f,
                    height = 104.dp,
                )
                var logoLoaded by remember(resolvedLogoUrl) { mutableStateOf(false) }
                val logoAlpha by animateFloatAsState(
                    targetValue = if (logoLoaded) 1f else 0f,
                    animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
                    label = "hero_logo_alpha",
                )
                AsyncImage(
                    model = logoModel ?: resolvedLogoUrl,
                    contentDescription = details.title,
                    modifier = Modifier
                        .widthIn(max = 420.dp)
                        .fillMaxWidth(0.81f)
                        .height(104.dp)
                        .graphicsLayer(alpha = logoAlpha),
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.Center,
                    onSuccess = { logoLoaded = true },
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

/**
 * The hero's height, as a pure function of the window's height in dp.
 *
 * It was `(configuration.screenHeightDp.dp * 0.40f).coerceIn(300.dp, 520.dp)`,
 * written out inside the composable, and `LocalConfiguration` is absent from
 * Compose Multiplatform's common metadata -- it lives in `ui-android`'s
 * `AndroidCompositionLocals_androidKt` -- so no `commonMain` composable can read it.
 *
 * Naming it is what makes the three regimes testable rather than asserted: below
 * the floor the floor wins, above the ceiling the ceiling wins, and in between it
 * is a linear 40%. A single golden could only ever have pinned one of them.
 */
internal fun heroHeight(screenHeightDp: Int): Dp =
    (screenHeightDp.dp * 0.40f).coerceIn(300.dp, 520.dp)

/**
 * The nine values the Android trailer layer needs, as one value.
 *
 * `HeroSection` passes it to its `heroTrailerLayer` slot; the Android file
 * unpacks it straight back into `HeroTrailerLayer`'s parameters, so the
 * implementation's signature is unchanged and this is the only new type.
 *
 * `public`, not `internal`, because `AppNavHostDependencies` carries it as the parameter of
 * its `homeHeroTrailerLayer` slot and that bundle is public.
 */
class HeroTrailerLayerArgs(
    val modifier: Modifier,
    val trailer: List<HeroTrailerSource>,
    val viewportWidthPx: Int,
    val viewportHeightPx: Int,
    val shouldPlay: Boolean,
    val isMuted: Boolean,
    val onFirstFrameRendered: () -> Unit,
    val onPlaybackState: (state: Int, timeSeconds: Double) -> Unit,
    val onFocusLossPause: () -> Unit,
)
