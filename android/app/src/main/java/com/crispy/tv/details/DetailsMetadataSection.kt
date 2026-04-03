package com.crispy.tv.details

import com.crispy.tv.backend.CrispyBackendClient
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
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun buildDetailsRows(
    details: MediaDetails,
    titleDetail: CrispyBackendClient.MetadataTitleDetailResponse?,
    content: CrispyBackendClient.MetadataContentView?,
): List<Pair<String, String>> {
    val rows = mutableListOf<Pair<String, String>>()
    val item = titleDetail?.item
    val production = titleDetail?.production

    if (
        details.mediaType.equals("series", ignoreCase = true) ||
            details.mediaType.equals("anime", ignoreCase = true)
    ) {
        item?.status?.takeIf { it.isNotBlank() }?.let { rows += "STATUS" to it }

        formatLongDate(item?.releaseDate)?.let { rows += "FIRST AIR DATE" to it }

        titleDetail?.seasons?.size?.takeIf { it > 0 }?.let { rows += "SEASONS" to "$it" }
        item?.episodeCount?.takeIf { it > 0 }?.let { rows += "EPISODES" to "$it" }

        details.runtime?.takeIf { it.isNotBlank() }?.let { rows += "EPISODE RUNTIME" to it }

        val originCountries = production?.originCountries.orEmpty().ifEmpty { production?.productionCountries.orEmpty() }
        if (originCountries.isNotEmpty()) {
            rows += "ORIGIN COUNTRY" to originCountries.joinToString(", ")
        }

        production?.originalLanguage?.takeIf { it.isNotBlank() }?.let {
            rows += "ORIGINAL LANGUAGE" to it.uppercase()
        }

        if (details.creators.isNotEmpty()) {
            rows += "CREATED BY" to details.creators.joinToString(", ")
        }
    } else {
        content?.description?.takeIf { it.isNotBlank() && !it.equals(details.description, ignoreCase = true) }?.let {
            rows += "PLOT" to it
        }
        item?.status?.takeIf { it.isNotBlank() }?.let { rows += "STATUS" to it }

        formatLongDate(item?.releaseDate ?: content?.released)?.let { rows += "RELEASE DATE" to it }

        (details.runtime?.takeIf { it.isNotBlank() } ?: formatRuntimeMinutes(content?.runtime))?.let {
            rows += "RUNTIME" to it
        }

        formatCurrency(content?.revenue)?.let {
            rows += "REVENUE" to it
        }

        formatCurrency(content?.budget)?.let {
            rows += "BUDGET" to it
        }

        val originCountry = content?.country?.takeIf { it.isNotBlank() }
            ?: production?.originCountries?.takeIf { it.isNotEmpty() }?.joinToString(", ")
            ?: production?.productionCountries?.takeIf { it.isNotEmpty() }?.joinToString(", ")
        originCountry?.let { rows += "ORIGIN COUNTRY" to it }

        val language = content?.language?.takeIf { it.isNotBlank() }
            ?: production?.originalLanguage?.takeIf { it.isNotBlank() }?.uppercase()
        language?.let { rows += "LANGUAGE" to it }
    }

    return rows
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

private fun formatCurrency(amount: Long?): String? {
    val value = amount?.takeIf { it > 0L } ?: return null
    return NumberFormat.getCurrencyInstance(Locale.US).format(value)
}
