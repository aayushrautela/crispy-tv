package com.crispy.tv.domain.media

import com.crispy.tv.domain.MediaKey

data class ContractMediaExternalIds(
    val tmdb: Int?,
    val imdb: String?,
    val tvdb: Int?,
)

data class ContractMediaItem(
    val mediaKey: MediaKey,
    val mediaType: String,
    val title: String,
    val originalTitle: String?,
    val overview: String?,
    val posterUrl: String?,
    val backdropUrl: String?,
    val logoUrl: String?,
    val stillUrl: String?,
    val releaseDate: String?,
    val releaseYear: Int?,
    val rating: Double?,
    val genres: List<String>,
    val runtimeMinutes: Int?,
    val status: String?,
    val certification: String?,
    val externalIds: ContractMediaExternalIds,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val absoluteEpisodeNumber: Int?,
    val episodeTitle: String?,
    val airDate: String?,
    val tagline: String?,
    val seriesId: String?,
    val seriesName: String?,
    val seasonId: String?,
    val seasonName: String?,
    val userData: ContractUserItemData?,
)

data class ContractUserItemData(
    val itemId: String?,
    val isFavorite: Boolean?,
    val played: Boolean?,
    val playCount: Int?,
    val playbackPositionSeconds: Double?,
    val runtimeSeconds: Double?,
    val playedPercentage: Double?,
    val lastPlayedDate: String?,
    val rating: Double?,
    val dismissedFromContinueWatching: Boolean?,
)

data class ContractBaseItemDtoQueryResult(
    val items: List<ContractMediaItem>,
    val startIndex: Int,
    val totalRecordCount: Int,
    val nextCursor: String?,
    val hasMore: Boolean,
)

data class ContractCalendarEnvelope(
    val profileId: String,
    val source: String,
    val kind: String?,
    val generatedAt: String,
    val items: List<ContractMediaItem>,
)

fun normalizeBaseItemDtoQueryResult(payload: Map<String, Any?>): ContractBaseItemDtoQueryResult? {
    if (!payload.hasExactKeys(setOf("Items", "StartIndex", "TotalRecordCount", "NextCursor", "HasMore"))) return null
    val items = payload.requiredList("Items")?.mapStrict { value ->
        (value as? Map<*, *>)?.toStringAnyMap()?.let(::parseMediaItem)
    } ?: return null
    val startIndex = payload.nullableInt("StartIndex") ?: 0
    val totalRecordCount = payload.nullableInt("TotalRecordCount") ?: items.size
    val nextCursor = payload.nullableString("NextCursor")
    val hasMore = payload.requiredBoolean("HasMore") ?: return null
    return ContractBaseItemDtoQueryResult(items, startIndex, totalRecordCount, nextCursor, hasMore)
}

fun normalizeCalendarEnvelope(payload: Map<String, Any?>): ContractCalendarEnvelope? {
    if (!payload.hasRequiredKeys(setOf("profileId", "source", "generatedAt", "items"))) return null
    val profileId = payload.requiredString("profileId") ?: return null
    val source = payload.requiredString("source") ?: return null
    val generatedAt = payload.requiredString("generatedAt") ?: return null
    val items = payload.requiredList("items")?.mapStrict { value ->
        (value as? Map<*, *>)?.toStringAnyMap()?.let(::parseMediaItem)
    } ?: return null
    val kind = payload.nullableString("kind")
    return ContractCalendarEnvelope(profileId, source, kind, generatedAt, items)
}

private fun parseMediaItem(payload: Map<String, Any?>): ContractMediaItem? {
    if (!payload.hasRequiredKeys(setOf("Id", "Type", "Name"))) return null
    val mediaKey = payload.requiredString("Id") ?: return null
    val type = payload.requiredString("Type") ?: return null
    val name = payload.requiredString("Name") ?: return null
    val imageTags = payload.nullableObject("ImageTags")
    return ContractMediaItem(
        mediaKey = MediaKey(mediaKey),
        mediaType = parseContractMediaItemType(type),
        title = name,
        originalTitle = payload.nullableString("OriginalTitle"),
        overview = payload.nullableString("Overview"),
        posterUrl = imageTags?.imageTagMedium("Primary"),
        backdropUrl = imageTags?.backdropMedium(),
        logoUrl = imageTags?.imageTagMedium("Logo"),
        stillUrl = imageTags?.imageTagMedium("Thumb"),
        releaseDate = payload.nullableString("PremiereDate"),
        releaseYear = payload.nullableInt("ProductionYear"),
        rating = payload.nullableNumber("CommunityRating"),
        genres = payload.requiredStringList("Genres") ?: return null,
        runtimeMinutes = payload.nullableNumber("RunTimeTicks")?.toLong()?.let { if (it > 0L) (it / 600_000_000L).toInt() else null },
        status = payload.nullableString("Status"),
        certification = payload.nullableString("Certification"),
        externalIds = payload.nullableObject("ProviderIds")?.let(::parseProviderIds) ?: ContractMediaExternalIds(null, null, null),
        seasonNumber = payload.nullableInt("ParentIndexNumber"),
        episodeNumber = payload.nullableInt("IndexNumber"),
        absoluteEpisodeNumber = payload.nullableInt("AbsoluteIndexNumber"),
        episodeTitle = payload.nullableString("EpisodeTitle"),
        airDate = payload.nullableString("AirDate"),
        tagline = payload.requiredStringList("Taglines")?.firstOrNull(),
        seriesId = payload.nullableString("SeriesId"),
        seriesName = payload.nullableString("SeriesName"),
        seasonId = payload.nullableString("SeasonId"),
        seasonName = payload.nullableString("SeasonName"),
        userData = payload.nullableObject("UserData")?.let(::parseContractUserItemData),
    )
}

