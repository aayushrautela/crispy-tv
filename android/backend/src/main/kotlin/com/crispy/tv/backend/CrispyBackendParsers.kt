package com.crispy.tv.backend

import com.crispy.tv.domain.account.builtInAvatarUrl
import com.crispy.tv.ai.AiInsightSlide
import com.crispy.tv.ai.AiInsightSlideKey
import com.crispy.tv.ai.AiInsightSlideKind
import com.crispy.tv.ai.AiInsightStandoutTag
import com.crispy.tv.backend.CrispyBackendClient.ClientImages
import com.crispy.tv.backend.CrispyBackendClient.ClientMediaCard
import com.crispy.tv.backend.CrispyBackendClient.ClientMediaCardQueryResult
import com.crispy.tv.backend.CrispyBackendClient.ClientParentRef
import com.crispy.tv.backend.CrispyBackendClient.ClientProgress
import com.crispy.tv.backend.CrispyBackendClient.MediaExternalIds
import com.crispy.tv.backend.CrispyBackendClient.MediaItem
import com.crispy.tv.backend.CrispyBackendClient.PersonSearchResultItem
import com.crispy.tv.backend.CrispyBackendClient.ImportJob
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
import com.crispy.tv.backend.CrispyBackendClient.Profile
import com.crispy.tv.backend.CrispyBackendClient.ProfileHomeSection
import com.crispy.tv.backend.CrispyBackendClient.ProviderState
import com.crispy.tv.backend.CrispyBackendClient.AccountSettings
import com.crispy.tv.backend.CrispyBackendClient.AddonCloudRow
import com.crispy.tv.backend.CrispyBackendClient.Avatar
import com.crispy.tv.backend.CrispyBackendClient.ResponsiveImageSet
import com.crispy.tv.backend.CrispyBackendClient.SearchResultsResponse
import com.crispy.tv.backend.CrispyBackendClient.SearchSuggestionItem
import com.crispy.tv.backend.CrispyBackendClient.SearchSuggestionsResponse
import com.crispy.tv.backend.CrispyBackendClient.User
import com.crispy.tv.backend.CrispyBackendClient.WatchActionResponse
import com.crispy.tv.backend.CrispyBackendClient.WatchStateEnvelope
import com.crispy.tv.backend.CrispyBackendClient.WatchStateResponse
import com.crispy.tv.backend.CrispyBackendClient.WatchStatesEnvelope
import com.crispy.tv.backend.CrispyBackendClient.WatchedStateView
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
        avatarKey = json.optString("avatarUrl").trim().ifBlank { null },
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

internal fun CrispyBackendClient.parseAccountSettings(json: JSONObject): AccountSettings {
    val settings = json.optJSONObject("settings") ?: JSONObject()
    val metadata = settings.optJSONObject("metadata") ?: JSONObject()
    return AccountSettings(
        pricingTier = settings.optString("pricingTier").trim().ifBlank { null },
        hasMdbListAccess = metadata.optBoolean("hasMdbListAccess", false),
    )
}

internal fun CrispyBackendClient.parseAddonCloudRows(value: Any?): List<AddonCloudRow> {
    val safeArray = when (value) {
        is JSONArray -> value
        is List<*> -> JSONArray(value)
        else -> null
    } ?: return emptyList()
    return buildList {
        for (index in 0 until safeArray.length()) {
            val obj = safeArray.optJSONObject(index) ?: continue
            val manifestUrl = obj.optString("manifestUrl").trim()
            if (manifestUrl.isBlank()) continue
            add(
                AddonCloudRow(
                    manifestUrl = manifestUrl,
                    sortOrder = obj.optInt("sortOrder", index),
                    name = obj.optString("name").trim().ifBlank { null },
                    enabled = obj.optBoolean("enabled", true),
                ),
            )
        }
    }
}

internal fun CrispyBackendClient.parseAvatars(array: JSONArray?): List<Avatar> {
    val safeArray = array ?: JSONArray()
    return buildList {
        for (index in 0 until safeArray.length()) {
            val obj = safeArray.optJSONObject(index) ?: continue
            val id = obj.optString("id").trim()
            if (id.isBlank()) continue
            add(
                Avatar(
                    id = id,
                    url = obj.optString("url").trim().ifBlank { null }
                        ?: com.crispy.tv.domain.account.builtInAvatarUrl(baseUrl, id),
                ),
            )
        }
    }
}

