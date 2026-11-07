package com.crispy.tv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.PlatformContext
import coil3.compose.LocalPlatformContext
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.transformations
import coil3.transform.Transformation
import com.crispy.tv.images.ImageQuality
import com.crispy.tv.images.ResponsiveImageSet
import com.crispy.tv.images.urlForDisplaySize

/**
 * The ceiling the app applies to every image it fetches.
 *
 * There is no image-quality setting any more: the app picks the rendition
 * itself. [urlForDisplaySize] fetches the smallest rendition wide enough to
 * cover the surface and never one above this ceiling, so a full-width hero
 * resolves `medium` while a small card resolves `low`. A surface that can
 * display even less passes its own lower `surfaceCap` — the random wheel's
 * 72.dp disc passes [ImageQuality.LOW].
 */
private val AppImageCap = ImageQuality.MEDIUM

/**
 * Builds the [ImageRequest] every Crispy image goes through, so that a card, a
 * poster, a still and a hero all size and key their loads the same way.
 *
 * The context comes from `coil3.compose.LocalPlatformContext`, not from
 * `androidx.compose.ui.platform.LocalContext`. That is the whole reason this
 * file can live in `commonMain`: `LocalContext` is an Android-only composition
 * local even under Compose Multiplatform (it lives in
 * `AndroidCompositionLocals_androidKt`), whereas `LocalPlatformContext` is
 * declared `expect` in Coil's own `commonMain` and is `LocalContext` on
 * Android. Coil's own `AsyncImage` reads it for the same reason.
 *
 * `coil3.PlatformContext` is the matching type, and it is an `expect abstract
 * class` in Coil's `commonMain` whose Android `actual` is a typealias for
 * `android.content.Context`. So no expect/actual seam of our own is needed and
 * the Android call site is unchanged in behaviour.
 *
 * Note this passes the *composition's* context rather than
 * `context.applicationContext`. Coil normalises it itself: `SingletonImageLoader`
 * calls `context.applicationContext()` before handing anything to an
 * `ImageLoader.Factory`, and `CrispyApplication` is that factory. Coil's only
 * other use of `ImageRequest.context` is an identity check in
 * `RealInterceptorChain` and the density lookup in `SvgDecoder`, neither of
 * which is sensitive to Activity-versus-Application.
 */
@Composable
fun crispyImageRequest(
    url: String?,
    width: Dp,
    height: Dp,
    enableCrossfade: Boolean = false,
    memoryCacheKey: String? = null,
    transformations: List<Transformation> = emptyList(),
    placeholderMemoryCacheKey: MemoryCache.Key? = null,
): Any? {
    if (url.isNullOrBlank()) return null
    val context = LocalPlatformContext.current
    val density = LocalDensity.current
    val widthPx = with(density) { width.roundToPx() }.coerceAtLeast(1)
    val heightPx = with(density) { height.roundToPx() }.coerceAtLeast(1)
    return rememberCrispyImageModel(
        context,
        url,
        widthPx,
        heightPx,
        enableCrossfade,
        memoryCacheKey,
        transformations,
        placeholderMemoryCacheKey,
    )
}

private fun buildCrispyImageRequest(
    context: PlatformContext,
    url: String,
    widthPx: Int,
    heightPx: Int,
    enableCrossfade: Boolean = false,
    memoryCacheKey: String? = null,
    transformations: List<Transformation> = emptyList(),
    placeholderMemoryCacheKey: MemoryCache.Key? = null,
): ImageRequest {
    // The memory-cache key carries the URL and the target size, so two surfaces
    // showing the same item at different sizes or from different renditions
    // (rail card `low` vs Top10 poster `medium`) get separate entries instead of
    // one shared key under which Coil's size-only validity check can serve the
    // wrong tier's bitmap.
    val effectiveMemoryCacheKey = "${memoryCacheKey ?: url}@$url@${widthPx}x${heightPx}"
    return ImageRequest.Builder(context)
        .data(url)
        .apply { if (widthPx > 0 && heightPx > 0) size(widthPx, heightPx) }
        .apply { if (enableCrossfade) crossfade(true) }
        .diskCacheKey(url)
        .memoryCacheKey(effectiveMemoryCacheKey)
        .apply {
            if (placeholderMemoryCacheKey != null) {
                placeholderMemoryCacheKey(placeholderMemoryCacheKey)
            } else {
                placeholderMemoryCacheKey(effectiveMemoryCacheKey)
            }
        }
        .transformations(transformations)
        .build()
}

@Composable
private fun rememberCrispyImageModel(
    appContext: PlatformContext,
    url: String,
    widthPx: Int,
    heightPx: Int,
    enableCrossfade: Boolean = false,
    memoryCacheKey: String? = null,
    transformations: List<Transformation> = emptyList(),
    placeholderMemoryCacheKey: MemoryCache.Key? = null,
): ImageRequest {
    return androidx.compose.runtime.remember(
        url,
        widthPx,
        heightPx,
        enableCrossfade,
        memoryCacheKey,
        transformations,
        placeholderMemoryCacheKey,
    ) {
        buildCrispyImageRequest(
            appContext,
            url,
            widthPx,
            heightPx,
            enableCrossfade,
            memoryCacheKey,
            transformations,
            placeholderMemoryCacheKey,
        )
    }
}

@Composable
fun rememberCrispyImageModel(
    url: String?,
    width: Dp,
    height: Dp,
    enableCrossfade: Boolean = false,
    memoryCacheKey: String? = null,
    transformations: List<Transformation> = emptyList(),
    placeholderMemoryCacheKey: MemoryCache.Key? = null,
): Any? = crispyImageRequest(
    url = url,
    width = width,
    height = height,
    enableCrossfade = enableCrossfade,
    memoryCacheKey = memoryCacheKey,
    transformations = transformations,
    placeholderMemoryCacheKey = placeholderMemoryCacheKey,
)

@Composable
fun rememberCrispyImageModel(
    image: ResponsiveImageSet?,
    width: Dp,
    height: Dp,
    enableCrossfade: Boolean = false,
    memoryCacheKey: String? = null,
    transformations: List<Transformation> = emptyList(),
    placeholderMemoryCacheKey: MemoryCache.Key? = null,
    surfaceCap: ImageQuality = ImageQuality.HIGH,
    fallbackUrl: String? = null,
): Any? {
    val density = LocalDensity.current
    val widthPx = with(density) { width.roundToPx() }.coerceAtLeast(1)
    val heightPx = with(density) { height.roundToPx() }.coerceAtLeast(1)
    // The app's ceiling and the surface's own one both cap the rendition, so a
    // 72.dp disc never fetches more than it can display.
    val cap = minOf(surfaceCap, AppImageCap)
    val url = image
        ?.takeIf { !it.isEmpty }
        ?.urlForDisplaySize(maxOf(widthPx, heightPx), cap)
        ?: fallbackUrl?.trim()?.ifBlank { null }
    if (url == null) return null
    return crispyImageRequest(
        url = url,
        width = width,
        height = height,
        enableCrossfade = enableCrossfade,
        memoryCacheKey = memoryCacheKey,
        transformations = transformations,
        placeholderMemoryCacheKey = placeholderMemoryCacheKey,
    )
}

object SharedImageMemoryKeys {
    private val cardKeys = mutableMapOf<String, MemoryCache.Key>()

    fun putCardKey(sharedKey: String, cacheKey: MemoryCache.Key) {
        cardKeys[sharedKey] = cacheKey
    }

    fun getCardKey(sharedKey: String): MemoryCache.Key? = cardKeys[sharedKey]
}
