package com.crispy.tv.backend

import com.crispy.tv.domain.account.builtInAvatarUrl
import com.crispy.tv.ai.AiInsightSlide
import com.crispy.tv.ai.AiInsightSlideKey
import com.crispy.tv.ai.AiInsightSlideKind
import com.crispy.tv.ai.AiInsightStandoutTag
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import com.crispy.tv.images.ResponsiveImageSet

internal fun CrispyBackendClient.parseUser(json: JsonObject): User {
    val id = json.optStringOrEmpty("id").trim()
    if (id.isBlank()) {
        throw IllegalStateException("Backend user is missing an id.")
    }
    return User(
        id = id,
        email = json.optStringOrEmpty("email").trim().ifBlank { null },
    )
}

internal fun CrispyBackendClient.parseProfiles(array: JsonArray?): List<Profile> {
    val safeArray = array ?: JsonArray(emptyList())
    return buildList {
        for (index in 0 until safeArray.size) {
            val profile = safeArray.getOrNull(index) as? JsonObject ?: continue
            add(parseProfile(profile))
        }
    }
}

internal fun CrispyBackendClient.parseProfile(json: JsonObject): Profile {
    val id = json.optStringOrEmpty("id").trim()
    val name = json.optStringOrEmpty("name").trim()
    if (id.isBlank() || name.isBlank()) {
        throw IllegalStateException("Backend profile is missing required fields.")
    }
    return Profile(
        id = id,
        name = name,
        avatarKey = json.optStringOrEmpty("avatarUrl").trim().ifBlank { null },
        isKids = json.jsonPrimitiveOrNull("isKids")?.booleanOrNull ?: false,
        sortOrder = json.jsonPrimitiveOrNull("sortOrder")?.intOrNull ?: 0,
        createdByUserId = json.optStringOrEmpty("createdByUserId").trim().ifBlank { null },
        createdAt = json.optStringOrEmpty("createdAt").trim().ifBlank { null },
        updatedAt = json.optStringOrEmpty("updatedAt").trim().ifBlank { null },
    )
}

internal fun CrispyBackendClient.parseProviderStates(array: JsonArray?): List<ProviderState> {
    val safeArray = array ?: JsonArray(emptyList())
    return buildList {
        for (index in 0 until safeArray.size) {
            val providerState = safeArray.getOrNull(index) as? JsonObject ?: continue
            add(parseProviderState(providerState))
        }
    }
}

internal fun CrispyBackendClient.parseProviderState(json: JsonObject): ProviderState {
    return ProviderState(
        provider = json.optStringOrEmpty("provider").trim(),
        connectionState = json.optStringOrEmpty("connectionState").trim(),
        accountStatus = json.optStringOrEmpty("accountStatus").trim().ifBlank { null },
        primaryAction = json.optStringOrEmpty("primaryAction").trim(),
        canImport = json.jsonPrimitiveOrNull("canImport")?.booleanOrNull ?: false,
        canReconnect = json.jsonPrimitiveOrNull("canReconnect")?.booleanOrNull ?: false,
        canDisconnect = json.jsonPrimitiveOrNull("canDisconnect")?.booleanOrNull ?: false,
        externalUsername = json.optStringOrEmpty("externalUsername").trim().ifBlank { null },
        statusLabel = json.optStringOrEmpty("statusLabel").trim(),
        statusMessage = json.optStringOrEmpty("statusMessage").trim().ifBlank { null },
        lastImportCompletedAt = json.optStringOrEmpty("lastImportCompletedAt").trim().ifBlank { null },
    )
}

internal fun CrispyBackendClient.parseImportJobs(array: JsonArray?): List<ImportJob> {
    val safeArray = array ?: JsonArray(emptyList())
    return buildList {
        for (index in 0 until safeArray.size) {
            val job = safeArray.getOrNull(index) as? JsonObject ?: continue
            add(parseImportJob(job))
        }
    }
}

internal fun CrispyBackendClient.parseImportJob(json: JsonObject): ImportJob {
    return ImportJob(
        id = json.optStringOrEmpty("id").trim(),
        profileId = json.optStringOrEmpty("profileId").trim(),
        provider = json.optStringOrEmpty("provider").trim(),
        mode = json.optStringOrEmpty("mode").trim(),
        status = json.optStringOrEmpty("status").trim(),
        requestedByUserId = json.optStringOrEmpty("requestedByUserId").trim(),
        errorMessage = json.optJsonObject("errorJson")?.optStringOrEmpty("message")?.trim().orEmpty().ifBlank { null },
        createdAt = json.optStringOrEmpty("createdAt").trim().ifBlank { null },
        startedAt = json.optStringOrEmpty("startedAt").trim().ifBlank { null },
        finishedAt = json.optStringOrEmpty("finishedAt").trim().ifBlank { null },
        updatedAt = json.optStringOrEmpty("updatedAt").trim().ifBlank { null },
    )
}

