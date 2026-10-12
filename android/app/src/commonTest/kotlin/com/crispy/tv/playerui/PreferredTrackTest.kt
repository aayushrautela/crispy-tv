package com.crispy.tv.playerui

import com.crispy.tv.addons.streams.AddonSubtitle
import com.crispy.tv.nativeengine.playback.NativeTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Covers the two matchers behind "the default audio selection is always the first
 * audio", and the preference resolution they share.
 *
 * ## Why the no-match case is pinned rather than implied
 *
 * Every assertion that a track is *selected* is written against a list where the match
 * is not first. The reverse assertion is the one the bug lives in: with no match at all
 * the answer must be `null`, so the engine keeps whatever it chose, **not the first
 * track**. Returning the first track would be a plausible reading of "fix the default
 * selection" and it is the reason these return a nullable instead of a `.first()` at
 * the call site.
 *
 * ## Why the ids are literal
 *
 * Fixture ids are written out rather than derived from the language they carry. An id
 * built from the value under test would make half of these assertions compare a string
 * against the expression that produced it, and pass whatever the matcher did.
 */
class PreferredTrackTest {

    private fun audio(id: String, language: String?) =
        NativeTrack(id = id, index = 0, language = language, title = null)

    private fun subtitle(id: String, language: String) =
        AddonSubtitle(id = id, url = "https://example.test/$id.vtt", language = language, display = id)

    // region preference resolution

    @Test
    fun `an unset preference falls back to english`() {
        // This is the hard-coded fallback, and the only line the server-side preference
        // replaces. Pinned as a value so that swap is a visible change.
        assertEquals("en", resolvePreferredLanguage(null))
        assertEquals("en", resolvePreferredLanguage(""))
        assertEquals("en", resolvePreferredLanguage("   "))
    }

    @Test
    fun `an undetermined preference is treated as no preference`() {
        // Not a request for unlabelled tracks: `und` names no language.
        assertEquals("en", resolvePreferredLanguage("und"))
        assertEquals("en", resolvePreferredLanguage("UND"))
        assertEquals("en", resolvePreferredLanguage("  und  "))
    }

    @Test
    fun `a set preference is normalized to the one spelling both sides compare in`() {
        assertEquals("en", resolvePreferredLanguage("eng"))
        assertEquals("en", resolvePreferredLanguage("  EN  "))
        assertEquals("fr", resolvePreferredLanguage("fre"))
        assertEquals("de", resolvePreferredLanguage("ger"))
        assertEquals("de", resolvePreferredLanguage("deu"))
    }

    @Test
    fun `a three-letter code the table does not carry survives unchanged`() {
        // `fra` is the ISO 639-2/T spelling of French and the table carries only
        // `fre`. It is *not* mapped to `fr`, which is `normalizeLang`'s documented
        // behaviour for an unknown three-letter code, so the one thing left is for a
        // `fra` preference to match a `fra` track. Pinned so it is a missing table
        // entry rather than an unnoticed cross-spelling break.
        assertEquals("fra", resolvePreferredLanguage("fra"))
        assertEquals(
            "fra",
            preferredAudioTrack(listOf(audio("fre", "fre"), audio("fra", "fra")), "fra")?.id,
        )
        assertNull(preferredAudioTrack(listOf(audio("fre", "fre")), "fra"))
    }

    // endregion

    // region audio

    @Test
    fun `an english track is selected for an english preference`() {
        // An Exo track reports ISO 639-2, so the spelled-out mismatch the user sees is
        // exactly this one.
        val tracks = listOf(audio("french", "fre"), audio("spanish", "spa"), audio("english", "eng"))
        assertEquals("english", preferredAudioTrack(tracks, "en")?.id)
    }

    @Test
    fun `both spellings of the preference reach the same track`() {
        val tracks = listOf(audio("spanish", "spa"), audio("english", "eng"))
        assertEquals("english", preferredAudioTrack(tracks, "en")?.id)
        assertEquals("english", preferredAudioTrack(tracks, "eng")?.id)
    }

