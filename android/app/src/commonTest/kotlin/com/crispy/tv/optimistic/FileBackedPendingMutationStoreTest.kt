package com.crispy.tv.optimistic

import com.crispy.tv.domain.optimistic.EpisodeWatchedMutation
import com.crispy.tv.domain.optimistic.MediaContentType
import com.crispy.tv.domain.optimistic.MutationStatus
import com.crispy.tv.domain.optimistic.RatingMutation
import com.crispy.tv.domain.optimistic.SeasonWatchedMutation
import com.crispy.tv.domain.optimistic.TitleWatchedMutation
import com.crispy.tv.domain.optimistic.UserMutation
import com.crispy.tv.domain.optimistic.WatchlistMutation
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The wire format of [FileBackedPendingMutationStore], which had **no coverage at
 * all** before this file existed, and the reason is the whole point of the suite.
 *
 * The store was `androidMain` for three stated reasons -- `org.json`, then
 * `java.io.File` and `Dispatchers.IO` on top of it. The `org.json` one was
 * discharged earlier and the file stayed put, because the other two would have
 * remained. **A format with no test is a format whose only evidence is that it has
 * not been noticed yet**, and the two remaining reasons were both *seams* wearing
 * the costume of pins: a `FileSystem` is a constructor parameter on an injected
 * filesystem, and a `CoroutineDispatcher` is a constructor parameter on an
 * injected dispatcher. Neither was ever a wall; they were un-injected dependencies
 * in a class nobody could construct.
 *
 * So every test here drives the **public** [PendingMutationStore] contract against
 * okio's in-memory [FakeFileSystem], which is why this is a `commonTest` and runs
 * on Android, desktop and both Apple test targets. It is a `commonTest` because
 * the code under test is now in `commonMain` -- a test follows the source set of
 * the code it covers, and an `androidMain` class could not be reached from here at
 * all.
 *
 * Two things this suite deliberately does **not** do, both because a fixture that
 * cannot fail is decoration:
 *
 * - It does not assert on the serialized text for its own sake. Asserting
 *   `encode` produced `{"type":"WATCHLIST",...}` would pin key order and spelling
 *   and break on every additive change while proving nothing a round trip does
 *   not. **The format's contract is what survives a write/read cycle, so every
 *   structural test here is a round trip**, and the raw-text cases exist only for
 *   the three inputs that a round trip *cannot* produce -- see the three tests
 *   named for them.
 * - It does not use a real filesystem. `FakeFileSystem` answers the same
 *   `metadataOrNull`/`source`/`sink`/`createDirectories` contract `FileSystem.SYSTEM`
 *   does, so the store cannot tell them apart, and the alternative is a test that
 *   writes to the developer's disk.
 */
class FileBackedPendingMutationStoreTest {

    private val path: Path = "/data/queue/pending_mutations.json".toPath()

    private fun storeOn(
        fs: FakeFileSystem,
        at: Path = path,
    ) = FileBackedPendingMutationStore(
        fileSystem = fs,
        path = at,
        // The dispatcher is a required parameter and this is why: the store does
        // blocking file IO inside `withContext`, and the test has to say which
        // dispatcher it is on rather than inherit a real one. `Unconfined` keeps
        // the assertions synchronous without `advanceUntilIdle`.
        ioDispatcher = UnconfinedTestDispatcher(),
    )

    private fun FakeFileSystem.writeRaw(text: String) {
        createDirectories(requireNotNull(path.parent))
        sink(path).buffer().use { it.writeUtf8(text) }
    }

    // ---- the round trip, for every kind ------------------------------------

    @Test
    fun everyMutationKindSurvivesAWriteAndRead() = runTest {
        val fs = FakeFileSystem()
        val store = storeOn(fs)
        val original = listOf(
            watchlist(desired = true),
            watchlist(id = "m2", desired = false),
            titleWatched(contentType = MediaContentType.SERIES),
            titleWatched(id = "m4", contentType = MediaContentType.ANIME, desired = false),
            rating(desired = true),
            rating(id = "m6", desired = false),
            rating(id = "m7", desired = null),
            episodeWatched(episode = 4),
            episodeWatched(id = "m9", episode = null),
            seasonWatched(seasonNumber = 2),
        )

        store.saveAll(original)

        assertEquals(original, store.loadAll())
    }

