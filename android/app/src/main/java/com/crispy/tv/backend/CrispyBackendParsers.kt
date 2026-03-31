package com.crispy.tv.backend

import com.crispy.tv.backend.CrispyBackendClient.AiInsightsCard
import com.crispy.tv.backend.CrispyBackendClient.BackendMetadataItem
import com.crispy.tv.backend.CrispyBackendClient.CalendarItem
import com.crispy.tv.backend.CrispyBackendClient.CanonicalLibrary
import com.crispy.tv.backend.CrispyBackendClient.CanonicalLibraryItem
import com.crispy.tv.backend.CrispyBackendClient.CanonicalWatchCollectionResponse
import com.crispy.tv.backend.CrispyBackendClient.ContinueWatchingStateView
import com.crispy.tv.backend.CrispyBackendClient.HomeSection
import com.crispy.tv.backend.CrispyBackendClient.HomeCalendarSection
import com.crispy.tv.backend.CrispyBackendClient.HomeRecommendationItem
import com.crispy.tv.backend.CrispyBackendClient.HomeRecommendationSection
import com.crispy.tv.backend.CrispyBackendClient.HomeWatchSection
import com.crispy.tv.backend.CrispyBackendClient.DetailsTarget
import com.crispy.tv.backend.CrispyBackendClient.EpisodeContext
import com.crispy.tv.backend.CrispyBackendClient.ImportConnection
import com.crispy.tv.backend.CrispyBackendClient.ImportJob
import com.crispy.tv.backend.CrispyBackendClient.LibrarySection
import com.crispy.tv.backend.CrispyBackendClient.LibrarySectionItem
import com.crispy.tv.backend.CrispyBackendClient.PlaybackTarget
import com.crispy.tv.backend.CrispyBackendClient.RatingItem
import com.crispy.tv.backend.CrispyBackendClient.WatchDerivedItem
import com.crispy.tv.backend.CrispyBackendClient.WatchlistItem
import com.crispy.tv.backend.CrispyBackendClient.LibraryMutationResponse
import com.crispy.tv.backend.CrispyBackendClient.LibraryAuth
import com.crispy.tv.backend.CrispyBackendClient.LibraryDiagnostics
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
import com.crispy.tv.backend.CrispyBackendClient.MetadataTitleContentResponse
import com.crispy.tv.backend.CrispyBackendClient.MetadataVideoView
import com.crispy.tv.backend.CrispyBackendClient.MetadataView
import com.crispy.tv.backend.CrispyBackendClient.NativeLibrary
import com.crispy.tv.backend.CrispyBackendClient.OmdbContentView
import com.crispy.tv.backend.CrispyBackendClient.OmdbRatingEntry
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
    val id = json.optString("id").trim()
    if (id.isBlank()) {
        throw IllegalStateException("Backend metadata card view is missing an id.")
    }
    val mediaKey = json.optString("mediaKey").trim()
    if (mediaKey.isBlank()) {
        throw IllegalStateException("Backend metadata card view is missing a mediaKey.")
    }
    return MetadataCardView(
        id = id,
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
        status = json.optNullableString("status"),
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
        kitsu = safe.optIntOrNull("kitsu"),
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
            if (id.isBlank() || tmdbPersonId == null || name.isBlank()) continue
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
            val id = item.optIntOrNull("id") ?: continue
            val name = item.optString("name").trim()
            if (name.isBlank()) continue
            add(
                MetadataCompanyView(
                    id = id,
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
    val id = safe.optIntOrNull("id") ?: return null
    val name = safe.optString("name").trim()
    if (name.isBlank()) return null
    return MetadataCollectionView(
        id = id,
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

internal fun CrispyBackendClient.parseOmdbRatingEntries(array: JSONArray?): List<OmdbRatingEntry> {
    val safeArray = array ?: JSONArray()
    return buildList {
        for (index in 0 until safeArray.length()) {
            val item = safeArray.optJSONObject(index) ?: continue
            val source = item.optString("source").trim()
            val value = item.optString("value").trim()
            if (source.isBlank() || value.isBlank()) continue
            add(OmdbRatingEntry(source = source, value = value))
        }
    }
}

internal fun CrispyBackendClient.parseOmdbContentView(json: JSONObject): OmdbContentView {
    val imdbId = json.optString("imdbId").trim()
    if (imdbId.isBlank()) {
        throw IllegalStateException("Backend OMDb content is missing imdbId.")
    }
    return OmdbContentView(
        imdbId = imdbId,
        title = json.optNullableString("title"),
        type = json.optNullableString("type"),
        year = json.optNullableString("year"),
        rated = json.optNullableString("rated"),
        released = json.optNullableString("released"),
        runtime = json.optNullableString("runtime"),
        genres = json.optStringList("genres"),
        directors = json.optStringList("directors"),
        writers = json.optStringList("writers"),
        actors = json.optStringList("actors"),
        plot = json.optNullableString("plot"),
        language = json.optNullableString("language"),
        country = json.optNullableString("country"),
        awards = json.optNullableString("awards"),
        posterUrl = json.optNullableString("posterUrl"),
        ratings = parseOmdbRatingEntries(json.optJSONArray("ratings")),
        metascore = json.optNullableString("metascore"),
        imdbRating = json.optNullableString("imdbRating"),
        imdbVotes = json.optNullableString("imdbVotes"),
        boxOffice = json.optNullableString("boxOffice"),
        production = json.optNullableString("production"),
        website = json.optNullableString("website"),
        totalSeasons = json.optNullableString("totalSeasons"),
        response = json.optNullableString("response"),
        error = json.optNullableString("error"),
    )
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

internal fun CrispyBackendClient.parseDetailsTarget(json: JSONObject): DetailsTarget {
    val titleId = json.optString("id").trim()
    if (titleId.isBlank()) {
        throw IllegalStateException("Contract detailsTarget is missing id.")
    }
    return DetailsTarget(
        titleId = titleId,
        titleMediaType = json.optString("mediaType").trim().ifBlank { "movie" },
    )
}

internal fun CrispyBackendClient.parsePlaybackTarget(json: JSONObject): PlaybackTarget {
    val contentId = json.optString("contentId").trim()
    if (contentId.isBlank()) {
        throw IllegalStateException("Contract playbackTarget is missing contentId.")
    }
    return PlaybackTarget(
        contentId = contentId,
        mediaType = json.optString("mediaType").trim().ifBlank { "movie" },
        seasonNumber = json.optIntOrNull("seasonNumber"),
        episodeNumber = json.optIntOrNull("episodeNumber"),
        provider = json.optNullableString("provider"),
        providerId = json.opt("providerId")?.toString()?.trim()?.ifBlank { null },
        parentMediaType = json.optNullableString("parentMediaType"),
        parentProvider = json.optNullableString("parentProvider"),
        parentProviderId = json.opt("parentProviderId")?.toString()?.trim()?.ifBlank { null },
    )
}

internal fun CrispyBackendClient.parseEpisodeContext(json: JSONObject?): EpisodeContext? {
    val safe = json ?: return null
    val title = safe.optNullableString("title")
    val season = safe.optIntOrNull("seasonNumber")
    val episode = safe.optIntOrNull("episodeNumber")
    if (title.isNullOrBlank() && season == null && episode == null) return null
    return EpisodeContext(
        title = title,
        seasonNumber = season,
        episodeNumber = episode,
    )
}

internal fun CrispyBackendClient.parseWatchDerivedItems(array: JSONArray?): List<WatchDerivedItem> {
    val safeArray = array ?: JSONArray()
    return buildList {
        for (index in 0 until safeArray.length()) {
            val item = safeArray.optJSONObject(index) ?: continue
            add(parseWatchDerivedItem(item))
        }
    }
}

internal fun CrispyBackendClient.parseWatchDerivedItem(json: JSONObject): WatchDerivedItem {
    val id = json.optString("id").trim()
    if (id.isBlank()) {
        throw IllegalStateException("Contract watch-derived item is missing id.")
    }
    val detailsTargetJson = json.optJSONObject("detailsTarget")
        ?: throw IllegalStateException("Contract watch-derived item is missing detailsTarget.")
    val playbackTargetJson = json.optJSONObject("playbackTarget")
    val episodeContextJson = json.optJSONObject("episodeContext")
    val mediaJson = json.optJSONObject("media")
        ?: throw IllegalStateException("Contract watch-derived item is missing media.")
    return WatchDerivedItem(
        id = id,
        detailsTarget = parseDetailsTarget(detailsTargetJson),
        playbackTarget = playbackTargetJson?.let(::parsePlaybackTarget),
        episodeContext = parseEpisodeContext(episodeContextJson),
        media = parseMetadataView(mediaJson),
        progress = parseWatchProgressView(json.optJSONObject("progress")),
        watchedAt = json.optNullableString("watchedAt"),
        lastActivityAt = json.optNullableString("lastActivityAt"),
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
    val id = json.optString("id").trim()
    val detailsTargetJson = json.optJSONObject("detailsTarget")
    val mediaJson = json.optJSONObject("media")
    if (id.isBlank() || detailsTargetJson == null || mediaJson == null) {
        throw IllegalStateException("Contract watchlist item is missing required fields.")
    }
    return WatchlistItem(
        id = id,
        detailsTarget = parseDetailsTarget(detailsTargetJson),
        playbackTarget = json.optJSONObject("playbackTarget")?.let(::parsePlaybackTarget),
        media = parseMetadataView(mediaJson),
        addedAt = json.optString("addedAt").trim(),
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
    val id = json.optString("id").trim()
    val detailsTargetJson = json.optJSONObject("detailsTarget")
    val mediaJson = json.optJSONObject("media")
    val value = json.optIntOrNull("value")
    val ratedAt = json.optNullableString("ratedAt")
    if (id.isBlank() || detailsTargetJson == null || mediaJson == null || value == null || ratedAt.isNullOrBlank()) {
        throw IllegalStateException("Contract rating item is missing required fields.")
    }
    return RatingItem(
        id = id,
        detailsTarget = parseDetailsTarget(detailsTargetJson),
        playbackTarget = json.optJSONObject("playbackTarget")?.let(::parsePlaybackTarget),
        media = parseMetadataView(mediaJson),
        value = value,
        ratedAt = ratedAt,
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

internal fun CrispyBackendClient.parseHydratedWatchCollectionResponse(
    json: JSONObject,
): CanonicalWatchCollectionResponse<WatchDerivedItem> {
    return CanonicalWatchCollectionResponse(
        profileId = json.optString("profileId").trim(),
        kind = json.optString("kind").trim(),
        source = json.optString("source").trim(),
        generatedAt = json.optNullableString("generatedAt"),
        items = parseWatchDerivedItems(json.optJSONArray("items")),
    )
}

internal fun CrispyBackendClient.parseHydratedWatchlistCollectionResponse(
    json: JSONObject,
): CanonicalWatchCollectionResponse<WatchlistItem> {
    return CanonicalWatchCollectionResponse(
        profileId = json.optString("profileId").trim(),
        kind = json.optString("kind").trim(),
        source = json.optString("source").trim(),
        generatedAt = json.optNullableString("generatedAt"),
        items = parseWatchlistItems(json.optJSONArray("items")),
    )
}

internal fun CrispyBackendClient.parseHydratedRatingCollectionResponse(
    json: JSONObject,
): CanonicalWatchCollectionResponse<RatingItem> {
    return CanonicalWatchCollectionResponse(
        profileId = json.optString("profileId").trim(),
        kind = json.optString("kind").trim(),
        source = json.optString("source").trim(),
        generatedAt = json.optNullableString("generatedAt"),
        items = parseRatingItems(json.optJSONArray("items")),
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
            val kind = section.optString("kind").trim().ifBlank { "watch" }
            val id = section.optString("id").trim()
            val title = section.optString("title").trim()
            val source = section.optString("source").trim()
            add(
                when (kind) {
                    "calendar" -> HomeCalendarSection(
                        id = id,
                        title = title,
                        source = source.ifBlank { "canonical_calendar" },
                        items = parseCalendarItems(items),
                    )

                    "recommendation" -> HomeRecommendationSection(
                        id = id,
                        title = title,
                        source = source.ifBlank { "recommendation" },
                        items = parseHomeRecommendationItems(items),
                        meta = section.optJSONObject("meta").toAnyMap(),
                    )

                    else -> HomeWatchSection(
                        id = id,
                        title = title,
                        source = source.ifBlank { "canonical_watch" },
                        items = parseWatchDerivedItems(items),
                    )
                }
            )
        }
    }
}

internal fun CrispyBackendClient.parseHomeRecommendationItems(array: JSONArray?): List<HomeRecommendationItem> {
    val safeArray = array ?: JSONArray()
    return buildList {
        for (index in 0 until safeArray.length()) {
            val item = safeArray.optJSONObject(index) ?: continue
            val mediaJson = item.optJSONObject("media") ?: continue
            add(
                HomeRecommendationItem(
                    media = parseMetadataView(mediaJson),
                    reason = item.optNullableString("reason"),
                    score = item.optDoubleOrNull("score"),
                    rank = item.optIntOrNull("rank"),
                    payload = item.optJSONObject("payload").toAnyMap(),
                )
            )
        }
    }
}

internal fun CrispyBackendClient.parseNativeLibrary(json: JSONObject): NativeLibrary {
    return NativeLibrary(
        continueWatching = parseWatchDerivedItems(json.optJSONArray("continueWatching")),
        history = parseWatchDerivedItems(json.optJSONArray("history")),
        watchlist = parseWatchlistItems(json.optJSONArray("watchlist")),
        ratings = parseRatingItems(json.optJSONArray("ratings")),
    )
}

internal fun CrispyBackendClient.parseCanonicalLibrary(json: JSONObject): CanonicalLibrary {
    return CanonicalLibrary(
        source = json.optString("source").trim(),
        generatedAt = json.optNullableString("generatedAt"),
        continueWatching = parseWatchDerivedItems(json.optJSONArray("continueWatching")),
        history = parseWatchDerivedItems(json.optJSONArray("history")),
        watchlist = parseWatchlistItems(json.optJSONArray("watchlist")),
        ratings = parseRatingItems(json.optJSONArray("ratings")),
        items = parseCanonicalLibraryItems(json.optJSONArray("items")),
    )
}

internal fun CrispyBackendClient.parseCanonicalLibraryItems(array: JSONArray?): List<CanonicalLibraryItem> {
    val safeArray = array ?: JSONArray()
    return buildList {
        for (index in 0 until safeArray.length()) {
            val item = safeArray.optJSONObject(index) ?: continue
            val key = item.optString("key").trim()
            val contentId = item.optString("contentId").trim()
            val contentType = item.optString("contentType").trim()
            val title = item.optString("title").trim()
            val addedAt = item.optString("addedAt").trim()
            if (key.isBlank() || contentId.isBlank() || contentType.isBlank() || title.isBlank() || addedAt.isBlank()) continue
            add(
                CanonicalLibraryItem(
                    key = key,
                    mediaKey = item.optNullableString("mediaKey"),
                    contentId = contentId,
                    contentType = contentType,
                    externalIds = item.optJSONObject("externalIds")?.let(::parseMetadataExternalIds),
                    title = title,
                    posterUrl = item.optNullableString("posterUrl"),
                    backdropUrl = item.optNullableString("backdropUrl"),
                    seasonNumber = item.optIntOrNull("seasonNumber"),
                    episodeNumber = item.optIntOrNull("episodeNumber"),
                    addedAt = addedAt,
                    providers = item.optStringList("providers"),
                    folderIds = item.optStringList("folderIds"),
                    media = item.optJSONObject("media")?.let(::parseMetadataView),
                )
            )
        }
    }
}

internal fun CrispyBackendClient.parseLibrarySections(array: JSONArray?): List<LibrarySection> {
    val safeArray = array ?: JSONArray()
    return buildList {
        for (index in 0 until safeArray.length()) {
            val section = safeArray.optJSONObject(index) ?: continue
            val sectionId = section.optString("id").trim()
            val label = section.optString("label").trim()
            if (sectionId.isBlank()) continue
            add(
                LibrarySection(
                    id = sectionId,
                    label = label.ifBlank { sectionId },
                    itemCount = section.optInt("itemCount"),
                    items = parseLibrarySectionItems(section.optJSONArray("items")),
                )
            )
        }
    }
}

internal fun CrispyBackendClient.parseLibrarySectionItems(array: JSONArray?): List<LibrarySectionItem> {
    val safeArray = array ?: JSONArray()
    return buildList {
        for (index in 0 until safeArray.length()) {
            val item = safeArray.optJSONObject(index) ?: continue
            val id = item.optString("id").trim()
            val detailsTargetJson = item.optJSONObject("detailsTarget")
            val mediaJson = item.optJSONObject("media")
            if (id.isBlank() || detailsTargetJson == null || mediaJson == null) continue
            add(
                LibrarySectionItem(
                    id = id,
                    detailsTarget = parseDetailsTarget(detailsTargetJson),
                    playbackTarget = item.optJSONObject("playbackTarget")?.let(::parsePlaybackTarget),
                    episodeContext = parseEpisodeContext(item.optJSONObject("episodeContext")),
                    media = parseMetadataView(mediaJson),
                    progress = parseWatchProgressView(item.optJSONObject("progress")),
                    rating = parseRatingStateView(item.optJSONObject("rating")),
                    watchedAt = item.optNullableString("watchedAt"),
                    addedAt = item.optNullableString("addedAt"),
                )
            )
        }
    }
}

internal fun CrispyBackendClient.parseLibraryAuth(json: JSONObject?): LibraryAuth {
    val safe = json ?: JSONObject()
    return LibraryAuth(providers = parseProviderAuthStates(safe.optJSONArray("providers")))
}

internal fun CrispyBackendClient.parseLibraryDiagnostics(json: JSONObject?): LibraryDiagnostics {
    val safe = json ?: JSONObject()
    return LibraryDiagnostics(
        source = safe.optString("source").trim(),
        generatedAt = safe.optNullableString("generatedAt"),
        providers = parseProviderLibrarySnapshots(safe.optJSONArray("providers")),
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
                    externalIds = item.optJSONObject("externalIds")?.let(::parseMetadataExternalIds),
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
