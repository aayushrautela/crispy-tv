package com.crispy.tv.backend

import com.crispy.tv.backend.CrispyBackendClient.AiInsightsCard
import com.crispy.tv.backend.CrispyBackendClient.CalendarContext
import com.crispy.tv.backend.CrispyBackendClient.CalendarItem
import com.crispy.tv.backend.CrispyBackendClient.CanonicalWatchCollectionResponse
import com.crispy.tv.backend.CrispyBackendClient.ContinueWatchingStateView
import com.crispy.tv.backend.CrispyBackendClient.MediaExternalIds
import com.crispy.tv.backend.CrispyBackendClient.MediaItem
import com.crispy.tv.backend.CrispyBackendClient.UserItemData
import com.crispy.tv.backend.CrispyBackendClient.MediaPresentationHint
import com.crispy.tv.backend.CrispyBackendClient.RecommendationCollectionCard
import com.crispy.tv.backend.CrispyBackendClient.RecommendationCollectionItem
import com.crispy.tv.backend.CrispyBackendClient.RecommendationHeroItem
import com.crispy.tv.backend.CrispyBackendClient.RecommendationItem
import com.crispy.tv.backend.CrispyBackendClient.RecommendationItemContext
import com.crispy.tv.backend.CrispyBackendClient.RecommendationSection
import com.crispy.tv.backend.CrispyBackendClient.PersonSearchResultItem
import com.crispy.tv.backend.CrispyBackendClient.SearchResultItem
import com.crispy.tv.backend.CrispyBackendClient.SurfaceContext
import com.crispy.tv.backend.CrispyBackendClient.ImportJob
import com.crispy.tv.backend.CrispyBackendClient.ContinueWatchingItem
import com.crispy.tv.backend.CrispyBackendClient.RatingItem
import com.crispy.tv.backend.CrispyBackendClient.HistoryItem
import com.crispy.tv.backend.CrispyBackendClient.WatchlistItem
import com.crispy.tv.backend.CrispyBackendClient.MetadataCollectionView
import com.crispy.tv.backend.CrispyBackendClient.MetadataCardView
import com.crispy.tv.backend.CrispyBackendClient.MetadataCompanyView
import com.crispy.tv.backend.CrispyBackendClient.MetadataEpisodePreview
import com.crispy.tv.backend.CrispyBackendClient.MetadataEpisodeView
import com.crispy.tv.backend.CrispyBackendClient.MetadataExternalIds
import com.crispy.tv.backend.CrispyBackendClient.MetadataImages
import com.crispy.tv.backend.CrispyBackendClient.MetadataPersonDetail
import com.crispy.tv.backend.CrispyBackendClient.MetadataPersonKnownForItem
import com.crispy.tv.backend.CrispyBackendClient.MetadataPersonRefView
import com.crispy.tv.backend.CrispyBackendClient.MetadataProductionInfoView
import com.crispy.tv.backend.CrispyBackendClient.MetadataReviewView
import com.crispy.tv.backend.CrispyBackendClient.MetadataSeasonView
import com.crispy.tv.backend.CrispyBackendClient.MetadataTitleRatings
import com.crispy.tv.backend.CrispyBackendClient.MetadataVideoView
import com.crispy.tv.backend.CrispyBackendClient.MetadataView
import com.crispy.tv.backend.CrispyBackendClient.PageInfo
import com.crispy.tv.backend.CrispyBackendClient.Profile
import com.crispy.tv.backend.CrispyBackendClient.ProviderState
import com.crispy.tv.backend.CrispyBackendClient.RatingStateView
import com.crispy.tv.backend.CrispyBackendClient.ResponsiveImageSet
import com.crispy.tv.backend.CrispyBackendClient.SearchResultsResponse
import com.crispy.tv.backend.CrispyBackendClient.SearchSuggestionItem
import com.crispy.tv.backend.CrispyBackendClient.SearchSuggestionsResponse
import com.crispy.tv.backend.CrispyBackendClient.User
import com.crispy.tv.backend.CrispyBackendClient.WatchActionResponse
import com.crispy.tv.backend.CrispyBackendClient.WatchProgressView
import com.crispy.tv.backend.CrispyBackendClient.WatchStateEnvelope
import com.crispy.tv.backend.CrispyBackendClient.WatchStateResponse
import com.crispy.tv.backend.CrispyBackendClient.WatchStatesEnvelope
import com.crispy.tv.backend.CrispyBackendClient.WatchedStateView
import com.crispy.tv.backend.CrispyBackendClient.WatchlistStateView
import org.json.JSONArray
import org.json.JSONObject

internal fun CrispyBackendClient.parseUser(json: JSONObject): User {
    val id = json.optString("id").trim()
    if (id.isBlank()) {
        throw IllegalStateException("Backend user is missing an id.")
    }
    return User(
        id = id,
        email = json.optString("email").trim().ifBlank { null },
    )
}

internal fun CrispyBackendClient.parseProfiles(array: JSONArray?): List<Profile> {
    val safeArray = array ?: JSONArray()
    return buildList {
        for (index in 0 until safeArray.length()) {
            val profile = safeArray.optJSONObject(index) ?: continue
            add(parseProfile(profile))
        }
    }
}

internal fun CrispyBackendClient.parseProfile(json: JSONObject): Profile {
    val id = json.optString("id").trim()
    val name = json.optString("name").trim()
    if (id.isBlank() || name.isBlank()) {
        throw IllegalStateException("Backend profile is missing required fields.")
    }
    return Profile(
        id = id,
        name = name,
        avatarKey = json.optString("avatarKey").trim().ifBlank { null },
        isKids = json.optBoolean("isKids", false),
        sortOrder = json.optInt("sortOrder", 0),
        createdByUserId = json.optString("createdByUserId").trim().ifBlank { null },
        createdAt = json.optString("createdAt").trim().ifBlank { null },
        updatedAt = json.optString("updatedAt").trim().ifBlank { null },
    )
}

internal fun CrispyBackendClient.parseProviderStates(array: JSONArray?): List<ProviderState> {
    val safeArray = array ?: JSONArray()
    return buildList {
        for (index in 0 until safeArray.length()) {
            val providerState = safeArray.optJSONObject(index) ?: continue
            add(parseProviderState(providerState))
        }
    }
}

