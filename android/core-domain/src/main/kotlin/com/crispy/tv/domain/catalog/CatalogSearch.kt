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

private fun sourceRank(addonId: String, preferredAddonId: String?): Int {
    if (preferredAddonId != null && addonId.equals(preferredAddonId, ignoreCase = true)) {
        return 0
    }
    if (addonId.contains("cinemeta", ignoreCase = true)) {
        return 1
    }
    return 2
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
