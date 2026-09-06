package com.crispy.tv.plugins.streams

import com.crispy.tv.addons.streams.AddonStream
import com.crispy.tv.addons.streams.ProviderStreamsResult
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.plugins.PluginStreamInput
import com.crispy.tv.plugins.normalizePluginMediaType
import com.crispy.tv.plugins.repo.PluginRepositoryManager
import com.crispy.tv.plugins.repo.PluginScraperDescriptor
import com.crispy.tv.plugins.runtime.PluginRuntime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal const val PLUGIN_PROVIDER_PREFIX = "plugin:"

/**
 * Public entry point the app consumes. Each provider result is emitted as its
 * scraper finishes, so surfaces render rows progressively instead of waiting
 * for the slowest scraper. Signature uses only public types so the internal
 * [PluginStreamsService] plumbing stays encapsulated.
 */
fun interface PluginStreamSource {
    fun stream(
        mediaType: MetadataLabMediaType,
        lookupId: String,
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
    ): Flow<ProviderStreamsResult>
}

internal class PluginStreamsService(
    private val repositoryManager: PluginRepositoryManager,
    private val runtimeProvider: () -> PluginRuntime?,
    private val refreshReposOnLoad: Boolean = true,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
) {

    /**
     * Emits one [ProviderStreamsResult] per matching scraper as it finishes.
     * Repo refresh runs alongside and never gates emissions; the flow completes
     * once every scraper (and the refresh) is done.
     */
    fun stream(
        mediaType: MetadataLabMediaType,
        lookupId: String,
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
    ): Flow<ProviderStreamsResult> =
        channelFlow {
            // Refresh runs alongside stream resolution instead of blocking it: manifest
            // fetches must never gate playback lookup. The scraper snapshot below may
            // miss repos that finish refreshing mid-load; they appear on the next load.
            launch {
                if (refreshReposOnLoad) {
                    runCatching { repositoryManager.refreshDueRepositories(nowEpochMs()) }
                        .onFailure { error -> android.util.Log.w(LOG_TAG, "repo refresh failed: ${error.message}") }
                }
            }
            val enabled = repositoryManager.getEnabledScrapers()
            val scrapers = enabled
                .filter { it.supports(mediaType) }
                .sortedBy { it.scraperId }
            android.util.Log.i(
                LOG_TAG,
                "stream mediaType=$mediaType lookupId=$lookupId season=$season episode=$episode: " +
                    "${enabled.size} enabled, ${scrapers.size} match (${scrapers.joinToString { it.scraperId }})",
            )
            if (scrapers.isEmpty()) {
                android.util.Log.w(LOG_TAG, "stream aborted: no matching scrapers")
                return@channelFlow
            }

            val runtime = runtimeProvider()
            if (runtime == null) {
                android.util.Log.w(LOG_TAG, "stream aborted: runtime unavailable")
                return@channelFlow
            }
            val input = buildInput(mediaType, lookupId, tmdbId, season, episode)
            android.util.Log.i(
                LOG_TAG,
                "plugin input tmdbId='${input.tmdbId}' imdbId='${input.imdbId}' mediaType='${input.mediaType}' " +
                    "season=${input.season} episode=${input.episode}",
            )

            val semaphore = Semaphore(MAX_CONCURRENT_SCRAPERS)
            scrapers.forEach { scraper ->
                launch {
                    val result = semaphore.withPermit {
                        runCatching { execute(runtime, scraper, input) }.getOrElse { error ->
                            ProviderStreamsResult(
                                providerId = providerId(scraper),
                                providerName = scraper.displayName,
                                streams = emptyList(),
                                errorMessage = error.message ?: "Plugin execution failed",
                            )
                        }
                    }
                    android.util.Log.i(
                        LOG_TAG,
                        "provider ${result.providerId} -> ${result.streams.size} stream(s)" +
                            (result.errorMessage?.let { " error=$it" } ?: ""),
                    )
                    send(result)
                }
            }
        }

    private suspend fun execute(
        runtime: PluginRuntime,
        scraper: PluginScraperDescriptor,
        input: PluginStreamInput,
    ): ProviderStreamsResult {
        val execution = runtime.getStreams(
            pluginId = scraper.scraperId,
            code = scraper.code,
            input = input,
        )
        // Same dedupe treatment as the addon parser: rows that repeat the same
        // url/infoHash/name/title collapse into one, keyed by stableKey.
        val deduped = LinkedHashMap<String, AddonStream>()
        execution.streams.forEach { stream ->
            val addon = stream.toAddonStream(scraper)
            deduped.putIfAbsent(addon.stableKey, addon)
        }
        return ProviderStreamsResult(
            providerId = providerId(scraper),
            providerName = scraper.displayName,
            streams = deduped.values.toList(),
        )
    }

    private fun buildInput(
        mediaType: MetadataLabMediaType,
        lookupId: String,
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
    ): PluginStreamInput {
        // Title/year stay app-side: the plugin contract exposes only the lookup ids
        // (tmdb/imdb, whichever the lookup carried), mediaType, season and episode.
        val parsed = parseLookupComponents(lookupId)
        return PluginStreamInput(
            tmdbId = resolvePluginTmdbId(tmdbId, lookupId),
            imdbId = parsed.imdbId.orEmpty(),
            mediaType = when (mediaType) {
                MetadataLabMediaType.MOVIE -> "movie"
                MetadataLabMediaType.SERIES, MetadataLabMediaType.ANIME -> "tv"
            },
            season = season ?: parsed.season,
            episode = episode ?: parsed.episode,
        )
    }

    private companion object {
        const val MAX_CONCURRENT_SCRAPERS = 4
        const val LOG_TAG = "CrispyPlugins"
    }
}

internal fun providerId(scraper: PluginScraperDescriptor): String = "$PLUGIN_PROVIDER_PREFIX${scraper.scraperId}"

internal fun PluginScraperDescriptor.supports(mediaType: MetadataLabMediaType): Boolean {
    if (supportedTypes.isEmpty()) return true
    val canonical = when (mediaType) {
        MetadataLabMediaType.MOVIE -> "movie"
        MetadataLabMediaType.SERIES, MetadataLabMediaType.ANIME -> "tv"
    }
    return supportedTypes.any { normalizePluginMediaType(it) == canonical }
}