internal fun CrispyBackendClient.parseProviderState(json: JSONObject): ProviderState {
    return ProviderState(
        provider = json.optString("provider").trim(),
        connectionState = json.optString("connectionState").trim(),
        accountStatus = json.optString("accountStatus").trim().ifBlank { null },
        primaryAction = json.optString("primaryAction").trim(),
        canImport = json.optBoolean("canImport", false),
        canReconnect = json.optBoolean("canReconnect", false),
        canDisconnect = json.optBoolean("canDisconnect", false),
        externalUsername = json.optString("externalUsername").trim().ifBlank { null },
        statusLabel = json.optString("statusLabel").trim(),
        statusMessage = json.optString("statusMessage").trim().ifBlank { null },
        lastImportCompletedAt = json.optString("lastImportCompletedAt").trim().ifBlank { null },
    )
}

internal fun CrispyBackendClient.parseImportJobs(array: JSONArray?): List<ImportJob> {
    val safeArray = array ?: JSONArray()
    return buildList {
        for (index in 0 until safeArray.length()) {
            val job = safeArray.optJSONObject(index) ?: continue
            add(parseImportJob(job))
        }
    }
}

internal fun CrispyBackendClient.parseImportJob(json: JSONObject): ImportJob {
    return ImportJob(
        id = json.optString("id").trim(),
        profileId = json.optString("profileId").trim(),
        provider = json.optString("provider").trim(),
        mode = json.optString("mode").trim(),
        status = json.optString("status").trim(),
        requestedByUserId = json.optString("requestedByUserId").trim(),
        errorMessage = json.optJSONObject("errorJson")?.optString("message")?.trim().orEmpty().ifBlank { null },
        createdAt = json.optString("createdAt").trim().ifBlank { null },
        startedAt = json.optString("startedAt").trim().ifBlank { null },
        finishedAt = json.optString("finishedAt").trim().ifBlank { null },
        updatedAt = json.optString("updatedAt").trim().ifBlank { null },
    )
}

internal fun CrispyBackendClient.parseSearchResultItems(array: JSONArray?): List<SearchResultItem> {
    val safeArray = array ?: JSONArray()
    return buildList {
        for (index in 0 until safeArray.length()) {
            val item = safeArray.optJSONObject(index) ?: continue
            add(parseSearchResultItem(item))
        }
    }
}

internal fun CrispyBackendClient.parseSearchResultItem(json: JSONObject): SearchResultItem {
    val mediaJson = json.optJSONObject("mediaItem")
        ?: throw IllegalStateException("Search result is missing mediaItem.")
    return SearchResultItem(
        kind = json.optNullableString("kind") ?: "search_result",
        mediaItem = parseMediaItem(mediaJson),
        context = SurfaceContext(json.optJSONObject("context").toAnyMap()),
        presentation = parseMediaPresentationHint(json.optJSONObject("presentation")),
    )
}

internal fun CrispyBackendClient.parsePersonSearchResultItems(array: JSONArray?): List<PersonSearchResultItem> {
    val safeArray = array ?: JSONArray()
    return buildList {
        for (index in 0 until safeArray.length()) {
            val item = safeArray.optJSONObject(index) ?: continue
            add(parsePersonSearchResultItem(item))
        }
    }
}

internal fun CrispyBackendClient.parsePersonSearchResultItem(json: JSONObject): PersonSearchResultItem {
    val tmdbPersonId = json.optIntOrNull("tmdbPersonId")
        ?: throw IllegalStateException("Person search result is missing tmdbPersonId.")
    val name = json.optString("name").trim()
    if (name.isBlank()) {
        throw IllegalStateException("Person search result is missing name.")
    }
    return PersonSearchResultItem(
        kind = json.optNullableString("kind") ?: "person_search_result",
        tmdbPersonId = tmdbPersonId,
        name = name,
        knownForDepartment = json.optNullableString("knownForDepartment"),
        profileUrl = json.optNullableString("profileUrl"),
        knownForTitles = json.optStringList("knownForTitles"),
    )
}

internal fun CrispyBackendClient.parseSearchResultsResponse(json: JSONObject): SearchResultsResponse {
    return SearchResultsResponse(
        query = json.optString("query").trim(),
        all = parseSearchResultItems(json.optJSONArray("all")),
        movies = parseSearchResultItems(json.optJSONArray("movies")),
        series = parseSearchResultItems(json.optJSONArray("series")),
        people = parsePersonSearchResultItems(json.optJSONArray("people")),
    )
}

internal fun CrispyBackendClient.parseSearchSuggestionsResponse(json: JSONObject): SearchSuggestionsResponse {
    return SearchSuggestionsResponse(
        suggestions = parseSearchSuggestionItems(json.optJSONArray("suggestions")),
    )
}

internal fun CrispyBackendClient.parseSearchSuggestionItems(array: JSONArray?): List<SearchSuggestionItem> {
    val safeArray = array ?: JSONArray()
    return buildList {
        for (index in 0 until safeArray.length()) {
            val item = safeArray.optJSONObject(index) ?: continue
            add(parseSearchSuggestionItem(item))
        }
    }
}

internal fun CrispyBackendClient.parseSearchSuggestionItem(json: JSONObject): SearchSuggestionItem {
    val tmdbId = json.optIntOrNull("tmdbId")
        ?: throw IllegalStateException("Search suggestion is missing tmdbId.")
    val mediaType = json.optString("mediaType").trim().lowercase()
    if (mediaType != "movie" && mediaType != "tv") {
        throw IllegalStateException("Search suggestion has invalid mediaType: $mediaType")
    }
    val title = json.optString("title").trim()
    if (title.isBlank()) {
        throw IllegalStateException("Search suggestion is missing title.")
    }
    return SearchSuggestionItem(
        tmdbId = tmdbId,
        mediaType = mediaType,
        title = title,
        year = json.optIntOrNull("year"),
        posterPath = json.optNullableString("posterPath"),
        popularity = json.optDouble("popularity", 0.0),
        overview = json.optNullableString("overview"),
    )
}

internal fun CrispyBackendClient.parseMetadataView(json: JSONObject): MetadataView {
    val mediaKey = json.optNullableString("mediaKey")
    if (mediaKey.isNullOrBlank()) {
        throw IllegalStateException("Backend metadata view is missing a mediaKey.")
    }
    return MetadataView(
        mediaKey = mediaKey,
        mediaType = json.optNullableString("mediaType") ?: error("Backend metadata view is missing mediaType."),
        kind = json.optNullableString("kind") ?: error("Backend metadata view is missing kind."),
        tmdbId = json.optIntOrNull("tmdbId"),
        showTmdbId = json.optIntOrNull("showTmdbId"),
        absoluteEpisodeNumber = json.optIntOrNull("absoluteEpisodeNumber"),
        seasonNumber = json.optIntOrNull("seasonNumber"),
        episodeNumber = json.optIntOrNull("episodeNumber"),
        title = json.optNullableString("title"),
        subtitle = json.optNullableString("subtitle"),
        summary = json.optNullableString("summary"),
        overview = json.optNullableString("overview"),
        images = parseMetadataImages(json.optJSONObject("images")),
        releaseDate = json.optNullableString("releaseDate"),
        releaseYear = json.optIntOrNull("releaseYear"),
        runtimeMinutes = json.optIntOrNull("runtimeMinutes"),
        rating = json.optDoubleOrNull("rating"),
        certification = json.optNullableString("certification"),
        status = json.optNullableString("status"),
        genres = json.optStringList("genres"),
        externalIds = parseMetadataExternalIds(json.optJSONObject("externalIds")),
        seasonCount = json.optIntOrNull("seasonCount"),
        episodeCount = json.optIntOrNull("episodeCount"),
        nextEpisode = json.optJSONObject("nextEpisode")?.let(::parseMetadataEpisodePreview),
    )
}

