package com.crispy.tv.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The four add-on url helpers, which used to read `android.net.Uri` and now read
 * `:addons`' [com.crispy.tv.addons.registry.ManifestUri].
 *
 * This file is the half of the port's specification that `ManifestUriTest` cannot be:
 * that suite covers the shared type against `Uri` on 26 url shapes, and this one covers
 * the four *decisions* `:app` layers on top of it -- which segment is the manifest,
 * which is the base, and when an asset url is already absolute. Splitting them that way
 * is why widening `ManifestUri` to `public` was widening a specification rather than
 * duplicating one.
 *
 * Two of the cases below exist to pin behaviour that is now *deliberately absent*, which
 * is the harder half of a port to keep honest: a rewrite is free to reintroduce a branch
 * it deleted, and nothing fails when it does.
 */
class AddonsSettingsUrlTest {
    // ---- addonIdFromUrl ---------------------------------------------------------

    @Test
    fun theHostIsTheIdForTheUrlShapesAddonsActuallyUse() {
        val cases =
            mapOf(
                "https://opensubtitles-v3.strem.io/manifest.json" to "opensubtitles-v3.strem.io",
                "https://v3-cinemeta.strem.io/manifest.json" to "v3-cinemeta.strem.io",
                "https://addon.example.co.uk/a/b/manifest.json" to "addon.example.co.uk",
            )
        for ((url, expected) in cases) {
            assertEquals(expected, addonIdFromUrl(url), "id for $url")
        }
    }

    /**
     * The behaviour difference the port introduces, stated rather than hidden.
     *
     * `Uri.parse` never returns null and treats a bare word as a host, so the old
     * `Uri.parse(url).host?.trim().orEmpty()` answered `"foo"` with `"foo"` -- it adopted
     * the whole string as the id. `ManifestUri.parse` requires `://` and a non-blank host,
     * so a malformed manifest url now answers the empty string.
     *
     * That is the better answer *here* and the reason it is worth a case rather than a
     * note: the value being asked for is an id, and the input is not one.
     */
    @Test
    fun aBareWordIsNotAnIdBecauseUriUsedToTreatItAsAHost() {
        assertEquals("", addonIdFromUrl("foo"))
        assertEquals("", addonIdFromUrl(""))
        assertEquals("", addonIdFromUrl("not a url"))
    }

    // ---- normalizeManifestUrl ---------------------------------------------------

    @Test
    fun aUrlThatAlreadyNamesTheManifestIsNotGivenASecondOne() {
        val url = "https://v3-cinemeta.strem.io/manifest.json"
        assertEquals(url, normalizeManifestUrl(url))
        assertEquals(url, normalizeManifestUrl("  $url  "))
        // Case-insensitively: the last segment is compared with `equals(ignoreCase)`, so
        // `MANIFEST.JSON` is the manifest and must not be doubled.
        assertEquals("https://v3-cinemeta.strem.io/MANIFEST.JSON",
            normalizeManifestUrl("https://v3-cinemeta.strem.io/MANIFEST.JSON"))
    }

    @Test
    fun aUrlWithoutTheManifestGetsItAppended() {
        assertEquals(
            "https://opensubtitles-v3.strem.io/manifest.json",
            normalizeManifestUrl("https://opensubtitles-v3.strem.io"),
        )
        assertEquals(
            "https://addon.example.com/deep/path/manifest.json",
            normalizeManifestUrl("https://addon.example.com/deep/path"),
        )
    }

    @Test
    fun aSchemeLessInputGetsHttpsRatherThanBeingRejected() {
        assertEquals(
            "https://addon.example.com/manifest.json",
            normalizeManifestUrl("addon.example.com"),
        )
    }

    /**
     * `stremio://` becomes `https://` -- and this is also the case that proves the dead
     * branch stayed dead. The old code rebuilt the uri through a
     * `when (scheme) { null, "", "stremio" -> buildUpon().scheme("https") }` arm. The
     * `stremio` arm could never fire, because the branch above it has already rewritten
     * every `stremio://` before the scheme is read. This case reaches the same answer
     * through the surviving branch.
     */
    @Test
    fun stremioSchemeIsRewrittenToHttpsByTheFirstBranchNotTheDeadOne() {
        assertEquals(
            "https://addon.example.com/manifest.json",
            normalizeManifestUrl("stremio://addon.example.com"),
        )
        assertEquals(
            "https://addon.example.com/manifest.json",
            normalizeManifestUrl("STREMIO://addon.example.com"),
        )
    }