    @Test
    fun `the french and german variants are equivalent on both sides`() {
        // Both `ger` and `deu` reduce to `de`, so where the track sits in the list is
        // what decides -- first match wins, and the expectation is written against that
        // order rather than against which spelling is "more correct".
        assertEquals("deu", preferredAudioTrack(listOf(audio("deu", "deu"), audio("ger", "ger")), "de")?.id)
        assertEquals("ger", preferredAudioTrack(listOf(audio("ger", "ger"), audio("deu", "deu")), "de")?.id)
        assertEquals("fre", preferredAudioTrack(listOf(audio("fre", "fre")), "fr")?.id)
        assertEquals("fre", preferredAudioTrack(listOf(audio("fre", "fre")), "fre")?.id)
        // The other direction: a preference stored in its three-letter spelling
        // reaching a track that is tagged two-letter. This is the case a matcher that
        // trusted its caller to have normalized would have missed.
        assertEquals("fr", preferredAudioTrack(listOf(audio("fr", "fr")), "fre")?.id)
    }
    @Test
    fun `case and whitespace on the track do not decide the match`() {
        assertEquals(
            "mixed case",
            preferredAudioTrack(listOf(audio("mixed case", "  ENg  ")), "en")?.id,
        )
    }

    @Test
    fun `the first matching track wins when several match`() {
        // Two candidates for one language: the engine's own order is the tiebreak, so
        // the answer is positional and a `first` is the behaviour. Pinned because
        // mpv prefers the track matching *more* subtags, which would be the other
        // answer and is not the rule here.
        val tracks =
            listOf(
                audio("commentary", "en").copy(index = 1, title = "Commentary"),
                audio("main", "en").copy(index = 0, title = "Main"),
            )
        assertEquals("commentary", preferredAudioTrack(tracks, "en")?.id)
    }

    @Test
    fun `no match is null rather than the first track`() {
        // The whole complaint, asserted in its negative form.
        val tracks = listOf(audio("spanish", "spa"), audio("japanese", "jpn"))
        assertNull(preferredAudioTrack(tracks, "en"))
        assertNull(preferredAudioTrack(tracks, resolvePreferredLanguage(null)))
    }

    @Test
    fun `an undetermined or unlabelled track never matches`() {
        // `normalizeLang` maps a blank tag to "und", so these are the same test.
        val tracks =
            listOf(audio("undetermined", "und"), audio("nulled", null), audio("blank", ""))
        assertNull(preferredAudioTrack(tracks, "en"))
        assertNull(preferredAudioTrack(listOf(audio("undetermined", "und")), "und"))
    }

    @Test
    fun `an empty track list selects nothing`() {
        assertNull(preferredAudioTrack(emptyList(), "en"))
    }

    @Test
    fun `a secondary subtag does not block the match`() {
        // `pt-BR` and `en-US` name a language with a region, and an add-on advertising
        // one is the case that made the old match too tight.
        assertEquals(
            "brazilian",
            preferredAudioTrack(listOf(audio("spanish", "es"), audio("brazilian", "pt-BR")), "pt")?.id,
        )
        assertEquals(
            "us english",
            preferredAudioTrack(
                listOf(audio("spanish", "es"), audio("us english", "en-US")),
                resolvePreferredLanguage("eng"),
            )?.id,
        )
    }

    // endregion

    // region add-on subtitles

    @Test
    fun `an add-on subtitle is selected for the preferred language`() {
        val subtitles = listOf(subtitle("spanish", "spa"), subtitle("english", "eng"))
        assertEquals("english", preferredAddonSubtitle(subtitles, "en")?.id)
    }

    @Test
    fun `an add-on subtitle is not selected when nothing matches`() {
        // Which is why the caller is allowed to start playback without one.
        assertNull(preferredAddonSubtitle(listOf(subtitle("spanish", "spa")), "en"))
        assertNull(preferredAddonSubtitle(emptyList(), "en"))
    }

    @Test
    fun `an undetermined add-on subtitle never matches`() {
        assertNull(preferredAddonSubtitle(listOf(subtitle("undetermined", "und")), "en"))
    }

    @Test
    fun `the audio and subtitle matchers agree about what the same language is`() {
        // The two are one rule over two types. A suite that only ever drives one of
        // them would not notice the day they stopped being one rule.
        val language = "fre"
        val track = preferredAudioTrack(listOf(audio("spanish", "spa"), audio("fre", language)), language)
        val sub = preferredAddonSubtitle(listOf(subtitle("spanish", "spa"), subtitle("fre", language)), language)
        assertEquals(
            listOf(language, language),
            listOf(track?.language, sub?.language),
            "the matchers disagreed about $language",
        )
    }

    // endregion
}
