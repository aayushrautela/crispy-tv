package com.crispy.tv.testing

import com.crispy.tv.player.CanonicalContinueWatchingResult
import com.crispy.tv.player.PlaybackIdentity
import com.crispy.tv.player.WatchHistoryRequest
import com.crispy.tv.player.WatchHistoryResult
import com.crispy.tv.player.WatchHistoryService
import kotlinx.coroutines.delay

/**
 * `:app`'s single double for [WatchHistoryService].
 *
 * ## Why it was promoted out of `HomeViewModelTest`
 *
 * It used to be a `private class` inside that test, answering only the four members the home
 * screen cares about. `PlaybackProgressReporter` needed the three `onPlayback*` members
 * answered as well, and the repo's rule is that `:app` has **one** double per port — exactly
 * as it has one `RecordingBackendApi` for `BackendApi`. Writing a second
 * `WatchHistoryService` double beside the first would have been a second copy to keep in
 * step, and the cost of *widening* this file is exactly the cost the rule accepts: a new
 * member someone needs answered is an override here, and a member nobody needs keeps the
 * interface's own default.
 *
 * It lives in `com.crispy.tv.testing` beside `FakeKeyValueStore` and `FakeStreamResolver`,
 * which is where `:app` puts a double more than one test file needs.
 *
 * Unlike `RecordingBackendApi` this one is *not* exhaustive, and the difference is the
 * interface's own doing: `WatchHistoryService` gives a default empty body to the reporting
 * methods, so a double that overrides nothing is already a valid implementation. That is why
 * the four overrides that were always here compile at all, and it is why the `throw
 * AssertionError` bodies below are for the two write members the home screen has no business
 * calling — they are assertions that the *test* did not ask for something, not stubs.
 */
internal class FakeWatchHistoryService : WatchHistoryService {

    var continueWatchingAnswer: CanonicalContinueWatchingResult =
        CanonicalContinueWatchingResult(statusMessage = "")
    var continueWatchingFailures: Int = 0
    val continueWatchingCalls = mutableListOf<Long>()
    var removeAnswer: WatchHistoryResult = WatchHistoryResult(statusMessage = "", accepted = true)
    val removedIds = mutableListOf<String>()

    /** One entry per `playback_started`, in the order the reporter sent them. */
    val startedReports = mutableListOf<PlaybackReport>()

    /** One entry per `playback_progress`, including its `isPlaying` flag. */
    val progressReports = mutableListOf<PlaybackReport>()

    /** One entry per `playback_stopped`. */
    val stoppedReports = mutableListOf<PlaybackReport>()

    /**
     * When greater than zero, `onPlaybackStopped` waits this long before recording.
     *
     * Its only purpose is to let a caller's own timeout fire, so the "a backend that never
     * answers" path can be reached under virtual time instead of by really waiting three
     * seconds in a test.
     */
    var stopReportDelayMs: Long = 0L

    data class PlaybackReport(
        val identity: PlaybackIdentity,
        val positionMs: Long,
        val durationMs: Long,
        val isPlaying: Boolean = true,
    )

    override suspend fun onPlaybackStarted(
        identity: PlaybackIdentity,
        positionMs: Long,
        durationMs: Long,
    ) {
        startedReports += PlaybackReport(identity, positionMs, durationMs)
    }

    override suspend fun onPlaybackProgress(
        identity: PlaybackIdentity,
        positionMs: Long,
        durationMs: Long,
        isPlaying: Boolean,
    ) {
        progressReports += PlaybackReport(identity, positionMs, durationMs, isPlaying)
    }

    override suspend fun onPlaybackStopped(
        identity: PlaybackIdentity,
        positionMs: Long,
        durationMs: Long,
    ) {
        if (stopReportDelayMs > 0L) {
            delay(stopReportDelayMs)
        }
        stoppedReports += PlaybackReport(identity, positionMs, durationMs)
    }

    override suspend fun getCanonicalContinueWatching(
        limit: Int,
        nowMs: Long,
    ): CanonicalContinueWatchingResult {
        continueWatchingCalls += nowMs
        if (continueWatchingFailures > 0) {
            continueWatchingFailures--
            throw IllegalStateException("offline")
        }
        return continueWatchingAnswer
    }

    override suspend fun removeFromPlayback(playbackId: String): WatchHistoryResult {
        removedIds += playbackId
        return removeAnswer
    }

    override suspend fun markWatched(request: WatchHistoryRequest): WatchHistoryResult =
        throw AssertionError("markWatched is not stubbed")

    override suspend fun unmarkWatched(request: WatchHistoryRequest): WatchHistoryResult =
        throw AssertionError("unmarkWatched is not stubbed")
}