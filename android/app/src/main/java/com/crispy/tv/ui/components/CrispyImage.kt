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
): Any? {
    if (url.isNullOrBlank()) return null
    val context = LocalContext.current
    val density = LocalDensity.current
    val widthPx = with(density) { width.roundToPx() }.coerceAtLeast(1)
    val heightPx = with(density) { height.roundToPx() }.coerceAtLeast(1)
    return rememberCrispyImageModel(url, widthPx, heightPx)
}

@Composable
private fun rememberCrispyImageModel(
    url: String,
    widthPx: Int,
    heightPx: Int,
): ImageRequest {
    val context = LocalContext.current
    return androidx.compose.runtime.remember(context, url, widthPx, heightPx) {
        ImageRequest.Builder(context)
            .data(url)
            .size(widthPx, heightPx)
            .crossfade(true)
            .diskCacheKey(url)
            .build()
    }
}

@Composable
fun rememberCrispyImageModel(
    url: String?,
    width: Dp,
    height: Dp,
): Any? = crispyImageRequest(url = url, width = width, height = height)

@Composable
fun rememberCrispyImageModel(
    image: ResponsiveImageSet?,
    width: Dp,
    height: Dp,
): Any? {
    if (image == null || image.isEmpty) return null
    val url = image.medium ?: image.high ?: image.low
    return crispyImageRequest(url = url, width = width, height = height)
}
