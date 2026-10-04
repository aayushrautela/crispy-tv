package com.crispy.tv.home

import com.crispy.tv.domain.home.HomeCatalogItem
import com.crispy.tv.domain.home.HomeCatalogList
import com.crispy.tv.domain.home.HomeCatalogPresentation
import com.crispy.tv.domain.home.HomeCatalogSnapshot
import com.crispy.tv.domain.home.HomeCatalogSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.fakefilesystem.FakeFileSystem
import okio.use
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [RecommendationCatalogDiskCacheStore]: what it writes to disk, and what it answers for
 * every shape a file on disk can arrive in.
 *
 * The awkward fixtures are hand-written JSON rather than the output of [write], and that is
 * the point of this suite. **A round trip through the code under test cannot distinguish
 * "reads what it writes" from "reads what a previous build wrote"**, and reading the latter
 * is this store's whole job — every file it will ever find was written by an older build of
 * this app. So the cases below are the shapes an old writer could have produced: a quoted
 * timestamp, a fractional one, an exponent AOSP refused, a missing field, a `null` where a
 * value used to be, a file that is not JSON at all.
 *
 * Every `null` answer is asserted, because "in how many ways can this answer `null`" is the
 * reader's only real property: nothing stored, an unreadable file, unparseable JSON, a blank
 * payload and a non-positive timestamp are five distinct reasons collapsing to one answer,
 * and the sixth case — an entry older than the caller's limit — is the only one that is not
 * an error at all.
 */
class RecommendationCatalogDiskCacheStoreTest {
    private val fileSystem = FakeFileSystem()
    private val root: Path = "/files".toPath()

    private fun store(nowMs: Long = NOW) = RecommendationCatalogDiskCacheStore(
        cacheRoot = root,
        timeSource = FixedTimeSource(nowMs),
        ioDispatcher = Dispatchers.Unconfined,
        fileSystem = fileSystem,
    )

    /**
     * Writes a cache file the way a *previous build* might have, bypassing [write] — which
     * is the only way to put a shape in front of the reader that the writer cannot produce.
     */
    private fun writeRaw(cacheKey: String, body: String) {
        val directory = root.resolve(CACHE_DIRECTORY)
        fileSystem.createDirectories(directory)
        fileSystem.sink(directory.resolve(cacheFileName(cacheKey))).buffer().use { it.writeUtf8(body) }
    }

    private fun cacheFileOnDisk(cacheKey: String): Path =
        root.resolve(CACHE_DIRECTORY).resolve(cacheFileName(cacheKey))

    private fun envelopeOnDisk(cacheKey: String): JsonObject =
        Json.parseToJsonElement(fileSystem.read(cacheFileOnDisk(cacheKey)) { readUtf8() }).jsonObject

    @Test
    fun `a written entry reads back with its payload and the instant it was stamped`() = runTest {
        val subject = store(nowMs = NOW)

        subject.write(KEY, PAYLOAD)
        val read = subject.read(KEY)

        assertEquals(PAYLOAD, read?.payload)
        assertEquals(NOW, read?.timestampMs)
    }

    @Test
    fun `the envelope on disk is a timestamp and a payload, and the payload is trimmed`() = runTest {
        // Both ends trim and they are not the same trim. `write` stores the trimmed payload,
        // so a caller handing over a padded snapshot does not leave a file whose bytes
        // differ from every other file's for a reason no reader could see.
        store().write(KEY, "\n  $PAYLOAD  \n")

        val envelope = envelopeOnDisk(KEY)
        assertEquals(setOf("timestamp_ms", "payload"), envelope.keys)
        assertEquals(NOW, (envelope["timestamp_ms"] as JsonPrimitive).content.toLong())
        assertEquals(PAYLOAD, (envelope["payload"] as JsonPrimitive).content)
    }

