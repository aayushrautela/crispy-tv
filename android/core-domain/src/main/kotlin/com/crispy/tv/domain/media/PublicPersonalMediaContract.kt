package com.crispy.tv.domain.media

data class ContractRegularCard(
    val mediaType: String,
    val mediaKey: String,
    val title: String,
    val posterUrl: String,
    val releaseYear: Int?,
    val rating: Double?,
    val genre: String?,
    val subtitle: String?,
)

data class ContractLandscapeCard(
    val mediaType: String,
    val mediaKey: String,
    val title: String,
    val posterUrl: String,
    val backdropUrl: String,
    val releaseYear: Int?,
    val rating: Double?,
    val genre: String?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val episodeTitle: String?,
    val airDate: String?,
    val runtimeMinutes: Int?,
)

data class ContractWatchProgress(
    val positionSeconds: Double?,
    val durationSeconds: Double?,
    val progressPercent: Double,
    val lastPlayedAt: String?,
)

data class ContractPageInfo(
    val nextCursor: String?,
    val hasMore: Boolean,
)

sealed interface WatchCollectionContractItem

data class ContractContinueWatchingItem(
    val id: String,
    val media: ContractLandscapeCard,
    val progress: ContractWatchProgress?,
    val lastActivityAt: String,
    val origins: List<String>,
    val dismissible: Boolean,
) : WatchCollectionContractItem

data class ContractHistoryItem(
    val id: String,
    val media: ContractRegularCard,
    val watchedAt: String,
    val origins: List<String>,
) : WatchCollectionContractItem

data class ContractWatchlistItem(
    val id: String,
    val media: ContractRegularCard,
    val addedAt: String,
    val origins: List<String>,
) : WatchCollectionContractItem

data class ContractRatingState(
    val value: Double,
    val ratedAt: String,
)

data class ContractRatingItem(
    val id: String,
    val media: ContractRegularCard,
    val rating: ContractRatingState,
    val origins: List<String>,
) : WatchCollectionContractItem

data class WatchCollectionContractEnvelope(
    val profileId: String,
    val kind: String,
    val source: String,
    val generatedAt: String,
    val items: List<WatchCollectionContractItem>,
    val pageInfo: ContractPageInfo,
)

data class CalendarContractItem(
    val bucket: String,
    val media: ContractLandscapeCard,
    val relatedShow: ContractRegularCard,
    val airDate: String?,
    val watched: Boolean,
)

data class CalendarContractEnvelope(
    val profileId: String,
    val source: String,
    val generatedAt: String,
    val kind: String?,
    val items: List<CalendarContractItem>,
)

fun normalizeWatchCollectionEnvelope(payload: Map<String, Any?>): WatchCollectionContractEnvelope? {
    if (!payload.hasExactKeys(setOf("profileId", "kind", "source", "generatedAt", "items", "pageInfo"))) {
        return null
    }
    val profileId = payload.requiredString("profileId") ?: return null
    val kind = payload.requiredString("kind") ?: return null
    if (kind !in setOf("continue-watching", "history", "watchlist", "ratings")) return null
    val source = payload.requiredString("source") ?: return null
    if (source != "canonical_watch") return null
    val generatedAt = payload.requiredString("generatedAt") ?: return null
    val items = payload.requiredList("items") ?: return null
    val pageInfo = payload.requiredObject("pageInfo")?.let(::parsePageInfo) ?: return null
    val normalizedItems = when (kind) {
        "continue-watching" -> items.mapStrict { value ->
            (value as? Map<*, *>)?.toStringAnyMap()?.let(::parseContinueWatchingItem)
        }
        "history" -> items.mapStrict { value ->
            (value as? Map<*, *>)?.toStringAnyMap()?.let(::parseHistoryItem)
        }
        "watchlist" -> items.mapStrict { value ->
            (value as? Map<*, *>)?.toStringAnyMap()?.let(::parseWatchlistItem)
        }
        "ratings" -> items.mapStrict { value ->
            (value as? Map<*, *>)?.toStringAnyMap()?.let(::parseRatingItem)
        }
        else -> null
    } ?: return null
    return WatchCollectionContractEnvelope(
        profileId = profileId,
        kind = kind,
        source = source,
        generatedAt = generatedAt,
        items = normalizedItems,
        pageInfo = pageInfo,
    )
}

