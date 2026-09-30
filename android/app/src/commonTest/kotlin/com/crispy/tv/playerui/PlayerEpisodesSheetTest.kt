package com.crispy.tv.playerui

import com.crispy.tv.addons.model.MediaVideo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Covers the two decisions `PlayerEpisodesSheet.kt` used to make inline inside two
 * `@Composable` bodies, each written twice.
 *
 * `selectedSeasonOrFirst` was the same two lines at two call sites, one of which
 * only runs after the chip row has scrolled; `visibleEpisodes` was three decisions
 * -- the null-number ordering, the title tie-break and the cap -- inside a single
 * `remember` block. Neither was reachable from a test, and a duplicate written twice
 * is a decision that will drift, so both are now named `internal` functions and the
 * sheet calls them.
 */
class PlayerEpisodesSheetTest {
    private fun video(title: String, season: Int? = 1, episode: Int? = null) = MediaVideo(
        id = title,
        title = title,
        season = season,
        episode = episode,
        released = null,
        overview = null,
        thumbnailUrl = null,
    )

    // region selectedSeasonOrFirst

    @Test
    fun aChosenSeasonWinsOverTheFirst() {
        assertEquals(3, selectedSeasonOrFirst(listOf(1, 2, 3), selectedSeason = 3))
        assertEquals(1, selectedSeasonOrFirst(listOf(1, 2, 3), selectedSeason = 1))
    }

    @Test
    fun anUnchosenSeasonFallsBackToTheFirst() {
        // The state a caller is in before it has ever chosen: the chips render with
        // the first season highlighted rather than with nothing highlighted.
        assertEquals(1, selectedSeasonOrFirst(listOf(1, 2, 3), selectedSeason = null))
    }

    @Test
    fun anEmptySeasonListHasNoSelection() {
        assertNull(selectedSeasonOrFirst(emptyList(), selectedSeason = null))
    }

    @Test
    fun aChosenSeasonSurvivesAnEmptyListRatherThanBeingDiscarded() {
        // The guard is `seasons.isNotEmpty()` on the caller's side, so this
        // combination is unreachable from the sheet -- but the function answers it
        // by keeping the caller's choice rather than inventing one, which is the
        // shape worth pinning.
        assertEquals(4, selectedSeasonOrFirst(emptyList(), selectedSeason = 4))
    }

    @Test
    fun aChosenSeasonThatIsNotInTheListIsStillHonoured() {
        // The sheet filters by season and an empty result renders the empty state;
        // silently substituting the first season instead would show the wrong
        // episodes without any visible error.
        assertEquals(9, selectedSeasonOrFirst(listOf(1, 2), selectedSeason = 9))
    }

    @Test
    fun aSeasonZeroIsASelectionRatherThanAnAbsentOne() {
        // The parameter is `Int?`, so the guard is absence and not zero — and
        // specials are commonly filed under season 0.
        assertEquals(0, selectedSeasonOrFirst(listOf(1, 2), selectedSeason = 0))
    }

    // endregion

    // region visibleEpisodes

    @Test
    fun episodesAreOrderedByNumberNotAsTheBackendSentThem() {
        val ordered = visibleEpisodes(
            listOf(video("c", episode = 3), video("a", episode = 1), video("b", episode = 2)),
        )
        assertEquals(listOf("a", "b", "c"), ordered.map { it.title })
    }

    @Test
    fun anEpisodeWithNoNumberSortsAfterEveryNumberedOne() {
        // `?: Int.MAX_VALUE`, not `?: 0`. A specials row filed with no number must
        // not open the list, and this is the single decision that says which end it
        // goes on.
        val ordered = visibleEpisodes(
            listOf(
                video("Special", episode = null),
                video("one", episode = 1),
                video("two", episode = 2),
            ),
        )
        assertEquals(listOf("one", "two", "Special"), ordered.map { it.title })
    }

    @Test
    fun twoUnnumberedEpisodesAreOrderedByTitle() {
        // The tie-break, so two specials have a stable order rather than whatever
        // order the backend happened to serialise them in.
        val ordered = visibleEpisodes(
            listOf(video("Zulu", episode = null), video("Alpha", episode = null)),
        )
        assertEquals(listOf("Alpha", "Zulu"), ordered.map { it.title })
    }

    @Test
    fun numberingStillWinsOverTitle() {
        // Episode 2 must not sort after episode 10's "Alpha"-alike title; the
        // comparator's first key is the number and the second is the title.
        val ordered = visibleEpisodes(
            listOf(video("Aardvark", episode = 2), video("Zebra", episode = 10)),
        )
        assertEquals(listOf("Aardvark", "Zebra"), ordered.map { it.title })
    }

    @Test
    fun theCapIsFiftyAndItIsAppliedAfterTheSort() {
        // 80 episodes in, 50 out. The 50 that survive must be the *lowest* numbers,
        // which is only true because the cap runs after the sort.
        val many = (1..80).map { video("Episode $it", episode = it) }
        val ordered = visibleEpisodes(many)
        assertEquals(50, ordered.size)
        assertEquals("Episode 1", ordered.first().title)
        assertEquals("Episode 50", ordered.last().title)
    }

    @Test
    fun theCapIsAppliedToTheSortedListAndNotToTheBackendOrder() {
        // **The case above cannot see `take(50)` moving before the sort**, because
        // its fixture is already ascending: capping first and sorting afterwards
        // picks exactly the same fifty rows. This one is the fixture that can. The
        // list is shuffled deterministically -- even numbers descending, then odd
        // numbers descending -- so the *first* fifty entries of the input are
        // episodes 80..31 and 79..50, none of which are the lowest fifty numbers.
        //
        // A sort-then-cap answers 1..50. A cap-then-sort answers whatever those
        // first fifty happen to be, and the assertion below names both ends so the
        // two orders cannot produce the same list.
        val shuffled = (2..80 step 2).reversed() + (1..79 step 2).reversed()
        assertEquals(80, shuffled.size)
        // The input's first row is episode 80, so a cap-then-sort would have kept it.
        assertEquals(80, shuffled.first())
        val ordered = visibleEpisodes(shuffled.map { video("Episode $it", episode = it) })
        assertEquals(50, ordered.size)
        assertEquals("Episode 1", ordered.first().title)
        assertEquals("Episode 50", ordered.last().title)
        assertFalse(ordered.any { it.episode == 80 }, "episode 80 survived, so the cap ran before the sort")
    }

    @Test
    fun aCapOfExactlyFiftyIsNotTruncated() {
        assertEquals(50, visibleEpisodes((1..50).map { video("Episode $it", episode = it) }).size)
    }

    @Test
    fun aListShorterThanTheCapIsReturnedWhole() {
        assertEquals(3, visibleEpisodes((1..3).map { video("Episode $it", episode = it) }).size)
    }

    @Test
    fun anEmptyListIsEmpty() {
        assertEquals(emptyList(), visibleEpisodes(emptyList()))
    }

    // endregion
}