    @Test
    fun `a blank payload is not written at all rather than written and then refused`() = runTest {
        val subject = store()

        subject.write(KEY, "   \n  ")

        // The absence of the file, not the absence of a read: a reader that cannot find a
        // file and a reader that finds an unreadable one answer the same thing, so asserting
        // the read would pass whether or not the write happened.
        assertFalse(fileSystem.exists(cacheFileOnDisk(KEY)))
        assertNull(subject.read(KEY))
    }

    @Test
    fun `the file lands under the cache directory, named by its digest`() = runTest {
        store().write(KEY, PAYLOAD)

        // The directory name is a stored-format contract of its own: renaming it orphans
        // every cached feed on every installed device, and nothing in the running app says
        // so. Asserting the whole relative path is what makes the assertion about the
        // directory too — the file name alone is [CacheFileNameTest]'s business.
        assertEquals(
            "/files/$CACHE_DIRECTORY/${cacheFileName(KEY)}",
            cacheFileOnDisk(KEY).toString(),
        )
    }

    @Test
    fun `an entry stamped later than the reading clock is fresh rather than infinitely old`() = runTest {
        // A user whose clock jumps forward between the write and the read must not have
        // their home feed invalidated, which is what an unclamped `now - stamped` does. The
        // clamp is the whole reason `CachedPayload.ageMs` coerces at zero.
        store(nowMs = NOW + 60_000).write(KEY, PAYLOAD)

        val read = store(nowMs = NOW).read(KEY)

        assertNotNull(read)
        assertEquals(0L, read.ageMs(NOW))
        assertNotNull(store(nowMs = NOW).read(KEY, maxAgeMs = 1L))
    }

    @Test
    fun `an entry older than the limit is refused and one exactly at the limit is not`() = runTest {
        store(nowMs = NOW).write(KEY, PAYLOAD)
        val subject = store(nowMs = NOW + 1000)

        // The comparison is `age > limit`, so the boundary belongs to the caller: at exactly
        // the limit the entry is still good. A rewrite to `>=` would make every caller's
        // limit one millisecond stricter than the code says it is.
        assertNotNull(subject.read(KEY, maxAgeMs = 1000L))
        assertNull(subject.read(KEY, maxAgeMs = 999L))
    }

    @Test
    fun `a non-positive limit is no limit at all`() = runTest {
        // `maxAgeMs?.takeIf { it > 0L }`. The alternative reading — an entry is always stale
        // when the limit is zero — would push every caller to pass `null` and hide the
        // intent from this port, so the two spellings have to agree here rather than at the
        // four call sites that use them.
        store(nowMs = NOW).write(KEY, PAYLOAD)
        val muchLater = store(nowMs = NOW + 10_000_000)

        assertNotNull(muchLater.read(KEY, maxAgeMs = null))
        assertNotNull(muchLater.read(KEY, maxAgeMs = 0L))
        assertNotNull(muchLater.read(KEY, maxAgeMs = -1L))
    }

    @Test
    fun `every unusable file answers null and none of them throws`() = runTest {
        val subject = store(nowMs = NOW)
        // Five reasons, one answer, written as one test on purpose: the property is "a cache
        // file that cannot be trusted is indistinguishable from no file", and five separate
        // tests would let four of them rot unnoticed.
        writeRaw("absent-timestamp", """{"payload":"{}"}""")
        writeRaw("zero-timestamp", """{"timestamp_ms":0,"payload":"{}"}""")
        writeRaw("negative-timestamp", """{"timestamp_ms":-5,"payload":"{}"}""")
        writeRaw("json-null-timestamp", """{"timestamp_ms":null,"payload":"{}"}""")
        writeRaw("blank-payload", """{"timestamp_ms":$NOW,"payload":"   "}""")
        writeRaw("null-payload", """{"timestamp_ms":$NOW,"payload":null}""")
        writeRaw("missing-payload", """{"timestamp_ms":$NOW}""")
        writeRaw("not-an-object", """["timestamp_ms",$NOW]""")
        writeRaw("unparseable", """{"timestamp_ms":""")
        writeRaw("not-json-at-all", "definitely not json")

        for (key in listOf(
            "absent-timestamp", "zero-timestamp", "negative-timestamp", "json-null-timestamp",
            "blank-payload", "null-payload", "missing-payload", "not-an-object",
            "unparseable", "not-json-at-all",
        )) {
            assertNull(subject.read(key), "read(\"$key\") should answer null")
        }
        // And the absent-key case, which is the only one of the eleven that can be wrong
        // without any of the others changing.
        assertNull(subject.read("never-written"))
    }