    @Test
    fun everyStatusSurvivesAWriteAndRead() = runTest {
        val fs = FakeFileSystem()
        val store = storeOn(fs)
        val original = listOf(
            watchlist(id = "a", status = MutationStatus.Pending),
            watchlist(id = "b", status = MutationStatus.Failed(reason = "server said no", retryable = false)),
            watchlist(id = "c", status = MutationStatus.Conflict(serverValue = "true")),
            watchlist(id = "d", status = MutationStatus.Conflict(serverValue = null)),
        )

        store.saveAll(original)

        assertEquals(original, store.loadAll())
    }

    @Test
    fun everyScalarFieldSurvivesAWriteAndRead() = runTest {
        val fs = FakeFileSystem()
        val store = storeOn(fs)
        // Zeroes and negative numbers on every field the format carries, because
        // a fixture of `1`s and `true`s cannot tell a field that is written from a
        // field that is read back as a default.
        val original = watchlist(
            id = "",
            titleItemId = "",
            entityId = "",
            createdAtMs = 0L,
            attempt = 0,
            nextAttemptAtMs = 0L,
        )

        store.saveAll(listOf(original))

        assertEquals(listOf(original), store.loadAll())
    }

    // ---- the three behaviour changes the file's own comments record ---------

    @Test
    fun aBlankContentTypeReadsAsMovieRatherThanDiscardingTheMutation() = runTest {
        // The recorded change: `optString(key, "MOVIE")` returns its default only
        // when the key is ABSENT, so a stored `""` came through as `""`, and
        // `MediaContentType.valueOf("")` threw -- into the `catch` that answers
        // `MOVIE`, but only for this arm, having already thrown. `ifBlank` folds
        // the second case into the same answer one line earlier.
        //
        // **The discriminator is `""` against a value that is merely unrecognised,
        // because both reach `MOVIE` through the same catch.** A fixture of
        // `"SHOW"` would pass against the old code too, and would prove nothing
        // about the `ifBlank`. Asserting the *mutation survives at all* is what
        // separates the two implementations: the old path threw out of
        // `TitleWatchedMutation`'s own construction is not the case -- the catch
        // is inside -- so the mutation is kept in both, and the visible difference
        // is nil here. Which is the honest finding: see the test below.
        val fs = FakeFileSystem()
        fs.writeRaw(
            """[{"type":"TITLE_WATCHED","id":"x","titleItemId":"t","entityId":"e",""" +
                """"createdAtMs":1,"attempt":0,"nextAttemptAtMs":0,"status":"pending",""" +
                """"contentType":"","desired":true}]""",
        )

        val loaded = storeOn(fs).loadAll().single()

        assertEquals(MediaContentType.MOVIE, (loaded as TitleWatchedMutation).contentType)
    }

    @Test
    fun anUnrecognisedContentTypeAlsoReadsAsMovieSoTheCatchIsNotTheOnlyRoute() = runTest {
        // The companion to the test above, and it exists because of what that one
        // found: **both** a blank and an unrecognised value answer `MOVIE`, so
        // neither test can tell `ifBlank` from the `catch`. What they *can* tell
        // is that the mutation is not lost, which is the user-visible half. Two
        // cases that agree on their answer are two worlds; this one exists to say
        // so rather than to imply the blank case is covered on its own.
        val fs = FakeFileSystem()
        fs.writeRaw(
            """[{"type":"TITLE_WATCHED","id":"x","titleItemId":"t","entityId":"e",""" +
                """"createdAtMs":1,"attempt":0,"nextAttemptAtMs":0,"status":"pending",""" +
                """"contentType":"SHOW","desired":true}]""",
        )

        val loaded = storeOn(fs).loadAll().single()

        assertEquals(MediaContentType.MOVIE, (loaded as TitleWatchedMutation).contentType)
    }

