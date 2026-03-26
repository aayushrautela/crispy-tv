package com.crispy.tv.backend

import com.crispy.tv.backend.CrispyBackendClient.AccountSecret
import com.crispy.tv.backend.CrispyBackendClient.AccountSettings
import com.crispy.tv.backend.CrispyBackendClient.AiInsightsCard
import com.crispy.tv.backend.CrispyBackendClient.BackendMetadataItem
import com.crispy.tv.backend.CrispyBackendClient.CalendarItem
import com.crispy.tv.backend.CrispyBackendClient.ContinueWatchingStateView
import com.crispy.tv.backend.CrispyBackendClient.HomeSection
import com.crispy.tv.backend.CrispyBackendClient.HydratedRatingItem
import com.crispy.tv.backend.CrispyBackendClient.HydratedWatchItem
import com.crispy.tv.backend.CrispyBackendClient.HydratedWatchlistItem
import com.crispy.tv.backend.CrispyBackendClient.ImportConnection
import com.crispy.tv.backend.CrispyBackendClient.ImportJob
import com.crispy.tv.backend.CrispyBackendClient.LibraryMutationResponse
import com.crispy.tv.backend.CrispyBackendClient.MetadataEpisodePreview
import com.crispy.tv.backend.CrispyBackendClient.MetadataEpisodeView
import com.crispy.tv.backend.CrispyBackendClient.MetadataExternalIds
import com.crispy.tv.backend.CrispyBackendClient.MetadataImages
import com.crispy.tv.backend.CrispyBackendClient.MetadataPersonDetail
import com.crispy.tv.backend.CrispyBackendClient.MetadataPersonKnownForItem
import com.crispy.tv.backend.CrispyBackendClient.MetadataSeasonView
import com.crispy.tv.backend.CrispyBackendClient.MetadataView
import com.crispy.tv.backend.CrispyBackendClient.NativeLibrary
import com.crispy.tv.backend.CrispyBackendClient.Profile
import com.crispy.tv.backend.CrispyBackendClient.ProviderAuthState
import com.crispy.tv.backend.CrispyBackendClient.ProviderLibraryFolder
import com.crispy.tv.backend.CrispyBackendClient.ProviderLibraryItem
import com.crispy.tv.backend.CrispyBackendClient.ProviderLibrarySnapshot
import com.crispy.tv.backend.CrispyBackendClient.ProviderMutationResult
import com.crispy.tv.backend.CrispyBackendClient.RatingStateView
import com.crispy.tv.backend.CrispyBackendClient.User
import com.crispy.tv.backend.CrispyBackendClient.WatchActionResponse
import com.crispy.tv.backend.CrispyBackendClient.WatchProgressView
import com.crispy.tv.backend.CrispyBackendClient.WatchStateResponse
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

internal fun CrispyBackendClient.parseImportConnections(array: JSONArray?): List<ImportConnection> {
    val safeArray = array ?: JSONArray()
    return buildList {
        for (index in 0 until safeArray.length()) {
            val connection = safeArray.optJSONObject(index) ?: continue
            add(parseImportConnection(connection))
        }
    }
}

internal fun CrispyBackendClient.parseImportConnection(json: JSONObject): ImportConnection {
    return ImportConnection(
        id = json.optString("id").trim(),
        provider = json.optString("provider").trim(),
        status = json.optString("status").trim(),
        providerUserId = json.optString("providerUserId").trim().ifBlank { null },
        externalUsername = json.optString("externalUsername").trim().ifBlank { null },
        createdAt = json.optString("createdAt").trim().ifBlank { null },
        updatedAt = json.optString("updatedAt").trim().ifBlank { null },
        lastUsedAt = json.optString("lastUsedAt").trim().ifBlank { null },
        lastImportJobId = json.optString("lastImportJobId").trim().ifBlank { null },
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
        connectionId = json.optString("connectionId").trim().ifBlank { null },
        errorMessage = json.optJSONObject("errorJson")?.optString("message")?.trim().orEmpty().ifBlank { null },
        createdAt = json.optString("createdAt").trim().ifBlank { null },
        startedAt = json.optString("startedAt").trim().ifBlank { null },
        finishedAt = json.optString("finishedAt").trim().ifBlank { null },
        updatedAt = json.optString("updatedAt").trim().ifBlank { null },
    )
}

