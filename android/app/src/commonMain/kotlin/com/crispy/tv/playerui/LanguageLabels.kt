package com.crispy.tv.playerui

/**
 * The ISO 639-2/B three-letter code to the 639-1 two-letter code, for the codes a
 * media backend actually sends.
 *
 * Both spellings appear in the wild for several languages (`spa`/`es` but also
 * `fre`/`fr` *and* `ger`/`de`; `ell`/`el` and `gre`/`el`; `rum`/`ro` and `ron`/`ro`;
 * `ice`/`is` and `isl`/`is`), so the table carries every variant rather than
 * canonicalising the input -- which would need a second table of the variants it
 * had failed to include.
 */
private val ISO639_2_TO_1 =
    mapOf(
        "eng" to "en", "spa" to "es", "fre" to "fr", "deu" to "de", "ger" to "de",
        "ita" to "it", "por" to "pt", "rus" to "ru", "jpn" to "ja", "kor" to "ko",
        "chi" to "zh", "zho" to "zh", "ara" to "ar", "hin" to "hi", "nld" to "nl",
        "dut" to "nl", "tur" to "tr", "pol" to "pl", "vie" to "vi", "tha" to "th",
        "ind" to "id", "msa" to "ms", "may" to "ms", "tam" to "ta", "tel" to "te",
        "ben" to "bn", "mal" to "ml", "guj" to "gu", "kan" to "kn", "mar" to "mr",
        "pan" to "pa", "urd" to "ur", "fas" to "fa", "per" to "fa", "heb" to "he",
        "swe" to "sv", "nor" to "no", "dan" to "da", "fin" to "fi", "ell" to "el",
        "gre" to "el", "ces" to "cs", "cze" to "cs", "hun" to "hu", "ron" to "ro",
        "rum" to "ro", "bul" to "bg", "ukr" to "uk", "hrv" to "hr", "srp" to "sr",
        "slv" to "sl", "lit" to "lt", "lav" to "lv", "est" to "et", "cat" to "ca",
        "gle" to "ga", "isl" to "is", "ice" to "is", "mkd" to "mk", "mac" to "mk",
        "sqi" to "sq", "alb" to "sq", "afr" to "af", "aka" to "ak", "amh" to "am",
        "bod" to "bo", "bos" to "bs", "mya" to "my", "cmn" to "zh", "cym" to "cy",
        "eus" to "eu", "fao" to "fo", "glg" to "gl", "hat" to "ht",
        "hau" to "ha", "hye" to "hy", "ibo" to "ig", "jav" to "jv", "kat" to "ka",
        "kaz" to "kk", "khm" to "km", "kin" to "rw", "kir" to "ky", "lao" to "lo",
        "mon" to "mn", "mri" to "mi", "nya" to "ny", "ori" to "or",
        "pus" to "ps", "que" to "qu", "sun" to "su", "swa" to "sw", "tgk" to "tg",
        "tuk" to "tk", "uig" to "ug", "uzb" to "uz", "wol" to "wo", "yor" to "yo",
        "zul" to "zu",
    )