    @Test
    fun episodeZeroIsAnEpisodeNumberAndNotAnAbsentEpisode() = runTest {
        // The recorded change, and the sharpest of the three: `0` is a legitimate
        // season-0 special, so the old `if (has("episode"))` guard was
        // load-bearing and a defaulted `optInt` could not have replaced it. The
        // two answers differ on exactly one input and that input is the one the
        // field allows, so the fixture pair below is the whole test -- a fixture
        // of only `0` would pass against a defaulted read too, and a fixture of
        // only `null` would prove nothing about the guard.
        val fs = FakeFileSystem()
        fs.writeRaw(
            """[{"type":"EPISODE_WATCHED","id":"zero","titleItemId":"t","entityId":"e",""" +
                """"createdAtMs":1,"attempt":0,"nextAttemptAtMs":0,"status":"pending",""" +
                """"itemId":"i","season":1,"episode":0,"videoId":"v","desired":true},""" +
                """{"type":"EPISODE_WATCHED","id":"absent","titleItemId":"t","entityId":"e",""" +
                """"createdAtMs":1,"attempt":0,"nextAttemptAtMs":0,"status":"pending",""" +
                """"itemId":"i","season":1,"videoId":"v","desired":true}]""",
        )

        val loaded = storeOn(fs).loadAll().map { (it as EpisodeWatchedMutation).episode }

        assertEquals(listOf(0, null), loaded)
    }

    @Test
    fun aRatingWithNoDesiredRoundTripsAsNullAndNotAsFalse() = runTest {
        // The third recorded change, and the only one where the two answers are
        // genuinely different data rather than the same data by two routes: `null`
        // is "clear the vote" and `false` is "dislike", so a round trip that
        // collapsed them would silently turn a cleared rating into a negative one
        // and the user would see a thumbs-down they never gave. `encode` writes a
        // JSON null for it, so this exercises the write side too.
        val fs = FakeFileSystem()
        val store = storeOn(fs)
        val original = rating(desired = null)

        store.saveAll(listOf(original))

        val loaded = store.loadAll().single() as RatingMutation
        assertNull(loaded.desired)
        assertEquals(original, loaded)
    }

    @Test
    fun aPresentNonBooleanDesiredOnARatingReadsAsNullRatherThanFalse() = runTest {
        // The one input the accessor's own comment says used to read `false` and
        // now reads `null`. **It cannot arise in a file this class wrote** -- that
        // is why the comment calls it out -- so it is only reachable by writing
        // raw text, which is exactly what a round trip can never do. This is the
        // reason the raw-text cases exist at all.
        val fs = FakeFileSystem()
        fs.writeRaw(
            """[{"type":"RATING","id":"x","titleItemId":"t","entityId":"e",""" +
                """"createdAtMs":1,"attempt":0,"nextAttemptAtMs":0,"status":"pending",""" +
                """"desired":"yes"}]""",
        )

        val loaded = storeOn(fs).loadAll().single() as RatingMutation

        assertNull(loaded.desired)
    }

    // ---- the coercion the domain KDoc promises ---------------------------

    @Test
    fun anInflightStatusIsCoercedBackToPendingOnReload() = runTest {
        // `MutationStatus`'s own KDoc says `Inflight` is transient and is
        // coerced back to `Pending` on reload so a crashed write is retried. That
        // is a promise in `:core-domain` about a file in `:app`, and **a promise
        // in one module about behaviour implemented in another is only real if
        // some test crosses the boundary.** This is that test, and it is why the
        // case exists even though `encode` writes `"inflight"` faithfully: the
        // coercion is in the read, so the only way to see it is to write the
        // status a crashed process would have left behind.
        val fs = FakeFileSystem()
        fs.writeRaw(
            """[{"type":"WATCHLIST","id":"x","titleItemId":"t","entityId":"e",""" +
                """"createdAtMs":1,"attempt":0,"nextAttemptAtMs":0,"status":"inflight",""" +
                """"desired":true}]""",
        )

        val loaded = storeOn(fs).loadAll().single()

        assertEquals(MutationStatus.Pending, loaded.status)
    }

