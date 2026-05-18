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
    val item = parseMediaItem(json)
    val imageTags = json.optJSONObject("ImageTags")
    return MetadataView(
        mediaKey = item.mediaKey,
        mediaType = item.mediaType,
        kind = "metadata_detail",
        tmdbId = item.externalIds.tmdb,
        showTmdbId = item.seriesId?.trim()?.toIntOrNull(),
        absoluteEpisodeNumber = item.absoluteEpisodeNumber,
        seasonNumber = item.seasonNumber,
        episodeNumber = item.episodeNumber,
        title = item.title,
        subtitle = item.episodeTitle,
        summary = item.overview,
        overview = item.overview,
        images = MetadataImages(
            poster = parseImageUrl(imageTags?.optString("Primary")),
            backdrop = parseBackdropImageUrl(imageTags),
            still = parseImageUrl(imageTags?.optString("Thumb")),
            logo = parseImageUrl(imageTags?.optString("Logo")),
        ),
        releaseDate = item.releaseDate,
        releaseYear = item.releaseYear,
        runtimeMinutes = item.runtimeMinutes,
        rating = item.rating,
        certification = item.certification,
        status = item.status,
        genres = item.genres,
        externalIds = MetadataExternalIds(
            tmdb = item.externalIds.tmdb,
            imdb = item.externalIds.imdb,
            tvdb = item.externalIds.tvdb,
        ),
        seasonCount = null,
        episodeCount = null,
        nextEpisode = null,
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
    val item = parseMediaItem(json)
    val imageTags = json.optJSONObject("ImageTags")
    return MetadataCardView(
        id = item.mediaKey,
        mediaKey = item.mediaKey,
        mediaType = item.mediaType,
        kind = "metadata_detail",
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
            poster = parseImageUrl(imageTags?.optString("Primary")),
            backdrop = parseBackdropImageUrl(imageTags),
            still = parseImageUrl(imageTags?.optString("Thumb")),
            logo = parseImageUrl(imageTags?.optString("Logo")),
        ),
        releaseDate = item.releaseDate,
        releaseYear = item.releaseYear,
        runtimeMinutes = item.runtimeMinutes,
        rating = item.rating,
        status = item.status,
    )
}

private fun parseImageUrl(url: String?): ResponsiveImageSet {
    val value = url?.trim()?.ifBlank { null }
    return ResponsiveImageSet(value, value, value)
}

private fun parseBackdropImageUrl(imageTags: JSONObject?): ResponsiveImageSet {
    if (imageTags == null) return ResponsiveImageSet(null, null, null)
    val arr = imageTags.optJSONArray("Backdrop")
    if (arr != null && arr.length() > 0) {
        val firstUrl = arr.optString(0).trim().ifBlank { null }
        return ResponsiveImageSet(firstUrl, firstUrl, firstUrl)
    }
    val url = imageTags.optString("Backdrop").trim().ifBlank { null }
    return ResponsiveImageSet(url, url, url)
}

private fun parseResponsiveImageSet(json: JSONObject?, fallbackUrl: String?): ResponsiveImageSet {
    val fallback = fallbackUrl?.trim()?.ifBlank { null }
    return ResponsiveImageSet(
        small = json.optNullableString("small") ?: fallback,
        medium = json.optNullableString("medium") ?: fallback,
        large = json.optNullableString("large") ?: fallback,
    )
}

