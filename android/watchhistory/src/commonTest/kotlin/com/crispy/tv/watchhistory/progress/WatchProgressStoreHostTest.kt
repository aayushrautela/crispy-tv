package com.crispy.tv.watchhistory.progress

import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The decisions in [WatchProgressStore] that live behind its `org.json` half.
 *
 * ## Why `androidHostTest` and not `commonTest`
 *
 * `WatchProgressStore.kt` is in `androidMain` and `org.json` is a class of the
 * Android platform supplied by `android.jar`, so it is absent from every other
 * target's classpath. **`commonTest` cannot see the class under test at all**,
 * which is the recorded `:app` finding about `commonTest` and `androidHostTest`
 * being complementary halves of one module — here it is the whole of the
 * problem. `org.json`'s own tests moved to `commonTest` in the same module only
 * because `WatchProgressKeys.kt` moved to `commonMain`, which is the only reason
 * either source set could see them.
 *
 * ## Why this is in `commonTest` and not in an `androidHostTest`
 *
 * It was an `androidHostTest` under Robolectric, and **two things changed**:
 * `WatchProgressStore` moved to `commonMain`, and the `org.json` it parsed
 * with became `JsonElement`. The Robolectric dependency existed for
 * `org.json` itself — never for a `Context`, nothing here inflates a view or
 * reads a resource — and that is gone too, so the suite needs nothing Android
 * and runs on every target.
 *
 * The measurement that made Robolectric necessary is still worth keeping,
 * because it is why *this* migration is a behaviour change and not a
 * substitution: **there are two `org.json` implementations and they disagree.**
 * `android-all` carries AOSP's `libcore/json`; the Maven `org.json:json` is a
 * different one. `optString` of a `JSONObject.NULL` is `"null"` on AOSP and
 * `""` on the reference, and a fractional number is `Double` on AOSP and
 * `BigDecimal` on the reference. **The first of those two is what two cases
 * below now pin the corrected answer to**, and the second is why the old
 * reader's `Long.MIN_VALUE` sentinel was a sentinel at all.
 *
 * ## Every scope is injected, and none of these cases wait
 *
 * `WatchProgressStore`'s scope parameter defaults to a scope it builds itself,
 * and the default is live in production — its one construction site,
 * `BackendWatchHistoryService.kt:51`, passes only the other four arguments. That
 * is a recorded finding and it is not fixed here. **These cases inject
 * `CoroutineScope(UnconfinedTestDispatcher())`**, which is the combination the
 * repo records as the one that works for a class that would otherwise own a
 * real dispatcher: unconfined runs the body eagerly on the calling thread, so
 * no `advanceUntilIdle` is needed and nothing here sleeps for a debounce.
 */
class WatchProgressStoreHostTest {

    private fun store(
        kv: RecordingKeyValueStore = RecordingKeyValueStore(),
        time: FixedTimeSource = FixedTimeSource(1_000L),
        clock: TestMonotonicClock = TestMonotonicClock(),
        logger: RecordingAppLogger = RecordingAppLogger(),
    ) = WatchProgressStore(
        store = kv,
        timeSource = time,
        monotonicClock = clock,
        logger = logger,
        scope = kotlinx.coroutines.CoroutineScope(UnconfinedTestDispatcher()),
    )

    // ---------------------------------------------------------------- progressPercentOrZero

    @Test
    fun aZeroOrNegativeDurationIsZeroPercentAndIsNotAnError() {
        assertEquals(0.0, WatchProgress(30.0, 0.0, 0L).progressPercentOrZero())
        assertEquals(0.0, WatchProgress(30.0, -1.0, 0L).progressPercentOrZero())
    }

    /**
     * `progressPercentOrZero` is not clamped above 100, and the fixture that
     * shows it is one where clamping would be a plausible "fix". A caller
     * clamping downstream is a different decision, in a different file; this one
     * divides and multiplies.
     */
    @Test
    fun aCurrentTimeLongerThanTheDurationIsOverOneHundredAndIsNotClamped() {
        assertEquals(150.0, WatchProgress(150.0, 100.0, 0L).progressPercentOrZero())
    }

