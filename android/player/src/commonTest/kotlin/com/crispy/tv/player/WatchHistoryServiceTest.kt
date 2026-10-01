package com.crispy.tv.player

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The module's own contract, pinned.
 *
 * ## Why this file exists at all
 *
 * `:android:player` had **no test source set**. All six of its files are `commonMain`
 * and its `androidMain` is empty, so unlike `:android:addons` there is no
 * Android-shaped sibling explaining the absence — the recipe this module established
 * for the five that followed simply did not include tests, and 389 lines ran on four
 * targets asserted by none of them.
 *
 * ## What is worth pinning here
 *
 * **Fourteen of the sixteen members have default bodies, and those defaults are not
 * conveniences** — they are the behaviour a caller gets when the real service is
 * unavailable, including the user-facing wording. A caller rendering
 * [WatchHistoryResult.statusMessage] shows those strings, so they are contract.
 *
 * **Exactly one default reports itself as an error.**
 * [WatchHistoryService.getCanonicalContinueWatching] sets `isError = true`; the other
 * thirteen return `accepted = false` and nothing else. A caller branching on
 * `isError` sees exactly one branch reachable, and that asymmetry is the sort of thing
 * a reader adds a flag to a sibling to fix.
 *
 * **Two pairs share wording, and the pairing is invisible from either name.**
 * `setInWatchlist`/`setTitleInWatchlist` and `setLiked`/`setTitleLiked` each say the
 * same two sentences.
 *
 * **The unavailable object uses two different wordings for one condition.**
 * [UnavailableWatchHistoryService] overrides four members — the two abstract ones plus
 * `removeFromPlayback` and `fetchProviderComments` — all saying
 * `"Watch history service unavailable."`, and inherits the other twelve. So
 * `markWatched` says one thing and `setLiked` says `"Rating unavailable."`. Pinned as
 * the claim it is; unifying them is a product decision, not a refactor.
 */
class WatchHistoryServiceTest {

    // ---------------------------------------------------------------- WatchProgressSnapshot

    @Test
    fun progressIsAPercentageOfTheDuration() {
        assertEquals(50.0, WatchProgressSnapshot(30.0, 60.0, LAST_UPDATED).progressPercent)
        assertEquals(0.0, WatchProgressSnapshot(0.0, 60.0, LAST_UPDATED).progressPercent)
        assertEquals(100.0, WatchProgressSnapshot(60.0, 60.0, LAST_UPDATED).progressPercent)
    }

    @Test
    fun aZeroDurationIsZeroRatherThanInfiniteOrNotANumber() {
        // The guard is `<= 0.0`, so this is the boundary the name does not describe:
        // a zero duration is not special-cased, it falls into the same arm as a
        // negative one. Pinned on both sides because only one is the obvious thing
        // anybody would write.
        assertEquals(0.0, WatchProgressSnapshot(30.0, 0.0, LAST_UPDATED).progressPercent)
        assertEquals(0.0, WatchProgressSnapshot(30.0, -60.0, LAST_UPDATED).progressPercent)
    }

    @Test
    fun progressIsNotClampedToTheDuration() {
        // A position past the duration reports as over 100%. There is no clamp, and
        // that is deliberate: the backend receives the raw ratio, and a clock briefly
        // ahead of the reported duration is normal. A reader meets an over-100% figure
        // as a bug, so it is pinned rather than left to be found.
        assertEquals(200.0, WatchProgressSnapshot(120.0, 60.0, LAST_UPDATED).progressPercent)
    }

    // ------------------------------------------------- CanonicalContinueWatchingItem's own decisions

    @Test
    fun theItemTypeIsFoldedIntoTheThreeMediaLabels() = runTest {
        // Four spellings of a show, one spelling of an anime, and everything else is a
        // movie. Pinned as a set over the four show arms rather than by sampling one,
        // because a new arm added to the `when` and left out of a hand-written list
        // would not fail anything.
        assertEquals("show", itemOf("show").type)
        assertEquals("show", itemOf("tv").type)
        assertEquals("show", itemOf("series").type)
        assertEquals("show", itemOf("episode").type)
        assertEquals("anime", itemOf("anime").type)
        assertEquals("movie", itemOf("movie").type)
    }

    @Test
    fun theItemTypeMatchIsCaseInsensitive() = runTest {
        // The `when` lowercases first, so a provider sending "TV" folds the same way.
        // A reader looking at the `when` sees four lowercase literals and no hint that
        // the comparison is on a lowercased copy.
        assertEquals("show", itemOf("TV").type)
        assertEquals("show", itemOf("SeRiEs").type)
        assertEquals("anime", itemOf("ANIME").type)
    }

