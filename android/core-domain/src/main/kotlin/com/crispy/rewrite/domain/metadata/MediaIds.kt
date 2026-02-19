package com.crispy.rewrite.domain.metadata

data class NuvioMediaId(
    val contentId: String,
    val videoId: String?,
    val isEpisode: Boolean,
    val season: Int?,
    val episode: Int?,
    val kind: String,
    val addonLookupId: String
)

fun normalizeNuvioMediaId(raw: String): NuvioMediaId {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) {
        return NuvioMediaId(
            contentId = "",
            videoId = null,
            isEpisode = false,
            season = null,
            episode = null,
            kind = "content",
            addonLookupId = ""
        )
    }

    val normalized = stripSeriesPrefix(trimmed)
    val parts = normalized.split(":")
    if (parts.size >= 3) {
        val season = parts[parts.lastIndex - 1].toIntOrNull()
        val episode = parts.last().toIntOrNull()
        if (season != null && season > 0 && episode != null && episode > 0) {
            val baseId = stripSeriesPrefix(parts.dropLast(2).joinToString(":").trim())
            if (baseId.isNotEmpty()) {
                val videoId = "$baseId:$season:$episode"
                return NuvioMediaId(
                    contentId = baseId,
                    videoId = videoId,
                    isEpisode = true,
                    season = season,
                    episode = episode,
                    kind = "episode",
                    addonLookupId = videoId
                )
            }
        }
    }

    return NuvioMediaId(
        contentId = normalized,
        videoId = null,
        isEpisode = false,
        season = null,
        episode = null,
        kind = "content",
        addonLookupId = normalized
    )
}

private fun stripSeriesPrefix(value: String): String {
    if (!value.startsWith("series:")) {
        return value
    }

    val remainder = value.removePrefix("series:")
    return if (remainder.isEmpty()) value else remainder
}
