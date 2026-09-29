package com.crispy.tv.library

import com.crispy.tv.catalog.CatalogItem

/**
 * A [LibraryDiskCache] that answers from a value and records what it was asked for.
 *
 * The port has exactly two members because those are the only two
 * `LibraryPagingSource` calls; the production store's third, `invalidate`, is
 * called by the screen rather than by the paging source and so is not on the
 * interface at all. This double therefore implements two members rather than
 * three, which is the port's fault and not an oversight.
 */
internal class FakeLibraryDiskCache(
    private var page: LibraryCachedPage? = null,
) : LibraryDiskCache {
    val readCalls = mutableListOf<ReadCall>()
    val writeCalls = mutableListOf<WriteCall>()

    data class ReadCall(val profileId: String, val sectionId: String)

    data class WriteCall(
        val profileId: String,
        val sectionId: String,
        val items: List<CatalogItem>,
        val appliedGenerationMs: Long?,
    )

    fun answerWith(page: LibraryCachedPage?) {
        this.page = page
    }

    override suspend fun read(profileId: String, sectionId: String): LibraryCachedPage? {
        readCalls += ReadCall(profileId, sectionId)
        return page
    }

    override suspend fun write(
        profileId: String,
        sectionId: String,
        page: LibrarySectionPageUi,
        appliedGenerationMs: Long?,
    ): Result<Unit> {
        writeCalls += WriteCall(profileId, sectionId, page.items, appliedGenerationMs)
        return Result.success(Unit)
    }
}
