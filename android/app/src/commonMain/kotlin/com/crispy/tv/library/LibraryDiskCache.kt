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
 * Declared with exactly the two members [LibraryPagingSource] calls. The store's
 * third member, `invalidate`, is used by the screen rather than the paging source,
 * and leaving it off the port is deliberate: an interface is a promise about what
 * a caller needs, and every member on it is a member someone has to implement and
 * a test has to answer.
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
}