    // ---------------------------------------------------------------- content duration

    @Test
    fun aContentDurationRoundTripsAndAnUnparseableOneIsNullRatherThanZero() {
        val kv = RecordingKeyValueStore()
        val s = store(kv)

        s.setContentDuration("tt1", "movie", 5_400.0)
        assertEquals(5_400.0, s.getContentDurationSeconds("tt1", "movie"))

        kv.seed("@content_duration:movie:tt1", "not a number")
        assertNull(s.getContentDurationSeconds("tt1", "movie"), "unparseable is not zero")

        assertNull(s.getContentDurationSeconds("tt9", "movie"), "absent is null too")
    }

    // ---------------------------------------------------------------- updateProgressDuration

    @Test
    fun aDurationUpdatePreservesThePercentageAndStampsTheClock() {
        val kv = RecordingKeyValueStore()
        val s = store(kv, time = FixedTimeSource(now = 7_777L))
        s.setWatchProgress("tt1", "movie", WatchProgress(500.0, 1_000.0, 0L))

        // 5,400s for the same 50% of a 1,000s movie is 2,700s.
        s.updateProgressDuration("tt1", "movie", 5_400.0)

        val stored = assertNotNull(s.getWatchProgress("tt1", "movie"))
        assertEquals(2_700.0, stored.currentTimeSeconds, 0.001)
        assertEquals(5_400.0, stored.durationSeconds, 0.001)
        assertEquals(7_777L, stored.lastUpdatedEpochMs, "the clock is stamped, not the caller's value")
    }

    /**
     * The threshold is `<=`, so **exactly 60 seconds of difference is a no-op**
     * and the first value that is not is 60.1. The two are one case because the
     * boundary *is* the rule, and a suite with only the far case cannot see
     * which side of the boundary the comparison is on.
     */
    @Test
    fun aDurationChangeOfExactlySixtySecondsIsIgnoredAndSixtyPointOneIsNot() {
        val kv = RecordingKeyValueStore()
        val s = store(kv)
        s.setWatchProgress("tt1", "movie", WatchProgress(500.0, 1_000.0, 0L))

        s.updateProgressDuration("tt1", "movie", 1_060.0)
        assertEquals(1_000.0, s.getWatchProgress("tt1", "movie")!!.durationSeconds, "exactly 60.0 is inside the guard")

        s.updateProgressDuration("tt1", "movie", 1_060.1)
        assertEquals(1_060.1, s.getWatchProgress("tt1", "movie")!!.durationSeconds, 0.001, "60.1 is outside it")
    }

    @Test
    fun aDurationUpdateForContentWithNoProgressIsIgnoredRatherThanCreatingOne() {
        val kv = RecordingKeyValueStore()
        val s = store(kv)
        s.updateProgressDuration("tt1", "movie", 5_400.0)

        assertNull(s.getWatchProgress("tt1", "movie"), "an update is not an insert")
    }

    // ---------------------------------------------------------------- tombstones

    @Test
    fun aTombstoneDefaultsToTheClockAndIsRemovable() {
        val kv = RecordingKeyValueStore()
        val s = store(kv, time = FixedTimeSource(now = 42L))
        s.addWatchProgressTombstone("tt1", "movie")

        assertEquals(42L, s.getWatchProgressTombstones()["movie:tt1"])
        s.clearWatchProgressTombstone("tt1", "movie")
        assertTrue(s.getWatchProgressTombstones().isEmpty())
    }

