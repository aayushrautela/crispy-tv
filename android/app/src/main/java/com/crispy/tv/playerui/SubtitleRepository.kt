package com.crispy.tv.playerui

import android.util.Log
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.streams.AddonStreamsService
import com.crispy.tv.streams.AddonSubtitle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "CrispySubtitles"

class SubtitleRepository(
    private val addonStreamsService: AddonStreamsService,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _addonSubtitles = MutableStateFlow<List<AddonSubtitle>>(emptyList())
    val addonSubtitles: StateFlow<List<AddonSubtitle>> = _addonSubtitles.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun fetchAddonSubtitles(mediaType: MetadataLabMediaType, lookupId: String) {
        Log.d(TAG, "fetch mediaType=$mediaType id=$lookupId")
        scope.launch {
            _isLoading.value = true
            _error.value = null
            runCatching {
                addonStreamsService.fetchAddonSubtitles(mediaType, lookupId)
            }.onSuccess { subtitles ->
                Log.d(TAG, "success count=${subtitles.size}")
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

    fun clear() {
        _addonSubtitles.value = emptyList()
        _isLoading.value = false
        _error.value = null
    }
}