fun normalizeCalendarEnvelope(payload: Map<String, Any?>, route: String): CalendarContractEnvelope? {
    val expectedKeys = when (route) {
        "calendar" -> setOf("profileId", "source", "generatedAt", "items")
        "this-week" -> setOf("profileId", "source", "kind", "generatedAt", "items")
        else -> return null
    }
    if (!payload.hasExactKeys(expectedKeys)) return null
    val profileId = payload.requiredString("profileId") ?: return null
    val source = payload.requiredString("source") ?: return null
    if (source != "canonical_calendar") return null
    val generatedAt = payload.requiredString("generatedAt") ?: return null
    val kind = when (route) {
        "this-week" -> payload.requiredString("kind")?.takeIf { it == "this-week" } ?: return null
        else -> null
    }
    val items = payload.requiredList("items")?.mapStrict { value ->
        (value as? Map<*, *>)?.toStringAnyMap()?.let(::parseCalendarItem)
    } ?: return null
    return CalendarContractEnvelope(
        profileId = profileId,
        source = source,
        generatedAt = generatedAt,
        kind = kind,
        items = items,
    )
}

private fun parseContinueWatchingItem(payload: Map<String, Any?>): ContractContinueWatchingItem? {
    if (!payload.hasExactKeys(setOf("id", "media", "progress", "lastActivityAt", "origins", "dismissible"))) return null
    return ContractContinueWatchingItem(
        id = payload.requiredString("id") ?: return null,
        media = payload.requiredObject("media")?.let(::parseLandscapeCard) ?: return null,
        progress = payload.nullableObject("progress")?.let(::parseWatchProgress),
        lastActivityAt = payload.requiredString("lastActivityAt") ?: return null,
        origins = payload.requiredStringList("origins") ?: return null,
        dismissible = payload.requiredBoolean("dismissible") ?: return null,
    )
}

private fun parseHistoryItem(payload: Map<String, Any?>): ContractHistoryItem? {
    if (!payload.hasExactKeys(setOf("id", "media", "watchedAt", "origins"))) return null
    return ContractHistoryItem(
        id = payload.requiredString("id") ?: return null,
        media = payload.requiredObject("media")?.let(::parseRegularCard) ?: return null,
        watchedAt = payload.requiredString("watchedAt") ?: return null,
        origins = payload.requiredStringList("origins") ?: return null,
    )
}

private fun parseWatchlistItem(payload: Map<String, Any?>): ContractWatchlistItem? {
    if (!payload.hasExactKeys(setOf("id", "media", "addedAt", "origins"))) return null
    return ContractWatchlistItem(
        id = payload.requiredString("id") ?: return null,
        media = payload.requiredObject("media")?.let(::parseRegularCard) ?: return null,
        addedAt = payload.requiredString("addedAt") ?: return null,
        origins = payload.requiredStringList("origins") ?: return null,
    )
}

private fun parseRatingItem(payload: Map<String, Any?>): ContractRatingItem? {
    if (!payload.hasExactKeys(setOf("id", "media", "rating", "origins"))) return null
    return ContractRatingItem(
        id = payload.requiredString("id") ?: return null,
        media = payload.requiredObject("media")?.let(::parseRegularCard) ?: return null,
        rating = payload.requiredObject("rating")?.let(::parseRatingState) ?: return null,
        origins = payload.requiredStringList("origins") ?: return null,
    )
}

private fun parseRatingState(payload: Map<String, Any?>): ContractRatingState? {
    if (!payload.hasExactKeys(setOf("value", "ratedAt"))) return null
    return ContractRatingState(
        value = payload.requiredNumber("value") ?: return null,
        ratedAt = payload.requiredString("ratedAt") ?: return null,
    )
}