/**
 * The label to show for a track language tag, or "Unknown" when there is nothing to
 * show.
 *
 * ## Why the sheet is not where this used to be
 *
 * This used to live at the bottom of `PlayerTrackSheet.kt`, and the sheet could not
 * follow it: the sheet names `NativeTrack` and `externalSubtitleTrackId`, and both
 * were declared in `:android:native-engine` -- a plain `com.android.library`, and so
 * unreachable from a KMP `commonMain` whatever the sheet itself looks like.
 *
 * **Both of those two names have since been lifted into `:android:core-domain`'s
 * `commonMain` under this same package**, and the sheet moved. That is the whole
 * mechanism, and it is worth naming because the first version of this note claimed
 * the opposite: the sheet was not stuck behind a Phase 5/6 media-engine decision,
 * it was stuck behind **two value types that had no business living in an Android
 * library**, and a five-field data class plus a hash helper do not need a player to
 * be defined in terms of. `PlayerResizeMode` escaped the same way before it.
 *
 * The lesson is the one `:core-domain`'s `PlayerResizeMode.kt` was created to
 * record, and it cuts the opposite way from "the sheet is too Android": **a file can
 * be portable in every line it writes and still be unpinnable, and the thing that
 * decides is the *kind* of the names it mentions, not how much platform API it
 * calls.**
 *
 * The one thing it could not keep is [englishDisplayName], and that is the slot.
 * `Locale.forLanguageTag(tag).getDisplayLanguage(Locale.ENGLISH)` is genuinely
 * locale work, and so is the `titlecase(Locale.ENGLISH)` that renders its result.
 * Both are Android-only APIs whose answers are *data*, not behaviour, so the slot
 * carries the whole "tag to the English name, already title-cased" step and the
 * caller decides how to compute it.
 *
 * **A hard-coded table of English display names was the other option and it was
 * rejected on measurement**: `mi` renders `Maori` with a macron on the first
 * vowel, so the table would have to carry non-ASCII spellings and would still be
 * wrong for any tag the author did not think of. The platform already has the
 * answer; the seam belongs at the call.
 *
 * ## The three ways this answers
 *
 * 1. A keyword the backend sends instead of a tag -- `none`/`off`, `forced`,
 *    `default`, `device`, `original`, `und` -- has its own label and never reaches
 *    the slot. These are checked on the *lowercased* input, so `"Forced"` and
 *    `"forced"` agree, while the fallback's uppercase rendering is applied to the
 *    original spelling.
 * 2. Otherwise the tag is reduced to a two-letter code when the table has it, and
 *    the slot's answer is used **only when it is not the tag echoed back**. A tag
 *    the platform has no name for returns itself, and rendering `ZZ` is more honest
 *    than rendering `Zz`.
 * 3. Otherwise -- a blank answer, or a slot that throws -- the code is uppercased.
 *    The `runCatching` is kept from the original rather than treated as defensive
 *    noise: it is the third arm, and removing it would turn a display-name
 *    implementation quirk into an empty label.
 *
 * `uppercase()` here is locale-invariant on purpose. The argument was
 * `code.trim().uppercase(Locale.ENGLISH)`, and an ISO 639 code is ASCII, so the
 * measured output of the two is identical for every code and every keyword above.
 */
internal fun languageLabelForCode(
    code: String?,
    englishDisplayName: (String) -> String,
): String {
    if (code.isNullOrBlank()) return "Unknown"
    val lower = code.trim().lowercase()
    return when (lower) {
        "none", "off" -> "Off"
        "forced" -> "Forced"
        "default" -> "Default"
        "device" -> "Device language"
        "original" -> "Original"
        "und" -> "Undetermined"
        else -> {
            val raw = code.trim()
            val two = if (raw.length == 3) ISO639_2_TO_1[lower] ?: raw else raw
            val upper = raw.uppercase()
            runCatching {
                val display = englishDisplayName(two)
                if (display.isNotBlank() && !display.equals(two, ignoreCase = true)) display else upper
            }.getOrDefault(upper)
        }
    }
}

/**
 * The grouping key for a track language tag, and the value [languageLabelForCode]
 * is normally asked about.
 *
 * Blank is `"und"` rather than an empty key so an unlabelled track joins the
 * "Undetermined" group instead of getting a group of its own with an empty header,
 * and so the two functions agree about where an absent tag belongs -- they are
 * compared against each other at the filter that shows one language's tracks.
 *
 * `internal` rather than `private` because this file is now `commonMain` and the
 * whole point of the move is that the decision is reachable from `commonTest`.
 *
 * ## Two guards here that no test can see removed
 *
 * Both of the conditions below survived a mutation of their own, and neither is a
 * gap in the suite. They are written down so the next person does not read the
 * surviving mutation as one.
 *
 * - `isBlank()` cannot be `isEmpty()`. `raw` is `code?.trim().orEmpty().lowercase()`,
 *   so by the time this line runs a blank-but-non-empty `raw` is not reachable --
 *   `trim` has already removed every character `isBlank` would have objected to.
 *   This is the **thirteenth** redundant guard in the repository.
 * - `raw.length == 3` cannot be dropped. Every key in [ISO639_2_TO_1] is exactly
 *   three letters, so `ISO639_2_TO_1[x]` is null at any other length and the guard
 *   changes no answer. It is still the statement of intent -- the table is keyed on
 *   three-letter codes -- and it would become load-bearing the day a two-letter
 *   key was added. This is the **fourteenth** redundant guard.
 *
 * These two are the **second pair in one file**, after the pair in `LibraryScreen.kt`
 * and the pair in `PlayerInfoSheet.kt`. **When two guards in a file both come from a
 * property of the value in front of them rather than from the domain, that is the
 * pattern and not a coincidence** -- and it is worth writing both down together
 * rather than rediscovering each one from a mutation run.
 */
internal fun normalizeLang(code: String?): String {
    val raw = code?.trim().orEmpty().lowercase()
    if (raw.isBlank()) return "und"
    return if (raw.length == 3) ISO639_2_TO_1[raw] ?: raw else raw
}
