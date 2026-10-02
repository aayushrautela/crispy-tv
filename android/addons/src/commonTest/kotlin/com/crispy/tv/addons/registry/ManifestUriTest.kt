package com.crispy.tv.addons.registry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * `ManifestUri` against the table `UriBehaviourHostTest` measured on the shipping
 * `android.net.Uri`.
 *
 * **This suite and that one are the same specification in two places**, and the
 * duplication is deliberate: that suite runs on Robolectric because `android.net.Uri`
 * cannot be named from `commonTest`, and it could only assert the *inputs it fed*
 * `Uri`. Every shape below is one of its 26 rows, with its answer transcribed —
 * so a change in `Uri`'s behaviour would not break this suite, and a change in
 * `ManifestUri` will.
 *
 * The assertions are **one per property, named after the input**, rather than a
 * single `assertEquals(EXPECTED, report)` over the whole table: the 26-row form
 * produced a 12 KB `ComparisonFailure` whose actual difference was invisible,
 * and the diff reader was then misled twice -- once by the literal text
 * `expected:<` occurring *inside* a failure message, and once by JUnit hoisting
 * the common prefix outside the brackets.
 */
class ManifestUriTest {

    /** Copied verbatim from `MetadataAddonRegistry`'s companion. */
    private val uriSchemeRegex = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")

    /**
     * `parseManifestSeed`'s normalization, verbatim -- see `UriBehaviourHostTest`
     * for why the probe has to run it rather than hand `Uri` a raw string.
     */
    private fun normalize(raw: String): String {
        val input = raw.trim()
        return when {
            input.startsWith("stremio://", ignoreCase = true) -> "https://${input.substringAfter("://")}"
            uriSchemeRegex.containsMatchIn(input) -> input
            else -> "https://$input"
        }
    }

    /**
     * The table's `baseUrl` column is the value `parseManifestSeed` stores, and it
     * is **not** [ManifestUri.baseUrl]: production first drops a trailing
     * `manifest.json` segment and only then rebuilds. So the helper mirrors that
     * two-step decision rather than calling `baseUrl()` and expecting the drop --
     * which was the first version of this suite, and it failed nineteen cases for
     * one reason: `baseUrl()` does exactly what it says and the *drop* is the
     * caller's. [baseUrlKeepsTheManifestSegment] pins that division.
     */
    private fun productionBaseUrl(uri: ManifestUri): String {
        val segments = uri.pathSegments
        val basePath =
            if (segments.lastOrNull().equals("manifest.json", ignoreCase = true)) segments.dropLast(1) else segments
        return uri.baseUrlFor(basePath)
    }

    /**
     * One row of the table. A blank [host] means `Uri.host` was `null` or empty,
     * which is the answer `parseManifestSeed` turns into a `null` seed.
     *
     * [pathSegments] has **no default**, which the first version of this suite gave
     * it -- and thirteen cases then failed at once with
     * `expected:<[]> but was:<[manifest.json]>`. The default asserted that every
     * row's path is empty, and only the four rows written out disagreed with it.
     * **A defaulted expected value is a fixture that agrees with every case it was
     * not given to**, and thirteen identical failures is the cost of not noticing.
     */
    private fun row(
        raw: String,
        host: String,
        baseUrl: String,
        pathSegments: List<String>,
        encodedQuery: String? = null,
    ) {
        val normalized = normalize(raw)
        val uri = ManifestUri.parse(normalized)
        if (host.isEmpty()) {
            assertNull(uri, "expected no host, so no seed, for <$raw> normalized to <$normalized>")
            return
        }
        val parsed = assertNotNull(uri, "expected a parse of <$normalized> for <$raw>")
        assertEquals(normalized, parsed.toString(), "toString is the identity for <$raw>")
        assertEquals(host, parsed.host, "host for <$raw>")
        assertEquals(baseUrl, productionBaseUrl(parsed), "the stored baseUrl for <$raw>")
        assertEquals(pathSegments, parsed.pathSegments, "pathSegments for <$raw>")
        assertEquals(encodedQuery, parsed.encodedQuery, "encodedQuery for <$raw>")
    }

