package com.crispy.tv.playerui

import com.crispy.tv.addons.streams.AddonSubtitle
import com.crispy.tv.nativeengine.playback.NativeTrack

/**
 * The language a track is auto-selected for when the user has expressed no preference.
 *
 * Hard-coded on purpose. The server-side preference that replaces it arrives later,
 * and when it does [resolvePreferredLanguage] is the single line that changes -- the
 * matchers below must not grow to know where a preference comes from.
 */
private const val FALLBACK_PREFERRED_LANGUAGE = "en"

/**
 * The language to match a track against: the persisted preference when there is one,
 * English otherwise.
 *
 * `normalizeLang` is applied here rather than at each comparison so that `"en"` and
 * `"eng"` name the same target however the preference was written -- and the history
 * in `PlayerSessionViewModel` says it is written from the *track's* spelling, which
 * for an Exo `Format` is an ISO 639-2 code.
 *
 * A preference that resolves to `"und"` is treated as no preference at all rather
 * than as a request for unlabelled tracks, which is why the `takeIf` is there and
 * not left to the matchers to rediscover.
 */
internal fun resolvePreferredLanguage(setting: String?): String =
    setting
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let(::normalizeLang)
        ?.takeIf { it != "und" }
        ?: FALLBACK_PREFERRED_LANGUAGE

/**
 * Whether one track's language tag names [preferred].
 *
 * `preferred` must already be normalized -- [resolvePreferredLanguage] does it.
 *
 * ## Why a secondary subtag is dropped
 *
 * A tag with a region (`pt-BR`, `zh-TW`) is not a different language, and the
 * complaint this matcher was written to answer is that the match was too tight.
 * Comparing primary subtags only is what makes `"en"` match an add-on advertising
 * `"en-US"`, and it costs nothing in the other direction: an `"und"` track still
 * never matches, because [normalizeLang] has already reduced a blank tag to `"und"`
 * and a primary subtag is never `"und"` for a real tag.
 */
private fun languageTagMatches(candidate: String?, preferred: String): Boolean {
    val tag = normalizeLang(candidate).substringBefore('-')
    return tag != "und" && tag == preferred
}

private inline fun <T> preferredByLanguage(
    items: List<T>,
    preferred: String,
    language: (T) -> String?,
): T? = items.firstOrNull { languageTagMatches(language(it), preferred) }

/**
 * The audio track to select for [preferred], or `null` when none of [tracks] matches.
 *
 * **`null` means "leave the engine's own choice alone".** The engine's default is the
 * first track, which is the behaviour being replaced -- but only when a match exists.
 * Falling back to the first track here would re-encode the bug as a decision this
 * file made, and would fight the user on a track they picked by hand.
 *
 * [preferred] is the resolved language, not the raw setting: pass
 * [resolvePreferredLanguage] rather than re-deriving the fallback at the call site.
 */
internal fun preferredAudioTrack(
    tracks: List<NativeTrack>,
    preferred: String,
): NativeTrack? = preferredByLanguage(tracks, preferred) { it.language }

/**
 * The add-on subtitle to attach for [preferred], or `null` when none matches.
 *
 * Same contract as [preferredAudioTrack]: `null` leaves whatever the engine loaded on
 * its own in place. The caller decides whether that means waiting for the list or
 * starting playback without subtitles, and the two answers are different -- a
 * preference is not a request to block the first frame.
 */
internal fun preferredAddonSubtitle(
    subtitles: List<AddonSubtitle>,
    preferred: String,
): AddonSubtitle? = preferredByLanguage(subtitles, preferred) { it.language }
