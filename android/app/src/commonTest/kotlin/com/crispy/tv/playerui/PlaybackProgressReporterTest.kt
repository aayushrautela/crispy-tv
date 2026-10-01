package com.crispy.tv.playerui

import com.crispy.tv.home.HomeRefreshBus
import com.crispy.tv.home.HomeRefreshEvent
import com.crispy.tv.platform.MonotonicClock
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.player.PlaybackIdentity
import com.crispy.tv.testing.FakeWatchHistoryService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The reporting rules `PlaybackProgressReporter` owns, pinned in `commonTest`.
 *
 * Most of these are about **what is not reported**, which is the whole design: the engine
 * reports a transient position of 0 while it settles after a seek, and persisting that would
 * move the user back to the start of what they were watching. A reporter that is merely
 * eager looks identical to a correct one on the happy path, so most of the cases here are
 * the moments where reporting must *stop*.
 *
 * Three of them pin an ordering that reads like a style choice and is not:
 *
 * - [theIntervalIsMeasuredFromTheSecondClockReading] — the clock is read twice per poll, and
 *   a mutation that collapsed it into one reading would move the interval boundary by
 *   exactly the gap between the two readings.
 * - [aStopWithNoDurationConsumesItsOnlyChance] — the stop latch is set *before* the duration
 *   guard, so a stop arriving with no duration is silently dropped rather than retried.
 * - [aHungBackendDropsTheHomeRefreshToo] — the refresh emit sits *inside* the timeout, so a
 *   backend that never answers costs the home screen its refresh as well as its write.
 *
 * The clock is a manual fake rather than the test scheduler's virtual time, because these
 * cases are about the *relationship* between two readings and a threshold, and expressing
 * that through `advanceTimeBy` would hide which number moved.
 */
class PlaybackProgressReporterTest {


    @Test
    fun theFirstSettledPollReportsAStartAndNotAProgress() = runTest {
        val h = Harness(this)

        h.reporter.syncWatchHistory(positionMs = 5_000L, durationMs = DURATION, isPlaying = true)
        advanceUntilIdle()

        assertEquals(1, h.service.startedReports.size, "the start report should have been sent")
        assertTrue(
            h.service.progressReports.isEmpty(),
            "the start branch returns, so the same poll must not also send progress",
        )
    }

    @Test
    fun aFirstPollOnALongRunningSessionStillReportsOnlyAStart() = runTest {
        // The interval guard cannot catch the fall-through here, and that is the whole point of
        // the case. [theFirstSettledPollReportsAStartAndNotAProgress] polls at clock 0, where
        // the elapsed-since-last-sync is 0 and the *interval* guard returns -- so it never shows
        // whether the start branch's own `return` is doing anything. Here the clock starts past
        // [PROGRESS_SYNC_INTERVAL_MS] with no progress ever sent, so that delta is the whole
        // session length, the interval guard lets the poll through, and without the explicit
        // return one poll would leave with both a start and a progress. That is the state a user
        // is in after resuming a title part-way through.
        val h = Harness(this)
        h.clock.nowMs = PROGRESS_SYNC_INTERVAL_MS + 1L

        h.reporter.syncWatchHistory(positionMs = 5_000L, durationMs = DURATION, isPlaying = true)
        advanceUntilIdle()

        assertEquals(1, h.service.startedReports.size, "the start report should have been sent")
        assertTrue(
            h.service.progressReports.isEmpty(),
            "a session already past the persist interval must not turn one poll into two reports",
        )
    }

    @Test
    fun aPollBelowTheMinimumPositionReportsNothing() = runTest {
        val h = Harness(this)

        h.reporter.syncWatchHistory(positionMs = 999L, durationMs = DURATION, isPlaying = true)
        advanceUntilIdle()

        assertTrue(h.service.startedReports.isEmpty(), "999ms is one millisecond short of the minimum")
        assertTrue(h.service.progressReports.isEmpty())
    }

    @Test
    fun theMinimumPositionIsInclusive() = runTest {
        val h = Harness(this)

        h.reporter.syncWatchHistory(positionMs = 1_000L, durationMs = DURATION, isPlaying = true)
        advanceUntilIdle()

        assertEquals(
            1,
            h.service.startedReports.size,
            "the minimum is >=, so exactly the minimum qualifies",
        )
    }

    @Test
    fun aPollWithNoDurationReportsNothing() = runTest {
        val h = Harness(this)

        h.reporter.syncWatchHistory(positionMs = 5_000L, durationMs = 0L, isPlaying = true)
        advanceUntilIdle()

        assertTrue(h.service.startedReports.isEmpty(), "a zero duration means nothing to record progress against")
    }

    @Test
    fun aPollBeforeAnyTitleIsResolvedReportsNothing() = runTest {
        val h = Harness(this)
        h.identity = null

        h.reporter.syncWatchHistory(positionMs = 5_000L, durationMs = DURATION, isPlaying = true)
        advanceUntilIdle()

        assertTrue(h.service.startedReports.isEmpty(), "there is no title to attribute the report to")
    }