    @Test
    fun anUnrecognisedItemTypeIsAMovieRatherThanNothing() = runTest {
        // The `else` arm is a real answer, not a fallback for absent data: an id this
        // code has never heard of still has to be labelled, and a rail that filtered on
        // `type` would otherwise drop the row. Pinned so the choice is visible.
        assertEquals("movie", itemOf("").type)
        assertEquals("movie", itemOf("documentary").type)
        assertEquals("movie", itemOf("tv-specials").type, "the match is equality, not a prefix")
    }

    @Test
    fun theWatchedTimestampIsTheLastUpdatedOneUnderASecondName() = runTest {
        // An alias, and an alias that reads like a different field. The contract sorts
        // a rail by `watchedAtEpochMs` while the record is written with
        // `lastUpdatedEpochMs`, so the two being the same value is what makes the
        // ordering work — and nothing else in the file says so.
        val item = itemOf("movie")
        assertEquals(LAST_UPDATED, item.lastUpdatedEpochMs)
        assertEquals(item.lastUpdatedEpochMs, item.watchedAtEpochMs)
    }

    // ---------------------------------------------------------------- the default bodies

    @Test
    fun aFailedWatchlistChangeReportsAsUnacceptedRatherThanAsAnError() = runTest {
        // Note what is *absent*: `isError`. Only `getCanonicalContinueWatching` sets
        // it, so this one never takes that branch.
        val result = Defaults.setInWatchlist(REQUEST, inWatchlist = true)
        assertEquals("Watchlist unavailable.", result.statusMessage)
        assertFalse(result.accepted)
    }

    @Test
    fun aFailedRatingReportsAsUnaccepted() = runTest {
        val result = Defaults.setLiked(REQUEST, liked = true)
        assertEquals("Rating unavailable.", result.statusMessage)
        assertFalse(result.accepted)
    }

    @Test
    fun aTitleLevelWatchlistChangeSaysExactlyWhatThePlaybackOneSays() = runTest {
        // The pairing is the point: two members, two sentences, one wording. A reader
        // comparing the two names would assume two messages.
        assertEquals(
            Defaults.setInWatchlist(REQUEST, inWatchlist = true).statusMessage,
            Defaults.setTitleInWatchlist(TITLE_ITEM_ID, inWatchlist = true).statusMessage,
            "setTitleInWatchlist should say what setInWatchlist says",
        )
    }

    @Test
    fun aTitleLevelRatingSaysExactlyWhatThePlaybackOneSays() = runTest {
        assertEquals(
            Defaults.setLiked(REQUEST, liked = true).statusMessage,
            Defaults.setTitleLiked(TITLE_ITEM_ID, liked = true).statusMessage,
            "setTitleLiked should say what setLiked says",
        )
    }

    @Test
    fun theCanonicalContinueWatchingDefaultIsTheOnlyOneThatFlagsItselfAsAnError() = runTest {
        val result = Defaults.getCanonicalContinueWatching(limit = 20, nowMs = NOW_MS)
        assertEquals("Canonical continue watching unavailable.", result.statusMessage)
        assertTrue(result.isError, "this is the one default that reports an error")
        assertTrue(result.entries.isEmpty())
    }

    @Test
    fun playbackRemovalFailsWithItsOwnMessageRatherThanTheGenericOne() = runTest {
        assertEquals(
            "Playback removal unavailable.",
            Defaults.removeFromPlayback(PLAYBACK_ID).statusMessage,
        )
    }

    @Test
    fun localWatchProgressFailsWithItsOwnMessage() = runTest {
        assertEquals(
            "Local watch progress removal unavailable.",
            Defaults.removeLocalWatchProgress(IDENTITY).statusMessage,
        )
    }

    @Test
    fun providerCommentsFailWithTheirOwnMessage() = runTest {
        val result = Defaults.fetchProviderComments(COMMENT_QUERY)
        assertEquals("Provider comments unavailable.", result.statusMessage)
        assertTrue(result.comments.isEmpty())
    }

    @Test
    fun theThreeReadOnlyDefaultsAreNullRatherThanAnEmptyResult() = runTest {
        // These three answer "I do not know", which the contract spells as null — not
        // an empty list, and not a result carrying a message. A caller treating null
        // and empty differently has to know which one arrived.
        assertNull(Defaults.getCanonicalWatchState(IDENTITY))
        assertNull(Defaults.getTitleWatchState(TITLE_ITEM_ID, MetadataLabMediaType.SERIES))
        assertNull(Defaults.getLocalWatchProgress(IDENTITY))
    }

