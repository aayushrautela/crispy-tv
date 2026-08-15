package com.crispy.tv.avatar

import com.crispy.tv.BuildConfig
import com.crispy.tv.domain.account.builtInAvatarUrl
import com.crispy.tv.domain.account.isSupportedAvatarId

object AvatarUrlResolver {
    private val backendBaseUrl: String
        get() = BuildConfig.CRISPY_BACKEND_URL.trim().trimEnd('/')

    fun isBuiltInAvatarId(raw: String?): Boolean = isSupportedAvatarId(raw)

    fun builtInAvatarUrl(id: String): String = builtInAvatarUrl(backendBaseUrl, id)

    /**
     * Resolves a stored avatar value to a loadable image URL.
     * - Absolute http(s) URLs are passed through unchanged (legacy/remote avatars).
     * - Built-in avatar ids (e.g. "avatar_03") resolve to the server-served PNG.
     * - Anything else returns null.
     */
    fun resolveAvatarUrl(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isBlank()) return null
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            return trimmed
        }
        if (isSupportedAvatarId(trimmed)) {
            return builtInAvatarUrl(trimmed)
        }
        return null
    }
}
