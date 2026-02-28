package com.crispy.tv.metadata.tmdb

import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.domain.metadata.normalizeNuvioMediaId
import com.crispy.tv.home.MediaDetails
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.network.CrispyHttpClient
import java.util.Locale
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject

private object TmdbGenre {
    private val genres = mapOf(
        28 to "Action",
        12 to "Adventure",
        16 to "Animation",
        35 to "Comedy",
        80 to "Crime",
        99 to "Documentary",
        18 to "Drama",
        10751 to "Family",
        14 to "Fantasy",
        36 to "History",
        27 to "Horror",
        10402 to "Music",
        9648 to "Mystery",
        10749 to "Romance",
        878 to "Sci-Fi",
        10770 to "TV Movie",
        53 to "Thriller",
        10752 to "War",
        37 to "Western"
    )

    fun fromId(id: Int): String? = genres[id]
}

data class TmdbEnrichmentResult(
    val enrichment: TmdbEnrichment,
    val fallbackDetails: MediaDetails
)

class TmdbEnrichmentRepository(
    apiKey: String,
    httpClient: CrispyHttpClient
) {
    private val client = TmdbJsonClient(apiKey = apiKey, httpClient = httpClient)

    suspend fun load(
        rawId: String,
        mediaTypeHint: MetadataLabMediaType? = null,
        locale: Locale = Locale.getDefault()
    ): TmdbEnrichmentResult? {
        val normalizedContentId = normalizeNuvioMediaId(rawId).contentId
        val imdbId = extractImdbId(normalizedContentId)

        val language = locale.toTmdbLanguageTag()

        val resolved = resolveTmdbIdAndType(normalizedContentId, imdbId, mediaTypeHint, language) ?: return null
        val tmdbId = resolved.tmdbId
        val mediaType = resolved.mediaType

        val detailsJson =
            fetchDetails(mediaType = mediaType, tmdbId = tmdbId, language = language) ?: return null

        val creditsJson = detailsJson.optJSONObject("credits")
        val videosJson = detailsJson.optJSONObject("videos")
        val reviewsJson = detailsJson.optJSONObject("reviews")

        val collectionId =
            if (mediaType == MetadataLabMediaType.MOVIE) {
                detailsJson.optJSONObject("belongs_to_collection")?.optInt("id")?.takeIf { it > 0 }
            } else {
                null
            }

        val imdbFromDetails =
            when (mediaType) {
                MetadataLabMediaType.MOVIE -> detailsJson.optStringNonBlank("imdb_id")
                MetadataLabMediaType.SERIES ->
                    detailsJson.optJSONObject("external_ids")?.optStringNonBlank("imdb_id")
            }

        val cast = parseCast(creditsJson)
        val production = parseProduction(detailsJson, mediaType)
        val trailers = parseTrailers(videosJson)
        val reviews = parseReviews(reviewsJson)

        val (similar, collection) =
            coroutineScope {
                val similarDeferred =
                    async {
                        fetchSimilar(mediaType = mediaType, tmdbId = tmdbId, language = language)
                    }
                val collectionDeferred =
                    async {
                        if (collectionId != null) {
                            fetchCollection(collectionId = collectionId, language = language)
                        } else {
                            null
                        }
                    }

                similarDeferred.await() to collectionDeferred.await()
            }
        val titleDetails = parseTitleDetails(detailsJson, mediaType)

        val enrichment =
            TmdbEnrichment(
                tmdbId = tmdbId,
                imdbId = imdbFromDetails ?: imdbId,
                mediaType = mediaType,
                cast = cast,
                production = production,
                trailers = trailers,
                reviews = reviews,
                similar = similar,
                collection = collection,
                titleDetails = titleDetails
            )

        val fallbackDetails =
            detailsJson.toFallbackMediaDetails(
                normalizedContentId = normalizedContentId,
                mediaType = mediaType,
                imdbId = imdbFromDetails ?: imdbId,
                preferredLanguage = language
            )

        return TmdbEnrichmentResult(
            enrichment = enrichment,
            fallbackDetails = fallbackDetails
        )
    }

    suspend fun loadArtwork(
        rawId: String,
        mediaTypeHint: MetadataLabMediaType? = null,
        locale: Locale = Locale.getDefault()
    ): MediaDetails? {
        val normalizedContentId = normalizeNuvioMediaId(rawId).contentId
        val imdbId = extractImdbId(normalizedContentId)

        val language = locale.toTmdbLanguageTag()

        val resolved = resolveTmdbIdAndType(normalizedContentId, imdbId, mediaTypeHint, language) ?: return null
        val tmdbId = resolved.tmdbId
        val mediaType = resolved.mediaType

        val detailsJson = fetchArtworkDetails(mediaType = mediaType, tmdbId = tmdbId, language = language) ?: return null

        val imdbFromDetails =
            when (mediaType) {
                MetadataLabMediaType.MOVIE -> detailsJson.optStringNonBlank("imdb_id")
                MetadataLabMediaType.SERIES -> detailsJson.optJSONObject("external_ids")?.optStringNonBlank("imdb_id")
            }

        return detailsJson.toFallbackMediaDetails(
            normalizedContentId = normalizedContentId,
            mediaType = mediaType,
            imdbId = imdbFromDetails ?: imdbId,
            preferredLanguage = language
        )
    }

    private suspend fun fetchArtworkDetails(
        mediaType: MetadataLabMediaType,
        tmdbId: Int,
        language: String
    ): JSONObject? {
        val path = "${mediaType.pathSegment()}/$tmdbId"
        val append =
            buildList {
                add("images")
                if (mediaType == MetadataLabMediaType.SERIES) add("external_ids")
            }.joinToString(",")
        val query =
            buildMap {
                put("language", language)
                put("append_to_response", append)
                put("include_image_language", buildIncludeImageLanguage(language))
            }
        return client.getJson(path = path, query = query)
    }

    private suspend fun fetchDetails(
        mediaType: MetadataLabMediaType,
        tmdbId: Int,
        language: String
    ): JSONObject? {
        val path = "${mediaType.pathSegment()}/$tmdbId"
        val append =
            when (mediaType) {
                MetadataLabMediaType.MOVIE -> "credits,videos,reviews,images"
                MetadataLabMediaType.SERIES -> "credits,videos,reviews,external_ids,images"
            }
        return client.getJson(
            path = path,
            query = mapOf(
                "language" to language,
                "append_to_response" to append,
                "include_image_language" to buildIncludeImageLanguage(language)
            )
        )
    }

    private suspend fun fetchSimilar(
        mediaType: MetadataLabMediaType,
        tmdbId: Int,
        language: String
    ): List<CatalogItem> {
        val json =
            client.getJson(
                path = "${mediaType.pathSegment()}/$tmdbId/similar",
                query = mapOf(
                    "language" to language,
                    "page" to "1"
                )
            ) ?: return emptyList()

        val results = json.optJSONArray("results") ?: return emptyList()
        return results.toJsonObjectList().mapNotNull { item ->
            val id = item.optInt("id").takeIf { it > 0 } ?: return@mapNotNull null
            val title =
                when (mediaType) {
                    MetadataLabMediaType.MOVIE -> item.optStringNonBlank("title")
                    MetadataLabMediaType.SERIES -> item.optStringNonBlank("name")
                } ?: return@mapNotNull null

            val year = item.optStringNonBlank("release_date")?.take(4)
                ?: item.optStringNonBlank("first_air_date")?.take(4)
            val genre = item.optJSONArray("genre_ids")?.let { ids ->
                if (ids.length() > 0) TmdbGenre.fromId(ids.optInt(0))
                else null
            }

            CatalogItem(
                id = "tmdb:$id",
                title = title,
                posterUrl = TmdbApi.imageUrl(item.optStringNonBlank("poster_path"), size = "w500"),
                backdropUrl = TmdbApi.imageUrl(item.optStringNonBlank("backdrop_path"), size = "w780"),
                addonId = "tmdb",
                type = mediaType.toCatalogType(),
                rating = item.optDoubleOrNull("vote_average")?.formatVoteAverage(),
                year = year,
                genre = genre
            )
        }
    }

    private fun parseReviews(reviews: JSONObject?): List<TmdbReview> {
        val results = reviews?.optJSONArray("results") ?: return emptyList()
        return results
            .toJsonObjectList()
            .mapNotNull { item ->
                val id = item.optStringNonBlank("id") ?: return@mapNotNull null
                val author = item.optStringNonBlank("author") ?: "Unknown"
                val rating = item.optJSONObject("author_details")?.optDoubleOrNull("rating")
                val content = item.optStringNonBlank("content") ?: return@mapNotNull null
                val createdAt = item.optStringNonBlank("created_at")
                TmdbReview(
                    id = id,
                    author = author,
                    rating = rating,
                    content = content,
                    createdAt = createdAt
                )
            }
            .take(12)
    }

    private suspend fun fetchCollection(
        collectionId: Int,
        language: String
    ): TmdbCollection? {
        val json =
            client.getJson(
                path = "collection/$collectionId",
                query = mapOf("language" to language)
            ) ?: return null

        val name = json.optStringNonBlank("name") ?: return null
        val parts =
            (json.optJSONArray("parts") ?: JSONArray())
                .toJsonObjectList()
                .mapNotNull { part ->
                    val id = part.optInt("id").takeIf { it > 0 } ?: return@mapNotNull null
                    val title = part.optStringNonBlank("title") ?: return@mapNotNull null
                    val year = part.optStringNonBlank("release_date")?.take(4)
                    CatalogItem(
                        id = "tmdb:$id",
                        title = title,
                        posterUrl = TmdbApi.imageUrl(part.optStringNonBlank("poster_path"), size = "w500"),
                        backdropUrl = TmdbApi.imageUrl(part.optStringNonBlank("backdrop_path"), size = "w780"),
                        addonId = "tmdb",
                        type = "movie",
                        rating = null,
                        year = year,
                        genre = null
                    )
                }

        return TmdbCollection(
            id = collectionId,
            name = name,
            posterUrl = TmdbApi.imageUrl(json.optStringNonBlank("poster_path"), size = "w500"),
            backdropUrl = TmdbApi.imageUrl(json.optStringNonBlank("backdrop_path"), size = "w780"),
            parts = parts
        )
    }

    private fun parseCast(credits: JSONObject?): List<TmdbCastMember> {
        val castArray = credits?.optJSONArray("cast") ?: return emptyList()
        return castArray.toJsonObjectList().mapNotNull { member ->
            val id = member.optInt("id").takeIf { it > 0 } ?: return@mapNotNull null
            val name = member.optStringNonBlank("name") ?: return@mapNotNull null
            val character = member.optStringNonBlank("character")
            val profileUrl = TmdbApi.imageUrl(member.optStringNonBlank("profile_path"), size = "w185")
            TmdbCastMember(
                id = id,
                name = name,
                character = character,
                profileUrl = profileUrl
            )
        }.take(24)
    }

    private fun parseProduction(details: JSONObject, mediaType: MetadataLabMediaType): List<TmdbProductionEntity> {
        val arrayName = if (mediaType == MetadataLabMediaType.MOVIE) "production_companies" else "networks"
        val entities = details.optJSONArray(arrayName) ?: return emptyList()
        return entities.toJsonObjectList().mapNotNull { entity ->
            val id = entity.optInt("id").takeIf { it > 0 } ?: return@mapNotNull null
            val name = entity.optStringNonBlank("name") ?: return@mapNotNull null
            val logo = TmdbApi.imageUrl(entity.optStringNonBlank("logo_path"), size = "w185")
            if (name.isBlank() && logo == null) return@mapNotNull null
            TmdbProductionEntity(
                id = id,
                name = name,
                logoUrl = logo
            )
        }.take(24)
    }

    private fun parseTrailers(videos: JSONObject?): List<TmdbTrailer> {
        val results = videos?.optJSONArray("results") ?: return emptyList()
        return results.toJsonObjectList().mapNotNull { video ->
            val id = video.optStringNonBlank("id") ?: return@mapNotNull null
            val name = video.optStringNonBlank("name") ?: return@mapNotNull null
            val site = video.optStringNonBlank("site") ?: return@mapNotNull null
            val key = video.optStringNonBlank("key") ?: return@mapNotNull null
            val type = video.optStringNonBlank("type") ?: "Video"
            val official = video.optBoolean("official", false)
            if (!site.equals("YouTube", ignoreCase = true)) return@mapNotNull null
            if (!type.equals("Trailer", ignoreCase = true) && !type.equals("Teaser", ignoreCase = true)) return@mapNotNull null

            TmdbTrailer(
                id = id,
                name = name,
                site = site,
                key = key,
                type = type,
                official = official,
                thumbnailUrl = TmdbApi.youtubeThumbnailUrl(key),
                watchUrl = TmdbApi.youtubeWatchUrl(key)
            )
        }
            .sortedWith(
                compareByDescending<TmdbTrailer> { it.official }
                    .thenBy { it.type.lowercase(Locale.US) }
                    .thenBy { it.name.lowercase(Locale.US) }
            )
            .take(12)
    }

    private fun parseTitleDetails(details: JSONObject, mediaType: MetadataLabMediaType): TmdbTitleDetails? {
        val status = details.optStringNonBlank("status")
        val originalLanguage = details.optStringNonBlank("original_language")
        val tagline = details.optStringNonBlank("tagline")

        val originCountries =
            when (mediaType) {
                MetadataLabMediaType.MOVIE -> {
                    val countries = details.optJSONArray("production_countries") ?: JSONArray()
                    countries.toJsonObjectList().mapNotNull { it.optStringNonBlank("iso_3166_1") }
                }

                MetadataLabMediaType.SERIES -> {
                    val countries = details.optJSONArray("origin_country")
                    if (countries != null) {
                        (0 until countries.length()).mapNotNull { idx -> countries.optString(idx).trim().takeIf { it.isNotBlank() } }
                    } else {
                        emptyList()
                    }
                }
            }

        return when (mediaType) {
            MetadataLabMediaType.MOVIE -> {
                val releaseDate = details.optStringNonBlank("release_date")
                val runtimeMinutes = details.optIntOrNull("runtime")
                val budget = details.optLongOrNull("budget")
                val revenue = details.optLongOrNull("revenue")
                TmdbMovieDetails(
                    status = status,
                    releaseDate = releaseDate,
                    runtimeMinutes = runtimeMinutes,
                    budget = budget,
                    revenue = revenue,
                    originalLanguage = originalLanguage,
                    originCountries = originCountries,
                    tagline = tagline
                )
            }

            MetadataLabMediaType.SERIES -> {
                val firstAirDate = details.optStringNonBlank("first_air_date")
                val lastAirDate = details.optStringNonBlank("last_air_date")
                val numberOfSeasons = details.optIntOrNull("number_of_seasons")
                val numberOfEpisodes = details.optIntOrNull("number_of_episodes")
                val episodeRunTime = details.optJSONArray("episode_run_time")?.let { array ->
                    (0 until array.length()).mapNotNull { idx -> array.optInt(idx).takeIf { it > 0 } }
                } ?: emptyList()
                val type = details.optStringNonBlank("type")
                TmdbTvDetails(
                    status = status,
                    firstAirDate = firstAirDate,
                    lastAirDate = lastAirDate,
                    numberOfSeasons = numberOfSeasons,
                    numberOfEpisodes = numberOfEpisodes,
                    episodeRunTimeMinutes = episodeRunTime,
                    type = type,
                    originalLanguage = originalLanguage,
                    originCountries = originCountries,
                    tagline = tagline
                )
            }
        }
    }

    private suspend fun resolveTmdbIdAndType(
        normalizedContentId: String,
        imdbId: String?,
        hint: MetadataLabMediaType?,
        language: String
    ): ResolvedTmdb? {
        val tmdbIdFromId = extractTmdbId(normalizedContentId)
        if (tmdbIdFromId != null) {
            if (hint != null) {
                val details = fetchDetails(hint, tmdbIdFromId, language)
                if (details != null) return ResolvedTmdb(tmdbIdFromId, hint)

                val other =
                    if (hint == MetadataLabMediaType.MOVIE) MetadataLabMediaType.SERIES else MetadataLabMediaType.MOVIE
                val otherDetails = fetchDetails(other, tmdbIdFromId, language)
                if (otherDetails != null) return ResolvedTmdb(tmdbIdFromId, other)
                return null
            }

            // Suspenders: tmdb:<id> is ambiguous without a type hint (movie vs tv IDs can collide).
            // Only accept the ID if exactly one namespace resolves.
            val movieDetails = fetchDetails(MetadataLabMediaType.MOVIE, tmdbIdFromId, language)
            val tvDetails = fetchDetails(MetadataLabMediaType.SERIES, tmdbIdFromId, language)
            return when {
                movieDetails != null && tvDetails == null -> ResolvedTmdb(tmdbIdFromId, MetadataLabMediaType.MOVIE)
                tvDetails != null && movieDetails == null -> ResolvedTmdb(tmdbIdFromId, MetadataLabMediaType.SERIES)
                else -> null
            }
        }

        val imdb = imdbId ?: return null
        val find =
            client.getJson(
                path = "find/$imdb",
                query = mapOf(
                    "external_source" to "imdb_id",
                    "language" to language
                )
            ) ?: return null

        fun firstId(arrayName: String): Int? {
            val arr = find.optJSONArray(arrayName) ?: return null
            return arr.optJSONObject(0)?.optInt("id")?.takeIf { it > 0 }
        }

        val movieId = firstId("movie_results")
        val tvId = firstId("tv_results")
        return when (hint) {
            MetadataLabMediaType.MOVIE ->
                movieId?.let { ResolvedTmdb(it, MetadataLabMediaType.MOVIE) }
                    ?: tvId?.let { ResolvedTmdb(it, MetadataLabMediaType.SERIES) }
            MetadataLabMediaType.SERIES ->
                tvId?.let { ResolvedTmdb(it, MetadataLabMediaType.SERIES) }
                    ?: movieId?.let { ResolvedTmdb(it, MetadataLabMediaType.MOVIE) }
            null -> {
                when {
                    movieId != null && tvId == null -> ResolvedTmdb(movieId, MetadataLabMediaType.MOVIE)
                    tvId != null && movieId == null -> ResolvedTmdb(tvId, MetadataLabMediaType.SERIES)
                    else -> null
                }
            }
        }
    }

    private data class ResolvedTmdb(
        val tmdbId: Int,
        val mediaType: MetadataLabMediaType
    )
}

