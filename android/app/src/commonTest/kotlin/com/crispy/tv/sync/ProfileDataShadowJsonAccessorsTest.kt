package com.crispy.tv.sync

import kotlin.test.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `:app`'s side of the two JSON policies that `com.crispy.tv.backend` declares
 * under the same name.
 *
 * ## Why this is in `androidHostTest` and not `commonTest`
 *
 * `org.json` is a class of the Android platform, supplied by `android.jar`, so
 * it is absent from every other target's classpath. Robolectric supplies the
 * real AOSP implementation; see `com.crispy.tv.backend.JsonAccessorPolicyHostTest`
 * for why the Maven `org.json:json` artifact is not a substitute.
 *
 * These were `private` members of [ProfileDataShadowStore], unnameable from a
 * test in this module too, and now live top-level `internal` in
 * `ProfileDataShadowJsonAccessors.kt`.
 *
 * ## The duplication is deliberate and this suite does not resolve it
 *
 * `com.crispy.tv.backend.toStringMap` is the one `public` declaration in
 * `CrispyBackendJsonExtensions.kt`, and **`:home` imports it** — so the same
 * policy is reachable from `:home` and unreachable from `:app`, decided by an
 * `internal` keyword on the other ten functions in that file. This copy and
 * that one disagree on three inputs, and the disagreement is the whole reason
 * this class exists:
 *
 * | input | `:backend` | here |
 * |---|---|---|
 * | `"  x  "` | `"x"` — trimmed | `"  x  "` — not trimmed |
 * | `""` | dropped | kept |
 * | `"   "` | dropped | kept |
 *
 * Two further differences are structural rather than behavioural: `:backend`
 * takes a **nullable** receiver and returns a `linkedMapOf`, this takes a
 * non-null receiver and returns a `mutableMapOf`. The nullable receiver is why
 * `:home` can write `json.optJSONObject("artwork")?.toStringMap() ?: emptyMap()`
 * and this copy could not.
 *
 * `toJsonObject` has **no** counterpart in `:backend` — the function of that
 * name there takes a `Map<String, Any?>` and does not sort — so these are not
 * overloads of one function but two functions with one name, and the key order
 * that reaches the stored profile shadow is sorted by this one only.
 */
class ProfileDataShadowJsonAccessorsTest {

    // ---------------------------------------------------------------------
    // toStringMap
    // ---------------------------------------------------------------------

    @Test
    fun aValueIsStringifiedWholeAndAJsonNullIsSkipped() {
        val json = Json.parseToJsonElement("""{"count":5,"flag":true,"nullish":null,"nested":{"n":1},"list":[1,"two"]}""").jsonObject

        assertEquals(
            mapOf(
                "count" to "5",
                "flag" to "true",
                "nested" to """{"n":1}""",
                "list" to """[1,"two"]""",
            ),
            json.toStringMap(),
            "a container falls through toString() rather than being skipped",
        )
    }

    @Test
    fun anAbsentKeyIsSimplyNeverVisitedBecauseTheMapIsBuiltFromTheDocumentsOwnKeys() {
        val json = Json.parseToJsonElement("""{"present":"1"}""").jsonObject

        assertEquals(mapOf("present" to "1"), json.toStringMap())
        assertTrue("missing" !in json.toStringMap())
    }

    /**
     * Divergences 3 and 4 together, and the reason the word "divergence" is
     * worth the trouble: **this copy neither trims nor drops blanks.**
     * `:backend`'s ends in `?.trim()` and `if (!normalized.isNullOrBlank())`, so
     * it answers `"x"` for `"  x  "` and omits `""` and `"   "` entirely.
     *
     * Both are consequential for a shadow store, which is exactly the shape of
     * data a shadow is written from and compared against: a value that gained or
     * lost surrounding whitespace round-trips here and is silently normalised
     * there, and a blank that is kept here has been dropped there. **Neither
     * difference is visible in a name, and nothing compares the two.**
     */
    @Test
    fun aValueIsNeitherTrimmedNorDroppedWhenItIsBlankAndThatIsWhereTheTwoCopiesDiffer() {
        val json = Json.parseToJsonElement("""{"padded":"  x  ","empty":"","spaces":"   "}""").jsonObject

        assertEquals(
            mapOf("padded" to "  x  ", "empty" to "", "spaces" to "   "),
            json.toStringMap(),
        )
    }

    /**
     * `mutableMapOf` is a `LinkedHashMap` on the JVM, so the document order
     * survives. That is worth pinning because [toJsonObject] sorts, and a reader
     * seeing one sort in a round trip would reasonably assume the other does
     * too.
     */
    @Test
    fun theDocumentOrderSurvivesBecauseMutableMapOfIsALinkedMap() {
        val json = Json.parseToJsonElement("""{"z":"1","a":"2","m":"3"}""").jsonObject

        assertEquals(listOf("z", "a", "m"), json.toStringMap().keys.toList())
    }

    // ---------------------------------------------------------------------
    // toJsonObject
    // ---------------------------------------------------------------------

    @Test
    fun theKeysAreSortedOnTheWayOutAndTheDocumentOrderIsNotPreserved() {
        val encoded = linkedMapOf("z" to "1", "a" to "2", "m" to "3").toJsonObject()

        // `org.json`'s `keys()` was an iterator; a `JsonObject`'s `keys` is a
        // `Set`, so the `asSequence().toList()` that made the iterator
        // comparable is gone and the assertion is the set's order as a list.
        assertEquals(listOf("a", "m", "z"), encoded.keys.toList())
    }

    @Test
    fun aRoundTripSortsOnTheWayOutAndKeepsEverythingOnTheWayBackIn() {
        val original = linkedMapOf("z" to "1", "a" to "", "m" to "   ")

        assertEquals(
            original,
            original.toJsonObject().toStringMap(),
            "so the blanks this copy keeps come back, and the order comes back sorted",
        )
    }
}
