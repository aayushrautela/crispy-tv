package com.crispy.tv.domain.media

import com.crispy.tv.domain.MediaKey

data class MediaStateNormalized(
    val cardFamily: String? = null,
    val mediaKey: MediaKey? = null,
    val mediaType: String? = null,
    val itemId: String? = null,
    val title: String? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val subtitle: String? = null,
    val progressPercent: Double? = null,
    val watchedAt: String? = null,
    val lastActivityAt: String? = null,
    val origins: List<String>? = null,
    val dismissible: Boolean? = null,
    val layout: String? = null,
    val routeKind: String? = null,
)

fun normalizeMediaStateCard(payload: Map<String, Any?>, kind: String): MediaStateNormalized? {
    return when (kind.trim().lowercase()) {
        "media_item", "search_result", "recommendation_item" -> normalizeBaseItemDto(payload)
        "continue_watching_item" -> normalizeContinueWatching(payload)
        "watched_item" -> normalizeWatchedItem(payload)
        "watchlist_item" -> normalizeWatchlistItem(payload)
        "rating_item" -> normalizeRatingItem(payload)
        "library_item" -> normalizeLibraryItem(payload)
        "home_snapshot_section" -> normalizeHomeSnapshotSection(payload)
        "title_route" -> normalizeTitleRoute(payload)
        else -> null
    }
}

private fun normalizeBaseItemDto(payload: Map<String, Any?>): MediaStateNormalized? {
    val mediaKeyStr = payload.stringValue("Id") ?: return null
    val mediaType = payload.stringValue("Type") ?: return null
    val title = payload.stringValue("Name") ?: return null
    val imageTags = payload.objectValue("ImageTags")
    return MediaStateNormalized(
        cardFamily = "media_item",
        mediaKey = MediaKey(mediaKeyStr),
        mediaType = mediaType,
        title = title,
        posterUrl = imageTags?.imageTagMedium("Primary"),
        backdropUrl = imageTags?.backdropMedium(),
        subtitle = payload.nullableStringValue("EpisodeTitle") ?: payload.nullableStringValue("Overview"),
    )
}

private fun userDataFrom(payload: Map<String, Any?>): Map<String, Any?>? {
    return payload.objectValue("UserData")
}

private fun normalizeContinueWatching(payload: Map<String, Any?>): MediaStateNormalized? {
    val normalized = normalizeBaseItemDto(payload) ?: return null
    val ud = userDataFrom(payload)
    return normalized.copy(
        itemId = normalized.mediaKey?.value,
        progressPercent = ud?.doubleValue("PlayedPercentage") ?: 0.0,
        lastActivityAt = ud?.stringValue("LastPlayedDate") ?: payload.stringValue("LastPlayedDate"),
        dismissible = ud?.booleanValue("DismissedFromContinueWatching") ?: false,
        origins = emptyList(),
    )
}

private fun normalizeWatchedItem(payload: Map<String, Any?>): MediaStateNormalized? {
    val normalized = normalizeBaseItemDto(payload) ?: return null
    val ud = userDataFrom(payload)
    return normalized.copy(
        itemId = normalized.mediaKey?.value,
        watchedAt = ud?.stringValue("LastPlayedDate"),
        origins = emptyList(),
    )
}

private fun normalizeWatchlistItem(payload: Map<String, Any?>): MediaStateNormalized? {
    val normalized = normalizeBaseItemDto(payload) ?: return null
    return normalized.copy(
        itemId = normalized.mediaKey?.value,
        origins = emptyList(),
    )
}

private fun normalizeRatingItem(payload: Map<String, Any?>): MediaStateNormalized? {
    val normalized = normalizeBaseItemDto(payload) ?: return null
    return normalized.copy(
        itemId = normalized.mediaKey?.value,
        origins = emptyList(),
    )
}

private fun normalizeLibraryItem(payload: Map<String, Any?>): MediaStateNormalized? {
    val normalized = normalizeBaseItemDto(payload) ?: return null
    return normalized.copy(
        itemId = normalized.mediaKey?.value,
        origins = emptyList(),
    )
}

private fun normalizeHomeSnapshotSection(payload: Map<String, Any?>): MediaStateNormalized? {
    val layout = payload.stringValue("layout") ?: return null
    if (layout !in setOf("regular", "landscape", "collection", "hero")) return null
    val items = payload["items"] as? List<*> ?: return null
    if (items.isEmpty()) return null
    return MediaStateNormalized(layout = layout)
}

private fun normalizeTitleRoute(payload: Map<String, Any?>): MediaStateNormalized? {
    val mediaKeyStr = payload.stringValue("mediaKey") ?: payload.stringValue("Id") ?: return null
    val path = payload.stringValue("path") ?: return null
    if (path != "/v1/metadata/titles/$mediaKeyStr") return null
    return MediaStateNormalized(mediaKey = MediaKey(mediaKeyStr), routeKind = "title")
}

private fun Map<String, Any?>.stringValue(key: String): String? {
    return this[key]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
}

private fun Map<String, Any?>.nullableStringValue(key: String): String? {
    if (!containsKey(key)) return null
    return this[key]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
}

private fun Map<String, Any?>.objectValue(key: String): Map<String, Any?>? {
    @Suppress("UNCHECKED_CAST")
    return this[key] as? Map<String, Any?>
}

private fun Map<String, Any?>.imageTagMedium(key: String): String? {
    val tag = this[key]
    return when (tag) {
        is String -> tag.trim().ifBlank { null }
        is Map<*, *> -> tag.mapStringValue("medium")
        else -> null
    }
}

private fun Map<String, Any?>.backdropMedium(): String? {
    val backdrops = this["Backdrop"] as? List<*> ?: return null
    val first = backdrops.firstOrNull() ?: return null
    return when (first) {
        is String -> first.trim().ifBlank { null }
        is Map<*, *> -> first.mapStringValue("medium")
        else -> null
    }
}

private fun Map<*, *>.mapStringValue(key: String): String? {
    return this[key]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
}

private fun Map<String, Any?>.booleanValue(key: String): Boolean? {
    return when (val value = this[key]) {
        is Boolean -> value
        is String -> when (value.trim().lowercase()) {
            "true" -> true
            "false" -> false
            else -> null
        }
        else -> null
    }
}

private fun Map<String, Any?>.doubleValue(key: String): Double? {
    return when (val value = this[key]) {
        is Number -> value.toDouble()
        is String -> value.trim().toDoubleOrNull()
        else -> null
    }
}
