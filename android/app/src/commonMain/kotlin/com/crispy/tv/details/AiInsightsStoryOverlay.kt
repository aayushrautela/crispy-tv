package com.crispy.tv.details

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.crispy.tv.ai.AiInsightSlide
import com.crispy.tv.ai.AiInsightSlideKey
import com.crispy.tv.ai.AiInsightsResult
import com.crispy.tv.ui.components.CrispyIcon
import com.crispy.tv.ui.resources.Res
import com.crispy.tv.ui.resources.ic_auto_awesome
import com.crispy.tv.ui.resources.ic_check_filled
import com.crispy.tv.ui.resources.ic_close_filled
import com.crispy.tv.ui.resources.ic_playlist_add_filled
import com.crispy.tv.ui.resources.ic_sentiment_very_dissatisfied
import com.crispy.tv.ui.resources.ic_share
import com.crispy.tv.ui.resources.ic_thumb_up
import com.crispy.tv.ui.theme.CrispyPalette
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/** Story presentation order: standout hook first, then good/bad, fun fact last. */
private val SlideDisplayOrder =
    listOf(
        AiInsightSlideKey.STANDOUT_ELEMENT,
        AiInsightSlideKey.THE_GOOD_STUFF,
        AiInsightSlideKey.THE_CATCH,
        AiInsightSlideKey.TRIVIA,
    )

/**
 * The story's wash: one hue per slide, extracted from that slide's own
 * backdrop (128px downscale + dominant-colour scoring, the same pipeline as
 * the details page) and clamped into a narrow luminance band by
 * [clampStoryWash]. The clamp is the whole trick: a raw vivid seed at any
 * visible alpha re-creates the milky-veil faded-text problem, so hue varies
 * per slide while contrast never moves. [StoryWashFallback] (white) covers a
 * missing or still-loading seed, which degrades to the previous design.
 */
private val StoryWashFallback = CrispyPalette.primary

private const val StoryWashAlpha = 0.12f

/**
 * Tames an extracted seed into a wash: keeps the hue, halves the saturation,
 * and pins lightness to [0.38, 0.55]. Pure function of the colour, so it lives
 * in `commonMain` and is covered from `commonTest`.
 */
internal fun clampStoryWash(seed: Color): Color {
    val r = seed.red
    val g = seed.green
    val b = seed.blue
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val lightness = (max + min) / 2f
    val delta = max - min
    val saturation =
        if (delta == 0f) {
            0f
        } else if (lightness < 0.5f) {
            delta / (max + min)
        } else {
            delta / (2f - max - min)
        }
    var hue = 0f
    if (delta != 0f) {
        hue =
            when (max) {
                r -> ((g - b) / delta) % 6f
                g -> (b - r) / delta + 2f
                else -> (r - g) / delta + 4f
            } * 60f
        if (hue < 0f) hue += 360f
    }
    val clampedLightness = lightness.coerceIn(0.38f, 0.55f)
    val clampedSaturation = (saturation * 0.5f).coerceIn(0f, 1f)
    return hslToColor(hue = hue, saturation = clampedSaturation, lightness = clampedLightness)
}

private fun hslToColor(
    hue: Float,
    saturation: Float,
    lightness: Float,
): Color {
    if (saturation == 0f) {
        return Color(red = lightness, green = lightness, blue = lightness)
    }
    val q = if (lightness < 0.5f) lightness * (1f + saturation) else lightness + saturation - lightness * saturation
    val p = 2f * lightness - q
    val h = hue / 360f
    fun channel(t: Float): Float {
        var tt = t
        if (tt < 0f) tt += 1f
        if (tt > 1f) tt -= 1f
        return when {
            tt < 1f / 6f -> p + (q - p) * 6f * tt
            tt < 1f / 2f -> q
            tt < 2f / 3f -> p + (q - p) * (2f / 3f - tt) * 6f
            else -> p
        }
    }
    return Color(red = channel(h + 1f / 3f), green = channel(h), blue = channel(h - 1f / 3f))
}

/**
 * The darkening the type sits in. Neutral, and deliberately not derived from the
 * wash: a light wash and a light falloff would brighten the picture exactly where
 * the white headline has to win.
 */
private val StoryDeepColor = CrispyPalette.background

/** The seat the type sits in: deep enough that white copy wins on bright backdrops. */
private const val StoryRadialAlpha = 0.78f
private const val StoryRadialCenterXRatio = 0.50f
private const val StoryRadialCenterYRatio = 0.68f
private const val StoryRadialRadiusRatio = 0.85f