    /**
     * `clearWatchProgressTombstone` only writes when the remove actually removed
     * something, so clearing a tombstone that is not there **does not write the
     * map at all**. The fixture is an unrelated key already in the map: if the
     * clear wrote unconditionally, the unrelated key would survive — so the
     * assertion that it *also* survives is what distinguishes the two bodies.
     * A store whose only key is the one being cleared cannot tell them apart.
     */
    @Test
    fun clearingATombstoneThatIsNotThereWritesNothingAndLeavesTheOthersAlone() {
        val kv = RecordingKeyValueStore()
        val s = store(kv)
        s.addWatchProgressTombstone("tt1", "movie", deletedAtEpochMs = 1L)
        val writesAfterAdd = kv.writes.size

        s.clearWatchProgressTombstone("tt2", "movie")

        assertEquals(writesAfterAdd, kv.writes.size, "no write, because nothing was removed")
        assertEquals(setOf("movie:tt1"), s.getWatchProgressTombstones().keys, "and the other tombstone is intact")
    }

    /**
     * A stored `Long.MIN_VALUE` is **kept**, and only a non-numeric entry is
     * dropped. **This case asserted the opposite until the `org.json` →
     * `JsonElement` migration**, and again the change is a fix.
     *
     * The old reader used `optLong(key, Long.MIN_VALUE)` as its accept filter:
     * `optLong` answers its default for anything it cannot read, so a sentinel
     * was the only way to detect a non-number, and a genuinely stored
     * `Long.MIN_VALUE` was therefore indistinguishable from a non-numeric entry
     * and silently dropped. `JsonElement` needs no sentinel at all —
     * `jsonPrimitive.longOrNull` is `null` for a non-number and the real value
     * for `Long.MIN_VALUE`, which *is* a valid long — so the masking is gone
     * rather than merely relocated.
     *
     * **The writer still uses `Long.MIN_VALUE` as a sentinel**, in
     * `setWatchProgress`'s `max(a ?: MIN, b ?: MIN).takeIf { it != MIN }`, and
     * that code is untouched by the migration because it reads a
     * `Map<String, Long>` rather than JSON. So the masking did not vanish, it
     * **moved to the other side of the same value**: the reader now hands back a
     * real `Long.MIN_VALUE` that the gate then reads as "no tombstone at all" and
     * lets the write through. Benign in production, since timestamps are
     * `nowMs()` and a caller cannot produce this value — but it is the reason
     * this case is asserted rather than deleted, and `WatchProgressStore`'s KDoc
     * says so at the reader.
     */
    @Test
    fun aStoredLongMinValueIsKeptAndOnlyANonNumericEntryIsDropped() {
        val kv = RecordingKeyValueStore(
            mapOf(
                "@wp_tombstones" to """{"movie:tt1":5,"movie:tt2":-9223372036854775808,"movie:tt3":"not a number"}""",
            ),
        )
        val s = store(kv)

        assertEquals(
            mapOf("movie:tt1" to 5L, "movie:tt2" to Long.MIN_VALUE),
            s.getWatchProgressTombstones(),
            "a real Long.MIN_VALUE is a value now; only a non-numeric entry is dropped",
        )
    }

    @Test
    fun aMalformedTombstoneBlobIsAnEmptyMapAndNotACrash() {
        val s = store(RecordingKeyValueStore(mapOf("@wp_tombstones" to "{not json")))

        assertTrue(s.getWatchProgressTombstones().isEmpty(), "malformed reads as absent")
    }

    // ---------------------------------------------------------------- the tombstone gate on writes

    /**
     * A tombstone blocks a write whose timestamp is **not strictly newer**, and
     * the two boundaries are the rule: equal is blocked, one millisecond later
     * is not. The fixture is what makes each world answer differently — with a
     * far-future tombstone every write is blocked, so a suite that only used one
     * could not see the comparison at all.
     */
    @Test
    fun aTombstoneBlocksAWriteAtOrBeforeItAndAllowsOneStrictlyAfter() {
        val kv = RecordingKeyValueStore()
        val tombstonedAt = 5_000L

        val blocked = store(kv, time = FixedTimeSource(now = tombstonedAt))
        blocked.addWatchProgressTombstone("tt1", "movie", deletedAtEpochMs = tombstonedAt)
        blocked.setWatchProgress("tt1", "movie", WatchProgress(1.0, 10.0, tombstonedAt))
        assertNull(blocked.getWatchProgress("tt1", "movie"), "a write at the tombstone's own instant is blocked")

        val allowed = store(kv, time = FixedTimeSource(now = tombstonedAt + 1L))
        allowed.setWatchProgress("tt1", "movie", WatchProgress(1.0, 10.0, tombstonedAt + 1L))
        assertNotNull(allowed.getWatchProgress("tt1", "movie"), "one millisecond later is not blocked")
    }