    @Test
    fun aPausedPollBeforeAnyStartReportsNothing() = runTest {
        val h = Harness(this)

        h.reporter.syncWatchHistory(positionMs = 5_000L, durationMs = DURATION, isPlaying = false)
        advanceUntilIdle()

        assertTrue(h.service.startedReports.isEmpty(), "a paused player is not a playback start")
        assertTrue(h.service.progressReports.isEmpty())
    }

    @Test
    fun aPollInsideTheSeekSettleWindowReportsNothing() = runTest {
        val h = Harness(this)
        h.reporter.scheduleProgressSyncAfterSeek()

        h.clock.nowMs = 699L
        h.reporter.syncWatchHistory(positionMs = 5_000L, durationMs = DURATION, isPlaying = true)
        advanceUntilIdle()

        assertTrue(
            h.service.startedReports.isEmpty(),
            "inside the window the engine's position is transient and must not be persisted",
        )
    }

    @Test
    fun aPollAfterTheSettleWindowReportsTheStart() = runTest {
        val h = Harness(this)
        h.reporter.scheduleProgressSyncAfterSeek()
        advanceUntilIdle()

        h.clock.nowMs = 800L
        h.reporter.syncWatchHistory(positionMs = 5_000L, durationMs = DURATION, isPlaying = true)
        advanceUntilIdle()

        assertEquals(1, h.service.startedReports.size, "once the window has passed, reporting resumes")
    }

    @Test
    fun aSecondPollBeforeThePersistIntervalReportsNothing() = runTest {
        val h = Harness(this)
        h.reporter.syncWatchHistory(positionMs = 5_000L, durationMs = DURATION, isPlaying = true)
        advanceUntilIdle()

        h.clock.nowMs = 59_000L
        h.reporter.syncWatchHistory(positionMs = 6_000L, durationMs = DURATION, isPlaying = true)
        advanceUntilIdle()

        assertTrue(
            h.service.progressReports.isEmpty(),
            "59s after the start is inside the 60s persist interval",
        )
    }

    @Test
    fun aPollAfterThePersistIntervalReportsProgress() = runTest {
        val h = Harness(this)
        h.reporter.syncWatchHistory(positionMs = 5_000L, durationMs = DURATION, isPlaying = true)
        advanceUntilIdle()

        h.clock.nowMs = 60_000L
        h.reporter.syncWatchHistory(positionMs = 6_000L, durationMs = DURATION, isPlaying = true)
        advanceUntilIdle()

        assertEquals(1, h.service.progressReports.size, "the interval is inclusive at exactly 60s")
        assertEquals(6_000L, h.service.progressReports.single().positionMs)
    }

    @Test
    fun theIntervalIsMeasuredFromTheSecondClockReading() = runTest {
        val h = Harness(this)
        // Every reading moves the clock on, so the guard and the interval see different
        // instants. Collapsing the two reads into one is what this case would catch.
        h.clock.stepMs = 9L

        h.reporter.syncWatchHistory(positionMs = 5_000L, durationMs = DURATION, isPlaying = true)
        advanceUntilIdle()
        assertEquals(1, h.service.startedReports.size)

        h.clock.nowMs = 60_000L
        h.clock.stepMs = 0L
        h.reporter.syncWatchHistory(positionMs = 6_000L, durationMs = DURATION, isPlaying = true)
        advanceUntilIdle()
        assertTrue(
            h.service.progressReports.isEmpty(),
            "the start was stamped at reading two, which is 9ms after the guard's reading",
        )

        h.clock.nowMs = 60_009L
        h.reporter.syncWatchHistory(positionMs = 7_000L, durationMs = DURATION, isPlaying = true)
        advanceUntilIdle()
        assertEquals(
            1,
            h.service.progressReports.size,
            "and nine milliseconds later it is genuinely due, so the interval is real",
        )
    }

    @Test
    fun aStopIsReportedOnceAndOnlyOnce() = runTest {
        val h = Harness(this)
        h.positionMs = 42_000L

        h.reporter.reportPlaybackStopped(SAMPLE)
        advanceUntilIdle()
        h.reporter.reportPlaybackStopped(SAMPLE)
        advanceUntilIdle()

        assertEquals(1, h.service.stoppedReports.size, "teardown and onCleared can both ask")
        assertEquals(42_000L, h.service.stoppedReports.single().positionMs)
    }

    @Test
    fun aStopWithNoDurationConsumesItsOnlyChance() = runTest {
        val h = Harness(this)
        h.durationMs = 0L

        h.reporter.reportPlaybackStopped(SAMPLE)
        advanceUntilIdle()
        assertTrue(h.service.stoppedReports.isEmpty(), "there is no duration to record")

        h.durationMs = DURATION
        h.reporter.reportPlaybackStopped(SAMPLE)
        advanceUntilIdle()
        assertTrue(
            h.service.stoppedReports.isEmpty(),
            "the latch is set before the duration guard, so the second stop is not retried",
        )
    }

