package com.crispy.tv.library

import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.images.ResponsiveImageSet
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [LibraryDiskCacheStore]'s own read/write/invalidate contract, against okio's
 * in-memory [FakeFileSystem].
 *
 * **The store is the reason this file exists, and so is the port next to it.**
 * `LibraryDiskCache` was declared as a type-level port while the store behind it
 * could not leave `androidMain` -- it wrote JSON with `org.json` and hashed file
 * names with `MessageDigest`. A port over an untravelable implementation is a
 * promise with no way to check it, so every one of these cases would have had to
 * live in `androidHostTest` on a real device filesystem. The store is now
 * `commonMain` and takes a `FileSystem`, so the suite is a `commonTest` and runs
 * on Android, desktop and both Apple test targets.
 *
 * **The store deliberately answers "no cache" rather than throwing, and that
 * choice is what most of these cases are about.** Every read and every write ends
 * in a `runCatching`, which means a missing file, a truncated file, a file that
 * is not JSON, an unwritable directory and a decode failure are all the *same*
 * answer to the caller. That is a defensible design for a cache -- a stale or
 * broken entry should cost a network fetch, not a crash -- and it is also the kind
 * of design that hides a real fault forever, so the cases below are written to
 * separate the answers that *are* distinguishable from the ones that are not.
 */
class LibraryDiskCacheStoreTest {

    private val cacheRoot: Path = "/data/files".toPath()

    private fun storeOn(fs: FileSystem) = LibraryDiskCacheStore(
        fileSystem = fs,
        cacheRoot = cacheRoot,
        // Required rather than defaulted, and the reason is the same as in every
        // other port here: the body does blocking file IO inside `withContext`,
        // and a test has to say which dispatcher it is on.
        ioDispatcher = UnconfinedTestDispatcher(),
    )

    private fun FakeFileSystem.cacheFileFor(profile: String, section: String): Path =
        cacheRoot.resolve("library_section_cache").resolve(libraryCacheFileName(profile, section))

    // ---- the round trip ---------------------------------------------------

    @Test
    fun aWrittenSectionIsReadBackWithEveryField() = runTest {
        val fs = FakeFileSystem()
        val store = storeOn(fs)
        val page = LibrarySectionPageUi(
            items = listOf(
                item(id = "i1", title = "First"),
                item(id = "i2", title = "Second"),
            ),
            nextCursor = "cursor-2",
            hasMore = true,
        )

        store.write("p1", "history", page, appliedGenerationMs = 1_700_000_000_000L)

        assertEquals(
            LibraryCachedPage(
                items = page.items,
                nextCursor = "cursor-2",
                hasMore = true,
                appliedGenerationMs = 1_700_000_000_000L,
            ),
            store.read("p1", "history"),
        )
    }

    @Test
    fun everyCatalogItemFieldSurvivesTheRoundTrip() = runTest {
        // One item with every field populated, and one with every nullable field
        // absent. **The all-populated fixture is the one that cannot fail**: a
        // field the writer drops and the reader defaults would still round trip if
        // the fixture's value happened to equal the default, so the second case
        // is what makes the first mean anything.
        val fs = FakeFileSystem()
        val store = storeOn(fs)
        val full = item(
            id = "i1",
            title = "Full",
            artworkUrl = "https://a/art.jpg",
            logoUrl = "https://a/logo.jpg",
            artwork = ResponsiveImageSet(low = "low", medium = "med", high = "high"),
            logo = ResponsiveImageSet(low = "llogo", medium = null, high = "hlogo"),
            addonId = "tmdb",
            type = "series",
            rating = "PG-13",
            year = "1999",
            genre = "Drama",
            maturityRating = "TV-14",
            liked = true,
            addedAt = "2024-01-01",
            watchedAt = "2024-02-02",
            ratedAt = "2024-03-03",
            lastActivityAt = "2024-04-04",
            episodeCount = 26,
        )

        store.write("p1", "watchlist", LibrarySectionPageUi(items = listOf(full), hasMore = false), null)

        assertEquals(full, store.read("p1", "watchlist")?.items?.single())
    }

    @Test
    fun anItemWithNoOptionalFieldsComesBackWithThemAbsent() = runTest {
        val fs = FakeFileSystem()
        val store = storeOn(fs)
        val bare = item(
            id = "i1",
            title = "Bare",
            artworkUrl = null,
            logoUrl = null,
            artwork = null,
            logo = null,
            rating = null,
            year = null,
            genre = null,
            maturityRating = null,
            liked = null,
            addedAt = null,
            watchedAt = null,
            ratedAt = null,
            lastActivityAt = null,
            episodeCount = null,
        )

        store.write("p1", "history", LibrarySectionPageUi(items = listOf(bare), hasMore = false), null)

        val read = store.read("p1", "history")?.items?.single()
        assertNull(read?.artwork)
        assertNull(read?.logo)
        assertNull(read?.liked)
        assertNull(read?.episodeCount)
        assertNull(read?.rating)
    }

