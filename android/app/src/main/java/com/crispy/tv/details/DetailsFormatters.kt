package com.crispy.tv.details

import com.crispy.tv.home.MediaVideo
import com.crispy.tv.metadata.tmdb.TmdbEnrichment
import com.crispy.tv.metadata.tmdb.TmdbMovieDetails
import com.crispy.tv.metadata.tmdb.TmdbTitleDetails
import com.crispy.tv.metadata.tmdb.TmdbTvDetails
import java.util.Locale

internal fun tmdbFacts(
    tmdb: TmdbEnrichment,
    titleDetails: TmdbTitleDetails
): List<String> {
    val out = ArrayList<String>(12)

    tmdb.imdbId?.takeIf { it.isNotBlank() }?.let { out.add("IMDb: ${it.lowercase(Locale.US)}") }
    titleDetails.status?.takeIf { it.isNotBlank() }?.let { out.add("Status: $it") }

    when (titleDetails) {
        is TmdbMovieDetails -> {
            titleDetails.releaseDate?.takeIf { it.isNotBlank() }?.let { out.add("Release: $it") }
            titleDetails.runtimeMinutes?.takeIf { it > 0 }?.let { out.add("Runtime: ${it}m") }
            titleDetails.budget?.let { formatMoneyShort(it) }?.let { out.add("Budget: $it") }
            titleDetails.revenue?.let { formatMoneyShort(it) }?.let { out.add("Revenue: $it") }
        }

        is TmdbTvDetails -> {
            titleDetails.firstAirDate?.takeIf { it.isNotBlank() }?.let { out.add("First air: $it") }
            titleDetails.lastAirDate?.takeIf { it.isNotBlank() }?.let { out.add("Last air: $it") }
            titleDetails.numberOfSeasons?.takeIf { it > 0 }?.let { out.add("Seasons: $it") }
            titleDetails.numberOfEpisodes?.takeIf { it > 0 }?.let { out.add("Episodes: $it") }
            titleDetails.episodeRunTimeMinutes.firstOrNull()?.takeIf { it > 0 }?.let { out.add("Ep: ${it}m") }
            titleDetails.type?.takeIf { it.isNotBlank() }?.let { out.add("Type: $it") }
        }
    }

    titleDetails.originalLanguage?.takeIf { it.isNotBlank() }?.let { out.add("Lang: ${it}") }
    if (titleDetails.originCountries.isNotEmpty()) {
        out.add("Country: ${titleDetails.originCountries.take(3).joinToString()}")
    }

    return out
}

internal fun formatMoneyShort(amount: Long): String? {
    if (amount <= 0L) return null
    val abs = amount.toDouble()
    val (value, suffix) =
        when {
            abs >= 1_000_000_000 -> abs / 1_000_000_000 to "B"
            abs >= 1_000_000 -> abs / 1_000_000 to "M"
            abs >= 1_000 -> abs / 1_000 to "K"
            else -> abs to ""
        }
    val formatted =
        if (value >= 10 || suffix.isEmpty()) {
            String.format(Locale.US, "%.0f", value)
        } else {
            String.format(Locale.US, "%.1f", value).removeSuffix(".0")
        }
    return "$$formatted$suffix"
}

internal fun episodePrefix(video: MediaVideo): String? {
    val season = video.season
    val episode = video.episode
    return when {
        season != null && episode != null -> "S${season}E${episode}"
        episode != null -> "E${episode}"
        else -> null
    }
}

internal fun parseRuntimeMinutes(runtime: String?): Int? {
    val input = runtime?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val hourMatch = Regex("(\\d+)\\s*h").find(input)
    val minMatch = Regex("(\\d+)\\s*min").find(input)
    if (hourMatch != null || minMatch != null) {
        val hours = hourMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val minutes = minMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val total = (hours * 60) + minutes
        return total.takeIf { it > 0 }
    }

    return input.toIntOrNull()?.takeIf { it > 0 }
}

internal fun formatRuntime(runtime: String?): String? {
    val input = runtime?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val hourMatch = Regex("(\\d+)\\s*h").find(input)
    val minMatch = Regex("(\\d+)\\s*min").find(input)
    if (hourMatch != null || minMatch != null) {
        val hours = hourMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val minutes = minMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        return buildString {
            if (hours > 0) append("${hours}H")
            if (hours > 0 && minutes > 0) append(" ")
            if (minutes > 0) append("${minutes}M")
            if (isEmpty()) append(input.uppercase())
        }
    }

    val numericMinutes = input.toIntOrNull()
    if (numericMinutes != null && numericMinutes > 0) {
        val hours = numericMinutes / 60
        val minutes = numericMinutes % 60
        return buildString {
            if (hours > 0) append("${hours}H")
            if (hours > 0 && minutes > 0) append(" ")
            if (minutes > 0) append("${minutes}M")
        }
    }

    return input.uppercase()
}

internal fun formatRuntimeForHeader(runtime: String?): String? {
    val input = runtime?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val hourMatch = Regex("(\\d+)\\s*h").find(input)
    val minMatch = Regex("(\\d+)\\s*min").find(input)

    fun human(hours: Int, minutes: Int): String? {
        if (hours <= 0 && minutes <= 0) return null
        return buildString {
            if (hours > 0) append("${hours} hr")
            if (hours > 0 && minutes > 0) append(" ")
            if (minutes > 0) append("${minutes} min")
        }
    }

    if (hourMatch != null || minMatch != null) {
        val hours = hourMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val minutes = minMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        return human(hours, minutes) ?: input
    }

    val numericMinutes = input.toIntOrNull()
    if (numericMinutes != null && numericMinutes > 0) {
        val hours = numericMinutes / 60
        val minutes = numericMinutes % 60
        return human(hours, minutes)
    }

    return input
}
