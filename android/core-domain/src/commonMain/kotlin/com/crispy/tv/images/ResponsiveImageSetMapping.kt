package com.crispy.tv.images

/**
 * Persistence for [ResponsiveImageSet], on the map format the disk caches use.
 *
 * **The keys stay `small`/`medium`/`large` because they are an on-disk format,
 * not a Kotlin field name -- renaming them would invalidate every cache already
 * on disk.** The map is what [com.crispy.tv.domain.home.HomeCatalogItem] carries
 * (`artwork`/`logo`), and it is also what this mapping converts, so the wire
 * keys live here beside the type rather than in the one module that happens to
 * read JSON: `:android:home` used to declare these `internal`, and the wheel
 * could not reach them from `:android:core-domain` until they moved here.
 */
fun ResponsiveImageSet.toDomainMap(): Map<String, String?> {
    return mapOf(
        "small" to low,
        "medium" to medium,
        "large" to high,
    )
}

fun responsiveImageSetFromDomainMap(values: Map<String, String?>): ResponsiveImageSet {
    return ResponsiveImageSet(
        low = values["small"],
        medium = values["medium"],
        high = values["large"],
    )
}

/** The set behind a cached map, or null when the map carries no rendition at all. */
fun Map<String, String?>.toResponsiveImageSetOrNull(): ResponsiveImageSet? {
    return takeIf { it.isNotEmpty() }?.let { responsiveImageSetFromDomainMap(it) }
}
