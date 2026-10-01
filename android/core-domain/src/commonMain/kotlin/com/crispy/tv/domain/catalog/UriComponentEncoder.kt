package com.crispy.tv.domain.catalog

/**
 * Percent-encodes a value exactly as `android.net.Uri.encode` does.
 *
 * **This is a second encoder, and the sibling next door is not interchangeable
 * with it.** [CatalogUrlBuilder] encodes for the catalog URL contract, which is
 * `application/x-www-form-urlencoded`-shaped: it *escapes* `~`. `Uri.encode`
 * *keeps* `~` literal, and also `!`, `'`, `(`, `)` and `*`. So the two differ on
 * five characters, and **the mechanism is identical** — byte-wise `%XX` over
 * UTF-8, uppercase hex, one escape per byte — so this is one function with a
 * different allow-list, not two encoders.
 *
 * **Substituting [CatalogUrlBuilder]'s encoder for this one is a silent
 * deep-link behaviour change**, which is why both this KDoc and the KDoc on
 * `AppRoutesBuilders` warn about it separately: a route that has already shipped
 * as `~` would be re-issued as `%7E`, and the two do not resolve to the same
 * destination. *A near-miss is not a match, and the closer the two are the
 * harder the mistake is to see in review.*
 *
 * ## Why the allow-list below is written out and not shared with the catalog one
 * `isLiteralUrlChar` in `CatalogUrlBuilder.kt` is a `private` top-level
 * function, and **a `private` top-level declaration cannot be reused from
 * another file** — so this repeats the seven-character overlap rather than
 * reaching for a shared helper, because sharing them would mean one function
 * whose two callers want *different* answers from it. That is the shape to watch
 * for when extracting a helper: two callers that agree on the mechanism and
 * disagree on the table are not a candidate for extraction.
 *
 * ## The one thing a reader must not do
 * **Do not treat this table as evidence.** It is a claim, and
 * `UriComponentEncoderTest` in `:core-domain`'s `commonTest` asserts it while
 * `UriEncodeAgreesTest` in `androidHostTest` compares it against the real
 * `android.net.Uri.encode`. That comparison is the only thing that makes the
 * table true, and it had to be a *separate* suite because **`android.jar` is a
 * stub jar** — `javap -c` on it returns `ldc // String Stub!` for
 * `Uri.encode(java.lang.String)` — so the platform artifact says the method
 * exists and nothing about what it does. The real implementation is in
 * Robolectric's `android-all` under `~/.m2`, which is what the host test runs
 * against. *A stub jar tells you a symbol exists; it never tells you what it
 * does, and a test written against a stub proves nothing.*
 */
private const val NOT_ENCODED = "_-!.~'()*"

private val HEX = "0123456789ABCDEF".toCharArray()

/**
 * Encodes [value] as `android.net.Uri.encode(value)` would: every character
 * outside `[A-Za-z0-9]` and [NOT_ENCODED] becomes one uppercase `%XX` per UTF-8
 * byte, and everything else is copied through unchanged.
 *
 * Nulls are treated as empty, which is what the call sites that used
 * `Uri.encode(x.orEmpty())` already did — the `orEmpty()` moves here so the
 * call sites are one call and not a call plus a decision.
 */
fun encodeUriComponent(value: String?): String {
    val source = value.orEmpty()
    if (source.isEmpty()) return ""
    return buildString {
        for (byte in source.encodeToByteArray()) {
            val code = byte.toInt() and 0xFF
            val char = code.toChar()
            if (char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char in NOT_ENCODED) {
                append(char)
            } else {
                append('%')
                append(HEX[code shr 4])
                append(HEX[code and 0x0F])
            }
        }
    }
}
