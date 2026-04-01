package com.crispy.tv.metadata.tmdb

import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.domain.metadata.MetadataSeason
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.ratings.formatRating
import java.util.Locale
import org.json.JSONObject

private object TmdbGenre {
    private val labelsById = mapOf(
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
        53 to "Thriller",
        10752 to "War",
        37 to "Western",
        10759 to "Action & Adventure",
        10762 to "Kids",
        10763 to "News",
        10764 to "Reality",
        10765 to "Sci-Fi & Fantasy",
        10766 to "Soap",
        10767 to "Talk",
        10768 to "War & Politics",
    )

    fun fromId(id: Int?): String? = id?.let(labelsById::get)
}

internal fun parseSimilarCatalogItems(
    similar: JSONObject?,
    mediaType: MetadataLabMediaType,
): List<CatalogItem> {
    val results = similar?.optJSONArray("results") ?: return emptyList()
    return buildList {
        for (index in 0 until minOf(results.length(), 20)) {
            val item = results.optJSONObject(index) ?: continue
            val id = item.optInt("id", 0)
            if (id <= 0) continue

            val title =
                when (mediaType) {
                    MetadataLabMediaType.MOVIE -> {
                        item.optStringNonBlank("title") ?: item.optStringNonBlank("original_title")
                    }
                    MetadataLabMediaType.SERIES -> {
                        item.optStringNonBlank("name") ?: item.optStringNonBlank("original_name")
                    }
                    MetadataLabMediaType.ANIME -> {
                        item.optStringNonBlank("name") ?: item.optStringNonBlank("original_name")
                    }
                } ?: continue

            val year =
                when (mediaType) {
                    MetadataLabMediaType.MOVIE -> item.optStringNonBlank("release_date")
                    MetadataLabMediaType.SERIES -> item.optStringNonBlank("first_air_date")
                    MetadataLabMediaType.ANIME -> item.optStringNonBlank("first_air_date")
                }?.take(4)

            val genreIds = item.optJSONArray("genre_ids")
            val primaryGenre = genreIds?.optInt(0)?.takeIf { it > 0 }

            add(
                CatalogItem(
                    id = "tmdb:$id",
                    title = title,
                    posterUrl = TmdbApi.imageUrl(item.optStringNonBlank("poster_path"), "w500"),
                    backdropUrl = TmdbApi.imageUrl(item.optStringNonBlank("backdrop_path"), "w780"),
                    addonId = "tmdb",
                    type = mediaType.toCatalogType(),
                    rating = formatRating(item.optDoubleOrNull("vote_average")),
                    year = year,
                    genre = TmdbGenre.fromId(primaryGenre),
                    provider = "tmdb",
                    providerId = id.toString(),
                )
            )
        }
    }
}

internal fun parseCollection(collection: JSONObject?): TmdbCollection? {
    val json = collection ?: return null
    val collectionId = json.optInt("id", 0)
    if (collectionId <= 0) return null
    val name = json.optStringNonBlank("name") ?: return null

    val parts = buildList {
        val partsArray = json.optJSONArray("parts") ?: return@buildList
        for (index in 0 until partsArray.length()) {
            val item = partsArray.optJSONObject(index) ?: continue
            val itemId = item.optInt("id", 0)
            if (itemId <= 0) continue
            val title = item.optStringNonBlank("title") ?: item.optStringNonBlank("original_title") ?: continue
            add(
                CatalogItem(
                    id = "tmdb:$itemId",
                    title = title,
                    posterUrl = TmdbApi.imageUrl(item.optStringNonBlank("poster_path"), "w500"),
                    backdropUrl = TmdbApi.imageUrl(item.optStringNonBlank("backdrop_path"), "w780"),
                    addonId = "tmdb",
                    type = MetadataLabMediaType.MOVIE.toCatalogType(),
                    rating = null,
                    year = item.optStringNonBlank("release_date")?.take(4),
                    genre = null,
                    provider = "tmdb",
                    providerId = itemId.toString(),
                )
            )
        }
    }

    return TmdbCollection(
        id = collectionId,
        name = name,
        posterUrl = TmdbApi.imageUrl(json.optStringNonBlank("poster_path"), "w500"),
        backdropUrl = TmdbApi.imageUrl(json.optStringNonBlank("backdrop_path"), "w780"),
        parts = parts,
    )
}

