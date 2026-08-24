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
