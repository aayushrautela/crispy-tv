package com.crispy.tv.plugins

/**
 * Exactly what crosses into plugin JS. Mirrors the Nuvio contract:
 * `getStreams(tmdbId, mediaType, season, episode)` — tmdbId is a string,
 * mediaType is "movie" or "tv", season/episode are numbers or undefined.
 * App-side metadata (title, year, imdbId) is intentionally NOT exposed.
 */
data class PluginStreamInput(
    val tmdbId: String,
    val mediaType: String,
    val season: Int?,
    val episode: Int?,
)

/**
 * One stream row returned by plugin JS. Field names mirror the Nuvio result schema
 * (`title`/`name`, `url` string or `{url}` object, string `size`, `language`,
 * `provider`, `type`, `seeders`/`peers`, `infoHash`, subtitles with
 * `language`/`name`/`headers`) plus our extras (`referer`, `audio`, `filename`).
 * Either [url] or [infoHash] must be present for the row to survive mapping.
 */
data class PluginStream(
    val name: String,
    val title: String?,
    val url: String?,
    val quality: String?,
    val headers: Map<String, String>,
    val referer: String?,
    val subtitles: List<PluginSubtitle>,
    val sizeBytes: Long?,
    val sizeLabel: String?,
    val language: String?,
    val provider: String?,
    val type: String?,
    val seeders: Int?,
    val peers: Int?,
    val infoHash: String?,
    val audio: String?,
    val filename: String?,
)

data class PluginSubtitle(
    val url: String,
    val lang: String,
    val name: String?,
    val headers: Map<String, String>,
)

data class PluginSetting(
    val key: String,
    val label: String,
    val type: String,
    val default: String?,
    val options: List<String>,
)

data class PluginExecutionResult(
    val streams: List<PluginStream>,
    val settings: List<PluginSetting>,
)

object PluginMediaTypes {
    const val MOVIE = "movie"
    const val SERIES = "series"
}

/** Canonical media type for the plugin boundary. Mirrors Nuvio's normalizePluginType. */
internal fun normalizePluginMediaType(value: String): String =
    when (value.lowercase()) {
        "series", "show", "other" -> "tv"
        else -> value.lowercase()
    }
