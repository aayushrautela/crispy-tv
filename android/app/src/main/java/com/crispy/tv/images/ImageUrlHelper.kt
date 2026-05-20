package com.crispy.tv.images

internal object ImageUrlHelper {
    fun resizedImageUrl(url: String?, size: String): String? {
        val raw = url?.trim().orEmpty()
        if (raw.isBlank()) return null
        return "$raw/$size"
    }
}
