package com.crispy.tv.plugins.streams

import com.crispy.tv.addons.streams.ProviderStreamsResult
import com.crispy.tv.addons.streams.StreamProviderDescriptor
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.plugins.PluginStreamInput
import com.crispy.tv.plugins.normalizePluginMediaType
import com.crispy.tv.plugins.repo.PluginRepositoryManager
import com.crispy.tv.plugins.repo.PluginScraperDescriptor
import com.crispy.tv.plugins.runtime.PluginRuntime
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal const val PLUGIN_PROVIDER_PREFIX = "plugin:"

/**
 * Public entry point the app consumes. Signature uses only public types so the
 * internal [PluginStreamsService] plumbing stays encapsulated.
 */
fun interface PluginStreamSource {
    suspend fun load(
        mediaType: MetadataLabMediaType,
        lookupId: String,
        title: String,
        year: Int?,
        season: Int?,
        episode: Int?,
        onProvidersResolved: ((List<StreamProviderDescriptor>) -> Unit)?,
        onProviderResult: ((ProviderStreamsResult) -> Unit)?,
    ): List<ProviderStreamsResult>
}

internal class PluginStreamsService(
    private val repositoryManager: PluginRepositoryManager,
    private val runtimeProvider: () -> PluginRuntime?,
    private val refreshReposOnLoad: Boolean = true,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
) {

    suspend fun load(
        mediaType: MetadataLabMediaType,
        lookupId: String,
        title: String,
        year: Int?,
        season: Int?,
        episode: Int?,
        onProvidersResolved: ((List<StreamProviderDescriptor>) -> Unit)?,
        onProviderResult: ((ProviderStreamsResult) -> Unit)?,
    ): List<ProviderStreamsResult> {
        return coroutineScope {
            // Refresh runs alongside stream resolution instead of blocking it: manifest
            // fetches must never gate playback lookup. The scraper snapshot below may
            // miss repos that finish refreshing mid-load; they appear on the next load.
            val refreshJob = launch {
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
                "load mediaType=$mediaType lookupId=$lookupId season=$season episode=$episode: " +
                    "${enabled.size} enabled, ${scrapers.size} match (${scrapers.joinToString { it.scraperId }})",
            )
            onProvidersResolved?.invoke(scrapers.map { it.toDescriptor() })
            if (scrapers.isEmpty()) {
                refreshJob.join()
                android.util.Log.w(LOG_TAG, "load aborted: no matching scrapers")
                return@coroutineScope emptyList()
            }

            val runtime = runtimeProvider()
            if (runtime == null) {
                refreshJob.join()
                android.util.Log.w(LOG_TAG, "load aborted: runtime unavailable")
                return@coroutineScope emptyList()
            }
            val input = buildInput(mediaType, lookupId, season, episode)
            android.util.Log.i(
                LOG_TAG,
                "plugin input tmdbId='${input.tmdbId}' mediaType='${input.mediaType}' season=${input.season} episode=${input.episode}",
            )

            val semaphore = Semaphore(MAX_CONCURRENT_SCRAPERS)
            val channel = Channel<ProviderStreamsResult>(capacity = scrapers.size)
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
                    channel.send(result)
                }
            }

            val results = ArrayList<ProviderStreamsResult>(scrapers.size)
            repeat(scrapers.size) {
                val result = channel.receive()
                android.util.Log.i(
                    LOG_TAG,
                    "provider ${result.providerId} -> ${result.streams.size} stream(s)" +
                        (result.errorMessage?.let { " error=$it" } ?: ""),
                )
                results += result
                onProviderResult?.invoke(result)
            }
            android.util.Log.i(LOG_TAG, "load done: ${results.sumOf { it.streams.size }} stream(s) from ${results.size} provider(s)")
            channel.close()
            refreshJob.join()
            results
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
        return ProviderStreamsResult(
            providerId = providerId(scraper),
            providerName = scraper.displayName,
            streams = execution.streams.map { stream -> stream.toAddonStream(scraper) },
        )
    }

    private fun buildInput(
        mediaType: MetadataLabMediaType,
        lookupId: String,
        season: Int?,
        episode: Int?,
    ): PluginStreamInput {
        // Title/year stay app-side: the plugin contract exposes only
        // tmdbId/mediaType/season/episode, like Nuvio.
        val parsed = parseLookupComponents(lookupId)
        return PluginStreamInput(
            tmdbId = parsed.tmdbId?.toString().orEmpty(),
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

internal fun PluginScraperDescriptor.toDescriptor(): StreamProviderDescriptor =
    StreamProviderDescriptor(
        providerId = providerId(this),
        providerName = displayName,
    )
