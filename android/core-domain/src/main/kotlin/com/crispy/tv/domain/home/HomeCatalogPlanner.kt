package com.crispy.tv.domain.home

import java.util.Locale

data class HomeCatalogItem(
    val id: String,
    val title: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val addonId: String,
    val type: String,
    val rating: String? = null,
    val year: String? = null,
    val description: String? = null,
)

enum class HomeCatalogSource(val key: String) {
    PERSONAL("personal"),
    PUBLIC("public");

    companion object {
        fun fromRaw(raw: String?): HomeCatalogSource? {
            return when (raw?.trim()?.lowercase(Locale.US)) {
                PERSONAL.key,
                "personal_home_feed",
                -> PERSONAL

                PUBLIC.key,
                "public_home_feed",
                -> PUBLIC

                else -> null
            }
        }
    }
}

enum class HomeCatalogPresentation(val key: String) {
    HERO("hero"),
    PILL("pill"),
    RAIL("rail");

    companion object {
        fun fromRaw(raw: String?): HomeCatalogPresentation {
            return when (raw?.trim()?.lowercase(Locale.US)) {
                HERO.key -> HERO
                PILL.key -> PILL
                else -> RAIL
            }
        }
    }
}

data class HomeCatalogList(
    val kind: String,
    val variantKey: String = DEFAULT_VARIANT_KEY,
    val source: HomeCatalogSource,
    val presentation: HomeCatalogPresentation = HomeCatalogPresentation.RAIL,
    val name: String = "",
    val heading: String = "",
    val title: String = "",
    val subtitle: String = "",
    val items: List<HomeCatalogItem>,
    val mediaTypes: Set<String> = emptySet(),
) {
    val catalogId: String
        get() = buildHomeCatalogId(source = source, kind = kind, variantKey = variantKey)

    val displayTitle: String
        get() = heading.ifBlank { title.ifBlank { name.ifBlank { kind } } }
}

data class HomeCatalogSnapshot(
    val profileId: String?,
    val lists: List<HomeCatalogList>,
    val statusMessage: String,
)

data class HomeCatalogHeroItem(
    val id: String,
    val title: String,
    val description: String,
    val rating: String?,
    val year: String? = null,
    val genres: List<String> = emptyList(),
    val backdropUrl: String,
    val addonId: String,
    val type: String,
)

data class HomeCatalogHeroResult(
    val items: List<HomeCatalogHeroItem> = emptyList(),
    val statusMessage: String = "",
)

data class HomeCatalogSection(
    val catalogId: String,
    val source: HomeCatalogSource,
    val presentation: HomeCatalogPresentation,
    val variantKey: String = DEFAULT_VARIANT_KEY,
    val name: String = "",
    val heading: String = "",
    val title: String = "",
    val subtitle: String = "",
) {
    val displayTitle: String
        get() = heading.ifBlank { title.ifBlank { name.ifBlank { catalogId } } }
}

data class HomeCatalogDiscoverRef(
    val section: HomeCatalogSection,
    val addonName: String,
    val genres: List<String> = emptyList(),
)

data class HomeCatalogFeedPlan(
    val heroResult: HomeCatalogHeroResult = HomeCatalogHeroResult(),
    val sections: List<HomeCatalogSection> = emptyList(),
    val sectionsStatusMessage: String = "",
)

data class HomeCatalogPageResult(
    val items: List<HomeCatalogItem> = emptyList(),
    val statusMessage: String = "",
    val attemptedUrls: List<String> = emptyList(),
)

data class HomeCatalogIdentifier(
    val source: HomeCatalogSource,
    val kind: String,
    val variantKey: String,
)

fun planHomeFeed(
    snapshot: HomeCatalogSnapshot,
    heroLimit: Int = 10,
    sectionLimit: Int = Int.MAX_VALUE,
): HomeCatalogFeedPlan {
    if (snapshot.lists.isEmpty()) {
        return HomeCatalogFeedPlan(
            heroResult = HomeCatalogHeroResult(statusMessage = snapshot.statusMessage),
            sections = emptyList(),
            sectionsStatusMessage = snapshot.statusMessage,
        )
    }

    return HomeCatalogFeedPlan(
        heroResult = buildHeroResult(snapshot, heroLimit),
        sections = buildHomeCatalogSections(snapshot.lists, sectionLimit),
        sectionsStatusMessage = "",
    )
}

