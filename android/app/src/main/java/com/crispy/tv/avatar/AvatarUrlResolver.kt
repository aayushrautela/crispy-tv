package com.crispy.tv.avatar

import com.crispy.tv.domain.account.DicebearStyle

object AvatarUrlResolver {
    private const val DICEBEAR_HOST = "api.dicebear.com"
    private const val DICEBEAR_VERSION = "v9"
    private const val DICEBEAR_FORMAT = "svg"
    private val DICEBEAR_BASE = "https://$DICEBEAR_HOST/$DICEBEAR_VERSION"

    fun dicebearUrl(style: DicebearStyle = DicebearStyle.THUMBS, seed: String? = null): String {
        val styleValue = style.apiValue
        val base = "$DICEBEAR_BASE/$styleValue/$DICEBEAR_FORMAT"
        val safeSeed = seed?.trim()?.ifBlank { null }
            ?.replace("[^A-Za-z0-9._~-]".toRegex(), "-")
            ?.take(64)
        return if (safeSeed != null) "$base?seed=$safeSeed" else base
    }

    fun resolveAvatarUrl(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isBlank()) return null
        if (trimmed.startsWith("https://", ignoreCase = true) && isDicebearUrl(trimmed)) {
            return trimmed
        }
        return null
    }

    fun isDicebearUrl(url: String): Boolean {
        if (!url.startsWith("https://", ignoreCase = true)) return false
        val parsed = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        if (!parsed.host.equals(DICEBEAR_HOST, ignoreCase = true)) return false
        val segments = parsed.path?.split("/")?.filter { it.isNotBlank() } ?: return false
        if (segments.size != 3) return false
        val (version, style, format) = segments
        if (!version.equals(DICEBEAR_VERSION, ignoreCase = true)) return false
        if (format.lowercase() !in listOf("svg", "png", "webp", "avif")) return false
        return DicebearStyle.SUPPORTED_DICEBEAR_STYLES.any { it.apiValue.equals(style, ignoreCase = true) }
    }
}
