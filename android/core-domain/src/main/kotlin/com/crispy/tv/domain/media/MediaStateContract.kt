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
        "media_item", "search_result", "recommendation_item" -> normalizeMediaItemWrapper(payload)
        "continue_watching_item" -> normalizeContinueWatching(payload)
        "watched_item" -> normalizeWatchItem(payload, stateKey = "watchedAt")
        "watchlist_item" -> normalizeWatchItem(payload, stateKey = "addedAt")
        "rating_item" -> normalizeWatchItem(payload, stateKey = "ratedAt")
        "library_item" -> normalizeLibraryItem(payload)
        "home_snapshot_section" -> normalizeHomeSnapshotSection(payload)
        "title_route" -> normalizeTitleRoute(payload)
        else -> null
    }
}

private fun normalizeMediaItemWrapper(payload: Map<String, Any?>): MediaStateNormalized? {
    val media = payload.objectValue("mediaItem") ?: payload.takeIf { it.containsKey("Id") } ?: return null
    return normalizeMediaItem(media)
}

private fun normalizeMediaItem(payload: Map<String, Any?>): MediaStateNormalized? {
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

private fun normalizeContinueWatching(payload: Map<String, Any?>): MediaStateNormalized? {
    val id = payload.stringValue("id") ?: return null
    val media = payload.objectValue("mediaItem") ?: return null
    val normalizedMedia = normalizeMediaItem(media) ?: return null
    if (!payload.containsKey("progress")) return null
    val lastActivityAt = payload.stringValue("lastActivityAt") ?: return null
    val origins = payload.stringList("origins") ?: return null
    val dismissible = payload.booleanValue("dismissible") ?: return null
    val progressPercent = payload.objectValue("progress")?.doubleValue("progressPercent") ?: 0.0
    return normalizedMedia.copy(
        itemId = id,
        progressPercent = progressPercent,
        lastActivityAt = lastActivityAt,
        origins = origins,
        dismissible = dismissible,
    )
}

private fun normalizeWatchItem(payload: Map<String, Any?>, stateKey: String): MediaStateNormalized? {
    val media = payload.objectValue("mediaItem") ?: return null
    val normalizedMedia = normalizeMediaItem(media) ?: return null
    val origins = payload.stringList("origins") ?: return null
    if (!payload.hasRequiredState(stateKey)) return null
    return normalizedMedia.copy(
        itemId = payload.stringValue("id"),
        watchedAt = payload.stringValue(stateKey).takeIf { stateKey == "watchedAt" },
        origins = origins,
    )
}

private fun Map<String, Any?>.hasRequiredState(stateKey: String): Boolean {
    return when (stateKey) {
        "ratedAt" -> objectValue("rating")?.stringValue(stateKey) != null
        else -> stringValue(stateKey) != null
    }
}

private fun normalizeLibraryItem(payload: Map<String, Any?>): MediaStateNormalized? {
    val itemId = payload.stringValue("id") ?: return null
    val media = payload.objectValue("mediaItem") ?: return null
    val normalizedMedia = normalizeMediaItem(media) ?: return null
    val origins = payload.stringList("origins") ?: return null
    if (!payload.containsKey("state")) return null
    return normalizedMedia.copy(itemId = itemId, origins = origins)
}

private fun normalizeHomeSnapshotSection(payload: Map<String, Any?>): MediaStateNormalized? {
    val layout = payload.stringValue("layout") ?: return null
    if (layout !in setOf("regular", "landscape", "collection", "hero")) return null
    val items = payload["items"] as? List<*> ?: return null
    if (items.isEmpty()) return null
    return MediaStateNormalized(layout = layout)
}

private fun normalizeTitleRoute(payload: Map<String, Any?>): MediaStateNormalized? {
    val mediaKeyStr = payload.stringValue("mediaKey") ?: return null
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
        is Map<*, *> -> tag.stringValue("medium")
        else -> null
    }
}

private fun Map<String, Any?>.backdropMedium(): String? {
    val backdrops = this["Backdrop"] as? List<*> ?: return null
    val first = backdrops.firstOrNull() ?: return null
    return when (first) {
        is String -> first.trim().ifBlank { null }
        is Map<*, *> -> first.stringValue("medium")
        else -> null
    }
}

private fun Map<*, *>.stringValue(key: String): String? {
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

private fun Map<String, Any?>.stringList(key: String): List<String>? {
    val values = this[key] as? List<*> ?: return null
    return values.mapNotNull { value -> value?.toString()?.trim()?.takeIf { it.isNotEmpty() } }
}