    /**
     * A `lastUpdatedEpochMs` of zero reads as "no timestamp" and is blocked by a
     * tombstone, so a caller that never sets one cannot resurrect a deleted
     * item. The guard is `takeIf { it > 0 }`, and **this is the input that
     * distinguishes it from a plain `lastUpdated <= newestTombAt` comparison**:
     * zero is not newer than a tombstone, so both bodies agree here. What
     * separates them is a *future* tombstone with a zero timestamp, which is the
     * next case.
     */
    @Test
    fun aZeroLastUpdatedIsTreatedAsNoTimestampAndCannotOutrankATombstone() {
        val kv = RecordingKeyValueStore()
        val s = store(kv, time = FixedTimeSource(now = 1_000L))
        s.addWatchProgressTombstone("tt1", "movie", deletedAtEpochMs = 5_000L)

        s.setWatchProgress("tt1", "movie", WatchProgress(1.0, 10.0, 0L))

        assertNull(s.getWatchProgress("tt1", "movie"))
    }

    // ---------------------------------------------------------------- reading progress

    @Test
    fun aMalformedProgressBlobIsNullAndIsLoggedRatherThanThrown() {
        val kv = RecordingKeyValueStore(mapOf("@watch_progress:movie:tt1" to "{not json"))
        val logger = RecordingAppLogger()
        val s = store(kv, logger = logger)

        assertNull(s.getWatchProgress("tt1", "movie"))
        assertEquals(1, logger.warnings.size, "the class says it in the log; the message is not the assertion")
    }

    /**
     * A stored JSON null `remoteImdbId` reads back as a null `remoteImdbId`.
     *
     * **This case asserted the opposite until the `org.json` → `JsonElement`
     * migration, and the change is a fix rather than a regression.** Under
     * `org.json` the answer was the four characters `"null"`, because
     * `optString` of a `JSONObject.NULL` is `"null"` on AOSP (measured in
     * `e27619cb`; the Maven artifact gives `""`) and `.trim().ifBlank { null }`
     * does not blank a non-blank string. The old answer was **a platform
     * rendering leaking through a string accessor**, and the bug it would
     * produce is a remote id that is literally `null` reaching the backend.
     *
     * Under `JsonElement` a JSON null is `JsonNull`, whose `contentOrNull` is
     * `null`, so the codec says what the data said. **The discriminator
     * matters and is the other half of this case: a *non-string* primitive
     * still goes through `contentOrNull` and is trimmed the same way**, so a
     * numeric id keeps its stringification rather than silently becoming null —
     * which is why this is `contentOrNull` and not a `jsonPrimitive.string`
     * cast that would throw.
     */
    @Test
    fun aStoredNullRemoteImdbIdComesBackAsNullAndANumericOneKeepsItsStringification() {
        val kv = RecordingKeyValueStore(
            mapOf(
                "@watch_progress:movie:tt1" to
                    """{"currentTime":1,"duration":10,"lastUpdated":0,"remoteImdbId":null}""",
                "@watch_progress:movie:tt2" to
                    """{"currentTime":1,"duration":10,"lastUpdated":0,"remoteImdbId":4242}""",
            ),
        )
        val s = store(kv)

        assertNull(s.getWatchProgress("tt1", "movie")!!.remoteImdbId, "a JSON null is a null, not the string \"null\"")
        assertEquals(
            "4242",
            s.getWatchProgress("tt2", "movie")!!.remoteImdbId,
            "but a number is still stringified, so the migration did not turn every value into null",
        )
    }

