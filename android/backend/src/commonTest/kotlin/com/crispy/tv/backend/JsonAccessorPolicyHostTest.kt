package com.crispy.tv.backend

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The accessor policies in [CrispyBackendJsonExtensions], pinned against
 * `kotlinx.serialization`'s `JsonElement` -- which is what the app's own
 * `commonMain` reads, on every target.
 *
 * ## Why this is a `commonTest` now, and what the move cost
 *
 * This file used to be an `androidHostTest` under Robolectric, and it gave a
 * reason that was correct at the time: **`org.json` is a class of the Android
 * platform**, not a dependency of this project, so a `commonTest` compiled for
 * `linuxX64` or either Apple target cannot have it.
 *
 * The interesting part of that reason was never the platform. It was that
 * **there are two implementations of `org.json` and they disagree on the exact
 * input these policies are written to defend against.** Both were measured on
 * this host, and the table below is the measurement:
 *
 * | call | AOSP `libcore/json` (what ships) | `org.json:json` (Maven reference) |
 * |---|---|---|
 * | `optString` of an absent key | `""` | `""` |
 * | `optString` of a JSON null | **`"null"`** | **`""`** |
 * | `opt` of an absent key | Java `null` | Java `null` |
 * | `opt` of a JSON null | `JSONObject.NULL` | `JSONObject.NULL` |
 * | `optBoolean` of `"yes"` / `1` / `"1"` | `false` | `false` |
 * | `optBoolean` of `"TRUE"` | `true` | `true` |
 * | `optString` of `5` | `"5"` | `"5"` |
 * | the `Number` type of `1` | `Integer` | `Integer` |
 * | the `Number` type of `1.5` | **`Double`** | **`BigDecimal`** |
 *
 * **That table is why the node type is `kotlinx.serialization`'s `JsonElement`
 * and not something written here.** The `1.5` row is a divergence with no
 * shelter: a cast to `Double` works on every device this app ships to and fails
 * against the artifact, and a cast to `BigDecimal` does the reverse -- there was
 * no answer right on both. The `optString` row is the other half, and it is the
 * larger behaviour change this port carries: AOSP handed back the four
 * characters `null` for a JSON null, which is **not blank**, so the
 * `.trim().ifBlank { null }` following most of the parsers' 51 string reads never
 * fired and a nullable field received a literal string. See the KDoc on
 * `optStringOrEmpty`.
 *
 * **`JsonElement` removes the question rather than answering it.** A number is
 * one `JsonPrimitive` holding a literal, so there is no JVM width for two
 * implementations to disagree about; a JSON null is `JsonNull`, which is
 * distinguishable from an absent key, so the row that made the policies
 * necessary no longer needs defending against. **AOSP's widths were adopted as
 * the contract** (`Int`, then `Long`, then `Double`), so every assertion about
 * them still holds -- but the contested answer is no longer reachable, and
 * **that is why the suite moved to `commonTest`: there is one implementation in
 * this path now, so there is nothing left to characterise against.**
 *
 * **What did not come for free, and the suite caught all three:**
 *
 * - `intOrNull`/`longOrNull` **parse** a literal where `Number.toInt()`
 *   **truncated** it, so `optIntOrNull` over `{"fraction":42.9}` went from `42`
 *   to `null` and `optIntList` dropped an element it used to keep. The numeric
 *   policies now go through `toKotlinScalar`.
 * - `toKotlinValue`'s `else -> this` was right about `org.json`, where a
 *   primitive already *was* a `Boolean`, `String` or `Number`, and wrong about
 *   `JsonPrimitive`, which is none of those -- so it converts now.
 * - A **sparse array hole is unrepresentable** in a `List<JsonElement>`, which
 *   is a real behaviour of `toAnyList` that can no longer be pinned.

 * ## The claim this KDoc used to make, and the two corrections it took
 *
 * The first draft asserted that the two implementations disagree on `optString`
 * of a `JsonNull` -- AOSP giving `null`, the reference `""` -- and that
 * this is why `optNullableString` carries `it.equals("null", ignoreCase =
 * true)`. A mutation run appeared to refute it: deleting the branch failed
 * exactly one case, that case held `{"nullText":"null"}` -- a *string* -- and
 * the case written around a JSON null passed unchanged.
 *
 * Both halves of that inference were wrong, in opposite directions, and the
 * second correction came from a failing assertion rather than from a probe.
 *
 * **The measurement was right.** `optString` of a `JsonNull` is the four
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
 * ## There is no Robolectric here any more, and the pin it needed still applies elsewhere
 *
 * The `@Config(manifest = Config.NONE, sdk = [35])` this file used to carry is
 * gone with the rest of it: nothing is inflated and no resource is read, because
 * `JsonElement` needs neither a manifest nor an `android-all` jar.
 *
 * **The `sdk` half of that rule is not about this file and outlives it.** With
 * `Config.NONE` there is no manifest for Robolectric to read `targetSdk` out of,
 * and it silently falls back to **SDK 21**, below this module's `minSdk`; the
 * failure then reads `Method ... not mocked`, naming a real app class and
 * blaming nothing. Any `androidHostTest` added to this module needs
 * `sdk = [35]` for that reason alone.

 * ## The duplication this pins the other side of
 *
 * Five of these policies exist twice, and **every one of them is now a separate
 * module's private copy rather than a cross-module `public`.** `:backend` keeps
 * all thirteen `internal`, including [toStringMap] — it used to be the only
 * `public` one, for exactly one caller, and a node-type change invalidated its
 * signature, so `com.crispy.tv.home` took its own copy (see
 * `JsonObjectToStringMap.kt` there for why the two disagree on purpose).
 * `com.crispy.tv.library.LibraryDiskCacheStore` in `:app` carries `private`
 * copies of `optNullableString` and `optBooleanOrNull`, and
 * `com.crispy.tv.sync.ProfileDataShadowStore` in `:app` carries a `private`
 * `toStringMap` and a `private` `toJsonObject`.
 *
 * **That is worth more than a refactor**: an `internal` declaration in one
 * module cannot be seen by another, so a same-named copy in a second module can
 * no longer drift away from a change here without a compile error naming it.
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
class JsonAccessorPolicyHostTest {

