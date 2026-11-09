package com.crispy.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.Font as TextFont
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.crispy.tv.ui.resources.Res
import com.crispy.tv.ui.resources.googlesans_flex
import org.jetbrains.compose.resources.Font

/**
 * Google Sans Flex -- the app's text face, on both surfaces (phone theme and the
 * 10-foot theme) and on the search page's genre tiles in the narrow cut. One
 * bundled TTF, `composeResources/font/googlesans_flex.ttf`, carries every style
 * across the five axes of the google/fonts distribution (measured off the
 * bundled artifact's `fvar`): `wght` 1-1000 (default 400), `wdth` 25-151
 * (default 100), `slnt` -10..0 (default 0), `opsz` 1..144 (default 18), `ROND`
 * 0-100 (default 0). Font sourced from google/fonts (`ofl/googlesansflex`),
 * licensed SIL OFL 1.1 without Reserved Font Names.
 *
 * Realized styles are registered per [Font] entry with the axes spelled out in
 * `FontVariation.Settings` -- Google's own guidance for variable fonts
 * ("Work with fonts" on developer.android.com, and the `GoogleSansFlexTypography`
 * reference built the same way): choose them on the font, not on the text. The
 * family registers the three cuts the Material3 ramp actually uses -- 400, 500
 * (the label/title defaults) and 700 (explicit `FontWeight.Bold`) -- so a
 * `fontWeight` on the calling side selects the nearest registered entry rather
 * than interpolating. Extend a family by adding entries for the new realized
 * weight when a caller needs one.
 *
 * The declared Apple targets compile this module too; a bundled
 * `composeResources` font is platform-neutral, so nothing here pins to Android.
 */
val GoogleSansFlex: FontFamily
    @Composable get() = FontFamily(
        googleSansFlexEntry(400),
        googleSansFlexEntry(500),
        googleSansFlexEntry(700),
    )

/**
 * One realized cut of the variable face. The [TextFont] weight is a matching
 * hint only -- the rendered weight is the `wght` axis in [FontVariation.Settings]
 * -- and the two are kept equal, so the family renders exactly the values its
 * entries spell.
 *
 * [Font] reads the resource bytes, so this is a composable call and the helper
 * has to be one too.
 */
@Composable
private fun googleSansFlexEntry(weight: Int): TextFont = Font(
    Res.font.googlesans_flex,
    FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/**
 * The editorial display face behind the genre tiles: the width axis locked to
 * 25 ("narrow", the web app's `.font-narrow` cut, per the Google Fonts specimen
 * style "Heading 1": width 25, weight 156). Ultra-light at 156 because that is
 * where the letterforms read tall and airy at display sizes; at ordinary body
 * sizes it borders on illegible, so keep this family on large text only.
 */
val GoogleSansFlexNarrow: FontFamily
    @Composable get() = FontFamily(
        Font(
            Res.font.googlesans_flex,
            variationSettings = FontVariation.Settings(
                FontVariation.weight(156),
                FontVariation.width(25f),
            ),
        ),
    )
