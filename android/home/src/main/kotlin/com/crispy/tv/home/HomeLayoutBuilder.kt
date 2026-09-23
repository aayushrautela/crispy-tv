package com.crispy.tv.home

data class CatalogSectionLayoutMeta(
    val key: String,
    val layout: String,
)

fun buildHomeLayoutState(
    wideRails: Map<String, HomeWideRailSectionUi>,
    catalogSectionLayoutMeta: List<CatalogSectionLayoutMeta>,
): HomeLayoutState {
    val blocks = mutableListOf<HomeContentSectionUi>()

    listOf(
        CONTINUE_WATCHING_SECTION_KEY,
        UP_NEXT_SECTION_KEY,
        THIS_WEEK_SECTION_KEY,
    ).forEach { key ->
        val section = wideRails[key] ?: return@forEach
        blocks += HomeWideRailLayoutUi(key = section.key, kind = section.kind)
    }

    val collectionKeys = catalogSectionLayoutMeta
        .filter { it.layout.equals("collection", ignoreCase = true) }
        .map { it.key }

    var collectionShelfPlaced = collectionKeys.isEmpty()

    for (sectionMeta in catalogSectionLayoutMeta) {
        if (sectionMeta.layout.equals("collection", ignoreCase = true)) {
            if (!collectionShelfPlaced) {
                blocks += HomeCollectionShelfSectionUi(
                    key = collectionKeys.joinToString(separator = ":", prefix = "collections:"),
                    sectionKeys = collectionKeys,
                )
                collectionShelfPlaced = true
            }
        } else {
            blocks += HomeCatalogRowSectionUi(
                key = sectionMeta.key,
                sectionKey = sectionMeta.key,
            )
        }
    }

    return HomeLayoutState(blocks = blocks)
}