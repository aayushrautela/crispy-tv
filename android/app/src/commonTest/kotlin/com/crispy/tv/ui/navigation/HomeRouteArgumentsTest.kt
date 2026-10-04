package com.crispy.tv.ui.navigation

import androidx.lifecycle.SavedStateHandle
import com.crispy.tv.ui.navigation.AppRoutes.HomeDetailsArtworkUrlArg
import com.crispy.tv.ui.navigation.AppRoutes.HomeDetailsAutoOpenEpisodeArg
import com.crispy.tv.ui.navigation.AppRoutes.HomeDetailsHighlightEpisodeIdArg
import com.crispy.tv.ui.navigation.AppRoutes.HomeDetailsItemIdArg
import com.crispy.tv.ui.navigation.AppRoutes.HomeDetailsItemTypeArg
import com.crispy.tv.ui.navigation.AppRoutes.HomeDetailsRuntimeAbsoluteEpisodeArg
import com.crispy.tv.ui.navigation.AppRoutes.HomeDetailsRuntimeEpisodeNumberArg
import com.crispy.tv.ui.navigation.AppRoutes.HomeDetailsRuntimeSeasonNumberArg
import com.crispy.tv.ui.navigation.AppRoutes.HomeDetailsSharedElementKeyArg
import com.crispy.tv.ui.navigation.AppRoutes.PersonDetailsPersonIdArg
import com.crispy.tv.ui.navigation.AppRoutes.PersonDetailsProfileUrlArg
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The mapping from a destination's argument set to the three `*RouteArgs` values.
 *
 * ## Why this suite exists at all, given that the mapping is "just" a map lookup
 *
 * Because until these readers moved into `commonMain` there was **nowhere** a test could
 * have reached them: they were three closures in `AppNavHostDependenciesAndroid.kt`, and
 * their own KDoc called them "a platform read with no logic in it to test". That claim was
 * half right -- they had almost no logic -- and it was the wrong conclusion, because the
 * logic that *does* exist is the part that is easiest to get wrong silently: which key is
 * read with which type, and what a key that was never written answers. Those are exactly
 * the two things a reader cannot see by reading it, and exactly the two things a rename
 * would break.
 *
 * So the suite pins the key names against the graph's own `navArgument` declarations
 * rather than against a copy of them, pins the type each key is read as, and pins the
 * two absent-key answers.
 */
class HomeRouteArgumentsTest {

    /**
     * Every key the details reader reads, against the type it reads it as.
     *
     * Written out rather than derived, because the value of the assertion *is* that it is
     * an independent list: a reader that quietly starts reading `item_type` for `item_id`
     * passes every other case in this file.
     */
    private val detailsKeys = listOf(
        HomeDetailsItemIdArg,
        HomeDetailsItemTypeArg,
        HomeDetailsHighlightEpisodeIdArg,
        HomeDetailsAutoOpenEpisodeArg,
        HomeDetailsRuntimeSeasonNumberArg,
        HomeDetailsRuntimeEpisodeNumberArg,
        HomeDetailsRuntimeAbsoluteEpisodeArg,
        HomeDetailsArtworkUrlArg,
        HomeDetailsSharedElementKeyArg,
    )

    @Test
    fun detailsArgumentsAreTheValuesTheDestinationDeclares() {
        val handle = SavedStateHandle(
            mapOf(
                HomeDetailsItemIdArg to "tt1234",
                HomeDetailsItemTypeArg to "movie",
                HomeDetailsHighlightEpisodeIdArg to "s01e02",
                HomeDetailsAutoOpenEpisodeArg to true,
                HomeDetailsRuntimeSeasonNumberArg to "1",
                HomeDetailsRuntimeEpisodeNumberArg to "2",
                HomeDetailsRuntimeAbsoluteEpisodeArg to "7",
                HomeDetailsArtworkUrlArg to "https://example.test/a.jpg",
                HomeDetailsSharedElementKeyArg to "hero-tt1234",
            ),
        )

        assertEquals(
            HomeDetailsRouteArgs(
                itemId = "tt1234",
                itemType = "movie",
                highlightEpisodeId = "s01e02",
                autoOpenEpisode = true,
                runtimeSeasonNumber = "1",
                runtimeEpisodeNumber = "2",
                runtimeAbsoluteEpisodeNumber = "7",
                initialArtworkUrl = "https://example.test/a.jpg",
                sharedElementKey = "hero-tt1234",
            ),
            detailsRouteArguments(handle),
        )
    }

