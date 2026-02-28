package com.crispy.tv.domain.catalog

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class CatalogFilter(
    val key: String,
    val value: String
)

data class CatalogRequestInput(
    val baseUrl: String,
    val mediaType: String,
    val catalogId: String,
    val page: Int,
    val pageSize: Int,
    val filters: List<CatalogFilter> = emptyList(),
    val encodedAddonQuery: String? = null
)

data class SearchMetaInput(
    val id: String,
    val title: String
)

data class AddonSearchResult(
    val addonId: String,
    val metas: List<SearchMetaInput>
)

data class RankedSearchMeta(
    val id: String,
    val title: String,
    val addonId: String
)

data class TmdbSearchResultInput(
    val mediaType: String,
    val id: Int,
    val title: String? = null,
    val name: String? = null,
    val releaseDate: String? = null,
    val firstAirDate: String? = null,
    val posterPath: String? = null,
    val profilePath: String? = null,
    val voteAverage: Double? = null
)

data class NormalizedSearchItem(
    val id: String,
    val type: String,
    val title: String,
    val year: Int?,
    val imageUrl: String?,
    val rating: Double?
)

private const val TMDB_IMAGE_BASE_URL = "https://image.tmdb.org/t/p/"

fun buildCatalogRequestUrls(input: CatalogRequestInput): List<String> {
    val baseUrl = input.baseUrl.trim().trimEnd('/')
    require(baseUrl.isNotEmpty()) { "baseUrl must not be blank" }

    val mediaType = input.mediaType.trim()
    require(mediaType.isNotEmpty()) { "mediaType must not be blank" }

    val catalogId = input.catalogId.trim()
    require(catalogId.isNotEmpty()) { "catalogId must not be blank" }

    val page = input.page.coerceAtLeast(1)
    val pageSize = input.pageSize.coerceAtLeast(1)
    val skip = (page - 1) * pageSize

    val normalizedAddonQueryParts = parseQueryParts(input.encodedAddonQuery)
    val normalizedFilters =
        input.filters.mapNotNull { filter ->
            val key = filter.key.trim()
            val value = filter.value.trim()
            if (key.isEmpty() || value.isEmpty()) {
                null
            } else {
                encodeQueryComponent(key) to encodeQueryComponent(value)
            }
        }

    val extraPathPairs =
        listOf("skip" to skip.toString(), "limit" to pageSize.toString()) + normalizedFilters
    val queryPairs =
        listOf("skip" to skip.toString(), "limit" to pageSize.toString()) +
            normalizedAddonQueryParts +
            normalizedFilters

    val encodedType = encodePathSegment(mediaType)
    val encodedCatalogId = encodePathSegment(catalogId)
    val simpleBase = "$baseUrl/catalog/$encodedType/$encodedCatalogId.json"
    val simpleUrl = appendQuery(simpleBase, normalizedAddonQueryParts)
    val pathStyleUrl =
        appendQuery(
            "$baseUrl/catalog/$encodedType/$encodedCatalogId/${joinPairs(extraPathPairs)}.json",
            normalizedAddonQueryParts
        )
    val legacyQueryUrl = appendQuery(simpleBase, queryPairs)

    val urls = mutableListOf<String>()
    if (page == 1 && normalizedFilters.isEmpty()) {
        urls += simpleUrl
    }
    urls += pathStyleUrl
    urls += legacyQueryUrl
    return urls.distinct()
}

fun mergeSearchResults(
    addonResults: List<AddonSearchResult>,
    preferredAddonId: String? = null
): List<RankedSearchMeta> {
    val preferred = preferredAddonId?.trim()?.takeIf { it.isNotEmpty() }
    val rankedAddons =
        addonResults
            .withIndex()
            .sortedWith(
                compareBy<IndexedValue<AddonSearchResult>>(
                    { sourceRank(it.value.addonId, preferred) },
                    { it.index },
                    { it.value.addonId.lowercase() }
                )
            )
            .map { indexed -> indexed.value }

    val seenIds = linkedSetOf<String>()
    val merged = mutableListOf<RankedSearchMeta>()

    rankedAddons.forEach { addon ->
        addon.metas.forEach { meta ->
            val id = meta.id.trim()
            if (id.isEmpty() || !seenIds.add(id)) {
                return@forEach
            }
            val title = meta.title.trim().ifEmpty { id }
            merged += RankedSearchMeta(id = id, title = title, addonId = addon.addonId)
        }
    }

    return merged
}

