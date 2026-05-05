package com.crispy.tv.backend

import com.crispy.tv.backend.CrispyBackendClient.AiInsightsCard
import com.crispy.tv.backend.CrispyBackendClient.BackendMetadataItem
import com.crispy.tv.backend.CrispyBackendClient.CalendarItem
import com.crispy.tv.backend.CrispyBackendClient.CanonicalWatchCollectionResponse
import com.crispy.tv.backend.CrispyBackendClient.ContinueWatchingStateView
import com.crispy.tv.backend.CrispyBackendClient.RecommendationCollectionCard
import com.crispy.tv.backend.CrispyBackendClient.RecommendationCollectionItem
import com.crispy.tv.backend.CrispyBackendClient.RecommendationHeroItem
import com.crispy.tv.backend.CrispyBackendClient.RecommendationItem
import com.crispy.tv.backend.CrispyBackendClient.RecommendationSection
import com.crispy.tv.backend.CrispyBackendClient.ImportJob
import com.crispy.tv.backend.CrispyBackendClient.ContinueWatchingItem
import com.crispy.tv.backend.CrispyBackendClient.RuntimeMediaCard
import com.crispy.tv.backend.CrispyBackendClient.RatingItem
import com.crispy.tv.backend.CrispyBackendClient.WatchedItem
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
import com.crispy.tv.backend.CrispyBackendClient.SearchResultsResponse
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

internal fun CrispyBackendClient.parseMetadataItems(array: JSONArray?): List<BackendMetadataItem> {
    val safeArray = array ?: JSONArray()
    return buildList {
        for (index in 0 until safeArray.length()) {
            val item = safeArray.optJSONObject(index) ?: continue
            add(parseMetadataItem(item))
        }
    }
}

internal fun CrispyBackendClient.parseSearchResultsResponse(json: JSONObject): SearchResultsResponse {
    return SearchResultsResponse(
        query = json.optString("query").trim(),
        all = parseMetadataItems(json.optJSONArray("all")),
        movies = parseMetadataItems(json.optJSONArray("movies")),
        series = parseMetadataItems(json.optJSONArray("series")),
        anime = parseMetadataItems(json.optJSONArray("anime")),
    )
}

internal fun CrispyBackendClient.parseMetadataItem(json: JSONObject): BackendMetadataItem {
    val title = json.optString("title").trim()
    val mediaType = json.optString("mediaType").trim()
    val provider = json.optNullableString("provider")
    val providerId = json.opt("providerId")?.toString()?.trim()?.ifBlank { null }
    val images = parseMetadataImages(json)
    val posterUrl = json.optNullableString("posterUrl") ?: images.posterUrl
    if (title.isBlank()) {
        throw IllegalStateException("Backend metadata item is missing required fields.")
    }
if (mediaType.isBlank() || posterUrl.isNullOrBlank()) {
    throw IllegalStateException("Backend metadata item is missing regular-card fields.")
  }
    val genre = json.optNullableString("genre") ?: json.optJSONArray("genres")?.optString(0)?.trim().takeUnless { it.isNullOrBlank() }
    val mediaKey = json.optNullableString("mediaKey")
    if (mediaKey.isNullOrBlank()) {
        throw IllegalStateException("Backend metadata item is missing a mediaKey.")
    }
    return BackendMetadataItem(
        mediaKey = mediaKey,
        title = title,
        summary = json.optString("summary").trim().ifBlank { null },
        posterUrl = posterUrl,
        backdropUrl = json.optNullableString("backdropUrl") ?: images.backdropUrl,
        logoUrl = json.optNullableString("logoUrl") ?: images.logoUrl,
        mediaType = mediaType,
        rating = json.opt("rating")?.toString()?.trim()?.ifBlank { null },
        year = json.opt("releaseYear")?.toString()?.trim()?.ifBlank { null },
        genre = genre,
        provider = provider,
        providerId = providerId,
    )
}

