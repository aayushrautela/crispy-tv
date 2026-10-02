package com.crispy.tv.addons.registry

import com.crispy.tv.platform.KeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [MetadataAddonRegistry] over an in-memory [KeyValueStore] and a hand-advanced
 * clock.
 *
 * The two things this suite exists to pin are the ones a *move* cannot break
 * silently and a *reader* cannot check:
 *
 * - **`installationId`, which is half of every persisted addon identity.** The
 *   ten goldens below were produced by **running the `MessageDigest`/`StandardCharsets`
 *   code this file used to contain on a JVM** and printing them -- not by
 *   computing what SHA-1 should be. That is deliberate and it is the
 *   `libraryCacheFileName` lesson one level up: a correct digest of a string the
 *   function does not build is still a wrong expectation, and "I measured it"
 *   reads like evidence in a way "I wrote it out" does not. What changed here is
 *   the *renderer*, not the digest -- `digest.take(6).joinToString { "%02x".format(it) }`
 *   became `sha1().hex().take(12)` -- and a renderer change is exactly where
 *   `take(6)`-means-six-bytes silently becomes twelve-nibbles-of-something-else.
 * - **The manifest a seed normalizes to**, because `manifestUrl` is persisted and
 *   is what `installationId` is a function of. The `stremio://` rewrite, the
 *   `HTTPS://` case-preservation and the trailing-`manifest.json` drop are the
 *   three decisions the KDoc of `ManifestUri` cites.
 *
 * `[nowMs]` is a slot with no default precisely so `addedAtEpochMs` is a value
 * this suite states rather than a wall clock it has to tolerate.
 */
class MetadataAddonRegistryTest {

    private var clock = 1_700_000_000_000L
    private val store = MemoryKeyValueStore()

    private fun registry() = MetadataAddonRegistry(store) { clock }

    // --- installationId: ten goldens from the code this replaces ----------------------

    /**
     * `addOnManifestUrl` is the only route by which an installation id reaches
     * storage, so the goldens are asserted through it rather than against
     * `installationId` directly -- which is also what "measured through the
     * function" means. The id is `"$normalizedHint:$hash"`; the hint here is the
     * host, and every host below is already lowercase.
     */
    private fun installationIdOf(manifestUrl: String): String {
        val registry = registry()
        registry.reconcileCloudAddons(listOf(CloudAddonRow(manifestUrl, sortOrder = 0)))
        val stored = assertNotNull(registry.orderedSeeds().firstOrNull(), "a reconcile with one row seeds one addon")
        return stored.installationId
    }

    /**
     * [idHint] is the **id's** hint, not the seed's. `installationId` lowercases it
     * and replaces every character outside `[a-z0-9._-]`, so a host typed as
     * `Example.COM` arrives at the id as `example.com` while the *seed* keeps
     * `Example.COM`. The first version of this helper took the host and failed on
     * exactly that row -- **the id and the seed are different strings, and only one
     * of them is normalized.**
     */
    private fun assertGolden(manifestUrl: String, idHint: String, hash: String) {
        assertEquals("$idHint:$hash", installationIdOf(manifestUrl), "installationId for <$manifestUrl>")
    }

    @Test
    fun installationIdGoldens() {
        assertGolden("https://example.com/manifest.json", "example.com", "e501bb3efcfe")
        assertGolden("HTTPS://Example.COM/Manifest.JSON?k=1#frag", "example.com", "ce24f333c222")
        assertGolden("https://user:pw@example.com:8443/a%20b/manifest.json", "example.com", "4b09b731c7d6")
        assertGolden("https://[::1]:9000/manifest.json", "1", "c8d74417689f")
        assertGolden("https://example.com/a/manifest.json", "example.com", "571d11bdb482")
        assertGolden("https://example.com/manifest", "example.com", "3cdec0acef5b")
        assertGolden("https://example.com//double//manifest.json", "example.com", "e4d10a7efa2d")

        // Two rows whose *hint* is not the host, because `parseManifestSeed` maps a
        // cinemeta/opensubtitles host onto the addon's published id. Reading the
        // hint off the host -- which is what the first version of this test did,
        // and it failed -- is the same mistake as a fixture written from a
        // parameter name instead of from the format.
        assertGolden("https://opensubtitles-v3.strem.io/manifest.json", "org.stremio.opensubtitlesv3", "f86499196875")
        assertGolden("https://v3-cinemeta.strem.io/manifest.json", "com.linvo.cinemeta", "0dc358cfcfe4")
    }

