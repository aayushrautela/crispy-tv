package com.crispy.tv.library

/**
 * The disk cache for the first page of each library section.
 *
 * A type-level port, like `BackendApi` and `StreamResolver` beside it. It is not a
 * transport abstraction and does not pretend to be: the implementation
 * `LibraryDiskCacheStore` writes JSON with `org.json` and hashes file names with
 * `MessageDigest`, and both are Android platform or JVM APIs that a KMP
 * `commonMain` does not have. So the cache cannot travel, and the read and write
 * pair it offers can.
 *
 * Declared with exactly the members its callers use. It originally carried two --
 * `read` and `write`, the pair [LibraryPagingSource] calls -- and deliberately left
 * the store's third member, `invalidate`, off, on the grounds that an interface is
 * a promise about what *a caller* needs and every member on it is a member someone
 * has to implement and a test has to answer. That reasoning was right about the
 * paging source and wrong once a second caller appeared: `LibraryViewModel` drops a
 * section's cached page by hand whenever the server's generation for that section
 * moves, which no amount of port discipline makes go away. So the port grew, and the
 * KDoc grew with it, because the rule is not "keep the port small" but "a member
 * arrives when a caller needs it".
 */
interface LibraryDiskCache {
    suspend fun read(profileId: String, sectionId: String): LibraryCachedPage?

    /**
     * Returns whether the write reached the file. The store's body ends in a
     * `runCatching`, so it has always returned `Result<Unit>` and a caller could
     * always see it; the port reproduces that rather than narrowing it to `Unit`,
     * because narrowing here would be a silent behaviour change to a method the
     * paging source calls on the hot path.
     */
    suspend fun write(
        profileId: String,
        sectionId: String,
        page: LibrarySectionPageUi,
        appliedGenerationMs: Long?,
    ): Result<Unit>

    /**
     * Drops one section's cached page. A caller that learns a section is stale
     * outside a [LibraryPagingSource] load -- [LibraryViewModel] does exactly that
     * when the server's generation stamp for the section moves -- has no other way
     * to say so.
     *
     * `Result<Boolean>`, not `Unit`, and not because the Boolean is interesting: the
     * store's body ends in a `runCatching` over a file delete, so it has always
     * answered with whether the removal happened, and a caller can always see it.
     * Narrowing the port to `Unit` would discard an answer the implementation has
     * been giving all along, which is the same mistake [write]'s KDoc records for the
     * other direction.
     */
    suspend fun invalidate(profileId: String, sectionId: String): Result<Boolean>
}
