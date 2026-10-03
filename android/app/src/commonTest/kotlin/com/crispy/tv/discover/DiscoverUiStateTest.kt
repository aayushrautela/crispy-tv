package com.crispy.tv.discover

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * [DiscoverUiState.comboKey] and nothing else in this package.
 *
 * `DiscoverViewModel.items` maps the state through `comboKey`, calls
 * `distinctUntilChanged()`, and only then `flatMapLatest`s a new `Pager`. **So the key's
 * format is a paging decision, not a display string**: widen it and every screen
 * recomposition rebuilds the pager, and narrow it -- two different states colliding -- and
 * changing a filter silently does nothing. Nothing about that is visible from the screen,
 * and `BackendBrowseRepository` being a `class` means the ViewModel's own three
 * `set*Filter` methods cannot be reached from a test at all. The key has no collaborators,
 * so it is reachable, which is the only reason this file exists.
 */
class DiscoverUiStateTest {

    @Test
    fun theDefaultStateKeysAsAllGenresTrending() {
        assertEquals("all||popularity", DiscoverUiState().comboKey)
    }

    @Test
    fun theKeyCarriesEachFiltersValueAndNotItsLabel() {
        // The enum has both a `label` for the chip and a `value` for the backend query.
        // This is the pair that settles which one the key uses, and it settles it because
        // the two differ: the Series chip reads "Shows" and queries `series`.
        val state = DiscoverUiState(
            typeFilter = DiscoverTypeFilter.Series,
            sortFilter = DiscoverSortFilter.Rating,
        )
        assertEquals("series||rating", state.comboKey)
        assertNotEquals("Shows||Rating", state.comboKey)
    }

    @Test
    fun everyCombinationOfBothFilterEnumsProducesADistinctKey() {
        // A completeness sweep, not a sample: a new enum constant has to fail this suite
        // rather than quietly produce a key that collides with an existing one.
        val keys = DiscoverTypeFilter.entries.flatMap { type ->
            DiscoverSortFilter.entries.map { sort ->
                DiscoverUiState(typeFilter = type, sortFilter = sort).comboKey
            }
        }
        assertEquals(
            DiscoverTypeFilter.entries.size * DiscoverSortFilter.entries.size,
            keys.size,
        )
        assertEquals(keys.size, keys.toSet().size, "two filter combinations share a key: $keys")
    }

    @Test
    fun aNullGenreAndABlankGenreKeyAreTheSameKey() {
        // `genreKey.orEmpty()` means the middle segment cannot distinguish "no genre
        // chosen" from "a genre chosen whose key is the empty string". That is
        // deliberate -- the chip list never offers an empty key -- but it is the one place
        // two genuinely different states collapse, so it is pinned here rather than left to
        // be discovered by a genre that stops working.
        val none = DiscoverUiState(genreKey = null)
        val blank = DiscoverUiState(genreKey = "")
        assertEquals(none.comboKey, blank.comboKey)
    }

    @Test
    fun aGenreKeyChangesTheKeyEvenWhenItIsNotShownInTheChipLabel() {
        // `genreLabel` is what the chip reads; `genreKey` is what the query uses. If the key
        // were built from the label, two genres sharing a label would share a pager.
        val action = DiscoverUiState(genreKey = "action", genreLabel = "Action")
        val adventure = DiscoverUiState(genreKey = "adventure", genreLabel = "Action")
        assertNotEquals(action.comboKey, adventure.comboKey)
        assertEquals("all|action|popularity", action.comboKey)
        assertEquals("all|adventure|popularity", adventure.comboKey)
    }

    @Test
    fun theKeyIsDelimitedSoTwoDifferentSplitsCannotCollide() {
        // The composite-key question. Segments are joined with `|`, and the one segment
        // that could contain a delimiter is the genre key, which comes from a fixed list
        // rather than from a user's text -- but the pair below is the shape that would
        // collide if the genre key ever became free text, so it is pinned now rather than
        // argued about later.
        val first = DiscoverUiState(genreKey = "a|b", sortFilter = DiscoverSortFilter.Trending)
        val second = DiscoverUiState(genreKey = "a", sortFilter = DiscoverSortFilter.Rating)
        assertNotEquals(first.comboKey, second.comboKey)
    }

    @Test
    fun everyFilterValueReachesTheKeyAndEveryLabelIsIgnored() {
        // States the other rules together, so a rewrite that drops one segment entirely
        // fails here even though the single-filter cases above would each still pass.
        val byType = DiscoverTypeFilter.entries.associate { filter ->
            filter to DiscoverUiState(typeFilter = filter).comboKey.substringBefore('|')
        }
        assertEquals(
            DiscoverTypeFilter.entries.map { it.value }.toSet(),
            byType.values.toSet(),
        )
        assertNotEquals(byType.values.toSet(), DiscoverTypeFilter.entries.map { it.label }.toSet())
    }
}