internal fun CrispyBackendClient.parseAccountSettings(json: JsonObject): AccountSettings {
    val settings = json.optJsonObject("settings") ?: JsonObject(emptyMap())
    val metadata = settings.optJsonObject("metadata") ?: JsonObject(emptyMap())
    return AccountSettings(
        pricingTier = settings.optStringOrEmpty("pricingTier").trim().ifBlank { null },
        hasMdbListAccess = metadata.jsonPrimitiveOrNull("hasMdbListAccess")?.booleanOrNull ?: false,
    )
}

internal fun CrispyBackendClient.parseAddons(value: Any?): List<AddonDto> {
    val safeArray = when (value) {
        is JsonArray -> value
        is List<*> -> JsonArray(value.map { it.toJsonValue() })
        else -> null
    } ?: return emptyList()
    return buildList {
        for (index in 0 until safeArray.size) {
            val obj = safeArray.getOrNull(index) as? JsonObject ?: continue
            parseAddonDto(obj)?.let(::add)
        }
    }
}

internal fun CrispyBackendClient.parseAddon(value: Any?): AddonDto? {
    val obj = when (value) {
        is JsonObject -> value
        else -> null
    } ?: return null
    return parseAddonDto(obj)
}

private fun parseAddonDto(obj: JsonObject): AddonDto? {
    val id = obj.optStringOrEmpty("id").trim()
    val manifestUrl = obj.optStringOrEmpty("manifestUrl").trim()
    if (id.isBlank() || manifestUrl.isBlank()) return null
    val payload = obj.optJsonObject("payload")
    return AddonDto(
        id = id,
        manifestUrl = manifestUrl,
        createdAt = obj.optStringOrEmpty("createdAt").trim(),
        type = obj.optStringOrEmpty("type").trim().ifBlank { "stremio" },
        payload = payload?.let { json ->
            buildMap {
                for ((key, element) in json) {
                    (element as? JsonPrimitive)?.contentOrNull
                        ?.takeIf { it.isNotBlank() }
                        ?.let { put(key, it) }
                }
            }
        } ?: emptyMap(),
    )
}

internal fun CrispyBackendClient.parseAvatars(array: JsonArray?): List<Avatar> {
    val safeArray = array ?: JsonArray(emptyList())
    return buildList {
        for (index in 0 until safeArray.size) {
            val obj = safeArray.getOrNull(index) as? JsonObject ?: continue
            val id = obj.optStringOrEmpty("id").trim()
            if (id.isBlank()) continue
            add(
                Avatar(
                    id = id,
                    url = obj.optStringOrEmpty("url").trim().ifBlank { null }
                        ?: com.crispy.tv.domain.account.builtInAvatarUrl(baseUrl, id),
                ),
            )
        }
    }
}

// --- Search parsers ---

internal fun CrispyBackendClient.parsePersonSearchResultItems(array: JsonArray?): List<PersonSearchResultItem> {
    val safeArray = array ?: JsonArray(emptyList())
    return buildList {
        for (index in 0 until safeArray.size) {
            val item = safeArray.getOrNull(index) as? JsonObject ?: continue
            add(parsePersonSearchResultItem(item))
        }
    }
}

