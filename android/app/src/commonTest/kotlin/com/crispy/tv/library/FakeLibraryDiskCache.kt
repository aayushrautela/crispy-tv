package com.crispy.tv.library

import com.crispy.tv.catalog.CatalogItem

/**
 * A [LibraryDiskCache] that answers from a value and records what it was asked for.
 *
 * The port started with two members, because those are the only two
 * `LibraryPagingSource` calls, and this double therefore implemented two rather than
 * three. It then grew a third -- `invalidate` -- for a **second** caller: the
 * library screen calls it when a mutation lands, and a port grows when a caller
 * needs it, not only when the first caller is written. That is why
 * `LibraryDiskCacheStore.invalidate` is an `override` today.
 *
 * So this double implements all three, and `invalidateCalls` exists because the
 * screen's refresh path is the thing that member was added for.
 */
internal class FakeLibraryDiskCache(
    private var page: LibraryCachedPage? = null,
) : LibraryDiskCache {
    val readCalls = mutableListOf<ReadCall>()
    val writeCalls = mutableListOf<WriteCall>()
    val invalidateCalls = mutableListOf<InvalidateCall>()

    data class ReadCall(val profileId: String, val sectionId: String)

    data class WriteCall(
        val profileId: String,
        val sectionId: String,
        val items: List<CatalogItem>,
        val appliedGenerationMs: Long?,
    )

    data class InvalidateCall(val profileId: String, val sectionId: String)

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

    /** Answerable so a caller can see the store refuse to invalidate. */
    var invalidateResult: Result<Boolean> = Result.success(true)

    override suspend fun invalidate(profileId: String, sectionId: String): Result<Boolean> {
        invalidateCalls += InvalidateCall(profileId, sectionId)
        return invalidateResult
    }
}