    @Test
    fun aBlankInputIsNoAnswerRatherThanAnEmptyUrl() {
        assertNull(normalizeManifestUrl(""))
        assertNull(normalizeManifestUrl("   "))
    }

    /**
     * A url with no host has no manifest to sit next to. The old code rejected it at
     * `uri.host?.takeIf { it.isNotBlank() } ?: return null`; `ManifestUri.parse` rejects it
     * instead, and `?: return null` here is that same rejection surfacing as a decision.
     */
    @Test
    fun aUrlWithoutAHostIsNoAnswer() {
        assertNull(normalizeManifestUrl("https:///manifest.json"))
    }

    @Test
    fun aQueryIsCarriedOntoTheNormalizedUrl() {
        assertEquals(
            "https://addon.example.com/manifest.json?lang=en",
            normalizeManifestUrl("https://addon.example.com?lang=en"),
        )
    }

    // ---- addonBaseUrl -----------------------------------------------------------

    @Test
    fun theBaseIsTheDirectoryTheManifestSitsIn() {
        assertEquals(
            "https://addon.example.com/deep",
            addonBaseUrl("https://addon.example.com/deep/manifest.json"),
        )
        assertEquals(
            "https://addon.example.com",
            addonBaseUrl("https://addon.example.com/manifest.json"),
        )
    }

    /**
     * The other documented difference. A url `ManifestUri` will not parse used to reach
     * `Uri.Builder` with a null scheme and a null authority, which emitted
     * `scheme:/...`-shaped nonsense. It now comes back as the input, trimmed. What the
     * answer is *for* is the thing that decides this is better: the only uses of the
     * result are joining and trimming, so a mangled version of the input was never useful.
     */
    @Test
    fun aUrlThatWillNotParseComesBackAsItselfRatherThanAsSchemeColonSlash() {
        assertEquals("not a url", addonBaseUrl("not a url"))
        assertEquals("foo", addonBaseUrl("foo/"))
    }

    // ---- resolveAddonAssetUrl ---------------------------------------------------

    /**
     * The reason the absolute check is a regex and not `ManifestUri.parse`. A `data:` or
     * `mailto:` asset names a scheme but carries no `://`, so a parser that requires
     * `://` would decline it and the asset would be joined onto the base url -- turning an
     * absolute reference into a path under the add-on's manifest.
     */
    @Test
    fun anAssetThatNamesItsOwnSchemeIsLeftAloneEvenWithoutASlashSlash() {
        assertEquals(
            "data:image/png;base64,AAAA",
            resolveAddonAssetUrl("https://addon.example.com/dir", "data:image/png;base64,AAAA"),
        )
        assertEquals(
            "mailto:someone@example.com",
            resolveAddonAssetUrl("https://addon.example.com/dir", "mailto:someone@example.com"),
        )
        assertEquals(
            "https://cdn.example.com/logo.png",
            resolveAddonAssetUrl("https://addon.example.com/dir", "https://cdn.example.com/logo.png"),
        )
    }

    @Test
    fun aRootedAssetKeepsTheBaseAuthorityAndSwapsThePath() {
        assertEquals(
            "https://addon.example.com/logo.png",
            resolveAddonAssetUrl("https://addon.example.com/dir/manifest.json", "/logo.png"),
        )
    }

