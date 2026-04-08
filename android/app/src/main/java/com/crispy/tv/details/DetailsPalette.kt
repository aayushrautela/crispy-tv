package com.crispy.tv.details

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.ktx.themeColor
import com.materialkolor.rememberDynamicColorScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val SEED_COLOR_CACHE_MAX_ENTRIES = 96
private const val SEED_COLOR_EXTRACTION_TIMEOUT_MS = 150L

private object DetailsSeedColorCache {
    private val cache =
        object : LinkedHashMap<String, ULong>(SEED_COLOR_CACHE_MAX_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ULong>): Boolean {
                return size > SEED_COLOR_CACHE_MAX_ENTRIES
            }
        }

    @Synchronized
    fun get(imageUrl: String): Color? {
        val value = cache[imageUrl] ?: return null
        return Color(value)
    }

    @Synchronized
    fun put(imageUrl: String, seedColor: Color) {
        cache[imageUrl] = seedColor.value
    }
}

@Stable
internal data class DetailsPaletteColors(
    val pageBackground: Color,
    val onPageBackground: Color,
    val accent: Color,
    val onAccent: Color,
    val pillBackground: Color,
    val onPillBackground: Color
)

@Stable
internal fun detailsPaletteFromScheme(scheme: ColorScheme): DetailsPaletteColors {
    return DetailsPaletteColors(
        pageBackground = scheme.background,
        onPageBackground = scheme.onBackground,
        accent = scheme.primary,
        onAccent = scheme.onPrimary,
        pillBackground = scheme.surfaceContainerHigh.copy(alpha = 0.72f),
        onPillBackground = scheme.onSurface,
    )
}

internal fun cachedDetailsSeedColor(imageUrl: String?): Color? {
    if (imageUrl.isNullOrBlank()) return null
    return DetailsSeedColorCache.get(imageUrl)
}

internal fun cacheDetailsSeedColor(imageUrl: String, seedColor: Color) {
    DetailsSeedColorCache.put(imageUrl, seedColor)
}

internal suspend fun computeDetailsSeedColor(
    bitmap: Bitmap,
    fallbackSeed: Color,
): Color? {
    return runCatching {
        withTimeoutOrNull(SEED_COLOR_EXTRACTION_TIMEOUT_MS) {
            withContext(Dispatchers.Default) {
                bitmap
                    .asImageBitmap()
                    .themeColor(fallback = fallbackSeed, filter = true, maxColors = 128)
            }
        }
    }.getOrNull()
}

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
            val drawable = (result as? SuccessResult)?.drawable ?: return@runCatching null
            computeDetailsSeedColor(
                bitmap = drawable.toBitmap(),
                fallbackSeed = fallbackSeed,
            )
        }.getOrNull()

    if (computedSeed != null) {
        cacheDetailsSeedColor(imageUrl, computedSeed)
    }
    return computedSeed
}

@Composable
internal fun rememberDetailsColorScheme(seedColor: Color): ColorScheme {
    return rememberDynamicColorScheme(
        seedColor = seedColor,
        isDark = true,
        specVersion = ColorSpec.SpecVersion.SPEC_2025,
        style = PaletteStyle.TonalSpot,
    )
}
