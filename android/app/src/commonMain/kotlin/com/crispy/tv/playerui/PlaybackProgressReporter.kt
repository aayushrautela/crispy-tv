package com.crispy.tv.playerui

import com.crispy.tv.home.HomeRefreshBus
import com.crispy.tv.home.HomeRefreshEvent
import com.crispy.tv.platform.MonotonicClock
import com.crispy.tv.player.PlaybackIdentity
import com.crispy.tv.player.WatchHistoryService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Owns every "the player moved" report the session sends to the backend.
 *
 * ## What was extracted, and why this is in `commonMain` rather than beside the view model
 *
 * These were three private methods of `PlayerSessionViewModel`, and `PlayerSessionViewModel`
 * is pinned to `androidMain` for reasons that have nothing to do with them: it holds a
 * `PlaybackController` and an `AudioFocusManager`, both declared in `:android:native-engine`,
 * which is a plain `com.android.library` and so publishes no JVM variant. The reporting
 * itself never touched either. Everything it reaches -- `WatchHistoryService` with its three
 * `onPlayback*` methods, `PlaybackIdentity`, `HomeRefreshBus`, `HomeRefreshEvent` and
 * `:android:platform-core`'s `MonotonicClock` -- is declared in a `commonMain`. So this
 * cluster is the part of the view model that was portable all along.
 *
 * The one Android call it had, `SystemClock.elapsedRealtime()`, is a monotonic reading, and
 * `MonotonicClock` is exactly that abstraction and already ships as a port with an Android
 * and a desktop implementation. It is a **no-default** slot: a defaulted `clock` would let a
 * caller leave the default in place and get wall-clock time, which jumps forwards on a time
 * sync and would let the settle window and the persist interval silently stop meaning
 * anything.
 *
 * ## Why the write slots and not `PlayerUiState`
 *
 * The view model holds `PlayerUiState`, which is `androidMain`. Rather than dragging that
 * type across, this takes the three things it actually reads from it. It also takes the
 * position and duration as **two** read slots rather than one snapshot, because the original
 * read them at two different moments and that is load-bearing: see
 * [reportPlaybackStopped].
 *
 * The method names are the originals' unchanged. `PlayerSessionViewModel` keeps calling
 * `syncWatchHistory`, `scheduleProgressSyncAfterSeek` and `reportPlaybackStopped`, so the
 * extraction is a delegation rather than a rewrite, and a reader comparing against `HEAD`
 * sees one word change per call site.
 */