private fun parseProviderIds(payload: Map<String, Any?>): ContractMediaExternalIds {
    return ContractMediaExternalIds(
        tmdb = payload.nullableString("Tmdb")?.toIntOrNull(),
        imdb = payload.nullableString("Imdb"),
        tvdb = payload.nullableString("Tvdb")?.toIntOrNull(),
    )
}

private fun parseContractUserItemData(payload: Map<String, Any?>): ContractUserItemData? {
    if (payload.isEmpty()) return null
    return ContractUserItemData(
        itemId = payload.nullableString("ItemId"),
        isFavorite = payload["IsFavorite"] as? Boolean,
        played = payload["Played"] as? Boolean,
        playCount = (payload["PlayCount"] as? Number)?.toInt(),
        playbackPositionSeconds = (payload["PlaybackPositionTicks"] as? Number)?.toDouble()?.let { it / 10_000_000.0 },
        runtimeSeconds = (payload["RuntimeTicks"] as? Number)?.toDouble()?.let { it / 10_000_000.0 },
        playedPercentage = (payload["PlayedPercentage"] as? Number)?.toDouble(),
        lastPlayedDate = payload.nullableString("LastPlayedDate"),
        rating = (payload["Rating"] as? Number)?.toDouble(),
        dismissedFromContinueWatching = payload["DismissedFromContinueWatching"] as? Boolean,
    )
}

private fun parseContractMediaItemType(type: String): String {
    return when (type.trim()) {
        "Movie" -> "movie"
        "Series" -> "show"
        "Season" -> "season"
        "Episode" -> "episode"
        "Unknown" -> "unknown"
        else -> "unknown"
    }
}

private fun Map<String, Any?>.imageTagMedium(key: String): String? {
    val tag = this[key]
    return when (tag) {
        is String -> tag
        is Map<*, *> -> tag.toStringAnyMap()?.nullableString("medium")
        else -> null
    }
}

private fun Map<String, Any?>.backdropMedium(): String? {
    val backdrops = this["Backdrop"] as? List<*> ?: return null
    val first = backdrops.firstOrNull() ?: return null
    return when (first) {
        is String -> first.trim().ifBlank { null }
        is Map<*, *> -> first.toStringAnyMap()?.nullableString("medium")
        else -> null
    }
}

private fun Map<String, Any?>.hasExactKeys(expected: Set<String>): Boolean = keys == expected

private fun Map<String, Any?>.hasRequiredKeys(required: Set<String>): Boolean = required.all { containsKey(it) }

private fun Map<String, Any?>.requiredString(key: String): String? = this[key] as? String

private fun Map<String, Any?>.nullableString(key: String): String? {
    if (!containsKey(key)) return null
    val value = this[key]
    return when (value) {
        null -> null
        is String -> value
        else -> return null
    }
}

private fun Map<String, Any?>.requiredBoolean(key: String): Boolean? = this[key] as? Boolean

private fun Map<String, Any?>.requiredNumber(key: String): Double? = (this[key] as? Number)?.toDouble()

private fun Map<String, Any?>.nullableNumber(key: String): Double? {
    if (!containsKey(key)) return null
    val value = this[key]
    return when (value) {
        null -> null
        is Number -> value.toDouble()
        else -> return null
    }
}

private fun Map<String, Any?>.nullableInt(key: String): Int? {
    if (!containsKey(key)) return null
    val value = this[key]
    return when (value) {
        null -> null
        is Int -> value
        is Long -> value.toInt()
        is Number -> value.toDouble().takeIf { it % 1.0 == 0.0 }?.toInt()
        else -> return null
    }
}

private fun Map<String, Any?>.requiredObject(key: String): Map<String, Any?>? = (this[key] as? Map<*, *>)?.toStringAnyMap()

private fun Map<String, Any?>.nullableObject(key: String): Map<String, Any?>? {
    if (!containsKey(key)) return null
    val value = this[key] ?: return null
    return (value as? Map<*, *>)?.toStringAnyMap()
}

private fun Map<String, Any?>.requiredList(key: String): List<Any?>? = this[key] as? List<Any?>

private fun Map<String, Any?>.requiredStringList(key: String): List<String>? {
    val values = this[key] as? List<*> ?: return null
    return values.mapStrict { value -> value as? String }
}

private fun <T, R> List<T>.mapStrict(transform: (T) -> R?): List<R>? {
    val results = ArrayList<R>(size)
    for (value in this) {
        results += transform(value) ?: return null
    }
    return results
}

private fun Map<*, *>.toStringAnyMap(): Map<String, Any?>? {
    val results = LinkedHashMap<String, Any?>(size)
    for ((key, value) in this) {
        val stringKey = key as? String ?: return null
        results[stringKey] = value
    }
    return results
}