    @Test
    fun aStopRefreshesTheHomeScreen() = runTest {
        val h = Harness(this)
        val events = mutableListOf<HomeRefreshEvent>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            HomeRefreshBus.events.collect { events += it }
        }

        h.reporter.reportPlaybackStopped(SAMPLE)
        advanceUntilIdle()
        collector.cancel()

        // The explicit type argument matters: `listOf(HomeRefreshEvent.PlaybackEnded)` infers
        // `List<PlaybackEnded>`, which does not unify with the `MutableList<HomeRefreshEvent>`
        // above it, and the failure reads as an inference error rather than a wrong answer.
        assertEquals(listOf<HomeRefreshEvent>(HomeRefreshEvent.PlaybackEnded), events)
    }

    @Test
    fun aHungBackendDropsTheHomeRefreshToo() = runTest {
        val h = Harness(this)
        val events = mutableListOf<HomeRefreshEvent>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            HomeRefreshBus.events.collect { events += it }
        }
        // Longer than the reporter's own 3s timeout, so the timeout wins.
        h.service.stopReportDelayMs = 10_000L

        h.reporter.reportPlaybackStopped(SAMPLE)
        advanceUntilIdle()
        collector.cancel()

        assertTrue(h.service.stoppedReports.isEmpty(), "the write itself is abandoned")
        assertTrue(
            events.isEmpty(),
            "the emit sits inside the timeout, so a hung backend costs the refresh too",
        )
    }

    @Test
    fun aResetReArmsTheStartReport() = runTest {
        val h = Harness(this)
        h.reporter.syncWatchHistory(positionMs = 5_000L, durationMs = DURATION, isPlaying = true)
        advanceUntilIdle()
        h.reporter.reportPlaybackStopped(SAMPLE)
        advanceUntilIdle()

        h.reporter.resetReportingState()
        h.clock.nowMs = 100_000L
        h.reporter.syncWatchHistory(positionMs = 6_000L, durationMs = DURATION, isPlaying = true)
        advanceUntilIdle()

        assertEquals(2, h.service.startedReports.size, "a fresh session starts from nothing again")
    }

    @Test
    fun aSecondSeekInheritsItsOwnDeadline() = runTest {
        val h = Harness(this)
        h.reporter.scheduleProgressSyncAfterSeek()

        h.clock.nowMs = 600L
        h.reporter.scheduleProgressSyncAfterSeek()

        h.clock.nowMs = 1_000L
        h.reporter.syncWatchHistory(positionMs = 5_000L, durationMs = DURATION, isPlaying = true)
        advanceUntilIdle()

        assertTrue(
            h.service.startedReports.isEmpty(),
            "the second seek restarts the wait from 600ms, so a poll at 1000ms is still inside it",
        )
    }

    @Test
    fun aSeekForcesAnImmediateProgressPush() = runTest {
        val h = Harness(this)
        h.reporter.syncWatchHistory(positionMs = 5_000L, durationMs = DURATION, isPlaying = true)
        advanceUntilIdle()
        h.clock.nowMs = 60_000L
        h.reporter.syncWatchHistory(positionMs = 6_000L, durationMs = DURATION, isPlaying = true)
        advanceUntilIdle()
        assertEquals(1, h.service.progressReports.size)

        h.clock.nowMs = 60_100L
        h.reporter.scheduleProgressSyncAfterSeek()
        advanceUntilIdle()

        h.reporter.syncWatchHistory(positionMs = 7_000L, durationMs = DURATION, isPlaying = true)
        advanceUntilIdle()

        assertEquals(
            2,
            h.service.progressReports.size,
            "the window clearing also zeroes the last-sync stamp, so the post-seek push is immediate",
        )
    }

    /**
     * A clock the test drives by hand, and optionally advances on every read.
     *
     * [stepMs] exists for [theIntervalIsMeasuredFromTheSecondClockReading]: it is the only
     * way to make the reporter's two readings of the same poll disagree, which is the
     * difference that case is about.
     */
    private class TestClock(var nowMs: Long = 0L, var stepMs: Long = 0L) : MonotonicClock {
        var reads: Int = 0
            private set

        override fun elapsedMs(): Long {
            reads++
            val value = nowMs
            nowMs += stepMs
            return value
        }
    }

    private class Harness(scope: CoroutineScope) {
        val clock = TestClock()
        val service = FakeWatchHistoryService()
        var identity: PlaybackIdentity? = SAMPLE
        var positionMs: Long = 0L
        // A real duration, not zero: the stop path gates on it, and a zero default would
        // make every stop case silently exercise the guard instead of the report.
        var durationMs: Long = DURATION

        val reporter = PlaybackProgressReporter(
            watchHistoryService = service,
            clock = clock,
            scope = scope,
            identity = { identity },
            currentPositionMs = { positionMs },
            currentDurationMs = { durationMs },
        )
    }

    private companion object {
        const val DURATION = 2_400_000L

        val SAMPLE = PlaybackIdentity(
            itemId = "tt0903747",
            contentType = MetadataLabMediaType.MOVIE,
            title = "Sample title",
        )
    }
}