internal fun CrispyBackendClient.parseMetadataCardViews(array: JSONArray?): List<MetadataCardView> {
    val safeArray = array ?: JSONArray()
    return buildList {
        for (index in 0 until safeArray.length()) {
            val item = safeArray.optJSONObject(index) ?: continue
            add(parseMetadataCardView(item))
        }
    }
}

internal fun CrispyBackendClient.parseMetadataCardView(json: JSONObject): MetadataCardView {
    return MetadataCardView(
        id = json.optNullableString("id"),
        mediaKey = json.optNullableString("mediaKey"),
        mediaType = json.optNullableString("mediaType") ?: error("Metadata card view is missing mediaType."),
        kind = json.optNullableString("kind") ?: error("Metadata card view is missing kind."),
        tmdbId = json.optIntOrNull("tmdbId"),
        showTmdbId = json.optIntOrNull("showTmdbId"),
        absoluteEpisodeNumber = json.optIntOrNull("absoluteEpisodeNumber"),
        seasonNumber = json.optIntOrNull("seasonNumber"),
        episodeNumber = json.optIntOrNull("episodeNumber"),
        title = json.optNullableString("title"),
        subtitle = json.optNullableString("subtitle"),
        summary = json.optNullableString("summary"),
        overview = json.optNullableString("overview"),
        images = parseMetadataImages(json.optJSONObject("images")),
        releaseDate = json.optNullableString("releaseDate"),
        releaseYear = json.optIntOrNull("releaseYear"),
        runtimeMinutes = json.optIntOrNull("runtimeMinutes"),
        rating = json.optDoubleOrNull("rating"),
        status = json.optNullableString("status"),
    )
}

internal fun CrispyBackendClient.parseMetadataRelatedItemViews(array: JSONArray?): List<MetadataCardView> {
    val safeArray = array ?: JSONArray()
    return buildList {
        for (index in 0 until safeArray.length()) {
            val item = safeArray.optJSONObject(index) ?: continue
            add(parseMetadataRelatedItemView(item))
        }
    }
}

internal fun CrispyBackendClient.parseMetadataRelatedItemView(json: JSONObject): MetadataCardView {
    val mediaItem = json.optJSONObject("mediaItem")
        ?: error("Metadata related item is missing mediaItem.")
    val item = parseMediaItem(mediaItem)
    return MetadataCardView(
        id = item.mediaKey,
        mediaKey = item.mediaKey,
        mediaType = item.mediaType,
        kind = json.optNullableString("kind") ?: "metadata_detail",
        tmdbId = item.externalIds.tmdb,
        showTmdbId = item.seriesId?.trim()?.toIntOrNull(),
        absoluteEpisodeNumber = item.absoluteEpisodeNumber,
        seasonNumber = item.seasonNumber,
        episodeNumber = item.episodeNumber,
        title = item.title,
        subtitle = null,
        summary = item.overview,
        overview = item.overview,
        images = MetadataImages(
            poster = item.poster,
            backdrop = item.backdrop,
            still = item.still,
            logo = item.logo,
        ),
        releaseDate = item.releaseDate,
        releaseYear = item.releaseYear,
        runtimeMinutes = item.runtimeMinutes,
        rating = item.rating,
        status = item.status,
    )
}

internal fun CrispyBackendClient.parseMetadataImages(json: JSONObject?): CrispyBackendClient.MetadataImages {
    val images = json ?: JSONObject()
    return CrispyBackendClient.MetadataImages(
        poster = parseResponsiveImageSet(images.optJSONObject("poster"), null),
        backdrop = parseResponsiveImageSet(images.optJSONObject("backdrop"), null),
        still = parseResponsiveImageSet(images.optJSONObject("still"), null),
        logo = parseResponsiveImageSet(images.optJSONObject("logo"), null),
    )
}

private fun parseResponsiveImageSet(json: JSONObject?, fallbackUrl: String?): ResponsiveImageSet {
    val fallback = fallbackUrl?.trim()?.ifBlank { null }
    return ResponsiveImageSet(
        small = json.optNullableString("small") ?: fallback,
        medium = json.optNullableString("medium") ?: fallback,
        large = json.optNullableString("large") ?: fallback,
    )
}

internal fun CrispyBackendClient.parseMetadataExternalIds(json: JSONObject?): MetadataExternalIds {
    val safe = json ?: JSONObject()
    return MetadataExternalIds(
        tmdb = safe.optIntOrNull("tmdb"),
        imdb = safe.optNullableString("imdb"),
        tvdb = safe.optIntOrNull("tvdb"),
    )
}

internal fun CrispyBackendClient.parseProviderIds(json: JSONObject?): MediaExternalIds {
    val safe = json ?: JSONObject()
    return MediaExternalIds(
        tmdb = safe.optString("tmdb").trim().toIntOrNull(),
        imdb = safe.optString("imdb").trim().ifBlank { null },
        tvdb = safe.optString("tvdb").trim().toIntOrNull(),
    )
}

private fun parseMediaItemType(type: String): String {
    return when (type.trim()) {
        "Movie" -> "movie"
        "Series" -> "show"
        "Season" -> "season"
        "Episode" -> "episode"
        "Unknown" -> "unknown"
        else -> "unknown"
    }
}

