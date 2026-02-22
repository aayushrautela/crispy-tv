package com.crispy.rewrite.details

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Stable
internal data class DetailsPaletteColors(
    val pageBackground: Color,
    val onPageBackground: Color,
    val accent: Color,
    val onAccent: Color,
    val pillBackground: Color,
    val onPillBackground: Color
)

@Composable
internal fun rememberDetailsPaletteColors(imageUrl: String?): DetailsPaletteColors {
    val scheme = androidx.compose.material3.MaterialTheme.colorScheme
    val fallbackPage = scheme.background
    val fallbackAccent = scheme.primary

    var colors by remember(fallbackPage, fallbackAccent) {
        mutableStateOf(
            DetailsPaletteColors(
                pageBackground = fallbackPage,
                onPageBackground = scheme.onBackground,
                accent = fallbackAccent,
                onAccent = scheme.onPrimary,
                pillBackground = scheme.surface.copy(alpha = 0.72f),
                onPillBackground = scheme.onSurface
            )
        )
    }

    val context = LocalContext.current
    val imageLoader = context.imageLoader

    LaunchedEffect(imageUrl, fallbackPage, fallbackAccent) {
        if (imageUrl.isNullOrBlank()) return@LaunchedEffect

        val request =
            ImageRequest.Builder(context)
                .data(imageUrl)
                .allowHardware(false)
                .build()

        val result = imageLoader.execute(request)
        val drawable = (result as? SuccessResult)?.drawable ?: return@LaunchedEffect
        val bitmap = drawable.toBitmap()

        val palette =
            withContext(Dispatchers.Default) {
                Palette.from(bitmap)
                    .clearFilters()
                    .generate()
            }

        val bgArgb =
            palette.darkMutedSwatch?.rgb
                ?: palette.darkVibrantSwatch?.rgb
                ?: palette.mutedSwatch?.rgb
                ?: palette.vibrantSwatch?.rgb

        val accentArgb =
            palette.vibrantSwatch?.rgb
                ?: palette.lightVibrantSwatch?.rgb
                ?: palette.mutedSwatch?.rgb
                ?: palette.lightMutedSwatch?.rgb

        val extractedBg = bgArgb?.let { Color(it) }
        val extractedAccent = accentArgb?.let { Color(it) }

        val pageBackground = if (extractedBg != null) lerp(fallbackPage, extractedBg, 0.88f) else fallbackPage
        val accent = if (extractedAccent != null) lerp(fallbackAccent, extractedAccent, 0.9f) else fallbackAccent
        val onPage = contrastColor(pageBackground)
        val onAccent = contrastColor(accent)
        val pillBackground = lerp(pageBackground, onPage, 0.14f).copy(alpha = 0.72f)

        colors =
            DetailsPaletteColors(
                pageBackground = pageBackground,
                onPageBackground = onPage,
                accent = accent,
                onAccent = onAccent,
                pillBackground = pillBackground,
                onPillBackground = onPage
            )
    }

    return colors
}

private fun contrastColor(background: Color): Color {
    // 0.52 chosen to keep mid-tones leaning white.
    return if (background.luminance() > 0.52f) Color.Black else Color.White
}
