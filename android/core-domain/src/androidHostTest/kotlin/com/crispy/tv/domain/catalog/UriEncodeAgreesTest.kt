package com.crispy.tv.domain.catalog

import android.net.Uri
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * **This is the test that makes [encodeUriComponent] correct.** Every other
 * assertion about it is a claim; this one is the comparison.
 *
 * It lives in `androidHostTest` and runs under Robolectric for one reason: the
 * allow-list can only be settled against the real `android.net.Uri.encode`, and
 * **the platform artifact cannot tell us what it does.** `javap -c` on
 * `android.jar` for `Uri.encode(java.lang.String)` returns
 * `ldc // String Stub!` — the SDK ships a stub jar with no method bodies, so the
 * platform says the method exists and nothing whatsoever about its behaviour.
 * The real implementation is in Robolectric's `android-all` under `~/.m2`, which
 * is what runs here. *The library that ships is a stub; the library the test
 * stands in for is not; and on a parsing boundary the substitute's answer is the
 * one a JVM test reports.*
 *
 * That is also why this is `org.junit.Test` with
 * `assertEquals(message, expected, actual)` in the JUnit argument order, while
 * `UriComponentEncoderTest` in `commonTest` is `kotlin.test` with the message
 * **last**. Both orders are correct; they are not interchangeable, and swapping
 * them still compiles.
 *
 * **No `Context` is needed, and deliberately so:** `Uri.encode` is a static pure
 * function over a `String`, which is exactly the case where Robolectric is
 * trustworthy. The failure mode this repository has recorded — `optString`
 * answering `"null"` on AOSP and `""` on the Maven artifact — is a
 * parsing-boundary disagreement, and this is one.
 *
 * **`@Config(sdk = [35])` is required, and this file's first version said
 * otherwise.** It ran with no `@Config` at all, on the reading that a static
 * function needs no SDK pin, and **all five cases failed with
 * `Method encode in android.net.Uri not mocked`** — which is this repository's
 * own recorded symptom for exactly the cause it names: with no manifest,
 * Robolectric falls back to **SDK 21**, where `android.jar` *is* the stub jar,
 * so every body throws. So the omission was self-defeating in the sharpest way —
 * **the stub jar that could not tell us what `Uri.encode` does at compile time
 * is the same jar that refuses to run it at SDK 21.** The rule being re-learned
 * is the recorded one: pin `sdk` on *every* `androidHostTest` class, because the
 * failure names a real platform class and blames nothing. And note what the five
 * failures did **not** say: not one was an assertion failure, so the allow-list
 * was neither confirmed nor refuted — the suite never reached its comparison.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UriEncodeAgreesTest {

    @Test
    fun everyPrintableAsciiCharacterAgreesWithThePlatform() {
        for (code in 0x20..0x7E) {
            val char = code.toChar()
            assertEquals(
                "U+%04X must encode identically to android.net.Uri.encode".format(code),
                Uri.encode(char.toString()),
                encodeUriComponent(char.toString()),
            )
        }
    }

    @Test
    fun theControlRangeAgreesWithThePlatform() {
        // 0x00..0x1F is outside the printable walk and is where a
        // "printable-only" allow-list would silently differ, so the comparison
        // covers it rather than trusting the table.
        for (code in 0x00..0x1F) {
            val char = code.toChar()
            assertEquals(
                "U+%04X must encode identically to android.net.Uri.encode".format(code),
                Uri.encode(char.toString()),
                encodeUriComponent(char.toString()),
            )
        }
    }

    @Test
    fun multiByteInputAgreesWithThePlatform() {
        // The comparison has to include non-ASCII, because the byte-wise
        // mechanism is only visible on a multi-byte input: a UTF-16-based
        // implementation agrees with `Uri.encode` on every ASCII character and
        // disagrees on every character above 0x7F. *An ASCII-only comparison is
        // a test that passes on a wrong implementation.*
        for (value in listOf("é", "ÿ", "😀", "aé b😀c", "日本語", "Ω≈ç√∫", "\u0000\u007F\u0080\u07FF\u0800\uFFFF")) {
            assertEquals(
                "\"$value\" must encode identically to android.net.Uri.encode",
                Uri.encode(value),
                encodeUriComponent(value),
            )
        }
    }

    @Test
    fun nullAndEmptyAgreeWithThePlatformOnTheEmptyString() {
        // `Uri.encode` has no null overload, so the call sites used
        // `Uri.encode(x.orEmpty())`. The comparison is against that form, which
        // is the behaviour the call sites actually had.
        assertEquals(Uri.encode(""), encodeUriComponent(null))
        assertEquals(Uri.encode(""), encodeUriComponent(""))
    }

    @Test
    fun aWholeRouteStringAgreesWithThePlatform() {
        // The unit that matters is not the character but the assembled path.
        // One real-shaped value, so a per-character agreement that still ordered
        // two escapes wrongly would be caught.
        val pathSegment = "The Matrix: Reloaded (1999) [4K] ~!?'*"
        val queryValue = "a/b?c=d&e=f #g"
        assertEquals(
            Uri.encode(pathSegment),
            encodeUriComponent(pathSegment),
        )
        assertEquals(
            Uri.encode(queryValue),
            encodeUriComponent(queryValue),
        )
    }
}