internal fun CrispyBackendClient.parseUserItemData(json: JSONObject?): UserItemData? {
    val safe = json ?: return null
    if (safe.length() == 0) return null
    return UserItemData(
        itemId = safe.optString("itemId").trim().ifBlank { null },
        isFavorite = if (safe.has("isFavorite") && !safe.isNull("isFavorite")) safe.optBoolean("isFavorite") else null,
        played = if (safe.has("played") && !safe.isNull("played")) safe.optBoolean("played") else null,
        playCount = safe.optIntOrNull("playCount"),
        playbackPositionSeconds = safe.optDoubleOrNull("playbackPositionSeconds"),
        runtimeSeconds = safe.optDoubleOrNull("runtimeSeconds"),
        playedPercentage = safe.optDoubleOrNull("playedPercentage"),
        lastPlayedDate = safe.optNullableString("lastPlayedDate"),
        rating = safe.optDoubleOrNull("rating"),
        dismissedFromContinueWatching = if (safe.has("dismissedFromContinueWatching") && !safe.isNull("dismissedFromContinueWatching")) safe.optBoolean("dismissedFromContinueWatching") else null,
    )
}

private fun parseImageTagBackdrop(array: JSONArray?, fallbackUrl: String?): ResponsiveImageSet {
    val first = array?.optJSONObject(0)
    return first?.let { parseResponsiveImageSet(it, null) }
        ?: ResponsiveImageSet(fallbackUrl, fallbackUrl, fallbackUrl)
}

internal fun CrispyBackendClient.parseMediaPresentationHint(json: JSONObject?): MediaPresentationHint? {
    val safe = json ?: return null
    return MediaPresentationHint(
        preferredSize = safe.optNullableString("preferredSize"),
        sectionId = safe.optNullableString("sectionId"),
        sectionTitle = safe.optNullableString("sectionTitle"),
    )
}

internal fun CrispyBackendClient.parseMediaItem(json: JSONObject): MediaItem {
    val mediaKey = json.optNullableString("mediaKey")
    val type = json.optNullableString("type")
    val name = json.optNullableString("name")
    if (mediaKey.isNullOrBlank() || type.isNullOrBlank() || name.isNullOrBlank()) {
        throw IllegalStateException("MediaItem is missing required identity fields.")
    }
    val imageTags = json.optJSONObject("imageTags")
    return MediaItem(
        mediaKey = mediaKey,
        mediaType = parseMediaItemType(type),
        title = name,
        originalTitle = json.optNullableString("originalTitle"),
        overview = json.optNullableString("overview"),
        poster = parseResponsiveImageSet(imageTags?.optJSONObject("primary"), null),
        backdrop = parseImageTagBackdrop(imageTags?.optJSONArray("backdrop"), null),
        logo = parseResponsiveImageSet(imageTags?.optJSONObject("logo"), null),
        still = parseResponsiveImageSet(imageTags?.optJSONObject("thumb"), null),
        releaseDate = json.optNullableString("premiereDate"),
        releaseYear = json.optIntOrNull("productionYear"),
        rating = json.optDoubleOrNull("communityRating"),
        genres = json.optStringList("genres"),
        runtimeMinutes = json.optIntOrNull("runTimeSeconds")?.let { if (it > 0) it / 60 else null },
        status = json.optNullableString("status"),
        maturityRating = json.optNullableString("officialRating"),
        certification = json.optNullableString("certification"),
        externalIds = parseProviderIds(json.optJSONObject("providerIds")),
        seasonNumber = json.optIntOrNull("parentIndexNumber"),
        episodeNumber = json.optIntOrNull("indexNumber"),
        absoluteEpisodeNumber = json.optIntOrNull("absoluteIndexNumber"),
        episodeTitle = json.optNullableString("episodeTitle"),
        airDate = json.optNullableString("airDate"),
        tagline = json.optNullableString("tagline"),
        seriesId = json.optNullableString("seriesId"),
        seriesName = json.optNullableString("seriesName"),
        seasonId = json.optNullableString("seasonId"),
        seasonName = json.optNullableString("seasonName"),
        userData = parseUserItemData(json.optJSONObject("userData")),
    )
}

internal fun CrispyBackendClient.parseMetadataEpisodePreview(json: JSONObject): MetadataEpisodePreview {
    val mediaKey = json.optNullableString("mediaKey")
    if (mediaKey.isNullOrBlank()) {
        throw IllegalStateException("Backend metadata episode preview is missing a mediaKey.")
    }
    return MetadataEpisodePreview(
        mediaKey = mediaKey,
        mediaType = json.optNullableString("mediaType") ?: error("Metadata episode preview is missing mediaType."),
        tmdbId = json.optIntOrNull("tmdbId"),
        showTmdbId = json.optIntOrNull("showTmdbId"),
        absoluteEpisodeNumber = json.optIntOrNull("absoluteEpisodeNumber"),
        seasonNumber = json.optIntOrNull("seasonNumber"),
        episodeNumber = json.optIntOrNull("episodeNumber"),
        title = json.optNullableString("title"),
        summary = json.optNullableString("summary"),
        airDate = json.optNullableString("airDate"),
        runtimeMinutes = json.optIntOrNull("runtimeMinutes"),
        rating = json.optDoubleOrNull("rating"),
        images = parseMetadataImages(json.optJSONObject("images")),
    )
}

internal fun CrispyBackendClient.parseMetadataSeasonViews(array: JSONArray?): List<MetadataSeasonView> {
    val safeArray = array ?: JSONArray()
    return buildList {
        for (index in 0 until safeArray.length()) {
            val item = safeArray.optJSONObject(index) ?: continue
            add(parseMetadataSeasonView(item))
        }
    }
}

internal fun CrispyBackendClient.parseMetadataSeasonView(json: JSONObject): MetadataSeasonView {
    val mediaKey = json.optNullableString("mediaKey")
    val seasonNumber = json.optIntOrNull("seasonNumber")
    if (mediaKey.isNullOrBlank() || seasonNumber == null) {
        throw IllegalStateException("Backend season view is missing required fields.")
    }
    return MetadataSeasonView(
        mediaKey = mediaKey,
        showTmdbId = json.optIntOrNull("showTmdbId"),
        seasonNumber = seasonNumber,
        title = json.optNullableString("title"),
        summary = json.optNullableString("summary"),
        airDate = json.optNullableString("airDate"),
        episodeCount = json.optIntOrNull("episodeCount"),
        posterUrl = parseMetadataImages(json.optJSONObject("images")).posterUrl,
    )
}

internal fun CrispyBackendClient.parseMetadataEpisodeViews(array: JSONArray?): List<MetadataEpisodeView> {
    val safeArray = array ?: JSONArray()
    return buildList {
        for (index in 0 until safeArray.length()) {
            val item = safeArray.optJSONObject(index) ?: continue
            add(parseMetadataEpisodeView(item))
        }
    }
}

