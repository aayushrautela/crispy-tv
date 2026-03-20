package com.crispy.tv.metadata.tmdb

import com.crispy.tv.player.MetadataLabMediaType
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

private val IMDB_ID_REGEX = Regex("tt\\d{4,}", RegexOption.IGNORE_CASE)
private val TMDB_ID_REGEX = Regex("\\btmdb:(?:movie:|tv:|show:)?(\\d+)", RegexOption.IGNORE_CASE)

internal fun MetadataLabMediaType.pathSegment(): String {
    return when (this) {
        MetadataLabMediaType.MOVIE -> "movie"
        MetadataLabMediaType.SERIES -> "tv"
    }
}

internal fun MetadataLabMediaType.toCatalogType(): String {
    return when (this) {
        MetadataLabMediaType.MOVIE -> "movie"
        MetadataLabMediaType.SERIES -> "series"
    }
}

internal fun Locale.toTmdbLanguageTag(): String {
    val lang = language.trim().ifBlank { "en" }
    val countryCode = country.trim().takeIf { it.isNotBlank() }
    return if (countryCode != null) {
        "$lang-${countryCode.uppercase(Locale.US)}"
    } else {
        lang
    }
}

internal fun extractImdbId(input: String?): String? {
    val raw = input?.trim().orEmpty()
    if (raw.isBlank()) return null
    if (raw.startsWith("tt", ignoreCase = true)) {
        return raw.lowercase(Locale.US).takeIf { it.length >= 4 }
    }
    val match = IMDB_ID_REGEX.find(raw) ?: return null
    return match.value.lowercase(Locale.US)
}

internal fun extractTmdbId(input: String?): Int? {
    val raw = input?.trim().orEmpty()
    if (raw.isBlank()) return null
    val match = TMDB_ID_REGEX.find(raw) ?: return null
    return match.groupValues.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 }
}

internal fun JSONArray.toJsonObjectList(): List<JSONObject> {
    if (length() == 0) return emptyList()
    val out = ArrayList<JSONObject>(length())
    for (index in 0 until length()) {
        optJSONObject(index)?.let(out::add)
    }
    return out
}

internal fun JSONObject.optStringNonBlank(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val value = optString(key)
    val trimmed = value.trim()
    return trimmed.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
}

internal fun JSONObject.optDoubleOrNull(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    val value = opt(key) ?: return null
    val number =
        when (value) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
            else -> null
    }
    return number?.takeIf { it.isFinite() }
}

internal fun JSONObject.optIntOrNull(key: String): Int? {
    if (!has(key) || isNull(key)) return null
    return optInt(key).takeIf { it > 0 }
}

internal fun JSONObject.optLongOrNull(key: String): Long? {
    if (!has(key) || isNull(key)) return null
    return optLong(key).takeIf { it > 0L }
}
