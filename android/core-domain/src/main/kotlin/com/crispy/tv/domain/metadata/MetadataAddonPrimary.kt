package com.crispy.tv.domain.metadata

data class AddonMetadataCandidate(
    val addonId: String,
    val mediaId: String,
    val title: String
)

data class AddonPrimaryMetadata(
    val primaryId: String,
    val title: String,
    val sources: List<String>
)

fun mergeAddonPrimaryMetadata(
    addonResults: List<AddonMetadataCandidate>,
    preferredAddonId: String? = null
): AddonPrimaryMetadata {
    require(addonResults.isNotEmpty()) { "addonResults must not be empty" }

    val preferred = preferredAddonId?.trim()?.takeIf { it.isNotEmpty() }
    val ranked = addonResults
        .withIndex()
        .sortedWith(
            compareBy<IndexedValue<AddonMetadataCandidate>>(
                { sourceRank(it.value.addonId, preferred) },
                { it.index },
                { it.value.addonId.lowercase() }
            )
        )

    val winner = ranked.first().value
    return AddonPrimaryMetadata(
        primaryId = winner.mediaId,
        title = winner.title,
        sources = ranked.map { it.value.addonId }.distinct()
    )
}

private fun sourceRank(addonId: String, preferredAddonId: String?): Int {
    if (preferredAddonId != null && addonId.equals(preferredAddonId, ignoreCase = true)) {
        return 0
    }
    return 1
}