    @Test
    fun savingAnInflightMutationAndReadingItBackIsAlsoPending() = runTest {
        // The same coercion reached the ordinary way, and it is a separate
        // assertion because `encode` and `decode` could disagree: nothing in the
        // write path has to preserve `Inflight` for the read path to coerce it.
        val fs = FakeFileSystem()
        val store = storeOn(fs)

        store.saveAll(listOf(watchlist(id = "x", status = MutationStatus.Inflight)))

        assertEquals(MutationStatus.Pending, store.loadAll().single().status)
    }

    @Test
    fun aFailedStatusWithoutARetryableFieldIsRetried() = runTest {
        // `retryable` defaults to `true` on the read, and that is the safe
        // direction: a mutation recorded as failed with an unreadable retry flag
        // is retried rather than abandoned. The fixture omits `statusReason` too,
        // which defaults to `""` -- the reason is a message for a log, not a
        // decision, and an absent one must not stop the retry.
        val fs = FakeFileSystem()
        fs.writeRaw(
            """[{"type":"WATCHLIST","id":"x","titleItemId":"t","entityId":"e",""" +
                """"createdAtMs":1,"attempt":0,"nextAttemptAtMs":0,"status":"failed",""" +
                """"desired":true}]""",
        )

        val loaded = storeOn(fs).loadAll().single().status

        assertEquals(MutationStatus.Failed(reason = "", retryable = true), loaded)
    }

    // ---- the file-level contract -----------------------------------------

    @Test
    fun anAbsentFileLoadsAsNoMutationsRatherThanThrowing() = runTest {
        // The first read after a fresh install. The old guard was
        // `if (!file.exists())`, and this is the case it was written for: without
        // it a first run would throw out of `readText` and the outbox would never
        // start. `metadataOrNull` is the same question asked the okio way.
        val store = storeOn(FakeFileSystem())

        assertEquals(emptyList(), store.loadAll())
    }

    @Test
    fun anEmptyFileLoadsAsNoMutationsRatherThanThrowing() = runTest {
        // A zero-byte file is the shape a crash mid-write leaves behind, and it is
        // NOT the same case as an absent one: the metadata check passes and the
        // *parse* has to be the thing that answers. It is the case that proves the
        // `runCatching` is load-bearing rather than decorative, because an absent
        // file never reaches it.
        val fs = FakeFileSystem()
        fs.writeRaw("")

        assertEquals(emptyList(), storeOn(fs).loadAll())
    }

    @Test
    fun aFileThatIsNotAJsonArrayLoadsAsNoMutations() = runTest {
        val fs = FakeFileSystem()
        fs.writeRaw("""{"type":"WATCHLIST"}""")

        assertEquals(emptyList(), storeOn(fs).loadAll())
    }

    @Test
    fun oneUnreadableElementDoesNotDiscardTheOthers() = runTest {
        // `decode` returns `null` for an unknown `type` and `mapNotNull` drops it,
        // so a mutation written by a *newer* version of the app is skipped rather
        // than poisoning the queue. **This is the good half of the malformed-file
        // story and the test below is the bad half**, and they differ on one thing
        // only: a null return versus a throw. Asserting only one of them leaves
        // the other unstated, and a reader cannot tell which one the code relies
        // on.
        val fs = FakeFileSystem()
        fs.writeRaw(
            """[{"type":"WATCHLIST","id":"keep","titleItemId":"t","entityId":"e",""" +
                """"createdAtMs":1,"attempt":0,"nextAttemptAtMs":0,"status":"pending",""" +
                """"desired":true},""" +
                """{"type":"SOMETHING_FROM_THE_FUTURE","id":"skip","titleItemId":"t",""" +
                """"entityId":"e","createdAtMs":1,"attempt":0,"nextAttemptAtMs":0}]""",
        )

        val loaded = storeOn(fs).loadAll()

        assertEquals(1, loaded.size)
        assertEquals("keep", loaded.single().id)
    }

