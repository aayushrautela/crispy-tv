package com.crispy.tv.streams

import com.crispy.tv.addons.streams.StreamSelectorUiState
import com.crispy.tv.addons.streams.StreamProviderUiState
import com.crispy.tv.addons.streams.AddonStream
import com.crispy.tv.addons.streams.StreamResolver
import com.crispy.tv.addons.streams.seedProviders
import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.addons.model.MediaDetails
import com.crispy.tv.addons.model.MediaVideo
import com.crispy.tv.addons.mapping.toMediaDetails
import com.crispy.tv.addons.lookup.StreamLookupTarget
import com.crispy.tv.addons.lookup.applyProviderResult
import com.crispy.tv.addons.lookup.finalizeFrom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
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
                pluginsPending = pluginStreamLoader != null,
            )
        resolveJob?.cancel()
        resolveJob = scope.launch { resolve(session, target, itemIdForMetadata) }
    }

    private suspend fun resolve(
        session: Long,
        target: StreamLookupTarget,
        itemIdForMetadata: String?,
    ) = coroutineScope {
        val metadataJob =
            if (itemIdForMetadata != null) {
                launch {
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
                launch {
                    if (session != sessionId || currentTarget != target) return@launch
                    // Plugins key on the tmdb id. Surfaces without one on hand (home
                    // continue-watching) wait for the metadata fetch already in flight
                    // rather than calling plugins with an empty id.
                    val resolvedTarget =
                        if (target.tmdbId == null && metadataJob != null) {
                            metadataJob.join()
                            if (session != sessionId || currentTarget != target) return@launch
                            val enrichedTmdbId = _details.value?.tmdbId
                            if (enrichedTmdbId != null) target.copy(tmdbId = enrichedTmdbId) else target
                        } else {
                            target
                        }
                    val request = buildPluginRequest(resolvedTarget) ?: return@launch
                    loader.stream(request)
                        .catch { error ->
                            android.util.Log.w("CrispyPlugins", "plugin stream flow failed: ${error.message}")
                        }
                        .collect { result ->
                            if (session == sessionId && currentTarget == target) {
                                _state.update { state ->
                                    state.copy(providers = state.providers.applyProviderResult(result))
                                }
                            }
                        }
                    if (session == sessionId && currentTarget == target) {
                        _state.update { it.copy(pluginsPending = false) }
                    }
                }
            }

        streamResolver.resolve(
            target = target,
            onProvidersResolved = { descriptors ->
                if (session == sessionId && currentTarget == target) {
                    _state.update { it.copy(providers = it.providers.seedProviders(descriptors)) }
                }
            },
            onProviderResult = { result ->
                if (session == sessionId && currentTarget == target) {
                    _state.update { it.copy(providers = it.providers.applyProviderResult(result)) }
                }
            },
        ).also { results ->
            if (session == sessionId && currentTarget == target) {
                _state.update { it.copy(providers = it.providers.finalizeFrom(results)) }
            }
        }

        pluginJob?.join()
        metadataJob?.join()
    }

    fun onProviderSelected(providerId: String?) {
        _state.update { it.copy(selectedProviderId = providerId) }
    }

    /** Re-reveals already-resolved results without refetching (same title reopen). */
    fun reshow(headerEpisode: MediaVideo?) {
        if (currentTarget == null) return
        if (headerEpisode != null) _headerEpisode.value = headerEpisode
        _state.update { it.copy(visible = true, headerEpisode = headerEpisode ?: it.headerEpisode) }
    }

    fun onStreamSelected(stream: AddonStream) {
        onStreamSelected?.invoke(stream)
    }

    fun dismiss() {
        ++sessionId
        resolveJob?.cancel()
        resolveJob = null
        _state.update { cur ->
            cur.copy(
                visible = false,
                pluginsPending = false,
                providers = cur.providers.map { provider -> provider.copy(isLoading = false) },
            )
        }
        // onStreamSelected is kept so reshow() can re-reveal results for the same
        // target and stream taps still route to the surface that opened last;
        // open() always overwrites it. currentTarget is kept for the same reason.
    }

    private fun buildPluginRequest(target: StreamLookupTarget): PluginStreamRequest? {
        if (pluginStreamLoader == null) return null
        val episode = _headerEpisode.value
        return PluginStreamRequest(
            mediaType = target.mediaType,
            lookupId = target.lookupId,
            tmdbId = target.tmdbId,
            season = episode?.season,
            episode = episode?.episode,
        )
    }
}
