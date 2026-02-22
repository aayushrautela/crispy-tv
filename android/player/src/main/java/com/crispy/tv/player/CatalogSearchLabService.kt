package com.crispy.tv.player

data class CatalogLabCatalog(
    val addonId: String,
    val catalogType: String,
    val catalogId: String,
    val name: String,
    val supportsSearch: Boolean
)

data class CatalogLabItem(
    val id: String,
    val title: String,
    val addonId: String,
    val type: String
)

data class CatalogPageRequest(
    val mediaType: MetadataLabMediaType,
    val catalogId: String,
    val page: Int = 1,
    val pageSize: Int = 50,
    val preferredAddonId: String? = null
)

data class CatalogSearchRequest(
    val mediaType: MetadataLabMediaType,
    val query: String,
    val page: Int = 1,
    val pageSize: Int = 50,
    val preferredAddonId: String? = null
)

data class CatalogLabResult(
    val catalogs: List<CatalogLabCatalog> = emptyList(),
    val items: List<CatalogLabItem> = emptyList(),
    val attemptedUrls: List<String> = emptyList(),
    val statusMessage: String = "Catalog/search idle."
)

interface CatalogSearchLabService {
    suspend fun fetchCatalogPage(request: CatalogPageRequest): CatalogLabResult
    suspend fun search(request: CatalogSearchRequest): CatalogLabResult
}

object DefaultCatalogSearchLabService : CatalogSearchLabService {
    override suspend fun fetchCatalogPage(request: CatalogPageRequest): CatalogLabResult {
        return CatalogLabResult(
            statusMessage = "Catalog/search service unavailable."
        )
    }

    override suspend fun search(request: CatalogSearchRequest): CatalogLabResult {
        return CatalogLabResult(
            statusMessage = "Catalog/search service unavailable."
        )
    }
}