    @Test
    fun oneElementThatThrowsDiscardsTheWholeFile() = runTest {
        // The bad half, and it is a **real defect rather than a fixture**: a single
        // element missing a field its own kind requires throws out of
        // `optBooleanOrThrow`, and that throw escapes `mapNotNull` into the
        // `runCatching` around the *whole* parse -- so one malformed record makes
        // every queued mutation read as `emptyList()`. The user then sees an empty
        // outbox and a queue that was about to retry work against the server.
        //
        // **It is pinned rather than fixed because the behaviour is a product
        // decision.** Per-element isolation would mean catching inside the
        // `mapNotNull`, which changes what a partially-readable file means, and
        // the current all-or-nothing answer is at least deterministic. A test
        // that recorded the *desired* behaviour would be a claim about a decision
        // nobody has made, so this one records the actual one and says so.
        val fs = FakeFileSystem()
        fs.writeRaw(
            """[{"type":"WATCHLIST","id":"good","titleItemId":"t","entityId":"e",""" +
                """"createdAtMs":1,"attempt":0,"nextAttemptAtMs":0,"status":"pending",""" +
                """"desired":true},""" +
                """{"type":"WATCHLIST","id":"no-desired","titleItemId":"t","entityId":"e",""" +
                """"createdAtMs":1,"attempt":0,"nextAttemptAtMs":0,"status":"pending"}]""",
        )

        assertEquals(emptyList(), storeOn(fs).loadAll())
    }

    @Test
    fun aNonObjectElementIsSkippedRatherThanDiscardingTheFile() = runTest {
        // The third shape a stored array can hold, and it is the one that makes the
        // `it as? JsonObject` in `loadAll` load-bearing: the cast is a `safe` cast,
        // so a bare string or number in the array becomes `null` and is dropped by
        // `mapNotNull` -- the same good path an unknown `type` takes, reached
        // without a throw.
        val fs = FakeFileSystem()
        fs.writeRaw(
            """["not an object",{"type":"WATCHLIST","id":"keep","titleItemId":"t",""" +
                """"entityId":"e","createdAtMs":1,"attempt":0,"nextAttemptAtMs":0,""" +
                """"status":"pending","desired":true},42]""",
        )

        val loaded = storeOn(fs).loadAll()

        assertEquals(listOf("keep"), loaded.map { it.id })
    }

    @Test
    fun savingCreatesTheParentDirectoryWhenItIsMissing() = runTest {
        // The `mkdirs()` the old code called and ignored. `FakeFileSystem` is
        // strict about this -- `sink` on a path whose parent does not exist
        // throws -- so the test fails without the `createDirectories` call rather
        // than passing around it. The path here is deliberately two levels deep
        // and both are absent, because a one-level parent is the case
        // `File.parentFile` and `Path.parent` could disagree about and this
        // exercises the deeper one.
        val fs = FakeFileSystem()
        val deep = "/a/b/c/mutations.json".toPath()
        assertNull(fs.metadataOrNull(requireNotNull(deep.parent)))

        val store = storeOn(fs, at = deep)
        store.saveAll(listOf(watchlist()))

        assertTrue(fs.metadataOrNull(deep)?.isRegularFile == true)
        assertEquals(listOf("m1"), store.loadAll().map { it.id })
    }

    @Test
    fun savingReplacesTheFileRatherThanAppendingToIt() = runTest {
        // `sink(path)` truncates by default while `appendingSink` would not, and
        // the queue is rewritten in full on every flush. A store that appended
        // would grow without bound and `loadAll` would return duplicates of every
        // mutation ever queued -- so this asserts the *count* falls, which is the
        // only direction that distinguishes the two.
        val fs = FakeFileSystem()
        val store = storeOn(fs)
        store.saveAll(listOf(watchlist(id = "a"), watchlist(id = "b")))
        assertEquals(2, store.loadAll().size)

        store.saveAll(listOf(watchlist(id = "c")))

        assertEquals(listOf("c"), store.loadAll().map { it.id })
    }