internal fun CrispyBackendClient.parsePersonSearchResultItem(json: JsonObject): PersonSearchResultItem {
    val personId = json.optNullableString("personId")
    val name = json.optStringOrEmpty("name").trim()
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

internal fun CrispyBackendClient.parseSearchResultsResponse(json: JsonObject): SearchResultsResponse {
    return SearchResultsResponse(
        query = json.optStringOrEmpty("query").trim(),
        movies = parseClientMediaCards(json.optJsonArray("movies")),
        series = parseClientMediaCards(json.optJsonArray("series")),
        people = parsePersonSearchResultItems(json.optJsonArray("people")),
    )
}

internal fun CrispyBackendClient.parseSearchSuggestionsResponse(json: JsonObject): SearchSuggestionsResponse {
    return SearchSuggestionsResponse(suggestions = json.optStringList("suggestions"))
}

internal fun CrispyBackendClient.parseBrowseTitlesResponse(json: JsonObject): BrowseTitlesResponse {
    val itemsArray = json.optJsonArray("items")
    val total = json.optIntOrNull("total")
    val hasMore = json.jsonPrimitiveOrNull("hasMore")?.booleanOrNull ?: false
    return BrowseTitlesResponse(
        items = parseClientMediaCards(itemsArray),
        total = total,
        hasMore = hasMore,
    )
}

internal fun CrispyBackendClient.parseProviderIds(json: JsonObject?): MediaExternalIds {
    val safe = json ?: JsonObject(emptyMap())
    val tmdb = safe.optNullableString("tmdb")?.trim()?.toIntOrNull() ?: safe.optNullableString("Tmdb")?.trim()?.toIntOrNull()
    val imdb = safe.optNullableString("imdb")?.trim()?.ifBlank { null } ?: safe.optNullableString("Imdb")?.trim()?.ifBlank { null }
    val tvdb = safe.optNullableString("tvdb")?.trim()?.toIntOrNull() ?: safe.optNullableString("Tvdb")?.trim()?.toIntOrNull()
    return MediaExternalIds(
        tmdb = tmdb,
        imdb = imdb,
        tvdb = tvdb,
    )
}

private fun parseBackdropImageUrl(imageTags: JsonObject?): ResponsiveImageSet {
    if (imageTags == null) return ResponsiveImageSet(null, null, null)
    val arr = imageTags.optJsonArray("Backdrop")
    if (arr != null && arr.size > 0) {
        val first = arr.getOrNull(0) as? JsonObject
        if (first != null) {
            return parseResponsiveImageSet(first)
        }
    }
    return ResponsiveImageSet(null, null, null)
}

private fun parseResponsiveImageSet(json: JsonObject?): ResponsiveImageSet {
    return ResponsiveImageSet(
        low = json.optNullableString("small"),
        medium = json.optNullableString("medium"),
        high = json.optNullableString("large"),
    )
}

// --- Home parsers ---

internal fun CrispyBackendClient.parseProfileHomeSections(array: JsonArray?): List<ProfileHomeSection> {
    val safeArray = array ?: JsonArray(emptyList())
    return buildList {
        for (index in 0 until safeArray.size) {
            val section = safeArray.getOrNull(index) as? JsonObject ?: continue
            add(parseProfileHomeSection(section))
        }
    }
}

internal fun CrispyBackendClient.parseProfileHomeSection(json: JsonObject): ProfileHomeSection {
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
        items = parseClientMediaCards(json.optJsonArray("items")),
        meta = json.optJsonObject("meta")?.toStringMap() ?: emptyMap(),
    )
}

internal fun CrispyBackendClient.parseClientMediaCards(array: JsonArray?): List<ClientMediaCard> {
    val safeArray = array ?: JsonArray(emptyList())
    return buildList {
        for (index in 0 until safeArray.size) {
            val item = safeArray.getOrNull(index) as? JsonObject ?: continue
            add(parseClientMediaCard(item))
        }
    }
}

internal fun CrispyBackendClient.parseClientMediaCard(json: JsonObject): ClientMediaCard {
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
        tagline = json.optNullableString("tagline"),
        year = json.optIntOrNull("year"),
        releaseDate = json.optNullableString("releaseDate"),
        rating = json.optDoubleOrNull("rating"),
        maturityRating = json.optNullableString("maturityRating"),
        genres = json.optStringList("genres"),
        runtimeSeconds = json.optIntOrNull("runtimeSeconds"),
        images = parseClientImages(json.optJsonObject("images")),
        trailerUrl = json.optNullableString("trailerUrl"),
        progress = parseClientProgress(json.optJsonObject("progress")),
        parent = parseClientParentRef(json.optJsonObject("parent")),
        providerIds = parseClientProviderIds(json.optJsonObject("providerIds")),
    )
}

internal fun CrispyBackendClient.parseClientProviderIds(json: JsonObject?): MediaExternalIds {
    val safe = json ?: JsonObject(emptyMap())
    return MediaExternalIds(
        tmdb = safe.optStringOrEmpty("tmdb").toIntOrNull(),
        imdb = safe.optStringOrEmpty("imdb").ifBlank { null },
        tvdb = safe.optStringOrEmpty("tvdb").toIntOrNull(),
    )
}

internal fun CrispyBackendClient.parseClientImages(json: JsonObject?): ClientImages {
    return ClientImages(
        artwork = parseResponsiveImageSet(json?.optJsonObject("artwork")),
        logo = parseResponsiveImageSet(json?.optJsonObject("logo")),
        still = parseResponsiveImageSet(json?.optJsonObject("still")),
    )
}