private fun parseWatchProgress(payload: Map<String, Any?>): ContractWatchProgress? {
    if (!payload.hasExactKeys(setOf("positionSeconds", "durationSeconds", "progressPercent", "lastPlayedAt"))) return null
    return ContractWatchProgress(
        positionSeconds = payload.nullableNumber("positionSeconds"),
        durationSeconds = payload.nullableNumber("durationSeconds"),
        progressPercent = payload.requiredNumber("progressPercent") ?: return null,
        lastPlayedAt = payload.nullableString("lastPlayedAt"),
    )
}

private fun parsePageInfo(payload: Map<String, Any?>): ContractPageInfo? {
    if (!payload.hasExactKeys(setOf("nextCursor", "hasMore"))) return null
    return ContractPageInfo(
        nextCursor = payload.nullableString("nextCursor"),
        hasMore = payload.requiredBoolean("hasMore") ?: return null,
    )
}

private fun parseCalendarItem(payload: Map<String, Any?>): CalendarContractItem? {
    if (!payload.hasExactKeys(setOf("bucket", "media", "relatedShow", "airDate", "watched"))) return null
    val bucket = payload.requiredString("bucket") ?: return null
    if (bucket !in setOf("up_next", "this_week", "upcoming", "recently_released", "no_scheduled")) return null
    return CalendarContractItem(
        bucket = bucket,
        media = payload.requiredObject("media")?.let(::parseLandscapeCard) ?: return null,
        relatedShow = payload.requiredObject("relatedShow")?.let(::parseRegularCard) ?: return null,
        airDate = payload.nullableString("airDate"),
        watched = payload.requiredBoolean("watched") ?: return null,
    )
}

private fun parseRegularCard(payload: Map<String, Any?>): ContractRegularCard? {
    if (!payload.hasExactKeys(setOf("mediaType", "mediaKey", "title", "posterUrl", "releaseYear", "rating", "genre", "subtitle"))) {
        return null
    }
    return ContractRegularCard(
        mediaType = payload.requiredString("mediaType") ?: return null,
        mediaKey = payload.requiredString("mediaKey") ?: return null,
        title = payload.requiredString("title") ?: return null,
        posterUrl = payload.requiredString("posterUrl") ?: return null,
        releaseYear = payload.nullableInt("releaseYear"),
        rating = payload.nullableNumber("rating"),
        genre = payload.nullableString("genre"),
        subtitle = payload.nullableString("subtitle"),
    )
}

private fun parseLandscapeCard(payload: Map<String, Any?>): ContractLandscapeCard? {
    if (!payload.hasExactKeys(setOf("mediaType", "mediaKey", "title", "posterUrl", "backdropUrl", "releaseYear", "rating", "genre", "seasonNumber", "episodeNumber", "episodeTitle", "airDate", "runtimeMinutes"))) {
        return null
    }
    return ContractLandscapeCard(
        mediaType = payload.requiredString("mediaType") ?: return null,
        mediaKey = payload.requiredString("mediaKey") ?: return null,
        title = payload.requiredString("title") ?: return null,
        posterUrl = payload.requiredString("posterUrl") ?: return null,
        backdropUrl = payload.requiredString("backdropUrl") ?: return null,
        releaseYear = payload.nullableInt("releaseYear"),
        rating = payload.nullableNumber("rating"),
        genre = payload.nullableString("genre"),
        seasonNumber = payload.nullableInt("seasonNumber"),
        episodeNumber = payload.nullableInt("episodeNumber"),
        episodeTitle = payload.nullableString("episodeTitle"),
        airDate = payload.nullableString("airDate"),
        runtimeMinutes = payload.nullableInt("runtimeMinutes"),
    )
}

private fun Map<String, Any?>.hasExactKeys(expected: Set<String>): Boolean = keys == expected

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