    @Test
    fun `a timestamp that org-json accepted is still accepted`() = runTest {
        // AOSP's `optLong` answers `Long.parseLong` for a *quoted* number and a truncating
        // cast for a real one, so both of these are readable and have to stay readable. A
        // port that reached for `longOrNull` would accept all three cases below, and the
        // third is the one it must not.
        store(nowMs = NOW)
        writeRaw("quoted", """{"timestamp_ms":"$NOW","payload":"$PAYLOAD"}""")
        writeRaw("truncated", """{"timestamp_ms":$NOW.9,"payload":"$PAYLOAD"}""")
        writeRaw("padded", """{"timestamp_ms":" $NOW ","payload":"$PAYLOAD"}""")

        val subject = store(nowMs = NOW)
        assertEquals(PAYLOAD, subject.read("quoted")?.payload)
        assertEquals(PAYLOAD, subject.read("truncated")?.payload)
        assertEquals(PAYLOAD, subject.read("padded")?.payload)
    }

    @Test
    fun `a timestamp org-json refused is still refused`() = runTest {
        // `"1e3"` parses as a number in nearly every JSON library, and AOSP's `optLong`
        // answered the *default* for it. A lenient port would read as an entry what its
        // predecessor wrote off as not-an-entry, and the default is load-bearing: it is what
        // makes `timestamp_ms <= 0` the "this is not a cache entry" test above.
        writeRaw("exponent", """{"timestamp_ms":"1e3","payload":"$PAYLOAD"}""")
        writeRaw("fraction", """{"timestamp_ms":"12.5","payload":"$PAYLOAD"}""")

        val subject = store(nowMs = NOW)
        assertNull(subject.read("exponent"))
        assertNull(subject.read("fraction"))
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val KEY = "home_snapshot:profile-1"
        /** No quotes or braces in it: the fixtures in this class are hand-built JSON by
         * string interpolation, so a payload containing either would make the file itself
         * unparseable and the case would be measuring its fixture. */
        const val PAYLOAD = "cached-payload"

        /** `private` in the store, and re-deriving it by importing it is impossible, so it
         * is spelled out once here rather than copied into three assertions. If the store's
         * directory name changes, `the file lands under the cache directory` fails. */
        const val CACHE_DIRECTORY = "recommendation_catalog_cache"
    }
}

/**
 * [DiskHomeCatalogSnapshotCache]: the mapping in both directions, plus the two facts about
 * the *stored payload* that a round trip cannot see.
 *
 * ## What this suite is careful about
 *
 * - **The key set, asserted as a set.** "Is every member of this group X?" is only worth
 *   asserting as a set comparison — a hand-picked list is a sample, and a key that quietly
 *   changed would simply not be in it. So [the written payload names exactly the expected
 *   keys] compares whole key sets, per object type, in both directions.
 * - **The two null shapes are different, and both deliberate.** A null *scalar* is an
 *   omitted key, because `org.json`'s `put(key, null)` removes the key and an older build
 *   must not meet a `null` it never wrote; a null *value inside the artwork and logo maps* is
 *   written as `null`, because `JSONObject(map)` wrapped it as `JSONObject.NULL`. That is the
 *   only place the suite claims a `null` is on purpose.
 * - **One payload, two keys.** The writer stores the same payload under the per-profile key
 *   and the global one, and the reader walks them in that order. Both halves are asserted,
 *   because a writer storing only one leaves the other reader with a permanent miss that no
 *   round-trip test would report.
 */