private fun MetadataLabMediaType.pathSegment(): String {
    return when (this) {
        MetadataLabMediaType.MOVIE -> "movie"
        MetadataLabMediaType.SERIES -> "tv"
    }
}

private fun MetadataLabMediaType.toCatalogType(): String {
    return when (this) {
        MetadataLabMediaType.MOVIE -> "movie"
        MetadataLabMediaType.SERIES -> "series"
    }
}

private fun Locale.toTmdbLanguageTag(): String {
    val lang = language.trim().ifBlank { "en" }
    val country = country.trim().takeIf { it.isNotBlank() }
    return if (country != null) {
        "$lang-${country.uppercase(Locale.US)}"
    } else {
        lang
    }
}

private fun extractImdbId(input: String?): String? {
    val raw = input?.trim().orEmpty()
    if (raw.isBlank()) return null
    if (raw.startsWith("tt", ignoreCase = true)) {
        return raw.lowercase(Locale.US).takeIf { it.length >= 4 }
    }
    val match = Regex("tt\\d{4,}", RegexOption.IGNORE_CASE).find(raw) ?: return null
    return match.value.lowercase(Locale.US)
}

private fun extractTmdbId(input: String?): Int? {
    val raw = input?.trim().orEmpty()
    if (raw.isBlank()) return null
    val match =
        Regex("\\btmdb:(?:movie:|tv:|show:)?(\\d+)", RegexOption.IGNORE_CASE)
            .find(raw)
            ?: return null
    return match.groupValues.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 }
}