fun normalizeTmdbSearchResults(results: List<TmdbSearchResultInput>): List<NormalizedSearchItem> {
    val seenKeys = linkedSetOf<String>()
    val normalized = mutableListOf<NormalizedSearchItem>()

    results.forEach { result ->
        val rawMediaType = result.mediaType.trim().lowercase()
        val type =
            when (rawMediaType) {
                "movie" -> "movie"
                "tv" -> "series"
                "person" -> "person"
                else -> return@forEach
            }

        val tmdbId = result.id
        if (tmdbId <= 0) {
            return@forEach
        }

        val id = "tmdb:$tmdbId"
        val key = "$type:$id"
        if (!seenKeys.add(key)) {
            return@forEach
        }

        val primaryTitle = if (rawMediaType == "movie") result.title else result.name
        val fallbackTitle = if (rawMediaType == "movie") result.name else result.title
        val title = primaryTitle?.trim().orEmpty().ifBlank { fallbackTitle?.trim().orEmpty() }
        if (title.isBlank()) {
            return@forEach
        }

        val year =
            when (rawMediaType) {
                "movie" -> parseYear(result.releaseDate)
                "tv" -> parseYear(result.firstAirDate)
                else -> null
            }

        val imagePath =
            when (rawMediaType) {
                "person" -> result.profilePath
                else -> result.posterPath
            }
        val imageSize = if (rawMediaType == "person") "h632" else "w500"
        val imageUrl = tmdbImageUrl(imagePath, imageSize)

        val rating = result.voteAverage?.takeIf { it.isFinite() }

        normalized +=
            NormalizedSearchItem(
                id = id,
                type = type,
                title = title,
                year = year,
                imageUrl = imageUrl,
                rating = rating
            )
    }

    return normalized
}

private fun sourceRank(addonId: String, preferredAddonId: String?): Int {
    if (preferredAddonId != null && addonId.equals(preferredAddonId, ignoreCase = true)) {
        return 0
    }
    return 1
}

private fun parseYear(value: String?): Int? {
    val raw = value?.trim().orEmpty()
    if (raw.length < 4) {
        return null
    }
    val year = raw.take(4).toIntOrNull() ?: return null
    return year.takeIf { it in 1800..3000 }
}

private fun tmdbImageUrl(path: String?, size: String): String? {
    val raw = path?.trim().orEmpty()
    if (raw.isEmpty()) {
        return null
    }
    val normalizedPath = if (raw.startsWith('/')) raw else "/$raw"
    return "$TMDB_IMAGE_BASE_URL$size$normalizedPath"
}

private fun parseQueryParts(raw: String?): List<Pair<String, String>> {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty()) {
        return emptyList()
    }

    return value.split('&').mapNotNull { pair ->
        val key = pair.substringBefore('=').trim()
        val rawValue = pair.substringAfter('=', missingDelimiterValue = "").trim()
        if (key.isEmpty()) {
            null
        } else {
            encodeQueryComponent(key) to encodeQueryComponent(rawValue)
        }
    }
}

private fun joinPairs(pairs: List<Pair<String, String>>): String {
    return pairs.joinToString(separator = "&") { (key, value) -> "$key=$value" }
}

private fun appendQuery(base: String, pairs: List<Pair<String, String>>): String {
    if (pairs.isEmpty()) {
        return base
    }
    return "$base?${joinPairs(pairs)}"
}

private fun encodePathSegment(value: String): String {
    return encodeQueryComponent(value).replace("%2F", "/")
}

private fun encodeQueryComponent(value: String): String {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
}
