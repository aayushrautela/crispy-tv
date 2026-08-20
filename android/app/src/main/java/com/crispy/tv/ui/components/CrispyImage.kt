package com.crispy.tv.ui.components

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
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
): Any? {
    if (url.isNullOrBlank()) return null
    val context = LocalContext.current
    val appContext = context.applicationContext
    val density = LocalDensity.current
    val widthPx = with(density) { width.roundToPx() }.coerceAtLeast(1)
    val heightPx = with(density) { height.roundToPx() }.coerceAtLeast(1)
    return rememberCrispyImageModel(appContext, url, widthPx, heightPx, enableCrossfade, memoryCacheKey, transformations)
}

private fun buildCrispyImageRequest(
    context: android.content.Context,
    url: String,
    widthPx: Int,
    heightPx: Int,
    enableCrossfade: Boolean = false,
    memoryCacheKey: String? = null,
    transformations: List<Transformation> = emptyList(),
): ImageRequest {
    return ImageRequest.Builder(context)
        .data(url)
        .apply { if (widthPx > 0 && heightPx > 0) size(widthPx, heightPx) }
        .apply { if (enableCrossfade) crossfade(true) }
        .diskCacheKey(url)
        .apply {
            if (memoryCacheKey != null) {
                memoryCacheKey(memoryCacheKey)
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
): ImageRequest {
    return androidx.compose.runtime.remember(url, widthPx, heightPx, enableCrossfade, memoryCacheKey, transformations) {
        buildCrispyImageRequest(appContext, url, widthPx, heightPx, enableCrossfade, memoryCacheKey, transformations)
    }
}

fun preloadCrispyImage(
    context: android.content.Context,
    url: String?,
    width: Dp = 0.dp,
    height: Dp = 0.dp,
    memoryCacheKey: String? = null,
) {
    if (url.isNullOrBlank()) return
    val appContext = context.applicationContext
    val density = appContext.resources.displayMetrics.density
    val widthPx = if (width > 0.dp) (width.value * density).toInt().coerceAtLeast(1) else 0
    val heightPx = if (height > 0.dp) (height.value * density).toInt().coerceAtLeast(1) else 0
    val request = buildCrispyImageRequest(
        context = appContext,
        url = url,
        widthPx = widthPx,
        heightPx = heightPx,
        memoryCacheKey = memoryCacheKey,
    )
    SingletonImageLoader.get(appContext).enqueue(request)
}

@Composable
fun rememberCrispyImageModel(
    url: String?,
    width: Dp,
    height: Dp,
    enableCrossfade: Boolean = false,
    memoryCacheKey: String? = null,
    transformations: List<Transformation> = emptyList(),
): Any? = crispyImageRequest(
    url = url,
    width = width,
    height = height,
    enableCrossfade = enableCrossfade,
    memoryCacheKey = memoryCacheKey,
    transformations = transformations,
)

@Composable
fun rememberCrispyImageModel(
    image: ResponsiveImageSet?,
    width: Dp,
    height: Dp,
    enableCrossfade: Boolean = false,
    memoryCacheKey: String? = null,
    transformations: List<Transformation> = emptyList(),
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
    )
}