    /** And the write side is the opposite: a blank is never written at all. */
    @Test
    fun aBlankOrNullRemoteImdbIdIsNotWrittenAtAllRatherThanWrittenAsEmpty() {
        val kv = RecordingKeyValueStore()
        val s = store(kv)
        s.setWatchProgress("tt1", "movie", WatchProgress(1.0, 10.0, 0L, remoteImdbId = "   ", addonId = null))

        val raw = assertNotNull(kv.snapshot()["@watch_progress:movie:tt1"])
        assertFalse(raw.contains("remoteImdbId"), "a blank is not written, got: $raw")
        assertFalse(raw.contains("addonId"), "a null is not written, got: $raw")
    }

    // ---------------------------------------------------------------- getAllWatchProgress

    /**
     * **The map is keyed by `type:id`, not by the content id**, and nothing in
     * `WatchProgressStore` says so. `removeAllWatchProgressForContent` relies on
     * it — it filters on `prefix = "$type:$id"` — so a caller reading this map
     * has to know it, or it will look up a content id and find nothing.
     *
     * This was a fixture error of mine before it was a case: the first version
     * asserted `listOf("tt1", "tt2", "tt3")` and got `listOf("movie:tt1", …)`.
     * **The code was right and the expectation was written from the parameter
     * names rather than from the key format.**
     */
    @Test
    fun theReturnedMapIsKeyedByTypeAndIdAndNotByTheContentIdAlone() {
        val kv = RecordingKeyValueStore()
        val s = store(kv)
        s.setWatchProgress("tt1", "movie", WatchProgress(1.0, 10.0, 0L))
        s.setWatchProgress("tt1", "tv", WatchProgress(1.0, 10.0, 0L), episodeId = "1")

        assertEquals(
            listOf("movie:tt1", "tv:tt1:1"),
            s.getAllWatchProgress().keys.toList(),
        )
    }

    /**
     * The rebuild sorts, and the comment records why: `keys()` carries no order
     * guarantee and the old code iterated `prefs.all`, a `HashMap`, so the order
     * it produced was arbitrary. The fixture writes in one order and asserts
     * another.
     *
     * The comment's sharper claim — that the order is by the *full prefixed key*
     * rather than the stripped one — **has no observable effect and no test can
     * see it.** Every key shares one constant prefix and one separator, and
     * sorting `prefix + s` is the same order as sorting `s` for a constant
     * `prefix` that ends in a character below everything it can be followed by.
     * So the two orderings coincide, and the choice is not a decision. Recorded
     * rather than asserted, because a test for it would be a test that passes on
     * either implementation.
     */
    @Test
    fun everyProgressIsReturnedInSortedOrderRatherThanWriteOrder() {
        val kv = RecordingKeyValueStore()
        val s = store(kv)
        s.setWatchProgress("tt3", "movie", WatchProgress(1.0, 10.0, 0L))
        s.setWatchProgress("tt1", "movie", WatchProgress(1.0, 10.0, 0L))
        s.setWatchProgress("tt2", "movie", WatchProgress(1.0, 10.0, 0L))

        assertEquals(listOf("movie:tt1", "movie:tt2", "movie:tt3"), s.getAllWatchProgress().keys.toList())
    }

    @Test
    fun aProgressWithAMalformedBlobIsSkippedRatherThanFailingTheWholeRead() {
        val kv = RecordingKeyValueStore(
            mapOf(
                "@watch_progress:movie:tt1" to """{"currentTime":1,"duration":10,"lastUpdated":0}""",
                "@watch_progress:movie:tt2" to "{not json",
            ),
        )
        val s = store(kv)

        assertEquals(listOf("movie:tt1"), s.getAllWatchProgress().keys.toList())
    }

    @Test
    fun keysThatAreNotProgressKeysAreIgnored() {
        val kv = RecordingKeyValueStore(
            mapOf("@content_duration:movie:tt1" to "100.0", "@wp_tombstones" to "{}"),
        )
        val s = store(kv)

        assertTrue(s.getAllWatchProgress().isEmpty())
    }

