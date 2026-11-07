package com.crispy.tv.images

/**
 * A rendition tier of a [ResponsiveImageSet]: the wire hands out the same artwork at three
 * sizes (`small`/`medium`/`large`, mapped onto these), and this enum is the vocabulary that
 * names them, ordered LOW < MEDIUM < HIGH.
 *
 * This is not a user setting — there is no image-quality toggle — it is only the label
 * [ResponsiveImageSet.urlFor] selects with and [urlForDisplaySize] compares caps on.
 */
enum class ImageQuality {
    LOW,
    MEDIUM,
    HIGH,
}
