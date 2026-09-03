package com.crispy.tv.plugins

data class PluginStreamInput(
    val tmdbId: Int,
    val imdbId: String?,
    val mediaType: String,
    val season: Int?,
    val episode: Int?,
    val title: String,
    val year: Int?,
    val settings: Map<String, String> = emptyMap(),
)

data class PluginStream(
    val name: String,
    val url: String,
    val quality: String?,
    val headers: Map<String, String>,
    val referer: String?,
    val subtitles: List<PluginSubtitle>,
    val sizeBytes: Long?,
    val audio: String?,
    val filename: String?,
)

data class PluginSubtitle(
    val url: String,
    val lang: String,
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
