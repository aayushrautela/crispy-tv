package com.crispy.tv.images

import com.crispy.tv.settings.ImageQuality

/**
 * A set of image URLs at three qualities, and the rule for choosing between them.
 *
 * Distinct from the backend's `ResponsiveImageSet`, which is the wire shape and
 * uses the server's own `small`/`medium`/`large` names. The mapping between the
 * two lives in `androidMain`, because it is a wire-format concern and `commonMain`
 * has no business depending on `:android:backend`.
 */
data class ResponsiveImageSet(
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