    @Test
    fun anEntryWrittenWithoutAnAddonIdOrATypeReadsTheStoresFallbacks() = runTest {
        // **The one case that cannot be reached by a round trip**, and the reason
        // it is here rather than folded into the case above: `CatalogItem.addonId`
        // and `CatalogItem.type` are non-null, so `encode` always writes both and
        // no fixture can produce their absence. The read-side fallbacks
        // (`?: "backend"`, `?: "movie"`) therefore only fire for an entry written
        // by a build that did not have the field, or by a hand-edited file -- and
        // an entry that old is exactly the population this cache is full of after
        // an update, so the fallback is not defensive decoration.
        //
        // This is the fourth instance of the shape the whole suite is built
        // around: **a round trip can only prove what the writer can produce, and
        // a default is a claim about an input the writer never makes.**
        val fs = FakeFileSystem()
        fs.writeRaw(
            "p1",
            "history",
            """{"items":[{"item_id":"i1","title":"No addon"}],"has_more":false}""",
        )

        val read = storeOn(fs).read("p1", "history")?.items?.single()

        assertEquals("backend", read?.addonId)
        assertEquals("movie", read?.type)
    }

    @Test
    fun anEpisodeCountOfZeroIsStoredAbsentBecauseTheFilterRejectsIt() = runTest {
        // The store's own comment on this line is worth repeating because it is a
        // case where the *faithful* translation is the defaulted read and the
        // obvious-looking refactor is wrong: `optIntOrNull ?: 0` then `takeIf { it
        // > 0 }` means 0 and absent are the same answer, on purpose, because the
        // filter rejects both. So the fixture cannot distinguish "stored 0" from
        // "not stored" -- what it can distinguish is "the filter ran", and a
        // positive count is what proves that.
        val fs = FakeFileSystem()
        val store = storeOn(fs)
        store.write(
            "p1",
            "series",
            LibrarySectionPageUi(
                items = listOf(item(id = "zero", episodeCount = 0), item(id = "many", episodeCount = 5)),
                hasMore = false,
            ),
            null,
        )

        assertEquals(
            listOf(null, 5),
            store.read("p1", "series")?.items?.map { it.episodeCount },
        )
    }

    // ---- profiles and sections are separate entries -----------------------

    @Test
    fun sectionsAndProfilesDoNotSeeEachOthersEntries() = runTest {
        val fs = FakeFileSystem()
        val store = storeOn(fs)
        store.write("p1", "history", LibrarySectionPageUi(items = listOf(item(id = "p1-history")), hasMore = false), null)
        store.write("p1", "watchlist", LibrarySectionPageUi(items = listOf(item(id = "p1-watchlist")), hasMore = false), null)
        store.write("p2", "history", LibrarySectionPageUi(items = listOf(item(id = "p2-history")), hasMore = false), null)

        assertEquals("p1-history", store.read("p1", "history")?.items?.single()?.id)
        assertEquals("p1-watchlist", store.read("p1", "watchlist")?.items?.single()?.id)
        assertEquals("p2-history", store.read("p2", "history")?.items?.single()?.id)
    }

    @Test
    fun writingASectionTwiceReplacesItRatherThanAccumulating() = runTest {
        val fs = FakeFileSystem()
        val store = storeOn(fs)
        store.write("p1", "history", LibrarySectionPageUi(items = listOf(item(id = "a"), item(id = "b")), hasMore = false), null)
        assertEquals(2, store.read("p1", "history")?.items?.size)

        store.write("p1", "history", LibrarySectionPageUi(items = listOf(item(id = "c")), hasMore = false), null)

        assertEquals(listOf("c"), store.read("p1", "history")?.items?.map { it.id })
    }

    // ---- the "no cache" answers -------------------------------------------

    @Test
    fun aSectionThatWasNeverWrittenReadsAsNoCache() = runTest {
        // The first launch case, and the one the constructor's old `mkdirs()` used
        // to be load-bearing for. **It is not**: the directory does not exist
        // until the first write, and this passes anyway, which is the evidence
        // that dropping the constructor side effect changed nothing observable.
        assertNull(storeOn(FakeFileSystem()).read("p1", "history"))
    }

    @Test
    fun aTruncatedFileReadsAsNoCacheRatherThanThrowing() = runTest {
        // The shape a crash mid-write leaves. The store used to write
        // non-atomically too, so this is a real state and not a hypothetical one.
        val fs = FakeFileSystem()
        fs.writeRaw("p1", "history", """[{"item_id":"i1",""")

        assertNull(storeOn(fs).read("p1", "history"))
    }