internal fun CrispyBackendClient.parseMetadataEpisodeView(json: JSONObject): MetadataEpisodeView {
    val preview = parseMetadataEpisodePreview(json)
    return MetadataEpisodeView(
        mediaKey = preview.mediaKey,
        mediaType = preview.mediaType,
        tmdbId = preview.tmdbId,
        showTmdbId = preview.showTmdbId,
        absoluteEpisodeNumber = preview.absoluteEpisodeNumber,
        seasonNumber = preview.seasonNumber,
        episodeNumber = preview.episodeNumber,
        title = preview.title,
        summary = preview.summary,
        airDate = preview.airDate,
        runtimeMinutes = preview.runtimeMinutes,
        rating = preview.rating,
        images = preview.images,
        showMediaKey = json.optNullableString("showMediaKey"),
        showTitle = json.optNullableString("showTitle"),
        showExternalIds = parseMetadataExternalIds(json.optJSONObject("showExternalIds")),
    )
}

internal fun CrispyBackendClient.parseMetadataPersonDetail(json: JSONObject): MetadataPersonDetail {
    val id = json.optString("id").trim()
    val tmdbPersonId = json.optIntOrNull("tmdbPersonId")
    val name = json.optString("name").trim()
    if (id.isBlank() || tmdbPersonId == null || name.isBlank()) {
        throw IllegalStateException("Backend person detail is missing required fields.")
    }
    return MetadataPersonDetail(
        id = id,
        tmdbPersonId = tmdbPersonId,
        name = name,
        knownForDepartment = json.optNullableString("knownForDepartment"),
        biography = json.optNullableString("biography"),
        birthday = json.optNullableString("birthday"),
        placeOfBirth = json.optNullableString("placeOfBirth"),
        profileUrl = json.optNullableString("profileUrl"),
        imdbId = json.optNullableString("imdbId"),
        instagramId = json.optNullableString("instagramId"),
        twitterId = json.optNullableString("twitterId"),
        knownFor = parseMetadataPersonKnownForItems(json.optJSONArray("knownFor")),
    )
}

internal fun CrispyBackendClient.parseMetadataPersonKnownForItems(array: JSONArray?): List<MetadataPersonKnownForItem> {
    val safeArray = array ?: JSONArray()
    return buildList {
        for (index in 0 until safeArray.length()) {
            val item = safeArray.optJSONObject(index) ?: continue
            val title = item.optString("title").trim()
            if (title.isBlank()) {
                continue
            }
            add(
                MetadataPersonKnownForItem(
                    mediaKey = item.optString("mediaKey").trim(),
                    mediaType = item.optNullableString("mediaType") ?: error("Person known-for item is missing mediaType."),
                    tmdbId = item.optIntOrNull("tmdbId"),
                    title = title,
                    posterUrl = item.optNullableString("posterUrl"),
                    rating = item.optDoubleOrNull("rating"),
                    releaseYear = item.optIntOrNull("releaseYear"),
                )
            )
        }
    }
}

internal fun CrispyBackendClient.parseMetadataVideoViews(array: JSONArray?): List<MetadataVideoView> {
    val safeArray = array ?: JSONArray()
    return buildList {
        for (index in 0 until safeArray.length()) {
            val item = safeArray.optJSONObject(index) ?: continue
            val id = item.optString("id").trim()
            val key = item.optString("key").trim()
            if (id.isBlank() || key.isBlank()) continue
            add(
                MetadataVideoView(
                    id = id,
                    key = key,
                    name = item.optNullableString("name"),
                    site = item.optNullableString("site"),
                    type = item.optNullableString("type"),
                    official = item.optBoolean("official", false),
                    publishedAt = item.optNullableString("publishedAt"),
                    url = item.optNullableString("url"),
                    thumbnailUrl = item.optNullableString("thumbnailUrl"),
                )
            )
        }
    }
}

internal fun CrispyBackendClient.parseMetadataPersonRefViews(array: JSONArray?): List<MetadataPersonRefView> {
    val safeArray = array ?: JSONArray()
    return buildList {
        for (index in 0 until safeArray.length()) {
            val item = safeArray.optJSONObject(index) ?: continue
            val id = item.optString("id").trim()
            val tmdbPersonId = item.optIntOrNull("tmdbPersonId")
            val name = item.optString("name").trim()
            if (id.isBlank() || name.isBlank()) continue
            add(
                MetadataPersonRefView(
                    id = id,
                    tmdbPersonId = tmdbPersonId,
                    name = name,
                    role = item.optNullableString("role"),
                    department = item.optNullableString("department"),
                    profileUrl = item.optNullableString("profileUrl"),
                )
            )
        }
    }
}

internal fun CrispyBackendClient.parseMetadataReviewViews(array: JSONArray?): List<MetadataReviewView> {
    val safeArray = array ?: JSONArray()
    return buildList {
        for (index in 0 until safeArray.length()) {
            val item = safeArray.optJSONObject(index) ?: continue
            val id = item.optString("id").trim()
            val content = item.optString("content").trim()
            if (id.isBlank() || content.isBlank()) continue
            add(
                MetadataReviewView(
                    id = id,
                    provider = item.optString("provider").trim(),
                    author = item.optNullableString("author"),
                    username = item.optNullableString("username"),
                    content = content,
                    createdAt = item.optNullableString("createdAt"),
                    updatedAt = item.optNullableString("updatedAt"),
                    url = item.optNullableString("url"),
                    rating = item.optDoubleOrNull("rating"),
                    avatarUrl = item.optNullableString("avatarUrl"),
                )
            )
        }
    }
}

internal fun CrispyBackendClient.parseMetadataCompanyViews(array: JSONArray?): List<MetadataCompanyView> {
    val safeArray = array ?: JSONArray()
    return buildList {
        for (index in 0 until safeArray.length()) {
            val item = safeArray.optJSONObject(index) ?: continue
            val id = item.opt("id")?.toString()?.trim().orEmpty()
            val name = item.optString("name").trim()
            if (id.isBlank() || name.isBlank()) continue
            add(
                MetadataCompanyView(
                    id = id,
                    name = name,
                    logo = parseResponsiveImageSet(item.optJSONObject("logo"), null),
                    originCountry = item.optNullableString("originCountry"),
                )
            )
        }
    }
}

internal fun CrispyBackendClient.parseMetadataCollectionView(json: JSONObject?): MetadataCollectionView? {
    val safe = json ?: return null
    val id = safe.opt("id")?.toString()?.trim().orEmpty()
    val name = safe.optString("name").trim()
    if (id.isBlank() || name.isBlank()) return null
    return MetadataCollectionView(
        id = id,
        name = name,
        poster = parseResponsiveImageSet(safe.optJSONObject("poster"), null),
        backdrop = parseResponsiveImageSet(safe.optJSONObject("backdrop"), null),
        parts = parseMetadataRelatedItemViews(safe.optJSONArray("parts")),
    )
}