internal fun parseCastMembers(credits: JSONObject?): List<TmdbCastMember> {
    val castArray = credits?.optJSONArray("cast") ?: return emptyList()
    return buildList {
        for (index in 0 until minOf(castArray.length(), 24)) {
            val cast = castArray.optJSONObject(index) ?: continue
            val id = cast.optInt("id", 0)
            val name = cast.optStringNonBlank("name") ?: continue
            add(
                TmdbCastMember(
                    id = id,
                    name = name,
                    character = cast.optStringNonBlank("character"),
                    profileUrl = TmdbApi.imageUrl(cast.optStringNonBlank("profile_path"), "w185"),
                )
            )
        }
    }
}

internal fun parseMetadataCastPairs(credits: JSONObject?, limit: Int = 12): List<Pair<String, String?>> {
    val castArray = credits?.optJSONArray("cast") ?: return emptyList()
    return buildList {
        for (index in 0 until minOf(castArray.length(), limit)) {
            val actor = castArray.optJSONObject(index) ?: continue
            val name = actor.optStringNonBlank("name") ?: continue
            add(name to actor.optStringNonBlank("character"))
        }
    }
}

internal fun parseMovieDirectors(credits: JSONObject?): List<String> {
    val crewArray = credits?.optJSONArray("crew") ?: return emptyList()
    val output = mutableListOf<String>()
    for (index in 0 until crewArray.length()) {
        val member = crewArray.optJSONObject(index) ?: continue
        if (!member.optStringNonBlank("job").equals("Director", ignoreCase = true)) continue
        val name = member.optStringNonBlank("name") ?: continue
        output += name
    }
    return output.distinct()
}

internal fun parseSeriesCreators(details: JSONObject): List<String> {
    val creators = details.optJSONArray("created_by") ?: return emptyList()
    val output = mutableListOf<String>()
    for (index in 0 until creators.length()) {
        val name = creators.optJSONObject(index)?.optStringNonBlank("name") ?: continue
        output += name
    }
    return output.distinct()
}

internal fun parseProductionEntities(
    details: JSONObject,
    mediaType: MetadataLabMediaType,
): List<TmdbProductionEntity> {
    val array =
        when (mediaType) {
            MetadataLabMediaType.MOVIE -> details.optJSONArray("production_companies")
            MetadataLabMediaType.SERIES -> details.optJSONArray("networks")
            MetadataLabMediaType.ANIME -> details.optJSONArray("networks")
        } ?: return emptyList()

    return buildList {
        for (index in 0 until minOf(array.length(), 24)) {
            val entity = array.optJSONObject(index) ?: continue
            val id = entity.optInt("id", 0)
            val name = entity.optStringNonBlank("name") ?: continue
            add(
                TmdbProductionEntity(
                    id = id,
                    name = name,
                    logoUrl = TmdbApi.imageUrl(entity.optStringNonBlank("logo_path"), "w185"),
                )
            )
        }
    }
}

internal fun parseTrailers(videos: JSONObject?): List<TmdbTrailer> {
    val results = videos?.optJSONArray("results") ?: return emptyList()
    return buildList {
        for (index in 0 until results.length()) {
            val item = results.optJSONObject(index) ?: continue
            val site = item.optStringNonBlank("site") ?: continue
            if (!site.equals("YouTube", ignoreCase = true)) continue
            val key = item.optStringNonBlank("key") ?: continue
            val type = item.optStringNonBlank("type") ?: continue
            val typeLower = type.lowercase(Locale.US)
            if (typeLower != "trailer" && typeLower != "teaser") continue
            add(
                TmdbTrailer(
                    id = item.optStringNonBlank("id") ?: key,
                    name = item.optStringNonBlank("name") ?: type,
                    site = site,
                    key = key,
                    type = type,
                    official = item.optBoolean("official"),
                    thumbnailUrl = TmdbApi.youtubeThumbnailUrl(key),
                    watchUrl = TmdbApi.youtubeWatchUrl(key),
                )
            )
        }
    }.sortedWith(
        compareByDescending<TmdbTrailer> { it.official }
            .thenBy { it.type.lowercase(Locale.US) }
            .thenBy { it.name.lowercase(Locale.US) }
    ).take(12)
}

