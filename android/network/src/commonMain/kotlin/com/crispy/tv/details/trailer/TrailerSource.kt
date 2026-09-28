package com.crispy.tv.details.trailer

enum class TrailerSource { DIRECT, YOUTUBE }

fun classifyTrailerSource(url: String): TrailerSource {
    val lower = url.lowercase()
    return if (lower.contains("youtube.com/watch") || lower.contains("youtu.be/")) {
        TrailerSource.YOUTUBE
    } else {
        TrailerSource.DIRECT
    }
}

data class TrailerPlaybackSource(
    val videoUrl: String,
    val audioUrl: String? = null,
)

private val YOUTUBE_VIDEO_ID_REGEX = Regex("(?:[?&]v=|youtu\\.be/|/shorts/|/embed/)([A-Za-z0-9_-]{6,})")

/** Accepts bare ids plus watch/shorts/embed/youtu.be links. */
fun extractYouTubeVideoId(value: String): String? {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return null
    if (!trimmed.contains("youtu", ignoreCase = true)) return trimmed
    return YOUTUBE_VIDEO_ID_REGEX.find(trimmed)?.groupValues?.getOrNull(1) ?: trimmed
}
