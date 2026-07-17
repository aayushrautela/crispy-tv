package com.crispy.tv.domain.account

sealed interface AddonUrlResult {
    data class Valid(val normalized: String) : AddonUrlResult
    data object Invalid : AddonUrlResult
}

fun normalizeAddonUrl(raw: String): AddonUrlResult {
    val trimmed = raw.trim()
    if (!trimmed.startsWith("https://", ignoreCase = true)) return AddonUrlResult.Invalid
    val lower = trimmed.lowercase()
    val withoutFragment = lower.substringBefore('#')
    val noTrailingSlash = withoutFragment.removeSuffix("/")
    val path = noTrailingSlash.substringAfter("https://").substringBefore('?')
    if (!path.contains('/')) return AddonUrlResult.Invalid
    val segments = path.split('/').filter { it.isNotEmpty() }
    if (segments.isEmpty()) return AddonUrlResult.Invalid
    val last = segments.last()
    if (!last.endsWith(".json")) return AddonUrlResult.Invalid
    return AddonUrlResult.Valid(noTrailingSlash)
}
