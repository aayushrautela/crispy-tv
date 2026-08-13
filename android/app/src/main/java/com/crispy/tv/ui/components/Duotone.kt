package com.crispy.tv.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix

/**
 * Duotone palette + deterministic theme selection for collection/catalog-section cards.
 *
 * Mirrors the webui rendering: the card background is the highlight color, the backdrop
 * image is grayscaled and multiplied into it, and a shadow-color layer blended with
 * `lighten` anchors the blacks. The blend modes are applied on the GPU via
 * `Modifier.graphicsLayer`, so this stays cheap at scroll time.
 */

private data class Rgb(val r: Int, val g: Int, val b: Int) {
    fun color(): Color = Color(r, g, b)
}

private data class DuotoneTheme(val shadow: Rgb, val highlight: Rgb)

private val DUOTONE_THEMES: Map<String, DuotoneTheme> = mapOf(
    "spotify" to DuotoneTheme(Rgb(18, 18, 18), Rgb(30, 215, 96)),
    "mint-choc" to DuotoneTheme(Rgb(42, 27, 24), Rgb(160, 232, 175)),
    "coral-navy" to DuotoneTheme(Rgb(0, 0, 64), Rgb(255, 127, 80)),
    "acid-night" to DuotoneTheme(Rgb(26, 0, 51), Rgb(197, 227, 126)),
    "neon-purple" to DuotoneTheme(Rgb(30, 11, 61), Rgb(217, 70, 239)),
    "fire-slate" to DuotoneTheme(Rgb(15, 23, 42), Rgb(239, 68, 68)),
)

private val THEME_KEYS: List<String> = DUOTONE_THEMES.keys.toList()

/** Deterministic theme for a stable seed so each card keeps its color across re-renders. */
fun pickDuotoneTheme(seed: String): String {
    var hash = 0
    for (c in seed) {
        hash = hash * 31 + c.code
    }
    return THEME_KEYS[(hash and 0x7fffffff) % THEME_KEYS.size]
}

fun duotoneHighlightColor(seed: String): Color =
    DUOTONE_THEMES[pickDuotoneTheme(seed)]!!.highlight.color()

fun duotoneShadowColor(seed: String): Color =
    DUOTONE_THEMES[pickDuotoneTheme(seed)]!!.shadow.color()

/** Grayscale (100%) + contrast (1.5), baked into one color matrix to match the webui filter. */
val DuotoneGrayContrastColorFilter: ColorFilter
    get() = ColorFilter.colorMatrix(
        ColorMatrix(
            floatArrayOf(
                0.4485f, 0.8805f, 0.171f, 0f, -0.25f,
                0.4485f, 0.8805f, 0.171f, 0f, -0.25f,
                0.4485f, 0.8805f, 0.171f, 0f, -0.25f,
                0f, 0f, 0f, 1f, 0f,
            )
        )
    )