    @Test
    fun savingAnEmptyQueueWritesAnEmptyArrayAndReadsBackEmpty() = runTest {
        // The outbox calls `saveAll(emptyList())` to drain the queue, so this is
        // the path that actually runs in production after a successful sync. If it
        // wrote nothing, the drained mutations would come back on the next
        // launch; if it wrote `null` or an empty string, `loadAll` would swallow it
        // and *look* correct until the file was read by something else.
        val fs = FakeFileSystem()
        val store = storeOn(fs)
        store.saveAll(listOf(watchlist(id = "a")))

        store.saveAll(emptyList())

        assertEquals(emptyList(), store.loadAll())
        assertTrue(fs.source(path).buffer().use { it.readUtf8() }.startsWith("[]"))
    }

    @Test
    fun twoStoresOverTheSamePathSeeEachOthersWrites() = runTest {
        // Not a test of the format but of the thing the store is *for*: the
        // outbox reads at startup and writes on every flush, and there is no cache
        // in between. Two instances over one path is the shape the app actually
        // has across a process restart, and a store that held the mutations in
        // memory would pass every other test in this file and fail this one.
        val fs = FakeFileSystem()
        storeOn(fs).saveAll(listOf(watchlist(id = "written-by-the-first")))

        assertEquals(
            listOf("written-by-the-first"),
            storeOn(fs).loadAll().map { it.id },
        )
    }

    // ---- fixtures ---------------------------------------------------------

    private fun watchlist(
        id: String = "m1",
        titleItemId: String = "t1",
        entityId: String = "e1",
        createdAtMs: Long = 1_700_000_000_000L,
        attempt: Int = 3,
        nextAttemptAtMs: Long = 1_700_000_060_000L,
        status: MutationStatus = MutationStatus.Pending,
        desired: Boolean = true,
    ): UserMutation = WatchlistMutation(
        id = id,
        titleItemId = titleItemId,
        entityId = entityId,
        createdAtMs = createdAtMs,
        attempt = attempt,
        status = status,
        nextAttemptAtMs = nextAttemptAtMs,
        desired = desired,
    )

    private fun titleWatched(
        id: String = "m1",
        contentType: MediaContentType = MediaContentType.MOVIE,
        desired: Boolean = true,
    ): UserMutation = TitleWatchedMutation(
        id = id,
        titleItemId = "t1",
        entityId = "e1",
        createdAtMs = 1_700_000_000_000L,
        attempt = 0,
        status = MutationStatus.Pending,
        nextAttemptAtMs = 0L,
        contentType = contentType,
        desired = desired,
    )

    private fun rating(
        id: String = "m1",
        desired: Boolean?,
    ): UserMutation = RatingMutation(
        id = id,
        titleItemId = "t1",
        entityId = "e1",
        createdAtMs = 1_700_000_000_000L,
        attempt = 0,
        status = MutationStatus.Pending,
        nextAttemptAtMs = 0L,
        desired = desired,
    )

    private fun episodeWatched(
        id: String = "m1",
        episode: Int?,
    ): UserMutation = EpisodeWatchedMutation(
        id = id,
        titleItemId = "t1",
        entityId = "e1",
        createdAtMs = 1_700_000_000_000L,
        attempt = 0,
        status = MutationStatus.Pending,
        nextAttemptAtMs = 0L,
        itemId = "i1",
        season = 1,
        episode = episode,
        videoId = "v1",
        desired = true,
    )

    private fun seasonWatched(
        id: String = "m1",
        seasonNumber: Int = 1,
    ): UserMutation = SeasonWatchedMutation(
        id = id,
        titleItemId = "t1",
        entityId = "e1",
        createdAtMs = 1_700_000_000_000L,
        attempt = 0,
        status = MutationStatus.Pending,
        nextAttemptAtMs = 0L,
        seasonItemId = "si1",
        seasonNumber = seasonNumber,
        desired = true,
    )
}