internal fun parseReviews(reviews: JSONObject?): List<TmdbReview> {
    val results = reviews?.optJSONArray("results") ?: return emptyList()
    return buildList {
        for (index in 0 until minOf(results.length(), 12)) {
            val item = results.optJSONObject(index) ?: continue
            val id = item.optStringNonBlank("id") ?: continue
            val content = item.optStringNonBlank("content") ?: continue
            val authorDetails = item.optJSONObject("author_details")
            add(
                TmdbReview(
                    id = id,
                    author = item.optStringNonBlank("author") ?: "Unknown",
                    rating = authorDetails?.optDoubleOrNull("rating"),
                    content = content,
                    createdAt = item.optStringNonBlank("created_at"),
                )
            )
        }
    }
}

internal fun parseBackdropUrls(
    images: JSONObject?,
    preferredLanguage: String,
): List<String> {
    val backdrops = images?.optJSONArray("backdrops")?.toJsonObjectList() ?: return emptyList()
    if (backdrops.isEmpty()) return emptyList()

    val language = preferredLanguage.substringBefore('-').trim().ifBlank { "en" }

    fun languageScore(iso6391: String?): Int {
        return when {
            iso6391.equals(language, ignoreCase = true) -> 0
            iso6391.equals("en", ignoreCase = true) -> 1
            iso6391 == null -> 2
            else -> 3
        }
    }

    return backdrops
        .mapNotNull { backdrop ->
            val filePath = backdrop.optStringNonBlank("file_path") ?: return@mapNotNull null
            BackdropCandidate(
                filePath = filePath,
                languageScore = languageScore(backdrop.optStringNonBlank("iso_639_1")),
                voteAverage = backdrop.optDoubleOrNull("vote_average") ?: 0.0,
                voteCount = backdrop.optInt("vote_count", 0),
                width = backdrop.optInt("width", 0),
                height = backdrop.optInt("height", 0),
            )
        }.sortedWith(
            compareBy<BackdropCandidate> { it.languageScore }
                .thenByDescending { it.voteAverage }
                .thenByDescending { it.voteCount }
                .thenByDescending { it.width }
                .thenByDescending { it.height }
                .thenBy { it.filePath.lowercase(Locale.US) }
        ).mapNotNull { candidate ->
            TmdbApi.imageUrl(candidate.filePath, "w780")
        }.distinct()
}

