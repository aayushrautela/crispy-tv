package com.crispy.tv.details

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `normalizedDetailsItemType` was a five-line `when` inside a `remember(itemType)` in
 * `DetailsRoute`'s composable body, so **no test in any source set could reach it** --
 * the same shape as the six guards the player overlay's auto-hide needed. Moving the
 * route to `commonMain` made the file reachable; naming the decision made it callable.
 *
 * The rule it decides is worth stating plainly, because the output is used for two
 * things at once: it is the `ViewModel` cache key's prefix, and `isBlank()` on it is
 * how an unrecognised route argument leaves the screen instead of loading a ViewModel
 * keyed on a shape nothing recognises.
 */
class NormalizedDetailsItemTypeTest {

    /**
     * The case nothing pinned before, and the one a real navigation actually produces:
     * the argument arrives URL-encoded through `AppRoutes.homeDetailsRoute`, which
     * encodes `itemType.trim()`, so a caller that passed `"  SERIES  "` gets the
     * padded form back. Trim and case-fold are therefore load-bearing, not tidiness.
     *
     * Asserted as a **set of pairs** rather than a loop over inputs: the expected side
     * is written out independently, so a function that returned a constant cannot pass.
     */
    @Test
    fun everyAcceptedSpellingIsMappedAndTrimmedAndCaseFolded() {
        val cases = mapOf(
            "movie" to "movie",
            "Movie" to "movie",
            "MOVIE" to "movie",
            "  movie  " to "movie",
            "\tmovie\n" to "movie",
            "series" to "show",
            "SERIES" to "show",
            "  SERIES  " to "show",
            "show" to "show",
            "Show" to "show",
            " tv " to "show",
            "anime" to "anime",
            "ANIME" to "anime",
            "  Anime " to "anime",
        )
        cases.forEach { (input, expected) ->
            assertEquals(expected, normalizedDetailsItemType(input), "for input ${input.quoted()}")
        }
        assertEquals(
            setOf("movie", "show", "anime"),
            cases.values.toSet(),
            "the table must reach all three real outputs, or a one-branch function passes it",
        )
    }

    /**
     * The `else -> ""` sentinel. Each row is a near miss for a plausible rewrite:
     *
     *  * `""` and `"   "` are what a blank argument looks like after trimming.
     *  * `"tv movie"` and `"tv show"` contain accepted tokens but are not one, so a
     *    `contains` rewrite would pass them. `"tv show"` is the sharpest of the two:
     *    both of its words are accepted values on their own.
     *  * `"serieses"` and `"animes"` are one character off, so a `startsWith` rewrite
     *    would pass them.
     *  * `"MOVIES"` is the case-only plural of an accepted value.
     *
     * **A row that reads `"tv "` was in this list in the first draft, and it is wrong:**
     * trimming makes it `"tv"`, which *is* accepted. It was a copy of the row above in
     * the accepted table, written from the shape of the other near misses rather than by
     * running the function -- which is the one thing a near-miss row must not be.
     */
    @Test
    fun anUnrecognisedArgumentBecomesTheBlankSentinelRatherThanAGuess() {
        val nearMisses = listOf(
            "",
            "   ",
            "\t\n",
            "tv movie",
            "tv show",
            "serieses",
            "animes",
            "MOVIES",
            "movie/series",
            "the movie",
            "2024",
        )
        for (input in nearMisses) {
            assertEquals(
                "",
                normalizedDetailsItemType(input),
                "${input.quoted()} is not one of the four accepted shapes and must not be guessed at",
            )
        }
    }

    /**
     * `show` is the *output* for three different inputs, and the route uses that output
     * as a ViewModel cache-key prefix. So three route arguments that differ only in
     * spelling must produce ONE key prefix -- otherwise a ViewModel is dropped and the
     * screen reloads when the user navigates back through a differently-spelled link.
     *
     * This is the property the key format exists for, and asserting it as a set is what
     * makes it observable: a function returning `input` unchanged fails it.
     */
    @Test
    fun threeSpellingsOfTheSameShapeCollapseToOneKeyPrefix() {
        val spellings = listOf("series", "SERIES", " show ", "tv", "TV")
        assertEquals(1, spellings.map { normalizedDetailsItemType(it) }.toSet().size)
        assertEquals(setOf("show"), spellings.map { normalizedDetailsItemType(it) }.toSet())
    }

    /**
     * The two shapes that must stay distinguishable, because a ViewModel keyed on the
     * wrong one would be reused across a different title shape -- which is exactly what
     * the comment on the key format says the second half of the key is for.
     */
    @Test
    fun aMovieIsNeverGivenTheSameKeyPrefixAsASeries() {
        assertTrue(normalizedDetailsItemType("movie") != normalizedDetailsItemType("series"))
        assertTrue(normalizedDetailsItemType("movie") != normalizedDetailsItemType("anime"))
        assertTrue(normalizedDetailsItemType("anime") != normalizedDetailsItemType("series"))
    }

    /**
     * `lowercase()` here is Kotlin's locale-invariant overload, not
     * `lowercase(Locale)`. This cannot be tested from `commonTest` -- there is no way to
     * set a device locale from common code -- so it is recorded rather than asserted.
     * The consequence is the one that matters: the Turkish dotless-i bug that
     * `titlecase(Locale.ENGLISH)` exists to avoid cannot occur here, because no locale
     * participates in the fold at all.
     */
    @Test
    fun aLocaleSensitiveLetterDoesNotHaveAnAnswerThatVariesByDevice() {
        // "SERİ" (capital S, dotted capital I) lowercases invariantly to "seri̇", which
        // is NOT "series" and not any accepted token, so it falls to the sentinel on
        // every device. Stated as a fact about the mapping, not as a locale test.
        assertEquals("", normalizedDetailsItemType("SERİ"))
        assertEquals("", normalizedDetailsItemType("İ"))
    }

    private fun String.quoted(): String = "\"" + this.replace("\t", "\\t").replace("\n", "\\n") + "\""
}