fun planPersonalHomeFeed(
    snapshot: HomeCatalogSnapshot,
    heroLimit: Int = 10,
    sectionLimit: Int = Int.MAX_VALUE,
): HomeCatalogFeedPlan {
    return planHomeFeed(
        snapshot = snapshot,
        heroLimit = heroLimit,
        sectionLimit = sectionLimit,
    )
}

fun listDiscoverCatalogs(
    snapshot: HomeCatalogSnapshot,
    mediaType: String? = null,
    limit: Int = Int.MAX_VALUE,
): Pair<List<HomeCatalogDiscoverRef>, String> {
    val normalizedType = mediaType?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotBlank() }
    if (normalizedType != null && normalizedType != "movie" && normalizedType != "series") {
        return emptyList<HomeCatalogDiscoverRef>() to "Unsupported media type: $mediaType"
    }

    if (snapshot.lists.isEmpty()) {
        return emptyList<HomeCatalogDiscoverRef>() to snapshot.statusMessage
    }

    val filteredLists =
        snapshot.lists.filter { list ->
            list.presentation == HomeCatalogPresentation.RAIL &&
                (normalizedType == null || list.supportsMediaType(normalizedType))
        }

    if (filteredLists.isEmpty()) {
        val suffix = if (normalizedType == null) "" else " for $normalizedType"
        return emptyList<HomeCatalogDiscoverRef>() to "No discover catalogs found$suffix."
    }

    val targetCount = limit.coerceAtLeast(1)
    val limitedLists = if (targetCount >= filteredLists.size) filteredLists else filteredLists.take(targetCount)
    return limitedLists.map { list ->
        HomeCatalogDiscoverRef(
            section = list.toSection(),
            addonName = "Supabase",
            genres = emptyList(),
        )
    } to ""
}

fun buildCatalogPage(
    snapshot: HomeCatalogSnapshot,
    sectionCatalogId: String,
    page: Int,
    pageSize: Int,
): HomeCatalogPageResult {
    val targetPage = page.coerceAtLeast(1)
    val targetSize = pageSize.coerceAtLeast(1)

    if (snapshot.lists.isEmpty()) {
        return HomeCatalogPageResult(
            items = emptyList(),
            statusMessage = snapshot.statusMessage,
            attemptedUrls = emptyList(),
        )
    }

    val identifier = parseHomeCatalogId(sectionCatalogId)
        ?: return HomeCatalogPageResult(
            items = emptyList(),
            statusMessage = "Catalog not found.",
            attemptedUrls = emptyList(),
        )

    val list =
        snapshot.lists.firstOrNull {
            it.source == identifier.source &&
                it.kind.equals(identifier.kind, ignoreCase = true) &&
                it.variantKey.equals(identifier.variantKey, ignoreCase = true)
        } ?: return HomeCatalogPageResult(
            items = emptyList(),
            statusMessage = "Catalog not found.",
            attemptedUrls = emptyList(),
        )

    val attempted =
        listOf(
            "supabase:${identifier.source.key}:${snapshot.profileId.orEmpty()}:${identifier.kind}:${identifier.variantKey}:page=$targetPage"
        )
    val allItems = list.items
    val startIndexLong = (targetPage.toLong() - 1L) * targetSize.toLong()
    if (startIndexLong >= allItems.size.toLong()) {
        return HomeCatalogPageResult(
            items = emptyList(),
            statusMessage = "No more items available.",
            attemptedUrls = attempted,
        )
    }

    val startIndex = startIndexLong.toInt()
    val endIndex = minOf(startIndex + targetSize, allItems.size)
    val items = if (startIndex < endIndex) allItems.subList(startIndex, endIndex) else emptyList()
    return HomeCatalogPageResult(
        items = items,
        statusMessage = when {
            items.isNotEmpty() -> ""
            targetPage <= 1 -> "No catalog items available."
            else -> "No more items available."
        },
        attemptedUrls = attempted,
    )
}

