package com.crispy.tv.details

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
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

private const val SEED_COLOR_CACHE_MAX_ENTRIES = 96

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
internal data class DetailsTheming(
    val colorScheme: ColorScheme,
    val seedColor: Color,
    val isSeedColorResolved: Boolean,
)

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

@Composable
internal fun rememberDetailsTheming(imageUrl: String?): DetailsTheming {
    val baseScheme = MaterialTheme.colorScheme
    val fallbackSeed = baseScheme.primary

    val cachedSeed =
        remember(imageUrl) {
            if (imageUrl.isNullOrBlank()) {
                null
            } else {
                DetailsSeedColorCache.get(imageUrl)
            }
        }

    var seedColor by remember(imageUrl, fallbackSeed) { mutableStateOf(cachedSeed ?: fallbackSeed) }
    var isSeedColorResolved by
        remember(imageUrl, fallbackSeed) {
            mutableStateOf(imageUrl.isNullOrBlank() || cachedSeed != null)
        }

    val context = LocalContext.current
    val imageLoader = context.imageLoader

    LaunchedEffect(imageUrl, fallbackSeed) {
        seedColor = cachedSeed ?: fallbackSeed
        isSeedColorResolved = imageUrl.isNullOrBlank() || cachedSeed != null
        if (imageUrl.isNullOrBlank() || cachedSeed != null) return@LaunchedEffect

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
                val bitmap = drawable.toBitmap()

                withContext(Dispatchers.Default) {
                    bitmap
                        .asImageBitmap()
                        .themeColor(fallback = fallbackSeed, filter = true, maxColors = 128)
                }
            }.getOrNull()

        seedColor = computedSeed ?: fallbackSeed
        if (computedSeed != null) {
            DetailsSeedColorCache.put(imageUrl, computedSeed)
        }

        isSeedColorResolved = true
    }

    val detailsScheme =
        rememberDynamicColorScheme(
            seedColor = seedColor,
            isDark = true,
            specVersion = ColorSpec.SpecVersion.SPEC_2025,
            style = PaletteStyle.TonalSpot,
        )

    return DetailsTheming(
        colorScheme = detailsScheme,
        seedColor = seedColor,
        isSeedColorResolved = isSeedColorResolved,
    )
}
