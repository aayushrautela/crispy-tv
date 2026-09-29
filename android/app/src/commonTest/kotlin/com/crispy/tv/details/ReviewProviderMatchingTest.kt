package com.crispy.tv.details

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the review-source name matching, which is the whole of the decision
 * that used to sit on the same function as the `R` lookups.
 *
 * It is in `commonTest` because the name matching is pure and portable. The
 * identifier lookup it feeds lives in `ReviewProviderBadge.kt` and cannot be
 * reached from here: `com.crispy.tv.ui.assets.R$raw` is not on a JVM test
 * classpath, so a test that called it failed with a `NoClassDefFoundError` that
 * said nothing about the matching. That split is why this file is a suite at
 * all rather than an assertion inside the badge.
 */
class ReviewProviderMatchingTest {

    @Test
    fun bothKnownSourcesAreRecognised() {
        assertEquals(ReviewProvider.TMDB, "tmdb".reviewProviderOrNull())
        assertEquals(ReviewProvider.TRAKT, "trakt".reviewProviderOrNull())
    }

    @Test
    fun caseDoesNotMatter() {
        assertEquals(ReviewProvider.TMDB, "TMDB".reviewProviderOrNull())
        assertEquals(ReviewProvider.TRAKT, "TrAkT".reviewProviderOrNull())
    }

    @Test
    fun paddingDoesNotMatter() {
        assertEquals(ReviewProvider.TMDB, "  tmdb  ".reviewProviderOrNull())
        assertEquals(ReviewProvider.TRAKT, "\tTrAkT\n".reviewProviderOrNull())
    }

    @Test
    fun anUnknownSourceIsNotAProvider() {
        assertNull("imdb".reviewProviderOrNull())
        assertNull("".reviewProviderOrNull())
        assertNull("   ".reviewProviderOrNull())
    }

    @Test
    fun aNameThatMerelyContainsAKnownSourceIsNotAKnownSource() {
        assertNull("tmdb.tv".reviewProviderOrNull())
        assertNull("not trakt".reviewProviderOrNull())
        assertNull("tmdb2".reviewProviderOrNull())
    }

    @Test
    fun aLabelIsOnlyWhatTheProviderSaysItIs() {
        assertEquals("TMDB review", "tmdb".providerLabel())
        assertEquals("Trakt review", "TRAKT".providerLabel())
        assertEquals("Review source", "imdb".providerLabel())
        assertEquals("Review source", "".providerLabel())
    }
}
