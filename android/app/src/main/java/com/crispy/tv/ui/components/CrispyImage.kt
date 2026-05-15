package com.crispy.tv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.crispy.tv.images.ResponsiveImageSet
import com.crispy.tv.metadata.tmdb.TmdbApi
import com.crispy.tv.settings.ImageSettingsRepositoryProvider
import java.util.Locale

@Composable
internal fun rememberCrispyImageModel(
    url: String?,
    width: Dp,
    height: Dp,
    tmdbSize: String? = null,
    enableCrossfade: Boolean = false,
    cacheKey: String? = null,
): Any? {
    return rememberCrispyImageModel(
        image = ResponsiveImageSet.fromSingle(url),
        width = width,
        height = height,
        tmdbSize = tmdbSize,
        enableCrossfade = enableCrossfade,
        cacheKey = cacheKey,
    )
}

@Composable
internal fun rememberCrispyImageModel(
    image: ResponsiveImageSet?,
    width: Dp,
    height: Dp,
    tmdbSize: String? = null,
    enableCrossfade: Boolean = false,
    cacheKey: String? = null,
): Any? {
    val context = LocalContext.current
    val imageSettingsRepository = remember(context) {
        ImageSettingsRepositoryProvider.get(context.applicationContext)
    }
    val imageSettings by imageSettingsRepository.settings.collectAsStateWithLifecycle()
    val density = LocalDensity.current
    val widthPx = with(density) { width.roundToPx() }.coerceAtLeast(1)
    val heightPx = with(density) { height.roundToPx() }.coerceAtLeast(1)
    val selectedUrl = image?.urlFor(imageSettings.quality)
    val resolvedUrl = remember(selectedUrl, tmdbSize) {
        when {
            selectedUrl.isNullOrBlank() -> null
            tmdbSize.isNullOrBlank() -> selectedUrl
            else -> TmdbApi.resizedImageUrl(selectedUrl, tmdbSize)
        }
    }
    val resolvedCacheKey = remember(cacheKey, imageSettings.quality) {
        cacheKey?.trim()?.ifBlank { null }?.let { "$it:${imageSettings.quality.key}" }
    }

    return remember(context, resolvedUrl, widthPx, heightPx, enableCrossfade, resolvedCacheKey) {
        resolvedUrl?.let {
            val requestBuilder = ImageRequest.Builder(context)
                .data(it)
                .size(widthPx, heightPx)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .networkCachePolicy(CachePolicy.ENABLED)
                .crossfade(enableCrossfade)
                .diskCacheKey(it)

            if (resolvedCacheKey != null) {
                requestBuilder
                    .memoryCacheKey(resolvedCacheKey)
                    .placeholderMemoryCacheKey(resolvedCacheKey)
            }

            requestBuilder.build()
        }
    }
}

internal fun crispyPosterImageKey(type: String?, id: String?): String? {
    return crispyImageKey("poster", type, id)
}

internal fun crispyBackdropImageKey(type: String?, id: String?): String? {
    return crispyImageKey("backdrop", type, id)
}

internal fun crispyLogoImageKey(type: String?, id: String?): String? {
    return crispyImageKey("logo", type, id)
}

internal fun crispyAvatarImageKey(id: String?): String? {
    val normalizedId = id?.trim()?.lowercase(Locale.US)?.ifBlank { null } ?: return null
    return "avatar:$normalizedId"
}

private fun crispyImageKey(kind: String, type: String?, id: String?): String? {
    val normalizedType = type?.trim()?.lowercase(Locale.US)?.ifBlank { null } ?: return null
    val normalizedId = id?.trim()?.lowercase(Locale.US)?.ifBlank { null } ?: return null
    return "$kind:$normalizedType:$normalizedId"
}