internal fun CrispyBackendClient.parseMetadataProductionInfoView(json: JSONObject?): MetadataProductionInfoView {
    val safe = json ?: JSONObject()
    return MetadataProductionInfoView(
        originalLanguage = safe.optNullableString("originalLanguage"),
        originCountries = safe.optStringList("originCountries"),
        spokenLanguages = safe.optStringList("spokenLanguages"),
        productionCountries = safe.optStringList("productionCountries"),
        companies = parseMetadataCompanyViews(safe.optJSONArray("companies")),
        networks = parseMetadataCompanyViews(safe.optJSONArray("networks")),
    )
}

internal fun CrispyBackendClient.parseMetadataTitleRatings(json: JSONObject?): MetadataTitleRatings {
    val safe = json ?: JSONObject()
    return MetadataTitleRatings(
        imdb = safe.optDoubleOrNull("imdb"),
        tmdb = safe.optDoubleOrNull("tmdb"),
        trakt = safe.optDoubleOrNull("trakt"),
        metacritic = safe.optDoubleOrNull("metacritic"),
        rottenTomatoes = safe.optDoubleOrNull("rottenTomatoes"),
        audience = safe.optDoubleOrNull("audience"),
        letterboxd = safe.optDoubleOrNull("letterboxd"),
        rogerEbert = safe.optDoubleOrNull("rogerEbert"),
        myAnimeList = safe.optDoubleOrNull("myAnimeList"),
    )
}

internal fun CrispyBackendClient.parseWatchProgressView(json: JSONObject?): WatchProgressView? {
    val safe = json ?: return null
    return WatchProgressView(
        positionSeconds = safe.optDoubleOrNull("positionSeconds"),
        durationSeconds = safe.optDoubleOrNull("durationSeconds"),
        progressPercent = safe.optDoubleOrNull("progressPercent") ?: 0.0,
        lastPlayedAt = safe.optNullableString("lastPlayedAt"),
    )
}

internal fun CrispyBackendClient.parseContinueWatchingStateView(json: JSONObject?): ContinueWatchingStateView? {
    val safe = json ?: return null
    val id = safe.optString("id").trim()
    val lastActivityAt = safe.optString("lastActivityAt").trim()
    if (id.isBlank() || lastActivityAt.isBlank()) {
        return null
    }
    return ContinueWatchingStateView(
        id = id,
        positionSeconds = safe.optDoubleOrNull("positionSeconds"),
        durationSeconds = safe.optDoubleOrNull("durationSeconds"),
        progressPercent = safe.optDoubleOrNull("progressPercent") ?: 0.0,
        lastActivityAt = lastActivityAt,
    )
}

internal fun CrispyBackendClient.parseWatchedStateView(json: JSONObject?): WatchedStateView? {
    val watchedAt = json.optNullableString("watchedAt") ?: return null
    return WatchedStateView(watchedAt = watchedAt)
}

internal fun CrispyBackendClient.parseWatchlistStateView(json: JSONObject?): WatchlistStateView? {
    val addedAt = json.optNullableString("addedAt") ?: return null
    return WatchlistStateView(addedAt = addedAt)
}

internal fun CrispyBackendClient.parseRatingStateView(json: JSONObject?): RatingStateView? {
    val safe = json ?: return null
    val value = safe.optIntOrNull("value") ?: return null
    val ratedAt = safe.optNullableString("ratedAt") ?: return null
    return RatingStateView(value = value, ratedAt = ratedAt)
}

internal fun CrispyBackendClient.parseOrigins(array: JSONArray?): List<String> {
    val safeArray = array ?: JSONArray()
    return buildList {
        for (index in 0 until safeArray.length()) {
            safeArray.optString(index).trim().takeIf { it.isNotBlank() }?.let(::add)
        }
    }
}

internal fun CrispyBackendClient.parseContinueWatchingItems(array: JSONArray?): List<ContinueWatchingItem> {
    val safeArray = array ?: JSONArray()
    return buildList {
        for (index in 0 until safeArray.length()) {
            val item = safeArray.optJSONObject(index) ?: continue
            add(parseContinueWatchingItem(item))
        }
    }
}

internal fun CrispyBackendClient.parseContinueWatchingItem(json: JSONObject): ContinueWatchingItem {
    val id = json.optString("id").trim()
    if (id.isBlank()) {
        throw IllegalStateException("Continue watching item is missing id.")
    }
    val mediaJson = json.optJSONObject("mediaItem")
        ?: throw IllegalStateException("Continue watching item is missing mediaItem.")
    return ContinueWatchingItem(
        id = id,
        mediaItem = parseMediaItem(mediaJson),
        context = SurfaceContext(json.optJSONObject("context").toAnyMap()),
        presentation = parseMediaPresentationHint(json.optJSONObject("presentation")),
        progress = parseWatchProgressView(json.optJSONObject("progress")),
        lastActivityAt = json.optString("lastActivityAt").trim(),
        origins = parseOrigins(json.optJSONArray("origins")),
        dismissible = json.optBoolean("dismissible", false),
    )
}

internal fun CrispyBackendClient.parseHistoryItems(array: JSONArray?): List<HistoryItem> {
    val safeArray = array ?: JSONArray()
    return buildList {
        for (index in 0 until safeArray.length()) {
            val item = safeArray.optJSONObject(index) ?: continue
            add(parseHistoryItem(item))
        }
    }
}

internal fun CrispyBackendClient.parseHistoryItem(json: JSONObject): HistoryItem {
    val id = json.optString("id").trim()
    if (id.isBlank()) {
        throw IllegalStateException("History item is missing id.")
    }
    val mediaJson = json.optJSONObject("mediaItem")
        ?: throw IllegalStateException("History item is missing mediaItem.")
    val eventType = json.optString("eventType").trim()
    if (eventType.isBlank()) {
        throw IllegalStateException("History item is missing eventType.")
    }
    return HistoryItem(
        id = id,
        mediaItem = parseMediaItem(mediaJson),
        context = SurfaceContext(json.optJSONObject("context").toAnyMap()),
        presentation = parseMediaPresentationHint(json.optJSONObject("presentation")),
        eventType = eventType,
        occurredAt = json.optNullableString("occurredAt"),
        watchedAt = json.optNullableString("watchedAt"),
        origins = parseOrigins(json.optJSONArray("origins")),
    )
}

internal fun CrispyBackendClient.parseWatchlistItems(array: JSONArray?): List<WatchlistItem> {
    val safeArray = array ?: JSONArray()
    return buildList {
        for (index in 0 until safeArray.length()) {
            val item = safeArray.optJSONObject(index) ?: continue
            add(parseWatchlistItem(item))
        }
    }
}