    /**
     * **The strongest thing the goldens can say, and it needed the normalization
     * to be correct to say it:** a `stremio://` URL and its `https://` twin are
     * *one* installation, not two. The `stremio://` form normalizes before the id
     * is computed, so its hash is the `https` row's hash and not the digest of the
     * raw string -- and `93c59c89cbc0`, which is what SHA-1 of the *un-normalized*
     * `stremio://…` gives, is the value this assertion exists to reject.
     *
     * Without that, a registry that hashed the pre-normalization string would pass
     * every golden above and re-identify every installed addon the moment a user's
     * stored URL came from a `stremio://` source.
     */
    @Test
    fun aStremioUrlAndItsHttpsTwinAreOneInstallation() {
        val stremio = installationIdOf("stremio://opensubtitles-v3.strem.io/manifest.json")
        val https = installationIdOf("https://opensubtitles-v3.strem.io/manifest.json")
        assertEquals(https, stremio, "the id is a function of the stored manifestUrl, and that is the normalized one")
    }

    /**
     * The twelve digits are the first **six bytes**, and it is worth a case that
     * pins where the cut falls: the full SHA-1 of `https://example.com/manifest.json`
     * begins `e501bb3efcfe…`, and a renderer that took twelve *bytes* would answer
     * twenty-four characters. Asserting the length is the cheap half; the golden
     * above is the half that would catch a wrong byte offset inside the first six.
     */
    @Test
    fun anInstallationIdCarriesTwelveHexDigitsAndNothingMore() {
        val id = installationIdOf("https://example.com/manifest.json")
        val hash = id.substringAfter(':')
        assertEquals(12, hash.length, "six bytes of SHA-1, and no more")
        assertEquals(hash.lowercase(), hash, "MessageDigest's `%02x` and ByteString.hex() are both lower-case")
        assertTrue(hash.all { it in "0123456789abcdef" }, "<$hash> is lower-case hex")
    }

    /**
     * The hint half of an installation id goes through
     * `lowercase().replace(Regex("[^a-z0-9._-]"), "-").trim('-').ifEmpty { "addon" }`,
     * and that is a *sharper* transformation than it looks. An IPv6 host hint of
     * `[::1]` collapses to the single character `1` -- every bracket and colon
     * becomes a dash and the dashes at both ends are then trimmed -- and a host made
     * entirely of excluded characters becomes the literal `addon`.
     *
     * **Two hosts can therefore share a hint, and only the hash half keeps them
     * apart.** That is worth a case of its own, because the alternative reading --
     * that the hint is the host -- is what the first version of these goldens
     * asserted, and it fails on exactly this row.
     */
    @Test
    fun theIdHintIsSanitisedAndCanCollapseToNothing() {
        assertTrue(installationIdOf("https://[::1]:9000/manifest.json").startsWith("1:"), "brackets and colons are dashes, then trimmed")
        assertTrue(installationIdOf("https://[::1]:9001/manifest.json").startsWith("1:"), "and the port is not in the hint")
        assertTrue(
            installationIdOf("https://[::1]:9000/manifest.json") != installationIdOf("https://[::1]:9001/manifest.json"),
            "two hosts with the same collapsed hint are still two installations, because the hash is of the url",
        )
    }

    // --- the normalization decisions the persisted manifestUrl depends on ---------------

    private fun seedOf(raw: String): AddonManifestSeed? {
        val registry = registry()
        registry.reconcileCloudAddons(listOf(CloudAddonRow(raw, sortOrder = 0)))
        return registry.orderedSeeds().firstOrNull()
    }