    /**
     * Protocol-relative: the asset names the host but not the scheme, so the scheme comes
     * from the base. This is the case a naive "does it look absolute" check gets wrong in
     * the *other* direction, by treating `//` as a scheme marker.
     */
    @Test
    fun aProtocolRelativeAssetTakesItsSchemeFromTheBase() {
        // `http`, not `https`: the scheme comes from the base, and that is the whole
        // point of this arm. Written from the shape of the case above it -- which uses an
        // `https` base -- this expectation was `https` and failed, which is the cheapest
        // possible reminder that a fixture written from its neighbours is a guess.
        assertEquals(
            "http://cdn.example.com/logo.png",
            resolveAddonAssetUrl("http://addon.example.com/dir", "//cdn.example.com/logo.png"),
        )
        assertEquals(
            "https://cdn.example.com/logo.png",
            resolveAddonAssetUrl("https://addon.example.com/dir", "//cdn.example.com/logo.png"),
        )
    }

    @Test
    fun aBareAssetIsJoinedOntoTheTrimmedBase() {
        assertEquals(
            "https://addon.example.com/dir/logo.png",
            resolveAddonAssetUrl("https://addon.example.com/dir/", "logo.png"),
        )
        assertEquals(
            "https://addon.example.com/dir/logo.png",
            resolveAddonAssetUrl("https://addon.example.com/dir", "  logo.png  "),
        )
    }

    @Test
    fun aBlankAssetIsNoAnswer() {
        assertNull(resolveAddonAssetUrl("https://addon.example.com/dir", null))
        assertNull(resolveAddonAssetUrl("https://addon.example.com/dir", ""))
        assertNull(resolveAddonAssetUrl("https://addon.example.com/dir", "   "))
    }

    // ---- the two extracted segment decisions ------------------------------------

    @Test
    fun manifestSegmentsAppendsUnlessTheLastAlreadyNamesIt() {
        assertEquals(listOf("a", "manifest.json"), manifestSegments(listOf("a")))
        // The three already-name-it cases, which are the ones a doubled segment would
        // break and the first of which is the reason the comparison is `ignoreCase`.
        assertEquals(listOf("a", "manifest.json"), manifestSegments(listOf("a", "manifest.json")))
        assertEquals(listOf("a", "MANIFEST.JSON"), manifestSegments(listOf("a", "MANIFEST.JSON")))
        // A `manifest.json` that is *not* last is not the manifest segment either, so a
        // later segment is appended rather than treated as the tail -- the rule is about
        // the last segment, not about the segment appearing anywhere.
        assertEquals(
            listOf("manifest.json", "other", "manifest.json"),
            manifestSegments(listOf("manifest.json", "other")),
        )
        // An empty path is the "user pasted just the host" case, and it still gets the
        // manifest: that is the whole point of the decision.
        assertEquals(listOf("manifest.json"), manifestSegments(emptyList()))
    }

    @Test
    fun baseSegmentsDropsTheManifestAndLeavesAnythingElseAlone() {
        assertEquals(listOf("a"), baseSegments(listOf("a", "manifest.json")))
        assertEquals(listOf("a"), baseSegments(listOf("a", "MANIFEST.JSON")))
        assertEquals(listOf("a"), baseSegments(listOf("a")))
        assertEquals(emptyList(), baseSegments(listOf("manifest.json")))
        assertEquals(emptyList(), baseSegments(emptyList()))
    }

    /**
     * The two are not inverses of each other and the pair is what proves it: a path with
     * no manifest round trips to itself under `manifestSegments` and to itself under
     * `baseSegments`, and one with a manifest round trips under neither in the same way.
     * A single `withManifest(present: Boolean)` covering both would pass every case above
     * too -- which is why they are kept as two named functions and tested as two.
     */
    @Test
    fun theTwoSegmentRulesAreIndependentRatherThanOneRuleWithAFlag() {
        val withManifest = listOf("a", "manifest.json")
        assertEquals(withManifest, manifestSegments(withManifest))
        assertEquals(listOf("a"), baseSegments(withManifest))
        assertTrue(manifestSegments(withManifest) != baseSegments(withManifest))
    }

    // ---- manifestUrlsMatch (unchanged by the port, pinned so it stays that way) ----

    @Test
    fun manifestUrlsMatchIgnoresCaseAndSurroundingSpace() {
        assertTrue(manifestUrlsMatch("https://a.example/m", " https://A.example/m "))
        assertTrue(!manifestUrlsMatch("https://a.example/m", "https://b.example/m"))
    }
}