internal fun CrispyBackendClient.parseWatchlistItem(json: JSONObject): WatchlistItem {
    val mediaJson = json.optJSONObject("mediaItem")
    if (mediaJson == null) {
        throw IllegalStateException("Watchlist item is missing mediaItem.")
    }
    return WatchlistItem(
        id = json.optNullableString("id"),
        mediaItem = parseMediaItem(mediaJson),
        context = SurfaceContext(json.optJSONObject("context").toAnyMap()),
        presentation = parseMediaPresentationHint(json.optJSONObject("presentation")),
        addedAt = json.optNullableString("addedAt"),
        origins = parseOrigins(json.optJSONArray("origins")),
    )
}

internal fun CrispyBackendClient.parseRatingItems(array: JSONArray?): List<RatingItem> {
    val safeArray = array ?: JSONArray()
    return buildList {
        for (index in 0 until safeArray.length()) {
            val item = safeArray.optJSONObject(index) ?: continue
            add(parseRatingItem(item))
        }
    }
}

internal fun CrispyBackendClient.parseRatingItem(json: JSONObject): RatingItem {
    val mediaJson = json.optJSONObject("mediaItem")
    val ratingJson = json.optJSONObject("rating")
    val rating = parseRatingStateView(ratingJson)
    if (mediaJson == null || rating == null) {
        throw IllegalStateException("Rating item is missing required fields.")
    }
    return RatingItem(
        id = json.optNullableString("id"),
        mediaItem = parseMediaItem(mediaJson),
        context = SurfaceContext(json.optJSONObject("context").toAnyMap()),
        presentation = parseMediaPresentationHint(json.optJSONObject("presentation")),
        rating = rating,
        origins = parseOrigins(json.optJSONArray("origins")),
    )
}

internal fun CrispyBackendClient.parsePageInfo(json: JSONObject?): PageInfo {
    val safe = json ?: JSONObject()
    return PageInfo(
        nextCursor = safe.optNullableString("nextCursor"),
        hasMore = safe.optBoolean("hasMore", false),
    )
}

internal fun CrispyBackendClient.parseWatchStateResponse(json: JSONObject): WatchStateResponse {
    return WatchStateResponse(
        kind = json.optNullableString("kind") ?: error("Backend watch state is missing kind."),
        mediaItem = parseMediaItem(json.optJSONObject("mediaItem") ?: throw IllegalStateException("Backend watch state is missing mediaItem.")),
        context = SurfaceContext(json.optJSONObject("context").toAnyMap()),
        presentation = parseMediaPresentationHint(json.optJSONObject("presentation")),
        progress = parseWatchProgressView(json.optJSONObject("progress")),
        continueWatching = parseContinueWatchingStateView(json.optJSONObject("continueWatching")),
        watched = parseWatchedStateView(json.optJSONObject("watched")),
        watchlist = parseWatchlistStateView(json.optJSONObject("watchlist")),
        rating = parseRatingStateView(json.optJSONObject("rating")),
        watchedEpisodeKeys = json.optStringList("watchedEpisodeKeys"),
        playCount = json.optIntOrNull("playCount") ?: 0,
    )
}

internal fun CrispyBackendClient.parseWatchStateEnvelope(json: JSONObject): WatchStateEnvelope {
    val itemJson = json.optJSONObject("item") ?: throw IllegalStateException("Backend watch state envelope is missing item.")
    return WatchStateEnvelope(
        profileId = json.optString("profileId").trim(),
        source = json.optString("source").trim(),
        generatedAt = json.optNullableString("generatedAt"),
        item = parseWatchStateResponse(itemJson),
    )
}

internal fun CrispyBackendClient.parseWatchStatesEnvelope(json: JSONObject): WatchStatesEnvelope {
    return WatchStatesEnvelope(
        profileId = json.optString("profileId").trim(),
        source = json.optString("source").trim(),
        generatedAt = json.optNullableString("generatedAt"),
        items = buildList {
            val array = json.optJSONArray("items") ?: JSONArray()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(parseWatchStateResponse(item))
            }
        },
    )
}

internal fun CrispyBackendClient.parseWatchCollectionResponse(
    json: JSONObject,
): CanonicalWatchCollectionResponse<ContinueWatchingItem> {
    return CanonicalWatchCollectionResponse(
        profileId = json.optString("profileId").trim(),
        kind = json.optString("kind").trim(),
        source = json.optString("source").trim(),
        generatedAt = json.optNullableString("generatedAt"),
        items = parseContinueWatchingItems(json.optJSONArray("items")),
        pageInfo = parsePageInfo(json.optJSONObject("pageInfo")),
    )
}

internal fun CrispyBackendClient.parseWatchlistCollectionResponse(
    json: JSONObject,
): CanonicalWatchCollectionResponse<WatchlistItem> {
    return CanonicalWatchCollectionResponse(
        profileId = json.optString("profileId").trim(),
        kind = json.optString("kind").trim(),
        source = json.optString("source").trim(),
        generatedAt = json.optNullableString("generatedAt"),
        items = parseWatchlistItems(json.optJSONArray("items")),
        pageInfo = parsePageInfo(json.optJSONObject("pageInfo")),
    )
}

internal fun CrispyBackendClient.parseRatingCollectionResponse(
    json: JSONObject,
): CanonicalWatchCollectionResponse<RatingItem> {
    return CanonicalWatchCollectionResponse(
        profileId = json.optString("profileId").trim(),
        kind = json.optString("kind").trim(),
        source = json.optString("source").trim(),
        generatedAt = json.optNullableString("generatedAt"),
        items = parseRatingItems(json.optJSONArray("items")),
        pageInfo = parsePageInfo(json.optJSONObject("pageInfo")),
    )
}

internal fun CrispyBackendClient.parseCalendarItems(array: JSONArray?): List<CalendarItem> {
    val safeArray = array ?: JSONArray()
    return buildList {
        for (index in 0 until safeArray.length()) {
            val item = safeArray.optJSONObject(index) ?: continue
            val mediaJson = item.optJSONObject("mediaItem") ?: continue
            val contextJson = item.optJSONObject("context") ?: continue
            val relatedShowJson = contextJson.optJSONObject("relatedShow") ?: continue
            add(
                CalendarItem(
                    bucket = item.optString("bucket").trim(),
                    kind = item.optNullableString("kind") ?: error("Calendar item is missing kind."),
                    mediaItem = parseMediaItem(mediaJson),
                    context = CalendarContext(
                        bucket = contextJson.optString("bucket").trim(),
                        airDate = contextJson.optNullableString("airDate"),
                        watched = contextJson.optBoolean("watched", false),
                        relatedShow = parseMediaItem(relatedShowJson),
                    ),
                    presentation = parseMediaPresentationHint(item.optJSONObject("presentation")),
                    airDate = item.optNullableString("airDate"),
                    watched = item.optBoolean("watched", false),
                )
            )
        }
    }
}

