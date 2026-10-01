package com.crispy.tv.playerui

import com.crispy.tv.platform.AppLogger
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.addons.streams.AddonSubtitle
import com.crispy.tv.addons.streams.StreamResolver
import kotlinx.coroutines.CoroutineScope
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

    private val _addonSubtitles = MutableStateFlow<List<AddonSubtitle>>(emptyList())
    val addonSubtitles: StateFlow<List<AddonSubtitle>> = _addonSubtitles.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun fetchAddonSubtitles(
        mediaType: MetadataLabMediaType,
        lookupId: String,
        force: Boolean = false,
    ) {
        scope.launch {
            if (!force && serveFromCache(mediaType, lookupId)) {
                return@launch
            }
            logger.debug(TAG, "fetch mediaType=$mediaType id=$lookupId force=$force")
            _isLoading.value = true
            _error.value = null
            runCatching {
                streamResolver.fetchAddonSubtitles(mediaType, lookupId)
            }.onSuccess { subtitles ->
                logger.debug(TAG, "success count=${subtitles.size}")
                storeInCache(mediaType, lookupId, subtitles)
                _addonSubtitles.value = subtitles
                if (subtitles.isEmpty()) {
                    _error.value = "No subtitles found"
                }
            }.onFailure { throwable ->
                logger.debug(TAG, "failure ${throwable.message}")
                _error.value = throwable.message ?: "Failed to fetch subtitles"
            }
            _isLoading.value = false
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
            // and only `onSuccess` ever writes it, on the line before this value is
            // published, with the same list - so a hit's cached list is by construction
            // the list already on screen. No test can see this assignment removed. It
            // becomes load-bearing the moment the cache holds more than one title, or a
            // failure can publish something without storing, so it states the invariant
            // rather than being quietly deleted. See the redundant-guard note in AGENTS.md.
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
