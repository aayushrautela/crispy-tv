package com.crispy.tv.addons.registry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The measured `java.net.URLEncoder` table, asserted.
 *
 * **Every expectation here is the output of `URLEncoder.encode(value, "UTF-8")` run on a
 * JVM** — the same call `RemoteMetadataLabDataSource` used to make — and not a value read
 * off [FormUrlEncoder]'s KDoc table. That is the distinction the golden rule turns on: a
 * digest (or an encoding) of a string the function does not build is still a wrong
 * expectation, and "I measured it" reads like evidence in a way "I wrote it out" does not.
 * The KDoc table is a *record* of the measurement; this suite is the check.
 */
class FormUrlEncoderTest {
    /**
     * The table, one case per row, and **no default parameter on the helper** — a defaulted
     * expected value is a fixture that agrees with every case it was not given to, which is
     * how thirteen cases once failed together here for the same reason.
     */
    private fun row(raw: String, expected: String) = assertEquals(expected, formUrlEncodeComponent(raw), "input [$raw]")

    @Test
    fun alphanumericCharactersAreLeftAlone() {
        row("tt1234567", "tt1234567")
        row("CAPS", "CAPS")
    }

    @Test
    fun theLookupIdShapeThatEveryRequestCarriesHasItsColonsEscaped() {
        // The one case that matters on every single request: a lookupId is baseId:season:episode.
        row("tt1234567:1:5", "tt1234567%3A1%3A5")
        row("tt1234567:1:5:extra", "tt1234567%3A1%3A5%3Aextra")
    }

    @Test
    fun aSpaceBecomesAPlusAndNotPercentTwenty() {
        row("a b", "a+b")
        row("a  b", "a++b")
        row(" ", "+")
        // The mirror: a form encoder never emits %20.
        assertNotEquals("a%20b", formUrlEncodeComponent("a b"), "a form encoder must not emit %20 for a space")
    }

    @Test
    fun aLiteralPlusIsEscapedSoItCannotArriveAsASpace() {
        // "a+b" and "a b" both reaching the server as "a b" is the ambiguity the escape prevents.
        row("a+b", "a%2Bb")
        row("a+b c", "a%2Bb+c")
        assertNotEquals(formUrlEncodeComponent("a b"), formUrlEncodeComponent("a+b"), "a space and a plus must not collide")
    }

    @Test
    fun starIsUnreservedAndTildeIsNotWhichIsTheOppositeOfRfc3986() {
        row("star*x", "star*x")
        row("under_score", "under_score")
        row("dash-dot.x", "dash-dot.x")
        row("~tilde", "%7Etilde")
    }

    @Test
    fun separatorsAndDelimitersAreAllEscaped() {
        row("slash/back", "slash%2Fback")
        row("a/b/c", "a%2Fb%2Fc")
        row("colon:and;comma", "colon%3Aand%3Bcomma")
        row("q?x=1&y=2", "q%3Fx%3D1%26y%3D2")
        row("hash#frag", "hash%23frag")
    }

    @Test
    fun nothingIsDecodedFirstSoAnEscapeInTheInputIsItselfEscaped() {
        row("%", "%25")
        row("%zz", "%25zz")
        row("%2F", "%252F")
        row("pct%20already", "pct%2520already")
    }

    @Test
    fun hexDigitsAreUpperCase() {
        // A lowercase digit is a different URL *string*, equivalent to the server, so this can
        // only ever be caught by a suite that asserts a string rather than by behaviour.
        val encoded = formUrlEncodeComponent("tt1234567:1:5")
        assertEquals("tt1234567%3A1%3A5", encoded)
        assertNotEquals("tt1234567%3a1%3a5", encoded, "hex digits must be upper case")
    }

    @Test
    fun nonAsciiIsEncodedOneByteAtATimeOverItsUtf8Run() {
        row("unicodeéè", "unicode%C3%A9%C3%A8")
        row("àéî", "%C3%A0%C3%A9%C3%AE")
        row("emoji😀", "emoji%F0%9F%98%80")
    }

    @Test
    fun controlCharactersAreEscapedRatherThanCarried() {
        row("tab\tsep", "tab%09sep")
        row("new\nline", "new%0Aline")
        row("\u0001ctrl", "%01ctrl")
    }

    @Test
    fun theEmptyStringEncodesToItself() {
        row("", "")
    }

    @Test
    fun itDiffersFromAPathSegmentEncoderOnExactlyTheCharactersThatSeparateTheTwoRules() {
        // **The pair that a shared predicate would collapse.** RFC 3986 keeps ~ and escapes *;
        // the form rule does the reverse. A single `isUnreserved` shared by both encoders
        // passes every case above and breaks exactly these four.
        assertNotEquals(percentEncodeSegment("~tilde"), formUrlEncodeComponent("~tilde"), "~: RFC 3986 keeps it, the form rule escapes it")
        assertNotEquals(percentEncodeSegment("star*x"), formUrlEncodeComponent("star*x"), "*: RFC 3986 escapes it, the form rule keeps it")
        assertNotEquals(percentEncodeSegment("a b"), formUrlEncodeComponent("a b"), "a space: RFC 3986 writes %20, the form rule writes +")

        // ...and on nothing else: alphanumeric, dash, dot and underscore agree, which is why
        // one wrong shared predicate still produces a plausible-looking URL.
        for (agreed in listOf("tt1234567", "dash-dot.x", "under_score")) {
            assertEquals(
                percentEncodeSegment(agreed),
                formUrlEncodeComponent(agreed),
                "[$agreed] is unreserved under both rules",
            )
        }
    }

    @Test
    fun twoDifferentIdsNeverEncodeToTheSameUrlSegment() {
        // **Not a round trip, and the difference is the finding.** An earlier version of this
        // case asserted `percentDecode(formUrlEncodeComponent(x)) == x`, which is false for
        // exactly one input, `"a b"`: it encodes to `a+b`, and `percentDecode` is *Uri's path*
        // decoder, which does not read `+` as a space — so it comes back as `a+b`. The encoder
        // is right and the property was wrong, because the pair that must not collide is the
        // pair the *server* sees, not the pair a path decode can tell apart.
        //
        // So the property worth pinning is injectivity on the encoded string, which is what
        // keeps `tt1234567:1:5` and `tt1234567-1-5` from naming the same resource.
        val inputs = listOf(
            "tt1234567:1:5", "tt1234567-1-5", "tt1234567 1 5", "a b", "a+b", "a%2Bb",
            "%2F", "a/b", "", "unicodeé", "unicodeè",
        )
        val encoded = inputs.map { it to formUrlEncodeComponent(it) }
        for ((leftInput, leftEncoded) in encoded) {
            for ((rightInput, rightEncoded) in encoded) {
                if (leftInput == rightInput) continue
                assertNotEquals(leftEncoded, rightEncoded, "[$leftInput] and [$rightInput] must not encode alike")
            }
        }
    }
}