internal fun CrispyBackendClient.parseRecommendationItems(array: JSONArray?): List<RecommendationItem> {
    val safeArray = array ?: JSONArray()
    return buildList {
        for (index in 0 until safeArray.length()) {
            val item = safeArray.optJSONObject(index) ?: continue
            val mediaJson = item.optJSONObject("mediaItem") ?: continue
            val contextJson = item.optJSONObject("context") ?: JSONObject()
            val payload = item.optJSONObject("payload")?.toAnyMap()
                ?: contextJson.optJSONObject("payload").toAnyMap()
            val rank = item.optDoubleOrNull("rank") ?: contextJson.optDoubleOrNull("rank") ?: (index + 1).toDouble()
            add(
                RecommendationItem(
                    kind = item.optNullableString("kind") ?: error("Recommendation item is missing kind."),
                    mediaItem = parseMediaItem(mediaJson),
                    context = RecommendationItemContext(
                        reason = contextJson.optNullableString("reason"),
                        reasonCodes = contextJson.optStringList("reasonCodes"),
                        score = contextJson.optDoubleOrNull("score"),
                        rank = contextJson.optDoubleOrNull("rank"),
                        payload = contextJson.optJSONObject("payload").toAnyMap(),
                    ),
                    presentation = parseMediaPresentationHint(item.optJSONObject("presentation")),
                    reason = item.optNullableString("reason") ?: contextJson.optNullableString("reason"),
                    score = item.optDoubleOrNull("score") ?: contextJson.optDoubleOrNull("score"),
                    rank = rank,
                    payload = payload,
                )
            )
        }
    }
}

internal fun CrispyBackendClient.parseRecommendationHeroItems(array: JSONArray?): List<RecommendationHeroItem> {
    val safeArray = array ?: JSONArray()
    return buildList {
        for (index in 0 until safeArray.length()) {
            val item = safeArray.optJSONObject(index) ?: continue
            val mediaKey = item.optNullableString("mediaKey") ?: continue
            val mediaType = item.optString("mediaType").trim().ifBlank { continue }
            val title = item.optString("title").trim().ifBlank { continue }
            val description = item.optString("description").trim().ifBlank { continue }
            val backdrop = parseResponsiveImageSet(item.optJSONObject("backdrop"), item.optNullableString("backdropUrl"))
            val poster = parseResponsiveImageSet(item.optJSONObject("poster"), item.optNullableString("posterUrl"))
            val logo = parseResponsiveImageSet(item.optJSONObject("logo"), item.optNullableString("logoUrl"))
            if (backdrop.isEmpty) continue
            add(
                RecommendationHeroItem(
                    mediaKey = mediaKey,
                    mediaType = mediaType,
                    title = title,
                    description = description,
                    backdrop = backdrop,
                    poster = poster,
                    logo = logo,
                    releaseYear = item.optIntOrNull("releaseYear"),
                    rating = item.optDoubleOrNull("rating"),
                    genre = item.optNullableString("genre"),
                )
            )
        }
    }
}

internal fun CrispyBackendClient.parseRecommendationCollectionCards(array: JSONArray?): List<RecommendationCollectionCard> {
    val safeArray = array ?: JSONArray()
    return buildList {
        for (index in 0 until safeArray.length()) {
            val item = safeArray.optJSONObject(index) ?: continue
            val title = item.optString("title").trim().ifBlank { continue }
            val logo = parseResponsiveImageSet(item.optJSONObject("logo"), item.optNullableString("logoUrl"))
            if (logo.isEmpty) continue
            val items = buildList {
                val parts = item.optJSONArray("items") ?: JSONArray()
                for (partIndex in 0 until parts.length()) {
                    val part = parts.optJSONObject(partIndex) ?: continue
                    val mediaType = part.optString("mediaType").trim().ifBlank { continue }
                    val partTitle = part.optString("title").trim().ifBlank { continue }
                    val poster = parseResponsiveImageSet(part.optJSONObject("poster"), part.optNullableString("posterUrl"))
                    if (poster.isEmpty) continue
                    add(
                        RecommendationCollectionItem(
                            mediaType = mediaType,
                            title = partTitle,
                            poster = poster,
                            releaseYear = part.optIntOrNull("releaseYear"),
                            rating = part.optDoubleOrNull("rating"),
                        )
                    )
                }
            }
            if (items.isNotEmpty()) {
                add(RecommendationCollectionCard(title = title, logo = logo, items = items))
            }
        }
    }
}

internal fun CrispyBackendClient.parseRecommendationSections(array: JSONArray?): List<RecommendationSection> {
    val safeArray = array ?: JSONArray()
    return buildList {
        for (index in 0 until safeArray.length()) {
            val section = safeArray.optJSONObject(index) ?: continue
            val items = section.optJSONArray("items") ?: JSONArray()
            val id = section.optString("id").trim()
            val title = section.optString("title").trim()
            val layout = section.optString("layout").trim().ifBlank { "regular" }
            val sourceKey = section.optString("sourceKey").trim().ifBlank { section.optString("source").trim() }
            add(
                RecommendationSection(
                    id = id,
                    title = title,
                    layout = layout,
                    sourceKey = sourceKey,
                    recommendationItems = when (layout) {
                        "regular", "landscape" -> parseRecommendationItems(items)
                        else -> emptyList()
                    },
                    heroItems = if (layout == "hero") parseRecommendationHeroItems(items) else emptyList(),
                    collectionItems = if (layout == "collection") parseRecommendationCollectionCards(items) else emptyList(),
                )
            )
        }
    }
}

internal fun CrispyBackendClient.parseWatchActionResponse(json: JSONObject): WatchActionResponse {
    return WatchActionResponse(
        accepted = json.optBoolean("accepted", false),
        mode = json.optString("mode").trim().ifBlank { error("Watch action response is missing mode.") },
    )
}

internal fun CrispyBackendClient.parseAiInsightsCards(array: JSONArray?): List<AiInsightsCard> {
    val safeArray = array ?: JSONArray()
    return buildList {
        for (index in 0 until safeArray.length()) {
            val item = safeArray.optJSONObject(index) ?: continue
            add(
                AiInsightsCard(
                    type = item.optString("type").trim(),
                    title = item.optString("title").trim(),
                    category = item.optString("category").trim(),
                    content = item.optString("content").trim(),
                )
            )
        }
    }
}