fun buildHomeCatalogId(
    source: HomeCatalogSource,
    kind: String,
    variantKey: String = DEFAULT_VARIANT_KEY,
): String {
    return "${source.key}:${kind.trim()}:${variantKey.trim().ifBlank { DEFAULT_VARIANT_KEY }}"
}

fun parseHomeCatalogId(catalogId: String): HomeCatalogIdentifier? {
    val trimmed = catalogId.trim()
    if (trimmed.isEmpty()) return null

    val parts = trimmed.split(':', limit = 3)
    if (parts.size < 3) return null

    val source = HomeCatalogSource.fromRaw(parts[0]) ?: return null
    val kind = parts[1].trim().takeIf { it.isNotEmpty() } ?: return null
    val variantKey = parts[2].trim().ifBlank { DEFAULT_VARIANT_KEY }
    return HomeCatalogIdentifier(
        source = source,
        kind = kind,
        variantKey = variantKey,
    )
}

fun resolveHomeCatalogSource(catalogId: String): HomeCatalogSource {
    return parseHomeCatalogId(catalogId)?.source ?: HomeCatalogSource.PERSONAL
}

private fun buildHeroResult(
    snapshot: HomeCatalogSnapshot,
    limit: Int,
): HomeCatalogHeroResult {
    val targetCount = limit.coerceAtLeast(1)
    val heroList = snapshot.lists.firstOrNull { it.presentation == HomeCatalogPresentation.HERO } ?: snapshot.lists.first()
    val fallbackDescription =
        heroList.subtitle.ifBlank {
            heroList.heading.ifBlank {
                heroList.title.ifBlank {
                    heroList.name.ifBlank { "Recommended for you." }
                }
            }
        }
    val heroItems =
        heroList.items
            .asSequence()
            .mapNotNull { item ->
                val backdrop = item.backdropUrl ?: item.posterUrl
                if (backdrop.isNullOrBlank()) return@mapNotNull null
                HomeCatalogHeroItem(
                    id = item.id,
                    title = item.title,
                    description = item.description ?: fallbackDescription,
                    rating = item.rating,
                    year = item.year,
                    genres = emptyList(),
                    backdropUrl = backdrop,
                    addonId = item.addonId,
                    type = item.type,
                )
            }
            .take(targetCount)
            .toList()

    if (heroItems.isEmpty()) {
        return HomeCatalogHeroResult(
            items = emptyList(),
            statusMessage = snapshot.statusMessage.ifBlank { "No featured items available." },
        )
    }

    return HomeCatalogHeroResult(items = heroItems, statusMessage = "")
}

private fun buildHomeCatalogSections(
    lists: List<HomeCatalogList>,
    limit: Int,
): List<HomeCatalogSection> {
    val targetCount = limit.coerceAtLeast(1)
    val filteredLists = lists.filterNot { it.presentation == HomeCatalogPresentation.HERO }
    if (filteredLists.isEmpty()) {
        return emptyList()
    }

    val limitedLists = if (targetCount >= filteredLists.size) filteredLists else filteredLists.take(targetCount)
    return limitedLists.map { it.toSection() }
}

private fun HomeCatalogList.toSection(): HomeCatalogSection {
    return HomeCatalogSection(
        catalogId = catalogId,
        source = source,
        presentation = presentation,
        variantKey = variantKey,
        name = name,
        heading = heading,
        title = title,
        subtitle = subtitle,
    )
}

private fun HomeCatalogList.supportsMediaType(mediaType: String): Boolean {
    return mediaTypes.any { it.equals(mediaType, ignoreCase = true) } ||
        items.any { it.type.equals(mediaType, ignoreCase = true) }
}

private const val DEFAULT_VARIANT_KEY = "default"