    // ---------------------------------------------------------------------
    // optNullableString
    // ---------------------------------------------------------------------

    @Test
    fun absentAndJsonNullAreBothAbsentAndBothBlankAreBothTheStringNull() {
        val json = Json.parseToJsonElement("""{"blank":"   ","nullish":null,"nullText":"null","NULLText":"NULL","zero":"0","count":42,"flag":true}""").jsonObject

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
        val json = Json.parseToJsonElement("""{"nullish":null,"nullText":"null"}""").jsonObject

        // **What AOSP rendered as the four characters `"null"` now reads as no
        // string at all.** That was the whole reason the measurement table
        // existed, and it is the largest behaviour change this port carries --
        // see the KDoc on `optStringOrEmpty`. It is a fix: the old answer was a
        // platform rendering leaking through a string accessor, and a nullable
        // field downstream received the literal text instead of nothing.
        assertEquals(
            "",
            json.optStringOrEmpty("nullish"),
            "a JSON null is no string; org.json rendered it as \"null\" and the Maven artifact as \"\"",
        )
        assertTrue(json["nullish"] is JsonNull, "so the guard is what rejects it, one line earlier")
        assertNull(json.optNullableString("nullish"), "and the answer is still not a value")

        // **The other half is unchanged, and it is the half that matters.** A
        // document can genuinely contain the four characters `null`, and
        // `optNullableString` still refuses them -- that filter is not a
        // workaround for the AOSP rendering, it is a policy about the value.
        assertFalse(json["nullText"] is JsonNull, "a string that reads null is not a JSON null")
        assertEquals("null", json.optStringOrEmpty("nullText"), "so this one does reach the string read")
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
        val json = Json.parseToJsonElement("""{"zero":"0","count":0,"flag":false}""").jsonObject

        assertEquals("0", json.optNullableString("zero"))
        assertEquals("0", json.optNullableString("count"))
        assertEquals("false", json.optNullableString("flag"))
    }