class PlaybackProgressReporter(
    private val watchHistoryService: WatchHistoryService,
    private val clock: MonotonicClock,
    private val scope: CoroutineScope,
    /** The title currently being played, or `null` before one has been resolved. */
    private val identity: () -> PlaybackIdentity?,
    /** Read at the moment of a stop report -- *inside* the launched coroutine. */
    private val currentPositionMs: () -> Long,
    /** Read *before* a stop report is launched, because it gates whether one is sent at all. */
    private val currentDurationMs: () -> Long,
) {

    private var hasReportedPlaybackStart = false
    private var hasReportedPlaybackStop = false
    private var lastProgressSyncAtElapsedMs = 0L
    private var seekSettleUntilElapsedMs = 0L
    private var seekSettleJob: Job? = null

    /**
     * The poll's progress report: a `playback_started` the first time playback settles past
     * [MIN_PROGRESS_POSITION_MS], a `playback_progress` every [PROGRESS_SYNC_INTERVAL_MS]
     * after that, and nothing in between.
     *
     * Three orderings in here are behaviour rather than style, and each is pinned:
     *
     * 1. **[clock.elapsedMs()] is read twice** -- once for the settle-window guard and once for
     *    `nowElapsedMs`. The second reading is strictly later than the first, so a single
     *    reading reused for both would change which side of [seekSettleUntilElapsedMs] a poll
     *    near the boundary lands on. The two calls are deliberately not one.
     * 2. **The start branch returns.** A poll that reports `playback_started` does *not* also
     *    report `playback_progress` for the same instant.
     *
     *    The `return` here is a **redundant guard**, and it stays. Deleting it alone changes
     *    no answer, because the fall-through below is caught twice over by the assignment
     *    immediately above it: the start branch has set `hasReportedPlaybackStart`, so
     *    `!hasReportedPlaybackStart` is false, *and* it has just stamped
     *    [lastProgressSyncAtElapsedMs], so the delta is 0. Either one ends the poll.
     *
     *    So why keep it? Because the two defences are not the same shape and a future edit can
     *    remove either one silently -- the stamp, for instance, if a "resume does not count as
     *    progress" change ever stops writing it. The explicit `return` states the rule without
     *    depending on an assignment three lines up doing the reader's work. Removing
     *    [both] is caught by `aFirstPollOnALongRunningSessionStillReportsOnlyAStart`, which
     *    polls with the clock already past [PROGRESS_SYNC_INTERVAL_MS] so the interval guard
     *    is *not* what ends the fall-through -- which is the only shape that tells the two
     *    apart. This is the sixteenth redundant guard in this repository; see `AGENTS.md`.
     * 3. **[lastProgressSyncAtElapsedMs] is assigned before the launch, in both branches.** A
     *    slow `onPlaybackProgress` therefore cannot cause a second send for the same window:
     *    the stamp moves whether or not the write has landed.
     *
     * A report is skipped entirely while [seekSettleUntilElapsedMs] is in the future, because
     * while the player settles after a seek, a load or an engine switch the engine reports a
     * transient position of 0 -- and persisting that would move the user back to the start.
     */
    fun syncWatchHistory(positionMs: Long, durationMs: Long, isPlaying: Boolean) {
        val playbackIdentity = identity() ?: return
        if (durationMs <= 0L) {
            return
        }

        // While the player settles after a seek/load/engine switch the engine reports a
        // transient 0. Skip reporting until the settle window passes, then resume.
        if (clock.elapsedMs() < seekSettleUntilElapsedMs) {
            return
        }

        val nowElapsedMs = clock.elapsedMs()
        if (!hasReportedPlaybackStart && isPlaying && positionMs >= MIN_PROGRESS_POSITION_MS) {
            hasReportedPlaybackStart = true
            hasReportedPlaybackStop = false
            lastProgressSyncAtElapsedMs = nowElapsedMs
            scope.launch {
                watchHistoryService.onPlaybackStarted(
                    identity = playbackIdentity,
                    positionMs = positionMs,
                    durationMs = durationMs,
                )
            }
            return
        }

        if (!hasReportedPlaybackStart || nowElapsedMs - lastProgressSyncAtElapsedMs < PROGRESS_SYNC_INTERVAL_MS) {
            return
        }

        lastProgressSyncAtElapsedMs = nowElapsedMs
        scope.launch {
            watchHistoryService.onPlaybackProgress(
                identity = playbackIdentity,
                positionMs = positionMs,
                durationMs = durationMs,
                isPlaying = isPlaying,
            )
        }
    }

    /**
     * Arms the settle window for [PLAYER_SEEK_PROGRESS_SYNC_DEBOUNCE_MS] and cancels any
     * window already armed, so a second seek restarts the wait rather than inheriting the
     * first one's deadline.
     *
     * When the window expires it clears both [seekSettleUntilElapsedMs] **and**
     * [lastProgressSyncAtElapsedMs]. The second part is the reason this exists: zeroing the
     * last-sync stamp is what forces an immediate progress push on the next settled poll
     * instead of making the user wait out a full [PROGRESS_SYNC_INTERVAL_MS] after seeking.
     */
    fun scheduleProgressSyncAfterSeek() {
        seekSettleJob?.cancel()
        seekSettleUntilElapsedMs = clock.elapsedMs() + PLAYER_SEEK_PROGRESS_SYNC_DEBOUNCE_MS
        seekSettleJob = scope.launch {
            delay(PLAYER_SEEK_PROGRESS_SYNC_DEBOUNCE_MS)
            // Clearing the settle window also forces an immediate progress push on the
            // next poll instead of waiting out the full persist interval.
            seekSettleUntilElapsedMs = 0L
            lastProgressSyncAtElapsedMs = 0L
        }
    }

    /**
     * Reports the end of playback exactly once, then refreshes the home screen.
     *
     * Two orderings are behaviour here, and the second is the subtle one:
     *
     * 1. **[hasReportedPlaybackStop] is latched before the duration guard.** A stop arriving
     *    while the player never reported a duration therefore consumes its one chance without
     *    sending anything. That is intentional -- the alternative re-sends a stop for every
     *    subsequent teardown once the duration is known, and a stop is not idempotent on the
     *    backend. Pinning it matters precisely because it reads like a bug.
     * 2. **The duration is read before the launch and the position inside it.** One reading
     *    would be wrong in both directions: a player torn down between the two would either
     *    report a stale duration or skip a real stop. It is also why the two values are two
     *    slots rather than one snapshot of the metrics holder.
     *
     * The whole body is wrapped in [withTimeoutOrNull] because a backend that never answers
     * must not hold the teardown hostage. The consequence -- which is worth stating rather
     * than hiding -- is that **a hung backend drops the [HomeRefreshBus] emit with it**, since
     * the emit sits inside the timeout. The home screen then refreshes on its next own trigger.
     */
    fun reportPlaybackStopped(playbackIdentity: PlaybackIdentity) {
        if (hasReportedPlaybackStop) {
            return
        }
        hasReportedPlaybackStop = true

        val lastDurationMs = currentDurationMs()
        if (lastDurationMs <= 0L) {
            return
        }

        scope.launch {
            withTimeoutOrNull(STOP_REPORT_TIMEOUT_MS) {
                watchHistoryService.onPlaybackStopped(
                    identity = playbackIdentity,
                    positionMs = currentPositionMs(),
                    durationMs = lastDurationMs,
                )
                HomeRefreshBus.emit(HomeRefreshEvent.PlaybackEnded)
            }
        }
    }

    /**
     * Returns the reporting state to its "nothing has happened yet" condition.
     *
     * This is a fifth member because the two sites that needed it were **byte-identical**
     * five-line blocks -- one for a fresh playback session, one for an engine switch -- and a
     * duplicated body is a signal that one copy needs a caller. It is a real member rather
     * than a plain function because it touches private state, so keeping the reset in one
     * place is what makes the five fields able to leave the view model at all.
     */
    fun resetReportingState() {
        hasReportedPlaybackStart = false
        hasReportedPlaybackStop = false
        lastProgressSyncAtElapsedMs = 0L
        seekSettleJob?.cancel()
        seekSettleUntilElapsedMs = 0L
    }
}

/** How often a settled poll may persist progress, once a start has been reported. */
internal const val PROGRESS_SYNC_INTERVAL_MS = 60_000L

/**
 * How long progress reporting stays suppressed after a seek, a load or an engine switch.
 *
 * During the window the engine reports a transient position of 0, and persisting that would
 * move the user back to the start of what they were watching.
 */
internal const val PLAYER_SEEK_PROGRESS_SYNC_DEBOUNCE_MS = 700L

/**
 * How far playback must have got before a poll reports a start at all.
 *
 * A user who opens something and immediately backs out has not watched it, and a
 * `playback_started` for them would put it in continue-watching for no reason.
 */
internal const val MIN_PROGRESS_POSITION_MS = 1000L

/**
 * How long a stop report may take before it is abandoned.
 *
 * It exists so a backend that never answers cannot block teardown. It is generous enough for
 * a slow network and short enough that the user does not see a frozen frame on exit.
 */
internal const val STOP_REPORT_TIMEOUT_MS = 3_000L