class DiskHomeCatalogSnapshotCacheTest {
    private val fileSystem = FakeFileSystem()
    private val root: Path = "/files".toPath()

    private fun cache(nowMs: Long = NOW) = DiskHomeCatalogSnapshotCache(
        RecommendationCatalogDiskCacheStore(
            cacheRoot = root,
            timeSource = FixedTimeSource(nowMs),
            ioDispatcher = Dispatchers.Unconfined,
            fileSystem = fileSystem,
        ),
    )

    /** The cache directory's listing, by file name, so a test can assert *which* keys exist. */
    private fun storedFileNames(): List<String> =
        fileSystem.listRecursively(root.resolve("recommendation_catalog_cache")).map { it.name }.toList().sorted()

    /** The payload a previous build's store would have wrapped under [cacheKey]. */
    private fun writeRawPayload(cacheKey: String, payload: String) {
        val directory = root.resolve("recommendation_catalog_cache")
        fileSystem.createDirectories(directory)
        fileSystem.sink(directory.resolve(cacheFileName(cacheKey))).buffer().use {
            it.writeUtf8(
                buildJsonObject {
                    put("timestamp_ms", NOW)
                    put("payload", payload)
                }.toString(),
            )
        }
    }

    private fun payloadUnder(cacheKey: String): JsonObject {
        val file = root.resolve("recommendation_catalog_cache").resolve(cacheFileName(cacheKey))
        val envelope = Json.parseToJsonElement(fileSystem.read(file) { readUtf8() }).jsonObject
        val payload = (envelope["payload"] as JsonPrimitive).content
        return Json.parseToJsonElement(payload).jsonObject
    }

    @Test
    fun `a snapshot survives a round trip through the store`() = runTest {
        val subject = cache()
        val snapshot = sampleSnapshot(populated = true)

        subject.write("profile-1", snapshot, expiresAtIso = "2030-01-01T00:00:00Z")
        val read = subject.read("profile-1")

        assertEquals(snapshot, read?.snapshot)
        assertEquals("2030-01-01T00:00:00Z", read?.expiresAtIso)
    }

    @Test
    fun `a read answers null rather than an empty snapshot when nothing was written`() = runTest {
        assertNull(cache().read("profile-1"))
        assertNull(cache().read(null))
    }

    @Test
    fun `the writer stores one payload under both the profile key and the global one`() = runTest {
        cache().write("profile-1", sampleSnapshot(populated = true), expiresAtIso = null)

        // The file list rather than the read, because a reader that found only the global
        // copy would answer the same thing with the profile copy missing.
        assertEquals(
            listOf(cacheFileName(GLOBAL_CACHE_KEY), cacheFileName(homeCacheKey("profile-1"))).sorted(),
            storedFileNames(),
        )
    }

    @Test
    fun `a null profile writes only the global key`() = runTest {
        cache().write(null, sampleSnapshot(populated = true), expiresAtIso = null)

        assertEquals(listOf(cacheFileName(GLOBAL_CACHE_KEY)), storedFileNames())
    }

    @Test
    fun `a blank profile id is trimmed away rather than becoming a key of its own`() = runTest {
        cache().write("   ", sampleSnapshot(populated = true), expiresAtIso = null)

        // The reader builds its key list the same way, so an entry written under "   " would
        // be one nothing ever reads: a cache file no lookup can reach, which is the failure
        // a cache is supposed to make impossible.
        assertFalse(storedFileNames().contains(cacheFileName(homeCacheKey("   "))))
        assertEquals(listOf(cacheFileName(GLOBAL_CACHE_KEY)), storedFileNames())
    }

