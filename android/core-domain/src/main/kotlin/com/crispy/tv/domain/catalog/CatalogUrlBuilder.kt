package com.crispy.tv.domain.catalog

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

fun buildCatalogUrls(
    baseUrl: String,
    encodedQuery: String? = null,
    mediaType: String,
    catalogId: String,
    skip: Int = 0,
    limit: Int = 20,
    filters: List<CatalogFilter> = emptyList(),
): List<String> {
    val normalizedBaseUrl = baseUrl.trim().trimEnd('/')
    val normalizedMediaType = mediaType.trim().lowercase(Locale.US)
    val normalizedCatalogId = catalogId.trim()
    val normalizedSkip = skip.coerceAtLeast(0)
    val normalizedLimit = limit.coerceAtLeast(1)
    val normalizedQuery = encodedQuery.normalizedEncodedQuery()
    val normalizedFilters = normalizeCatalogFilters(filters)

    val pathRoot = buildString {
        append(normalizedBaseUrl)
        append("/catalog/")
        append(encodePathSegment(normalizedMediaType))
        append('/')
        append(encodePathSegment(normalizedCatalogId))
    }

    val urls = mutableListOf<String>()
    if (normalizedSkip == 0 && normalizedFilters.isEmpty()) {
        urls += "$pathRoot.json${querySuffix(normalizedQuery)}"
    }

    val pathExtras = buildList {
        add("skip=${encodePathSegment(normalizedSkip.toString())}")
        add("limit=${encodePathSegment(normalizedLimit.toString())}")
        normalizedFilters.forEach { filter ->
            add("${encodePathSegment(filter.key)}=${encodePathSegment(filter.value)}")
        }
    }
    urls += "$pathRoot/${pathExtras.joinToString("/")}.json${querySuffix(normalizedQuery)}"

    val queryParts = mutableListOf<String>()
    normalizedQuery?.let(queryParts::add)
    queryParts += "skip=${encodeQueryComponent(normalizedSkip.toString())}"
    queryParts += "limit=${encodeQueryComponent(normalizedLimit.toString())}"
    normalizedFilters.forEach { filter ->
        queryParts += "${encodeQueryComponent(filter.key)}=${encodeQueryComponent(filter.value)}"
    }
    urls += "$pathRoot.json?${queryParts.joinToString("&")}"

    return urls
}

private fun normalizeCatalogFilters(filters: List<CatalogFilter>): List<CatalogFilter> {
    return filters
        .mapNotNull { filter ->
            val key = filter.key.trim()
            val value = filter.value.trim()
            if (key.isEmpty() || value.isEmpty()) {
                null
            } else {
                CatalogFilter(key = key, value = value)
            }
        }
        .sortedWith(compareBy<CatalogFilter>({ it.key.lowercase(Locale.US) }, { it.value.lowercase(Locale.US) }))
}

private fun String?.normalizedEncodedQuery(): String? {
    val normalized = this?.trim().orEmpty().removePrefix("?")
    return normalized.ifEmpty { null }
}

private fun querySuffix(encodedQuery: String?): String {
    return encodedQuery?.let { "?$it" } ?: ""
}

private fun encodePathSegment(value: String): String {
    return encodeQueryComponent(value)
}

private fun encodeQueryComponent(value: String): String {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
}