/**
 * One shadow, in the same colour the falloff uses, so the lift belongs to the
 * picture instead of sitting on top of it. Tight and dark: a wide soft shadow
 * is what makes white type look out of focus and faded.
 */
private val StoryTextShadow =
    Shadow(
        color = StoryDeepColor.copy(alpha = 0.80f),
        offset = Offset(0f, 2f),
        blurRadius = 8f,
    )

/** Decorative only -- there is no handler, and every slide carries the same weight. */
private val StoryStickerSize = 116.dp

/** White, not grey: above the copy it sits over faces and sky, where grey thins out. */
private val StoryStickerTint = Color.White.copy(alpha = 0.95f)

/**
 * The lower third: type sits above the footer, the way story captions do, with
 * the face and sky left open. Centre-placed copy reads as a block of article
 * text; low-placed copy reads as a story. The top reserve only needs to clear
 * the progress bars plus the identity row.
 */
private val StorySafeTopReserve = 132.dp

/** Keeps the copy clear of the footer action row below it. */
private val StorySafeBottomReserve = 48.dp

private val StoryHorizontalPadding = 24.dp
private val StoryVerticalPadding = 12.dp

@Composable
internal fun AiInsightsStoryOverlay(
    result: AiInsightsResult,
    backdropUrls: List<String>,
    onDismiss: () -> Unit,
    artworkUrl: String?,
    palette: DetailsPaletteColors,
    isInWatchlist: Boolean,
    onToggleWatchlist: () -> Unit,
    onShare: () -> Unit,
    slideWashSeeds: Map<Int, Color> = emptyMap(),
) {
    val slides = remember(result) { result.slides.sortedForDisplay() }

    if (slides.isEmpty()) {
        AiInsightsEmptyStory(
            palette = palette,
            isInWatchlist = isInWatchlist,
            onToggleWatchlist = onToggleWatchlist,
            onShare = onShare,
            onDismiss = onDismiss,
        )
        return
    }

    val cyclingBackdrops =
        remember(slides, backdropUrls) {
            backdropUrls.mapNotNull(String::normalizedUrl).distinct()
        }

    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(slides) {
        index = 0
    }

    val safeIndex = index.coerceIn(0, slides.lastIndex)

    fun prev() {
        index = (safeIndex - 1).coerceAtLeast(0)
    }

    fun next() {
        if (safeIndex >= slides.lastIndex) {
            onDismiss()
        } else {
            index = safeIndex + 1
        }
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(StoryDeepColor),
    ) {
        // No transition: a hard cut. Taps move one slide at a time and the
        // direction is ambiguous (a left-third tap goes back), so any
        // directional motion plays the wrong way half the time.
        val slide = slides[safeIndex]
        AiInsightsStorySlide(
            slide = slide,
            imageUrl =
                resolveSlideImageUrl(
                    slide = slide,
                    cyclingBackdropUrl =
                        if (cyclingBackdrops.isEmpty()) {
                            null
                        } else {
                            cyclingBackdrops[safeIndex % cyclingBackdrops.size]
                        },
                    artworkUrl = artworkUrl,
                ),
            washColor = slideWashSeeds[safeIndex]?.let(::clampStoryWash) ?: StoryWashFallback,
            modifier = Modifier.fillMaxSize(),
        )

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = StoryHorizontalPadding)
                    .padding(top = 4.dp, bottom = StoryVerticalPadding),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AiInsightsProgressHeader(
                slideCount = slides.size,
                index = safeIndex,
                onDismiss = onDismiss,
            )

            // Username position: the slide label lives at the top, under the
            // progress bars, the way an Instagram handle does -- not buried
            // in the copy block.
            AiInsightsStoryIdentity(text = slides[safeIndex].label)

            // Chrome sits above the slides, so this is an empty tap target: the
            // whole gesture surface is the region between the header and the footer.
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = true)
                        .pointerInput(safeIndex, slides.size) {
                            detectTapGestures { offset ->
                                if (offset.x < size.width * 0.33f) {
                                    prev()
                                } else {
                                    next()
                                }
                            }
                        },
            )

            AiInsightsFooterActions(
                palette = palette,
                isInWatchlist = isInWatchlist,
                onToggleWatchlist = onToggleWatchlist,
                onShare = onShare,
            )
        }
    }
}

