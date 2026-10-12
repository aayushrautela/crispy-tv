package com.crispy.tv.playerui

import com.crispy.tv.platform.AppLogger
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.addons.streams.AddonSubtitle
import com.crispy.tv.addons.streams.StreamResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "CrispySubtitles"

class SubtitleRepository(
    private val streamResolver: StreamResolver,
    private val logger: AppLogger,
    /**
     * The scope fetches run in. Injected rather than created so a test can drive them
     * deterministically.
     *
     * No default, and the old default was wrong in two directions at once. It read
     * `Dispatchers.IO`, which does not exist in `commonMain` -- on Kotlin/Native it is
     * `internal`, so this file did not compile for an Apple target. And it had a default
     * *because* the default was "the behaviour this class always had", which is exactly
     * the reasoning a shared-source-set port has to reject: a defaulted dispatcher
     * silently puts blocking network and disk work on whatever the default happens to
     * be, and where the old default does not exist at all the caller gets no signal.
     * The caller is the composition root, where `Dispatchers.IO` does exist and is the
     * right answer. See the other view models' `ioDispatcher` slots for the same shape.
     */
    private val scope: CoroutineScope,
) {

    /**
     * Guards the two cache fields below.
     *
     * It replaces a `synchronized(cacheLock)`, and the reason is not simply that
     * `synchronized` is JVM-only -- `Mutex` is the portable answer and is what the rest
     * of this repository uses. Two others mattered.
     *
     * **The lock was guarding a pair, and a lock does not make a pair atomic.** A
     * reader could take the new key and the old list, because the two reads sit either
     * side of the point where the other thread is mid-write. The old code made each
     * field's access safe and the *pair's* consistency a matter of luck. Holding the
     * pair as one immutable value inside the critical section fixes that for free.
     *
     * **`withLock` suspends, so the cache-hit path had to become scheduled.** That is a
     * real cost and it is why the change is stated here rather than buried:
     * `serveFromCache` used to answer `Boolean` synchronously to a non-suspending
     * `fetchAddonSubtitles`, and it no longer does -- the answer now arrives one
     * dispatch later. Nothing renders differently, because the subtitle surface already
     * observes `StateFlow` values, and the fetch path was always `scope.launch`. The
     * one caller that had to change is `PlayerSessionViewModel.fetchAddonSubtitles`,
     * whose own five call sites include three non-suspending ones, so it launches.
     *
     * The alternative was a single immutable slot in an `AtomicReference`, which would
     * have kept the synchronous answer. **`kotlin.concurrent.atomics` is not in this
     * toolchain's standard library** -- `Unresolved reference 'concurrent'` on the
     * import, measured, not assumed -- and pulling in `atomicfu` for one field is a
     * heavier answer than one dispatch.
     */
    private val cacheLock = Mutex()
    private var cachedKey: Pair<MetadataLabMediaType, String>? = null
    private var cachedSubtitles: List<AddonSubtitle> = emptyList()

    /**
     * The fetch currently running for [inFlightKey], under [cacheLock].
     *
     * **Dedup, because the same title is asked for twice on the way into the
     * player.** `PlayerSessionViewModel.startPlayback` fetches at entry, and then
     * `playResolvedStream` / `handleChosenStream` fetch again for the same title once
     * the stream has been chosen. Both are real requests and neither is wrong, but the
     * second one lands while the first is still mid-flight -- the cache is only written
     * *after* the network round trip -- so it used to start a second identical round
     * trip, resolving every addon manifest a second time. A caller that arrives for a
     * title already being fetched now joins that one instead.
     */
    private var inFlightKey: Pair<MetadataLabMediaType, String>? = null
    private var inFlightJob: Job? = null

    private val _addonSubtitles = MutableStateFlow<List<AddonSubtitle>>(emptyList())
    val addonSubtitles: StateFlow<List<AddonSubtitle>> = _addonSubtitles.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /**
     * Start a fetch and return without waiting for it.
     *
     * Returns the [Job] rather than `Unit` because one caller has to know when the
     * fetch has actually finished, and the repository's own state cannot tell it:
     * `isLoading` is raised *inside* the coroutine, so it is still `false` when this
     * function returns. The player needs to attach a matched subtitle before the first
     * `play()` call -- see [fetchAndAwait] for why that deadline is real -- and
     * "wait on `isLoading`" was measured to wait on a signal that had not been sent.
     *
     * The caller that suspends is the only one that has to; `ViewState` observers keep
     * ignoring the result.
     */
    fun fetchAddonSubtitles(
        mediaType: MetadataLabMediaType,
        lookupId: String,
        force: Boolean = false,
    ): Job = scope.launch { fetchAndAwait(mediaType, lookupId, force) }

    /**
     * The fetch, as a suspend function that returns when it has finished.
     *
     * This is the same work as [fetchAddonSubtitles], and it exists because the
     * fire-and-forget one cannot be waited on. Two reasons, both measured rather than
     * argued.
     *
     * **`isLoading` is not an answer to "has it finished".** It goes `false` before
     * `launch` has even started -- `_isLoading.value = true` is set *inside* the
     * coroutine -- so a caller that waits for it to fall has waited on a signal that
     * was never raised, and returns with no subtitles and no error in a state that
     * reads as "done".
     *
     * **The repository has no per-key state to ask about.** The cache is a single
     * slot, so "has this key been fetched" is not a question it can answer without
     * adding one. The suspend function is that answer, without the slot.
     *
     * **A request for a title already being fetched joins it** rather than starting
     * a second round trip -- see [inFlightJob]. So this can return while having made
     * no network call of its own, which is the point.
     *
     * `ViewState` observers keep using [fetchAddonSubtitles]. The one caller that
     * suspends is the one that has to: the player must attach a matched subtitle
     * *before* the first `play()` call, because the Exo path rebuilds the media item
     * to add one afterwards and that is a visible re-prepare.
     */
    suspend fun fetchAndAwait(
        mediaType: MetadataLabMediaType,
        lookupId: String,
        force: Boolean = false,
    ) {
        if (!force && serveFromCache(mediaType, lookupId)) {
            return
        }
        val key = mediaType to lookupId
        if (!force) {
            val running =
                cacheLock.withLock {
                    if (inFlightKey == key) inFlightJob else null
                }
            if (running != null) {
                logger.debug(TAG, "join in-flight fetch mediaType=$mediaType id=$lookupId")
                // Waiting for the other caller's fetch rather than starting a second
                // one. It does not throw: `runFetch` answers every failure as state,
                // so this returns whether it succeeded or not.
                running.join()
                return
            }
        }
        val job =
            scope.launch { runFetch(mediaType, lookupId, this.coroutineContext.job) }
        cacheLock.withLock {
            if (force) {
                // A forced fetch supersedes one still in flight for the same title;
                // cancelling it rather than leaving both running is what stops the
                // superseded one publishing its answer over the fresh one later.
                inFlightJob?.cancel()
            }
            inFlightKey = key
            inFlightJob = job
        }
        job.join()
    }

    private suspend fun runFetch(
        mediaType: MetadataLabMediaType,
        lookupId: String,
        job: Job,
    ) {
        logger.debug(TAG, "fetch mediaType=$mediaType id=$lookupId")
        _isLoading.value = true
        _error.value = null
        try {
            // The network call answers a failure as `null` and lets cancellation pass
            // through unwritten: `catch (throwable: Throwable)` below would swallow the
            // `CancellationException` too, so the rethrow-first arm is load-bearing.
            // Rethrowing also skips `_isLoading.value = false` further down on purpose.
            // A cancelled job did not finish -- and the one case where the flag must
            // not be lowered here is the force-supersede in `fetchAndAwait`, where the
            // replacing fetch has already raised it. A torn-down scope has no observers
            // left to read the flag anyway.
            val subtitles =
                try {
                    streamResolver.fetchAddonSubtitles(mediaType, lookupId)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (throwable: Throwable) {
                    logger.debug(TAG, "failure ${throwable.message}")
                    _error.value = throwable.message ?: "Failed to fetch subtitles"
                    null
                }
            if (subtitles != null) {
                logger.debug(TAG, "success count=${subtitles.size}")
                // An empty answer is not stored.
                //
                // It is not evidence of absence. `AddonStreamsService` returns
                // `emptyList()` for "no addon declares a `subtitles` resource" *and* for
                // "the addons were not warm yet at entry time", and those are different
                // events. Storing the second as if it were the first made the entry fetch
                // poison every later non-forced one: opening the subtitle sheet hit the
                // cache, saw nothing, and showed an empty sheet until the viewer pressed
                // Search -- which is exactly the "subtitles only fetch once I open the
                // sheet" report. Failures never cached either, so an empty answer is the
                // one remaining case where a successful round trip used to be remembered
                // as absence.
                if (subtitles.isNotEmpty()) {
                    storeInCache(mediaType, lookupId, subtitles)
                }
                _addonSubtitles.value = subtitles
                if (subtitles.isEmpty()) {
                    _error.value = "No subtitles found"
                }
            }
            _isLoading.value = false
        } finally {
            // Released even on cancellation, because a finished-or-dead job is not an
            // in-flight one. Without this the slot would keep holding the dead job and
            // a later non-forced call for the same title would `join()` it -- which for
            // a cancelled job throws into the joiner rather than returning.
            //
            // The identity check is there because a forced fetch cancels this job before
            // overwriting the slot with its own, and this `finally` runs *after* that
            // overwrite in whichever order the cancellations land.
            cacheLock.withLock {
                if (inFlightJob === job) {
                    inFlightKey = null
                    inFlightJob = null
                }
            }
        }
    }

    // Sheet opens hit this instead of re-downloading every addon manifest; only the
    // explicit Search action forces a fresh network round trip.
    private suspend fun serveFromCache(mediaType: MetadataLabMediaType, lookupId: String): Boolean =
        cacheLock.withLock {
            val key = cachedKey
            val subtitles = cachedSubtitles
            if (key != mediaType to lookupId) {
                return@withLock false
            }
            logger.debug(TAG, "cache hit mediaType=$mediaType id=$lookupId count=${subtitles.size}")
            // Redundant today, and kept for that reason. The cache is a single slot
            // and only a non-empty answer ever writes it, on the line before this
            // value is published, with the same list - so a hit's cached list is by
            // construction the list already on screen. No test can see this assignment
            // removed. It becomes load-bearing the moment the cache holds more than one
            // title, or a failure can publish something without storing, so it states
            // the invariant rather than being quietly deleted. See the redundant-guard
            // note in AGENTS.md.
            _addonSubtitles.value = subtitles
            _isLoading.value = false
            _error.value = null
            return@withLock true
        }

    private suspend fun storeInCache(
        mediaType: MetadataLabMediaType,
        lookupId: String,
        subtitles: List<AddonSubtitle>,
    ) = cacheLock.withLock {
        cachedKey = mediaType to lookupId
        cachedSubtitles = subtitles
    }
}
