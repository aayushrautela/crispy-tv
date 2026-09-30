package com.crispy.tv.playerui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers the two decisions that left `PlayerTrackSheet.kt`, and the six keyword arms
 * that did not move at all.
 *
 * The slot is stubbed here, so the standing rule applies with full force: **an
 * assertion that reads a string the stub invented proves the stub.** So the stub
 * records *the tag it was asked about* into [askedFor] and every case asserts on
 * that list, which is the value production computed and handed over. Only the cases
 * that are genuinely about the fallback's *shape* assert on the label itself, and
 * those use a stub whose answer is fixed and obvious (`"English"` for `en`, the tag
 * for anything else, blank for one deliberately broken case).
 */
class LanguageLabelsTest {
    private val askedFor = mutableListOf<String>()
    private var answer: (String) -> String = { tag -> if (tag == "en") "English" else tag }

    private fun displayName(tag: String): String {
        askedFor += tag
        return answer(tag)
    }

    /** A three-letter code the table resolves, and the two-letter code it becomes. */
    private fun labelFor(code: String?): String = languageLabelForCode(code, ::displayName)

    // region the six keyword arms

    @Test
    fun everyBackendKeywordHasItsOwnLabel() {
        assertEquals("Off", labelFor("none"))
        assertEquals("Off", labelFor("off"))
        assertEquals("Forced", labelFor("forced"))
        assertEquals("Default", labelFor("default"))
        assertEquals("Device language", labelFor("device"))
        assertEquals("Original", labelFor("original"))
        assertEquals("Undetermined", labelFor("und"))
    }

    @Test
    fun noTwoKeywordsShareALabel() {
        // Two keywords sharing a label would render two indistinguishable rows in
        // the same dropdown, so the labels are pinned individually *and* as a set.
        val labels = listOf("none", "forced", "default", "device", "original", "und")
            .map { labelFor(it) }
        assertEquals(labels.size, labels.toSet().size, "duplicate keyword labels: $labels")
    }

    @Test
    fun aKeywordIsMatchedWhateverItsCase() {
        assertEquals("Forced", labelFor("FORCED"))
        assertEquals("Forced", labelFor("  Forced  "))
        assertEquals("Off", labelFor("None"))
    }

    @Test
    fun aKeywordNeverReachesTheDisplayLookup() {
        askedFor.clear()
        labelFor("device")
        labelFor("und")
        assertEquals(emptyList(), askedFor, "a keyword consulted the platform: $askedFor")
    }

    @Test
    fun theKeywordTableIsCheckedBeforeTheIsoLookup() {
        // `und` is also an ISO 639-2 code (Undetermined). The keyword arm has to
        // win, or the label becomes whatever the platform calls `und`.
        answer = { tag -> if (tag == "und") "Undetermined language" else tag }
        assertEquals("Undetermined", labelFor("und"))
        assertEquals(emptyList(), askedFor)
    }

    // endregion

    // region blank and missing

    @Test
    fun aMissingOrBlankCodeIsUnknown() {
        assertEquals("Unknown", labelFor(null))
        assertEquals("Unknown", labelFor(""))
        assertEquals("Unknown", labelFor("   "))
        assertEquals(emptyList(), askedFor)
    }

    @Test
    fun blankIsUnknownRatherThanTheUndeterminedKeyword() {
        // The `isNullOrBlank` guard runs before the keyword table, so a blank is not
        // reported as "Undetermined" -- and the two are different claims about the
        // track, so the distinction is a product decision worth pinning.
        assertEquals("Unknown", labelFor("  \t "))
    }

    // endregion

    // region the ISO 639-2/B to 639-1 lookup

    @Test
    fun aThreeLetterCodeIsReducedToItsTwoLetterForm() {
        askedFor.clear()
        answer = { "English" }
        assertEquals("English", labelFor("eng"))
        assertEquals(listOf("en"), askedFor)
    }

    @Test
    fun theVariantSpellingsAreAllInTheTable() {
        // A language's 639-2 code has more than one spelling in the wild, and this
        // table has to carry every one rather than canonicalising the input -- which
        // would need a second table of the variants it had missed.
        val expected =
            mapOf(
                "fre" to "fr", "ger" to "de", "ell" to "el", "gre" to "el",
                "rum" to "ro", "ron" to "ro", "ice" to "is", "isl" to "is",
                "chi" to "zh", "zho" to "zh", "cmn" to "zh",
            )
        for ((three, two) in expected) {
            askedFor.clear()
            answer = { "English" }
            labelFor(three)
            assertEquals(listOf(two), askedFor, "$three should have resolved to $two, asked for $askedFor")
        }
    }