    /**
     * Each declared key feeds exactly one field, with its own value, and nothing else does.
     *
     * As a *set* comparison rather than a hand-picked sample, because the property is the
     * pairing: a reader that read `HomeDetailsItemTypeArg` where it should read
     * `HomeDetailsItemIdArg` passes a suite that only checks populated values, because both
     * keys are strings. So each key is presented **alone**, and the assertion is that
     * exactly one field moved and it holds that key's sample value.
     *
     * The sample values are per-key typed, and that is not decoration: the boolean key is
     * read as a `Boolean`, so a handle of nine strings -- the obvious way to build "all
     * keys present" -- is a `ClassCastException` rather than a test failure. That is the
     * documented type contract, so the fixture has to honour it.
     */
    @Test
    fun everyDeclaredKeyFeedsExactlyOneFieldAndNothingElseDoes() {
        val sample = mapOf(
            HomeDetailsItemIdArg to "v-item-id",
            HomeDetailsItemTypeArg to "v-item-type",
            HomeDetailsHighlightEpisodeIdArg to "v-highlight",
            HomeDetailsAutoOpenEpisodeArg to true,
            HomeDetailsRuntimeSeasonNumberArg to "v-season",
            HomeDetailsRuntimeEpisodeNumberArg to "v-episode",
            HomeDetailsRuntimeAbsoluteEpisodeArg to "v-absolute",
            HomeDetailsArtworkUrlArg to "v-artwork",
            HomeDetailsSharedElementKeyArg to "v-shared-key",
        )
        assertEquals(detailsKeys.toSet(), sample.keys, "the sample and the declared list disagree")
        assertEquals(detailsKeys.size, detailsKeys.distinct().size, "the list above repeats a key")

        val empty = detailsRouteArguments(SavedStateHandle(emptyMap())).fields()
        sample.forEach { (key, value) ->
            val read = detailsRouteArguments(SavedStateHandle(mapOf(key to value))).fields()
            val changed = read.filter { (field, fieldValue) -> fieldValue != empty[field] }

            assertEquals(1, changed.size, "key $key moved ${changed.keys}")
            assertEquals(value, changed.values.single(), "key $key landed on the wrong field")
        }
    }

    /** The nine fields by name, so "which field moved" is a question the suite can ask. */
    private fun HomeDetailsRouteArgs.fields(): Map<String, Any?> = mapOf(
        "itemId" to itemId,
        "itemType" to itemType,
        "highlightEpisodeId" to highlightEpisodeId,
        "autoOpenEpisode" to autoOpenEpisode,
        "runtimeSeasonNumber" to runtimeSeasonNumber,
        "runtimeEpisodeNumber" to runtimeEpisodeNumber,
        "runtimeAbsoluteEpisodeNumber" to runtimeAbsoluteEpisodeNumber,
        "initialArtworkUrl" to initialArtworkUrl,
        "sharedElementKey" to sharedElementKey,
    )

    /**
     * A key that was never written answers `null`, which is what `Bundle.getString` on an
     * absent key answered -- so this is the mapping preserving the old behaviour rather
     * than a decision about it. The boolean is the exception and needs its own case: it is
     * read as a `Boolean?` and compared to `true`, so an absent key is `false` and cannot
     * be told from an explicit `false`.
     */
    @Test
    fun anEmptyHandleReadsAsNoValueRatherThanAsABlankOne() {
        val read = detailsRouteArguments(SavedStateHandle(emptyMap()))

        assertNull(read.itemId)
        assertNull(read.itemType)
        assertNull(read.highlightEpisodeId)
        assertNull(read.runtimeSeasonNumber)
        assertNull(read.runtimeEpisodeNumber)
        assertNull(read.runtimeAbsoluteEpisodeNumber)
        assertNull(read.initialArtworkUrl)
        assertNull(read.sharedElementKey)
        assertEquals(false, read.autoOpenEpisode)
    }

