package com.crispy.tv.addons

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Every policy in `JsonAccessors.kt`, driven directly.
 *
 * **These eight accessors had no coverage at all until the file moved to
 * `commonMain`.** `internal` is module-scoped, so from `androidMain` the only
 * source set that could name them was `androidHostTest`, and no one had
 * written one; the ninety-odd call sites in `AddonStreamsService.kt`,
 * `RemoteMetadataLabDataSource.kt` and `MetadataAddonRegistry.kt` reached the
 * policies by accident, each through whichever of the three consumers happened
 * to need one. **So a policy could have been wrong in all three files at once
 * and every suite would have passed.**
 *
 * Fixtures are parsed from JSON text rather than built from node constructors,
 * because the question under test is what the accessor does with a node
 * **arrived at by parsing**, and a hand-built `JsonPrimitive("42")` does not
 * distinguish itself from a number in the same way a parsed literal does.
 */
class JsonAccessorsTest {

    private fun obj(json: String) = Json.parseToJsonElement(json) as JsonObject

    private fun objOf(vararg pairs: Pair<String, String>): JsonObject =
        Json.parseToJsonElement(pairs.joinToString(",", "{", "}") { "\"${it.first}\":${it.second}" })
            as JsonObject

    // ---------------------------------------------------------------- jsonPrimitiveOrNull

    @Test
    fun anAbsentKeyIsNotAPrimitive() {
        assertNull(obj("{}").jsonPrimitiveOrNull("k"), "absent key")
    }

    @Test
    fun aJsonNullIsNotAPrimitive() {
        assertNull(objOf("k" to "null").jsonPrimitiveOrNull("k"), "stored null")
    }

    @Test
    fun aContainerIsNotAPrimitive() {
        val subject = objOf("k" to "{\"a\":1}")
        assertNull(subject.jsonPrimitiveOrNull("k"), "object")
        assertNull(objOf("k" to "[1]").jsonPrimitiveOrNull("k"), "array")
    }

    @Test
    fun everyScalarKindIsAPrimitive() {
        val subject = objOf("s" to "\"x\"", "n" to "1", "t" to "true")
        assertEquals("x", subject.jsonPrimitiveOrNull("s")?.contentOrNull, "string")
        assertEquals("1", subject.jsonPrimitiveOrNull("n")?.contentOrNull, "number")
        assertEquals("true", subject.jsonPrimitiveOrNull("t")?.contentOrNull, "boolean")
    }

    // ---------------------------------------------------------------- optStringOrEmpty

    @Test
    fun absentAndJsonNullBothReadAsEmpty() {
        assertEquals("", obj("{}").optStringOrEmpty("k"), "absent")
        assertEquals("", objOf("k" to "null").optStringOrEmpty("k"), "stored null")
    }

    @Test
    fun scalarsAreReadAsTheirJsonText() {
        val subject = objOf("s" to "\"x\"", "n" to "42", "f" to "42.9", "t" to "true")
        assertEquals("x", subject.optStringOrEmpty("s"), "string")
        assertEquals("42", subject.optStringOrEmpty("n"), "whole number")
        assertEquals("42.9", subject.optStringOrEmpty("f"), "fraction is not truncated")
        assertEquals("true", subject.optStringOrEmpty("t"), "boolean")
    }

    @Test
    fun aStringIsNotTrimmed() {
        assertEquals("  x  ", objOf("k" to "\"  x  \"").optStringOrEmpty("k"))
    }

    @Test
    fun aContainerIsStringifiedRatherThanRefused() {
        // `org.json`'s `optString` returned the container's text for a
        // non-primitive, and this keeps that. Pinning it because it is the one
        // policy here that is a *permissive* answer rather than a defaulted one,
        // so a later "this should be null" tidy-up would be a behaviour change.
        assertEquals("""{"a":1}""", objOf("k" to "{\"a\":1}").optStringOrEmpty("k"), "object")
        assertEquals("[1,2]", objOf("k" to "[1,2]").optStringOrEmpty("k"), "array")
    }

    // ---------------------------------------------------------------- optJsonObject / optJsonArray

