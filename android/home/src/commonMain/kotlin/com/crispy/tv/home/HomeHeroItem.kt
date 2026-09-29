package com.crispy.tv.home

import androidx.compose.runtime.Immutable
import com.crispy.tv.images.ResponsiveImageSet

/**
 * Lifted out of `HomeCatalogService` when the home models moved to `commonMain`.
 *
 * A nested type is as pinned as the file that declares it: `HomeCatalogService` is
 * `androidMain` because it parses `org.json` and calls `formatRating`, and neither fact
 * had anything to do with this 12-field data class, whose every field is already
 * portable (`ResponsiveImageSet` lives in `:android:core-domain`). The package is
 * unchanged, so no consumer needed an import.
 *
 * The `@Immutable` annotation is load-bearing, not decorative: `HeroState` holds a
 * `List<HomeHeroItem>` and the hero carousel relies on Compose treating it as stable.
 */
@Immutable
data class HomeHeroItem(
    val id: String,
    val title: String,
    val description: String,
    val tagline: String? = null,
    val rating: String?,
    val year: String? = null,
    val genres: List<String> = emptyList(),
    val artworkUrl: String?,
    val artwork: ResponsiveImageSet = ResponsiveImageSet.fromSingle(artworkUrl),
    val addonId: String,
    val type: String,
)
