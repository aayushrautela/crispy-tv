package com.crispy.tv.playerui

import androidx.compose.runtime.Immutable
import com.crispy.tv.home.MediaDetails
import com.crispy.tv.home.MediaVideo
import org.json.JSONArray
import org.json.JSONObject

@Immutable
data class PlayerEpisodeSnapshot(
    val id: String,
    val title: String,
    val season: Int? = null,
    val episode: Int? = null,
    val released: String? = null,
    val overview: String? = null,
    val thumbnailUrl: String? = null,
    val lookupId: String? = null,
    val tmdbId: Int? = null,
    val showTmdbId: Int? = null,
)

@Immutable
data class PlayerLaunchSnapshot(
    val contentId: String,
    val imdbId: String? = null,
    val mediaKey: String? = null,
    val tmdbId: Int? = null,
    val showTmdbId: Int? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val mediaType: String,
    val title: String,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val description: String? = null,
    val genres: List<String> = emptyList(),
    val year: String? = null,
    val runtime: String? = null,
    val certification: String? = null,
    val rating: String? = null,
    val cast: List<String> = emptyList(),
    val seasons: List<Int> = emptyList(),
    val selectedSeason: Int? = null,
    val seasonEpisodes: List<PlayerEpisodeSnapshot> = emptyList(),
    val currentEpisodeId: String? = null,
) {
    fun toJsonString(): String {
        return JSONObject()
            .put("content_id", contentId)
            .put("imdb_id", imdbId)
            .put("media_key", mediaKey)
            .put("tmdb_id", tmdbId)
            .put("show_tmdb_id", showTmdbId)
            .put("season_number", seasonNumber)
            .put("episode_number", episodeNumber)
            .put("media_type", mediaType)
            .put("title", title)
            .put("poster_url", posterUrl)
            .put("backdrop_url", backdropUrl)
            .put("description", description)
            .put("genres", JSONArray(genres))
            .put("year", year)
            .put("runtime", runtime)
            .put("certification", certification)
            .put("rating", rating)
            .put("cast", JSONArray(cast))
            .put("seasons", JSONArray(seasons))
            .put("selected_season", selectedSeason)
            .put(
                "season_episodes",
                JSONArray().apply {
                    seasonEpisodes.forEach { episode ->
                        put(
                            JSONObject()
                                .put("id", episode.id)
                                .put("title", episode.title)
                                .put("season", episode.season)
                                .put("episode", episode.episode)
                                .put("released", episode.released)
                                .put("overview", episode.overview)
                                .put("thumbnail_url", episode.thumbnailUrl)
                                .put("lookup_id", episode.lookupId)
                                .put("tmdb_id", episode.tmdbId)
                                .put("show_tmdb_id", episode.showTmdbId)
                        )
                    }
                },
            )
            .put("current_episode_id", currentEpisodeId)
            .toString()
    }

    fun toMediaDetails(): MediaDetails {
        return MediaDetails(
            id = contentId,
            imdbId = imdbId,
            mediaType = mediaType,
            title = title,
            posterUrl = posterUrl,
            backdropUrl = backdropUrl,
            logoUrl = null,
            description = description,
            genres = genres,
            year = year,
            runtime = runtime,
            certification = certification,
            rating = rating,
            cast = cast,
            directors = emptyList(),
            creators = emptyList(),
            videos = seasonEpisodes.map(PlayerEpisodeSnapshot::toMediaVideo),
            mediaKey = mediaKey,
            tmdbId = tmdbId,
            showTmdbId = showTmdbId,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            addonId = null,
        )
    }

    companion object {
        fun fromJsonString(value: String?): PlayerLaunchSnapshot? {
            val raw = value?.trim().orEmpty()
            if (raw.isBlank()) return null

            return runCatching {
                val json = JSONObject(raw)
                PlayerLaunchSnapshot(
                    contentId = json.optString("content_id").trim(),
                    imdbId = json.optString("imdb_id").trim().ifBlank { null },
                    mediaKey = json.optString("media_key").trim().ifBlank { null },
                    tmdbId = json.optInt("tmdb_id").takeIf { it > 0 },
                    showTmdbId = json.optInt("show_tmdb_id").takeIf { it > 0 },
                    seasonNumber = json.optInt("season_number").takeIf { it > 0 },
                    episodeNumber = json.optInt("episode_number").takeIf { it > 0 },
                    mediaType = json.optString("media_type").trim(),
                    title = json.optString("title").trim(),
                    posterUrl = json.optString("poster_url").trim().ifBlank { null },
                    backdropUrl = json.optString("backdrop_url").trim().ifBlank { null },
                    description = json.optString("description").trim().ifBlank { null },
                    genres = json.optJSONArray("genres").toStringList(),
                    year = json.optString("year").trim().ifBlank { null },
                    runtime = json.optString("runtime").trim().ifBlank { null },
                    certification = json.optString("certification").trim().ifBlank { null },
                    rating = json.optString("rating").trim().ifBlank { null },
                    cast = json.optJSONArray("cast").toStringList(),
                    seasons = json.optJSONArray("seasons").toIntList(),
                    selectedSeason = json.optInt("selected_season").takeIf { it > 0 },
                    seasonEpisodes = json.optJSONArray("season_episodes").toEpisodeSnapshots(),
                    currentEpisodeId = json.optString("current_episode_id").trim().ifBlank { null },
                ).takeIf { snapshot ->
                    snapshot.contentId.isNotBlank() && snapshot.mediaType.isNotBlank() && snapshot.title.isNotBlank()
                }
            }.getOrNull()
        }
    }
}

internal fun PlayerEpisodeSnapshot.toMediaVideo(): MediaVideo {
    return MediaVideo(
        id = id,
        title = title,
        season = season,
        episode = episode,
        released = released,
        overview = overview,
        thumbnailUrl = thumbnailUrl,
        lookupId = lookupId,
        tmdbId = tmdbId,
        showTmdbId = showTmdbId,
    )
}

private fun JSONArray?.toStringList(): List<String> {
    if (this == null || length() == 0) return emptyList()
    return buildList(length()) {
        for (index in 0 until length()) {
            val value = optString(index).trim()
            if (value.isNotBlank()) {
                add(value)
            }
        }
    }
}

private fun JSONArray?.toIntList(): List<Int> {
    if (this == null || length() == 0) return emptyList()
    return buildList(length()) {
        for (index in 0 until length()) {
            val value = optInt(index)
            if (value > 0) {
                add(value)
            }
        }
    }
}

private fun JSONArray?.toEpisodeSnapshots(): List<PlayerEpisodeSnapshot> {
    if (this == null || length() == 0) return emptyList()
    return buildList(length()) {
        for (index in 0 until length()) {
            val json = optJSONObject(index) ?: continue
            val id = json.optString("id").trim()
            val title = json.optString("title").trim()
            if (id.isBlank() || title.isBlank()) continue
            add(
                PlayerEpisodeSnapshot(
                    id = id,
                    title = title,
                    season = json.optInt("season").takeIf { it > 0 },
                    episode = json.optInt("episode").takeIf { it > 0 },
                    released = json.optString("released").trim().ifBlank { null },
                    overview = json.optString("overview").trim().ifBlank { null },
                    thumbnailUrl = json.optString("thumbnail_url").trim().ifBlank { null },
                    lookupId = json.optString("lookup_id").trim().ifBlank { null },
                    tmdbId = json.optInt("tmdb_id").takeIf { it > 0 },
                    showTmdbId = json.optInt("show_tmdb_id").takeIf { it > 0 },
                )
            )
        }
    }
}
