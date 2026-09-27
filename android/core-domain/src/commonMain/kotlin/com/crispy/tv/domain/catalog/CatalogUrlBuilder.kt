package com.crispy.tv.domain.catalog

data class CatalogFilter(
    val key: String,
    val value: String
)

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
    val normalizedMediaType = mediaType.trim().lowercase()
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
        .sortedWith(compareBy<CatalogFilter>({ it.key.lowercase() }, { it.value.lowercase() }))
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

/**
 * Percent-encodes a value for use in a URL path segment or query component.
 *
 * This reproduces `application/x-www-form-urlencoded` encoding as the catalog
 * contract specifies it, which is *not* the same as RFC 3986: `*` stays
 * literal, `~` is escaped, and a space becomes `%20` rather than `+`. Kotlin's
 * `encodeURLParameter()` follows RFC 3986 (the opposite way round) and is still
 * experimental, so the encoding is spelled out here instead.
 *
 * Non-ASCII input is emitted as one `%XX` per UTF-8 byte, in uppercase hex.
 */
private fun encodeQueryComponent(value: String): String {
    return buildString {
        for (byte in value.encodeToByteArray()) {
            val code = byte.toInt() and 0xFF
            if (isLiteralUrlChar(code)) {
                append(code.toChar())
            } else {
                append('%')
                append(HEX_DIGITS[code shr 4])
                append(HEX_DIGITS[code and 0x0F])
            }
        }
    }
}

private fun isLiteralUrlChar(code: Int): Boolean =
    code in 'a'.code..'z'.code ||
        code in 'A'.code..'Z'.code ||
        code in '0'.code..'9'.code ||
        code == '.'.code ||
        code == '-'.code ||
        code == '*'.code ||
        code == '_'.code

private val HEX_DIGITS = "0123456789ABCDEF".toCharArray()
