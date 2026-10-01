package com.crispy.tv.backend

import org.json.JSONArray
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
 * The accessor policies in [CrispyBackendJsonExtensions], pinned against the
 * `org.json` the app actually ships.
 *
 * ## Why this is an `androidHostTest` and not a `commonTest`
 *
 * `org.json` is a class of the Android platform. It is not a dependency of this
 * project — there is no version to bump and no artifact to swap — so a
 * `commonMain` file cannot have it, and neither can a `commonTest` compiled for
 * `linuxX64` or either Apple target.
 *
 * That is the ordinary reason, and it is not the interesting one. The
 * interesting one is that **there are two implementations of `org.json` and they
 * disagree on the exact input these policies are written to defend against.**
 *
 * Both were measured on this host:
 *
 * | call | AOSP `libcore/json` (what ships) | `org.json:json` (Maven reference) |
 * |---|---|---|
 * | `optString` of an absent key | `""` | `""` |
 * | `optString` of a `JSONObject.NULL` | **`"null"`** | **`""`** |
 * | `opt` of an absent key | Java `null` | Java `null` |
 * | `opt` of a `JSONObject.NULL` | `JSONObject.NULL` | `JSONObject.NULL` |
 * | `optBoolean` of `"yes"` / `1` / `"1"` | `false` | `false` |
 * | `optBoolean` of `"TRUE"` | `true` | `true` |
 * | `optString` of `5` | `"5"` | `"5"` |
 * | the `Number` type of `1` | `Integer` | `Integer` |
 * | the `Number` type of `1.5` | **`Double`** | **`BigDecimal`** |
 *
 * Two rows matter, and they matter for reasons a reading of the source alone
 * would not separate.
 *
 * The `optString` row is a real divergence: AOSP hands back the four characters
 * `null` for a JSON null and the reference artifact hands back `""`. It is also
 * **invisible to the policy that appears to depend on it**, because
 * `optNullableString` guards with `isNull(key)` and returns before it ever
 * calls `optString`. So the first draft of this file, which said the
 * `equals("null", ignoreCase = true)` branch exists *because* of that
 * divergence, was right about the measurement and wrong about the mechanism.
 *
 * The `1.5` row is a divergence with no such shelter: AOSP gives a `Double`,
 * the reference artifact a `BigDecimal`, and both agree on every whole number.
 * A cast to `Double` works on every device this app ships to and fails against
 * the artifact, and a cast to `BigDecimal` does the reverse -- there is no
 * answer that is right on both, which is the argument for reading every number
 * as `Number`, which is what the `toXOrNull` arms already do.
 *
 * **This suite is therefore written to run under Robolectric**, because a
 * characterisation written against the Maven artifact would pin the *wrong*
 * implementation: the fractional-number row would come back `BigDecimal`, and
 * the suite would be describing an `org.json` no device has.
 *
 * ## The claim this KDoc used to make, and the two corrections it took
 *
 * The first draft asserted that the two implementations disagree on `optString`
 * of a `JSONObject.NULL` -- AOSP giving `null`, the reference `""` -- and that
 * this is why `optNullableString` carries `it.equals("null", ignoreCase =
 * true)`. A mutation run appeared to refute it: deleting the branch failed
 * exactly one case, that case held `{"nullText":"null"}` -- a *string* -- and
 * the case written around a JSON null passed unchanged.
 *
 * Both halves of that inference were wrong, in opposite directions, and the
 * second correction came from a failing assertion rather than from a probe.
 *
 * **The measurement was right.** `optString` of a `JSONObject.NULL` is the four
 * characters `null` on AOSP and `""` on the reference artifact. The
 * "refutation" refuted it by deleting the branch and observing that a JSON-null
 * case survived -- but surviving a mutation is not evidence about `optString`,
 * because the policy never calls it. A test can pass under a mutation for a
 * reason that has nothing to do with the line under test.
 *
 * **The mechanism was wrong.** The branch is not reached by a JSON null. The
 * `!has(key) || isNull(key)` guard one line above returns first, so
 * `optNullableString` answers `null` for a JSON null without consulting
 * `optString` at all. The branch exists for the boring correct reason:
 * **backend payloads really do carry the four characters `null` as a string**,
 * and the check rejects them.
 *
 * What the two mistakes had in common is worth writing down, because it is the
 * shape this file got wrong twice: **a claim about one line, justified by a
 * measurement of a different line.** `AGENTS.md` already records that a KDoc
 * sentence about one caller is not a statement about the function, and that *a
 * caught mutation is evidence that some test caught it, not that the test you
 * would have pointed at is the one that did*. Here the second rule did the
 * correcting, and it corrected a false claim rather than a stale `expect` list
 * -- which is a better use of it than the one it was written for.
 *
 * All three facts are asserted below: the platform's rendering, that the guard
 * is what rejects a JSON null, and that a string reading `null` is what the
 * `equals` branch rejects.
 *
 * The first draft of this KDoc claimed four more divergences than exist: that
 * `optBoolean` answers `true` for `"yes"`, for the integer `1`, or for `"1"`.
 * AOSP's `JSON.toBoolean` is `Boolean` → itself, `String` → case-insensitive
 * `"true"`/`"false"` only, everything else → `null`, and `optBoolean` unwraps
 * that `null` to its `false` default. The Maven artifact agrees on every one of
 * them. **The claims were inferred from memory, measured, and refuted — by
 * both implementations, which is the only reason the refutation is trustworthy.**
 *
 * ## Why `@Config(manifest = Config.NONE)` and `sdk = [35]`
 *
 * No view is inflated and no resource is read, because `android-all` supplies
 * `org.json` whether or not there is a manifest, so `isIncludeAndroidResources`
 * is not wanted here. But `sdk` must be pinned: with `Config.NONE` there is no
 * manifest for Robolectric to read `targetSdk` out of, and it silently falls
 * back to **SDK 21**, which is below this module's `minSdk` of 26.
 *
 * ## The duplication this pins the other side of
 *
 * Five of these policies exist twice. `:backend` declares
 * [toStringMap] `public` and the rest `internal`;
 * `com.crispy.tv.library.LibraryDiskCacheStore` in `:app` carries `private`
 * copies of `optNullableString` and `optBooleanOrNull`, and
 * `com.crispy.tv.sync.ProfileDataShadowStore` in `:app` carries a `private`
 * `toStringMap` and a `private` `toJsonObject`. `:app` is a different module,
 * so `internal` was never visible to it, and it wrote its own — twice.
 *
 * The copies are **not** the same function. Measured differences, all of which
 * this suite pins `:backend`'s side of:
 *
 * 1. `optNullableString` of the literal string `"null"`: here → `null`;
 *    `LibraryDiskCacheStore`'s → `"null"`.
 * 2. `optBooleanOrNull` of a present-but-unparseable value (`"banana"`): here →
 *    `null`; `LibraryDiskCacheStore`'s delegates to `optBoolean`, which cannot
 *    return `null`, so → `false`. **That copy's name promises the `null` its body
 *    cannot deliver.**
 * 3. [toStringMap] trims every value; the `:app` copy does not.
 * 4. [toStringMap] drops blank values; the `:app` copy keeps `""` and `"   "`.
 * 5. `toJsonObject` key order: `ProfileDataShadowStore`'s sorts, this one does
 *    not — and the two take different receiver types, so they are not overloads
 *    of one function but two functions with one name.
 *
 * A sixth finding is not a divergence but a reason: **`optString` cannot
 * distinguish an absent key from a JSON null** on either implementation, which
 * is why every one of these policies carries a `has(key) || isNull(key)` guard
 * and why that guard is load-bearing rather than redundant.
 *
 * `:app` cannot reach this suite — its copies are `private` in another module —
 * so it pins its own side of the same six cases. A silent divergence between two
 * copies of one policy becomes a recorded fact only when both sides are written
 * down.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class JsonAccessorPolicyHostTest {

    // ---------------------------------------------------------------------
    // optNullableString
    // ---------------------------------------------------------------------

    @Test
    fun absentAndJsonNullAreBothAbsentAndBothBlankAreBothTheStringNull() {
        val json = JSONObject("""{"blank":"   ","nullish":null,"nullText":"null","NULLText":"NULL","zero":"0","count":42,"flag":true}""")

        assertNull(json.optNullableString("missing"), "an absent key is not a value")
        assertNull(json.optNullableString("nullish"), "a JSON null is not a value")
        assertNull(json.optNullableString("blank"), "whitespace is not a value")
        assertNull(json.optNullableString("nullText"), "the four characters \"null\" are not a value")
        assertNull(json.optNullableString("NULLText"), "and the check on them is case-insensitive")
    }

    /**
     * The three lines that decide this, and which of them does what.
     *
     * `optNullableString` guards with `!has(key) || isNull(key)` and *then* calls
     * `optString`. A JSON null therefore never reaches `optString` at all --
     * the guard returns first -- which is why deleting the `equals("null",
     * ignoreCase = true)` branch did not fail a case written around a JSON null,
     * and why the first draft of this file misread that as evidence about
     * `optString`.
     *
     * So: the platform's own rendering of a JSON null is the four characters
     * `null`, the guard is what rejects it, and the `equals` branch is carried
     * by a value the guard does not catch -- an actual string that reads `null`.
     */
    @Test
    fun aJsonNullIsRejectedByTheGuardAndTheEqualsBranchIsCarriedByAStringThatReadsNull() {
        val json = JSONObject("""{"nullish":null,"nullText":"null"}""")

        assertEquals(
            "null",
            json.optString("nullish"),
            "what the platform renders for a JSON null; the reference artifact answers empty",
        )
        assertTrue(json.isNull("nullish"), "so the guard is what rejects it, a line before optString")
        assertNull(json.optNullableString("nullish"), "and the answer is still not a value")

        assertFalse(json.isNull("nullText"), "a string that reads null is not a JSON null")
        assertEquals("null", json.optString("nullText"), "so this one does reach optString")
        assertNull(json.optNullableString("nullText"), "and only the equals branch rejects it")
    }

    /**
     * The guard is not over-broad, and this is the case that says so: a value
     * which merely *looks* null-ish is a value. `"0"` is the fixture because it
     * is the string most likely to be caught by a well-meaning "falsy" check,
     * and `0` the number is the one most likely to be caught by a truthiness
     * check.
     */
    @Test
    fun aValueThatMerelyLooksNullishIsStillAValue() {
        val json = JSONObject("""{"zero":"0","count":0,"flag":false}""")

        assertEquals("0", json.optNullableString("zero"))
        assertEquals("0", json.optNullableString("count"))
        assertEquals("false", json.optNullableString("flag"))
    }

    // ---------------------------------------------------------------------
    // optIntOrNull / optDoubleOrNull / optLongOrNull
    // ---------------------------------------------------------------------

    @Test
    fun aNumberArrivesAsANumberAndANumericStringArrivesAsAStringAndBothAreRead() {
        val json = JSONObject("""{"real":42,"padded":" 42 ","word":"banana"}""")

        assertEquals(42, json.optIntOrNull("real"))
        assertEquals(42, json.optIntOrNull("padded"), "the string arm is the live path for JSON numeric strings")
        assertNull(json.optIntOrNull("word"), "and it is the arm that stops garbage being read as 0")
    }

    /**
     * `opt(" 42 ")` is a `String`, not a `Number`, on both implementations —
     * so [optIntOrNull]'s `is Number` arm fires only for real JSON numbers and
     * its `is String` arm does the rest. Both are load-bearing and they are
     * disjoint, which is why this suite keeps the two cases apart.
     */
    @Test
    fun theTwoArmsAreDisjointAndBothAreReachable() {
        val json = JSONObject("""{"real":42,"padded":" 42 "}""")

        assertTrue(json.opt("real") is Number)
        assertTrue(json.opt("padded") is String, "a padded number is a String on both implementations")
    }

    /**
     * `optIntOrNull` of a fractional number **truncates, and does not return
     * `null`.** `org.json` has one number type per parse, so `42.9` reaches the
     * `is Number` arm and `toInt()` is 42. No sibling policy returns `null` for
     * it, and nothing in the repository says so.
     */
    @Test
    fun aFractionalNumberIsSilentlyTruncatedRatherThanRejected() {
        val json = JSONObject("""{"fraction":42.9}""")

        assertEquals(42, json.optIntOrNull("fraction"), "truncation, not null")
        assertNull(json.optDoubleOrNull("missing"), "and null is still available for a key with no value")
        assertEquals(42.9, json.optDoubleOrNull("fraction"), "because the double policy is not the one that truncates")
    }

    /**
     * The two-sentinel case for the numeric path. `opt` returns Java `null` for
     * an absent key but `JSONObject.NULL` for a JSON null, so the `?.let` and
     // the `when`'s `else` arm are each catching a *different* sentinel.
     * Deleting either one changes an answer.
     */
    @Test
    fun theAbsentKeyAndTheJsonNullAreDifferentSentinelsAndBothAreRejected() {
        val json = JSONObject("""{"nullish":null}""")

        assertNull(json.opt("missing"), "absent is Java null")
        assertEquals(JSONObject.NULL, json.opt("nullish"), "a JSON null is the sentinel, not Java null")
        assertNull(json.optIntOrNull("missing"))
        assertNull(json.optIntOrNull("nullish"))
    }

    // ---------------------------------------------------------------------
    // optBooleanOrNull
    // ---------------------------------------------------------------------

    @Test
    fun aBooleanIsReadFromABooleanAndFromTheTwoWordsAndNothingElse() {
        val json = JSONObject("""{"yes":true,"no":false,"upperText":"TRUE","mixedText":"FaLsE"}""")

        assertEquals(true, json.optBooleanOrNull("yes"))
        assertEquals(false, json.optBooleanOrNull("no"))
        assertEquals(true, json.optBooleanOrNull("upperText"), "and the word is matched case-insensitively")
        assertEquals(false, json.optBooleanOrNull("mixedText"))
    }

    /**
     * The behaviour `com.crispy.tv.library.LibraryDiskCacheStore`'s
     * `optBooleanOrNull` cannot reproduce: **a present-but-unparseable value is
     * `null` here and `false` there.** The `:app` copy returns `optBoolean(key)`,
     * which never returns `null`, so its name promises something its body does
     * not deliver. The two answers are indistinguishable for a caller that only
     * branches on truth, which is why the divergence survived.
     *
     * `"yes"`, the integer `1` and the string `"1"` were all claimed to be
     * divergences here. They are not: `optBoolean` accepts only real `Boolean`s
     * and the two words, and answers `false` to everything else, on both
     * implementations. Measured, not assumed — see the class KDoc.
     */
    @Test
    fun aPresentButUnparseableBooleanIsNullRatherThanFalse() {
        val json = JSONObject("""{"word":"banana","yesish":"yes","numeric":1,"numericText":"1"}""")
        val withNull = JSONObject("""{"nullish":null}""")

        assertNull(json.optBooleanOrNull("word"))
        assertNull(json.optBooleanOrNull("yesish"))
        assertNull(json.optBooleanOrNull("numeric"))
        assertNull(json.optBooleanOrNull("numericText"))
        assertNull(json.optBooleanOrNull("missing"))
        assertNull(withNull.optBooleanOrNull("nullish"))
    }

    // ---------------------------------------------------------------------
    // optStringList / optIntList
    // ---------------------------------------------------------------------

    @Test
    fun aStringListTrimsEachEntryAndDropsTheBlankOnes() {
        val json = JSONObject("""{"items":[" a ","","   ","b",5]}""")

        assertEquals(listOf("a", "b", "5"), json.optStringList("items"), "a number stringifies; a blank does not survive")
        assertEquals(emptyList(), json.optStringList("missing"))
        assertEquals(emptyList(), JSONObject("""{"items":null}""").optStringList("items"))
    }

    @Test
    fun anIntListDropsWhatItCannotReadRatherThanFailingTheWholeList() {
        val json = JSONObject("""{"items":[1,"2","x",3.7]}""")

        assertEquals(listOf(1, 2, 3), json.optIntList("items"), "3.7 truncates like optIntOrNull does")
        assertEquals(emptyList(), JSONObject("""{"items":["x"]}""").optIntList("items"))
        assertEquals(emptyList(), json.optIntList("missing"))
    }

    // ---------------------------------------------------------------------
    // toStringMap
    // ---------------------------------------------------------------------

    @Test
    fun toAStringMapTrimsEveryValueAndDropsTheBlankOnes() {
        val json = JSONObject("""{"padded":" x ","empty":"","spaces":"   ","count":5,"flag":true}""")

        assertEquals(mapOf("padded" to "x", "count" to "5", "flag" to "true"), json.toStringMap())
    }

    /**
     * `com.crispy.tv.sync.ProfileDataShadowStore`'s `toStringMap` does neither of
     * those two things, which is why the two copies disagree about `""` and
     * about surrounding whitespace on the same input.
     */
    @Test
    fun toAStringMapPreservesDocumentOrderBecauseItIsALinkedMap() {
        val json = JSONObject("""{"z":"1","a":"2","m":"3"}""")

        assertEquals(listOf("z", "a", "m"), json.toStringMap().keys.toList())
    }

    @Test
    fun toAStringMapRejectsBothSentinelsAndBothBlanksAndSkipsEverythingElse() {
        val json = JSONObject("""{"nullish":null,"nested":{"n":1},"list":[1,"two"]}""")

        assertEquals(
            mapOf("nested" to """{"n":1}""", "list" to """[1,"two"]"""),
            json.toStringMap(),
            "a container falls to the else arm and is stringified whole",
        )
        assertEquals(emptyMap(), JSONObject("""{"a":null}""").toStringMap(), "a JSON null is skipped, not stringified as \"null\"")
        assertEquals(emptyMap(), (null as JSONObject?).toStringMap(), "and a null receiver is empty, not a crash")
    }

    // ---------------------------------------------------------------------
    // the neutral model: toAnyMap / toAnyList / toKotlinValue
    // ---------------------------------------------------------------------

    /**
     * **The number type a JSON number arrives as is width-dependent, and it is
     * not `Double` on either implementation.**
     *
     * This case was written asserting `Double`, from a claim that "`org.json`
     * has one number type per parse". That was inferred, and it is wrong on
     * both implementations — the first run of this suite failed all three of
     * its number assertions. Measured:
     *
     * | JSON | `org.json:json` (Maven reference) | AOSP (what ships) |
     * |---|---|---|
     * | `1` | `Integer` | `Integer` |
     * | `1.5` | **`BigDecimal`** | **`Double`** |
     * | `-7` | `Integer` | `Integer` |
     * | `3000000000` | `Long` | `Long` |
     *
     * So the two implementations agree on every whole number and **disagree on
     * exactly the fractional ones.** A caller that reads the neutral model and
     * casts to `Double` works on every device and **fails against the reference
     * artifact**; one that casts to `BigDecimal` does the reverse. Neither
     * compiles into a common answer, which is the argument for reading these
     * as `Number` — which is what the two `toXOrNull` arms above do, and the
     * only reason they were portable before anyone measured this.
     */
    @Test
    fun everyJsonNumberArrivesAsAWidthDependentTypeAndFractionalIsWhereTheTwoDisagree() {
        val json = JSONObject("""{"whole":1,"fractional":1.5,"neg":-7,"big":3000000000}""")

        assertEquals("Integer", json.opt("whole")!!.javaClass.simpleName)
        assertEquals("Integer", json.opt("neg")!!.javaClass.simpleName)
        assertEquals("Long", json.opt("big")!!.javaClass.simpleName)
        assertEquals("Double", json.opt("fractional")!!.javaClass.simpleName, "BigDecimal on the reference artifact")

        assertEquals(1, json.toAnyMap()["whole"], "and 1 stayed 1 rather than becoming 1.0")
    }

    /**
     * The limitation of the neutral model, pinned: **it collapses a JSON null
     * and an absent key into the same `null`.** `optNullableString` above
     * distinguishes the two, and it can only do that because it holds an
     * `org.json` node where the sentinel survives. Once a value has crossed
     * `toAnyMap`, that distinction is gone.
     */
    @Test
    fun theNeutralModelCollapsesAJsonNullAndAnAbsentKeyIntoTheSameNull() {
        val json = JSONObject("""{"nullish":null}""")

        assertEquals(mapOf("nullish" to null), json.toAnyMap())
        assertTrue("nullish" in json.toAnyMap(), "so the key is still present, even though its value is not")
        assertTrue("missing" !in json.toAnyMap(), "and an absent key is simply not there")
    }

    @Test
    fun theNeutralModelIsOrderedAndRecursive() {
        val json = JSONObject("""{"z":1,"a":{"inner":[1,"two",null]}}""")

        assertEquals(listOf("z", "a"), json.toAnyMap().keys.toList())
        assertEquals(
            mapOf("inner" to listOf<Any?>(1, "two", null)),
            json.toAnyMap()["a"],
        )
        assertEquals(listOf<Any?>(1, "two", null), JSONArray("""[1,"two",null]""").toAnyList())
    }

    /**
     * `toAnyList` reads with `opt`, not `get`, so **a gap in the array yields
     * `null` rather than throwing** — an array of three with a hole is a list of
     * three with a `null` in the middle. There is no way to build one through
     * the JSON parser, so the assertion is about the read path, not about
     * parseable input.
     */
    @Test
    fun aGapInAnArrayReadsAsNullRatherThanThrowing() {
        val array = JSONArray()
        array.put("first")
        array.put(JSONObject.NULL)
        array.put("third")

        assertEquals(listOf("first", null, "third"), array.toAnyList())
    }

    // ---------------------------------------------------------------------
    // encoding
    // ---------------------------------------------------------------------

    /**
     * `toJsonValue` skips any key of a `Map` that is not a `String`, because a
     * `JSONObject` can only be keyed by one. A caller passing a
     * `Map<String, Any?>` with a wider key type loses entries silently, which is
     * why this is pinned rather than left to the `when`.
     */
    /**
     * The branch is in [toJsonValue], not in [toJsonObject] — a first draft of
     * this case asserted it about `toJsonObject` and the compiler refused,
     * because `toJsonObject` takes a `Map<String, Any?>` and a map with a wider
     * key type is not one. So `toJsonObject` cannot lose a key: there is no such
     * key. `toJsonValue` is reached with a `Map<*, *>`, and that is where a
     * non-`String` key can arrive and be dropped.
     */
    @Test
    fun encodingSkipsAnyMapKeyThatIsNotAString() {
        val encoded = mapOf<Any?, Any?>("kept" to 1, 7 to "dropped").toJsonValue()

        assertTrue(encoded is JSONObject, "a Map<*, *> becomes an object; this is the arm under test")
        val obj = encoded as JSONObject
        assertEquals(1, obj.getInt("kept"))
        assertEquals(1, obj.length(), "the non-String key did not become a key")
    }

    @Test
    fun encodingRoundTripsThroughTheNeutralModel() {
        val original = mapOf("a" to 1, "b" to "two", "c" to listOf(1, 2), "d" to null)

        assertEquals(
            mapOf("a" to 1, "b" to "two", "c" to listOf<Any?>(1, 2), "d" to null),
            original.toJsonObject().toAnyMap(),
        )
    }

    @Test
    fun aNullValueEncodesAsTheSentinelAndDecodesBackToNull() {
        assertEquals(JSONObject.NULL, null.toJsonValue(), "not Kotlin null: that would drop the key")
        assertEquals(JSONObject.NULL, JSONObject.NULL.toJsonValue())
        assertNull(JSONObject.NULL.toKotlinValue())
        assertNull(null.toKotlinValue())
    }
}
