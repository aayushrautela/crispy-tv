package com.crispy.tv.plugins.streams

import android.content.Context
import com.crispy.tv.addons.streams.AddonStream
import com.crispy.tv.addons.streams.StreamBehaviorHints
import com.crispy.tv.addons.streams.StreamSubtitle
import com.crispy.tv.plugins.PluginStream
import com.crispy.tv.plugins.repo.PluginCodeStore
import com.crispy.tv.plugins.repo.PluginManifestClient
import com.crispy.tv.plugins.repo.PluginRepositoryManager
import com.crispy.tv.plugins.repo.PluginRepositoryStore
import com.crispy.tv.plugins.repo.PluginScraperDescriptor
import com.crispy.tv.plugins.runtime.HostPluginBridges
import com.crispy.tv.plugins.runtime.PluginRuntimeProvider
import com.crispy.tv.plugins.runtime.SharedPreferencesPluginStorage
import okhttp3.OkHttpClient
import java.util.Locale

internal data class ParsedPluginLookupId(
    val tmdbId: Int?,
    val imdbId: String?,
    val season: Int?,
    val episode: Int?,
)

internal fun parseLookupComponents(rawLookupId: String): ParsedPluginLookupId {
    val trimmed = rawLookupId.trim()
    if (trimmed.isEmpty()) return ParsedPluginLookupId(tmdbId = null, imdbId = null, season = null, episode = null)

    val parts = trimmed.split(":")
    var baseId = trimmed
    var season: Int? = null
    var episode: Int? = null
    if (parts.size >= 3) {
        val candidateSeason = parts[parts.lastIndex - 1].toIntOrNull()
        val candidateEpisode = parts.last().toIntOrNull()
        if (candidateSeason != null && candidateSeason > 0 && candidateEpisode != null && candidateEpisode > 0) {
            baseId = parts.dropLast(2).joinToString(":")
            season = candidateSeason
            episode = candidateEpisode
        }
    }
    val normalized = baseId.trim()
    val imdbId = normalized.takeIf { it.startsWith("tt", ignoreCase = true) }
    val tmdbId = normalized.toIntOrNull()?.takeIf { it > 0 }
    return ParsedPluginLookupId(tmdbId = tmdbId, imdbId = imdbId, season = season, episode = episode)
}

internal fun PluginStream.toAddonStream(scraper: PluginScraperDescriptor): AddonStream {
    val normalizedUrl = url.trim()
    val headers = buildMap {
        putAll(this@toAddonStream.headers)
        referer?.trim()?.takeIf { it.isNotEmpty() }?.let { referer -> putIfAbsent("Referer", referer) }
    }
    val providerKey = providerId(scraper)
    return AddonStream(
        providerId = providerKey,
        providerName = scraper.displayName,
        name = name,
        title = name,
        description = listOfNotNull(quality, sizeBytes?.toSizeLabel(), audio)
            .joinToString(separator = " • ")
            .takeIf { it.isNotBlank() },
        url = normalizedUrl,
        requestHeaders = headers,
        stableKey = "$providerKey-${(normalizedUrl + headers.entries.joinToString("|") { "${it.key}=${it.value}" })
            .hashCode().toUInt().toString(16)}",
        subtitles = subtitles.map { StreamSubtitle(url = it.url, lang = it.lang, name = it.lang) },
        behaviorHints = StreamBehaviorHints(filename = filename),
    )
}

private fun Long.toSizeLabel(): String {
    val gib = this / (1024.0 * 1024.0 * 1024.0)
    if (gib >= 1.0) return String.format(Locale.US, "%.1f GB", gib)
    val mib = this / (1024.0 * 1024.0)
    return String.format(Locale.US, "%.0f MB", mib)
}

object PluginStreamsServiceFactory {
    fun create(appContext: Context, okHttpClient: OkHttpClient): PluginStreamSource {
        val storage = SharedPreferencesPluginStorage(appContext)
        val bridges = HostPluginBridges(okHttpClient, storage)
        val repositoryManager = PluginRepositoryManager(
            manifestClient = PluginManifestClient(okHttpClient),
            codeStore = PluginCodeStore(appContext.filesDir),
            store = PluginRepositoryStore(appContext.filesDir),
        )
        val service = PluginStreamsService(
            repositoryManager = repositoryManager,
            runtimeProvider = { PluginRuntimeProvider.create(bridges) },
        )
        return PluginStreamSource { mediaType, lookupId, title, year, season, episode, onProvidersResolved, onProviderResult ->
            service.load(
                mediaType = mediaType,
                lookupId = lookupId,
                title = title,
                year = year,
                season = season,
                episode = episode,
                onProvidersResolved = onProvidersResolved,
                onProviderResult = onProviderResult,
            )
        }
    }
}