    @Test
    fun `the profile entry wins over the global one`() = runTest {
        // The reader walks the per-profile key first, and that order is the whole reason the
        // signed-in feed does not show the last signed-out user's rows. Both entries are
        // seeded with *different* snapshots, so a reversed walk fails rather than passing
        // because the two copies happened to agree.
        // Both entries are seeded raw, and the reason is a property of the *writer* worth
        // stating: `write` stores the same payload under the profile key and the global one,
        // so writing a profile snapshot overwrites the global copy. A case that seeded the
        // two through the writer could never make them disagree, and would therefore be
        // asserting nothing at all about which one the reader reaches first.
        val subject = cache()
        writeRawPayload(homeCacheKey("profile-1"), OLD_SHAPE.withTitle("PROFILE"))
        writeRawPayload(GLOBAL_CACHE_KEY, OLD_SHAPE.withTitle("GLOBAL"))

        assertEquals("PROFILE", subject.read("profile-1")?.snapshot?.lists?.single()?.title)
        assertEquals("GLOBAL", subject.read("someone-else")?.snapshot?.lists?.single()?.title)
    }

    @Test
    fun `the expiry crosses as the trimmed string and an absent or blank one is null`() = runTest {
        val subject = cache()
        val snapshot = sampleSnapshot(populated = true)

        subject.write("profile-1", snapshot, expiresAtIso = "  2030-01-01T00:00:00Z  ")
        assertEquals("2030-01-01T00:00:00Z", subject.read("profile-1")?.expiresAtIso)

        subject.write("profile-1", snapshot, expiresAtIso = "   ")
        assertNull(subject.read("profile-1")?.expiresAtIso)

        subject.write("profile-1", snapshot, expiresAtIso = null)
        assertNull(subject.read("profile-1")?.expiresAtIso)
    }

    @Test
    fun `the written payload names exactly the expected keys`() = runTest {
        cache().write(
            "profile-1",
            sampleSnapshot(artwork = mapOf("poster" to null), populated = false),
            expiresAtIso = null,
        )

        val payload = payloadUnder(homeCacheKey("profile-1"))
        assertEquals(setOf("profile_id", "status_message", "lists"), payload.keys)

        // `layout` is absent here: it is the one nullable scalar the list writes, and it is
        // the case a `put(key, null)` rewrite would turn into an explicit null.
        val list = payload.singleList()
        assertEquals(
            setOf("kind", "variant_key", "source", "presentation", "name", "heading", "title", "subtitle", "media_types", "items"),
            list.keys,
        )

        // The seven nullable scalars — `artwork_url`, `logo_url`, `layout` above, and
        // `rating`, `year`, `genre`, `description`, `tagline` here — are all *absent* rather
        // than null. They are named so that adding an eighth to the model fails this test
        // instead of silently starting to write a null an older build never saw.
        val item = list.singleList("items")
        assertEquals(
            setOf("item_id", "title", "artwork", "logo", "addon_id", "type"),
            item.keys,
        )
        // The exception the KDoc names: a null *value inside the artwork map* is written as
        // null, because `JSONObject(map)` wrapped it that way. If this became an absent
        // entry, a read would still answer an empty map and nothing would report it.
        assertEquals<Map<String, JsonElement?>>(mapOf("poster" to JsonNull), item["artwork"]!!.jsonObject)
        assertEquals<Map<String, JsonElement?>>(mapOf("poster" to JsonNull), item["logo"]!!.jsonObject)
    }

    @Test
    fun `a fully populated item writes every key the writer knows`() = runTest {
        // The complement of the case above, and not redundant with it: absent-key
        // assertions cannot tell "skipped because it was null" from "never written at all",
        // and only this case can.
        cache().write("profile-1", sampleSnapshot(artwork = mapOf("poster" to "/p.jpg"), populated = true), expiresAtIso = null)

        val payload = payloadUnder(homeCacheKey("profile-1"))
        val list = payload.singleList()
        val item = list.singleList("items")
        assertEquals(
            setOf(
                "item_id", "title", "artwork_url", "logo_url", "artwork", "logo", "addon_id",
                "type", "rating", "year", "genre", "description", "tagline",
            ),
            item.keys,
        )
        assertEquals("heroCarousel", jsonString(list, "layout"))
        assertEquals("profile-1", jsonString(payload, "profile_id"))
        assertEquals(setOf("movie"), mediaTypesIn(list))
    }

