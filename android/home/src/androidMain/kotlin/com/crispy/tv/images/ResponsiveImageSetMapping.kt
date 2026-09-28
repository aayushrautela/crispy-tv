package com.crispy.tv.images

/**
 * Persistence for [ResponsiveImageSet], on the map format the disk caches use.
 *
 * `androidMain` because those caches are: `HomeCatalogService` and
 * `RecommendationCatalogDiskCacheStore` are `androidMain` for `org.json` and
 * `Context`. The keys stay `small`/`medium`/`large` because they are an
 * on-disk format, not a Kotlin field name -- renaming them would invalidate
 * every cache already on disk.
 */
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
