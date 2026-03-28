package com.crispy.tv.home

import android.content.Context
import androidx.compose.runtime.Immutable
import com.crispy.tv.metadata.tmdb.TmdbEnrichmentRepository
import com.crispy.tv.metadata.tmdb.TmdbServicesProvider
import com.crispy.tv.player.CanonicalContinueWatchingItem
import com.crispy.tv.player.CanonicalContinueWatchingResult
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.player.WatchHistoryEntry
import com.crispy.tv.player.WatchProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import java.util.Locale

class HomeWatchActivityService(
    context: Context,
    private val tmdbEnrichmentRepository: TmdbEnrichmentRepository =
        TmdbServicesProvider.enrichmentRepository(context.applicationContext),
) {
    private val continueWatchingMetaCache = mutableMapOf<String, CachedContinueWatchingMeta>()
    private val metaResolveSemaphore = Semaphore(6)

    suspend fun loadWatchActivity(
        selectedSource: WatchProvider,
        localEntries: List<WatchHistoryEntry>,
        providerResult: CanonicalContinueWatchingResult,
        limit: Int = 20,
        enrichMetadata: Boolean = true,
    ): CanonicalContinueWatchingResult {
        return when (selectedSource) {
            WatchProvider.LOCAL -> loadLocalContinueWatchingItems(localEntries, limit, enrichMetadata)
            WatchProvider.TRAKT, WatchProvider.SIMKL -> {
                if (providerResult.entries.isNotEmpty()) {
                    loadProviderContinueWatchingItems(providerResult.entries, limit, enrichMetadata).copy(
                        statusMessage = providerResult.statusMessage,
                        isError = providerResult.isError,
                    )
                } else if (providerResult.isError) {
                    CanonicalContinueWatchingResult(
                        statusMessage = providerResult.statusMessage,
                        isError = true,
                    )
                } else {
                    CanonicalContinueWatchingResult(statusMessage = "")
                }
            }
        }
    }

    private suspend fun loadLocalContinueWatchingItems(
        entries: List<WatchHistoryEntry>,
        limit: Int,
        enrichMetadata: Boolean,
    ): CanonicalContinueWatchingResult {
        val targetCount = limit.coerceAtLeast(1)
        val dedupedEntries = entries
            .sortedByDescending { it.watchedAtEpochMs }
            .distinctBy { continueWatchingKey(it) }
            .take(targetCount)

        if (dedupedEntries.isEmpty()) {
            return CanonicalContinueWatchingResult(statusMessage = "")
        }

        val items =
            if (!enrichMetadata) {
                dedupedEntries.map { entry ->
                    buildLocalContinueWatchingItem(entry = entry, resolvedMeta = null)
                }
            } else {
                coroutineScope {
                    dedupedEntries.map { entry ->
                        async(Dispatchers.IO) {
                            metaResolveSemaphore.acquire()
                            try {
                                buildLocalContinueWatchingItem(
                                    entry = entry,
                                    resolvedMeta = resolveContinueWatchingMeta(entry),
                                )
                            } finally {
                                metaResolveSemaphore.release()
                            }
                        }
                    }.awaitAll()
                }
            }

        return CanonicalContinueWatchingResult(
            statusMessage = "",
            entries = items,
        )
    }

    private suspend fun loadProviderContinueWatchingItems(
        entries: List<CanonicalContinueWatchingItem>,
        limit: Int,
        enrichMetadata: Boolean,
    ): CanonicalContinueWatchingResult {
        val targetCount = limit.coerceAtLeast(1)
        val projectedEntries = entries.take(targetCount)

        if (projectedEntries.isEmpty()) {
            return CanonicalContinueWatchingResult(statusMessage = "")
        }

        val items =
            if (!enrichMetadata) {
                projectedEntries.map { entry ->
                    buildProviderContinueWatchingItem(entry = entry, resolvedMeta = null)
                }
            } else {
                coroutineScope {
                    projectedEntries.map { entry ->
                        async(Dispatchers.IO) {
                            metaResolveSemaphore.acquire()
                            try {
                                buildProviderContinueWatchingItem(
                                    entry = entry,
                                    resolvedMeta = resolveProviderContinueWatchingMeta(entry),
                                )
                            } finally {
                                metaResolveSemaphore.release()
                            }
                        }
                    }.awaitAll()
                }
            }

        return CanonicalContinueWatchingResult(
            statusMessage = "",
            entries = items,
        )
    }

    private fun buildLocalContinueWatchingItem(
        entry: WatchHistoryEntry,
        resolvedMeta: ContinueWatchingMeta?,
    ): CanonicalContinueWatchingItem {
        val mediaType = entry.asCatalogMediaType()
        return CanonicalContinueWatchingItem(
            id = continueWatchingKey(entry),
            contentId = entry.contentId,
            title = resolvedMeta?.title ?: fallbackContinueWatchingTitle(entry),
            season = entry.season,
            episode = entry.episode,
            lastUpdatedEpochMs = entry.watchedAtEpochMs,
            progressPercent = 100.0,
            provider = WatchProvider.LOCAL,
            providerPlaybackId = null,
            isUpNextPlaceholder = false,
            backdropUrl = resolvedMeta?.backdropUrl,
            posterUrl = resolvedMeta?.posterUrl,
            logoUrl = resolvedMeta?.logoUrl,
            addonId = resolvedMeta?.addonId,
            contentType = if (mediaType == "series") MetadataLabMediaType.SERIES else MetadataLabMediaType.MOVIE,
        )
    }

    private fun buildProviderContinueWatchingItem(
        entry: CanonicalContinueWatchingItem,
        resolvedMeta: ContinueWatchingMeta?,
    ): CanonicalContinueWatchingItem {
        val displayMeta = resolvedMeta ?: ContinueWatchingMeta(
            title = null,
            backdropUrl = entry.backdropUrl,
            posterUrl = entry.posterUrl,
            logoUrl = entry.logoUrl,
            addonId = entry.addonId,
        )
        return CanonicalContinueWatchingItem(
            id = "${entry.provider.name.lowercase(Locale.US)}:${entry.contentType.name.lowercase(Locale.US)}:${entry.contentId}:${entry.season ?: -1}:${entry.episode ?: -1}",
            contentId = entry.contentId,
            title = displayMeta.title ?: entry.title,
            season = entry.season,
            episode = entry.episode,
            lastUpdatedEpochMs = entry.lastUpdatedEpochMs,
            progressPercent = entry.progressPercent,
            provider = entry.provider,
            providerPlaybackId = entry.providerPlaybackId,
            isUpNextPlaceholder = entry.isUpNextPlaceholder,
            backdropUrl = displayMeta.backdropUrl,
            posterUrl = displayMeta.posterUrl,
            logoUrl = displayMeta.logoUrl,
            addonId = displayMeta.addonId,
            contentType = entry.contentType,
        )
    }

    private suspend fun resolveProviderContinueWatchingMeta(
        entry: CanonicalContinueWatchingItem,
    ): ContinueWatchingMeta? {
        val existing = ContinueWatchingMeta(
            title = nonBlank(entry.title),
            backdropUrl = entry.backdropUrl,
            posterUrl = entry.posterUrl,
            logoUrl = entry.logoUrl,
            addonId = entry.addonId,
        )
        if (existing.hasDisplayData()) {
            return existing
        }

        val fakeWatchEntry = WatchHistoryEntry(
            contentId = entry.contentId,
            contentType = entry.contentType,
            title = entry.title,
            season = entry.season,
            episode = entry.episode,
            watchedAtEpochMs = entry.lastUpdatedEpochMs,
        )
        return resolveContinueWatchingMeta(fakeWatchEntry)
    }

    private suspend fun resolveContinueWatchingMeta(entry: WatchHistoryEntry): ContinueWatchingMeta? {
        val rawId = entry.contentId.trim()
        if (rawId.isBlank()) {
            return null
        }

        val episodeMatch = EPISODE_SUFFIX_REGEX.matchEntire(rawId)
        val baseId = episodeMatch?.groupValues?.get(1)?.trim().orEmpty().ifBlank { rawId }
        val mediaType = entry.asCatalogMediaType()
        val cacheKey = continueWatchingCacheKey(mediaType = mediaType, contentId = baseId)

        val cached = readCachedContinueWatchingMeta(cacheKey)
        if (cached != null) {
            return cached
        }

        val details =
            runCatching {
                tmdbEnrichmentRepository.loadArtwork(
                    rawId = baseId,
                    mediaTypeHint = entry.contentType,
                )
            }.getOrNull() ?: return null

        val resolved =
            ContinueWatchingMeta(
                title = nonBlank(details.title) ?: nonBlank(entry.title),
                backdropUrl = details.backdropUrl,
                posterUrl = details.posterUrl,
                logoUrl = details.logoUrl,
                addonId = details.addonId ?: "tmdb",
            )
        if (!resolved.hasDisplayData()) {
            return null
        }

        cacheContinueWatchingMeta(cacheKey, resolved)
        return resolved
    }

    private fun continueWatchingCacheKey(mediaType: String, contentId: String): String {
        return "${mediaType.lowercase(Locale.US)}:${contentId.lowercase(Locale.US)}"
    }

    private fun readCachedContinueWatchingMeta(cacheKey: String): ContinueWatchingMeta? {
        val cached = continueWatchingMetaCache[cacheKey] ?: return null
        val ageMs = System.currentTimeMillis() - cached.cachedAtEpochMs
        if (ageMs > CONTINUE_WATCHING_META_CACHE_TTL_MS) {
            continueWatchingMetaCache.remove(cacheKey)
            return null
        }
        return cached.meta
    }

    private fun cacheContinueWatchingMeta(cacheKey: String, value: ContinueWatchingMeta) {
        continueWatchingMetaCache[cacheKey] =
            CachedContinueWatchingMeta(
                meta = value,
                cachedAtEpochMs = System.currentTimeMillis(),
            )
    }

    private fun fallbackContinueWatchingTitle(entry: WatchHistoryEntry): String {
        val normalizedTitle = nonBlank(entry.title)
        if (normalizedTitle != null) {
            return normalizedTitle
        }
        if (entry.season != null && entry.episode != null) {
            return "${entry.contentId} S${entry.season} E${entry.episode}"
        }
        return entry.contentId
    }

    private fun continueWatchingKey(entry: WatchHistoryEntry): String {
        val seasonPart = entry.season?.toString() ?: "-"
        val episodePart = entry.episode?.toString() ?: "-"
        return "${entry.contentType.name.lowercase(Locale.US)}:${entry.contentId}:$seasonPart:$episodePart"
    }

    private data class ContinueWatchingMeta(
        val title: String?,
        val backdropUrl: String?,
        val posterUrl: String?,
        val logoUrl: String?,
        val addonId: String?,
    ) {
        fun hasDisplayData(): Boolean {
            return !title.isNullOrBlank() || !backdropUrl.isNullOrBlank() || !posterUrl.isNullOrBlank() || !logoUrl.isNullOrBlank()
        }
    }

    private data class CachedContinueWatchingMeta(
        val meta: ContinueWatchingMeta,
        val cachedAtEpochMs: Long,
    )

    companion object {
        private val EPISODE_SUFFIX_REGEX = Regex("^(.*):(\\d+):(\\d+)$")
        private const val CONTINUE_WATCHING_META_CACHE_TTL_MS = 5 * 60 * 1000L
    }
}

private fun WatchHistoryEntry.asCatalogMediaType(): String {
    return if (contentType == MetadataLabMediaType.SERIES) "series" else "movie"
}

private fun nonBlank(value: String?): String? {
    val trimmed = value?.trim()
    return if (trimmed.isNullOrEmpty()) null else trimmed
}