internal fun CrispyBackendClient.parseMetadataView(json: JSONObject): MetadataView {
    val mediaKey = json.optNullableString("mediaKey")
    if (mediaKey.isNullOrBlank()) {
        throw IllegalStateException("Backend metadata view is missing a mediaKey.")
    }
    return MetadataView(
        mediaKey = mediaKey,
        mediaType = json.optNullableString("mediaType") ?: "movie",
        kind = json.optNullableString("kind") ?: "title",
        tmdbId = json.optIntOrNull("tmdbId"),
        showTmdbId = json.optIntOrNull("showTmdbId"),
        provider = json.optNullableString("provider"),
        providerId = json.opt("providerId")?.toString()?.trim()?.ifBlank { null },
        parentMediaType = json.optNullableString("parentMediaType"),
        parentProvider = json.optNullableString("parentProvider"),
        parentProviderId = json.opt("parentProviderId")?.toString()?.trim()?.ifBlank { null },
        absoluteEpisodeNumber = json.optIntOrNull("absoluteEpisodeNumber"),
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
        mediaType = json.optNullableString("mediaType") ?: "movie",
        kind = json.optNullableString("kind") ?: "title",
        tmdbId = json.optIntOrNull("tmdbId"),
        showTmdbId = json.optIntOrNull("showTmdbId"),
        provider = json.optNullableString("provider"),
        providerId = json.opt("providerId")?.toString()?.trim()?.ifBlank { null },
        parentMediaType = json.optNullableString("parentMediaType"),
        parentProvider = json.optNullableString("parentProvider"),
        parentProviderId = json.opt("parentProviderId")?.toString()?.trim()?.ifBlank { null },
        absoluteEpisodeNumber = json.optIntOrNull("absoluteEpisodeNumber"),
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
        status = json.optNullableString("status"),
    )
}

internal fun CrispyBackendClient.parseMetadataImages(json: JSONObject): MetadataImages {
    val images = json.optJSONObject("images")
    val artwork = json.optJSONObject("artwork")
    return MetadataImages(
        posterUrl = json.optNullableString("posterUrl") ?: images.optNullableString("posterUrl") ?: artwork.optNullableString("posterUrl"),
        backdropUrl = json.optNullableString("backdropUrl") ?: images.optNullableString("backdropUrl") ?: artwork.optNullableString("backdropUrl"),
        stillUrl = json.optNullableString("stillUrl") ?: images.optNullableString("stillUrl") ?: artwork.optNullableString("stillUrl"),
        logoUrl = json.optNullableString("logoUrl") ?: images.optNullableString("logoUrl"),
    )
}

internal fun CrispyBackendClient.parseMetadataExternalIds(json: JSONObject?): MetadataExternalIds {
    val safe = json ?: JSONObject()
    return MetadataExternalIds(
        tmdb = safe.optIntOrNull("tmdb"),
        imdb = safe.optNullableString("imdb"),

    )
}

