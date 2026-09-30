package com.crispy.tv.details

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.ktx.themeColor
import com.materialkolor.rememberDynamicColorScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val SEED_COLOR_CACHE_MAX_ENTRIES = 96
private const val SEED_COLOR_EXTRACTION_TIMEOUT_MS = 500L

/**
 * The palette a details surface is tinted from, derived once from its [ColorScheme].
 *
 * It lives in `commonMain` because every consumer needs it, and because none of
 * what it holds is Android-specific. Its provenance is worth recording: the file
 * it came from sat in `androidMain` because of three things beside it, none of
 * which is this type. `com.materialkolor` is a genuine Kotlin Multiplatform
 * artifact (it publishes `android`, `iosArm64`, `iosSimulatorArm64`, `jvm`,
 * `macosArm64`, `js` and `wasmJs`), so `rememberDynamicColorScheme` and
 * `themeColor` were never blockers. Coil's `toBitmap` *is* `expect`/`actual` and
 * returns a different bitmap type per platform, which is why [computeDetailsSeedColor]
 * takes Compose's `ImageBitmap` rather than any platform bitmap. And the two
 * loader composables are in `androidMain` because they need a `Context` to reach
 * an image loader at all.
 */
@Stable
internal data class DetailsPaletteColors(
    val pageBackground: Color,
    val onPageBackground: Color,
    val accent: Color,
    val onAccent: Color,
    val pillBackground: Color,
    val pillBackgroundSolid: Color,
    val onPillBackground: Color,
)

@Stable
internal fun detailsPaletteFromScheme(scheme: ColorScheme): DetailsPaletteColors =
    DetailsPaletteColors(
        pageBackground = scheme.background,
        onPageBackground = scheme.onBackground,
        accent = scheme.primary,
        onAccent = scheme.onPrimary,
        pillBackground = scheme.surfaceContainerHigh.copy(alpha = 0.55f),
        pillBackgroundSolid = scheme.surfaceContainerHigh,
        onPillBackground = scheme.onSurface,
    )

/**
 * Remembers extracted seed colors for the rest of the process.
 *
 * The original was a `@Synchronized` `LinkedHashMap` in access order, capped at
 * [SEED_COLOR_CACHE_MAX_ENTRIES]. Its entire job is making single reads and
 * single writes safe from the several screens that resolve a seed colour
 * concurrently; there is no compound operation anywhere in it, so a
 * `Mutex`-guarded map carries the same guarantee. An LRU needs the eviction
 * bookkeeping kept explicitly, since a plain map cannot be asked to drop its
 * eldest entry.
 */
private object DetailsSeedColorCache {
    private val mutex = Mutex()
    private val cache = mutableMapOf<String, ULong>()

    suspend fun get(imageUrl: String): Color? {
        val value = mutex.withLock { cache[imageUrl] } ?: return null
        return Color(value)
    }

    suspend fun put(imageUrl: String, seedColor: Color) {
        mutex.withLock {
            // Re-inserting a hit moves it to the end, which is what access-order
            // eviction used to do for us.
            if (cache.containsKey(imageUrl)) cache.remove(imageUrl)
            cache[imageUrl] = seedColor.value
            while (cache.size > SEED_COLOR_CACHE_MAX_ENTRIES) {
                val eldest = cache.keys.firstOrNull() ?: break
                cache.remove(eldest)
            }
        }
    }
}

internal suspend fun cachedDetailsSeedColor(imageUrl: String?): Color? {
    if (imageUrl.isNullOrBlank()) return null
    return DetailsSeedColorCache.get(imageUrl)
}

internal suspend fun cacheDetailsSeedColor(imageUrl: String, seedColor: Color) {
    DetailsSeedColorCache.put(imageUrl, seedColor)
}

/**
 * Extracts a seed colour from a decoded image.
 *
 * The parameter is Compose's `ImageBitmap` rather than a platform bitmap because
 * there is no portable bitmap: Coil's `toBitmap` is `expect`/`actual` and yields
 * `android.graphics.Bitmap` on Android but `org.jetbrains.skia.Bitmap` elsewhere,
 * so a `commonMain` signature cannot name its result. The caller converts with
 * whichever `toBitmap` its platform provides; `asImageBitmap` then bridges into
 * the one type every target shares.
 *
 * The timeout and the `runCatching` are deliberate: this runs during
 * composition on a screen that can already be drawn, so a slow or undecodable
 * image must cost nothing but the fallback.
 */
internal suspend fun computeDetailsSeedColor(
    image: ImageBitmap,
    fallbackSeed: Color,
): Color? =
    runCatching {
        withTimeoutOrNull(SEED_COLOR_EXTRACTION_TIMEOUT_MS) {
            withContext(Dispatchers.Default) {
                image.themeColor(fallback = fallbackSeed, filter = true, maxColors = 128)
            }
        }
    }.getOrNull()

@Composable
internal fun rememberDetailsColorScheme(seedColor: Color): ColorScheme =
    rememberDynamicColorScheme(
        seedColor = seedColor,
        isDark = true,
        specVersion = ColorSpec.SpecVersion.SPEC_2025,
        style = PaletteStyle.TonalSpot,
    )