/** One slide: full-bleed image, its own wash over it, type in the lower third. */
@Composable
private fun AiInsightsStorySlide(
    slide: AiInsightSlide,
    imageUrl: String?,
    washColor: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .background(StoryDeepColor),
    ) {
        if (!imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        Box(modifier = Modifier.fillMaxSize().storyTreatment(washColor))
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = StoryHorizontalPadding),
        ) {
            Spacer(modifier = Modifier.height(StorySafeTopReserve))
            // Lower third: the sticker rides above the copy, right side, and
            // the copy column pins to the bottom, so the face and sky stay
            // open. Both flow with each slide's text length instead of sitting
            // on fixed marks.
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = true),
                verticalArrangement = Arrangement.Bottom,
            ) {
                AiInsightsStorySticker(slide = slide)
                Spacer(modifier = Modifier.height(12.dp))
                AiInsightsStoryCopy(slide = slide)
            }
            Spacer(modifier = Modifier.height(StorySafeBottomReserve))
        }
    }
}

/**
 * The per-slide wash, then the dark seat the type sits in.
 *
 * The wash hue varies per slide; the falloff is one constant set of numbers
 * for every slide, which is what keeps white copy winning on all of them.
 */
private fun Modifier.storyTreatment(washColor: Color): Modifier =
    drawBehind {
        drawRect(color = washColor.copy(alpha = StoryWashAlpha))
        drawRect(
            brush =
                Brush.radialGradient(
                    colorStops =
                        arrayOf(
                            0f to StoryDeepColor.copy(alpha = StoryRadialAlpha),
                            0.45f to StoryDeepColor.copy(alpha = StoryRadialAlpha * 0.55f),
                            0.75f to StoryDeepColor.copy(alpha = StoryRadialAlpha * 0.18f),
                            1f to Color.Transparent,
                        ),
                    center =
                        Offset(
                            x = size.width * StoryRadialCenterXRatio,
                            y = size.height * StoryRadialCenterYRatio,
                        ),
                    radius = maxOf(size.width, size.height) * StoryRadialRadiusRatio,
                ),
        )
    }

@Composable
private fun AiInsightsStoryCopy(
    slide: AiInsightSlide,
    modifier: Modifier = Modifier,
) {
    val headline = slide.storyHeadline()
    val body = slide.storyBody()
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (body == null) {
            // No focus: the headline IS the body text, so print it at body
            // size instead of leaving one small line in the middle of the frame.
            headline?.let {
                Text(
                    text = it,
                    style =
                        MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Bold,
                            shadow = StoryTextShadow,
                            lineHeight = 34.sp,
                        ),
                    color = Color.White,
                    maxLines = 20,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            headline?.let {
                Text(
                    text = it,
                    style =
                        MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            shadow = StoryTextShadow,
                            lineHeight = 22.sp,
                        ),
                    color = Color.White.copy(alpha = 0.90f),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = body,
                style =
                    MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Bold,
                        shadow = StoryTextShadow,
                        lineHeight = 34.sp,
                    ),
                color = Color.White,
                // Generous cap, not a design limit: the copy should spend the
                // empty middle of the frame before it ever truncates.
                maxLines = 20,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The watermark glyph: right side, above the copy, fully on-screen, swaying
 * like a metronome. ±8° at ~2s reads as barely alive rather than distracting;
 * the pivot hangs from the top edge so it swings instead of spinning in place.
 * `graphicsLayer` keeps it a draw transform, so there is no recomposition cost.
 */
@Composable
private fun AiInsightsStorySticker(
    slide: AiInsightSlide,
    modifier: Modifier = Modifier,
) {
    val sway = rememberInfiniteTransition(label = "story sticker sway")
    val angle by sway.animateFloat(
        initialValue = -8f,
        targetValue = 8f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = 2000, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "sway angle",
    )
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Icon(
            painter = painterResource(slide.storySticker()),
            contentDescription = null,
            tint = StoryStickerTint,
            modifier =
                Modifier
                    .size(StoryStickerSize)
                    .graphicsLayer {
                        rotationZ = angle
                        transformOrigin = TransformOrigin(0.5f, 0f)
                    },
        )
    }
}

@Composable
private fun AiInsightsStoryIdentity(text: String) {
    val label = text.trim()
    if (label.isEmpty()) return
    Text(
        text = label.uppercase(),
        style =
            MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.Bold,
                shadow = StoryTextShadow,
            ),
        color = Color.White,
        letterSpacing = 1.2.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * The hook: the server's `focus` when it sent one, otherwise the body is the
 * hook itself, which is what keeps the big type populated on every slide.
 */
internal fun AiInsightSlide.storyHeadline(): String? =
    focus?.trim()?.takeIf { it.isNotEmpty() }
        ?: (body ?: context)?.trim()?.takeIf { it.isNotEmpty() }

/** Printed only when [storyHeadline] already spent `focus`, so nothing shows twice. */
internal fun AiInsightSlide.storyBody(): String? =
    if (focus.isNullOrBlank()) {
        null
    } else {
        (body ?: context)?.trim()?.takeIf { it.isNotEmpty() }
    }

private fun AiInsightSlide.storySticker(): DrawableResource =
    when (key) {
        AiInsightSlideKey.THE_GOOD_STUFF -> Res.drawable.ic_thumb_up
        AiInsightSlideKey.THE_CATCH -> Res.drawable.ic_sentiment_very_dissatisfied
        AiInsightSlideKey.STANDOUT_ELEMENT,
        AiInsightSlideKey.TRIVIA,
        AiInsightSlideKey.UNKNOWN -> Res.drawable.ic_auto_awesome
    }

@Composable
private fun AiInsightsEmptyStory(
    palette: DetailsPaletteColors,
    isInWatchlist: Boolean,
    onToggleWatchlist: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(palette.pageBackground),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = StoryHorizontalPadding, vertical = StoryVerticalPadding),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AiInsightsProgressHeader(
                slideCount = 1,
                index = 0,
                onDismiss = onDismiss,
            )
            Box(
                modifier = Modifier.weight(1f, fill = true),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "AI insights unavailable",
                    style = MaterialTheme.typography.headlineSmall,
                    color = palette.onPageBackground,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            AiInsightsFooterActions(
                palette = palette,
                isInWatchlist = isInWatchlist,
                onToggleWatchlist = onToggleWatchlist,
                onShare = onShare,
            )
        }
    }
}

@Composable
private fun AiInsightsProgressHeader(
    slideCount: Int,
    index: Int,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Fixed white, off the dynamic scheme: the artwork-seeded pastel
            // accent is light by design and dissolves over bright photos.
            repeat(slideCount.coerceAtLeast(1)) { i ->
                val fillColor =
                    if (i <= index) {
                        Color.White
                    } else {
                        Color.White.copy(alpha = 0.45f)
                    }
                Box(
                    modifier =
                        Modifier
                            .height(4.dp)
                            .weight(1f)
                            .clip(CircleShape)
                            .background(fillColor),
                )
            }
        }
        IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
            Icon(
                painter = painterResource(Res.drawable.ic_close_filled),
                contentDescription = "Close",
                tint = Color.White,
            )
        }
    }
}

