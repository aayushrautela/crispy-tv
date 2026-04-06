package com.crispy.tv.domain.media

data class MediaStateNormalized(
    val cardFamily: String? = null,
    val mediaKey: String? = null,
    val mediaType: String? = null,
    val itemId: String? = null,
    val provider: String? = null,
    val providerId: String? = null,
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
        "regular_card" -> normalizeCard(payload, requireBackdrop = false)
        "landscape_card" -> normalizeCard(payload, requireBackdrop = true)
        "metadata_card" -> normalizeMetadataCard(payload)
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

private fun normalizeCard(payload: Map<String, Any?>, requireBackdrop: Boolean): MediaStateNormalized? {
    val mediaKey = payload.stringValue("mediaKey") ?: return null
    val mediaType = payload.stringValue("mediaType") ?: return null
    val provider = payload.stringValue("provider") ?: return null
    val providerId = payload.stringValue("providerId") ?: return null
    val title = payload.stringValue("title") ?: return null
    val posterUrl = payload.stringValue("posterUrl") ?: payload.objectValue("images")?.stringValue("posterUrl") ?: return null
    val backdropUrl = payload.stringValue("backdropUrl") ?: payload.objectValue("images")?.stringValue("backdropUrl")
    if (requireBackdrop && backdropUrl.isNullOrBlank()) return null
    return MediaStateNormalized(
        cardFamily = if (requireBackdrop) "landscape" else "regular",
        mediaKey = mediaKey,
        mediaType = mediaType,
        itemId = null,
        provider = provider,
        providerId = providerId,
        title = title,
        posterUrl = posterUrl,
        backdropUrl = backdropUrl,
        subtitle = payload.stringValue("subtitle"),
    )
}

private fun normalizeMetadataCard(payload: Map<String, Any?>): MediaStateNormalized? {
    val mediaKey = payload.stringValue("mediaKey") ?: return null
    val mediaType = payload.stringValue("mediaType") ?: return null
    val provider = payload.stringValue("provider") ?: return null
    val providerId = payload.stringValue("providerId") ?: return null
    val title = payload.stringValue("title") ?: payload.stringValue("subtitle") ?: return null
    val images = payload.objectValue("images")
    val posterUrl = payload.stringValue("posterUrl") ?: images?.stringValue("posterUrl") ?: return null
    val backdropUrl = payload.stringValue("backdropUrl") ?: images?.stringValue("backdropUrl")
    return MediaStateNormalized(
        cardFamily = "regular",
        mediaKey = mediaKey,
        mediaType = mediaType,
        itemId = null,
        provider = provider,
        providerId = providerId,
        title = title,
        posterUrl = posterUrl,
        backdropUrl = backdropUrl,
        subtitle = payload.stringValue("subtitle") ?: payload.stringValue("summary") ?: payload.stringValue("overview"),
    )
}

private fun normalizeContinueWatching(payload: Map<String, Any?>): MediaStateNormalized? {
    val id = payload.stringValue("id") ?: return null
    val media = payload.objectValue("media") ?: return null
    val normalizedMedia = normalizeCard(media, requireBackdrop = true) ?: return null
    val watchedAt = payload.stringValue("watchedAt") ?: return null
    val lastActivityAt = payload.stringValue("lastActivityAt") ?: return null
    val origins = payload.stringList("origins") ?: return null
    val dismissible = payload.booleanValue("dismissible") ?: return null
    val progressPercent = payload.objectValue("progress")?.doubleValue("progressPercent") ?: 0.0
    return normalizedMedia.copy(
        itemId = id,
        progressPercent = progressPercent,
        watchedAt = watchedAt,
        lastActivityAt = lastActivityAt,
        origins = origins,
        dismissible = dismissible,
    )
}

private fun normalizeWatchItem(payload: Map<String, Any?>, stateKey: String): MediaStateNormalized? {
    val media = payload.objectValue("media") ?: return null
    val normalizedMedia = normalizeCard(media, requireBackdrop = false) ?: return null
    val origins = payload.stringList("origins") ?: return null
    return normalizedMedia.copy(
        watchedAt = payload.stringValue(stateKey).takeIf { stateKey == "watchedAt" },
        origins = origins,
    )
}

private fun normalizeLibraryItem(payload: Map<String, Any?>): MediaStateNormalized? {
    val itemId = payload.stringValue("id") ?: return null
    val media = payload.objectValue("media") ?: return null
    val normalizedMedia = normalizeCard(media, requireBackdrop = false) ?: return null
    val state = payload.objectValue("state") ?: return null
    val origins = payload.stringList("origins") ?: return null
    if (state.isEmpty()) return null
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
    val mediaKey = payload.stringValue("mediaKey") ?: return null
    val path = payload.stringValue("path") ?: return null
    if (path != "/v1/metadata/titles/$mediaKey") return null
    return MediaStateNormalized(mediaKey = mediaKey, routeKind = "title")
}

private fun Map<String, Any?>.stringValue(key: String): String? {
    return this[key]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
}

private fun Map<String, Any?>.objectValue(key: String): Map<String, Any?>? {
    @Suppress("UNCHECKED_CAST")
    return this[key] as? Map<String, Any?>
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
