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
     * **This case asserted the opposite until the fix, and the name asserted it
     * too** — `…IsFalseAndNotNullBecauseNothingCanBeNullHere` claimed the defect
     * was correct. A name narrower or wider than its body is the same defect
     * twice, so both moved.
     *
     * The old body delegated to `org.json`'s `optBoolean`, which returns the
     * `Boolean` or its `false` default and can never return `null`. All four
     * fixtures below therefore read `false` from a function whose return type
     * is `Boolean?`, and `:backend` answers `null` to all four. That is not a
     * cross-implementation difference — `optBoolean` is strict on **both** — it
     * was this copy discarding the difference itself, which is the worse of the
     * two failures because it leaves no trace in the value.
     *
     * All four are now `null`, and the shape worth noticing is that the
     * *string* `"1"` and the *number* `1` are both unreadable as booleans. A
     * reader expecting `1` to be truthy gets the same answer from both, which
     * is the correct one: neither is a rating.
     */
    @Test
    fun aPresentButUnreadableBooleanIsNullBecauseTheReturnTypeIsNamedForNullable() {
        val json = JSONObject("""{"word":"banana","yesish":"yes","numeric":1,"numericText":"1"}""")
        val withNull = JSONObject("""{"nullish":null}""")

        assertNull(json.optBooleanOrNull("word"), "present and unreadable")
        assertNull(json.optBooleanOrNull("yesish"), "present and unreadable")
        assertNull(json.optBooleanOrNull("numeric"), "present and unreadable, and 1 is not a rating")
        assertNull(json.optBooleanOrNull("numericText"), "and the string 1 is not either")

        // The three ways to get null are all answered now, and none of them is
        // a special case in the body.
        assertNull(json.optBooleanOrNull("missing"), "an absent key")
        assertNull(withNull.optBooleanOrNull("nullish"), "a JSON null")
    }

    /**
     * The old name here was `theTwoWaysToGetNullAreBothAboutTheKeyAndNeitherIs
     * AboutTheValue`, and its thesis is now false: a value that is neither a
     * boolean nor one of the two words is a third way to get `null`, and it is
     * the only one of the three that is about the value.
     */
    @Test
    fun theThreeWaysToGetNullAreAbsentAJsonNullAndAValueThatIsNotABoolean() {
        val json = JSONObject("""{"value":0,"no":false,"nullish":null,"word":"banana"}""")

        assertTrue(json.has("value"), "a zero is present, and present is enough")
        assertFalse(json.isNull("value"), "and it is not a JSON null")
        // The old case read this key as `false` and said "so it is read, as
        // false". Under the fixed policy a zero is a *number*, and the policy
        // is about the value rather than the key, so it is the third way to
        // get null. **Being present was never the question** -- that was the
        // assumption the removed guard encoded, and the fix takes it out.
        assertNull(json.optBooleanOrNull("value"), "a zero is present, but a number is not a boolean")
        assertEquals(false, json.optBooleanOrNull("no"), "while a real false is still false")
        assertNull(json.optBooleanOrNull("nullish"), "a JSON null")
        assertNull(json.optBooleanOrNull("word"), "and an unreadable value, which is the third")
    }

    /**
     * The reason the fix is worth making, and the reason the previous answer
     * was a defect rather than a policy difference: **`null` and `false` reach
     * different pixels.**
     *
     * Every consumer of `CatalogItem.liked` branches two ways and never three.
     * `DetailsHeader` does `selected = liked == true` and `selected = liked ==
     * false` for the like and dislike buttons, so a `false` renders the dislike
     * button *pressed*. `LibraryScreen` filters
     * `RATING_BAND_DISLIKED -> items.filter { it.liked == false }`, so the item
     * lands in the Disliked band. `DetailScreen` labels the action
     * `"Disliked"` rather than `"Dislike"` on the same comparison.
     *
     * So before the fix, a cached `liked` that was a string or a number — which
     * `LibraryDiskCacheStore`'s own writer never produces, since it writes
     * `liked ?: JSONObject.NULL` — was reported as a positive claim about the
     * user's own data: liked-nothing, actively disliked. It is now no rating at
     * all, which is what every other path already does for an absent one.
     *
     * Pinned at the accessor because that is where the answer is produced; the
     * consumers are the reason it matters, and they are not what this function
     * decides.
     */
    @Test
    fun nullAndFalseAreDifferentAnswersAndTheConsumersBranchOnTheDifference() {
        val explicitFalse = JSONObject("""{"liked":false}""")
        val unreadable = JSONObject("""{"liked":"banana"}""")
        val absent = JSONObject("""{}""")

        assertEquals(false, explicitFalse.optBooleanOrNull("liked"), "the document said false")
        assertNull(unreadable.optBooleanOrNull("liked"), "the document said nothing usable")
        assertNull(absent.optBooleanOrNull("liked"), "the document said nothing at all")

        // The distinction, stated as the two-way comparison every consumer
        // actually writes. `liked == false` is what selects Disliked, so an
        // unreadable value must not satisfy it.
        // The first draft of these two asserted the comparison itself with
        // `assertNull`. That compiled, because `assertNull` takes `Any?`, and
        // then failed: `null == false` is Kotlin `false`, a `Boolean`, and
        // never a `null`. **An assertion whose type contradicts its intent
        // still compiles**, so the compiler was no help here and the failure
        // was the only witness.
        assertEquals(false, unreadable.optBooleanOrNull("liked") == false, "and so it is not a dislike")
        assertEquals(true, explicitFalse.optBooleanOrNull("liked") == false, "while a real false is")
    }
}