internal fun CrispyBackendClient.parseMetadataEpisodePreview(json: JSONObject): MetadataEpisodePreview {
    val mediaKey = json.optNullableString("mediaKey")
    if (mediaKey.isNullOrBlank()) {
        throw IllegalStateException("Backend metadata episode preview is missing a mediaKey.")
    }
    return MetadataEpisodePreview(
        mediaKey = mediaKey,
        mediaType = json.optNullableString("mediaType") ?: "episode",
        tmdbId = json.optIntOrNull("tmdbId"),
        showTmdbId = json.optIntOrNull("showTmdbId"),
        provider = json.optNullableString("provider"),
        providerId = json.opt("providerId")?.toString()?.trim()?.ifBlank { null },
        parentMediaType = json.optNullableString("parentMediaType"),
        parentProvider = json.optNullableString("parentProvider"),
        parentProviderId = json.opt("parentProviderId")?.toString()?.trim()?.ifBlank { null },
        absoluteEpisodeNumber = json.optIntOrNull("absoluteEpisodeNumber"),
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
    val mediaKey = json.optNullableString("mediaKey")
    val seasonNumber = json.optIntOrNull("seasonNumber")
    if (mediaKey.isNullOrBlank() || seasonNumber == null) {
        throw IllegalStateException("Backend season view is missing required fields.")
    }
    return MetadataSeasonView(
        mediaKey = mediaKey,
        showTmdbId = json.optIntOrNull("showTmdbId"),
        provider = json.optNullableString("provider"),
        providerId = json.opt("providerId")?.toString()?.trim()?.ifBlank { null },
        parentMediaType = json.optNullableString("parentMediaType"),
        parentProvider = json.optNullableString("parentProvider"),
        parentProviderId = json.opt("parentProviderId")?.toString()?.trim()?.ifBlank { null },
        seasonNumber = seasonNumber,
        title = json.optNullableString("title"),
        summary = json.optNullableString("summary"),
        airDate = json.optNullableString("airDate"),
        episodeCount = json.optIntOrNull("episodeCount"),
        posterUrl = parseMetadataImages(json).posterUrl,
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
        provider = preview.provider,
        providerId = preview.providerId,
        parentMediaType = preview.parentMediaType,
        parentProvider = preview.parentProvider,
        parentProviderId = preview.parentProviderId,
        absoluteEpisodeNumber = preview.absoluteEpisodeNumber,
        seasonNumber = preview.seasonNumber,
        episodeNumber = preview.episodeNumber,
        title = preview.title,
        summary = preview.summary,
        airDate = preview.airDate,
        runtimeMinutes = preview.runtimeMinutes,
        rating = preview.rating,
        images = preview.images,
        showMediaKey = json.optNullableString("showMediaKey") ?: json.optNullableString("showId"),
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
        provider = json.optString("provider").trim(),
        providerId = json.optString("providerId").trim(),
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
                    mediaType = item.optNullableString("mediaType") ?: "movie",
                    tmdbId = item.optIntOrNull("tmdbId"),
                    title = title,
                    posterUrl = item.optNullableString("posterUrl"),
                    rating = item.optDoubleOrNull("rating"),
                    releaseYear = item.optIntOrNull("releaseYear"),
                    provider = item.optNullableString("provider"),
                    providerId = item.opt("providerId")?.toString()?.trim()?.ifBlank { null },
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
            val provider = item.optString("provider").trim()
            val providerId = item.optString("providerId").trim()
            if (id.isBlank() || provider.isBlank() || providerId.isBlank() || name.isBlank()) continue
            add(
                MetadataPersonRefView(
                    id = id,
                    provider = provider,
                    providerId = providerId,
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
            val provider = item.optString("provider").trim()
            val providerId = item.optString("providerId").trim()
            val name = item.optString("name").trim()
            if (id.isBlank() || provider.isBlank() || providerId.isBlank() || name.isBlank()) continue
            add(
                MetadataCompanyView(
                    id = id,
                    provider = provider,
                    providerId = providerId,
                    name = name,
                    logoUrl = item.optNullableString("logoUrl"),
                    originCountry = item.optNullableString("originCountry"),
                )
            )
        }
    }
}

internal fun CrispyBackendClient.parseMetadataCollectionView(json: JSONObject?): MetadataCollectionView? {
    val safe = json ?: return null
    val id = safe.opt("id")?.toString()?.trim().orEmpty()
    val provider = safe.optString("provider").trim()
    val providerId = safe.optString("providerId").trim()
    val name = safe.optString("name").trim()
    if (id.isBlank() || provider.isBlank() || providerId.isBlank() || name.isBlank()) return null
    return MetadataCollectionView(
        id = id,
        provider = provider,
        providerId = providerId,
        name = name,
        posterUrl = safe.optNullableString("posterUrl"),
        backdropUrl = safe.optNullableString("backdropUrl"),
        parts = parseMetadataCardViews(safe.optJSONArray("parts")),
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

internal fun CrispyBackendClient.parseRuntimeMediaCard(
    json: JSONObject,
    requireBackdrop: Boolean,
): RuntimeMediaCard {
    val mediaKey = json.optNullableString("mediaKey")
    val mediaType = json.optString("mediaType").trim()
    val provider = json.optNullableString("provider")?.trim()?.ifBlank { null }
    val providerId = json.opt("providerId")?.toString()?.trim()?.ifBlank { null }
    val title = json.optNullableString("title")
    val posterUrl = json.optNullableString("posterUrl")
        ?: json.optJSONObject("images").optNullableString("posterUrl")
    val backdropUrl = json.optNullableString("backdropUrl")
        ?: json.optJSONObject("images").optNullableString("backdropUrl")
    if (
        mediaKey.isNullOrBlank() ||
        mediaType.isBlank() ||
        provider.isNullOrBlank() ||
        providerId.isNullOrBlank() ||
        title.isNullOrBlank() ||
        posterUrl.isNullOrBlank()
    ) {
        throw IllegalStateException("Runtime media card is missing required fields.")
    }
    if (requireBackdrop && backdropUrl.isNullOrBlank()) {
        throw IllegalStateException("Landscape runtime media card is missing backdropUrl.")
    }
    return RuntimeMediaCard(
        mediaKey = mediaKey,
        mediaType = mediaType,
        provider = provider,
        providerId = providerId,
        title = title,
        posterUrl = posterUrl,
        backdropUrl = backdropUrl,
        subtitle = json.optNullableString("subtitle"),
        releaseYear = json.optIntOrNull("releaseYear"),
        rating = json.optDoubleOrNull("rating"),
        genre = json.optNullableString("genre"),
        seasonNumber = json.optIntOrNull("seasonNumber"),
        episodeNumber = json.optIntOrNull("episodeNumber"),
        episodeTitle = json.optNullableString("episodeTitle"),
        airDate = json.optNullableString("airDate"),
        runtimeMinutes = json.optIntOrNull("runtimeMinutes"),
    )
}

internal fun CrispyBackendClient.parseContinueWatchingItem(json: JSONObject): ContinueWatchingItem {
    val id = json.optString("id").trim()
    if (id.isBlank()) {
        throw IllegalStateException("Continue watching item is missing id.")
    }
    val mediaJson = json.optJSONObject("media")
        ?: throw IllegalStateException("Continue watching item is missing media.")
    return ContinueWatchingItem(
        id = id,
        media = parseRuntimeMediaCard(mediaJson, requireBackdrop = true),
        progress = parseWatchProgressView(json.optJSONObject("progress")),
        lastActivityAt = json.optString("lastActivityAt").trim(),
        origins = parseOrigins(json.optJSONArray("origins")),
        dismissible = json.optBoolean("dismissible", false),
    )
}

internal fun CrispyBackendClient.parseWatchedItems(array: JSONArray?): List<WatchedItem> {
    val safeArray = array ?: JSONArray()
    return buildList {
        for (index in 0 until safeArray.length()) {
            val item = safeArray.optJSONObject(index) ?: continue
            add(parseWatchedItem(item))
        }
    }
}

internal fun CrispyBackendClient.parseWatchedItem(json: JSONObject): WatchedItem {
    val mediaJson = json.optJSONObject("media")
    if (mediaJson == null) {
        throw IllegalStateException("Watched item is missing media.")
    }
    return WatchedItem(
        id = json.optNullableString("id"),
        media = parseRuntimeMediaCard(mediaJson, requireBackdrop = false),
        watchedAt = json.optNullableString("watchedAt"),
        lastActivityAt = json.optNullableString("lastActivityAt"),
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
    val mediaJson = json.optJSONObject("media")
    if (mediaJson == null) {
        throw IllegalStateException("Watchlist item is missing media.")
    }
    return WatchlistItem(
        id = json.optNullableString("id"),
        media = parseRuntimeMediaCard(mediaJson, requireBackdrop = false),
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
    val mediaJson = json.optJSONObject("media")
    val ratingJson = json.optJSONObject("rating")
    val rating = parseRatingStateView(ratingJson)
    if (mediaJson == null || rating == null) {
        throw IllegalStateException("Rating item is missing required fields.")
    }
    return RatingItem(
        id = json.optNullableString("id"),
        media = parseRuntimeMediaCard(mediaJson, requireBackdrop = false),
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
        media = parseMetadataView(json.optJSONObject("media") ?: throw IllegalStateException("Backend watch state is missing media.")),
        progress = parseWatchProgressView(json.optJSONObject("progress")),
        continueWatching = parseContinueWatchingStateView(json.optJSONObject("continueWatching")),
        watched = parseWatchedStateView(json.optJSONObject("watched")),
        watchlist = parseWatchlistStateView(json.optJSONObject("watchlist")),
        rating = parseRatingStateView(json.optJSONObject("rating")),
        watchedEpisodeKeys = json.optStringList("watchedEpisodeKeys"),
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
            val mediaJson = item.optJSONObject("media") ?: continue
            val relatedShowJson = item.optJSONObject("relatedShow") ?: continue
            add(
                CalendarItem(
                    bucket = item.optString("bucket").trim(),
                    media = parseRuntimeMediaCard(mediaJson, requireBackdrop = false),
                    relatedShow = parseRuntimeMediaCard(relatedShowJson, requireBackdrop = false),
                    airDate = item.optNullableString("airDate"),
                    watched = item.optBoolean("watched", false),
                )
            )
        }
    }
}

internal fun CrispyBackendClient.parseRecommendationItems(array: JSONArray?, requireBackdrop: Boolean): List<RecommendationItem> {
    val safeArray = array ?: JSONArray()
    return buildList {
        for (index in 0 until safeArray.length()) {
            val item = safeArray.optJSONObject(index) ?: continue
            val mediaJson = item.optJSONObject("media") ?: continue
            add(
                RecommendationItem(
                    media = parseRuntimeMediaCard(mediaJson, requireBackdrop = requireBackdrop),
                    reason = item.optNullableString("reason"),
                    score = item.optDoubleOrNull("score"),
                    rank = item.optDoubleOrNull("rank") ?: index.toDouble(),
                    payload = item.optJSONObject("payload").toStringMap(),
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
            val provider = item.optString("provider").trim().ifBlank { continue }
            val providerId = item.optString("providerId").trim().ifBlank { continue }
            val title = item.optString("title").trim().ifBlank { continue }
            val description = item.optString("description").trim().ifBlank { continue }
            val backdropUrl = item.optString("backdropUrl").trim().ifBlank { continue }
            add(
                RecommendationHeroItem(
                    mediaKey = mediaKey,
                    mediaType = mediaType,
                    provider = provider,
                    providerId = providerId,
                    title = title,
                    description = description,
                    backdropUrl = backdropUrl,
                    posterUrl = item.optNullableString("posterUrl"),
                    logoUrl = item.optNullableString("logoUrl"),
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
            val logoUrl = item.optString("logoUrl").trim().ifBlank { continue }
            val items = buildList {
                val parts = item.optJSONArray("items") ?: JSONArray()
                for (partIndex in 0 until parts.length()) {
                    val part = parts.optJSONObject(partIndex) ?: continue
                    val mediaType = part.optString("mediaType").trim().ifBlank { continue }
                    val provider = part.optString("provider").trim().ifBlank { continue }
                    val providerId = part.optString("providerId").trim().ifBlank { continue }
                    val partTitle = part.optString("title").trim().ifBlank { continue }
                    val posterUrl = part.optString("posterUrl").trim().ifBlank { continue }
                    add(
                        RecommendationCollectionItem(
                            mediaType = mediaType,
                            provider = provider,
                            providerId = providerId,
                            title = partTitle,
                            posterUrl = posterUrl,
                            releaseYear = part.optIntOrNull("releaseYear"),
                            rating = part.optDoubleOrNull("rating"),
                        )
                    )
                }
            }
            if (items.isNotEmpty()) {
                add(RecommendationCollectionCard(title = title, logoUrl = logoUrl, items = items))
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
                        "regular" -> parseRecommendationItems(items, requireBackdrop = false)
                        "landscape" -> parseRecommendationItems(items, requireBackdrop = true)
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
