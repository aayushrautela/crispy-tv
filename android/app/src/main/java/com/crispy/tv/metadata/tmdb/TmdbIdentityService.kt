package com.crispy.tv.metadata.tmdb

import com.crispy.tv.player.MetadataLabMediaType
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

internal data class TmdbResolvedId(
    val tmdbId: Int,
    val mediaType: MetadataLabMediaType,
)

internal class TmdbIdentityService(
    private val client: TmdbJsonClient,
) {
    private val imdbIdCache = ConcurrentHashMap<String, String>()
    private val tmdbByImdbCache = ConcurrentHashMap<String, TmdbResolvedId>()
    private val tmdbMissCache = ConcurrentHashMap<String, Boolean>()

    suspend fun resolveImdbId(contentId: String, mediaType: MetadataLabMediaType): String? {
        extractImdbId(contentId)?.let { return it }

        val tmdbId = extractTmdbId(contentId) ?: return null
        val cacheKey = "${mediaType.pathSegment()}:$tmdbId"
        val cached = imdbIdCache[cacheKey]
        if (cached != null) {
            return cached.takeUnless { it == NO_VALUE }
        }

        val imdbId =
            when (mediaType) {
                MetadataLabMediaType.MOVIE -> {
                    client.getJson(path = "movie/$tmdbId")?.optStringNonBlank("imdb_id")
                }
                MetadataLabMediaType.SERIES -> {
                    client.getJson(path = "tv/$tmdbId/external_ids")?.optStringNonBlank("imdb_id")
                }
                MetadataLabMediaType.ANIME -> {
                    client.getJson(path = "tv/$tmdbId/external_ids")?.optStringNonBlank("imdb_id")
                }
            }?.let(::extractImdbId)

        imdbIdCache[cacheKey] = imdbId ?: NO_VALUE
        return imdbId
    }

    suspend fun resolveTmdb(
        contentId: String,
        preferredMediaType: MetadataLabMediaType? = null,
        language: String? = null,
    ): TmdbResolvedId? {
        val tmdbId = extractTmdbId(contentId)
        if (tmdbId != null) {
            return resolveTmdbId(tmdbId = tmdbId, preferredMediaType = preferredMediaType, language = language)
        }

        val imdbId = extractImdbId(contentId) ?: return null
        return resolveTmdbFromImdb(
            imdbId = imdbId,
            preferredMediaType = preferredMediaType,
            language = language,
        )
    }

    suspend fun resolveTmdbFromImdb(
        imdbId: String,
        preferredMediaType: MetadataLabMediaType? = null,
        language: String? = null,
    ): TmdbResolvedId? {
        val normalizedImdbId = extractImdbId(imdbId) ?: return null
        val cacheKey = buildImdbCacheKey(normalizedImdbId, preferredMediaType)

        tmdbByImdbCache[cacheKey]?.let { return it }
        if (tmdbMissCache.containsKey(cacheKey)) {
            return null
        }

        val query = buildMap {
            put("external_source", "imdb_id")
            val trimmedLanguage = language?.trim().orEmpty()
            if (trimmedLanguage.isNotBlank()) {
                put("language", trimmedLanguage)
            }
        }
        val find = client.getJson(path = "find/$normalizedImdbId", query = query)
        if (find == null) {
            tmdbMissCache[cacheKey] = true
            return null
        }

        fun firstId(arrayName: String): Int? {
            val arr = find.optJSONArray(arrayName) ?: return null
            return arr.optJSONObject(0)?.optInt("id")?.takeIf { it > 0 }
        }

        val movieId = firstId("movie_results")
        val tvId = firstId("tv_results")
        val resolved =
            when (preferredMediaType) {
                MetadataLabMediaType.MOVIE -> {
                    movieId?.let { TmdbResolvedId(it, MetadataLabMediaType.MOVIE) }
                        ?: tvId?.let { TmdbResolvedId(it, MetadataLabMediaType.SERIES) }
                }
                MetadataLabMediaType.SERIES -> {
                    tvId?.let { TmdbResolvedId(it, MetadataLabMediaType.SERIES) }
                        ?: movieId?.let { TmdbResolvedId(it, MetadataLabMediaType.MOVIE) }
                }
                MetadataLabMediaType.ANIME -> {
                    tvId?.let { TmdbResolvedId(it, MetadataLabMediaType.ANIME) }
                        ?: movieId?.let { TmdbResolvedId(it, MetadataLabMediaType.MOVIE) }
                }
                null -> {
                    when {
                        movieId != null && tvId == null -> TmdbResolvedId(movieId, MetadataLabMediaType.MOVIE)
                        tvId != null && movieId == null -> TmdbResolvedId(tvId, MetadataLabMediaType.SERIES)
                        else -> null
                    }
                }
            }

        if (resolved == null) {
            tmdbMissCache[cacheKey] = true
            return null
        }

        tmdbByImdbCache[cacheKey] = resolved
        imdbIdCache["${resolved.mediaType.pathSegment()}:${resolved.tmdbId}"] = normalizedImdbId
        return resolved
    }

    private suspend fun resolveTmdbId(
        tmdbId: Int,
        preferredMediaType: MetadataLabMediaType?,
        language: String?,
    ): TmdbResolvedId? {
        if (preferredMediaType != null) {
            if (exists(preferredMediaType, tmdbId, language)) {
                return TmdbResolvedId(tmdbId, preferredMediaType)
            }

            val otherMediaType =
                if (preferredMediaType == MetadataLabMediaType.MOVIE) {
                    MetadataLabMediaType.SERIES
                } else {
                    MetadataLabMediaType.MOVIE
                }
            if (exists(otherMediaType, tmdbId, language)) {
                return TmdbResolvedId(tmdbId, otherMediaType)
            }
            return null
        }

        return coroutineScope {
            val movieDeferred = async { exists(MetadataLabMediaType.MOVIE, tmdbId, language) }
            val seriesDeferred = async { exists(MetadataLabMediaType.SERIES, tmdbId, language) }
            val movieExists = movieDeferred.await()
            val seriesExists = seriesDeferred.await()
            when {
                movieExists && !seriesExists -> TmdbResolvedId(tmdbId, MetadataLabMediaType.MOVIE)
                seriesExists && !movieExists -> TmdbResolvedId(tmdbId, MetadataLabMediaType.SERIES)
                else -> null
            }
        }
    }

    private suspend fun exists(
        mediaType: MetadataLabMediaType,
        tmdbId: Int,
        language: String?,
    ): Boolean {
        if (tmdbId <= 0) return false
        val query = buildMap<String, String> {
            val trimmedLanguage = language?.trim().orEmpty()
            if (trimmedLanguage.isNotBlank()) {
                put("language", trimmedLanguage)
            }
        }
        return client.getJson(path = "${mediaType.pathSegment()}/$tmdbId", query = query) != null
    }

    private fun buildImdbCacheKey(imdbId: String, preferredMediaType: MetadataLabMediaType?): String {
        return preferredMediaType?.let { "$imdbId:${it.pathSegment()}" } ?: imdbId
    }

    private companion object {
        private const val NO_VALUE = "__none__"
    }
}
