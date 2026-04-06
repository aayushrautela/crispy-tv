package com.crispy.tv.details

import com.crispy.tv.home.MediaVideo
import kotlin.math.roundToInt

internal fun episodePrefix(video: MediaVideo): String? {
    val season = video.season ?: return null
    val episode = video.episode ?: return null
    return "S${season.toString().padStart(2, '0')} E${episode.toString().padStart(2, '0')}"
}

internal fun parseRuntimeMinutes(runtime: String?): Int? {
    val text = runtime?.trim().orEmpty()
    if (text.isBlank()) return null
    return Regex("(\\d+)").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
}

internal fun formatRuntime(minutes: Int?): String? {
    val value = minutes?.takeIf { it > 0 } ?: return null
    val hours = value / 60
    val remainingMinutes = value % 60
    return when {
        hours > 0 && remainingMinutes > 0 -> "${hours}h ${remainingMinutes}m"
        hours > 0 -> "${hours}h"
        else -> "${remainingMinutes}m"
    }
}

internal fun formatRuntimeForHeader(runtime: String?): String? {
    return formatRuntime(parseRuntimeMinutes(runtime))
        ?: runtime?.trim()?.takeIf { it.isNotBlank() }
}

internal fun formatMoneyShort(amount: Long?): String? {
    val value = amount ?: return null
    if (value <= 0L) return null
    val billion = 1_000_000_000L
    val million = 1_000_000L
    return when {
        value >= billion -> "$${((value.toDouble() / billion) * 10).roundToInt() / 10.0}B"
        value >= million -> "$${((value.toDouble() / million) * 10).roundToInt() / 10.0}M"
        else -> "$${value}"
    }
}
