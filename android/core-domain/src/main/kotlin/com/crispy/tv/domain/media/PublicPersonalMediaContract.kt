package com.crispy.tv.domain.media

data class ContractMediaExternalIds(
    val tmdb: Int?,
    val imdb: String?,
    val tvdb: Int?,
)

data class ContractMediaItemParent(
    val mediaKey: String,
    val mediaType: String,
    val title: String,
)

data class ContractMediaItem(
    val mediaKey: String,
    val mediaType: String,
    val title: String,
    val originalTitle: String?,
    val subtitle: String?,
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
    val parent: ContractMediaItemParent?,
    val showTmdbId: Int?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val absoluteEpisodeNumber: Int?,
    val episodeTitle: String?,
    val airDate: String?,
)

data class ContractMediaPresentationHint(
    val preferredSize: String?,
    val sectionId: String?,
    val sectionTitle: String?,
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
    val mediaItem: ContractMediaItem,
    val context: Map<String, Any?>,
    val presentation: ContractMediaPresentationHint?,
    val progress: ContractWatchProgress?,
    val lastActivityAt: String,
    val origins: List<String>,
    val dismissible: Boolean,
) : WatchCollectionContractItem

data class ContractHistoryItem(
    val id: String,
    val mediaItem: ContractMediaItem,
    val context: Map<String, Any?>,
    val presentation: ContractMediaPresentationHint?,
    val eventType: String,
    val occurredAt: String?,
    val watchedAt: String?,
    val origins: List<String>,
) : WatchCollectionContractItem

data class ContractWatchlistItem(
    val id: String,
    val mediaItem: ContractMediaItem,
    val context: Map<String, Any?>,
    val presentation: ContractMediaPresentationHint?,
    val addedAt: String,
    val origins: List<String>,
) : WatchCollectionContractItem

data class ContractRatingState(
    val value: Double,
    val ratedAt: String,
)

