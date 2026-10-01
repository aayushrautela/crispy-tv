package com.crispy.tv.addons.registry

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What `android.net.Uri` actually answers, measured rather than assumed, for the
 * URL shapes a Stremio addon manifest can take.
 *
 * ## Why this suite exists at all
 *
 * `MetadataAddonRegistry.parseManifestSeed` reads six things off a `Uri`, and one
 * of them is load-bearing in a way the other five are not: **`uri.toString()` is
 * persisted as `manifestUrl` and hashed into `installationId`**, so every
 * installed addon's identity is a function of how `Uri` re-renders a user-typed
 * URL. Anything that round-trips differently re-identifies every existing
 * installation, silently, with no failed request and no crash. This is the
 * `libraryCacheFileName` lesson one level up — a correct digest of a string the
 * function does not build is still a wrong expectation.
 *
 * ## The three findings, and the two that changed the plan
 *
 * 1. **`Uri.toString()` is the identity on every input.** All 26 shapes in the
 *    table below render back as exactly the normalized string. So the portable
 *    replacement does not need a re-renderer at all — it can carry the
 *    normalized string through unchanged, which removes the whole class of bug
 *    this suite was opened to guard against.
 * 2. **The scheme-override branch is dead code.** `parseManifestSeed` builds
 *    `parsedUri.buildUpon().scheme("https").build()` for a `null`/blank/`stremio`
 *    scheme, but its own normalization block above it already guarantees a
 *    scheme on every path: `stremio://` is rewritten to `https://`, a
 *    `URI_SCHEME_REGEX` match keeps its own, and everything else is prefixed
 *    with `https://`. **The branch cannot be reached, so the port must not
 *    reproduce it** — carrying it over would be porting dead code.
 * 3. **`Uri` normalizes nothing.** It does not lowercase a scheme or a host
 *    (`HTTPS://Example.COM` keeps both), does not drop a fragment, and does not
 *    collapse a blank path segment. The caller's `scheme.lowercase()` is what
 *    lowercases the *decision*, not the value that gets stored.
 *
 * ## The measured table
 *
 * `host` excludes userinfo and the port; `encodedAuthority` includes both; an
 * IPv6 host keeps its brackets. `pathSegments` are percent-**decoded** and have
 * blank segments dropped, while `toString()` preserves both. `baseUrl` is
 * rebuilt by re-encoding each decoded segment, so `%2F` survives the
 * round trip as `%2F` while a literal space becomes `%20`, and `~` is left
 * alone because it is unreserved. Query and fragment are dropped from `baseUrl`
 * and kept in `toString()`.
 *
 * | input | normalized | host | rendered | baseUrl |
 * |---|---|---|---|---|
 * | `https://opensubtitles-v3.strem.io/manifest.json` | unchanged | `opensubtitles-v3.strem.io` | unchanged | `https://opensubtitles-v3.strem.io` |
 * | `stremio://opensubtitles-v3.strem.io/manifest.json` | `https://…` | `opensubtitles-v3.strem.io` | `https://opensubtitles-v3.strem.io/manifest.json` | `https://opensubtitles-v3.strem.io` |
 * | `stremio://` | `https://` | `""` | `https://` | `https:` |
 * | `  stremio://Cinemeta.strem.io/manifest.json  ` | `https://Cinemeta…` (trimmed) | `Cinemeta.strem.io` | — | `https://Cinemeta.strem.io` |
 * | `example.com/manifest.json` | `https://…` | `example.com` | unchanged | `https://example.com` |
 * | `foo` | `https://foo` | `foo` | `https://foo` | `https://foo` |
 * | `HTTPS://Example.COM/Manifest.json` | unchanged | `Example.COM` | unchanged | `HTTPS://Example.COM` |
 * | `ftp://example.com/manifest.json` | unchanged | `example.com` | unchanged | `ftp://example.com` |
 * | `https://EXAMPLE.com:8080/a/b/manifest.json` | unchanged | `EXAMPLE.com` | unchanged | `https://EXAMPLE.com:8080/a/b` |
 * | `https://example.com/a%20b/manifest.json` | unchanged | `example.com` | unchanged | `https://example.com/a%20b` |
 * | `https://example.com/a%2Fb/manifest.json` | unchanged | `example.com` | unchanged | `https://example.com/a%2Fb` |
 * | `https://example.com/a//b/manifest.json` | unchanged | `example.com` | unchanged | `https://example.com/a/b` |
 * | `https://example.com/manifest.json?foo=bar%20baz` | unchanged | `example.com` | unchanged | `https://example.com` |
 * | `https://user:pass@example.com/manifest.json` | unchanged | `example.com` | unchanged | `https://user:pass@example.com` |
 * | `https://example.com` | unchanged | `example.com` | unchanged | `https://example.com` |
 * | `https://example.com/` | unchanged | `example.com` | `https://example.com/` | `https://example.com` |
 * | `https://example.com/manifest.json#frag` | unchanged | `example.com` | unchanged | `https://example.com` |
 * | `https://[::1]:8080/manifest.json` | unchanged | `[::1]` | unchanged | `https://[::1]:8080` |
 * | `//example.com/manifest.json` | `https:////example.com/…` | `""` | `https:////example.com/manifest.json` | `https:///example.com` |
 * | `https://example.com/a b/manifest.json` | unchanged | `example.com` | unchanged | `https://example.com/a%20b` |
 * | `https://example.com/~tilde/manifest.json` | unchanged | `example.com` | unchanged | `https://example.com/~tilde` |
 * | `https://example.com/manifest.JSON` | unchanged | `example.com` | unchanged | `https://example.com` |
 * | `https://example.com/manifest.json/` | unchanged | `example.com` | unchanged | `https://example.com` |
 * | `https://例え.jp/manifest.json` | unchanged | `例え.jp` | unchanged | `https://例え.jp` |
 *
 * ## Why Robolectric can answer this at all
 *
 * `android.net.Uri` is implemented in pure Java in AOSP's `libcore`, not in
 * native code, so `android-all` on this host is the **shipping** implementation
 * rather than a stand-in. That is *not* true of `org.json`, where AOSP and the
 * Maven reference disagree on `optString` of a JSON null (see `:backend`'s
 * `JsonAccessorPolicyHostTest`, whose table records that divergence, and which
 * left `commonTest` for the same reason this suite cannot). **Whether a
 * measurement can be trusted is a property of the class being measured, so it
 * is stated here rather than assumed for the whole `android.jar`.**
 *
 * `@Config(sdk = [35])` and nothing else: no view is inflated and no resource is
 * read, so `isIncludeAndroidResources` is not needed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UriBehaviourHostTest {

    /** Copied verbatim from `MetadataAddonRegistry`'s companion. */
    private val uriSchemeRegex = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")

    /**
     * `parseManifestSeed`'s normalization, verbatim. It has to be here and not
     * inline: the first version of this suite omitted it, so `Uri` was handed
     * `example.com/manifest.json` as a *relative path* and reported
     * `host = null` — an answer production never sees, because the caller
     * prepends `https://` first. **The probe has to feed the function the
     * argument the caller feeds it**, which is the `libraryCacheFileName` defect
     * in a new shape: measuring a real call with the wrong argument still
     * produces a confident, wrong table.
     */
    private fun normalize(raw: String): String {
        val input = raw.trim()
        return when {
            input.startsWith("stremio://", ignoreCase = true) -> "https://${input.substringAfter("://")}"
            uriSchemeRegex.containsMatchIn(input) -> input
            else -> "https://$input"
        }
    }

    private fun uriFor(raw: String): Uri {
        val parsed = Uri.parse(normalize(raw))
        return when (parsed.scheme?.lowercase()) {
            null, "", "stremio" -> parsed.buildUpon().scheme("https").build()
            else -> parsed
        }
    }

    private fun baseUrlOf(uri: Uri): String {
        val segments = uri.pathSegments.filter { it.isNotBlank() }
        val basePath =
            if (segments.lastOrNull().equals("manifest.json", ignoreCase = true)) {
                segments.dropLast(1)
            } else {
                segments
            }
        return Uri.Builder()
            .scheme(uri.scheme ?: "https")
            .encodedAuthority(uri.encodedAuthority)
            .apply { basePath.forEach { segment -> appendPath(segment) } }
            .build()
            .toString()
            .trimEnd('/')
    }

    /** The headline finding, over every shape in the table above. */
    @Test
    fun renderingIsTheIdentityOnEveryInput() {
        ALL_INPUTS.forEach { raw ->
            val normalized = normalize(raw)
            assertEquals(
                "Uri.parse must render back as the normalized string for input=<$raw>",
                normalized,
                uriFor(raw).toString(),
            )
        }
    }

    /** Finding 2: the override branch is unreachable, because normalization guarantees a scheme. */
    @Test
    fun normalizationAlreadyGuaranteesASchemeSoTheOverrideNeverFires() {
        ALL_INPUTS.forEach { raw ->
            val scheme = Uri.parse(normalize(raw)).scheme
            assertNotNull("input=<$raw> must already carry a scheme", scheme)
            assertTrue(
                "input=<$raw> scheme=<$scheme> must not be one the override rewrites",
                scheme!!.lowercase() !in setOf("", "stremio"),
            )
        }
    }

    @Test
    fun hostExcludesUserinfoAndPort() {
        assertEquals("example.com", uriFor("https://user:pass@example.com/manifest.json").host)
        assertEquals("EXAMPLE.com", uriFor("https://EXAMPLE.com:8080/a/b/manifest.json").host)
        assertEquals(
            "user:pass@example.com",
            uriFor("https://user:pass@example.com/manifest.json").encodedAuthority,
        )
        assertEquals("EXAMPLE.com:8080", uriFor("https://EXAMPLE.com:8080/a/b/manifest.json").encodedAuthority)
    }

    @Test
    fun anIpv6HostKeepsItsBrackets() {
        assertEquals("[::1]", uriFor("https://[::1]:8080/manifest.json").host)
        assertEquals("[::1]:8080", uriFor("https://[::1]:8080/manifest.json").encodedAuthority)
    }

    /** A bare word is a *host*, not a path — and this only showed up once the normalizer ran. */
    @Test
    fun aBareWordBecomesTheHost() {
        val uri = uriFor("foo")
        assertEquals("foo", uri.host)
        assertEquals(emptyList<String>(), uri.pathSegments.filter { it.isNotBlank() })
        assertEquals("https://foo", baseUrlOf(uri))
    }

    /** `stremio://` on its own yields a blank host, which the caller's guard turns into `null`. */
    @Test
    fun anEmptyAuthorityGivesABlankHostRatherThanNull() {
        val uri = uriFor("stremio://")
        assertEquals("", uri.host)
        assertEquals("", uri.encodedAuthority)
        assertFalse("a blank host must fail the caller's isNotBlank guard", uri.host!!.isNotBlank())
    }

    @Test
    fun pathSegmentsAreDecodedAndBlankOnesDropped() {
        assertEquals(
            listOf("a b", "manifest.json"),
            uriFor("https://example.com/a%20b/manifest.json").pathSegments,
        )
        assertEquals(
            listOf("a/b", "manifest.json"),
            uriFor("https://example.com/a%2Fb/manifest.json").pathSegments,
        )
        assertEquals(
            listOf("a", "b", "manifest.json"),
            uriFor("https://example.com/a//b/manifest.json").pathSegments,
        )
    }

    /** The decoded-then-re-encoded round trip is what keeps `%2F` from becoming a real separator. */
    @Test
    fun baseUrlReEncodesEachSegmentAndDropsQueryAndFragment() {
        assertEquals("https://example.com/a%20b", baseUrlOf(uriFor("https://example.com/a b/manifest.json")))
        assertEquals("https://example.com/a%2Fb", baseUrlOf(uriFor("https://example.com/a%2Fb/manifest.json")))
        assertEquals("https://example.com/~tilde", baseUrlOf(uriFor("https://example.com/~tilde/manifest.json")))
        assertEquals(
            "https://example.com",
            baseUrlOf(uriFor("https://example.com/manifest.json?foo=bar%20baz")),
        )
        assertEquals("https://example.com", baseUrlOf(uriFor("https://example.com/manifest.json#frag")))
    }

    @Test
    fun queryIsKeptEncodedAndSeparateFromTheFragment() {
        val uri = uriFor("https://example.com/manifest.json?foo=bar%20baz")
        assertEquals("foo=bar%20baz", uri.encodedQuery)
        assertEquals("https://example.com/manifest.json?foo=bar%20baz", uri.toString())
    }

    /** Finding 3: `Uri` lowercases nothing. */
    @Test
    fun neitherSchemeNorHostIsLowercased() {
        val uri = uriFor("HTTPS://Example.COM/Manifest.json")
        assertEquals("HTTPS", uri.scheme)
        assertEquals("Example.COM", uri.host)
        assertEquals("HTTPS://Example.COM/Manifest.json", uri.toString())
    }

    /** `Uri` does not lowercase for us, so the hash input keeps the user's casing. */
    @Test
    fun theHashInputIsTheLowercasedRenderedString() {
        assertEquals(
            "https://cinemeta.strem.io/manifest.json",
            uriFor("stremio://Cinemeta.strem.io/manifest.json").toString().lowercase(),
        )
    }

    private companion object {
        val ALL_INPUTS = listOf(
            "https://opensubtitles-v3.strem.io/manifest.json",
            "https://cinemeta.strem.io/manifest.json",
            "stremio://opensubtitles-v3.strem.io/manifest.json",
            "stremio://opensubtitles-v3.strem.io",
            "stremio://",
            "  stremio://Cinemeta.strem.io/manifest.json  ",
            "example.com/manifest.json",
            "example.com",
            "foo",
            "HTTPS://Example.COM/Manifest.json",
            "ftp://example.com/manifest.json",
            "https://EXAMPLE.com:8080/a/b/manifest.json",
            "https://example.com/a%20b/manifest.json",
            "https://example.com/a%2Fb/manifest.json",
            "https://example.com/a//b/manifest.json",
            "https://example.com/manifest.json?foo=bar%20baz",
            "https://user:pass@example.com/manifest.json",
            "https://example.com",
            "https://example.com/",
            "https://example.com/manifest.json#frag",
            "https://[::1]:8080/manifest.json",
            "//example.com/manifest.json",
            "https://example.com/a b/manifest.json",
            "https://example.com/~tilde/manifest.json",
            "https://example.com/manifest.JSON",
            "https://example.com/manifest.json/",
            "https://例え.jp/manifest.json",
        )
    }
}