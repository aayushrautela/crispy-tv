package com.crispy.tv.streams

import com.crispy.tv.addons.streams.StreamSelectorUiState
import com.crispy.tv.addons.streams.StreamProviderUiState
import com.crispy.tv.addons.streams.StreamResolver
import com.crispy.tv.addons.streams.AddonStream
import com.crispy.tv.addons.streams.ProviderStreamsResult
import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.addons.model.MediaDetails
import com.crispy.tv.addons.model.MediaVideo
import com.crispy.tv.addons.mapping.toMediaDetails
import com.crispy.tv.addons.lookup.StreamLookupTarget
import com.crispy.tv.addons.lookup.applyProviderResult
import com.crispy.tv.addons.lookup.finalizeFrom
import com.crispy.tv.addons.lookup.matchesTarget
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
 * When [pluginStreamLoader] is set (foss builds), JS plugin providers are resolved in parallel
 * with the addon lookup and merged through the same provider-result pipeline.
 */
class SelectorCoordinator(
    private val scope: CoroutineScope,
    private val streamResolver: StreamResolver,
    private val getMetadataItemDetail: suspend (accessToken: String, itemId: String) -> CrispyBackendClient.MetadataTitleDetailResponse,
    private val sessionTokenProvider: suspend () -> String?,
    private val pluginStreamLoader: PluginStreamLoader? = null,
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

        val pluginJob =
            pluginStreamLoader?.let { loader ->
                scope.launch {
                    metadataJob?.join()
                    if (session != sessionId || currentTarget != target) return@launch
                    val request = buildPluginRequest(target) ?: return@launch
                    val results =
                        runCatching { loader.load(request) }.getOrElse { error ->
                            listOf(
                                ProviderStreamsResult(
                                    providerId = PLUGIN_PROVIDER_ERROR_ID,
                                    providerName = "Plugins",
                                    streams = emptyList(),
                                    errorMessage = error.message ?: "Plugin stream loading failed",
                                ),
                            )
                        }
                    if (session == sessionId && currentTarget == target) {
                        _state.update { state ->
                            val updated =
                                results.fold(state.providers) { providers, result ->
                                    providers.applyProviderResult(result)
                                }
                            state.copy(providers = updated)
                        }
                    }
                }
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

        pluginJob?.join()
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
                if (providerId.startsWith(PLUGIN_PROVIDER_PREFIX, ignoreCase = true)) {
                    val request = buildPluginRequest(target)
                    request?.let { requestValue ->
                        runCatching { pluginStreamLoader?.load(requestValue) }
                            .getOrNull()
                            ?.firstOrNull { it.providerId.equals(providerId, ignoreCase = true) }
                    }
                } else {
                    runCatching { streamResolver.loadProviderStreams(target.mediaType, target.lookupId, providerId) }
                        .getOrNull()
                }
            if (result != null && currentTarget == target) {
                _state.update { it.copy(providers = it.providers.applyProviderResult(result), isLoading = false) }
            } else if (currentTarget == target) {
                _state.update { it.copy(isLoading = false) }
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

    private fun buildPluginRequest(target: StreamLookupTarget): PluginStreamRequest? {
        if (pluginStreamLoader == null) return null
        val details = _details.value
        val episode = _headerEpisode.value
        return PluginStreamRequest(
            mediaType = target.mediaType,
            lookupId = target.lookupId,
            title = details?.title,
            year = details?.year,
            season = episode?.season,
            episode = episode?.episode,
        )
    }

    private companion object {
        const val PLUGIN_PROVIDER_PREFIX = "plugin:"
        const val PLUGIN_PROVIDER_ERROR_ID = "plugin:error"
    }
}
