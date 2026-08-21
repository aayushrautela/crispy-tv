package com.crispy.tv.streams

import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.home.MediaDetails
import com.crispy.tv.home.MediaVideo
import com.crispy.tv.metadata.toMediaDetails
import com.crispy.tv.playback.StreamLookupTarget
import com.crispy.tv.playback.applyProviderResult
import com.crispy.tv.playback.finalizeFrom
import com.crispy.tv.playback.matchesTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Single source of truth for stream-selector state. Used by every surface that opens the
 * selector (Player, Details, Home). It resolves streams via [StreamResolver] and, when given
 * [open] with a non-null [itemIdForMetadata], enriches the header metadata from the backend in
 * parallel with the addon lookup. Surfaces that already hold [fallbackDetails] avoid that fetch.
 */
class SelectorCoordinator(
    private val scope: CoroutineScope,
    private val streamResolver: StreamResolver,
    private val getMetadataItemDetail: suspend (accessToken: String, itemId: String) -> CrispyBackendClient.MetadataTitleDetailResponse,
    private val sessionTokenProvider: suspend () -> String?,
) {
    private val _state = MutableStateFlow(StreamSelectorUiState())
    val state: StateFlow<StreamSelectorUiState> = _state.asStateFlow()

    private val _details = MutableStateFlow<MediaDetails?>(null)
    val details: StateFlow<MediaDetails?> = _details.asStateFlow()

    private val _headerEpisode = MutableStateFlow<MediaVideo?>(null)
    val headerEpisode: StateFlow<MediaVideo?> = _headerEpisode.asStateFlow()

    private var currentTarget: StreamLookupTarget? = null
    private var onStreamSelected: ((AddonStream) -> Unit)? = null
    private var resolveJob: Job? = null
    private var sessionId = 0L

    fun open(
        target: StreamLookupTarget,
        headerEpisode: MediaVideo?,
        fallbackDetails: MediaDetails?,
        itemIdForMetadata: String?,
        onStreamSelected: (AddonStream) -> Unit,
    ) {
        this.onStreamSelected = onStreamSelected
        this.currentTarget = target
        _headerEpisode.value = headerEpisode
        _details.value = fallbackDetails
        val session = ++sessionId
        _state.value =
            StreamSelectorUiState(
                visible = true,
                mediaType = target.mediaType,
                lookupId = target.lookupId,
                headerEpisode = headerEpisode,
                selectedProviderId = null,
                providers = emptyList(),
                isLoading = true,
            )
        resolveJob?.cancel()
        resolveJob = scope.launch { resolve(session, target, itemIdForMetadata) }
    }

    private suspend fun resolve(
        session: Long,
        target: StreamLookupTarget,
        itemIdForMetadata: String?,
    ) {
        val metadataJob =
            if (itemIdForMetadata != null) {
                scope.launch {
                    val token = runCatching { sessionTokenProvider() }.getOrNull() ?: return@launch
                    runCatching { getMetadataItemDetail(token, itemIdForMetadata) }
                        .onSuccess { response ->
                            if (session == sessionId && currentTarget == target) _details.value = response.toMediaDetails()
                        }
                }
            } else {
                null
            }

        streamResolver.resolve(
            target = target,
            onProvidersResolved = {
                if (session == sessionId && currentTarget == target) {
                    _state.update { it.copy(providers = emptyList(), isLoading = true) }
                }
            },
            onProviderResult = { result ->
                if (session == sessionId && currentTarget == target) {
                    _state.update { it.copy(providers = it.providers.applyProviderResult(result)) }
                }
            },
        ).also { results ->
            if (session == sessionId && currentTarget == target) {
                _state.update { it.copy(providers = it.providers.finalizeFrom(results), isLoading = false) }
            }
        }

        metadataJob?.join()
    }

    fun onProviderSelected(providerId: String?) {
        _state.update { it.copy(selectedProviderId = providerId) }
    }

    fun onRetryProvider(providerId: String) {
        val target = currentTarget ?: return
        val cur = _state.value
        if (!cur.matchesTarget(target)) return
        _state.update {
            it.copy(
                isLoading = true,
                providers =
                    it.providers.map { p ->
                        if (p.providerId.equals(providerId, ignoreCase = true)) {
                            p.copy(isLoading = true, errorMessage = null)
                        } else {
                            p
                        }
                    },
            )
        }
        scope.launch {
            val result =
                runCatching { streamResolver.loadProviderStreams(target.mediaType, target.lookupId, providerId) }
                    .getOrNull()
            if (result != null && currentTarget == target) {
                _state.update { it.copy(providers = it.providers.applyProviderResult(result), isLoading = false) }
            }
        }
    }

    fun onStreamSelected(stream: AddonStream) {
        onStreamSelected?.invoke(stream)
    }

    fun dismiss() {
        ++sessionId
        resolveJob?.cancel()
        resolveJob = null
        _state.update { it.copy(visible = false) }
        onStreamSelected = null
        currentTarget = null
    }
}
