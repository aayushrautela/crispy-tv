package com.crispy.tv.domain.home

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the random-pick pool rules.
 *
 * These are the only decisions behind the home wheel that are pure, so this
 * suite is what stands between a test suite that passes and a wheel that picks
 * from the wrong set. Three things are asserted here that a build cannot catch:
 * that an unusable rating never reaches a pool, that a genre chip and the pool
 * it spins agree on how a genre is spelled, and that two pools drawn from the
 * same seed are the same pool.
 */
class HomeRandomPoolTest {

    private fun item(
        itemId: String,
        title: String = itemId,
        type: String = "movie",
        genre: String? = null,
        rating: String? = null,
        artworkUrl: String? = null,
    ): HomeCatalogItem = HomeCatalogItem(
        itemId = itemId,
        title = title,
        artworkUrl = artworkUrl,
        addonId = "test-addon",
        type = type,
        rating = rating,
        genre = genre,
    )

    private fun idsOf(candidates: List<HomeRandomCandidate>): List<String> =
        candidates.map { it.itemId }

    // --- toRandomCandidates -------------------------------------------------

    @Test
    fun dropsItemsWithABlankId() {
        val candidates = listOf(
            item(itemId = "  "),
            item(itemId = "kept"),
        ).toRandomCandidates()

        assertEquals(listOf("kept"), idsOf(candidates))
    }

    @Test
    fun dropsItemsWithABlankTitle() {
        val candidates = listOf(
            item(itemId = "no-title", title = "   "),
            item(itemId = "titled"),
        ).toRandomCandidates()

        assertEquals(listOf("titled"), idsOf(candidates))
    }

    @Test
    fun keepsTheFirstOccurrenceOfARepeatedId() {
        val candidates = listOf(
            item(itemId = "dupe", title = "Curated Title"),
            item(itemId = "other", title = "Other"),
            item(itemId = "dupe", title = "Later Title"),
        ).toRandomCandidates()

        assertEquals(listOf("Curated Title", "Other"), candidates.map { it.title })
    }

    @Test
    fun trimsTextFieldsAndTurnsBlankOptionalsIntoNull() {
        val candidate = listOf(
            item(itemId = " one ", title = "  A Title  ", genre = "   ", artworkUrl = " "),
        ).toRandomCandidates().single()

        assertEquals("one", candidate.itemId)
        assertEquals("A Title", candidate.title)
        assertNull(candidate.genre, "blank genre should be null, not empty")
        assertNull(candidate.artworkUrl, "blank artworkUrl should be null, not empty")
    }

    @Test
    fun keepsANonBlankGenreTrimmed() {
        val candidate = listOf(
            item(itemId = "one", genre = "  Sci-Fi "),
        ).toRandomCandidates().single()

        assertEquals("Sci-Fi", candidate.genre)
    }

    @Test
    fun parsesARatingToANumber() {
        val candidate = listOf(
            item(itemId = "one", rating = " 7.3 "),
        ).toRandomCandidates().single()

        assertEquals(7.3, candidate.rating)
    }

    @Test
    fun turnsAnUnreadableRatingIntoNull() {
        val candidates = listOf(
            item(itemId = "blank", rating = "  "),
            item(itemId = "text", rating = "no score"),
            item(itemId = "absent"),
        ).toRandomCandidates()

        assertEquals(listOf<HomeRandomCandidate>(), candidates.filter { it.rating != null })
        assertEquals(3, candidates.size, "an unreadable rating must not drop the item")
    }

    // --- randomPool ---------------------------------------------------------

    @Test
    fun poolExcludesItemsWithNoRating() {
        val pool = listOf(
            item(itemId = "rated", rating = "8.0"),
            item(itemId = "unrated"),
        ).toRandomCandidates().randomPool(genre = null, minRating = 6.0, cap = 50, random = Random(1))

        assertEquals(listOf("rated"), idsOf(pool))
    }

    @Test
    fun poolExcludesItemsBelowTheThreshold() {
        val pool = listOf(
            item(itemId = "below", rating = "5.9"),
            item(itemId = "at", rating = "6.0"),
            item(itemId = "above", rating = "9.9"),
        ).toRandomCandidates().randomPool(genre = null, minRating = 6.0, cap = 50, random = Random(1))

        assertEquals(setOf("at", "above"), idsOf(pool).toSet())
    }

    @Test
    fun poolExcludesARatingThatParsesButIsNotARating() {
        val pool = listOf(
            item(itemId = "not-a-number", rating = "NaN"),
            item(itemId = "infinite", rating = "Infinity"),
            item(itemId = "good", rating = "7.0"),
        ).toRandomCandidates().randomPool(genre = null, minRating = 6.0, cap = 50, random = Random(1))

        assertEquals(listOf("good"), idsOf(pool))
    }

    @Test
    fun poolCutsAtTheCap() {
        val pool = (1..8).map { index ->
            item(itemId = "item-$index", rating = "7.0")
        }.toRandomCandidates().randomPool(genre = null, minRating = 6.0, cap = 3, random = Random(1))

        assertEquals(3, pool.size)
    }

    @Test
    fun poolReturnsEverythingItHasWhenFewerThanTheCapQualify() {
        val pool = listOf(
            item(itemId = "one", rating = "7.0"),
            item(itemId = "two", rating = "7.1"),
        ).toRandomCandidates().randomPool(genre = null, minRating = 6.0, cap = 50, random = Random(1))

        assertEquals(setOf("one", "two"), idsOf(pool).toSet())
    }