    // --- the 26 measured shapes -----------------------------------------------------------------

    @Test
    fun plainHttpsManifest() {
        row(
            raw = "https://opensubtitles-v3.strem.io/manifest.json",
            host = "opensubtitles-v3.strem.io",
            baseUrl = "https://opensubtitles-v3.strem.io",
            pathSegments = listOf("manifest.json"),
        )
    }

    @Test
    fun stremioSchemeIsRewrittenToHttpsBeforeParsing() {
        row(
            raw = "stremio://opensubtitles-v3.strem.io/manifest.json",
            host = "opensubtitles-v3.strem.io",
            baseUrl = "https://opensubtitles-v3.strem.io",
            pathSegments = listOf("manifest.json"),
        )
    }

    @Test
    fun aStremioPrefixWithNoHostHasNoSeed() {
        row(raw = "stremio://", host = "", baseUrl = "", pathSegments = emptyList())
    }

    @Test
    fun surroundingWhitespaceIsTrimmedAndTheHostKeepsItsCase() {
        row(
            raw = "  stremio://Cinemeta.strem.io/manifest.json  ",
            host = "Cinemeta.strem.io",
            baseUrl = "https://Cinemeta.strem.io",
            pathSegments = listOf("manifest.json"),
        )
    }

    @Test
    fun aBareHostAndPathGainsTheDefaultScheme() {
        row(
            raw = "example.com/manifest.json",
            host = "example.com",
            baseUrl = "https://example.com",
            pathSegments = listOf("manifest.json"),
        )
    }

    @Test
    fun aBareWordIsAHostNotARelativePath() {
        row(raw = "foo", host = "foo", baseUrl = "https://foo", pathSegments = emptyList())
    }

    @Test
    fun neitherTheSchemeNorTheHostIsLowercased() {
        row(
            raw = "HTTPS://Example.COM/Manifest.json",
            host = "Example.COM",
            baseUrl = "HTTPS://Example.COM",
            pathSegments = listOf("Manifest.json"),
        )
    }

    @Test
    fun aNonHttpSchemeIsCarriedThroughRatherThanReplaced() {
        row(
            raw = "ftp://example.com/manifest.json",
            host = "example.com",
            baseUrl = "ftp://example.com",
            pathSegments = listOf("manifest.json"),
        )
    }

    @Test
    fun thePortStaysInTheAuthorityAndOutOfTheHost() {
        row(
            raw = "https://EXAMPLE.com:8080/a/b/manifest.json",
            host = "EXAMPLE.com",
            baseUrl = "https://EXAMPLE.com:8080/a/b",
            pathSegments = listOf("a", "b", "manifest.json"),
        )
    }

    @Test
    fun anEncodedSpaceIsDecodedThenReEncodedAsTheSameEscape() {
        row(
            raw = "https://example.com/a%20b/manifest.json",
            host = "example.com",
            baseUrl = "https://example.com/a%20b",
            pathSegments = listOf("a b", "manifest.json"),
        )
    }

    @Test
    fun anEncodedSlashIsDecodedToACharacterAndReEncodedAsAnEscapeNotASeparator() {
        row(
            raw = "https://example.com/a%2Fb/manifest.json",
            host = "example.com",
            baseUrl = "https://example.com/a%2Fb",
            pathSegments = listOf("a/b", "manifest.json"),
        )
    }

    @Test
    fun aLiteralSpaceIsDecodedAsItselfThenEncoded() {
        row(
            raw = "https://example.com/a b/manifest.json",
            host = "example.com",
            baseUrl = "https://example.com/a%20b",
            pathSegments = listOf("a b", "manifest.json"),
        )
    }

