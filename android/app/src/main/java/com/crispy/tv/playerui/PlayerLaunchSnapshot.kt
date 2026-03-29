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
    val provider: String? = null,
    val providerId: String? = null,
    val parentProvider: String? = null,
    val parentProviderId: String? = null,
    val absoluteEpisodeNumber: Int? = null,
)

@Immutable
data class PlayerLaunchSnapshot(
    val contentId: String,
    val imdbId: String? = null,
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
    val provider: String? = null,
    val providerId: String? = null,
    val parentMediaType: String? = null,
    val parentProvider: String? = null,
    val parentProviderId: String? = null,
    val absoluteEpisodeNumber: Int? = null,
) {
    fun toJsonString(): String {
        return JSONObject()
            .put("content_id", contentId)
            .put("imdb_id", imdbId)
            .put("season_number", seasonNumber)
            .put("episode_number", episodeNumber)
            .put("media_type", mediaType)
            .put("provider", provider)
            .put("provider_id", providerId)
            .put("parent_media_type", parentMediaType)
            .put("parent_provider", parentProvider)
            .put("parent_provider_id", parentProviderId)
            .put("absolute_episode_number", absoluteEpisodeNumber)
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
                                .put("provider", episode.provider)
                                .put("provider_id", episode.providerId)
                                .put("parent_provider", episode.parentProvider)
                                .put("parent_provider_id", episode.parentProviderId)
                                .put("absolute_episode_number", episode.absoluteEpisodeNumber)
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
            tmdbId = null,
            showTmdbId = null,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            addonId = null,
            provider = provider,
            providerId = providerId,
            parentMediaType = parentMediaType,
            parentProvider = parentProvider,
            parentProviderId = parentProviderId,
            absoluteEpisodeNumber = absoluteEpisodeNumber,
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
                    seasonNumber = json.optInt("season_number").takeIf { it > 0 },
                    episodeNumber = json.optInt("episode_number").takeIf { it > 0 },
                    mediaType = json.optString("media_type").trim(),
                    provider = json.optString("provider").trim().ifBlank { null },
                    providerId = json.optString("provider_id").trim().ifBlank { null },
                    parentMediaType = json.optString("parent_media_type").trim().ifBlank { null },
                    parentProvider = json.optString("parent_provider").trim().ifBlank { null },
                    parentProviderId = json.optString("parent_provider_id").trim().ifBlank { null },
                    absoluteEpisodeNumber = json.optInt("absolute_episode_number").takeIf { it > 0 },
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
        tmdbId = null,
        showTmdbId = null,
        provider = provider,
        providerId = providerId,
        parentProvider = parentProvider,
        parentProviderId = parentProviderId,
        absoluteEpisodeNumber = absoluteEpisodeNumber,
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
                    provider = json.optString("provider").trim().ifBlank { null },
                    providerId = json.optString("provider_id").trim().ifBlank { null },
                    parentProvider = json.optString("parent_provider").trim().ifBlank { null },
                    parentProviderId = json.optString("parent_provider_id").trim().ifBlank { null },
                    absoluteEpisodeNumber = json.optInt("absolute_episode_number").takeIf { it > 0 },
                )
            )
        }
    }
}
