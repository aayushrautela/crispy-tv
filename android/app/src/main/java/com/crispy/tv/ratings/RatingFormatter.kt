package com.crispy.tv.ratings

import java.util.Locale

internal fun formatRating(value: Double?): String? {
    val rating = value ?: return null
    if (!rating.isFinite() || rating <= 0.0) return null
    return String.format(Locale.US, "%.1f", rating)
}

internal fun normalizeRatingText(value: String?): String? {
    val trimmed = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return formatRating(trimmed.toDoubleOrNull()) ?: trimmed
}

internal fun formatRatingOutOfTen(value: String?): String? {
    val trimmed = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
    if ('/' in trimmed) {
        val numerator = normalizeRatingText(trimmed.substringBefore('/')) ?: return trimmed
        val denominator = trimmed.substringAfter('/').trim().ifEmpty { "10" }
        return "$numerator/$denominator"
    }

    val normalized = normalizeRatingText(trimmed) ?: return null
    return "$normalized/10"
}