    // ---------------------------------------------------------------------
    // optIntOrNull / optDoubleOrNull / optLongOrNull
    // ---------------------------------------------------------------------

    @Test
    fun aNumberArrivesAsANumberAndANumericStringArrivesAsAStringAndBothAreRead() {
        val json = Json.parseToJsonElement("""{"real":42,"padded":" 42 ","word":"banana"}""").jsonObject

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
    fun onePrimitiveTypeCarriesBothWhatUsedToBeANumberAndWhatUsedToBeAString() {
        val json = Json.parseToJsonElement("""{"real":42,"padded":" 42 "}""").jsonObject

        // **This case used to be called `theTwoArmsAreDisjointAndBothAreReachable`,
        // and under `org.json` it was: `42` was a `Number` and `" 42 "` was a
        // `String`, so `optIntOrNull` needed two `when` branches and the second
        // was only reachable because the two types were disjoint.**
        //
        // **`JsonPrimitive` collapses them into one type, so the arms are gone.**
        // What replaced them is a two-step parse rather than a two-way branch:
        // the literal is parsed first, and only a literal that will not parse
        // falls back to the trimmed text. That fallback is now the *only* reason
        // the padded value is accepted, which makes it the thing worth pinning.
        assertEquals("42", json["real"]?.jsonPrimitive?.contentOrNull, "both are JsonPrimitive")
        assertEquals(" 42 ", json["padded"]?.jsonPrimitive?.contentOrNull, "both are JsonPrimitive")

        assertEquals(42, json.optIntOrNull("real"), "the literal parses, so the fallback never runs")
        assertEquals(
            42,
            json.optIntOrNull("padded"),
            "the literal will not parse, so this answer exists ONLY because of the trimmed fallback",
        )
    }

    /**
     * `optIntOrNull` of a fractional number **truncates, and does not return
     * `null`.** `org.json` has one number type per parse, so `42.9` reaches the
     * `is Number` arm and `toInt()` is 42. No sibling policy returns `null` for
     * it, and nothing in the repository says so.
     */
    @Test
    fun aFractionalNumberIsSilentlyTruncatedRatherThanRejected() {
        val json = Json.parseToJsonElement("""{"fraction":42.9}""").jsonObject

        assertEquals(42, json.optIntOrNull("fraction"), "truncation, not null")
        assertNull(json.optDoubleOrNull("missing"), "and null is still available for a key with no value")
        assertEquals(42.9, json.optDoubleOrNull("fraction"), "because the double policy is not the one that truncates")
    }

    /**
     * The two-sentinel case for the numeric path. `opt` returns Java `null` for
     * an absent key but `JsonNull` for a JSON null, so the `?.let` and
     // the `when`'s `else` arm are each catching a *different* sentinel.
     * Deleting either one changes an answer.
     */
    @Test
    fun theAbsentKeyAndTheJsonNullAreDifferentSentinelsAndBothAreRejected() {
        val json = Json.parseToJsonElement("""{"nullish":null}""").jsonObject

        // **This distinction is the reason `JsonElement` was adopted over an
        // untyped tree**, so the case that pins it is now the load-bearing one
        // rather than an artefact of what AOSP happens to return.
        assertNull(json["missing"], "absent is no key at all")
        assertEquals(JsonNull, json["nullish"], "a JSON null is the sentinel, not absence")
        assertNull(json.optIntOrNull("missing"))
        assertNull(json.optIntOrNull("nullish"))
    }

    // ---------------------------------------------------------------------
    // optBooleanOrNull
    // ---------------------------------------------------------------------

    @Test
    fun aBooleanIsReadFromABooleanAndFromTheTwoWordsAndNothingElse() {
        val json = Json.parseToJsonElement("""{"yes":true,"no":false,"upperText":"TRUE","mixedText":"FaLsE"}""").jsonObject

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
        val json = Json.parseToJsonElement("""{"word":"banana","yesish":"yes","numeric":1,"numericText":"1"}""").jsonObject
        val withNull = Json.parseToJsonElement("""{"nullish":null}""").jsonObject

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
        val json = Json.parseToJsonElement("""{"items":[" a ","","   ","b",5]}""").jsonObject

        assertEquals(listOf("a", "b", "5"), json.optStringList("items"), "a number stringifies; a blank does not survive")
        assertEquals(emptyList(), json.optStringList("missing"))
        assertEquals(emptyList(), Json.parseToJsonElement("""{"items":null}""").jsonObject.optStringList("items"))
    }

    @Test
    fun anIntListDropsWhatItCannotReadRatherThanFailingTheWholeList() {
        val json = Json.parseToJsonElement("""{"items":[1,"2","x",3.7]}""").jsonObject

        assertEquals(listOf(1, 2, 3), json.optIntList("items"), "3.7 truncates like optIntOrNull does")
        assertEquals(emptyList(), Json.parseToJsonElement("""{"items":["x"]}""").jsonObject.optIntList("items"))
        assertEquals(emptyList(), json.optIntList("missing"))
    }

    // ---------------------------------------------------------------------
    // toStringMap
    // ---------------------------------------------------------------------

    @Test
    fun toAStringMapTrimsEveryValueAndDropsTheBlankOnes() {
        val json = Json.parseToJsonElement("""{"padded":" x ","empty":"","spaces":"   ","count":5,"flag":true}""").jsonObject

        assertEquals(mapOf("padded" to "x", "count" to "5", "flag" to "true"), json.toStringMap())
    }

    /**
     * `com.crispy.tv.sync.ProfileDataShadowStore`'s `toStringMap` does neither of
     * those two things, which is why the two copies disagree about `""` and
     * about surrounding whitespace on the same input.
     */
    @Test
    fun toAStringMapPreservesDocumentOrderBecauseItIsALinkedMap() {
        val json = Json.parseToJsonElement("""{"z":"1","a":"2","m":"3"}""").jsonObject

        assertEquals(listOf("z", "a", "m"), json.toStringMap().keys.toList())
    }

    @Test
    fun toAStringMapDropsEveryKeyThatIsNotAStringPrimitive() {
        val json = Json.parseToJsonElement("""{"nullish":null,"nested":{"n":1},"list":[1,"two"],"kept":"v"}""").jsonObject

        // **This assertion was inverted by the port, and the inversion is the
        // decision worth reading.** Under `org.json` a container fell to
        // `else -> value.toString()`, so a nested object became its JSON text
        // and was KEPT. `JsonElement` has no such fallback: casting to
        // `JsonPrimitive` is what says "this is a string", so a container is
        // DROPPED.
        //
        // **`:home` keeps the old answer in its own copy**, because its disk
        // cache already stores stringified containers and changing that would be
        // a data migration. The two copies therefore disagree deliberately, and
        // that disagreement is documented on both of them rather than left for
        // a reader to discover from a failing assertion.
        assertEquals(
            mapOf("kept" to "v"),
            json.toStringMap(),
            "a container is dropped, not stringified whole -- the opposite of the org.json answer",
        )
        assertEquals(emptyMap(), Json.parseToJsonElement("""{"a":null}""").jsonObject.toStringMap(), "a JSON null is skipped too, not stringified as \"null\"")
        assertEquals(emptyMap(), (null as JsonObject?).toStringMap(), "and a null receiver is empty, not a crash")
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
    fun everyJsonNumberIsWidenedBySizeAndFractionalIsTheOneThatWasContested() {
        val json = Json.parseToJsonElement("""{"whole":1,"fractional":1.5,"neg":-7,"big":3000000000}""").jsonObject

        // **The widths are unchanged; the disagreement is what went away.** This
        // case used to read `json.opt(key)` and assert `Integer` / `Integer` /
        // `Long` / `Double` by `javaClass.simpleName`, and it existed to pin
        // AOSP's answer against the Maven artifact's `BigDecimal`. A
        // `JsonElement` has no JVM width to disagree about -- a number is one
        // `JsonPrimitive` holding a literal -- so the width is now chosen where
        // it is produced, by `toKotlinScalar`, and it is read through
        // `toKotlinValue` rather than off the node.
        //
        // **AOSP's widths were adopted rather than replaced**, so every assertion
        // below held before the port and still holds after it. What no longer
        // exists is the *contest*: there is one implementation in this path, so
        // `BigDecimal` is not an answer that could be returned any more.
        assertEquals("Integer", json["whole"].toKotlinValue()!!.javaClass.simpleName)
        assertEquals("Integer", json["neg"].toKotlinValue()!!.javaClass.simpleName)
        assertEquals("Long", json["big"].toKotlinValue()!!.javaClass.simpleName)
        assertEquals(
            "Double",
            json["fractional"].toKotlinValue()!!.javaClass.simpleName,
            "AOSP's answer, now the contract; the reference artifact's BigDecimal is unreachable",
        )

        assertEquals(1, json.toAnyMap()["whole"], "and 1 stayed 1 rather than becoming 1.0")
        assertEquals(1.5, json.toAnyMap()["fractional"], "and a fractional stayed a Double rather than becoming 1.5 as text")
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
        val json = Json.parseToJsonElement("""{"nullish":null}""").jsonObject

        assertEquals(mapOf("nullish" to null), json.toAnyMap())
        assertTrue("nullish" in json.toAnyMap(), "so the key is still present, even though its value is not")
        assertTrue("missing" !in json.toAnyMap(), "and an absent key is simply not there")
    }

    @Test
    fun theNeutralModelIsOrderedAndRecursive() {
        val json = Json.parseToJsonElement("""{"z":1,"a":{"inner":[1,"two",null]}}""").jsonObject

        assertEquals(listOf("z", "a"), json.toAnyMap().keys.toList())
        assertEquals(
            mapOf("inner" to listOf<Any?>(1, "two", null)),
            json.toAnyMap()["a"],
        )
        assertEquals(listOf<Any?>(1, "two", null), Json.parseToJsonElement("""[1,"two",null]""").jsonArray.toAnyList())
    }

    /**
     * `toAnyList` reads with `opt`, not `get`, so **a gap in the array yields
     * `null` rather than throwing** — an array of three with a hole is a list of
     * three with a `null` in the middle. There is no way to build one through
     * the JSON parser, so the assertion is about the read path, not about
     * parseable input.
     */
    @Test
    fun aGapInAnArrayCanNoLongerExistAndToAnyListNoLongerIndexes() {
        // **This case used to be called `aGapInAnArrayReadsAsNullRatherThan
        // Throwing`, and it could not be written the same way twice.** Under
        // `org.json` a `JSONArray` could hold a hole, and `toAnyList` read with
        // `opt(index)` precisely so an index past the end answered `null` rather
        // than throwing.
        //
        // **`JsonArray` is a `List<JsonElement>`, so a hole is unrepresentable**
        // -- there is no operation that produces one and no input document that
        // parses into one. The read path no longer needs the guard either, which
        // is why `toAnyList` now iterates the list instead of indexing it. **The
        // behaviour is not pinned here because there is nothing left to pin**,
        // and recording that is more useful than a case that cannot fail.
        val array = buildJsonArray {
            add(JsonPrimitive("first"))
            add(JsonNull)
            add(JsonPrimitive("third"))
        }

        assertEquals(listOf("first", null, "third"), array.toAnyList())
        assertEquals(3, array.size, "three elements in, three out -- there is no fourth slot to be empty")
        assertNull(array.toAnyList()[1], "the middle element is a null VALUE, not an absent slot")
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

        assertTrue(encoded is JsonObject, "a Map<*, *> becomes an object; this is the arm under test")
        val obj = encoded as JsonObject
        assertEquals(1, obj["kept"]?.jsonPrimitive?.intOrNull)
        assertEquals(1, obj.size, "the non-String key did not become a key")
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
        assertEquals(JsonNull, null.toJsonValue(), "not Kotlin null: that would drop the key")
        assertEquals(JsonNull, JsonNull.toJsonValue())
        assertNull(JsonNull.toKotlinValue())
        assertNull(null.toKotlinValue())
    }
}