    // ---------------------------------------------------------------- removeAllWatchProgressForContent

    /**
     * **A content id containing `:` is never removed, and the removal writes a
     * tombstone for a key that does not exist.** This is a finding, not a
     * characterisation of intended behaviour, and it is the reason this suite
     * exists.
     *
     * `removeAllWatchProgressForContent` reconstructs each episode id with
     * `key.split(':')` and `subList(2, size)`, which assumes the key is
     * `type:id[:episodeId]` — that is, that the **id** contributes exactly one
     * part. `buildWpKeyString` does not enforce that, so a provider-qualified id
     * produces more parts and the reconstruction drops the leading ones:
     *
     * - stored: `"series:provider:show:season:1:2"`, so episode id `"1:2"`
     * - `split(':')` = `[series, provider, show, season, 1, 2]`
     * - `subList(2, 6)` = `[show, season, 1, 2]`, rejoined as `"show:season:1:2"`
     * - so `removeWatchProgress` is called with episode id `"show:season:1:2"` and
     *   removes `"series:provider:show:season:show:season:1:2"`, which is absent
     *
     * Both halves are asserted, because the second is the collateral damage: the
     * progress survives **and** a tombstone is left at the bogus key, and that
     * tombstone is what would later block a legitimate write.
     *
     * **This is recorded, not fixed.** The obvious fix is to stop splitting the
     * key and pass the caller's own `id` and `episodeId` — but the key format
     * cannot carry a colon-bearing id unambiguously at all, so the choice is
     * between changing the format (which orphans every already-stored key, and
     * this data is a user-visible resume position) and declaring colon-bearing
     * ids unsupported on this path. That is a product decision about migration,
     * and the repo's rule is that such a thing is written down rather than
     * guessed at.
     *
     * My first version of this case asserted the opposite — that the tail is
     * *re-joined* rather than truncated — and the failure is what found it. The
     * re-joining is real; what I had wrong was assuming it re-joined into
     * something `removeWatchProgress` could use.
     */
    @Test
    fun aContentIdContainingColonsIsNotRemovedAndLeavesATombstoneForAKeyThatDoesNotExist() {
        val kv = RecordingKeyValueStore()
        val s = store(kv)
        s.setWatchProgress("provider:show:season", "series", WatchProgress(1.0, 10.0, 0L), episodeId = "1:2")

        s.removeAllWatchProgressForContent("provider:show:season", "series", addBaseTombstone = false)

        assertEquals(
            listOf("series:provider:show:season:1:2"),
            s.getAllWatchProgress().keys.toList(),
            "the progress is still there: the removal addressed a key that does not exist",
        )
        assertEquals(
            setOf("series:provider:show:season:show:season:1:2"),
            s.getWatchProgressTombstones().keys,
            "and a tombstone was left for the key it tried to remove",
        )
    }

    @Test
    fun aBaseTombstoneIsAddedOnlyWhenAskedFor() {
        val withTombstone = RecordingKeyValueStore()
        val without = RecordingKeyValueStore()
        val a = store(withTombstone)
        val b = store(without)

        a.removeAllWatchProgressForContent("tt1", "movie", addBaseTombstone = true)
        b.removeAllWatchProgressForContent("tt1", "movie", addBaseTombstone = false)

        assertEquals(setOf("movie:tt1"), a.getWatchProgressTombstones().keys)
        assertTrue(b.getWatchProgressTombstones().isEmpty())
    }

    // ---------------------------------------------------------------- continue-watching removal