internal fun CrispyBackendClient.parseClientProgress(json: JsonObject?): ClientProgress? {
    val safe = json ?: return null
    if (safe.size == 0) return null
    return ClientProgress(
        played = safe.jsonPrimitiveOrNull("played")?.booleanOrNull ?: false,
        playCount = safe.optIntOrNull("playCount") ?: 0,
        positionSeconds = safe.optIntOrNull("positionSeconds"),
        durationSeconds = safe.optIntOrNull("durationSeconds"),
        percent = safe.optDoubleOrNull("percent"),
        lastPlayedAt = safe.optNullableString("lastPlayedAt"),
        watchlisted = safe.jsonPrimitiveOrNull("watchlisted")?.booleanOrNull ?: false,
        liked = safe.optBooleanOrNull("liked"),
    )
}

internal fun CrispyBackendClient.parseClientParentRef(json: JsonObject?): ClientParentRef? {
    val safe = json ?: return null
    if (safe.size == 0) return null
    return ClientParentRef(
        seriesItemId = safe.optNullableString("seriesItemId"),
        seriesTitle = safe.optNullableString("seriesTitle"),
        seasonItemId = safe.optNullableString("seasonItemId"),
        seasonNumber = safe.optIntOrNull("seasonNumber"),
        episodeNumber = safe.optIntOrNull("episodeNumber"),
        images = parseClientParentImages(safe.optJsonObject("images")),
    )
}

internal fun CrispyBackendClient.parseClientParentImages(json: JsonObject?): ClientParentImages? {
    val safe = json ?: return null
    if (safe.size == 0) return null
    return ClientParentImages(
        artwork = parseResponsiveImageSet(safe.optJsonObject("artwork")),
    )
}

// --- Calendar parsers ---

internal fun CrispyBackendClient.parseCalendarItems(array: JsonArray?): List<CalendarItem> {
    val safeArray = array ?: JsonArray(emptyList())
    return buildList {
        for (index in 0 until safeArray.size) {
            val item = safeArray.getOrNull(index) as? JsonObject ?: continue
            add(
                CalendarItem(
                    card = parseClientMediaCard(item),
                    airDate = item.optNullableString("airDate"),
                    bucket = item.optNullableString("bucket"),
                )
            )
        }
    }
}

// --- Watch State parsers ---

internal fun CrispyBackendClient.parseWatchStateResponse(json: JsonObject): WatchStateResponse {
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
        liked = progress?.liked,
        resumePositionSeconds = positionSeconds,
        durationSeconds = durationSeconds,
        progressPercent = progressPercent,
        lastPlayedAt = lastPlayedAt,
    )
}

internal fun CrispyBackendClient.parseWatchStateEnvelope(json: JsonObject, profileId: String): WatchStateEnvelope {
    return WatchStateEnvelope(
        profileId = profileId,
        source = "server",
        generatedAt = null,
        item = parseWatchStateResponse(json),
    )
}

internal fun CrispyBackendClient.parseWatchStatesEnvelope(json: JsonObject, profileId: String): WatchStatesEnvelope {
    return WatchStatesEnvelope(
        profileId = profileId,
        source = "server",
        generatedAt = null,
        items = parseClientMediaCards(json.optJsonArray("items")).map(::clientMediaCardToWatchStateResponse),
    )
}

// --- ClientMediaCardQueryResult parser ---

internal fun CrispyBackendClient.parseClientMediaCardQueryResult(json: JsonObject): ClientMediaCardQueryResult {
    return ClientMediaCardQueryResult(
        items = parseClientMediaCards(json.optJsonArray("Items")),
        startIndex = json.jsonPrimitiveOrNull("StartIndex")?.intOrNull ?: 0,
        totalRecordCount = json.jsonPrimitiveOrNull("TotalRecordCount")?.intOrNull ?: 0,
        nextCursor = json.optNullableString("NextCursor"),
        hasMore = json.jsonPrimitiveOrNull("HasMore")?.booleanOrNull ?: false,
    )
}

// --- Watch action parser ---

internal fun CrispyBackendClient.parseWatchActionResponse(json: JsonObject): WatchActionResponse {
    return WatchActionResponse(
        accepted = json.jsonPrimitiveOrNull("accepted")?.booleanOrNull ?: false,
        mode = json.optStringOrEmpty("mode").trim().ifBlank { error("Watch action response is missing mode.") },
        reason = json.optNullableString("reason")?.trim()?.takeIf { it.isNotBlank() },
    )
}

// --- Metadata / Detail parsers (unchanged) ---

internal fun CrispyBackendClient.parseMetadataPersonDetail(json: JsonObject): MetadataPersonDetail {
    val personId = json.optStringOrEmpty("personId").trim()
    val name = json.optStringOrEmpty("name").trim()
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
        socials = parsePersonSocials(json.optJsonObject("socials")),
        knownFor = parseClientMediaCards(json.optJsonArray("knownFor")),
    )
}