    /**
     * The `== true` on the boolean, pinned from both sides: it must not read `false` as
     * `true`, and it must not turn an absent key into a value either. Without the `== true`
     * the field would be a `Boolean?` and the graph's own `defaultValue = false` would be
     * invisible here.
     */
    @Test
    fun autoOpenEpisodeIsOnlyTrueWhenTheKeySaysTrue() {
        assertEquals(
            true,
            detailsRouteArguments(SavedStateHandle(mapOf(HomeDetailsAutoOpenEpisodeArg to true)))
                .autoOpenEpisode,
        )
        assertEquals(
            false,
            detailsRouteArguments(SavedStateHandle(mapOf(HomeDetailsAutoOpenEpisodeArg to false)))
                .autoOpenEpisode,
        )
    }

    /**
     * A blank string stays a blank string. The reader is not where "blank means absent" is
     * decided -- the graph does that with `ifBlank { null }` -- and this case is what stops
     * somebody "tidying" the blank into a null here and quietly changing the graph's
     * answer rather than moving its rule.
     */
    @Test
    fun aBlankValueIsPassedThroughUnchanged() {
        val read = detailsRouteArguments(
            SavedStateHandle(
                mapOf(
                    HomeDetailsItemIdArg to "",
                    HomeDetailsItemTypeArg to "",
                    HomeDetailsHighlightEpisodeIdArg to "",
                    HomeDetailsSharedElementKeyArg to "",
                ),
            ),
        )

        assertEquals("", read.itemId)
        assertEquals("", read.itemType)
        assertEquals("", read.highlightEpisodeId)
        assertEquals("", read.sharedElementKey)
    }

    @Test
    fun personArgumentsAreTheValuesTheDestinationDeclares() {
        assertEquals(
            HomePersonRouteArgs(personId = "nm42", profileUrl = "https://example.test/n.jpg"),
            personRouteArguments(
                SavedStateHandle(
                    mapOf(
                        PersonDetailsPersonIdArg to "nm42",
                        PersonDetailsProfileUrlArg to "https://example.test/n.jpg",
                    ),
                ),
            ),
        )
    }

    @Test
    fun anEmptyHandleReadsAsNoPerson() {
        val read = personRouteArguments(SavedStateHandle(emptyMap()))

        assertNull(read.personId)
        assertNull(read.profileUrl)
    }

    @Test
    fun catalogArgumentsAreTheValuesTheDestinationDeclares() {
        assertEquals(
            HomeCatalogRouteArgs(catalogId = "personal:1", title = "My list"),
            catalogRouteArguments(
                SavedStateHandle(
                    mapOf(
                        AppRoutes.CatalogIdArg to "personal:1",
                        AppRoutes.CatalogTitleArg to "My list",
                    ),
                ),
            ),
        )
    }

    /**
     * The catalog's `title` is declared with `defaultValue = ""` and the details' id is
     * declared without one, so the graph can see one and not the other. Nothing here
     * decides that; the case records that an absent title is `null` rather than `""`, so
     * the difference between a defaulting key and a non-defaulting one is visible in the
     * read and not only in the graph's declaration.
     */
    @Test
    fun anEmptyHandleReadsAsNoCatalog() {
        val read = catalogRouteArguments(SavedStateHandle(emptyMap()))

        assertNull(read.catalogId)
        assertNull(read.title)
    }

    /**
     * Three readers over one handle holding one key per destination: each answers for the
     * key that is its own and reports the other two as absent.
     *
     * The property is the *disjointness* of the key sets. A reader that fell back to a
     * neighbouring key would still pass every single-destination case above, because each
     * of those supplies exactly the keys its reader owns.
     */
    @Test
    fun eachReaderAnswersOnlyItsOwnDestinationsKeys() {
        val handle = SavedStateHandle(
            mapOf(
                HomeDetailsItemIdArg to "tt1234",
                PersonDetailsPersonIdArg to "nm42",
                AppRoutes.CatalogIdArg to "personal:1",
            ),
        )

        val details = detailsRouteArguments(handle)
        assertEquals("tt1234", details.itemId)
        assertNull(details.itemType)

        val person = personRouteArguments(handle)
        assertEquals("nm42", person.personId)
        assertNull(person.profileUrl)

        val catalog = catalogRouteArguments(handle)
        assertEquals("personal:1", catalog.catalogId)
        assertNull(catalog.title)
    }
}