// --- Search parsers ---

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
    val personId = json.optNullableString("personId")
    val name = json.optString("name").trim()
    if (personId.isNullOrBlank() || name.isBlank()) {
        throw IllegalStateException("Person search result is missing required fields.")
    }
    return PersonSearchResultItem(
        kind = json.optNullableString("kind") ?: "person_search_result",
        personId = personId,
        name = name,
        knownForDepartment = json.optNullableString("knownForDepartment"),
        profileUrl = json.optNullableString("profileUrl"),
        knownForTitles = json.optStringList("knownForTitles"),
    )
}

internal fun CrispyBackendClient.parseSearchResultsResponse(json: JSONObject): SearchResultsResponse {
    return SearchResultsResponse(
        query = json.optString("query").trim(),
        movies = parseMediaItems(json.optJSONArray("movies")),
        series = parseMediaItems(json.optJSONArray("series")),
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
    val itemId = json.optNullableString("Id")
    if (itemId.isNullOrBlank()) {
        throw IllegalStateException("Search suggestion is missing Id.")
    }
    val type = json.optNullableString("Type")
    if (type.isNullOrBlank()) {
        throw IllegalStateException("Search suggestion is missing Type.")
    }
    val title = json.optNullableString("Name")
    if (title.isNullOrBlank()) {
        throw IllegalStateException("Search suggestion is missing title.")
    }
    val imageTags = json.optJSONObject("ImageTags")
    val primaryImage = imageTags?.optJSONObject("Primary")
    return SearchSuggestionItem(
        itemId = itemId,
        itemType = parseMediaItemType(type),
        title = title,
        year = json.optIntOrNull("ProductionYear"),
        posterUrl = primaryImage?.optNullableString("medium") ?: primaryImage?.optNullableString("large") ?: primaryImage?.optNullableString("small"),
        providerIds = parseProviderIds(json.optJSONObject("ProviderIds")),
    )
}

// --- Media item parsers ---

internal fun CrispyBackendClient.parseMediaItems(array: JSONArray?): List<MediaItem> {
    val safeArray = array ?: JSONArray()
    return buildList {
        for (index in 0 until safeArray.length()) {
            val item = safeArray.optJSONObject(index) ?: continue
            add(parseMediaItem(item))
        }
    }
}

internal fun CrispyBackendClient.parseMediaItem(json: JSONObject): MediaItem {
    val itemId = json.optNullableString("Id")
    val type = json.optNullableString("Type")
    val name = json.optNullableString("Name")
    if (itemId.isNullOrBlank() || type.isNullOrBlank() || name.isNullOrBlank()) {
        throw IllegalStateException("BaseItemDto is missing required identity fields.")
    }
    val imageTags = json.optJSONObject("ImageTags")
    val taglines = json.optStringList("Taglines")
    return MediaItem(
        itemId = itemId,
        itemType = parseMediaItemType(type),
        title = name,
        originalTitle = json.optNullableString("OriginalTitle"),
        overview = json.optNullableString("Overview"),
        poster = parseResponsiveImageSet(imageTags?.optJSONObject("Primary")),
        backdrop = parseBackdropImageUrl(imageTags),
        logo = parseResponsiveImageSet(imageTags?.optJSONObject("Logo")),
        still = parseResponsiveImageSet(imageTags?.optJSONObject("Thumb")),
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

private fun parseBackdropImageUrl(imageTags: JSONObject?): ResponsiveImageSet {
    if (imageTags == null) return ResponsiveImageSet(null, null, null)
    val arr = imageTags.optJSONArray("Backdrop")
    if (arr != null && arr.length() > 0) {
        val first = arr.optJSONObject(0)
        if (first != null) {
            return parseResponsiveImageSet(first)
        }
    }
    return ResponsiveImageSet(null, null, null)
}

private fun parseResponsiveImageSet(json: JSONObject?): ResponsiveImageSet {
    return ResponsiveImageSet(
        small = json.optNullableString("small"),
        medium = json.optNullableString("medium"),
        large = json.optNullableString("large"),
    )
}

// --- Home parsers ---

internal fun CrispyBackendClient.parseProfileHomeSections(array: JSONArray?): List<ProfileHomeSection> {
    val safeArray = array ?: JSONArray()
    return buildList {
        for (index in 0 until safeArray.length()) {
            val section = safeArray.optJSONObject(index) ?: continue
            add(parseProfileHomeSection(section))
        }
    }
}

internal fun CrispyBackendClient.parseProfileHomeSection(json: JSONObject): ProfileHomeSection {
    val listKey = json.optNullableString("listKey")
    val title = json.optNullableString("title")
    if (listKey.isNullOrBlank() || title.isNullOrBlank()) {
        throw IllegalStateException("ProfileHomeSection is missing required fields.")
    }
    val sectionType = json.optNullableString("sectionType")
    val layout = json.optNullableString("layout")
    val presentation = sectionType ?: layout ?: "contentRail"
    return ProfileHomeSection(
        listKey = listKey,
        title = title,
        subtitle = json.optNullableString("subtitle"),
        layout = presentation,
        items = parseClientMediaCards(json.optJSONArray("items")),
        meta = json.optJSONObject("meta")?.toStringMap() ?: emptyMap(),
    )
}

internal fun CrispyBackendClient.parseClientMediaCards(array: JSONArray?): List<ClientMediaCard> {
    val safeArray = array ?: JSONArray()
    return buildList {
        for (index in 0 until safeArray.length()) {
            val item = safeArray.optJSONObject(index) ?: continue
            add(parseClientMediaCard(item))
        }
    }
}

internal fun CrispyBackendClient.parseClientMediaCard(json: JSONObject): ClientMediaCard {
    val itemId = json.optNullableString("itemId")
    val mediaType = json.optNullableString("mediaType")
    val title = json.optNullableString("title")
    if (itemId.isNullOrBlank() || mediaType.isNullOrBlank() || title.isNullOrBlank()) {
        throw IllegalStateException("ClientMediaCard is missing required identity fields.")
    }
    return ClientMediaCard(
        itemId = itemId,
        mediaType = mediaType,
        title = title,
        overview = json.optNullableString("overview"),
        year = json.optIntOrNull("year"),
        releaseDate = json.optNullableString("releaseDate"),
        rating = json.optDoubleOrNull("rating"),
        maturityRating = json.optNullableString("maturityRating"),
        genres = json.optStringList("genres"),
        runtimeSeconds = json.optIntOrNull("runtimeSeconds"),
        images = parseClientImages(json.optJSONObject("images")),
        progress = parseClientProgress(json.optJSONObject("progress")),
        parent = parseClientParentRef(json.optJSONObject("parent")),
        providerIds = parseClientProviderIds(json.optJSONObject("providerIds")),
    )
}

internal fun CrispyBackendClient.parseClientProviderIds(json: JSONObject?): MediaExternalIds {
    val safe = json ?: JSONObject()
    return MediaExternalIds(
        tmdb = safe.optString("tmdb").toIntOrNull(),
        imdb = safe.optString("imdb").ifBlank { null },
        tvdb = safe.optString("tvdb").toIntOrNull(),
    )
}

internal fun CrispyBackendClient.parseClientImages(json: JSONObject?): ClientImages {
    return ClientImages(
        poster = parseResponsiveImageSet(json?.optJSONObject("poster")),
        backdrop = parseResponsiveImageSet(json?.optJSONObject("backdrop")),
        logo = parseResponsiveImageSet(json?.optJSONObject("logo")),
        still = parseResponsiveImageSet(json?.optJSONObject("still")),
    )
}

internal fun CrispyBackendClient.parseClientProgress(json: JSONObject?): ClientProgress? {
    val safe = json ?: return null
    if (safe.length() == 0) return null
    return ClientProgress(
        played = safe.optBoolean("played", false),
        playCount = safe.optIntOrNull("playCount") ?: 0,
        positionSeconds = safe.optIntOrNull("positionSeconds"),
        durationSeconds = safe.optIntOrNull("durationSeconds"),
        percent = safe.optDoubleOrNull("percent"),
        lastPlayedAt = safe.optNullableString("lastPlayedAt"),
        watchlisted = safe.optBoolean("watchlisted", false),
        userRating = safe.optDoubleOrNull("userRating"),
    )
}

internal fun CrispyBackendClient.parseClientParentRef(json: JSONObject?): ClientParentRef? {
    val safe = json ?: return null
    if (safe.length() == 0) return null
    return ClientParentRef(
        seriesItemId = safe.optNullableString("seriesItemId"),
        seriesTitle = safe.optNullableString("seriesTitle"),
        seasonItemId = safe.optNullableString("seasonItemId"),
        seasonNumber = safe.optIntOrNull("seasonNumber"),
        episodeNumber = safe.optIntOrNull("episodeNumber"),
    )
}

// --- Calendar parsers ---

internal fun CrispyBackendClient.parseCalendarItems(array: JSONArray?): List<MediaItem> {
    val safeArray = array ?: JSONArray()
    return buildList {
        for (index in 0 until safeArray.length()) {
            val item = safeArray.optJSONObject(index) ?: continue
            add(parseMediaItem(item))
        }
    }
}

// --- Watch State parsers ---

internal fun CrispyBackendClient.parseWatchStateResponse(json: JSONObject): WatchStateResponse {
    val card = parseClientMediaCard(json)
    return clientMediaCardToWatchStateResponse(card)
}

private fun clientMediaCardToWatchStateResponse(card: ClientMediaCard): WatchStateResponse {
    val progress = card.progress
    val lastPlayedAt = progress?.lastPlayedAt
    val played = progress?.played == true
    val positionSeconds = progress?.positionSeconds?.toDouble()
    val durationSeconds = progress?.durationSeconds?.toDouble()
    val progressPercent = progress?.percent
        ?: if (positionSeconds != null && durationSeconds != null && durationSeconds > 0.0) {
            ((positionSeconds / durationSeconds) * 100.0).coerceIn(0.0, 100.0)
        } else {
            null
        }
    return WatchStateResponse(
        itemId = card.itemId,
        played = played,
        watched = if (played && lastPlayedAt != null) WatchedStateView(watchedAt = lastPlayedAt) else null,
        playCount = progress?.playCount ?: 0,
        resumePositionSeconds = positionSeconds,
        durationSeconds = durationSeconds,
        progressPercent = progressPercent,
        lastPlayedAt = lastPlayedAt,
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
        items = parseClientMediaCards(json.optJSONArray("items")).map(::clientMediaCardToWatchStateResponse),
    )
}

// --- ClientMediaCardQueryResult parser ---

internal fun CrispyBackendClient.parseClientMediaCardQueryResult(json: JSONObject): ClientMediaCardQueryResult {
    return ClientMediaCardQueryResult(
        items = parseClientMediaCards(json.optJSONArray("Items")),
        startIndex = json.optInt("StartIndex", 0),
        totalRecordCount = json.optInt("TotalRecordCount", 0),
        nextCursor = json.optNullableString("NextCursor"),
        hasMore = json.optBoolean("HasMore", false),
    )
}

// --- Watch action parser ---

internal fun CrispyBackendClient.parseWatchActionResponse(json: JSONObject): WatchActionResponse {
    return WatchActionResponse(
        accepted = json.optBoolean("accepted", false),
        mode = json.optString("mode").trim().ifBlank { error("Watch action response is missing mode.") },
        reason = json.optNullableString("reason")?.trim()?.takeIf { it.isNotBlank() },
    )
}

// --- Metadata / Detail parsers (unchanged) ---

internal fun CrispyBackendClient.parseMetadataView(json: JSONObject): MetadataView {
    val item = parseMediaItem(json)
    val imageTags = json.optJSONObject("ImageTags")
    return MetadataView(
        itemId = item.itemId,
        itemType = item.itemType,
        kind = "metadata_detail",
        absoluteEpisodeNumber = item.absoluteEpisodeNumber,
        seasonNumber = item.seasonNumber,
        episodeNumber = item.episodeNumber,
        title = item.title,
        subtitle = item.episodeTitle,
        summary = item.overview,
        overview = item.overview,
        images = MetadataImages(
            poster = parseResponsiveImageSet(imageTags?.optJSONObject("Primary")),
            backdrop = parseBackdropImageUrl(imageTags),
            still = parseResponsiveImageSet(imageTags?.optJSONObject("Thumb")),
            logo = parseResponsiveImageSet(imageTags?.optJSONObject("Logo")),
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
        remoteTrailers = parseRemoteTrailers(json),
    )
}

private fun CrispyBackendClient.parseRemoteTrailers(json: JSONObject): List<RemoteTrailerDto> {
    val array = json.optJSONArray("RemoteTrailers") ?: return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val url = item.optNullableString("Url") ?: continue
            if (url.isBlank()) continue
            add(
                RemoteTrailerDto(
                    url = url,
                    thumbnailUrl = item.optNullableString("ThumbnailUrl"),
                ),
            )
        }
    }
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
        id = item.itemId,
        itemId = item.itemId,
        itemType = item.itemType,
        kind = "metadata_detail",
        absoluteEpisodeNumber = item.absoluteEpisodeNumber,
        seasonNumber = item.seasonNumber,
        episodeNumber = item.episodeNumber,
        title = item.title,
        subtitle = null,
        summary = item.overview,
        overview = item.overview,
        images = MetadataImages(
            poster = parseResponsiveImageSet(imageTags?.optJSONObject("Primary")),
            backdrop = parseBackdropImageUrl(imageTags),
            still = parseResponsiveImageSet(imageTags?.optJSONObject("Thumb")),
            logo = parseResponsiveImageSet(imageTags?.optJSONObject("Logo")),
        ),
        releaseDate = item.releaseDate,
        releaseYear = item.releaseYear,
        runtimeMinutes = item.runtimeMinutes,
        rating = item.rating,
        status = item.status,
        genre = item.genres.firstOrNull(),
        providerIds = item.externalIds,
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
    val itemId = json.optNullableString("Id")
    val seasonNumber = json.optIntOrNull("IndexNumber")
    if (itemId.isNullOrBlank() || seasonNumber == null) {
        throw IllegalStateException("Backend season view is missing required fields.")
    }
    val imageTags = json.optJSONObject("ImageTags")
    return MetadataSeasonView(
        itemId = itemId,
        seasonNumber = seasonNumber,
        title = json.optNullableString("Name"),
        summary = json.optNullableString("Overview"),
        airDate = json.optNullableString("PremiereDate"),
        episodeCount = null,
        posterUrl = parseResponsiveImageSet(imageTags?.optJSONObject("Primary")).medium,
    )
}

internal fun CrispyBackendClient.parseMetadataEpisodeView(json: JSONObject): MetadataEpisodeView {
    val itemId = json.optNullableString("Id")
    if (itemId.isNullOrBlank()) {
        throw IllegalStateException("Backend episode view is missing Id.")
    }
    val imageTags = json.optJSONObject("ImageTags")
    val providerIds = parseProviderIds(json.optJSONObject("ProviderIds"))
    return MetadataEpisodeView(
        itemId = itemId,
        itemType = parseMediaItemType(json.optNullableString("Type") ?: "Episode"),
        absoluteEpisodeNumber = json.optIntOrNull("AbsoluteIndexNumber"),
        seasonNumber = json.optIntOrNull("ParentIndexNumber"),
        episodeNumber = json.optIntOrNull("IndexNumber"),
        title = json.optNullableString("EpisodeTitle") ?: json.optNullableString("Name"),
        summary = json.optNullableString("Overview"),
        airDate = json.optNullableString("AirDate") ?: json.optNullableString("PremiereDate"),
        runtimeMinutes = json.optLongOrNull("RunTimeTicks")?.let { if (it > 0L) (it / 600_000_000L).toInt() else null },
        rating = json.optDoubleOrNull("CommunityRating"),
        images = MetadataImages(
            poster = parseResponsiveImageSet(imageTags?.optJSONObject("Primary")),
            backdrop = parseBackdropImageUrl(imageTags),
            still = parseResponsiveImageSet(imageTags?.optJSONObject("Thumb")),
            logo = parseResponsiveImageSet(imageTags?.optJSONObject("Logo")),
        ),
        showItemId = json.optNullableString("SeriesId"),
        showTitle = json.optNullableString("SeriesName"),
        showExternalIds = MetadataExternalIds(
            tmdb = providerIds.tmdb,
            imdb = providerIds.imdb,
            tvdb = providerIds.tvdb,
        ),
    )
}

internal fun CrispyBackendClient.parseMetadataPersonDetail(json: JSONObject): MetadataPersonDetail {
    val personId = json.optString("personId").trim()
    val name = json.optString("name").trim()
    if (personId.isBlank() || name.isBlank()) {
        throw IllegalStateException("Backend person detail is missing required fields.")
    }
    return MetadataPersonDetail(
        personId = personId,
        name = name,
        knownForDepartment = json.optNullableString("knownForDepartment"),
        biography = json.optNullableString("biography"),
        birthday = json.optNullableString("birthday"),
        placeOfBirth = json.optNullableString("placeOfBirth"),
        profileUrl = json.optNullableString("profileUrl"),
        knownFor = parseMetadataPersonKnownForItems(json.optJSONArray("knownFor")),
    )
}

internal fun CrispyBackendClient.parseMetadataPersonKnownForItems(array: JSONArray?): List<MetadataPersonKnownForItem> {
    val safeArray = array ?: JSONArray()
    return buildList {
        for (index in 0 until safeArray.length()) {
            val item = safeArray.optJSONObject(index) ?: continue
            val itemId = item.optString("itemId").trim()
            val mediaType = item.optString("mediaType").trim()
            val title = item.optString("title").trim()
            if (itemId.isBlank() || mediaType.isBlank() || title.isBlank()) {
                continue
            }
            add(
                MetadataPersonKnownForItem(
                    itemId = itemId,
                    mediaType = mediaType,
                    title = title,
                    poster = parseResponsiveImageSet(item.optJSONObject("poster")),
                    backdrop = parseResponsiveImageSet(item.optJSONObject("backdrop")),
                    logo = parseResponsiveImageSet(item.optJSONObject("logo")),
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
            val personId = item.optString("personId").trim()
            val name = item.optString("name").trim()
            if (personId.isBlank() || name.isBlank()) continue
            add(
                MetadataPersonRefView(
                    personId = personId,
                    name = name,
                    role = item.optNullableString("role"),
                    department = item.optNullableString("department"),
                    profileUrl = item.optNullableString("profileUrl"),
                )
            )
        }
    }.distinctBy { it.personId }
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
    }.distinctBy { it.id }
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
                    logo = parseResponsiveImageSet(item.optJSONObject("logo")),
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

fun parseAiInsightsSlides(array: JSONArray?): List<AiInsightSlide> {
    val safeArray = array ?: JSONArray()
    return buildList {
        for (index in 0 until safeArray.length()) {
            val item = safeArray.optJSONObject(index) ?: continue
            val label = item.optNullableString("label").orEmpty().trim()
            val body = item.optNullableString("body")?.trim().orEmpty()
            val focus = item.optNullableString("focus")?.trim().orEmpty()
            val context = item.optNullableString("context")?.trim().orEmpty()
            if (label.isEmpty() && body.isEmpty() && focus.isEmpty() && context.isEmpty()) continue
            add(
                AiInsightSlide(
                    key = AiInsightSlideKey.fromWire(item.optNullableString("key")),
                    label = label,
                    kind = AiInsightSlideKind.fromWire(item.optNullableString("kind")),
                    body = body.takeIf { it.isNotEmpty() },
                    tag = AiInsightStandoutTag.fromWire(item.optNullableString("tag")),
                    focus = focus.takeIf { it.isNotEmpty() },
                    context = context.takeIf { it.isNotEmpty() },
                    backdrop = parseResponsiveImageSet(item.optJSONObject("backdrop")),
                    accent = item.optNullableString("accent").orEmpty().trim(),
                )
            )
        }
    }
}
