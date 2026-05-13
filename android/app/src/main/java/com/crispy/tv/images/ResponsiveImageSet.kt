package com.crispy.tv.images

import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.settings.ImageQuality

internal data class ResponsiveImageSet(
    val low: String?,
    val medium: String?,
    val high: String?,
) {
    val isEmpty: Boolean
        get() = low.isNullOrBlank() && medium.isNullOrBlank() && high.isNullOrBlank()

    fun urlFor(quality: ImageQuality): String? {
        return when (quality) {
            ImageQuality.LOW -> low ?: medium ?: high
            ImageQuality.MEDIUM -> medium ?: high ?: low
            ImageQuality.HIGH -> high ?: medium ?: low
        }?.trim()?.ifBlank { null }
    }

    companion object {
        fun fromSingle(url: String?): ResponsiveImageSet {
            return ResponsiveImageSet(
                low = url,
                medium = url,
                high = url,
            )
        }
    }
}

internal fun CrispyBackendClient.ResponsiveImageSet.toUiResponsiveImageSet(): ResponsiveImageSet {
    return ResponsiveImageSet(
        low = small,
        medium = medium,
        high = large,
    )
}

internal fun ResponsiveImageSet.toDomainMap(): Map<String, String?> {
    return mapOf(
        "small" to low,
        "medium" to medium,
        "large" to high,
    )
}

internal fun responsiveImageSetFromDomainMap(values: Map<String, String?>): ResponsiveImageSet {
    return ResponsiveImageSet(
        low = values["small"],
        medium = values["medium"],
        high = values["large"],
    )
}