private fun JSONArray.toJsonObjectList(): List<JSONObject> {
    if (length() == 0) return emptyList()
    val out = ArrayList<JSONObject>(length())
    for (i in 0 until length()) {
        optJSONObject(i)?.let(out::add)
    }
    return out
}

private fun JSONObject.optStringNonBlank(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val value = optString(key)
    val trimmed = value.trim()
    return trimmed.takeIf { it.isNotBlank() }
}

private fun JSONObject.optDoubleOrNull(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    val value = opt(key)
    return when (value) {
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull()
        else -> null
    }
}

private fun Double.formatVoteAverage(): String? {
    if (!isFinite() || this <= 0.0) return null
    val formatted = String.format(Locale.US, "%.1f", this)
    return formatted.removeSuffix(".0")
}

private fun JSONObject.optIntOrNull(key: String): Int? {
    if (!has(key) || isNull(key)) return null
    val value = optInt(key, -1)
    return value.takeIf { it > 0 }
}

private fun JSONObject.optLongOrNull(key: String): Long? {
    if (!has(key) || isNull(key)) return null
    val value = optLong(key, -1L)
    return value.takeIf { it > 0L }
}

private fun JSONObject.toFallbackMediaDetails(
    normalizedContentId: String,
    mediaType: MetadataLabMediaType,
    imdbId: String?,
    preferredLanguage: String
): MediaDetails {
    val title =
        when (mediaType) {
            MetadataLabMediaType.MOVIE -> optStringNonBlank("title") ?: optStringNonBlank("original_title") ?: "Unknown"
            MetadataLabMediaType.SERIES -> optStringNonBlank("name") ?: optStringNonBlank("original_name") ?: "Unknown"
        }

    val posterUrl = TmdbApi.imageUrl(optStringNonBlank("poster_path"), size = "w500")
    val backdropUrl = TmdbApi.imageUrl(optStringNonBlank("backdrop_path"), size = "w780")
    val logoUrl = parseBestTitleLogoUrl(images = optJSONObject("images"), preferredLanguage = preferredLanguage)
    val description = optStringNonBlank("overview")

    val genres =
        (optJSONArray("genres") ?: JSONArray())
            .toJsonObjectList()
            .mapNotNull { it.optStringNonBlank("name") }

    val year =
        when (mediaType) {
            MetadataLabMediaType.MOVIE -> optStringNonBlank("release_date")?.take(4)
            MetadataLabMediaType.SERIES -> optStringNonBlank("first_air_date")?.take(4)
        }

    val runtime =
        when (mediaType) {
            MetadataLabMediaType.MOVIE -> optIntOrNull("runtime")?.toString()
            MetadataLabMediaType.SERIES -> optJSONArray("episode_run_time")?.optInt(0)?.takeIf { it > 0 }?.toString()
        }

    val credits = optJSONObject("credits")
    val cast =
        credits
            ?.optJSONArray("cast")
            ?.toJsonObjectList()
            ?.mapNotNull { it.optStringNonBlank("name") }
            ?.take(24)
            ?: emptyList()

    val directors =
        if (mediaType == MetadataLabMediaType.MOVIE) {
            val crew = credits?.optJSONArray("crew")
            crew?.toJsonObjectList()
                ?.filter { it.optStringNonBlank("job")?.equals("Director", ignoreCase = true) == true }
                ?.mapNotNull { it.optStringNonBlank("name") }
                ?.distinct()
                ?.take(6)
                ?: emptyList()
        } else {
            emptyList()
        }

    val creators =
        if (mediaType == MetadataLabMediaType.SERIES) {
            val createdBy = optJSONArray("created_by")
            createdBy?.toJsonObjectList()?.mapNotNull { it.optStringNonBlank("name") }?.take(12) ?: emptyList()
        } else {
            emptyList()
        }

    return MediaDetails(
        id = normalizedContentId,
        imdbId = imdbId?.trim()?.takeIf { it.startsWith("tt", ignoreCase = true) }?.lowercase(Locale.US),
        mediaType = if (mediaType == MetadataLabMediaType.MOVIE) "movie" else "series",
        title = title,
        posterUrl = posterUrl,
        backdropUrl = backdropUrl,
        logoUrl = logoUrl,
        description = description,
        genres = genres,
        year = year,
        runtime = runtime,
        certification = null,
        rating = optDoubleOrNull("vote_average")?.formatVoteAverage(),
        cast = cast,
        directors = directors,
        creators = creators,
        videos = emptyList(),
        addonId = "tmdb"
    )
}