    @Test
    fun aStremioUrlIsStoredRewrittenToHttps() {
        val seed = assertNotNull(seedOf("stremio://opensubtitles-v3.strem.io/manifest.json"))
        assertEquals(
            "https://opensubtitles-v3.strem.io/manifest.json",
            seed.manifestUrl,
            "the stored URL is the normalized one, and it is what installationId hashed",
        )
        assertEquals("stremio://opensubtitles-v3.strem.io/manifest.json", seed.originalManifestUrl)
    }

    /** `Uri` lowercases nothing, and the stored value is the thing that must not drift. */
    @Test
    fun theStoredUrlKeepsTheCaseTheUserTyped() {
        val seed = assertNotNull(seedOf("HTTPS://Example.COM/Manifest.JSON"))
        assertEquals("HTTPS://Example.COM/Manifest.JSON", seed.manifestUrl)
        assertEquals("Example.COM", seed.addonIdHint, "the hint is the host, so it carries the host's case too")
    }

    @Test
    fun theBaseUrlIsTheDirectoryAboveTheManifestSegment() {
        assertEquals("https://example.com/a/b", assertNotNull(seedOf("https://example.com/a/b/manifest.json")).baseUrl)
        assertEquals("https://example.com", assertNotNull(seedOf("https://example.com/manifest.json")).baseUrl)
        assertEquals("https://example.com/a/b", assertNotNull(seedOf("https://example.com/a/b")).baseUrl, "no manifest segment to drop")
    }

    @Test
    fun aQueryIsKeptBesideTheBaseUrlAndNotInIt() {
        val seed = assertNotNull(seedOf("https://example.com/manifest.json?foo=bar%20baz"))
        assertEquals("foo=bar%20baz", seed.encodedQuery, "encodedQuery is the raw encoded query, undecoded")
        assertEquals("https://example.com", seed.baseUrl)
    }

    /**
     * A URL with no host produces no row. **It does not produce an empty registry**,
     * because reading the registry always seeds the default OpenSubtitles addon, so
     * "no seed" has to be counted over the reconcile rather than read off
     * `orderedSeeds().firstOrNull()` -- which answers with the default addon and
     * makes every no-seed assertion here pass for the wrong reason. That is what the
     * first version of this case did.
     */
    @Test
    fun aUrlWithNoHostReconcilesNothing() {
        assertEquals(0, registry().reconcileCloudAddons(listOf(CloudAddonRow("stremio://", 0))), "no host, no row")
        assertEquals(
            0,
            registry().reconcileCloudAddons(listOf(CloudAddonRow("   ", 0))),
            "and blank input is rejected before the parser runs",
        )
        assertEquals(
            listOf("https://opensubtitles-v3.strem.io/manifest.json"),
            registry().orderedSeeds().map { it.manifestUrl },
            "so the only installation left is the one the registry seeds for itself",
        )
    }

    // --- the two default addons, the clock slot, and the removal set -------------------

    @Test
    fun readingTheRegistrySeedsTheDefaultOpenSubtitlesAddon() {
        val seeds = registry().orderedSeeds()
        assertEquals(1, seeds.size, "buildDesiredSeeds adds OpenSubtitles when it has not been removed")
        val seed = seeds.first()
        assertEquals("org.stremio.opensubtitlesv3", seed.addonIdHint)
        assertEquals("https://opensubtitles-v3.strem.io/manifest.json", seed.manifestUrl)
    }

    @Test
    fun addedAtIsTheInjectedClockAndNotTheWallClock() {
        clock = 1_234_567_890L
        val first = assertNotNull(registry().orderedSeeds().firstOrNull())
        assertEquals(
            "org.stremio.opensubtitlesv3:${first.installationId.substringAfter(':')}",
            first.installationId,
            "sanity: the hint is the default addon's, so the id is not being rebuilt from the seed",
        )
        // A seed does not expose the timestamp, so it is read back off the stored JSON.
        val state = assertNotNull(store.getString("state_json"))
        assertTrue(state.contains("1234567890"), "the injected clock reached the persisted state: $state")
    }

