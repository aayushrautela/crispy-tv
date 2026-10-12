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
 * `normalizeLang` is applied here so that `"en"` and `"eng"` name the same target. That
 * matters because the preference is written from the *track's* own spelling, and an
 * Exo `Format` reports ISO 639-2 -- so an unnormalized store would hold `"eng"` after
 * the first tap and never match an `"en"` tag again.
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
 * Both sides go through [normalizeLang], not just the track's. **A caller passing the
 * raw preference must still get the match** -- the preference is written from whichever
 * track the viewer last tapped, so `"eng"` is the common spelling, and a matcher that
 * trusted its caller to have normalized would silently match nothing on exactly the
 * case it was built for.
 *
 * ## Why a secondary subtag is dropped
 *
 * A tag with a region (`pt-BR`, `zh-TW`) is not a different language, and the
 * complaint this matcher was written to answer is that the match was too tight.
 * Comparing primary subtags only is what makes `"en"` match an add-on advertising
 * `"en-US"`, and it costs nothing in the other direction: an `"und"` track still
 * never matches, because [normalizeLang] has already reduced a blank tag to `"und"`
 * -- and a *preference* of `und` guards itself rather than asking for unlabelled
 * tracks, which is why both sides are checked.
 */
private fun languageTagMatches(candidate: String?, preferred: String): Boolean {
    val target = normalizeLang(preferred).substringBefore('-')
    val tag = normalizeLang(candidate).substringBefore('-')
    return target != "und" && tag != "und" && tag == target
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
 * [preferred] is normalized here rather than trusted to have been normalized by the
 * caller, because the stored preference is written from whatever spelling the last
 * tapped track happened to send.
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
