package com.crispy.tv.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The two recent-search rows in `SearchScreen` identify a chip with
 * `key = { it.lowercase() }`. That expression used to be `it.lowercase(Locale.ROOT)`.
 *
 * The swap is exact rather than approximate, and this is the case that pins it. Kotlin's
 * no-arg `String.lowercase()` is specified to be locale-invariant; the argument-taking
 * overload is not. So the pair the code used to spell out, `lowercase(Locale.ROOT)`, and
 * the one it spells now, `lowercase()`, are the same function by definition -- which is
 * why the `java.util.Locale` import could be deleted rather than moved.
 *
 * **This test deliberately does not name `Locale`.** A `commonTest` source set has to be
 * free of JVM types for the Apple targets, exactly as `commonMain` does, and
 * `check_common_purity.py` scans `commonMain` only -- so a `java.util.Locale` reference
 * here would compile on the desktop target and fail on Kotlin/Native without any gate in
 * this repository noticing. What is asserted instead is the *property* the swap claims and
 * that the two expressions differ on if it were wrong: the folding is locale-invariant, so
 * a dotted capital I folds to `i` and never to the dotless `ı` that a Turkish locale
 * would produce.
 *
 * **The key is asserted through `searchItemKey`, the function the screen itself calls.**
 * The rows used to spell it inline, `key = { it.lowercase() }`, and because that lambda is
 * an argument to `items(...)` inside a `LazyRow` no test could invoke it -- the first
 * version of this file therefore defined its own `private fun key(query) = query.lowercase()`
 * and proved the standard library rather than this code. A mutation that made the key
 * case-sensitive passed that suite. The production key is now a named `internal`
 * function, and a test double of a decision is worse than no double at all.
 */
class SearchScreenTest {
    private fun key(query: String): String = searchItemKey(query)

    @Test
    fun `queries differing only in case share one chip`() {
        assertEquals(key("trakt"), key("Trakt"))
        assertEquals(key("trakt"), key("TRAKT"))
        assertEquals(key("the office"), key("The Office"))
    }

    @Test
    fun `different queries keep different keys`() {
        assertNotEquals(key("trakt"), key("simkl"))
        // Case folding must not collapse two genuinely different queries either.
        assertNotEquals(key("a b"), key("ab"))
    }

    @Test
    fun `folding does not depend on a locale`() {
        // Under a Turkish locale `lowercase()` would produce the dotless "ı" here. The
        // dotless form is what makes this case worth having rather than a restatement of
        // the case-insensitivity cases above.
        assertEquals("ilovei", key("ILOVEI"))
        assertEquals("i", key("I"))
    }

    @Test
    fun `surrounding whitespace is part of the key rather than being trimmed away`() {
        // The row stores what the user typed, and the key is derived from the same
        // string, so a leading space distinguishes two chips. Asserting it keeps a future
        // "trim it, it is tidier" edit from silently merging two history entries.
        assertNotEquals(key("trakt"), key(" trakt"))
    }

    @Test
    fun `an empty query is still a key rather than an error`() {
        assertEquals("", key(""))
        // Blank is not folded to empty: the two are separate history entries, and the
        // case above about surrounding whitespace is the reason that matters.
        assertNotEquals(key(""), key("  "))
    }

    @Test
    fun `a query with punctuation is keyed on the punctuation too`() {
        assertEquals("s.w.1", key("S.W.1"))
        assertNotEquals(key("s.w.1"), key("sw1"))
    }
}