    @Test
    fun optJsonObjectTakesAnObjectAndNothingElse() {
        val subject = objOf("o" to "{\"a\":1}", "a" to "[]", "s" to "\"x\"", "n" to "null")
        assertSame<Any?>(subject["o"], subject.optJsonObject("o"), "an object is returned as itself")
        assertNull(subject.optJsonObject("a"), "array")
        assertNull(subject.optJsonObject("s"), "string")
        assertNull(subject.optJsonObject("n"), "stored null")
        assertNull(subject.optJsonObject("absent"), "absent")
    }

    @Test
    fun optJsonArrayTakesAnArrayAndNothingElse() {
        val subject = objOf("a" to "[1]", "o" to "{}", "s" to "\"x\"", "n" to "null")
        assertSame<Any?>(subject["a"], subject.optJsonArray("a"), "an array is returned as itself")
        assertNull(subject.optJsonArray("o"), "object")
        assertNull(subject.optJsonArray("s"), "string")
        assertNull(subject.optJsonArray("n"), "stored null")
        assertNull(subject.optJsonArray("absent"), "absent")
    }

    // ---------------------------------------------------------------- optBooleanOrNull / optBooleanOrFalse

    @Test
    fun optBooleanOrNullDistinguishesFalseFromNotABoolean() {
        val subject = objOf("t" to "true", "f" to "false", "n" to "1", "s" to "\"x\"", "z" to "null")
        assertEquals(true, subject.optBooleanOrNull("t"), "true")
        assertEquals(false, subject.optBooleanOrNull("f"), "false")
        assertNull(subject.optBooleanOrNull("n"), "a number is not a boolean")
        assertNull(subject.optBooleanOrNull("s"), "a string is not a boolean")
        assertNull(subject.optBooleanOrNull("z"), "stored null")
        assertNull(subject.optBooleanOrNull("absent"), "absent")
    }

    @Test
    fun aBooleanLiteralAsAStringIsReadAsABoolean() {
        // `org.json`'s `optBoolean` accepted the text "true"/"false", so this is
        // a preserved leniency rather than a new one.
        assertEquals(true, objOf("k" to "\"true\"").optBooleanOrNull("k"))
        assertEquals(false, objOf("k" to "\"false\"").optBooleanOrNull("k"))
    }

    @Test
    fun optBooleanOrFalseDefaultsEverythingElseToFalse() {
        // The discriminator for the accessor that used to be named
        // `optBooleanOrTrue`: a **present non-boolean** answers `false` here.
        // That was the whole claimed difference between the two names, both of
        // whose bodies read `jsonPrimitiveOrNull(key)?.booleanOrNull == true`,
        // and `jsonPrimitiveOrNull` excludes `JsonNull` -- so the answer was
        // `false` under both names and the difference was not observable.
        val subject = objOf("t" to "true", "f" to "false", "n" to "1", "o" to "{}", "z" to "null")
        assertEquals(true, subject.optBooleanOrFalse("t"), "true")
        assertEquals(false, subject.optBooleanOrFalse("f"), "false")
        assertEquals(false, subject.optBooleanOrFalse("n"), "number")
        assertEquals(false, subject.optBooleanOrFalse("o"), "object")
        assertEquals(false, subject.optBooleanOrFalse("z"), "stored null")
        assertEquals(false, subject.optBooleanOrFalse("absent"), "absent")
    }

    @Test
    fun aBooleanWordIsReadCaseInsensitively() {
        // **Measured, not assumed, and the assumption would have been wrong.**
        // `org.json`'s `optBoolean` accepts "true"/"false" case-insensitively,
        // and it was the one leniency in this file that kotlinx's `booleanOrNull`
        // looked like it might have dropped -- so this case was written first as
        // the expectation that it *had*, and it failed with
        // `expected:<false> but was:<true>`. The two implementations agree.
        //
        // Which fixes what a caller of this module may rely on: **the boolean
        // policies here are a pure port with no behaviour change at all**, and
        // the single documented change in this file is numeric -- `optInt`/
        // `optLong` truncated a fraction and these parse. A manifest that spells
        // a flag `TRUE` read as `true` before and reads as `true` now.
        val subject = objOf("upper" to "\"TRUE\"", "mixed" to "\"True\"", "spaced" to "\" true \"")
        assertEquals(true, subject.optBooleanOrFalse("upper"), "uppercase word")
        assertEquals(true, subject.optBooleanOrFalse("mixed"), "capitalised word")
        assertEquals(true, subject.optBooleanOrNull("upper"), "and the nullable form agrees")
        // A padded word is not a boolean on either implementation, so the
        // case-insensitivity does not extend to surrounding whitespace.
        assertEquals(false, subject.optBooleanOrFalse("spaced"), "padded word")
    }

