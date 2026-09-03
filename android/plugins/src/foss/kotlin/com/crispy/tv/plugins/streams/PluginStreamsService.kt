package com.crispy.tv.plugins.streams

import com.crispy.tv.addons.streams.ProviderStreamsResult
import com.crispy.tv.addons.streams.StreamProviderDescriptor
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.plugins.PluginStreamInput
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
        if (refreshReposOnLoad) {
            runCatching { repositoryManager.refreshDueRepositories(nowEpochMs()) }
        }
        val scrapers = repositoryManager.getEnabledScrapers()
            .filter { it.supports(mediaType) }
            .sortedBy { it.scraperId }
        onProvidersResolved?.invoke(scrapers.map { it.toDescriptor() })
        if (scrapers.isEmpty()) return emptyList()

        val runtime = runtimeProvider() ?: return emptyList()
        val input = buildInput(mediaType, lookupId, title, year, season, episode)

        return coroutineScope {
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
                results += result
                onProviderResult?.invoke(result)
            }
            channel.close()
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
        title: String,
        year: Int?,
        season: Int?,
        episode: Int?,
    ): PluginStreamInput {
        val parsed = parseLookupComponents(lookupId)
        return PluginStreamInput(
            tmdbId = parsed.tmdbId ?: 0,
            imdbId = parsed.imdbId,
            mediaType = when (mediaType) {
                MetadataLabMediaType.MOVIE -> "movie"
                MetadataLabMediaType.SERIES, MetadataLabMediaType.ANIME -> "series"
            },
            season = season ?: parsed.season,
            episode = episode ?: parsed.episode,
            title = title,
            year = year,
        )
    }

    private companion object {
        const val MAX_CONCURRENT_SCRAPERS = 4
    }
}

internal fun providerId(scraper: PluginScraperDescriptor): String = "$PLUGIN_PROVIDER_PREFIX${scraper.scraperId}"

internal fun PluginScraperDescriptor.supports(mediaType: MetadataLabMediaType): Boolean {
    if (supportedTypes.isEmpty()) return true
    val canonical = when (mediaType) {
        MetadataLabMediaType.MOVIE -> "movie"
        MetadataLabMediaType.SERIES, MetadataLabMediaType.ANIME -> "series"
    }
    return supportedTypes.any { it.equals(canonical, ignoreCase = true) }
}

internal fun PluginScraperDescriptor.toDescriptor(): StreamProviderDescriptor =
    StreamProviderDescriptor(
        providerId = providerId(this),
        providerName = displayName,
    )
