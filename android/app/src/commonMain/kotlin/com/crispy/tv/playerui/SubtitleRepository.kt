package com.crispy.tv.playerui

import com.crispy.tv.platform.AppLogger
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.addons.streams.AddonSubtitle
import com.crispy.tv.addons.streams.StreamResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
     * deterministically; the default is the behaviour this class always had, and the
     * call sites in the composition root keep reading `fetchAddonSubtitles` the same way.
     */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    private val cacheLock = Any()
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
        if (!force && serveFromCache(mediaType, lookupId)) {
            return
        }
        logger.debug(TAG, "fetch mediaType=$mediaType id=$lookupId force=$force")
        scope.launch {
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
    private fun serveFromCache(mediaType: MetadataLabMediaType, lookupId: String): Boolean {
        synchronized(cacheLock) {
            if (cachedKey != mediaType to lookupId) {
                return false
            }
            logger.debug(TAG, "cache hit mediaType=$mediaType id=$lookupId count=${cachedSubtitles.size}")
            // Redundant today, and kept for that reason. The cache is a single slot
            // and only `onSuccess` ever writes it, on the line before this value is
            // published, with the same list - so a hit's cached list is by construction
            // the list already on screen. No test can see this assignment removed. It
            // becomes load-bearing the moment the cache holds more than one title, or a
            // failure can publish something without storing, so it states the invariant
            // rather than being quietly deleted. See the redundant-guard note in AGENTS.md.
            _addonSubtitles.value = cachedSubtitles
            _isLoading.value = false
            _error.value = null
            return true
        }
    }

    private fun storeInCache(
        mediaType: MetadataLabMediaType,
        lookupId: String,
        subtitles: List<AddonSubtitle>,
    ) {
        synchronized(cacheLock) {
            cachedKey = mediaType to lookupId
            cachedSubtitles = subtitles
        }
    }
}