private fun buildIncludeImageLanguage(language: String): String {
    val base = language.substringBefore('-').trim().lowercase(Locale.US)
    val out = linkedSetOf<String>()
    if (base.isNotBlank()) out.add(base)
    out.add("en")
    out.add("null")
    return out.joinToString(",")
}

private fun parseBestTitleLogoUrl(images: JSONObject?, preferredLanguage: String): String? {
    val logos = images?.optJSONArray("logos") ?: return null
    if (logos.length() == 0) return null

    data class LogoCandidate(
        val filePath: String,
        val iso6391: String?,
        val voteAverage: Double,
        val voteCount: Int,
        val width: Int,
        val height: Int,
        val langScore: Int
    )

    val preferredBase = preferredLanguage.substringBefore('-').trim().lowercase(Locale.US)
    val candidates =
        logos
            .toJsonObjectList()
            .mapNotNull { item ->
                val filePath = item.optStringNonBlank("file_path") ?: return@mapNotNull null
                val iso = item.optStringNonBlank("iso_639_1")?.trim()?.lowercase(Locale.US)
                val langScore =
                    when {
                        preferredBase.isNotBlank() && iso == preferredBase -> 3
                        iso == null -> 2
                        iso == "en" -> 1
                        else -> 0
                    }
                LogoCandidate(
                    filePath = filePath,
                    iso6391 = iso,
                    voteAverage = item.optDoubleOrNull("vote_average") ?: 0.0,
                    voteCount = item.optInt("vote_count", 0),
                    width = item.optInt("width", 0),
                    height = item.optInt("height", 0),
                    langScore = langScore
                )
            }

    val best =
        candidates.maxWithOrNull(
            compareByDescending<LogoCandidate> { it.langScore }
                .thenByDescending { it.voteAverage }
                .thenByDescending { it.voteCount }
                .thenByDescending { it.width }
                .thenByDescending { it.height }
                .thenBy { it.filePath }
        ) ?: return null

    return TmdbApi.imageUrl(best.filePath, size = "w500")
}
