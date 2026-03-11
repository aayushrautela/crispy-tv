package com.crispy.tv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import coil.request.ImageRequest
import com.crispy.tv.metadata.tmdb.TmdbApi

@Composable
internal fun rememberCrispyImageModel(
    url: String?,
    width: Dp,
    height: Dp,
    tmdbSize: String? = null,
): Any? {
    val context = LocalContext.current
    val density = LocalDensity.current
    val widthPx = with(density) { width.roundToPx() }.coerceAtLeast(1)
    val heightPx = with(density) { height.roundToPx() }.coerceAtLeast(1)
    val resolvedUrl = remember(url, tmdbSize) {
        when {
            url.isNullOrBlank() -> null
            tmdbSize.isNullOrBlank() -> url
            else -> TmdbApi.resizedImageUrl(url, tmdbSize)
        }
    }

    return remember(context, resolvedUrl, widthPx, heightPx) {
        resolvedUrl?.let {
            ImageRequest.Builder(context)
                .data(it)
                .size(widthPx, heightPx)
                .crossfade(true)
                .build()
        }
    }
}