    @Test
    fun aTwoLetterCodeIsPassedThroughUnchanged() {
        askedFor.clear()
        answer = { "English" }
        assertEquals("English", labelFor("en"))
        assertEquals(listOf("en"), askedFor)
    }

    @Test
    fun anUnknownThreeLetterCodeIsHandedToTheLookupAsIs() {
        // No table entry, so the code itself is what the platform gets asked about.
        // The stub echoes it, which is also what the real platform does for a code
        // it has no name for, and the third arm below takes over from there.
        askedFor.clear()
        answer = { tag -> tag }
        assertEquals("QAA", labelFor("qaa"))
        assertEquals(listOf("qaa"), askedFor)
    }

    @Test
    fun theLookupSeesTheThreeLetterCodeUnchangedButTheFallbackKeepsTheOriginalSpelling() {
        // A three-letter code the table does not have is handed to the lookup as it
        // was written -- there is nothing to look it up by -- so the lookup sees
        // `Qaa` while the fallback renders `QAA`. The two answers come from
        // different places, which is why the case exists at all.
        askedFor.clear()
        answer = { tag -> tag }
        assertEquals("QAA", labelFor("Qaa"))
        assertEquals(listOf("Qaa"), askedFor, "the lookup is handed the original spelling")
    }

    @Test
    fun theTableIsKeyedOnTheLowercasedCode() {
        // The same code in a different case resolves identically, which is only true
        // if the table key is the lowercased input rather than the raw string.
        askedFor.clear()
        answer = { "English" }
        labelFor("FRE")
        labelFor("fre")
        labelFor("Fre")
        assertEquals(listOf("fr", "fr", "fr"), askedFor)
    }

    @Test
    fun aCodeLongerThanThreeLettersIsNotLookedUp() {
        askedFor.clear()
        answer = { tag -> tag }
        assertEquals("TOOLONGTAG", labelFor("toolongtag"))
        assertEquals(listOf("toolongtag"), askedFor)
    }

    // endregion

    // region the three ways the fallback answers

    @Test
    fun aDisplayNameIsUsedWhenThePlatformHasOne() {
        answer = { "Français" }
        assertEquals("Français", labelFor("fr"))
    }

    @Test
    fun aNonAsciiDisplayNameSurvivesIntact() {
        // Measured on JDK 21: `mi` resolves to "Māori", with a macron on the first
        // vowel. That is the evidence that the answer is not a hard-coded table --
        // and it is also why this case exists: a `lowercase()` or `uppercase()`
        // applied to the *label* anywhere would mangle it, so neither is applied.
        answer = { "Māori" }
        assertEquals("Māori", labelFor("mi"))
    }

    @Test
    fun aDisplayNameEqualToTheTagFallsBackToTheUppercasedCode() {
        // The platform echoes a code it has no name for. Rendering `ZZ` is more
        // honest than rendering `Zz`, so the equals-tag guard takes the third arm.
        answer = { tag -> tag }
        assertEquals("ZZ", labelFor("zz"))
        assertEquals("QQ", labelFor("qq"))
        assertEquals("XX", labelFor("xx"))
    }

    @Test
    fun theEqualsTagGuardIgnoresCase() {
        answer = { tag -> tag.uppercase() }
        assertEquals("ZZ", labelFor("zz"))
    }

    @Test
    fun theEqualsTagGuardIgnoresCaseWhenTheTwoAnswersDifferOnlyByCase() {
        // The case above cannot see the flag, and it is worth saying why rather than
        // deleting it: with the code `zz` and the answer `ZZ`, the guard firing
        // returns `raw.uppercase()` -- which is `"ZZ"` -- and the guard *not* firing
        // returns the display name, which is also `"ZZ"`. The two answers coincide,
        // so deleting `ignoreCase` changes nothing there.
        //
        // The only arrangement that separates them is a **two-letter** code, so
        // `two == raw`, whose display name differs from the tag by case and nothing
        // else: `EN` against `En`. Then the guard firing yields `raw.uppercase()` =
        // `"EN"`, while not firing yields the display name `"En"`.
        answer = { "En" }
        assertEquals("EN", labelFor("EN"))
        assertEquals(listOf("EN"), askedFor.takeLast(1))
    }