@Composable
private fun AiInsightsFooterActions(
    palette: DetailsPaletteColors,
    isInWatchlist: Boolean,
    onToggleWatchlist: () -> Unit,
    onShare: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AiInsightsPillButton(
                text = if (isInWatchlist) "In watchlist" else "Add to watchlist",
                icon = if (isInWatchlist) Res.drawable.ic_check_filled else Res.drawable.ic_playlist_add_filled,
                onClick = onToggleWatchlist,
                modifier = Modifier.weight(1f),
            )
            AiInsightsPillButton(
                text = "Share",
                icon = Res.drawable.ic_share,
                onClick = onShare,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            text = "Generative AI is experimental",
            style = MaterialTheme.typography.bodySmall,
            color = palette.onPageBackground.copy(alpha = 0.75f),
        )
    }
}

@Composable
private fun AiInsightsPillButton(
    text: String,
    icon: DrawableResource,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Solid white pills, not the dynamic pastel accent: `palette.accent` is a
    // TonalSpot primary, which is light *by design* in a dark scheme, so pills
    // built from it read as washed out. White + near-black is the story-CTA
    // look and wins on every backdrop.
    Button(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        shape = RoundedCornerShape(999.dp),
        colors =
            ButtonDefaults.buttonColors(
                containerColor = CrispyPalette.primary,
                contentColor = CrispyPalette.onPrimary,
            ),
    ) {
        CrispyIcon(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Reorders server slides into story order while keeping any unknown keys at the end. */
internal fun List<AiInsightSlide>.sortedForDisplay(): List<AiInsightSlide> =
    sortedBy { slide -> SlideDisplayOrder.indexOf(slide.key).takeIf { it >= 0 } ?: Int.MAX_VALUE }

internal fun resolveSlideImageUrl(
    slide: AiInsightSlide,
    cyclingBackdropUrl: String?,
    artworkUrl: String?,
): String? {
    return (slide.backdrop.high ?: slide.backdrop.medium ?: slide.backdrop.low)?.normalizedUrl()
        ?: cyclingBackdropUrl.normalizedUrl()
        ?: artworkUrl.normalizedUrl()
}

private fun String?.normalizedUrl(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