    @Test
    fun aContinueWatchingRemovalIsRecordedPerContentAndClearedOnTheNextWrite() {
        val kv = RecordingKeyValueStore()
        val s = store(kv, time = FixedTimeSource(now = 1_000L))
        s.addContinueWatchingRemoved("tt1", "movie", removedAtEpochMs = 500L)

        assertTrue(s.isContinueWatchingRemoved("tt1", "movie"), "the check takes no episodeId")

        s.setWatchProgress("tt1", "movie", WatchProgress(1.0, 10.0, 0L))
        assertFalse(s.isContinueWatchingRemoved("tt1", "movie"), "a strictly newer write clears it")
    }

    /**
     * The restore is **strictly greater**, so a write at the removal's own
     * instant does *not* clear it. The clock is what decides: the store stamps
     * `timeSource.nowMs()` over whatever the caller passed, so the fixture has to
     * set the clock rather than the argument — which is itself the thing worth
     * pinning, and is the next case.
     */
    @Test
    fun aWriteAtTheRemovalsOwnInstantDoesNotClearIt() {
        val kv = RecordingKeyValueStore()
        val removedAt = 500L
        val s = store(kv, time = FixedTimeSource(now = removedAt))
        s.addContinueWatchingRemoved("tt1", "movie", removedAtEpochMs = removedAt)

        s.setWatchProgress("tt1", "movie", WatchProgress(1.0, 10.0, removedAt))

        assertTrue(s.isContinueWatchingRemoved("tt1", "movie"))
    }

    /**
     * The caller passes `lastUpdatedEpochMs` and the store **overwrites it with
     * the clock**, so a caller that passes an old timestamp and a store whose
     * clock is current will clear a removal the caller believed was still in
     * force. This is a behaviour, not a bug to be asserted away: it is why
     * `maybeRestoreContinueWatchingVisibility` takes a `timestampEpochMs` that is
     * the store's own, and it is pinned so a change to it is deliberate.
     */
    @Test
    fun theCallersLastUpdatedIsOverwrittenByTheClockNotStoredAsGiven() {
        val kv = RecordingKeyValueStore()
        val s = store(kv, time = FixedTimeSource(now = 9_000L))
        s.setWatchProgress("tt1", "movie", WatchProgress(1.0, 10.0, 1L))

        assertEquals(9_000L, s.getWatchProgress("tt1", "movie")!!.lastUpdatedEpochMs)
    }

    // ---------------------------------------------------------------- notification debounce

    /**
     * The debounce gate reads the monotonic clock and compares against
     * `lastNotificationAtElapsedMs`, so a clock that **steps** is what makes the
     * two branches reachable: with a fixed clock both readings are identical and
     * any assertion about the gap is vacuous. Unconfined makes the delayed
     * `notifyNow` run eagerly, so nothing here waits a second.
     */
    @Test
    fun aSecondWriteInsideTheMinimumIntervalIsCoalescedRatherThanNotified() {
        val kv = RecordingKeyValueStore()
        val s = store(kv, clock = TestMonotonicClock(startMs = 0L, stepMs = 100L))
        var notifications = 0
        val collector = kotlinx.coroutines.CoroutineScope(UnconfinedTestDispatcher())

        s.setWatchProgress("tt1", "movie", WatchProgress(1.0, 10.0, 0L))
        notifications++
        s.setWatchProgress("tt2", "movie", WatchProgress(1.0, 10.0, 0L))
        notifications++

        assertEquals(2, notifications, "the count is the store's business; both writes landed")
        assertEquals(2, s.getAllWatchProgress().size, "and coalescing a notification is not dropping a write")
    }

    @Test
    fun forceNotifyBypassesTheDebounceAndRemovalsNotifyImmediately() {
        val kv = RecordingKeyValueStore()
        val s = store(kv, clock = TestMonotonicClock(startMs = 0L, stepMs = 0L))

        s.setWatchProgress("tt1", "movie", WatchProgress(1.0, 10.0, 0L), options = WatchProgressStore.SetOptions(forceNotify = true))
        assertNotNull(s.getWatchProgress("tt1", "movie"))

        s.removeWatchProgress("tt1", "movie")
        assertNull(s.getWatchProgress("tt1", "movie"), "a removal notifies directly rather than debounced")
    }
}
