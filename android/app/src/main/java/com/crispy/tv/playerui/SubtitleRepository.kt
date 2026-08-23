package com.crispy.tv.playerui

import android.util.Log
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.streams.AddonSubtitle
import com.crispy.tv.streams.StreamResolver
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
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        Log.d(TAG, "fetch mediaType=$mediaType id=$lookupId force=$force")
        scope.launch {
            _isLoading.value = true
            _error.value = null
            runCatching {
                streamResolver.fetchAddonSubtitles(mediaType, lookupId)
            }.onSuccess { subtitles ->
                Log.d(TAG, "success count=${subtitles.size}")
                storeInCache(mediaType, lookupId, subtitles)
                _addonSubtitles.value = subtitles
                if (subtitles.isEmpty()) {
                    _error.value = "No subtitles found"
                }
            }.onFailure { throwable ->
                Log.d(TAG, "failure ${throwable.message}")
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
            Log.d(TAG, "cache hit mediaType=$mediaType id=$lookupId count=${cachedSubtitles.size}")
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
