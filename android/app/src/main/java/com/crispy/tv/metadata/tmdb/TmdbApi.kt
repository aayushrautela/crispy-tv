package com.crispy.tv.metadata.tmdb

internal object TmdbApi {
    const val BASE_URL: String = "https://api.themoviedb.org/3"
    private const val IMAGE_BASE_URL: String = "https://image.tmdb.org/t/p/"

    fun imageUrl(path: String?, size: String): String? {
        val trimmed = path?.trim().orEmpty()
        if (trimmed.isBlank()) return null
        val normalized = if (trimmed.startsWith('/')) trimmed else "/$trimmed"
        return "$IMAGE_BASE_URL$size$normalized"
    }

    fun resizedImageUrl(url: String?, size: String): String? {
        val trimmed = url?.trim().orEmpty()
        if (trimmed.isBlank()) return null
        if (!trimmed.startsWith(IMAGE_BASE_URL)) return trimmed

        val relativePath = trimmed.removePrefix(IMAGE_BASE_URL)
        val firstSlash = relativePath.indexOf('/')
        if (firstSlash <= 0 || firstSlash == relativePath.lastIndex) return trimmed

        val path = relativePath.substring(firstSlash)
        return "$IMAGE_BASE_URL$size$path"
    }

    fun youtubeWatchUrl(key: String?): String? {
        val trimmed = key?.trim().orEmpty()
        if (trimmed.isBlank()) return null
        return "https://www.youtube.com/watch?v=$trimmed"
    }

    fun youtubeThumbnailUrl(key: String?): String? {
        val trimmed = key?.trim().orEmpty()
        if (trimmed.isBlank()) return null
        return "https://i.ytimg.com/vi/$trimmed/hqdefault.jpg"
    }
}
