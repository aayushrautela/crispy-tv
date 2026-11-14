package com.crispy.tv.images

/**
 * A set of image URLs at three qualities, and the rule for choosing between them.
 *
 * ## One type, not two
 *
 * This used to be two types with the same name and identical shape: the backend's
 * wire DTO in `com.crispy.tv.backend` (`small`/`medium`/`large`) and a UI model in
 * `com.crispy.tv.images` (`low`/`medium`/`high`), joined by a
 * `toUiResponsiveImageSet()` mapping. Both declared an identical `isEmpty`.
 *
 * The split bought nothing and cost three things: a name collision that forced an
 * import alias, a file that could not be `commonMain` because it held an
 * extension on a backend type, and a mapping that was an identity once the two
 * shapes are lined up. So there is one type now, and `CrispyBackendParsers`
 * maps the wire's `small`/`medium`/`large` onto it in the one place that reads
 * JSON.
 *
 * The field names follow `ImageQuality` rather than the wire, because
 * `ImageQuality` is what selects among them.
 *
 * `original` is the fourth slot the backend now sends (`xl` on the wire): the
 * uncapped source image. It sits outside the `ImageQuality` ladder on purpose --
 * it is what a tier degrades to when no capped tier is wide enough, not a step
 * on the quality slider -- and it defaults to `null` so every existing
 * construction site keeps compiling and every cached set written before it
 * existed still parses.
 *
 * It lives in `:android:core-domain` because that is the only module the wire
 * layer, `:android:home` and `:android:app` all already depend on, and the only
 * one guaranteed to be free of platform types on every target.
 */
data class ResponsiveImageSet(
    val low: String?,
    val medium: String?,
    val high: String?,
    val original: String? = null,
) {
    val isEmpty: Boolean
        get() = low.isNullOrBlank() && medium.isNullOrBlank() && high.isNullOrBlank() && original.isNullOrBlank()

    fun urlFor(quality: ImageQuality): String? {
        return when (quality) {
            ImageQuality.LOW -> low ?: medium ?: high ?: original
            ImageQuality.MEDIUM -> medium ?: high ?: low ?: original
            ImageQuality.HIGH -> high ?: medium ?: low ?: original
        }?.trim()?.ifBlank { null }
    }

    companion object {
        fun fromSingle(url: String?): ResponsiveImageSet {
            return ResponsiveImageSet(
                low = url,
                medium = url,
                high = url,
                original = url,
            )
        }
    }
}