    @Test
    fun `a payload written by an older build is still readable`() = runTest {
        // The compatibility claim this landing can actually test. The key set is the same,
        // but an old *file* may be missing the keys a newer model added and may hold `null`
        // where the current writer omits the key — so the reader is fed the oldest shape that
        // could plausibly be on disk: no optional scalar, no `layout`, no `profile_id`, and
        // `null` for both artwork maps.
        val subject = cache()
        writeRawPayload(homeCacheKey("profile-1"), OLD_SHAPE)

        val read = subject.read("profile-1")

        assertEquals("", read?.snapshot?.statusMessage)
        assertNull(read?.snapshot?.profileId)
        val list = read?.snapshot?.lists?.single()
        assertEquals("continue_watching", list?.kind)
        assertNull(list?.layout)
        assertEquals(HomeCatalogSource.PERSONAL, list?.source)
        assertEquals(HomeCatalogPresentation.RAIL, list?.presentation)
        val item = list?.items?.single()
        assertEquals("tt1", item?.itemId)
        assertTrue(item?.artwork?.isEmpty() == true)
        assertTrue(item?.logo?.isEmpty() == true)
        assertNull(item?.artworkUrl)
        assertNull(read?.expiresAtIso)
    }

    @Test
    fun `an unusable payload is skipped and the next cache key is tried`() = runTest {
        val subject = cache()
        writeRawPayload(homeCacheKey("profile-1"), "not json at all")
        writeRawPayload(GLOBAL_CACHE_KEY, OLD_SHAPE)

        // Both halves are load-bearing. The profile entry exists and is unreadable, so a
        // reader that gave up on the first key would answer null; one that carries on returns
        // the global copy, which is what keeps a signed-out cache serving a signed-in app
        // that has not fetched yet.
        assertEquals("Show", subject.read("profile-1")?.snapshot?.lists?.single()?.items?.single()?.title)
    }

    @Test
    fun `a list with no kind and an item missing a required field are both dropped`() = runTest {
        val subject = cache()
        writeRawPayload(
            GLOBAL_CACHE_KEY,
            """
            {"status_message":"kept","lists":[
              {"kind":"  ","items":[]},
              {"kind":"rail","items":[
                {"title":"no item id","addon_id":"trakt","type":"movie"},
                {"item_id":"tt2","addon_id":"trakt","type":"movie"},
                {"item_id":"tt3","title":"No addon","type":"movie"},
                {"item_id":"tt4","title":"No type","addon_id":"trakt"},
                {"item_id":"tt5","title":"Kept","addon_id":"trakt","type":"movie"}
              ]}
            ]}
            """.trimIndent(),
        )

        val read = subject.read(null)

        // A list is dropped for a blank `kind` but an item for any of four missing
        // identifiers, so the two rules are asserted together: the count of what survives is
        // what says both fired, and a test that listed only the surviving rows would pass
        // with either rule missing.
        assertEquals(listOf("rail"), read?.snapshot?.lists?.map { it.kind })
        assertEquals(listOf("tt5"), read?.snapshot?.lists?.single()?.items?.map { it.itemId })
        assertEquals("kept", read?.snapshot?.statusMessage)
    }

