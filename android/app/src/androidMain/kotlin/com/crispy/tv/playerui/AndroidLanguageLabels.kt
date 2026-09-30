package com.crispy.tv.playerui

import java.util.Locale

/**
 * The English display name for a language tag, title-cased the way the track sheet
 * renders it.
 *
 * This is the whole of [languageLabelForCode]'s platform dependence, in one place.
 * Two things happen here and neither can happen in `commonMain`:
 *
 * - `Locale.forLanguageTag(tag).getDisplayLanguage(Locale.ENGLISH)` resolves the
 *   tag against the platform's own language database.
 * - `titlecase(Locale.ENGLISH)` capitalises the answer. It takes a `Locale` because
 *   title-casing is itself locale-dependent, which is why the slot carries the
 *   title-casing rather than returning a raw display name for common code to
 *   capitalise -- a `replaceFirstChar { it.uppercase() }` in `commonMain` would be
 *   the Turkish dotless-i bug waiting for a user.
 *
 * The function is deliberately total for the inputs [languageLabelForCode] can
 * produce: a tag the platform has no name for comes back as the tag itself, and a
 * malformed one comes back blank. Both are handled by the caller, which is where
 * the decision to render `ZZ` instead of a blank label lives.
 */
internal fun englishDisplayNameForTag(tag: String): String =
    Locale
        .forLanguageTag(tag)
        .getDisplayLanguage(Locale.ENGLISH)
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ENGLISH) else it.toString() }
