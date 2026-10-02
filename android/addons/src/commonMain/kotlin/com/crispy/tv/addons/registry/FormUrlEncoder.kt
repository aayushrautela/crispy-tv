package com.crispy.tv.addons.registry

/**
 * `java.net.URLEncoder`'s `application/x-www-form-urlencoded` rule, ported.
 *
 * **This is not [percentEncodeSegment].** Both percent-encode UTF-8 bytes with
 * upper-case hex, and both leave alphanumerics alone, but a form encoder differs
 * from an RFC 3986 path-segment encoder in three places, and each one is a real
 * answer a request URL depends on:
 *
 * 1. **A space becomes `+`, not `%20`.**
 * 2. **`*` is unreserved; `~` is not.** RFC 3986 has it the other way round, which
 *    is why [percentEncodeSegment] must keep its own predicate: swapping one for
 *    the other changes `~` and `*` in every URL built from it.
 * 3. **Nothing is decoded first.** `%2F` encodes to `%252F`, because the `%` is
 *    itself escaped. A "be lenient and pass escapes through" encoder would emit a
 *    URL naming a different resource.
 *
 * The table below is `URLEncoder.encode(value, "UTF-8")` run on a JVM, which is
 * how `RemoteMetadataLabDataSource` used to build a lookup-id path segment. It is
 * recorded here rather than in the test because **the test is where it is checked**,
 * and the reason it had to be checked at all is that this function and
 * [percentEncodeSegment] differ in exactly the characters an id is most likely to
 * contain -- a `lookupId` is `baseId:season:episode`, so the `:` is the case that
 * matters on every single request.
 *
 * | input | encoded |
 * |---|---|
 * | `tt1234567` | `tt1234567` |
 * | `tt1234567:1:5` | `tt1234567%3A1%3A5` |
 * | `tt1234567:1:5:extra` | `tt1234567%3A1%3A5%3Aextra` |
 * | `a b` | `a+b` |
 * | `a  b` | `a++b` |
 * | `a+b` | `a%2Bb` |
 * | `a+b c` | `a%2Bb+c` |
 * | `~tilde` | `%7Etilde` |
 * | `star*x` | `star*x` |
 * | `under_score` | `under_score` |
 * | `dash-dot.x` | `dash-dot.x` |
 * | `slash/back` | `slash%2Fback` |
 * | `colon:and;comma` | `colon%3Aand%3Bcomma` |
 * | `q?x=1&y=2` | `q%3Fx%3D1%26y%3D2` |
 * | `hash#frag` | `hash%23frag` |
 * | `pct%20already` | `pct%2520already` |
 * | `%2F` | `%252F` |
 * | `%zz` | `%25zz` |
 * | `CAPS` | `CAPS` |
 * | ` ` (a lone space) | `+` |
 * | `%` | `%25` |
 * | `unicodeéè` | `unicode%C3%A9%C3%A8` |
 * | `àéî` | `%C3%A0%C3%A9%C3%AE` |
 * | `emoji😀` | `emoji%F0%9F%98%80` |
 * | `tab\tsep` | `tab%09sep` |
 * | `new\nline` | `new%0Aline` |
 * | `\u0001ctrl` | `%01ctrl` |
 *
 * The empty string encodes to itself, which is what the old call did and what a
 * `[0x20]`-style early return would get wrong for no reason.
 */
fun formUrlEncodeComponent(value: String): String {
    if (value.isEmpty()) return value
    val out = StringBuilder(value.length)
    for (byte in value.encodeToByteArray()) {
        val char = (byte.toInt() and 0xFF).toChar()
        when {
            // A space is the one character the form rule replaces rather than escapes.
            char == ' ' -> out.append('+')
            char.isFormUnreserved() -> out.append(char)
            else -> {
                val unsigned = byte.toInt() and 0xFF
                out.append('%')
                    .append(FORM_HEX_DIGITS[unsigned shr 4])
                    .append(FORM_HEX_DIGITS[unsigned and 0xF])
            }
        }
    }
    return out.toString()
}

/**
 * The form rule's unreserved set: `URLEncoder`'s own, which is *not* RFC 3986's.
 * `URLEncoder` is defined over `application/x-www-form-urlencoded`, where `*` is
 * safe to carry and `~` is not — measured, not inferred, because the difference
 * from [percentEncodeSegment] is invisible in the source of either.
 */
private fun Char.isFormUnreserved(): Boolean =
    this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' ||
        this == '-' || this == '.' || this == '_' || this == '*'

/**
 * Upper-case, because `URLEncoder` emits upper-case and **a lowercase hex digit is
 * a different URL string** -- and for a percent-encoded path segment the two forms
 * are equivalent to a server, so a drift here would never fail a test written
 * against behaviour and would always fail one written against a stored URL.
 */
private const val FORM_HEX_DIGITS = "0123456789ABCDEF"