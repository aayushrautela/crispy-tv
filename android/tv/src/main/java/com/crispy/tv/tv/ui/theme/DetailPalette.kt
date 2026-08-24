package com.crispy.tv.tv.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.material3.ColorScheme as M3ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.materialkolor.PaletteStyle
import com.materialkolor.rememberDynamicColorScheme
import com.materialkolor.ktx.themeColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import androidx.tv.material3.darkColorScheme as tvDarkColorScheme

private const val SEED_COLOR_EXTRACTION_TIMEOUT_MS = 1_500L

private val seedCache = LruCache<String, Color>(24)

internal suspend fun computeDetailsSeedColor(bitmap: Bitmap, fallbackSeed: Color): Color? =
    runCatching {
        withTimeoutOrNull(SEED_COLOR_EXTRACTION_TIMEOUT_MS) {
            withContext(Dispatchers.Default) {
                bitmap.asImageBitmap().themeColor(fallback = fallbackSeed, filter = true, maxColors = 128)
            }
        }
    }.getOrNull()

internal suspend fun loadDetailsSeedColor(
    context: Context,
    imageUrl: String,
    fallbackSeed: Color,
): Color? {
    seedCache.get(imageUrl)?.let { return it }
    val computed = runCatching {
        val request = ImageRequest.Builder(context)
            .data(imageUrl)
            .size(128)
            .allowHardware(false)
            .build()
        val result = ImageLoader(context).execute(request) as? SuccessResult
            ?: return@runCatching null
        computeDetailsSeedColor(result.image.toBitmap(), fallbackSeed)
    }.getOrNull()
    if (computed != null) seedCache.put(imageUrl, computed)
    return computed
}

@Composable
fun rememberDetailsSeedColor(imageUrl: String?, fallbackSeed: Color): State<Color?> {
    val context = LocalContext.current
    return produceState(initialValue = null as Color?, imageUrl, fallbackSeed) {
        val url = imageUrl?.takeIf { it.isNotBlank() } ?: return@produceState
        seedCache.get(url)?.let {
            value = it
            return@produceState
        }
        value = loadDetailsSeedColor(context, url, fallbackSeed)
    }
}

@Composable
fun rememberDetailsTvColorScheme(seedColor: Color?): androidx.tv.material3.ColorScheme {
    val m3: M3ColorScheme = rememberDynamicColorScheme(
        seedColor = seedColor ?: Color(0xFFFFC400),
        isDark = true,
        style = PaletteStyle.TonalSpot,
    )
    return tvDarkColorScheme(
        primary = m3.primary,
        onPrimary = m3.onPrimary,
        primaryContainer = m3.primaryContainer,
        onPrimaryContainer = m3.onPrimaryContainer,
        inversePrimary = m3.inversePrimary,
        secondary = m3.secondary,
        onSecondary = m3.onSecondary,
        secondaryContainer = m3.secondaryContainer,
        onSecondaryContainer = m3.onSecondaryContainer,
        tertiary = m3.tertiary,
        onTertiary = m3.onTertiary,
        tertiaryContainer = m3.tertiaryContainer,
        onTertiaryContainer = m3.onTertiaryContainer,
        background = m3.background,
        onBackground = m3.onBackground,
        surface = m3.surface,
        onSurface = m3.onSurface,
        surfaceVariant = m3.surfaceVariant,
        onSurfaceVariant = m3.onSurfaceVariant,
        surfaceTint = m3.surfaceTint,
        inverseSurface = m3.inverseSurface,
        inverseOnSurface = m3.inverseOnSurface,
        error = m3.error,
        onError = m3.onError,
        errorContainer = m3.errorContainer,
        onErrorContainer = m3.onErrorContainer,
        border = m3.outline,
        borderVariant = m3.outlineVariant,
        scrim = m3.scrim,
    )
}