    @Test
    fun theFallbackUppercasesTheOriginalCodeRatherThanTheReducedTag() {
        // The third arm must render the code **as the backend sent it**, uppercased,
        // and not the two-letter form the ISO table reduced it to. The only way to see
        // which one is used is a three-letter code the table *resolves*, because then
        // `two != raw`: `ger` reduces to `de`, and an answer of `de` trips the
        // equals-tag guard (case-insensitively, so `de` == `de`).
        //
        // With `upper = raw.uppercase()` the answer is `"GER"`; with
        // `upper = two.uppercase()` it would be `"DE"`.
        answer = { "de" }
        assertEquals("GER", labelFor("ger"))
        assertEquals(listOf("de"), askedFor.takeLast(1))
    }

    @Test
    fun aBlankDisplayNameFallsBackToTheUppercasedCode() {
        answer = { "" }
        assertEquals("ZZ", labelFor("zz"))
    }

    @Test
    fun aLookupThatThrowsFallsBackToTheUppercasedCode() {
        // `runCatching` is the third arm, not defensive noise: without it a display-
        // name implementation quirk would render an empty label instead of a code.
        answer = { error("no such language data") }
        assertEquals("ZZ", labelFor("zz"))
    }

    @Test
    fun theFallbackUppercasesRatherThanTitlecasing() {
        // Measured on JDK 21: `toUpperCase(Locale.ENGLISH)` and
        // `toUpperCase(ROOT)` agree for every ISO 639 code, which is why this is
        // the locale-invariant `uppercase()`. It is a *code* here, not a name, so
        // there is nothing to title-case.
        answer = { tag -> tag }
        assertEquals("DE", labelFor("de"))
        assertEquals("PT-BR", labelFor("pt-br"))
    }

    @Test
    fun aTwoLetterAnswerIsNeverTheLabel() {
        answer = { "fr" }
        assertEquals("FR", labelFor("fr"))
    }

    // endregion

    // region normalizeLang

    @Test
    fun aBlankCodeIsTheUndeterminedKey() {
        assertEquals("und", normalizeLang(null))
        assertEquals("und", normalizeLang(""))
        assertEquals("und", normalizeLang("   "))
    }

    @Test
    fun aCodeIsTrimmedAndLowercased() {
        // `XYZ` is three letters and is **not** in the table, so it is the case that
        // shows the trimming and the lowercasing without the ISO lookup also firing.
        assertEquals("xyz", normalizeLang("  XYZ  "))
        assertEquals("fr", normalizeLang("  FR  "))
        assertEquals("fr", normalizeLang("FRE"))
        assertEquals("de", normalizeLang("  GER  "))
    }

    @Test
    fun aThreeLetterCodeIsAlsoReducedForTheGroupingKey() {
        assertEquals("de", normalizeLang("ger"))
        assertEquals("ro", normalizeLang("rum"))
        assertEquals("zh", normalizeLang("zho"))
    }

    @Test
    fun aTwoLetterCodeIsItsOwnKey() {
        assertEquals("fr", normalizeLang("fr"))
    }

    @Test
    fun anUnknownThreeLetterCodeIsItsOwnKey() {
        assertEquals("qaa", normalizeLang("qaa"))
    }

    @Test
    fun theGroupingKeyIsAlwaysLowercase() {
        // The grouping key is compared for equality at the filter that shows one
        // language's tracks, so a key that kept the caller's casing would split one
        // language into two groups.
        val keys = listOf("EN", "en", "Eng", "  FRE  ").map { normalizeLang(it) }
        assertEquals(setOf("en", "en", "en", "fr"), keys.toSet())
        assertTrue(keys.all { it == it.lowercase() }, "a key kept its caller casing: $keys")
    }

    @Test
    fun theGroupingKeyAndTheLabelAreAskingAboutTheSameValue() {
        // `normalizeLang` is what groups and `languageLabelForCode` is what it groups
        // *by name of*, so a code that normalises one way and is labelled from the
        // un-normalised form would render "FRE" under a header saying "fr".
        askedFor.clear()
        answer = { "French" }
        val key = normalizeLang("FRE")
        assertEquals("French", languageLabelForCode(key, ::displayName))
        assertEquals(listOf("fr"), askedFor)
    }

    // endregion
}