    @Test
    fun aTildeIsUnreservedSoItSurvivesEncodingUnchanged() {
        row(
            raw = "https://example.com/~tilde/manifest.json",
            host = "example.com",
            baseUrl = "https://example.com/~tilde",
            pathSegments = listOf("~tilde", "manifest.json"),
        )
    }

    @Test
    fun blankSegmentsAreDroppedFromTheListAndKeptInTheString() {
        row(
            raw = "https://example.com/a//b/manifest.json",
            host = "example.com",
            baseUrl = "https://example.com/a/b",
            pathSegments = listOf("a", "b", "manifest.json"),
        )
    }

    @Test
    fun theQueryIsKeptBesideTheBaseUrlAndNotFoldedIntoIt() {
        row(
            raw = "https://example.com/manifest.json?foo=bar%20baz",
            host = "example.com",
            baseUrl = "https://example.com",
            pathSegments = listOf("manifest.json"),
            encodedQuery = "foo=bar%20baz",
        )
    }

    @Test
    fun userInfoIsInTheAuthorityAndOutOfTheHost() {
        row(
            raw = "https://user:pass@example.com/manifest.json",
            host = "example.com",
            baseUrl = "https://user:pass@example.com",
            pathSegments = listOf("manifest.json"),
        )
    }

    @Test
    fun anAuthorityWithNoPathHasABaseUrlWithNoTrailingSlash() {
        row(raw = "https://example.com", host = "example.com", baseUrl = "https://example.com", pathSegments = emptyList())
    }

    @Test
    fun aPathOfOneBlankSegmentIsDroppedFromTheBaseUrl() {
        row(
            raw = "https://example.com/",
            host = "example.com",
            baseUrl = "https://example.com",
            pathSegments = emptyList(),
        )
    }

    @Test
    fun aFragmentIsKeptInTheStringAndDroppedFromTheBaseUrl() {
        row(
            raw = "https://example.com/manifest.json#frag",
            host = "example.com",
            baseUrl = "https://example.com",
            pathSegments = listOf("manifest.json"),
        )
    }

    @Test
    fun anIpv6HostKeepsItsBracketsInBothTheHostAndTheAuthority() {
        row(
            raw = "https://[::1]:8080/manifest.json",
            host = "[::1]",
            baseUrl = "https://[::1]:8080",
            pathSegments = listOf("manifest.json"),
        )
    }

    @Test
    fun aProtocolRelativeUrlNormalizesToFourSlashesAndHasNoHost() {
        row(raw = "//example.com/manifest.json", host = "", baseUrl = "", pathSegments = emptyList())
    }

    @Test
    fun theManifestSegmentIsMatchedCaseInsensitivelyAndDropped() {
        row(
            raw = "https://example.com/manifest.JSON",
            host = "example.com",
            baseUrl = "https://example.com",
            pathSegments = listOf("manifest.JSON"),
        )
    }

    @Test
    fun aManifestSegmentFollowedByABlankSegmentIsStillDropped() {
        row(
            raw = "https://example.com/manifest.json/",
            host = "example.com",
            baseUrl = "https://example.com",
            pathSegments = listOf("manifest.json"),
        )
    }

    @Test
    fun aNonAsciiHostIsCarriedThroughUnchanged() {
        row(
            raw = "https://例え.jp/manifest.json",
            host = "例え.jp",
            baseUrl = "https://例え.jp",
            pathSegments = listOf("manifest.json"),
        )
    }

    // --- the invariant `parse` relies on, and the codec under it -----------------------------------

    /**
     * Every branch of the normalization emits a `://`, which is the whole reason
     * `parse` can require one. This is the row that would break first if a branch
     * were added that did not.
     */
    @Test
    fun everyNormalizingBranchEmitsASchemeDelimiter() {
        listOf(
            "stremio://a/b",
            "https://a/b",
            "ftp://a/b",
            "a/b",
            "foo",
            "",
        ).forEach { raw ->
            assertEquals(
                true,
                normalize(raw).contains("://"),
                "normalized <$raw> as <${normalize(raw)}>, which parse would reject",
            )
        }
    }

