package com.crispy.tv.ui.components

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.transformations
import coil3.transform.Transformation
import com.crispy.tv.images.ResponsiveImageSet

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
    val context = LocalContext.current
    val appContext = context.applicationContext
    val density = LocalDensity.current
    val widthPx = with(density) { width.roundToPx() }.coerceAtLeast(1)
    val heightPx = with(density) { height.roundToPx() }.coerceAtLeast(1)
    return rememberCrispyImageModel(
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

private fun buildCrispyImageRequest(
    context: android.content.Context,
    url: String,
    widthPx: Int,
    heightPx: Int,
    enableCrossfade: Boolean = false,
    memoryCacheKey: String? = null,
    transformations: List<Transformation> = emptyList(),
    placeholderMemoryCacheKey: MemoryCache.Key? = null,
): ImageRequest {
    return ImageRequest.Builder(context)
        .data(url)
        .apply { if (widthPx > 0 && heightPx > 0) size(widthPx, heightPx) }
        .apply { if (enableCrossfade) crossfade(true) }
        .diskCacheKey(url)
        .apply {
            if (memoryCacheKey != null) memoryCacheKey(memoryCacheKey)
            if (placeholderMemoryCacheKey != null) {
                placeholderMemoryCacheKey(placeholderMemoryCacheKey)
            } else if (memoryCacheKey != null) {
                placeholderMemoryCacheKey(memoryCacheKey)
            }
        }
        .transformations(transformations)
        .build()
}

@Composable
private fun rememberCrispyImageModel(
    appContext: android.content.Context,
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
): Any? {
    if (image == null || image.isEmpty) return null
    val url = image.medium ?: image.high ?: image.low
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
