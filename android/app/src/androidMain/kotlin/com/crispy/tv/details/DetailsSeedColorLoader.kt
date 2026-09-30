package com.crispy.tv.details

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap

/**
 * The platform half of the details seed-colour pipeline.
 *
 * Loading an image needs an image loader, and an image loader needs a platform
 * context: `coil3.imageLoader` is an extension on `Context` on Android, so there
 * is nothing to call from `commonMain`. That, and nothing else, is why these two
 * composables stayed behind when the palette, the cache and the extraction
 * itself moved — the extraction now takes Compose's `ImageBitmap`, which every
 * target shares, and `asImageBitmap` is the bridge from whichever bitmap
 * `coil3.toBitmap` returned on this platform.
 */
internal suspend fun loadDetailsSeedColor(
    context: Context,
    imageUrl: String,
    fallbackSeed: Color,
): Color? {
    val imageLoader = context.imageLoader
    val computedSeed =
        runCatching {
            val request =
                ImageRequest.Builder(context)
                    .data(imageUrl)
                    // Keep this small: quantization + scoring is proportional to pixel count.
                    .size(128)
                    .allowHardware(false)
                    .build()

            val result = imageLoader.execute(request)
            val image = (result as? SuccessResult)?.image ?: return@runCatching null
            computeDetailsSeedColor(
                image = image.toBitmap().asImageBitmap(),
                fallbackSeed = fallbackSeed,
            )
        }.getOrNull()

    if (computedSeed != null) {
        cacheDetailsSeedColor(imageUrl, computedSeed)
    }
    return computedSeed
}

@Composable
internal fun rememberSeedColor(
    imageUrl: String?,
    fallbackSeed: Color,
): State<Color?> {
    val context = LocalContext.current
    return produceState<Color?>(
        initialValue = null,
        keys = arrayOf(imageUrl, fallbackSeed),
    ) {
        val url = imageUrl?.trim()?.takeIf { it.isNotEmpty() }
        if (url == null) {
            value = fallbackSeed
            return@produceState
        }
        val cached = cachedDetailsSeedColor(url)
        if (cached != null) {
            value = cached
            return@produceState
        }
        val loaded = loadDetailsSeedColor(
            context = context,
            imageUrl = url,
            fallbackSeed = fallbackSeed,
        )
        value = loaded ?: fallbackSeed
    }
}