    @Test
    fun aFileThatIsNotAJsonObjectReadsAsNoCache() = runTest {
        val fs = FakeFileSystem()
        fs.writeRaw("p1", "history", """["an array, not an object"]""")

        assertNull(storeOn(fs).read("p1", "history"))
    }

    @Test
    fun anEntryWithNoItemsKeyAtAllReadsAsNoCache() = runTest {
        // This is the comment on the `read` body, pinned: `has("items")` and
        // `optJsonArray("items")` are different questions, so an entry with a
        // *present but unusable* `items` is "present" to the first and "absent" to
        // the second. The store deliberately keeps both, and this is the case
        // where the distinction decides the answer.
        val fs = FakeFileSystem()
        fs.writeRaw("p1", "history", """{"has_more":true}""")

        assertNull(storeOn(fs).read("p1", "history"))
    }

    @Test
    fun anEntryWithAPresentButUnusableItemsKeyIsStillACacheHit() = runTest {
        // The other half of the pair above, and the reason the distinction is
        // load-bearing rather than pedantic. **A `null` `items` is a cache hit
        // with zero items**, not a miss: the key was there, so the server answered
        // "this section is empty" and the store must not spend a network request
        // re-asking the same question.
        val fs = FakeFileSystem()
        fs.writeRaw("p1", "history", """{"items":null,"has_more":false}""")

        val read = storeOn(fs).read("p1", "history")

        assertEquals(emptyList(), read?.items)
        assertEquals(false, read?.hasMore)
    }

    @Test
    fun anEmptyItemsArrayIsACacheHitAndNotAMiss() = runTest {
        // The third shape, and the one a reader is most likely to get wrong: an
        // empty array is a *present* key with a usable value, so it is a hit. If
        // this were a miss, a genuinely empty section would refetch on every
        // single load, forever.
        val fs = FakeFileSystem()
        fs.writeRaw("p1", "history", """{"items":[],"has_more":false}""")

        val read = storeOn(fs).read("p1", "history")

        assertEquals(emptyList(), read?.items)
        assertEquals(false, read?.hasMore)
    }

    @Test
    fun anItemMissingItsRequiredIdIsSkippedAndTheOthersSurvive() = runTest {
        // `toCatalogItem` returns null for a missing `item_id` or `title`, and
        // `parseItems` drops the null. So a partially-written entry degrades to
        // fewer items rather than to a failed read -- which is the other side of
        // the "one bad element" behaviour recorded for the mutation store, and the
        // two are deliberately different.
        val fs = FakeFileSystem()
        fs.writeRaw(
            "p1",
            "history",
            """{"items":[{"title":"no id"},{"item_id":"keep","title":"Kept"},42],"has_more":false}""",
        )

        assertEquals(listOf("keep"), storeOn(fs).read("p1", "history")?.items?.map { it.id })
    }

    // ---- invalidate -------------------------------------------------------

    @Test
    fun invalidateRemovesTheEntryAndOnlyThatEntry() = runTest {
        val fs = FakeFileSystem()
        val store = storeOn(fs)
        store.write("p1", "history", LibrarySectionPageUi(items = listOf(item(id = "gone")), hasMore = false), null)
        store.write("p1", "watchlist", LibrarySectionPageUi(items = listOf(item(id = "stays")), hasMore = false), null)

        assertTrue(store.invalidate("p1", "history").getOrThrow())

        assertNull(store.read("p1", "history"))
        assertEquals("stays", store.read("p1", "watchlist")?.items?.single()?.id)
    }

    @Test
    fun invalidatingSomethingThatWasNeverWrittenAnswersFalseRatherThanThrowing() = runTest {
        // `FileSystem.delete` returns `false` for an absent path, exactly as
        // `File.delete()` did, so this is the preserved behaviour rather than a
        // new one. The `Result` carries it because the port's KDoc is explicit
        // that narrowing it to `Unit` would discard an answer the implementation
        // has always given -- and a caller *can* see it, which is what makes that
        // KDoc a constraint rather than a preference.
        assertEquals(false, storeOn(FakeFileSystem()).invalidate("p1", "history").getOrThrow())
    }

    // ---- the generation stamp ---------------------------------------------

    @Test
    fun aZeroOrNegativeGenerationReadsAsNoStamp() = runTest {
        // `takeIf { it > 0L }` on the read, and a matching `if (appliedGenerationMs
        // > 0L)` on the write. **Zero is not a generation**: it is what the read
        // uses for "absent", so storing it would make an un-stamped section look
        // stamped and the caller would compare `null` against `0L` forever.
        val fs = FakeFileSystem()
        val store = storeOn(fs)
        store.write("p1", "history", LibrarySectionPageUi(items = listOf(item(id = "i1")), hasMore = false), 0L)

        assertNull(store.read("p1", "history")?.appliedGenerationMs)
    }