    @Test
    fun removingTheDefaultAddonStopsItBeingReSeeded() {
        val registry = registry()
        registry.markAddonRemoved("org.stremio.opensubtitlesv3")
        assertEquals(0, registry.orderedSeeds().size, "the desired seed is built from the removal set")
    }

    @Test
    fun removingByManifestAddonIdAlsoDropsTheInstallation() {
        val registry = registry()
        val seeded = assertNotNull(registry.orderedSeeds().firstOrNull())
        registry.markAddonRemoved("org.stremio.opensubtitlesv3")
        assertEquals(0, registry.orderedSeeds().size)
        assertEquals(0, registry.reconcileCloudAddons(emptyList()), "an empty row list returns before it touches state")
        assertEquals(emptyList(), registry.orderedSeeds(), "and the removal survives a no-op reconcile")
        assertTrue(seeded.installationId.isNotEmpty())
    }

    @Test
    fun cloudRowsAreReconciledInSortOrderThenLowercasedUrlAndACaseVariantIsTheSameInstallation() {
        val registry = registry()
        registry.reconcileCloudAddons(
            listOf(
                CloudAddonRow("https://b.example/manifest.json", sortOrder = 1),
                CloudAddonRow("https://a.example/manifest.json", sortOrder = 0),
                CloudAddonRow("https://A.EXAMPLE/manifest.json", sortOrder = 0),
            ),
        )
        val order = registry.orderedSeeds().map { it.manifestUrl }
        // `https://a.example/...` and `https://A.EXAMPLE/...` are ONE installation:
        // the id hashes the *lowercased* manifestUrl, so both rows carry the same id
        // and the second overwrites the first. That is why there are three URLs here
        // and not four, and a case that asserted the four would have been asserting
        // a guess about whether ids are case-sensitive.
        assertEquals(
            listOf(
                "https://A.EXAMPLE/manifest.json",
                "https://b.example/manifest.json",
                "https://opensubtitles-v3.strem.io/manifest.json",
            ),
            order,
            "sortOrder first, then the lowercased url; a case-variant url is the same installation",
        )
    }

    @Test
    fun aCorruptStoredStateReadsAsEmptyRatherThanThrowing() {
        store.putString("state_json", "{not json")
        assertEquals(1, registry().orderedSeeds().size, "the default addon is seeded again over an unread snapshot")
    }

    @Test
    fun theRegistryRemembersWhatItWroteThroughAFreshInstance() {
        registry().reconcileCloudAddons(listOf(CloudAddonRow("https://kept.example/manifest.json", 0)))
        val reread = registry().orderedSeeds().map { it.manifestUrl }
        assertTrue("https://kept.example/manifest.json" in reread, "state_json survived: $reread")
    }

    /** The `KeyValueStore` slot is what replaced `SharedPreferences`, so its two calls are the contract. */
    @Test
    fun exactlyOneKeyIsWrittenAndItIsTheState() {
        registry().orderedSeeds()
        assertEquals(setOf("state_json"), store.keys(), "the registry has one persisted value and one name for it")
    }

    private class MemoryKeyValueStore : KeyValueStore {
        private val values = mutableMapOf<String, Any>()
        override fun getString(key: String, defaultValue: String?): String? = values[key] as? String ?: defaultValue
        override fun putString(key: String, value: String) { values[key] = value }
        override fun getBoolean(key: String, defaultValue: Boolean): Boolean = values[key] as? Boolean ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { values[key] = value }
        override fun getInt(key: String, defaultValue: Int): Int = values[key] as? Int ?: defaultValue
        override fun putInt(key: String, value: Int) { values[key] = value }
        override fun getFloat(key: String, defaultValue: Float): Float = values[key] as? Float ?: defaultValue
        override fun putFloat(key: String, value: Float) { values[key] = value }
        override fun contains(key: String): Boolean = values.containsKey(key)
        override fun keys(): Set<String> = values.keys.toSet()
        override fun remove(key: String) { values.remove(key) }
        override fun clear() = values.clear()
    }
}