    // ---------------------------------------------------------------- optIntOrNull / optLongOrNull

    @Test
    fun optIntOrNullParsesRatherThanTruncates() {
        // The behaviour change this file's KDoc is about: `org.json`'s
        // `optInt`/`optLong` **truncated** a fractional number, and these parse.
        // `42.9` answered `42` under `org.json` and answers `null` here, which is
        // a caller-visible difference rather than a refactor.
        assertNull(objOf("k" to "42.9").optIntOrNull("k"), "fraction")
        assertNull(objOf("k" to "42.9").optLongOrNull("k"), "fraction")
        assertEquals(42, objOf("k" to "42").optIntOrNull("k"), "whole number")
    }

    @Test
    fun aNumericStringIsAccepted() {
        assertEquals(42, objOf("k" to "\"42\"").optIntOrNull("k"), "string")
        assertEquals(-7, objOf("k" to "\"-7\"").optIntOrNull("k"), "negative string")
        assertEquals(42, objOf("k" to "\" 42 \"").optIntOrNull("k"), "padded string is trimmed")
        assertEquals(42L, objOf("k" to "\"42\"").optLongOrNull("k"), "string")
    }

    @Test
    fun whatFitsTheNumberAndNotTheInt() {
        // `intOrNull` is an `Int` parse, so a value past `Int.MAX_VALUE` is
        // `null` here and a `Long` on `optLongOrNull`. `AddonStreamsService.kt`
        // reads `size`/`folderSize`/`videoSize` as longs and takes them only when
        // positive, so this boundary is the difference between a shown size and
        // no size at all.
        assertNull(objOf("k" to "3000000000").optIntOrNull("k"), "past Int.MAX_VALUE")
        assertEquals(3000000000L, objOf("k" to "3000000000").optLongOrNull("k"), "long")
    }

    @Test
    fun absentNullAndNonNumbersAreNull() {
        val subject = objOf("z" to "null", "t" to "true", "o" to "{}", "s" to "\"abc\"", "e" to "\"\"")
        assertNull(subject.optIntOrNull("absent"), "absent int")
        assertNull(subject.optLongOrNull("absent"), "absent long")
        for (key in listOf("z", "t", "o", "s", "e")) {
            assertNull(subject.optIntOrNull(key), "int at $key")
            assertNull(subject.optLongOrNull(key), "long at $key")
        }
    }

    // ---------------------------------------------------------------- stringAtOrEmpty

    @Test
    fun stringAtOrEmptyReadsOneElementAndRefusesEverythingElse() {
        val array = Json.parseToJsonElement("""["x", 42, true, null, {"a":1}, [1]]""")
            as JsonArray
        assertEquals("x", array.stringAtOrEmpty(0), "string")
        assertEquals("42", array.stringAtOrEmpty(1), "number")
        assertEquals("true", array.stringAtOrEmpty(2), "boolean")
        assertEquals("", array.stringAtOrEmpty(3), "stored null")
        assertEquals("", array.stringAtOrEmpty(4), "object")
        assertEquals("", array.stringAtOrEmpty(5), "array")
        assertEquals("", array.stringAtOrEmpty(6), "past the end")
        assertEquals("", array.stringAtOrEmpty(-1), "negative")
    }

    @Test
    fun anEmptyArrayIsEmptyRatherThanAnError() {
        assertEquals("", JsonArray(emptyList<JsonElement>()).stringAtOrEmpty(0))
    }
}