internal fun CrispyBackendClient.parseAccountSettings(json: JSONObject?): AccountSettings {
    val settingsJson = json ?: JSONObject()
    val rawSettings = settingsJson.toStringMap()
    val hasOpenRouterKey = settingsJson.optJSONObject("ai")?.optBoolean("hasOpenRouterKey", false) == true
    val hasOmdbApiKey = settingsJson.optJSONObject("metadata")?.optBoolean("hasOmdbApiKey", false) == true
    return AccountSettings(
        settings = rawSettings,
        hasOpenRouterKey = hasOpenRouterKey,
        hasOmdbApiKey = hasOmdbApiKey,
    )
}

internal fun CrispyBackendClient.parseMetadataItems(array: JSONArray?): List<BackendMetadataItem> {
    val safeArray = array ?: JSONArray()
    return buildList {
        for (index in 0 until safeArray.length()) {
            val item = safeArray.optJSONObject(index) ?: continue
            add(parseMetadataItem(item))
        }
    }
}

internal fun CrispyBackendClient.parseMetadataItem(json: JSONObject): BackendMetadataItem {
    val id = json.optString("id").trim()
    val title = json.optString("title").trim()
    if (id.isBlank() || title.isBlank()) {
        throw IllegalStateException("Backend metadata item is missing required fields.")
    }
    val genre = json.optJSONArray("genres")?.optString(0)?.trim().takeUnless { it.isNullOrBlank() }
    return BackendMetadataItem(
        id = id,
        title = title,
        summary = json.optString("summary").trim().ifBlank { null },
        posterUrl = json.optJSONObject("images").optNullableString("posterUrl"),
        backdropUrl = json.optJSONObject("images").optNullableString("backdropUrl"),
        logoUrl = json.optJSONObject("images").optNullableString("logoUrl"),
        mediaType = json.optString("mediaType").trim().ifBlank { "movie" },
        rating = json.opt("rating")?.toString()?.trim()?.ifBlank { null },
        year = json.opt("releaseYear")?.toString()?.trim()?.ifBlank { null },
        genre = genre,
    )
}

