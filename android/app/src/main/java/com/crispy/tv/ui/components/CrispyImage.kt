package com.crispy.tv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.crispy.tv.images.ResponsiveImageSet

@Composable
fun crispyImageRequest(
    url: String?,
    width: Dp,
    height: Dp,
    enableCrossfade: Boolean = true,
    memoryCacheKey: String? = null,
): Any? {
    if (url.isNullOrBlank()) return null
    val context = LocalContext.current
    val appContext = context.applicationContext
    val density = LocalDensity.current
    val widthPx = with(density) { width.roundToPx() }.coerceAtLeast(1)
    val heightPx = with(density) { height.roundToPx() }.coerceAtLeast(1)
    return rememberCrispyImageModel(appContext, url, widthPx, heightPx, enableCrossfade, memoryCacheKey)
}

@Composable
private fun rememberCrispyImageModel(
    appContext: android.content.Context,
    url: String,
    widthPx: Int,
    heightPx: Int,
    enableCrossfade: Boolean = true,
    memoryCacheKey: String? = null,
): ImageRequest {
    return androidx.compose.runtime.remember(url, widthPx, heightPx, enableCrossfade, memoryCacheKey) {
        ImageRequest.Builder(appContext)
            .data(url)
            .size(widthPx, heightPx)
            .apply { if (enableCrossfade) crossfade(true) }
            .diskCacheKey(url)
            .apply {
                if (memoryCacheKey != null) {
                    memoryCacheKey(memoryCacheKey)
                    placeholderMemoryCacheKey(memoryCacheKey)
                }
            }
            .build()
    }
}

@Composable
fun rememberCrispyImageModel(
    url: String?,
    width: Dp,
    height: Dp,
    enableCrossfade: Boolean = true,
    memoryCacheKey: String? = null,
): Any? = crispyImageRequest(url = url, width = width, height = height, enableCrossfade = enableCrossfade, memoryCacheKey = memoryCacheKey)

@Composable
fun rememberCrispyImageModel(
    image: ResponsiveImageSet?,
    width: Dp,
    height: Dp,
    enableCrossfade: Boolean = true,
    memoryCacheKey: String? = null,
): Any? {
    if (image == null || image.isEmpty) return null
    val url = image.medium ?: image.high ?: image.low
    return crispyImageRequest(url = url, width = width, height = height, enableCrossfade = enableCrossfade, memoryCacheKey = memoryCacheKey)
}