data class ContractRatingItem(
    val id: String,
    val mediaItem: ContractMediaItem,
    val context: Map<String, Any?>,
    val presentation: ContractMediaPresentationHint?,
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

data class CalendarContractContext(
    val bucket: String,
    val airDate: String?,
    val watched: Boolean,
    val relatedShow: ContractMediaItem,
)

data class CalendarContractItem(
    val bucket: String,
    val kind: String,
    val mediaItem: ContractMediaItem,
    val context: CalendarContractContext,
    val presentation: ContractMediaPresentationHint?,
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
    if (!payload.hasExactKeys(setOf("profileId", "kind", "source", "generatedAt", "items", "pageInfo"))) return null
    val profileId = payload.requiredString("profileId") ?: return null
    val kind = payload.requiredString("kind") ?: return null
    if (kind !in setOf("continue-watching", "history", "watchlist", "ratings")) return null
    val source = payload.requiredString("source") ?: return null
    if (source != "canonical_watch") return null
    val generatedAt = payload.requiredString("generatedAt") ?: return null
    val items = payload.requiredList("items") ?: return null
    val pageInfo = payload.requiredObject("pageInfo")?.let(::parsePageInfo) ?: return null
    val normalizedItems = when (kind) {
        "continue-watching" -> items.mapStrict { value -> (value as? Map<*, *>)?.toStringAnyMap()?.let(::parseContinueWatchingItem) }
        "history" -> items.mapStrict { value -> (value as? Map<*, *>)?.toStringAnyMap()?.let(::parseHistoryItem) }
        "watchlist" -> items.mapStrict { value -> (value as? Map<*, *>)?.toStringAnyMap()?.let(::parseWatchlistItem) }
        "ratings" -> items.mapStrict { value -> (value as? Map<*, *>)?.toStringAnyMap()?.let(::parseRatingItem) }
        else -> null
    } ?: return null
    return WatchCollectionContractEnvelope(profileId, kind, source, generatedAt, normalizedItems, pageInfo)
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
    return CalendarContractEnvelope(profileId, source, generatedAt, kind, items)
}

private fun parseContinueWatchingItem(payload: Map<String, Any?>): ContractContinueWatchingItem? {
    if (!payload.hasExactKeys(setOf("id", "kind", "mediaItem", "context", "presentation", "progress", "lastActivityAt", "origins", "dismissible"))) return null
    return ContractContinueWatchingItem(
        id = payload.requiredString("id") ?: return null,
        mediaItem = payload.requiredObject("mediaItem")?.let(::parseMediaItem) ?: return null,
        context = payload.requiredObject("context") ?: return null,
        presentation = payload.nullableObject("presentation")?.let(::parsePresentation),
        progress = payload.nullableObject("progress")?.let(::parseWatchProgress),
        lastActivityAt = payload.requiredString("lastActivityAt") ?: return null,
        origins = payload.requiredStringList("origins") ?: return null,
        dismissible = payload.requiredBoolean("dismissible") ?: return null,
    )
}

private fun parseHistoryItem(payload: Map<String, Any?>): ContractHistoryItem? {
    if (!payload.hasExactKeys(setOf("id", "kind", "mediaItem", "context", "presentation", "eventType", "occurredAt", "watchedAt", "origins"))) return null
    return ContractHistoryItem(
        id = payload.requiredString("id") ?: return null,
        mediaItem = payload.requiredObject("mediaItem")?.let(::parseMediaItem) ?: return null,
        context = payload.requiredObject("context") ?: return null,
        presentation = payload.nullableObject("presentation")?.let(::parsePresentation),
        eventType = payload.requiredString("eventType") ?: return null,
        occurredAt = payload.nullableString("occurredAt"),
        watchedAt = payload.nullableString("watchedAt"),
        origins = payload.requiredStringList("origins") ?: return null,
    )
}

private fun parseWatchlistItem(payload: Map<String, Any?>): ContractWatchlistItem? {
    if (!payload.hasExactKeys(setOf("id", "kind", "mediaItem", "context", "presentation", "addedAt", "origins"))) return null
    return ContractWatchlistItem(
        id = payload.requiredString("id") ?: return null,
        mediaItem = payload.requiredObject("mediaItem")?.let(::parseMediaItem) ?: return null,
        context = payload.requiredObject("context") ?: return null,
        presentation = payload.nullableObject("presentation")?.let(::parsePresentation),
        addedAt = payload.requiredString("addedAt") ?: return null,
        origins = payload.requiredStringList("origins") ?: return null,
    )
}

private fun parseRatingItem(payload: Map<String, Any?>): ContractRatingItem? {
    if (!payload.hasExactKeys(setOf("id", "kind", "mediaItem", "context", "presentation", "rating", "origins"))) return null
    return ContractRatingItem(
        id = payload.requiredString("id") ?: return null,
        mediaItem = payload.requiredObject("mediaItem")?.let(::parseMediaItem) ?: return null,
        context = payload.requiredObject("context") ?: return null,
        presentation = payload.nullableObject("presentation")?.let(::parsePresentation),
        rating = payload.requiredObject("rating")?.let(::parseRatingState) ?: return null,
        origins = payload.requiredStringList("origins") ?: return null,
    )
}

private fun parseCalendarItem(payload: Map<String, Any?>): CalendarContractItem? {
    if (!payload.hasExactKeys(setOf("bucket", "kind", "mediaItem", "context", "presentation", "airDate", "watched"))) return null
    val bucket = payload.requiredString("bucket") ?: return null
    if (bucket !in setOf("up_next", "this_week", "upcoming", "recently_released", "no_scheduled")) return null
    val context = payload.requiredObject("context") ?: return null
    return CalendarContractItem(
        bucket = bucket,
        kind = payload.requiredString("kind")?.takeIf { it == "calendar_item" } ?: return null,
        mediaItem = payload.requiredObject("mediaItem")?.let(::parseMediaItem) ?: return null,
        context = parseCalendarContext(context) ?: return null,
        presentation = payload.nullableObject("presentation")?.let(::parsePresentation),
        airDate = payload.nullableString("airDate"),
        watched = payload.requiredBoolean("watched") ?: return null,
    )
}

private fun parseCalendarContext(payload: Map<String, Any?>): CalendarContractContext? {
    if (!payload.hasExactKeys(setOf("bucket", "airDate", "watched", "relatedShow"))) return null
    val bucket = payload.requiredString("bucket") ?: return null
    if (bucket !in setOf("up_next", "this_week", "upcoming", "recently_released", "no_scheduled")) return null
    return CalendarContractContext(
        bucket = bucket,
        airDate = payload.nullableString("airDate"),
        watched = payload.requiredBoolean("watched") ?: return null,
        relatedShow = payload.requiredObject("relatedShow")?.let(::parseMediaItem) ?: return null,
    )
}

private fun parseRatingState(payload: Map<String, Any?>): ContractRatingState? {
    if (!payload.hasExactKeys(setOf("value", "ratedAt"))) return null
    return ContractRatingState(payload.requiredNumber("value") ?: return null, payload.requiredString("ratedAt") ?: return null)
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
    return ContractPageInfo(payload.nullableString("nextCursor"), payload.requiredBoolean("hasMore") ?: return null)
}

private fun parsePresentation(payload: Map<String, Any?>): ContractMediaPresentationHint? {
    if (!payload.hasExactKeys(setOf("preferredSize", "sectionId", "sectionTitle"))) return null
    return ContractMediaPresentationHint(
        preferredSize = payload.nullableString("preferredSize"),
        sectionId = payload.nullableString("sectionId"),
        sectionTitle = payload.nullableString("sectionTitle"),
    )
}

private fun parseMediaItem(payload: Map<String, Any?>): ContractMediaItem? {
    if (!payload.hasExactKeys(setOf("mediaKey", "mediaType", "title", "originalTitle", "subtitle", "overview", "posterUrl", "backdropUrl", "logoUrl", "stillUrl", "releaseDate", "releaseYear", "rating", "genres", "runtimeMinutes", "status", "certification", "externalIds", "parent", "showTmdbId", "seasonNumber", "episodeNumber", "absoluteEpisodeNumber", "episodeTitle", "airDate"))) return null
    return ContractMediaItem(
        mediaKey = payload.requiredString("mediaKey") ?: return null,
        mediaType = payload.requiredString("mediaType") ?: return null,
        title = payload.requiredString("title") ?: return null,
        originalTitle = payload.nullableString("originalTitle"),
        subtitle = payload.nullableString("subtitle"),
        overview = payload.nullableString("overview"),
        posterUrl = payload.nullableString("posterUrl"),
        backdropUrl = payload.nullableString("backdropUrl"),
        logoUrl = payload.nullableString("logoUrl"),
        stillUrl = payload.nullableString("stillUrl"),
        releaseDate = payload.nullableString("releaseDate"),
        releaseYear = payload.nullableInt("releaseYear"),
        rating = payload.nullableNumber("rating"),
        genres = payload.requiredStringList("genres") ?: return null,
        runtimeMinutes = payload.nullableInt("runtimeMinutes"),
        status = payload.nullableString("status"),
        certification = payload.nullableString("certification"),
        externalIds = payload.requiredObject("externalIds")?.let(::parseExternalIds) ?: return null,
        parent = payload.nullableObject("parent")?.let(::parseParent),
        showTmdbId = payload.nullableInt("showTmdbId"),
        seasonNumber = payload.nullableInt("seasonNumber"),
        episodeNumber = payload.nullableInt("episodeNumber"),
        absoluteEpisodeNumber = payload.nullableInt("absoluteEpisodeNumber"),
        episodeTitle = payload.nullableString("episodeTitle"),
        airDate = payload.nullableString("airDate"),
    )
}

private fun parseExternalIds(payload: Map<String, Any?>): ContractMediaExternalIds? {
    if (!payload.hasExactKeys(setOf("tmdb", "imdb", "tvdb"))) return null
    return ContractMediaExternalIds(payload.nullableInt("tmdb"), payload.nullableString("imdb"), payload.nullableInt("tvdb"))
}

private fun parseParent(payload: Map<String, Any?>): ContractMediaItemParent? {
    if (!payload.hasExactKeys(setOf("mediaKey", "mediaType", "title"))) return null
    return ContractMediaItemParent(
        mediaKey = payload.requiredString("mediaKey") ?: return null,
        mediaType = payload.requiredString("mediaType") ?: return null,
        title = payload.requiredString("title") ?: return null,
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