internal fun CrispyBackendClient.parseProviderIds(json: JSONObject?): MediaExternalIds {
    val safe = json ?: JSONObject()
    return MediaExternalIds(
        tmdb = safe.optString("Tmdb").trim().toIntOrNull(),
        imdb = safe.optString("Imdb").trim().ifBlank { null },
        tvdb = safe.optString("Tvdb").trim().toIntOrNull(),
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
        itemId = safe.optString("ItemId").trim().ifBlank { null },
        isFavorite = if (safe.has("IsFavorite") && !safe.isNull("IsFavorite")) safe.optBoolean("IsFavorite") else null,
        played = if (safe.has("Played") && !safe.isNull("Played")) safe.optBoolean("Played") else null,
        playCount = safe.optIntOrNull("PlayCount"),
        playbackPositionSeconds = safe.optDoubleOrNull("PlaybackPositionTicks")?.let { it / 10_000_000.0 },
        runtimeSeconds = safe.optDoubleOrNull("RuntimeTicks")?.let { it / 10_000_000.0 },
        playedPercentage = safe.optDoubleOrNull("PlayedPercentage"),
        lastPlayedDate = safe.optNullableString("LastPlayedDate"),
        rating = safe.optDoubleOrNull("Rating"),
        dismissedFromContinueWatching = if (safe.has("DismissedFromContinueWatching") && !safe.isNull("DismissedFromContinueWatching")) safe.optBoolean("DismissedFromContinueWatching") else null,
    )
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
    val mediaKey = json.optNullableString("Id")
    val type = json.optNullableString("Type")
    val name = json.optNullableString("Name")
    if (mediaKey.isNullOrBlank() || type.isNullOrBlank() || name.isNullOrBlank()) {
        throw IllegalStateException("BaseItemDto is missing required identity fields.")
    }
    val imageTags = json.optJSONObject("ImageTags")
    val taglines = json.optStringList("Taglines")
    return MediaItem(
        mediaKey = mediaKey,
        mediaType = parseMediaItemType(type),
        title = name,
        originalTitle = json.optNullableString("OriginalTitle"),
        overview = json.optNullableString("Overview"),
        poster = parseImageUrl(imageTags?.optString("Primary")),
        backdrop = parseBackdropImageUrl(imageTags),
        logo = parseImageUrl(imageTags?.optString("Logo")),
        still = parseImageUrl(imageTags?.optString("Thumb")),
        releaseDate = json.optNullableString("PremiereDate"),
        releaseYear = json.optIntOrNull("ProductionYear"),
        rating = json.optDoubleOrNull("CommunityRating"),
        genres = json.optStringList("Genres"),
        runtimeMinutes = json.optLongOrNull("RunTimeTicks")?.let { if (it > 0L) (it / 600_000_000L).toInt() else null },
        status = json.optNullableString("Status"),
        maturityRating = json.optNullableString("OfficialRating"),
        certification = json.optNullableString("Certification"),
        externalIds = parseProviderIds(json.optJSONObject("ProviderIds")),
        seasonNumber = json.optIntOrNull("ParentIndexNumber"),
        episodeNumber = json.optIntOrNull("IndexNumber"),
        absoluteEpisodeNumber = json.optIntOrNull("AbsoluteIndexNumber"),
        episodeTitle = json.optNullableString("EpisodeTitle"),
        airDate = json.optNullableString("AirDate"),
        tagline = taglines.firstOrNull(),
        seriesId = json.optNullableString("SeriesId"),
        seriesName = json.optNullableString("SeriesName"),
        seasonId = json.optNullableString("SeasonId"),
        seasonName = json.optNullableString("SeasonName"),
        userData = parseUserItemData(json.optJSONObject("UserData")),
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
    val mediaKey = json.optNullableString("Id")
    val seasonNumber = json.optIntOrNull("IndexNumber")
    if (mediaKey.isNullOrBlank() || seasonNumber == null) {
        throw IllegalStateException("Backend season view is missing required fields.")
    }
    val imageTags = json.optJSONObject("ImageTags")
    return MetadataSeasonView(
        mediaKey = mediaKey,
        showTmdbId = json.optNullableString("SeriesId")?.trim()?.toIntOrNull(),
        seasonNumber = seasonNumber,
        title = json.optNullableString("Name"),
        summary = json.optNullableString("Overview"),
        airDate = json.optNullableString("PremiereDate"),
        episodeCount = null,
        posterUrl = parseImageUrl(imageTags?.optString("Primary")).medium,
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
    val mediaKey = json.optNullableString("Id")
    if (mediaKey.isNullOrBlank()) {
        throw IllegalStateException("Backend episode view is missing Id.")
    }
    val imageTags = json.optJSONObject("ImageTags")
    val providerIds = parseProviderIds(json.optJSONObject("ProviderIds"))
    return MetadataEpisodeView(
        mediaKey = mediaKey,
        mediaType = parseMediaItemType(json.optNullableString("Type") ?: "Episode"),
        tmdbId = providerIds.tmdb,
        showTmdbId = json.optNullableString("SeriesId")?.trim()?.toIntOrNull(),
        absoluteEpisodeNumber = json.optIntOrNull("AbsoluteIndexNumber"),
        seasonNumber = json.optIntOrNull("ParentIndexNumber"),
        episodeNumber = json.optIntOrNull("IndexNumber"),
        title = json.optNullableString("EpisodeTitle") ?: json.optNullableString("Name"),
        summary = json.optNullableString("Overview"),
        airDate = json.optNullableString("AirDate") ?: json.optNullableString("PremiereDate"),
        runtimeMinutes = json.optLongOrNull("RunTimeTicks")?.let { if (it > 0L) (it / 600_000_000L).toInt() else null },
        rating = json.optDoubleOrNull("CommunityRating"),
        images = MetadataImages(
            poster = parseImageUrl(imageTags?.optString("Primary")),
            backdrop = parseBackdropImageUrl(imageTags),
            still = parseImageUrl(imageTags?.optString("Thumb")),
            logo = parseImageUrl(imageTags?.optString("Logo")),
        ),
        showMediaKey = json.optNullableString("SeriesId"),
        showTitle = json.optNullableString("SeriesName"),
        showExternalIds = MetadataExternalIds(
            tmdb = providerIds.tmdb,
            imdb = providerIds.imdb,
            tvdb = providerIds.tvdb,
        ),
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
    val items = safe.optJSONArray("Items")
    if (items == null || items.length() == 0) return null
    return MetadataCollectionView(
        id = "collection",
        name = "Collection",
        poster = ResponsiveImageSet(null, null, null),
        backdrop = ResponsiveImageSet(null, null, null),
        parts = parseMetadataRelatedItemViews(items),
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
    val userData = json.optJSONObject("UserData")
    val played = userData?.optBoolean("Played") ?: false
    val isFavorite = userData?.optBoolean("IsFavorite") ?: false
    val ratingValue = userData?.optIntOrNull("Rating")
    val playCount = userData?.optInt("PlayCount") ?: 0
    return WatchStateResponse(
        kind = "watch_state",
        mediaItem = parseMediaItem(json),
        context = SurfaceContext(emptyMap()),
        presentation = null,
        progress = null,
        continueWatching = null,
        watched = if (played) WatchedStateView(watchedAt = userData?.optNullableString("LastPlayedDate")) else null,
        watchlist = if (isFavorite) WatchlistStateView(addedAt = null) else null,
        rating = ratingValue?.let { RatingStateView(value = it, ratedAt = null) },
        watchedEpisodeKeys = emptyList(),
        playCount = playCount,
    )
}

internal fun CrispyBackendClient.parseWatchStateEnvelope(json: JSONObject, profileId: String): WatchStateEnvelope {
    return WatchStateEnvelope(
        profileId = profileId,
        source = "server",
        generatedAt = null,
        item = parseWatchStateResponse(json),
    )
}

internal fun CrispyBackendClient.parseWatchStatesEnvelope(json: JSONObject, profileId: String): WatchStatesEnvelope {
    return WatchStatesEnvelope(
        profileId = profileId,
        source = "server",
        generatedAt = null,
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