internal fun parseTitleDetails(
    details: JSONObject,
    mediaType: MetadataLabMediaType,
): TmdbTitleDetails {
    val status = details.optStringNonBlank("status")
    val originalLanguage = details.optStringNonBlank("original_language")
    val originCountries =
        when (mediaType) {
            MetadataLabMediaType.MOVIE -> details.optJSONArray("origin_country")
            MetadataLabMediaType.SERIES -> details.optJSONArray("origin_country")
            MetadataLabMediaType.ANIME -> details.optJSONArray("origin_country")
        }?.let { array ->
            buildList {
                for (index in 0 until array.length()) {
                    array.optString(index)?.trim()?.takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        } ?: emptyList()
    val tagline = details.optStringNonBlank("tagline")

    return when (mediaType) {
        MetadataLabMediaType.MOVIE -> {
            TmdbMovieDetails(
                status = status,
                releaseDate = details.optStringNonBlank("release_date"),
                runtimeMinutes = details.optIntOrNull("runtime"),
                budget = details.optLongOrNull("budget"),
                revenue = details.optLongOrNull("revenue"),
                originalLanguage = originalLanguage,
                originCountries = originCountries,
                tagline = tagline,
            )
        }
        MetadataLabMediaType.SERIES -> {
            val episodeRunTime = details.optJSONArray("episode_run_time")
            TmdbTvDetails(
                status = status,
                firstAirDate = details.optStringNonBlank("first_air_date"),
                lastAirDate = details.optStringNonBlank("last_air_date"),
                numberOfSeasons = details.optIntOrNull("number_of_seasons"),
                numberOfEpisodes = details.optIntOrNull("number_of_episodes"),
                episodeRunTimeMinutes =
                    buildList {
                        if (episodeRunTime != null) {
                            for (index in 0 until episodeRunTime.length()) {
                                val value = episodeRunTime.optInt(index)
                                if (value > 0) add(value)
                            }
                        }
                    },
                type = details.optStringNonBlank("type"),
                originalLanguage = originalLanguage,
                originCountries = originCountries,
                tagline = tagline,
            )
        }
        MetadataLabMediaType.ANIME -> {
            val episodeRunTime = details.optJSONArray("episode_run_time")
            TmdbTvDetails(
                status = status,
                firstAirDate = details.optStringNonBlank("first_air_date"),
                lastAirDate = details.optStringNonBlank("last_air_date"),
                numberOfSeasons = details.optIntOrNull("number_of_seasons"),
                numberOfEpisodes = details.optIntOrNull("number_of_episodes"),
                episodeRunTimeMinutes =
                    buildList {
                        if (episodeRunTime != null) {
                            for (index in 0 until episodeRunTime.length()) {
                                val value = episodeRunTime.optInt(index)
                                if (value > 0) add(value)
                            }
                        }
                    },
                type = details.optStringNonBlank("type"),
                originalLanguage = originalLanguage,
                originCountries = originCountries,
                tagline = tagline,
            )
        }
    }
}

internal fun parseRecommendationIds(recommendations: JSONObject?): List<String> {
    val results = recommendations?.optJSONArray("results") ?: return emptyList()
    return buildList {
        for (index in 0 until minOf(results.length(), 20)) {
            val entry = results.optJSONObject(index) ?: continue
            val id = entry.optInt("id", -1)
            if (id > 0) {
                add("tmdb:$id")
            }
        }
    }
}

internal fun parseMovieCollectionIds(details: JSONObject): List<String> {
    val collection = details.optJSONObject("belongs_to_collection") ?: return emptyList()
    val collectionId = collection.optInt("id", -1)
    return if (collectionId > 0) {
        listOf("tmdb:collection:$collectionId")
    } else {
        emptyList()
    }
}

internal fun parseMetadataSeasons(details: JSONObject, tmdbId: Int): List<MetadataSeason> {
    val seasons = details.optJSONArray("seasons") ?: return emptyList()
    return buildList {
        for (index in 0 until seasons.length()) {
            val seasonObject = seasons.optJSONObject(index) ?: continue
            val seasonNumber = seasonObject.optInt("season_number", -1)
            if (seasonNumber <= 0) continue
            val episodeCount = seasonObject.optInt("episode_count", -1)
            if (episodeCount <= 0) continue

            add(
                MetadataSeason(
                    id = "tmdb:$tmdbId:season:$seasonNumber",
                    name = seasonObject.optStringNonBlank("name") ?: "Season $seasonNumber",
                    overview = seasonObject.optStringNonBlank("overview") ?: "",
                    seasonNumber = seasonNumber,
                    episodeCount = episodeCount,
                    airDate = seasonObject.optStringNonBlank("air_date"),
                )
            )
        }
    }
}

internal fun parseSeasonEpisodes(
    seasonJson: JSONObject,
    fallbackSeasonNumber: Int,
): List<TmdbSeasonEpisode> {
    val episodes = seasonJson.optJSONArray("episodes") ?: return emptyList()
    return buildList {
        for (index in 0 until episodes.length()) {
            val episode = episodes.optJSONObject(index) ?: continue
            val episodeNumber = episode.optInt("episode_number", 0)
            if (episodeNumber <= 0) continue
            add(
                TmdbSeasonEpisode(
                    seasonNumber = episode.optInt("season_number", fallbackSeasonNumber).takeIf { it > 0 } ?: fallbackSeasonNumber,
                    episodeNumber = episodeNumber,
                    name = episode.optStringNonBlank("name") ?: "Episode $episodeNumber",
                    airDate = episode.optStringNonBlank("air_date"),
                    overview = episode.optStringNonBlank("overview"),
                    stillUrl = TmdbApi.imageUrl(episode.optStringNonBlank("still_path"), "w300"),
                )
            )
        }
    }.sortedWith(compareBy<TmdbSeasonEpisode> { it.episodeNumber }.thenBy { it.name })
}

internal fun resolveTitle(
    details: JSONObject,
    mediaType: MetadataLabMediaType,
): String? {
    return when (mediaType) {
        MetadataLabMediaType.MOVIE -> details.optStringNonBlank("title") ?: details.optStringNonBlank("original_title")
        MetadataLabMediaType.SERIES -> details.optStringNonBlank("name") ?: details.optStringNonBlank("original_name")
        MetadataLabMediaType.ANIME -> details.optStringNonBlank("name") ?: details.optStringNonBlank("original_name")
    }
}

private data class BackdropCandidate(
    val filePath: String,
    val languageScore: Int,
    val voteAverage: Double,
    val voteCount: Int,
    val width: Int,
    val height: Int,
)