    @Test
    fun aStringWithNoSchemeDelimiterHasNoParse() {
        assertNull(ManifestUri.parse("example.com/manifest.json"), "a relative path is not a manifest URL")
        assertNull(ManifestUri.parse("mailto:someone@example.com"), "a scheme with no authority is not one either")
    }

    @Test
    fun anEmptyAuthorityHasNoParse() {
        assertNull(ManifestUri.parse("https:///manifest.json"), "the host is what parseManifestSeed requires")
        assertNull(ManifestUri.parse("https://"), "and a scheme on its own is not enough")
    }

    @Test
    fun consecutiveEscapesAreDecodedAsOneUtf8RunNotAsLatin1Characters() {
        assertEquals("é", percentDecode("%C3%A9"), "two bytes of one character must not become two characters")
        assertEquals("é", percentDecode("%c3%a9"), "lower-case hex digits decode the same")
    }

    /**
     * `Uri` is lenient with a malformed escape, so a strict decoder would turn a
     * manifest URL that used to work into an exception instead of a seed.
     */
    @Test
    fun anEscapeThatIsNotTwoHexDigitsStaysLiteral() {
        assertEquals("%ZZ", percentDecode("%ZZ"), "not hex digits")
        assertEquals("100%", percentDecode("100%"), "a trailing percent with nothing after it")
        assertEquals("a%2", percentDecode("a%2"), "a truncated escape")
    }

    @Test
    fun encodingUsesUpperCaseHexAndTheUnreservedSet() {
        assertEquals("a%20b", percentEncodeSegment("a b"))
        assertEquals("a%2Fb", percentEncodeSegment("a/b"))
        assertEquals("%C3%A9", percentEncodeSegment("é"), "multi-byte characters are encoded per byte")
        assertEquals("aZ0-._~", percentEncodeSegment("aZ0-._~"), "unreserved characters pass through")
        assertEquals("", percentEncodeSegment(""), "an empty segment stays empty rather than becoming %00")
    }

    /**
     * `baseUrl()` drops nothing: which segment is the manifest is the caller's
     * decision, and `productionBaseUrl` above is where it is made. Asserting it
     * keeps the two from being confused in the other direction.
     */
    @Test
    fun baseUrlKeepsTheManifestSegmentBecauseDroppingItIsTheCallersDecision() {
        val uri = assertNotNull(ManifestUri.parse("https://example.com/a/b/manifest.json"))
        assertEquals("https://example.com/a/b/manifest.json", uri.baseUrl(), "baseUrl() is the whole path")
        assertEquals("https://example.com/a/b", uri.baseUrlFor(uri.pathSegments.dropLast(1)), "and the caller drops")
    }

    /** The codec is only worth having if decode and encode are inverses. */
    @Test
    fun decodingThenEncodingReturnsTheSegment() {
        listOf("plain", "a b", "a/b", "~tilde", "é", "100%", "%ZZ", "already%20encoded", "a?b#c")
            .forEach { segment ->
                val once = percentDecode(segment)
                assertEquals(once, percentDecode(percentEncodeSegment(once)), "round trip of <$segment>")
            }
    }

    /**
     * `equals` is on `raw`, so a URI parsed twice from the same string is the same
     * value -- which is what lets `installationId` be a function of the URL.
     */
    @Test
    fun twoParsesOfTheSameStringAreEqual() {
        val first = ManifestUri.parse("https://example.com/manifest.json")
        val second = ManifestUri.parse("https://example.com/manifest.json")
        assertEquals(first!!.host, second!!.host, "host is a value carried through, not re-derived")
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertEquals(false, first == ManifestUri.parse("https://other.example/manifest.json"))
    }
}