internal fun CrispyBackendClient.parseMetadataView(json: JSONObject): MetadataView {
    val id = json.optString("id").trim()
    if (id.isBlank()) {
        throw IllegalStateException("Backend metadata view is missing an id.")
    }
    val mediaKey = json.optString("mediaKey").trim()
    if (mediaKey.isBlank()) {
        throw IllegalStateException("Backend metadata view is missing a mediaKey.")
    }
    return MetadataView(
        id = id,
        mediaKey = mediaKey,
        mediaType = json.optNullableString("mediaType") ?: "movie",
        kind = json.optNullableString("kind") ?: "title",
        tmdbId = json.optIntOrNull("tmdbId"),
        showTmdbId = json.optIntOrNull("showTmdbId"),
        seasonNumber = json.optIntOrNull("seasonNumber"),
        episodeNumber = json.optIntOrNull("episodeNumber"),
        title = json.optNullableString("title"),
        subtitle = json.optNullableString("subtitle"),
        summary = json.optNullableString("summary"),
        overview = json.optNullableString("overview"),
        images = parseMetadataImages(json),
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

internal fun CrispyBackendClient.parseMetadataImages(json: JSONObject): MetadataImages {
    val images = json.optJSONObject("images")
    val artwork = json.optJSONObject("artwork")
    return MetadataImages(
        posterUrl = images.optNullableString("posterUrl") ?: artwork.optNullableString("posterUrl"),
        backdropUrl = images.optNullableString("backdropUrl") ?: artwork.optNullableString("backdropUrl"),
        stillUrl = images.optNullableString("stillUrl") ?: artwork.optNullableString("stillUrl"),
        logoUrl = images.optNullableString("logoUrl"),
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

internal fun CrispyBackendClient.parseMetadataEpisodePreview(json: JSONObject): MetadataEpisodePreview {
    val id = json.optString("id").trim()
    if (id.isBlank()) {
        throw IllegalStateException("Backend metadata episode preview is missing an id.")
    }
    return MetadataEpisodePreview(
        id = id,
        mediaType = json.optNullableString("mediaType") ?: "episode",
        tmdbId = json.optIntOrNull("tmdbId"),
        showTmdbId = json.optIntOrNull("showTmdbId"),
        seasonNumber = json.optIntOrNull("seasonNumber"),
        episodeNumber = json.optIntOrNull("episodeNumber"),
        title = json.optNullableString("title"),
        summary = json.optNullableString("summary"),
        airDate = json.optNullableString("airDate"),
        runtimeMinutes = json.optIntOrNull("runtimeMinutes"),
        rating = json.optDoubleOrNull("rating"),
        images = parseMetadataImages(json),
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
    val id = json.optString("id").trim()
    val showId = json.optString("showId").trim()
    val seasonNumber = json.optIntOrNull("seasonNumber")
    if (id.isBlank() || showId.isBlank() || seasonNumber == null) {
        throw IllegalStateException("Backend season view is missing required fields.")
    }
    return MetadataSeasonView(
        id = id,
        showId = showId,
        showTmdbId = json.optIntOrNull("showTmdbId"),
        seasonNumber = seasonNumber,
        title = json.optNullableString("title"),
        summary = json.optNullableString("summary"),
        airDate = json.optNullableString("airDate"),
        episodeCount = json.optIntOrNull("episodeCount"),
        posterUrl = json.optJSONObject("images").optNullableString("posterUrl"),
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
        id = preview.id,
        mediaType = preview.mediaType,
        tmdbId = preview.tmdbId,
        showTmdbId = preview.showTmdbId,
        seasonNumber = preview.seasonNumber,
        episodeNumber = preview.episodeNumber,
        title = preview.title,
        summary = preview.summary,
        airDate = preview.airDate,
        runtimeMinutes = preview.runtimeMinutes,
        rating = preview.rating,
        images = preview.images,
        showId = json.optNullableString("showId"),
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
            val id = item.optString("id").trim()
            val title = item.optString("title").trim()
            if (id.isBlank() || title.isBlank()) {
                continue
            }
            add(
                MetadataPersonKnownForItem(
                    id = id,
                    mediaType = item.optNullableString("mediaType") ?: "movie",
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

internal fun CrispyBackendClient.parseProviderAuthStates(array: JSONArray?): List<ProviderAuthState> {
    val safeArray = array ?: JSONArray()
    return buildList {
        for (index in 0 until safeArray.length()) {
            val item = safeArray.optJSONObject(index) ?: continue
            add(
                ProviderAuthState(
                    provider = item.optString("provider").trim(),
                    connected = item.optBoolean("connected", false),
                    status = item.optString("status").trim().ifBlank { "disconnected" },
                    tokenState = item.optNullableString("tokenState"),
                    externalUsername = item.optNullableString("externalUsername"),
                    lastImportCompletedAt = item.optNullableString("lastImportCompletedAt"),
                    lastUsedAt = item.optNullableString("lastUsedAt"),
                    message = item.optNullableString("message"),
                )
            )
        }
    }
}

internal fun CrispyBackendClient.parseWatchProgressView(json: JSONObject?): WatchProgressView? {
    val safe = json ?: return null
    return WatchProgressView(
        positionSeconds = safe.optDoubleOrNull("positionSeconds"),
        durationSeconds = safe.optDoubleOrNull("durationSeconds"),
        progressPercent = safe.optDoubleOrNull("progressPercent") ?: 0.0,
        status = safe.optNullableString("status"),
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

internal fun CrispyBackendClient.parseHydratedWatchItems(array: JSONArray?): List<HydratedWatchItem> {
    val safeArray = array ?: JSONArray()
    return buildList {
        for (index in 0 until safeArray.length()) {
            val item = safeArray.optJSONObject(index) ?: continue
            add(parseHydratedWatchItem(item))
        }
    }
}

internal fun CrispyBackendClient.parseHydratedWatchItem(json: JSONObject): HydratedWatchItem {
    val media = json.optJSONObject("media") ?: throw IllegalStateException("Backend watch item is missing media.")
    return HydratedWatchItem(
        id = json.optNullableString("id"),
        media = parseMetadataView(media),
        progress = parseWatchProgressView(json.optJSONObject("progress")),
        watchedAt = json.optNullableString("watchedAt"),
        lastActivityAt = json.optNullableString("lastActivityAt"),
        payload = json.optJSONObject("payload").toAnyMap(),
    )
}

internal fun CrispyBackendClient.parseHydratedWatchlistItems(array: JSONArray?): List<HydratedWatchlistItem> {
    val safeArray = array ?: JSONArray()
    return buildList {
        for (index in 0 until safeArray.length()) {
            val item = safeArray.optJSONObject(index) ?: continue
            val mediaJson = item.optJSONObject("media") ?: continue
            val addedAt = item.optNullableString("addedAt") ?: continue
            add(
                HydratedWatchlistItem(
                    media = parseMetadataView(mediaJson),
                    addedAt = addedAt,
                    payload = item.optJSONObject("payload").toAnyMap(),
                )
            )
        }
    }
}

internal fun CrispyBackendClient.parseHydratedRatingItems(array: JSONArray?): List<HydratedRatingItem> {
    val safeArray = array ?: JSONArray()
    return buildList {
        for (index in 0 until safeArray.length()) {
            val item = safeArray.optJSONObject(index) ?: continue
            val mediaJson = item.optJSONObject("media") ?: continue
            val rating = parseRatingStateView(item.optJSONObject("rating")) ?: continue
            add(
                HydratedRatingItem(
                    media = parseMetadataView(mediaJson),
                    rating = rating,
                    payload = item.optJSONObject("payload").toAnyMap(),
                )
            )
        }
    }
}

internal fun CrispyBackendClient.parseWatchStateResponse(json: JSONObject): WatchStateResponse {
    return WatchStateResponse(
        media = parseMetadataView(json.optJSONObject("media") ?: throw IllegalStateException("Backend watch state is missing media.")),
        progress = parseWatchProgressView(json.optJSONObject("progress")),
        continueWatching = parseContinueWatchingStateView(json.optJSONObject("continueWatching")),
        watched = parseWatchedStateView(json.optJSONObject("watched")),
        watchlist = parseWatchlistStateView(json.optJSONObject("watchlist")),
        rating = parseRatingStateView(json.optJSONObject("rating")),
        watchedEpisodeKeys = json.optStringList("watchedEpisodeKeys"),
    )
}

internal fun CrispyBackendClient.parseCalendarItems(array: JSONArray?): List<CalendarItem> {
    val safeArray = array ?: JSONArray()
    return buildList {
        for (index in 0 until safeArray.length()) {
            val item = safeArray.optJSONObject(index) ?: continue
            val mediaJson = item.optJSONObject("media") ?: continue
            val showJson = item.optJSONObject("relatedShow") ?: continue
            add(
                CalendarItem(
                    bucket = item.optString("bucket").trim(),
                    media = parseMetadataView(mediaJson),
                    relatedShow = parseMetadataView(showJson),
                    airDate = item.optNullableString("airDate"),
                    watched = item.optBoolean("watched", false),
                )
            )
        }
    }
}

internal fun CrispyBackendClient.parseHomeSections(array: JSONArray?): List<HomeSection> {
    val safeArray = array ?: JSONArray()
    return buildList {
        for (index in 0 until safeArray.length()) {
            val section = safeArray.optJSONObject(index) ?: continue
            val items = section.optJSONArray("items") ?: JSONArray()
            val calendarItems = parseCalendarItems(items)
            val watchItems = if (calendarItems.isEmpty()) parseHydratedWatchItems(items) else emptyList()
            add(
                HomeSection(
                    id = section.optString("id").trim(),
                    title = section.optString("title").trim(),
                    watchItems = watchItems,
                    calendarItems = calendarItems,
                )
            )
        }
    }
}

internal fun CrispyBackendClient.parseNativeLibrary(json: JSONObject): NativeLibrary {
    return NativeLibrary(
        continueWatching = parseHydratedWatchItems(json.optJSONArray("continueWatching")),
        history = parseHydratedWatchItems(json.optJSONArray("history")),
        watchlist = parseHydratedWatchlistItems(json.optJSONArray("watchlist")),
        ratings = parseHydratedRatingItems(json.optJSONArray("ratings")),
    )
}

internal fun CrispyBackendClient.parseProviderLibrarySnapshots(array: JSONArray?): List<ProviderLibrarySnapshot> {
    val safeArray = array ?: JSONArray()
    return buildList {
        for (index in 0 until safeArray.length()) {
            val snapshot = safeArray.optJSONObject(index) ?: continue
            add(
                ProviderLibrarySnapshot(
                    provider = snapshot.optString("provider").trim(),
                    status = snapshot.optString("status").trim(),
                    statusMessage = snapshot.optString("statusMessage").trim(),
                    folders = parseProviderLibraryFolders(snapshot.optJSONArray("folders")),
                    items = parseProviderLibraryItems(snapshot.optJSONArray("items")),
                )
            )
        }
    }
}

internal fun CrispyBackendClient.parseProviderLibraryFolders(array: JSONArray?): List<ProviderLibraryFolder> {
    val safeArray = array ?: JSONArray()
    return buildList {
        for (index in 0 until safeArray.length()) {
            val item = safeArray.optJSONObject(index) ?: continue
            val id = item.optString("id").trim()
            val label = item.optString("label").trim()
            if (id.isBlank() || label.isBlank()) {
                continue
            }
            add(
                ProviderLibraryFolder(
                    id = id,
                    label = label,
                    provider = item.optString("provider").trim(),
                    itemCount = item.optIntOrNull("itemCount") ?: 0,
                )
            )
        }
    }
}

internal fun CrispyBackendClient.parseProviderLibraryItems(array: JSONArray?): List<ProviderLibraryItem> {
    val safeArray = array ?: JSONArray()
    return buildList {
        for (index in 0 until safeArray.length()) {
            val item = safeArray.optJSONObject(index) ?: continue
            val provider = item.optString("provider").trim()
            val folderId = item.optString("folderId").trim()
            val contentId = item.optString("contentId").trim()
            val contentType = item.optString("contentType").trim()
            val title = item.optString("title").trim()
            if (provider.isBlank() || folderId.isBlank() || contentId.isBlank() || contentType.isBlank() || title.isBlank()) {
                continue
            }
            add(
                ProviderLibraryItem(
                    provider = provider,
                    folderId = folderId,
                    contentId = contentId,
                    contentType = contentType,
                    title = title,
                    posterUrl = item.optNullableString("posterUrl"),
                    backdropUrl = item.optNullableString("backdropUrl"),
                    seasonNumber = item.optIntOrNull("seasonNumber"),
                    episodeNumber = item.optIntOrNull("episodeNumber"),
                    addedAt = item.optNullableString("addedAt"),
                    media = item.optJSONObject("media")?.let(::parseMetadataView),
                )
            )
        }
    }
}

internal fun CrispyBackendClient.parseLibraryMutationResponse(json: JSONObject): LibraryMutationResponse {
    return LibraryMutationResponse(
        source = json.optString("source").trim(),
        action = json.optString("action").trim(),
        media = parseMetadataView(json.optJSONObject("media") ?: throw IllegalStateException("Backend library mutation is missing media.")),
        watchlist = json.optBooleanOrNull("watchlist"),
        rating = json.optIntOrNull("rating"),
        results = parseProviderMutationResults(json.optJSONArray("results")),
        statusMessage = json.optString("statusMessage").trim(),
    )
}

internal fun CrispyBackendClient.parseProviderMutationResults(array: JSONArray?): List<ProviderMutationResult> {
    val safeArray = array ?: JSONArray()
    return buildList {
        for (index in 0 until safeArray.length()) {
            val item = safeArray.optJSONObject(index) ?: continue
            add(
                ProviderMutationResult(
                    provider = item.optString("provider").trim(),
                    status = item.optString("status").trim(),
                    message = item.optNullableString("message"),
                )
            )
        }
    }
}

internal fun CrispyBackendClient.parseWatchActionResponse(json: JSONObject): WatchActionResponse {
    return WatchActionResponse(
        accepted = json.optBoolean("accepted", false),
        mode = json.optString("mode").trim().ifBlank { "synchronous" },
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

internal fun CrispyBackendClient.parseAccountSecret(json: JSONObject?): AccountSecret? {
    val secretJson = json ?: return null
    val key = secretJson.optString("key").trim()
    val value = secretJson.optString("value").trim()
    if (key.isBlank() || value.isBlank()) {
        return null
    }
    return AccountSecret(key = key, value = value)
}
