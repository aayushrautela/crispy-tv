package com.crispy.tv.playerui

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
        scope.launch {
            _isLoading.value = true
            _error.value = null
            runCatching {
                addonStreamsService.fetchAddonSubtitles(mediaType, lookupId)
            }.onSuccess { subtitles ->
                _addonSubtitles.value = subtitles
                if (subtitles.isEmpty()) {
                    _error.value = "No subtitles found"
                }
            }.onFailure { throwable ->
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
