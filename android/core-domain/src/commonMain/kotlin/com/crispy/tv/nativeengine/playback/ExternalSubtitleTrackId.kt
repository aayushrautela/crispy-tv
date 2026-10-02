package com.crispy.tv.nativeengine.playback

/** The marker that separates an externally attached subtitle's id from an engine's own. */
const val EXTERNAL_SUBTITLE_TRACK_ID_PREFIX = "ext:"

/**
 * Stable engine-agnostic track id for an externally attached subtitle, derived from
 * its URL so the sheet can match catalog entries against engine tracks by id alone.
 *
 * ## Two decisions here, and one that is not a decision at all
 *
 * **The fragment is stripped and the url is trimmed**, so `"  a.ass#en  "` and `"a.ass"`
 * produce the *same* id. That is deliberate -- a Media4 fragment can carry per-track
 * options that are not part of the subtitle's identity -- but it also means two
 * catalog entries that differ only in their fragment are indistinguishable to anything
 * matching by id. `aDifferentUrlCollidesWithThisOneIsNotAsserted` records the boundary
 * rather than asserting injectivity, which this function does not have.
 *
 * **The hash is rendered unsigned.** `String.hashCode()` is signed, and
 * `toUInt().toString(16)` is what keeps a negative hash from serialising as `-5a...`;
 * an id is compared as a string, so the sign would otherwise become part of the key.
 *
 * **The width is not a decision.** `toString(16)` drops leading zeros, so ids are
 * variable-length. That is stable for a given url, which is all a match key has to be,
 * and padding it would invent a width this function has no way to know.
 *
 * This function is a *hash-based composite key*, so it can collide, and a test that
 * asserted injectivity over a table would be asserting a property the implementation
 * does not have.
 */
fun externalSubtitleTrackId(url: String): String {
    val normalized = url.trim().substringBefore('#')
    return "$EXTERNAL_SUBTITLE_TRACK_ID_PREFIX${normalized.hashCode().toUInt().toString(16)}"
}