    @Test
    fun poolForAGenreIgnoresOtherGenresAndSpellingDifferences() {
        val pool = listOf(
            item(itemId = "sci-fi-a", genre = "Sci-Fi", rating = "7.0"),
            item(itemId = "sci-fi-b", genre = "  sci-fi  ", rating = "7.1"),
            item(itemId = "drama", genre = "Drama", rating = "8.0"),
        ).toRandomCandidates().randomPool(genre = "SCI-FI", minRating = 6.0, cap = 50, random = Random(1))

        assertEquals(setOf("sci-fi-a", "sci-fi-b"), idsOf(pool).toSet())
    }

    @Test
    fun poolForABlankGenreIsEveryGenre() {
        val pool = listOf(
            item(itemId = "action", genre = "Action", rating = "7.0"),
            item(itemId = "drama", genre = "Drama", rating = "8.0"),
        ).toRandomCandidates().randomPool(genre = "   ", minRating = 6.0, cap = 50, random = Random(1))

        assertEquals(setOf("action", "drama"), idsOf(pool).toSet())
    }

    @Test
    fun theSameSeedDrawsTheSamePool() {
        val candidates = listOf(
            item(itemId = "a", genre = "Action", rating = "7.0"),
            item(itemId = "b", genre = "Action", rating = "7.1"),
            item(itemId = "c", genre = "Action", rating = "7.2"),
            item(itemId = "d", genre = "Action", rating = "7.3"),
        ).toRandomCandidates()

        val first = candidates.randomPool(genre = null, minRating = 6.0, cap = 50, random = Random(4242))
        val second = candidates.randomPool(genre = null, minRating = 6.0, cap = 50, random = Random(4242))

        assertEquals(idsOf(first), idsOf(second), "two draws from one seed must agree, or nothing can be asserted")
    }

    @Test
    fun theSeedChangesTheDraw() {
        val candidates = (1..6).map { index ->
            item(itemId = "item-$index", rating = "7.0")
        }.toRandomCandidates()

        val first = idsOf(candidates.randomPool(genre = null, minRating = 6.0, cap = 50, random = Random(1)))
        val second = idsOf(candidates.randomPool(genre = null, minRating = 6.0, cap = 50, random = Random(2)))

        assertTrue(first != second, "two different seeds produced the same order, so the pool is not shuffled")
    }

    // --- topGenres ----------------------------------------------------------

    @Test
    fun topGenresCountsOnlyEligibleItemsWithAGenre() {
        val genres = listOf(
            item(itemId = "a1", genre = "Action", rating = "7.0"),
            item(itemId = "a2", genre = "Action", rating = "8.0"),
            item(itemId = "weak", genre = "Action", rating = "5.0"),
            item(itemId = "unrated", genre = "Action"),
            item(itemId = "drama", genre = "Drama", rating = "7.0"),
            item(itemId = "genre-less", rating = "9.0"),
        ).toRandomCandidates().topGenres(minRating = 6.0, limit = 3)

        assertEquals(listOf(HomeRandomGenre("Action", 2), HomeRandomGenre("Drama", 1)), genres)
    }

    @Test
    fun topGenresGroupsSpellingsButLabelsWithTheFirstSeen() {
        val genres = listOf(
            item(itemId = "a", genre = "Sci-Fi", rating = "7.0"),
            item(itemId = "b", genre = "sci-fi", rating = "7.0"),
        ).toRandomCandidates().topGenres(minRating = 6.0, limit = 3)

        assertEquals(listOf(HomeRandomGenre("Sci-Fi", 2)), genres)
    }

    @Test
    fun topGenresBreaksAnEqualCountByNameNotByArrival() {
        val genres = listOf(
            item(itemId = "h", genre = "Horror", rating = "7.0"),
            item(itemId = "h2", genre = "Horror", rating = "7.0"),
            item(itemId = "d", genre = "Drama", rating = "7.0"),
            item(itemId = "d2", genre = "Drama", rating = "7.0"),
        ).toRandomCandidates().topGenres(minRating = 6.0, limit = 3)

        assertEquals(listOf("Drama", "Horror"), genres.map { it.genre })
    }

    @Test
    fun topGenresStopsAtTheLimit() {
        val genres = (1..5).map { index ->
            item(itemId = "item-$index", genre = "Genre$index", rating = "7.0")
        }.toRandomCandidates().topGenres(minRating = 6.0, limit = 3)

        assertEquals(3, genres.size)
    }

    @Test
    fun topGenresOfNothingEligibleIsEmpty() {
        val genres = listOf(
            item(itemId = "weak", genre = "Action", rating = "2.0"),
        ).toRandomCandidates().topGenres(minRating = 6.0, limit = 3)

        assertEquals(emptyList(), genres)
    }

    // --- a chip and the pool it spins agree ---------------------------------

    @Test
    fun everyTopGenreSpinsANonEmptyPool() {
        val candidates = listOf(
            item(itemId = "a1", genre = "Action", rating = "7.0"),
            item(itemId = "a2", genre = "Action", rating = "7.5"),
            item(itemId = "d1", genre = "Drama", rating = "7.0"),
            item(itemId = "weak", genre = "Comedy", rating = "4.0"),
        ).toRandomCandidates()
        val genres = candidates.topGenres(minRating = 6.0, limit = 3)

        assertEquals(listOf("Action", "Drama"), genres.map { it.genre })
        for (genre in genres) {
            val pool = candidates.randomPool(genre.genre, minRating = 6.0, cap = 50, random = Random(1))
            assertTrue(pool.isNotEmpty(), "chip ${genre.genre} has a count but an empty pool")
        }
    }
}