    @Test
    fun aGenerationIsStoredAndReadBackExactly() = runTest {
        val fs = FakeFileSystem()
        val store = storeOn(fs)
        store.write("p1", "history", LibrarySectionPageUi(items = listOf(item(id = "i1")), hasMore = false), 1L)

        assertEquals(1L, store.read("p1", "history")?.appliedGenerationMs)
    }

    // ---- write failure ----------------------------------------------------

    @Test
    fun aWriteIntoAFilesystemThatRefusesItAnswersAResultRatherThanThrowing() = runTest {
        // The port returns `Result<Unit>` and the body ends in a `runCatching`, and
        // **that pairing is the whole contract**: the paging source calls `write`
        // on its hot path and must not have a full disk take down a library load.
        // A cache that cannot be written is a cache miss, not a crash.
        val store = LibraryDiskCacheStore(
            fileSystem = DenyEverythingFileSystem(),
            cacheRoot = cacheRoot,
            ioDispatcher = UnconfinedTestDispatcher(),
        )

        val result = store.write("p1", "history", LibrarySectionPageUi(items = listOf(item(id = "i1")), hasMore = false), null)

        assertTrue(result.isFailure)
    }

    // ---- helpers ----------------------------------------------------------

    private fun FakeFileSystem.writeRaw(profile: String, section: String, text: String) {
        val file = cacheFileFor(profile, section)
        createDirectories(requireNotNull(file.parent))
        sink(file).buffer().use { it.writeUtf8(text) }
    }

    private fun item(
        id: String,
        title: String = "Title",
        artworkUrl: String? = "https://a/art.jpg",
        logoUrl: String? = "https://a/logo.jpg",
        artwork: ResponsiveImageSet? = null,
        logo: ResponsiveImageSet? = null,
        addonId: String = "backend",
        type: String = "movie",
        rating: String? = null,
        year: String? = null,
        genre: String? = null,
        maturityRating: String? = null,
        liked: Boolean? = null,
        addedAt: String? = null,
        watchedAt: String? = null,
        ratedAt: String? = null,
        lastActivityAt: String? = null,
        episodeCount: Int? = null,
    ) = CatalogItem(
        id = id,
        itemId = id,
        title = title,
        artworkUrl = artworkUrl,
        logoUrl = logoUrl,
        artwork = artwork,
        logo = logo,
        addonId = addonId,
        type = type,
        rating = rating,
        year = year,
        genre = genre,
        maturityRating = maturityRating,
        liked = liked,
        addedAt = addedAt,
        watchedAt = watchedAt,
        ratedAt = ratedAt,
        lastActivityAt = lastActivityAt,
        episodeCount = episodeCount,
    )
}

/**
 * A [FileSystem] that refuses every write, for the one case a permissive
 * filesystem cannot produce.
 *
 * **`FakeFileSystem` is strict but not hostile**: it throws on a missing parent
 * directory and on a malformed path, and it happily writes everywhere else. So
 * the "the write failed and the caller got a `Result`" case cannot be staged with
 * it, and the alternative -- making the test directory read-only -- is a JVM-only
 * trick that `androidHostTest` on a host with a root-owned checkout would turn
 * into a no-op. The class is a `commonTest` fixture for the same reason
 * `FakeKeyValueStore` is.
 */
private class DenyEverythingFileSystem : FileSystem() {
    private val delegate = FakeFileSystem()

    override fun canonicalize(path: Path): Path = throw UnsupportedOperationException()

    override fun metadataOrNull(path: Path) = delegate.metadataOrNull(path)

    override fun list(dir: Path): List<Path> = delegate.list(dir)

    override fun listOrNull(dir: Path): List<Path>? = delegate.listOrNull(dir)

    override fun openReadOnly(file: Path) = delegate.openReadOnly(file)

    override fun openReadWrite(file: Path, mustCreate: Boolean, mustExist: Boolean) =
        delegate.openReadWrite(file, mustCreate, mustExist)

    override fun source(file: Path) = delegate.source(file)

    override fun sink(file: Path, mustCreate: Boolean) =
        throw UnsupportedOperationException("write refused")

    override fun appendingSink(file: Path, mustExist: Boolean) =
        throw UnsupportedOperationException("write refused")

    override fun createDirectory(dir: Path, mustCreate: Boolean) =
        throw UnsupportedOperationException("write refused")

    override fun atomicMove(source: Path, target: Path) = throw UnsupportedOperationException()

    override fun delete(path: Path, mustExist: Boolean) = delegate.delete(path, mustExist)

    override fun createSymlink(link: Path, target: Path) = throw UnsupportedOperationException()
}
