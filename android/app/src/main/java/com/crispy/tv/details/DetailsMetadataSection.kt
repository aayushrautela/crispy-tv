package com.crispy.tv.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crispy.tv.home.MediaDetails
import com.crispy.tv.metadata.tmdb.TmdbMovieDetails
import com.crispy.tv.metadata.tmdb.TmdbTitleDetails
import com.crispy.tv.metadata.tmdb.TmdbTvDetails
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun buildDetailsRows(
    details: MediaDetails,
    titleDetails: TmdbTitleDetails?
): List<Pair<String, String>> {
    val rows = mutableListOf<Pair<String, String>>()

    when (titleDetails) {
        is TmdbTvDetails -> {
            titleDetails.status?.takeIf { it.isNotBlank() }?.let { rows += "STATUS" to it }

            formatLongDate(titleDetails.firstAirDate)?.let { rows += "FIRST AIR DATE" to it }
            formatLongDate(titleDetails.lastAirDate)?.let { rows += "LAST AIR DATE" to it }

            titleDetails.numberOfSeasons?.takeIf { it > 0 }?.let { rows += "SEASONS" to "$it" }
            titleDetails.numberOfEpisodes?.takeIf { it > 0 }?.let { rows += "EPISODES" to "$it" }

            val episodeRuntime = titleDetails.episodeRunTimeMinutes.filter { it > 0 }
            if (episodeRuntime.isNotEmpty()) {
                rows += "EPISODE RUNTIME" to "${episodeRuntime.joinToString(" - ")} min"
            }

            if (titleDetails.originCountries.isNotEmpty()) {
                rows += "ORIGIN COUNTRY" to titleDetails.originCountries.joinToString(", ")
            }

            titleDetails.originalLanguage?.takeIf { it.isNotBlank() }?.let {
                rows += "ORIGINAL LANGUAGE" to it.uppercase()
            }

            if (details.creators.isNotEmpty()) {
                rows += "CREATED BY" to details.creators.joinToString(", ")
            }
        }

        is TmdbMovieDetails -> {
            titleDetails.tagline?.takeIf { it.isNotBlank() }?.let {
                rows += "TAGLINE" to "\"$it\""
            }
            titleDetails.status?.takeIf { it.isNotBlank() }?.let { rows += "STATUS" to it }

            formatLongDate(titleDetails.releaseDate)?.let { rows += "RELEASE DATE" to it }

            val runtime = formatRuntimeMinutes(titleDetails.runtimeMinutes) ?: details.runtime?.takeIf { it.isNotBlank() }
            runtime?.let { rows += "RUNTIME" to it }

            formatCurrency(titleDetails.budget)?.let { rows += "BUDGET" to it }
            formatCurrency(titleDetails.revenue)?.let { rows += "REVENUE" to it }

            if (titleDetails.originCountries.isNotEmpty()) {
                rows += "ORIGIN COUNTRY" to titleDetails.originCountries.joinToString(", ")
            }

            titleDetails.originalLanguage?.takeIf { it.isNotBlank() }?.let {
                rows += "ORIGINAL LANGUAGE" to it.uppercase()
            }
        }

        else -> Unit
    }

    return rows
}

internal fun formatCurrency(amount: Long?): String? {
    if (amount == null || amount <= 0) return null
    return try {
        NumberFormat
            .getCurrencyInstance(Locale.US)
            .apply { maximumFractionDigits = 0 }
            .format(amount)
    } catch (_: Throwable) {
        "$amount"
    }
}

internal fun formatLongDate(date: String?): String? {
    val raw = date?.trim().orEmpty()
    if (raw.isBlank()) return null

    val iso = if (raw.length >= 10) raw.take(10) else raw
    return try {
        val parsed = LocalDate.parse(iso)
        parsed.format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US))
    } catch (_: Throwable) {
        date
    }
}

internal fun formatRuntimeMinutes(minutes: Int?): String? {
    if (minutes == null || minutes <= 0) return null
    val h = minutes / 60
    val m = minutes % 60

    return when {
        h > 0 && m > 0 -> "$h hr $m min"
        h > 0 -> "$h hr"
        else -> "$m min"
    }
}