private fun CrispyBackendClient.parsePersonSocials(json: JsonObject?): PersonSocials {
    if (json == null) {
        return PersonSocials(null, null, null, null, null, null)
    }
    return PersonSocials(
        imdbId = json.optNullableString("imdbId"),
        instagram = json.optNullableString("instagram"),
        twitter = json.optNullableString("twitter"),
        facebook = json.optNullableString("facebook"),
        tiktok = json.optNullableString("tiktok"),
        youtube = json.optNullableString("youtube"),
    )
}

internal fun CrispyBackendClient.parseMetadataVideoViews(array: JsonArray?): List<MetadataVideoView> {
    val safeArray = array ?: JsonArray(emptyList())
    return buildList {
        for (index in 0 until safeArray.size) {
            val item = safeArray.getOrNull(index) as? JsonObject ?: continue
            val id = item.optStringOrEmpty("id").trim()
            val key = item.optStringOrEmpty("key").trim()
            if (id.isBlank() || key.isBlank()) continue
            add(
                MetadataVideoView(
                    id = id,
                    key = key,
                    name = item.optNullableString("name"),
                    site = item.optNullableString("site"),
                    type = item.optNullableString("type"),
                    official = item.jsonPrimitiveOrNull("official")?.booleanOrNull ?: false,
                    publishedAt = item.optNullableString("publishedAt"),
                    url = item.optNullableString("url"),
                    thumbnailUrl = item.optNullableString("thumbnailUrl"),
                )
            )
        }
    }
}

internal fun CrispyBackendClient.parseMetadataPersonRefViews(array: JsonArray?): List<MetadataPersonRefView> {
    val safeArray = array ?: JsonArray(emptyList())
    return buildList {
        for (index in 0 until safeArray.size) {
            val item = safeArray.getOrNull(index) as? JsonObject ?: continue
            val personId = item.optStringOrEmpty("personId").trim()
            val name = item.optStringOrEmpty("name").trim()
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

internal fun CrispyBackendClient.parseMetadataReviewViews(array: JsonArray?): List<MetadataReviewView> {
    val safeArray = array ?: JsonArray(emptyList())
    return buildList {
        for (index in 0 until safeArray.size) {
            val item = safeArray.getOrNull(index) as? JsonObject ?: continue
            val id = item.optStringOrEmpty("id").trim()
            val content = item.optStringOrEmpty("content").trim()
            if (id.isBlank() || content.isBlank()) continue
            add(
                MetadataReviewView(
                    id = id,
                    provider = item.optStringOrEmpty("provider").trim(),
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

internal fun CrispyBackendClient.parseMetadataCompanyViews(array: JsonArray?): List<MetadataCompanyView> {
    val safeArray = array ?: JsonArray(emptyList())
    return buildList {
        for (index in 0 until safeArray.size) {
            val item = safeArray.getOrNull(index) as? JsonObject ?: continue
            val id = item.optStringOrEmpty("id").trim()
            val name = item.optStringOrEmpty("name").trim()
            if (id.isBlank() || name.isBlank()) continue
            add(
                MetadataCompanyView(
                    id = id,
                    name = name,
                    logo = parseResponsiveImageSet(item.optJsonObject("logo")),
                    originCountry = item.optNullableString("originCountry"),
                )
            )
        }
    }
}

internal fun CrispyBackendClient.parseMetadataProductionInfoView(json: JsonObject?): MetadataProductionInfoView {
    val safe = json ?: JsonObject(emptyMap())
    return MetadataProductionInfoView(
        originalLanguage = safe.optNullableString("originalLanguage"),
        originCountries = safe.optStringList("originCountries"),
        spokenLanguages = safe.optStringList("spokenLanguages"),
        productionCountries = safe.optStringList("productionCountries"),
        companies = parseMetadataCompanyViews(safe.optJsonArray("companies")),
        networks = parseMetadataCompanyViews(safe.optJsonArray("networks")),
    )
}

internal fun CrispyBackendClient.parseMetadataTitleRatings(json: JsonObject?): MetadataTitleRatings {
    val safe = json ?: JsonObject(emptyMap())
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

fun parseAiInsightsSlides(array: JsonArray?): List<AiInsightSlide> {
    val safeArray = array ?: JsonArray(emptyList())
    return buildList {
        for (index in 0 until safeArray.size) {
            val item = safeArray.getOrNull(index) as? JsonObject ?: continue
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
                    backdrop = parseResponsiveImageSet(item.optJsonObject("backdrop")),
                    accent = item.optNullableString("accent").orEmpty().trim(),
                )
            )
        }
    }
}