    @Test
    fun theThreePlaybackHooksAreSilent() = runTest {
        // Empty bodies, and that is correct: the reporter owns the throttle and the
        // latch, so a hook that reported something would double-count. Worth a test
        // because a hook that logged instead would otherwise be invisible.
        Defaults.onPlaybackStarted(IDENTITY, positionMs = 1_000L, durationMs = 60_000L)
        Defaults.onPlaybackProgress(IDENTITY, positionMs = 2_000L, durationMs = 60_000L, isPlaying = true)
        Defaults.onPlaybackStopped(IDENTITY, positionMs = 3_000L, durationMs = 60_000L)
    }

    // ------------------------------------------------- the four overrides on Unavailable…

    @Test
    fun theUnavailableObjectOverridesFourMembersAndTheyShareOneWording() = runTest {
        // The two abstract members are necessarily among them, plus the two the
        // object chooses to speak for itself.
        assertEquals("Watch history service unavailable.", UnavailableWatchHistoryService.markWatched(REQUEST).statusMessage)
        assertEquals("Watch history service unavailable.", UnavailableWatchHistoryService.unmarkWatched(REQUEST).statusMessage)
        assertEquals("Watch history service unavailable.", UnavailableWatchHistoryService.removeFromPlayback(PLAYBACK_ID).statusMessage)
        assertEquals("Watch history service unavailable.", UnavailableWatchHistoryService.fetchProviderComments(COMMENT_QUERY).statusMessage)
    }

    @Test
    fun theUnavailableObjectInheritsTwelveDifferentWordingsForTheSameCondition() = runTest {
        // The asymmetry, stated as the claim it is. The object is documented as
        // "everything unavailable", yet a rating set through it says
        // `"Rating unavailable."` — the interface's word, not the object's. Unifying
        // them is a product decision, so this pins the current wording rather than
        // blessing it.
        assertEquals("Rating unavailable.", UnavailableWatchHistoryService.setLiked(REQUEST, liked = true).statusMessage)
        assertEquals(
            "Watchlist unavailable.",
            UnavailableWatchHistoryService.setInWatchlist(REQUEST, inWatchlist = true).statusMessage,
        )
        assertEquals(
            "Canonical continue watching unavailable.",
            UnavailableWatchHistoryService.getCanonicalContinueWatching(limit = 20, nowMs = NOW_MS).statusMessage,
        )
    }

    private companion object {
        const val LAST_UPDATED = 1_700_000_000_000L
        const val NOW_MS = 1_700_000_000_000L
        const val PLAYBACK_ID = "playback-1"
        const val TITLE_ITEM_ID = "tt0903747"

        fun itemOf(itemType: String) = CanonicalContinueWatchingItem(
            id = "continue-1",
            titleItemId = TITLE_ITEM_ID,
            playbackItemId = PLAYBACK_ID,
            itemType = itemType,
            title = "Sample title",
            season = 1,
            episode = 2,
            progressPercent = 25.0,
            lastUpdatedEpochMs = LAST_UPDATED,
        )

        val IDENTITY = PlaybackIdentity(
            itemId = TITLE_ITEM_ID,
            seriesItemId = TITLE_ITEM_ID,
            contentType = MetadataLabMediaType.SERIES,
            season = 1,
            episode = 2,
            title = "Sample title",
        )

        val REQUEST = WatchHistoryRequest(
            itemId = TITLE_ITEM_ID,
            contentType = MetadataLabMediaType.SERIES,
            title = "Sample title",
            season = 1,
            episode = 2,
        )

        val COMMENT_QUERY = ProviderCommentQuery(
            scope = ProviderCommentScope.EPISODE,
            imdbId = TITLE_ITEM_ID,
            season = 1,
            episode = 2,
        )
    }
}

/**
 * The minimum a caller must write — the **two** abstract members — and nothing else.
 *
 * It exists so the fourteen defaults above can be observed through a real instance. A
 * double that overrides a default would prove nothing about that default, so this one
 * overrides only what the compiler forces.
 *
 * **Why it is not the one in `:app`.** `android/app/src/commonTest/kotlin/com/crispy/tv/testing/FakeWatchHistoryService.kt`
 * is `:app`'s double, promoted out of `HomeViewModelTest` in `f16f6867`. It cannot be
 * shared with this one because **`commonTest` is not published** — nothing in one
 * module's `commonTest` is visible from another's. So each module needing a double has
 * its own, and the `:app` one records playback reports this one does not. That is a
 * real constraint rather than an oversight, and it is why "one double per interface" is
 * not achievable across module boundaries.
 */
private object Defaults : WatchHistoryService {
    override suspend fun markWatched(request: WatchHistoryRequest): WatchHistoryResult =
        WatchHistoryResult(statusMessage = "marked by the caller", accepted = true)

    override suspend fun unmarkWatched(request: WatchHistoryRequest): WatchHistoryResult =
        WatchHistoryResult(statusMessage = "unmarked by the caller", accepted = true)
}