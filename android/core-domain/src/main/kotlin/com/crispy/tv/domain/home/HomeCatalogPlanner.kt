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

data class HomeCatalogList(
    val id: String,
    val kind: String?,
    val title: String,
    val subtitle: String?,
    val heading: String?,
    val items: List<HomeCatalogItem>,
    val mediaTypes: Set<String> = emptySet(),
)

data class HomeCatalogSnapshot(
    val profileId: String?,
    val lists: List<HomeCatalogList>,
    val statusMessage: String,
)

enum class HomeCatalogSource(val key: String) {
    PERSONAL("personal_home_feed"),
    GLOBAL("global_home_feed");

    fun catalogId(rawCatalogId: String): String {
        val normalizedId = rawCatalogId.trim()
        return when (this) {
            PERSONAL -> normalizedId
            GLOBAL -> "$GLOBAL_CATALOG_ID_PREFIX$normalizedId"
        }
    }
}

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
    val title: String,
    val catalogId: String,
    val subtitle: String = "",
)

data class HomeCatalogDiscoverRef(
    val section: HomeCatalogSection,
    val addonName: String,
    val genres: List<String> = emptyList(),
)

data class HomeCatalogPersonalFeedPlan(
    val heroResult: HomeCatalogHeroResult = HomeCatalogHeroResult(),
    val sections: List<HomeCatalogSection> = emptyList(),
    val sectionsStatusMessage: String = "",
)

data class HomeCatalogPageResult(
    val items: List<HomeCatalogItem> = emptyList(),
    val statusMessage: String = "",
    val attemptedUrls: List<String> = emptyList(),
)

fun planPersonalHomeFeed(
    snapshot: HomeCatalogSnapshot,
    heroLimit: Int = 10,
    sectionLimit: Int = Int.MAX_VALUE,
): HomeCatalogPersonalFeedPlan {
    if (snapshot.lists.isEmpty()) {
        return HomeCatalogPersonalFeedPlan(
            heroResult = HomeCatalogHeroResult(statusMessage = snapshot.statusMessage),
            sections = emptyList(),
            sectionsStatusMessage = snapshot.statusMessage,
        )
    }

    return HomeCatalogPersonalFeedPlan(
        heroResult = buildHeroResult(snapshot, heroLimit),
        sections = buildHomeCatalogSections(snapshot.lists, HomeCatalogSource.PERSONAL, sectionLimit),
        sectionsStatusMessage = "",
    )
}

fun buildGlobalHeaderSections(
    snapshot: HomeCatalogSnapshot,
    limit: Int = Int.MAX_VALUE,
): List<HomeCatalogSection> {
    if (snapshot.lists.isEmpty()) {
        return emptyList()
    }
    return buildHomeCatalogSections(snapshot.lists, HomeCatalogSource.GLOBAL, limit)
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
            !list.isHeroList() && (normalizedType == null || list.supportsMediaType(normalizedType))
        }

    if (filteredLists.isEmpty()) {
        val suffix = if (normalizedType == null) "" else " for $normalizedType"
        return emptyList<HomeCatalogDiscoverRef>() to "No discover catalogs found$suffix."
    }

    val targetCount = limit.coerceAtLeast(1)
    val limitedLists = if (targetCount >= filteredLists.size) filteredLists else filteredLists.take(targetCount)
    return limitedLists.map { list ->
        HomeCatalogDiscoverRef(
            section = HomeCatalogSection(
                title = list.title,
                catalogId = list.id,
                subtitle = list.subtitle.orEmpty(),
            ),
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

    val source = resolveHomeCatalogSource(sectionCatalogId)
    val catalogId = normalizeHomeCatalogId(sectionCatalogId, source)
    val list = snapshot.lists.firstOrNull { it.id.equals(catalogId, ignoreCase = true) }
        ?: return HomeCatalogPageResult(
            items = emptyList(),
            statusMessage = "Catalog not found.",
            attemptedUrls = emptyList(),
        )

    val attempted = listOf("supabase:${source.key}:${snapshot.profileId.orEmpty()}:${list.id}:page=$targetPage")
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

fun resolveHomeCatalogSource(catalogId: String): HomeCatalogSource {
    return if (catalogId.trim().startsWith(GLOBAL_CATALOG_ID_PREFIX, ignoreCase = true)) {
        HomeCatalogSource.GLOBAL
    } else {
        HomeCatalogSource.PERSONAL
    }
}

fun normalizeHomeCatalogId(catalogId: String, source: HomeCatalogSource): String {
    val trimmed = catalogId.trim()
    return when {
        source == HomeCatalogSource.GLOBAL && trimmed.startsWith(GLOBAL_CATALOG_ID_PREFIX, ignoreCase = true) -> {
            trimmed.substring(GLOBAL_CATALOG_ID_PREFIX.length)
        }

        else -> trimmed
    }
}

private fun buildHeroResult(
    snapshot: HomeCatalogSnapshot,
    limit: Int,
): HomeCatalogHeroResult {
    val targetCount = limit.coerceAtLeast(1)
    val heroList = snapshot.lists.firstOrNull { it.isHeroList() } ?: snapshot.lists.first()
    val fallbackDescription =
        heroList.subtitle
            ?: heroList.heading
            ?: heroList.title.ifBlank { "Recommended for you." }
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
    source: HomeCatalogSource,
    limit: Int,
): List<HomeCatalogSection> {
    val targetCount = limit.coerceAtLeast(1)
    val filteredLists = lists.filterNot { it.isHeroList() }
    if (filteredLists.isEmpty()) {
        return emptyList()
    }

    val limitedLists = if (targetCount >= filteredLists.size) filteredLists else filteredLists.take(targetCount)
    return limitedLists.map { list ->
        HomeCatalogSection(
            title = list.title,
            catalogId = source.catalogId(list.id),
            subtitle = list.subtitle.orEmpty(),
        )
    }
}

private fun HomeCatalogList.isHeroList(): Boolean {
    val normalizedId = id.trim().lowercase(Locale.US)
    val normalizedKind = kind?.trim()?.lowercase(Locale.US)
    return normalizedId == HERO_LIST_ID ||
        normalizedId.startsWith("hero.") ||
        normalizedKind?.contains("hero") == true
}

private fun HomeCatalogList.supportsMediaType(mediaType: String): Boolean {
    return mediaTypes.any { it.equals(mediaType, ignoreCase = true) } ||
        items.any { it.type.equals(mediaType, ignoreCase = true) }
}

private const val HERO_LIST_ID = "hero.shelf"
private const val GLOBAL_CATALOG_ID_PREFIX = "global:"
