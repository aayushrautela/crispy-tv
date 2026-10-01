package com.crispy.tv.domain.catalog

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the allow-list of [encodeUriComponent] as an **explicit table**, not as a
 * condition restated from the implementation.
 *
 * Two reasons, and the second is the one that matters:
 *
 *  1. **A table is visible.** A test that re-derives the expected answer by
 *     calling the same predicate the implementation calls is a re-implementation
 *     — everything about it is correct and it proves the suite, not the code.
 *  2. **This suite cannot be the proof, and it is written to say so.** The only
 *     thing that makes this table true is
 *     `UriEncodeAgreesTest` in `androidHostTest`, which compares it against the
 *     real `android.net.Uri.encode`. *A test that runs and a test that could be
 *     wrong look identical in a build log, and only one of them is worth
 *     anything here* — so this file pins the shape on every target and the
 *     other file settles whether the shape is right.
 */
class UriComponentEncoderTest {

    /** The characters `Uri.encode` copies through, beyond the alphanumerics. */
    private val uriLiteral = setOf(
        '_', '-', '!', '.', '~', '\'', '(', ')', '*',
    )

    /**
     * The five characters on which this encoder and
     * [CatalogUrlBuilder]'s differ. `Uri.encode` keeps them; the catalog
     * contract escapes them. **This is the whole reason both exist**, so it is a
     * named set rather than a by-product of the loop below.
     */
    private val divergingFromCatalog = setOf('!', '~', '\'', '(', ')')

    @Test
    fun everyPrintableAsciiCharacterIsEitherCopiedOrPercentEncoded() {
        for (code in 0x20..0x7E) {
            val char = code.toChar()
            val expected = if (char.isLetterOrDigit() && char.code < 0x80 || char in uriLiteral) {
                char.toString()
            } else {
                "%" + "0123456789ABCDEF"[code shr 4] + "0123456789ABCDEF"[code and 0x0F]
            }
            assertEquals(
                expected = expected,
                actual = encodeUriComponent(char.toString()),
                message = "U+%04X must encode to $expected, not to " +
                    "${encodeUriComponent(char.toString())}",
            )
        }
    }

    @Test
    fun theFiveCharactersThatDivergeFromTheCatalogEncoderAreKeptLiteral() {
        for (char in divergingFromCatalog) {
            assertEquals(
                expected = char.toString(),
                actual = encodeUriComponent(char.toString()),
                message = "Uri.encode keeps '$char' literal, and this is the " +
                    "difference that keeps both encoders in the repository",
            )
        }
    }

    @Test
    fun tildeIsTheCharacterTheTwoKdocsArgueAbout() {
        // The single assertion a reader can check by reading one line of each
        // KDoc: `Uri.encode` keeps it, the catalog contract escapes it.
        assertEquals("~", encodeUriComponent("~"))
        assertEquals("%7E", encodeQueryComponentForTest("~"))
    }

    @Test
    fun spaceBecomesPercentTwentyAndNeverAPlus() {
        // `application/x-www-form-urlencoded` would answer "+" here. `Uri.encode`
        // answers "%20", and the catalog encoder agrees on this one -- which is
        // precisely why the two are easy to mistake for each other.
        assertEquals("%20", encodeUriComponent(" "))
        assertEquals("a%20b", encodeUriComponent("a b"))
    }

    @Test
    fun nonAsciiIsEmittedAsOneEscapePerUtf8Byte() {
        // "é" is two UTF-8 bytes (0xC3 0xA9), "😀" is four (0xF0 0x9F 0x98 0x80).
        assertEquals("%C3%A9", encodeUriComponent("é"))
        assertEquals("%F0%9F%98%80", encodeUriComponent("😀"))
    }

    @Test
    fun hexIsUppercase() {
        // The sibling encoder uses uppercase hex, and the sibling is the one
        // this must agree with on the characters they share -- so a lowercase
        // `%c3` here would be a *different* URL from `%C3`, and both would
        // resolve, which is the worst kind of bug: silent and asymmetric.
        // U+007F is outside the printable range the table above walks, so it is
        // the only way to get an escape whose hex digit is a letter.
        assertEquals("%7F", encodeUriComponent("\u007F"))
        assertEquals("%C3%BF", encodeUriComponent("ÿ"))
    }

    @Test
    fun nullAndEmptyBothEncodeToTheEmptyString() {
        // The call sites used to write `Uri.encode(x.orEmpty())`. The null
        // decision moved into the function, so the table above starts at 0x20
        // and never has to reason about it.
        assertEquals("", encodeUriComponent(null))
        assertEquals("", encodeUriComponent(""))
    }

    @Test
    fun theAlphanumericsAreAllCopiedThroughUnchanged() {
        val alphanumerics =
            ('a'..'z').toList() + ('A'..'Z').toList() + ('0'..'9').toList()
        for (char in alphanumerics) {
            assertEquals(
                expected = char.toString(),
                actual = encodeUriComponent(char.toString()),
                message = "'$char' is alphanumeric and must never be escaped",
            )
        }
    }
}

/**
 * The catalog contract's own encoder, spelled out here so this suite can pin
 * the *difference* rather than only this side of it.
 *
 * It is `private` in `CatalogUrlBuilder.kt`, so it cannot be called from a test
 * in another file — and that is the reason this landing could not simply assert
 * "the two agree on the overlap": the overlap's other half is not nameable from
 * here. **A `private` top-level decision is an untestable decision**, and the
 * fix is to name it in production. This copy is the temporary half of that: it
 * is stated as a claim, not measured, and the measured half is
 * [UriComponentEncoderTest]'s table plus the host-test comparison.
 */
private fun encodeQueryComponentForTest(value: String): String {
    val hex = "0123456789ABCDEF"
    return buildString {
        for (byte in value.encodeToByteArray()) {
            val code = byte.toInt() and 0xFF
            val char = code.toChar()
            val literal = char in 'a'..'z' || char in 'A'..'Z' ||
                char in '0'..'9' || char == '.' || char == '-' ||
                char == '*' || char == '_'
            if (literal) append(char) else append('%').append(hex[code shr 4]).append(hex[code and 0x0F])
        }
    }
}