    @Test
    fun `an unknown source falls back to personal and an unknown presentation to rail`() = runTest {
        val subject = cache()
        writeRawPayload(
            GLOBAL_CACHE_KEY,
            """{"status_message":"","lists":[{"kind":"rail","source":"martian","presentation":"hologram","items":[]}]}""",
        )

        val list = subject.read(null)?.snapshot?.lists?.single()

        // Both are fallbacks rather than rejections, and that is the asymmetry worth stating:
        // the row is dropped for a blank `kind` but kept for a source this build has never
        // heard of, because dropping it would empty a rail the backend still serves.
        assertEquals(HomeCatalogSource.PERSONAL, list?.source)
        assertEquals(HomeCatalogPresentation.RAIL, list?.presentation)
    }

    @Test
    fun `media types are trimmed and blanks are dropped`() = runTest {
        val subject = cache()
        writeRawPayload(
            GLOBAL_CACHE_KEY,
            """{"status_message":"","lists":[{"kind":"rail","media_types":[" movie ","","   ",5],"items":[]}]}""",
        )

        // A blank entry kept would become a media-type filter that matches nothing, which
        // renders as a rail the user cannot explain rather than as a missing filter.
        assertEquals(setOf("movie", "5"), subject.read(null)?.snapshot?.lists?.single()?.mediaTypes)
    }

    private fun JsonObject.singleList(): JsonObject = (this["lists"] as JsonArray).single().jsonObject

    private fun JsonObject.singleList(key: String): JsonObject = (this[key] as JsonArray).single().jsonObject

    /** A stored string, the way the payload holds one. Returns `null` for anything else, so
     * a test that reads a key as a string cannot pass by reading a number's text. */
    private fun jsonString(json: JsonObject, key: String): String? =
        (json[key] as? JsonPrimitive)?.content

    private fun mediaTypesIn(list: JsonObject): Set<String> =
        (list["media_types"] as JsonArray).map { (it as JsonPrimitive).content }.toSet()

    private fun sampleSnapshot(
        title: String = "Show",
        artwork: Map<String, String?> = emptyMap(),
        populated: Boolean,
    ) = HomeCatalogSnapshot(
        profileId = "profile-1",
        lists = listOf(
            HomeCatalogList(
                kind = "continue_watching",
                variantKey = "default",
                source = HomeCatalogSource.PERSONAL,
                presentation = HomeCatalogPresentation.RAIL,
                layout = if (populated) "heroCarousel" else null,
                name = "Continue watching",
                heading = "",
                title = title,
                subtitle = "",
                items = listOf(
                    HomeCatalogItem(
                        itemId = "tt1",
                        title = title,
                        artworkUrl = if (populated) "/poster.jpg" else null,
                        logoUrl = if (populated) "/logo.png" else null,
                        artwork = artwork,
                        logo = artwork,
                        addonId = "trakt",
                        type = "movie",
                        rating = if (populated) "8.4" else null,
                        year = if (populated) "2024" else null,
                        genre = if (populated) "Drama" else null,
                        description = if (populated) "A description." else null,
                        tagline = if (populated) "A tagline." else null,
                    ),
                ),
                mediaTypes = setOf("movie"),
            ),
        ),
        statusMessage = "",
    )

    private companion object {
        const val NOW = 1_700_000_000_000L

        /** The oldest shape this reader could plausibly meet: no optional scalar, no
         * `layout`, no `profile_id`, `null` where the current writer omits a key. */
        val OLD_SHAPE = oldShape("Show")

        fun oldShape(itemTitle: String): String = """
            {"status_message":"","lists":[{"kind":"continue_watching","variant_key":"default",
            "source":"personal","presentation":"rail","name":"","heading":"","title":"$itemTitle","subtitle":"",
            "media_types":[],"items":[{"item_id":"tt1","title":"$itemTitle","artwork":null,"logo":null,
            "addon_id":"trakt","type":"movie","description":null}]}]}
        """.trimIndent()

        /** The same payload with a different item title, for seeding two entries that
         * disagree — which the writer cannot produce, because it overwrites the global key
         * with whatever profile snapshot it was handed. */
        fun String.withTitle(title: String): String = replace("\"Show\"", "\"$title\"")
    }
}