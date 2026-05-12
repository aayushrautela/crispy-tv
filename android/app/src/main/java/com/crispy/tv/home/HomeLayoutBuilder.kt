package com.crispy.tv.home

internal data class CatalogSectionLayoutMeta(
    val key: String,
    val layout: String,
)

internal fun buildHomeLayoutState(
    wideRails: Map<String, HomeWideRailSectionUi>,
    catalogSectionLayoutMeta: List<CatalogSectionLayoutMeta>,
    catalogStatusMessage: String,
): HomeLayoutState {
    val blocks = mutableListOf<HomeContentSectionUi>()

    listOf(
        CONTINUE_WATCHING_SECTION_KEY,
        UP_NEXT_SECTION_KEY,
        THIS_WEEK_SECTION_KEY,
    ).forEach { key ->
        val section = wideRails[key] ?: return@forEach
        if (section.isVisible()) {
            blocks += HomeWideRailLayoutUi(key = section.key, kind = section.kind)
        }
    }

    if (catalogSectionLayoutMeta.isEmpty() && catalogStatusMessage.isNotBlank()) {
        blocks += HomeStatusSectionUi(
            key = "catalogStatus",
            statusMessage = catalogStatusMessage,
        )
    }

    var index = 0
    while (index < catalogSectionLayoutMeta.size) {
        val sectionMeta = catalogSectionLayoutMeta[index]
        if (sectionMeta.layout.equals("collection", ignoreCase = true)) {
            val groupedKeys = mutableListOf<String>()
            while (
                index < catalogSectionLayoutMeta.size &&
                    catalogSectionLayoutMeta[index].layout.equals("collection", ignoreCase = true)
            ) {
                groupedKeys += catalogSectionLayoutMeta[index].key
                index += 1
            }
            blocks += HomeCollectionShelfSectionUi(
                key = groupedKeys.joinToString(separator = ":", prefix = "collections:"),
                sectionKeys = groupedKeys,
            )
        } else {
            blocks += HomeCatalogRowSectionUi(
                key = sectionMeta.key,
                sectionKey = sectionMeta.key,
            )
            index += 1
        }
    }

    return HomeLayoutState(blocks = blocks)
}
