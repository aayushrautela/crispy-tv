package com.crispy.tv.library

import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `:app`'s side of the two accessor policies that `com.crispy.tv.backend`
 * declares under the same names.
 *
 * ## Why this is in `androidHostTest` and not `commonTest`
 *
 * `org.json` is a class of the Android platform, supplied by `android.jar`, so
 * it is absent from every other target's classpath. Robolectric supplies the
 * real AOSP implementation; `:backend`'s
 * `JsonAccessorPolicyHostTest` says why the Maven `org.json:json` artifact is
 * not a substitute — the two implementations disagree.
 *
 * These functions were `private` members of [LibraryDiskCacheStore], which made
 * them unnameable from a test **in this module as well as any other**. They are
 * now top-level `internal` in `LibraryDiskCacheJsonAccessors.kt`, which is what
 * makes this file possible without constructing a `Context`.
 *
 * ## The two copies, and what this class is for
 *
 * `:backend` declares the same two names. Its `optNullableString` is
 * `internal`, `:app` is a different module, and it was never visible here — so
 * these two were written instead. **The duplication is deliberate and this suite
 * does not resolve it**; it pins both sides so that resolving it later is a
 * recorded decision rather than an accident. The `:backend` side is pinned in
 * `com.crispy.tv.backend.JsonAccessorPolicyHostTest`.
 *
 * Two of the disagreements are behavioural and neither is visible from a name:
 *
 * | input | `:backend` | here |
 * |---|---|---|
 * | the literal string `"null"` | `null` | `"null"` |
 * | `"banana"` as a boolean | `null` | `false` |
 * | the integer `1` as a boolean | `null` | `false` |
 *
 * The last two are the same defect seen from two sides: **this copy's
 * `optBooleanOrNull` cannot return `null` at all**, because it delegates to
 * `org.json`'s `optBoolean`, which returns the `Boolean` itself or its `false`
 * default and never `null`. So a function whose name promises an `Int?`-shaped
 * answer reports `false` for a value that is present and unreadable, and a
 * caller cannot tell that apart from a genuine `false`. That is asserted below
 * rather than left as a claim.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class LibraryDiskCacheJsonAccessorsTest {

    // ---------------------------------------------------------------------
    // optNullableString
    // ---------------------------------------------------------------------

    @Test
    fun anAbsentKeyAJsonNullAndABlankValueAreAllAbsent() {
        val json = JSONObject("""{"blank":"   ","nullish":null}""")

        assertNull(json.optNullableString("missing"), "an absent key is not a value")
        assertNull(json.optNullableString("nullish"), "a JSON null is not a value")
        assertNull(json.optNullableString("blank"), "whitespace is not a value")
    }

    /**
     * Divergence 1, and the only one of the two that the two copies share
     * between them. `:backend`'s `optNullableString` ends in
     * `takeUnless { it.isBlank() || it.equals("null", ignoreCase = true) }`,
     * so it answers `null` for a value that is the four characters `null`; this
     * one ends in `takeIf { it.isNotEmpty() }` and answers `"null"`.
     *
     * The `:backend` branch exists because backend payloads really do carry
     * that string — it is defensive parsing of a real shape, and it is *not*
     * there because of how the platform renders a JSON null (that never reaches
     * `optString`, because the guard above returns first). So this is a
     * deliberate-looking difference in two files that have never been compared.
     */
    @Test
    fun theFourCharactersNullAreAValueHereAndNotAValueInBackend() {
        val json = JSONObject("""{"nullText":"null","NULLText":"NULL"}""")

        assertEquals("null", json.optNullableString("nullText"))
        assertEquals("NULL", json.optNullableString("NULLText"), "and nothing here is case-insensitive")
    }

    @Test
    fun aValueIsTrimmedAndAValueThatMerelyLooksNullishIsStillAValue() {
        val json = JSONObject("""{"padded":"  x  ","zero":"0","count":0,"flag":false}""")

        assertEquals("x", json.optNullableString("padded"))
        assertEquals("0", json.optNullableString("zero"))
        assertEquals("0", json.optNullableString("count"))
        assertEquals("false", json.optNullableString("flag"))
    }

    // ---------------------------------------------------------------------
    // optBooleanOrNull
    // ---------------------------------------------------------------------

    @Test
    fun aBooleanIsReadFromABooleanAndFromTheTwoWordsInAnyCase() {
        val json = JSONObject("""{"yes":true,"no":false,"upperText":"TRUE","mixedText":"FaLsE"}""")

        assertEquals(true, json.optBooleanOrNull("yes"))
        assertEquals(false, json.optBooleanOrNull("no"))
        assertEquals(true, json.optBooleanOrNull("upperText"), "and the word is matched case-insensitively")
        assertEquals(false, json.optBooleanOrNull("mixedText"))
    }

    /**
     * Divergence 2, and the reason this function is pinned at all: **it cannot
     * return `null` for a value that is present and unreadable.** It delegates
     * to `org.json`'s `optBoolean`, which returns the `Boolean` or its `false`
     * default. `:backend`'s copy matches on the *value* and answers `null` for
     * anything that is not a boolean and not one of the two words.
     *
     * All four rows are the same answer — `false` — and `:backend` answers
     * `null` to all four. `org.json`'s `optBoolean` is strict on **both**
     * implementations (only a real `Boolean`, or case-insensitive
     * `"true"`/`"false"`; everything else is the `false` default), so this is
     * not a cross-implementation difference — it is this copy discarding the
     * difference itself, which is the worse of the two failures because it
     * leaves no trace in the value.
     */
    @Test
    fun aPresentButUnreadableBooleanIsFalseAndNotNullBecauseNothingCanBeNullHere() {
        val json = JSONObject("""{"word":"banana","yesish":"yes","numeric":1,"numericText":"1"}""")
        val withNull = JSONObject("""{"nullish":null}""")

        assertEquals(false, json.optBooleanOrNull("word"), "present and unreadable")
        assertEquals(false, json.optBooleanOrNull("yesish"), "present and unreadable")
        assertEquals(false, json.optBooleanOrNull("numeric"), "present and unreadable")
        assertEquals(false, json.optBooleanOrNull("numericText"), "present and unreadable")

        // So the only two ways to get null are the guard's two, and both of
        // them are about the key rather than the value.
        assertNull(json.optBooleanOrNull("missing"), "an absent key")
        assertNull(withNull.optBooleanOrNull("nullish"), "a JSON null")
    }

    @Test
    fun theTwoWaysToGetNullAreBothAboutTheKeyAndNeitherIsAboutTheValue() {
        val json = JSONObject("""{"value":0,"nullish":null}""")

        assertTrue(json.has("value"), "a zero is present, and present is enough")
        assertFalse(json.isNull("value"), "and it is not a JSON null")
        assertEquals(false, json.optBooleanOrNull("value"), "so it is read, as false")
        assertNull(json.optBooleanOrNull("nullish"))
    }
}
