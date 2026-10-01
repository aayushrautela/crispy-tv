package com.crispy.tv.details.trailer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Two pure functions, 27 lines, and zero tests — pinned.
 *
 * ## Why this file exists
 *
 * `:android:network` had no test source set. Its `androidMain` is four OkHttp /
 * `Context` adapters that are unreachable from any `commonMain` forever (OkHttp
 * publishes no Kotlin/Native artifact), so the *only* testable surface in the module
 * is these two files — and nothing asserted either of them. That is the shape of a
 * module whose `androidMain` looks untestable and whose `commonMain` is not.
 *
 * ## The two functions do not agree about what a YouTube link is
 *
 * `classifyTrailerSource` matches exactly two literals, `youtube.com/watch` and
 * `youtu.be/`. `extractYouTubeVideoId` matches four shapes through a regex, adding
 * `/shorts/` and `/embed/`. So **a shorts or embed link is classified `DIRECT` while
 * the extractor will happily pull an id out of it**, and the two cases below pin both
 * halves of that. Which of the two is right is a question about how providers hand
 * over trailer links; what the code does is not in doubt, and until now nothing in the
 * repository said so.
 *
 * ## The extractor's fallback is not a failure signal
 *
 * `extractYouTubeVideoId` returns `null` for **exactly one** input shape: the empty or
 * blank string. Every other input returns a non-null value — including a value that
 * contains no "youtu" at all, and including a YouTube URL whose id is too short for
 * the regex, which comes back as the whole URL. `noNonBlankInputReturnsNull` is the
 * property that makes this explicit, and it is load-bearing for two callers:
 *
 * * `android/app/.../details/DetailsScreen.kt:572` writes
 *   `key = extractYouTubeVideoId(id) ?: id`, and the `?: id` arm is reachable only
 *   when `id` is blank — the call site already trims and blank-checks upstream.
 * * `android/youtube-extractor/.../YouTubeTrailerExtractor.kt:41` writes
 *   `val key = extractYouTubeVideoId(videoId) ?: return null`, which reads as
 *   "give up when this is not a YouTube id". It does not: it gives up only on a blank
 *   id, and otherwise builds `https://www.youtube.com/watch?v=$key` and asks
 *   NewPipe about whatever the caller passed. **That is a caller-shape consequence of
 *   a pure function's return contract, and it is invisible from either file alone**,
 *   which is why the property case is here rather than a note on the extractor.
 */
class TrailerSourceTest {

    // ----------------------------------------------------------------- classifyTrailerSource

    @Test
    fun aWatchUrlIsYouTube() {
        assertEquals(TrailerSource.YOUTUBE, classifyTrailerSource("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
    }

    @Test
    fun aShortYouTubeUrlIsYouTube() {
        assertEquals(TrailerSource.YOUTUBE, classifyTrailerSource("https://youtu.be/dQw4w9WgXcQ"))
    }

    @Test
    fun classificationIgnoresCase() {
        // `classifyTrailerSource` lowercases the whole URL once and matches on that, so
        // the host is compared case-insensitively without an `ignoreCase` flag.
        assertEquals(TrailerSource.YOUTUBE, classifyTrailerSource("HTTPS://WWW.YOUTUBE.COM/WATCH?v=dQw4w9WgXcQ"))
        assertEquals(TrailerSource.YOUTUBE, classifyTrailerSource("https://YOUTU.BE/dQw4w9WgXcQ"))
    }

    @Test
    fun aShortsUrlIsNotYouTube() {
        // The first half of the asymmetry, and the half a reader believes is wrong.
        // `classifyTrailerSource` has no `/shorts/` alternative, so a shorts link is
        // `DIRECT` — and the extractor below still reads an id out of the very same URL.
        assertEquals(TrailerSource.DIRECT, classifyTrailerSource("https://www.youtube.com/shorts/dQw4w9WgXcQ"))
    }

    @Test
    fun anEmbedUrlIsNotYouTube() {
        assertEquals(TrailerSource.DIRECT, classifyTrailerSource("https://www.youtube.com/embed/dQw4w9WgXcQ"))
    }

    @Test
    fun aBareIdIsNotYouTube() {
        // The other asymmetry, and this one is deliberate on the caller's side:
        // `DetailsScreen` builds its fallback branch with a hard-coded
        // `TrailerSource.YOUTUBE` and never calls `classifyTrailerSource` for a bare
        // key. So the answer here is only ever reached for a URL, and it is still
        // worth pinning because a reader would assume a bare id classifies as YouTube.
        assertEquals(TrailerSource.DIRECT, classifyTrailerSource("dQw4w9WgXcQ"))
    }

    @Test
    fun theMarkerIsTheHostAndPathRatherThanTheWordYouTube() {
        // A page that merely mentions YouTube is not a YouTube link. Both halves of the
        // disjunction are full literals, so a URL carrying the word without one of them
        // is `DIRECT` — and this is the half that is easy to get right by accident.
        assertEquals(TrailerSource.DIRECT, classifyTrailerSource("https://example.com/youtube/watch"))
        assertEquals(TrailerSource.DIRECT, classifyTrailerSource("https://www.youtube.com/feed/subscriptions"))
    }

    @Test
    fun theMarkerIsASubstringWithNoHostBoundarySoAnyHostEndingInItCounts() {
        // The finding, and it is the opposite of what the case above implies: the test
        // is `contains`, with no host boundary and no URL parsing, so
        // `notyoutube.com/watch` *contains* `youtube.com/watch` and classifies as
        // YOUTUBE. A host that merely ends in `youtube` — a mirror, a fan site, a typo
        // — is indistinguishable from YouTube here.
        //
        // The first draft of this file asserted the intuitive answer and failed. What
        // makes it worth a case rather than a deleted assertion is that the name of the
        // thing being tested and the behaviour of the thing being tested disagree, and
        // the disagreement is invisible until the substring is written out.
        assertEquals(TrailerSource.YOUTUBE, classifyTrailerSource("https://notyoutube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals(TrailerSource.YOUTUBE, classifyTrailerSource("https://myyoutube.com/watch?v=dQw4w9WgXcQ"))
    }

    @Test
    fun theShortLinkMarkerNeedsItsSlash() {
        // `"youtu.be/"` with a trailing slash, so a bare host mentions nothing.
        assertEquals(TrailerSource.DIRECT, classifyTrailerSource("https://youtu.be"))
        assertEquals(TrailerSource.DIRECT, classifyTrailerSource("https://www.youtube.com"))
    }

    @Test
    fun anEmptyOrUnrelatedUrlIsDirect() {
        assertEquals(TrailerSource.DIRECT, classifyTrailerSource(""))
        assertEquals(TrailerSource.DIRECT, classifyTrailerSource("   "))
        assertEquals(TrailerSource.DIRECT, classifyTrailerSource("https://example.com/trailer.mp4"))
    }

    @Test
    fun aBareHostWithNoSchemeStillClassifies() {
        // No scheme parsing at all — the match is a substring, so a protocol-relative
        // or scheme-less form classifies the same way. Worth pinning because it is a
        // consequence of not parsing rather than a rule anyone wrote down.
        assertEquals(TrailerSource.YOUTUBE, classifyTrailerSource("www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals(TrailerSource.YOUTUBE, classifyTrailerSource("youtube.com/watch?v=dQw4w9WgXcQ"))
    }

    // ------------------------------------------------------------ extractYouTubeVideoId

    @Test
    fun theGateIsCaseInsensitiveEvenThoughEveryAlternativeInThePatternIsNot() {
        // The one case that makes the gate's `ignoreCase` observable, and it took a
        // second attempt to find. The obvious fixture — an uppercase `YOUTU.BE/` link —
        // does **not** discriminate: the gate passes, the lowercase-only `youtu\.be/`
        // alternative then fails, and the `?: trimmed` fallback returns the whole URL,
        // which is *also* what a case-sensitive gate returns. Both answers are the
        // string, so the mutation survives and the guard looks redundant.
        //
        // The fixture that discriminates is an uppercase host carrying a **lowercase**
        // parameter: the gate decides whether the lowercase alternative is ever
        // reached, and only there does the two answers part company. This is the
        // redundant-guard finding from the other side — the gate is not redundant, it
        // is *masked*, and masking is indistinguishable from absence until the input
        // that reaches the guard you meant and fails only there is written down.
        assertEquals("abcdefghijk", extractYouTubeVideoId("https://YOUTU.com/embed/abcdefghijk"))
        assertEquals("abcdefghijk", extractYouTubeVideoId("https://YOUTU.com/watch?v=abcdefghijk"))
    }

    @Test
    fun anEmptyValueIsTheOnlyNull() {
        assertNull(extractYouTubeVideoId(""))
        assertNull(extractYouTubeVideoId("   "))
        assertNull(extractYouTubeVideoId("\t\n "))
    }

    @Test
    fun noNonBlankInputReturnsNull() {
        // The property behind the caller's `?: return null`, and the reason that line is
        // not the guard it reads as. Every entry is an input a caller could plausibly
        // hand the extractor; if any of them returned null, `YouTubeTrailerExtractor`
        // would behave differently for it, so the set is asserted rather than sampled.
        val nonBlank = listOf(
            "dQw4w9WgXcQ",
            "not a url at all",
            "https://example.com/trailer.mp4",
            "https://youtu.be/abc",
            "https://www.youtube.com/shorts/dQw4w9WgXcQ",
            "https://www.youtube.com/embed/dQw4w9WgXcQ",
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "HTTPS://YOUTU.BE/dQw4w9WgXcQ",
            "https://example.com/?v=zzzzzzzz&ref=youtu.be",
            "x",
        )
        for (value in nonBlank) {
            assertNotNull(extractYouTubeVideoId(value), "'$value' should not return null")
        }
    }

    @Test
    fun aBareIdComesBackUnchanged() {
        assertEquals("dQw4w9WgXcQ", extractYouTubeVideoId("dQw4w9WgXcQ"))
    }

    @Test
    fun aBareIdIsTrimmedFirst() {
        assertEquals("dQw4w9WgXcQ", extractYouTubeVideoId("  dQw4w9WgXcQ  "))
    }

    @Test
    fun aValueWithNoYouTubeInItComesBackWhole() {
        // Not null, and not an id: the whole trimmed value. A caller cannot tell this
        // apart from "this URL is itself the id", which is the ambiguity the class
        // KDoc describes and the reason `YouTubeTrailerExtractor` needs its own
        // `contains("youtu")` knowledge.
        assertEquals("https://example.com/trailer.mp4", extractYouTubeVideoId("https://example.com/trailer.mp4"))
        assertEquals("some random text", extractYouTubeVideoId("  some random text  "))
    }

    @Test
    fun aQueryParameterIsNotAnIdWithoutYouTubeInTheUrl() {
        // The gate is the substring `"youtu"` anywhere, and this URL has a `v=` but no
        // "youtu", so the regex is never reached and the `v` is not read. The mirror of
        // `anUnrelatedQueryParameterIsReadAsTheId` below, and the pair is what shows the
        // gate is doing the deciding rather than the regex.
        assertEquals(
            "https://example.com/watch?v=dQw4w9WgXcQ",
            extractYouTubeVideoId("https://example.com/watch?v=dQw4w9WgXcQ"),
        )
    }

    @Test
    fun everyYouTubeLinkShapeYieldsItsId() {
        // All four regex alternatives, on the id length the `{6,}` bound admits.
        assertEquals("dQw4w9WgXcQ", extractYouTubeVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", extractYouTubeVideoId("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", extractYouTubeVideoId("https://www.youtube.com/shorts/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", extractYouTubeVideoId("https://www.youtube.com/embed/dQw4w9WgXcQ"))
    }

    @Test
    fun theQueryParameterFormIsReadWithOrWithoutTheAmpersand() {
        // `[?&]v=` — the `&` form is what a watch URL with a playlist in front carries,
        // and the `?` form is the plain one. **Both halves belong in the case that
        // names them.** This body originally asserted only the `&` form while its name
        // claimed both, and a mutation dropping `?` from the class was caught — but by
        // three *other* cases (`everyYouTubeLinkShapeYieldsItsId` and two more), not by
        // this one, because the rule it names was not actually written down here. A
        // caught mutation is evidence that *some* test caught it, not that the case you
        // would have named is the one doing the work; when the failure list does not
        // contain your `expect` set, read the cases that did fail before concluding the
        // suite is fine.
        assertEquals(
            "dQw4w9WgXcQ",
            extractYouTubeVideoId("https://www.youtube.com/watch?list=PL123&v=dQw4w9WgXcQ"),
        )
        assertEquals(
            "dQw4w9WgXcQ",
            extractYouTubeVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ"),
        )
    }

    @Test
    fun trailingQueryParametersAreNotPartOfTheId() {
        // The id character class is `[A-Za-z0-9_-]`, so a `?si=` tracking parameter ends
        // it rather than being swallowed.
        assertEquals("dQw4w9WgXcQ", extractYouTubeVideoId("https://youtu.be/dQw4w9WgXcQ?si=abcdef"))
    }

    @Test
    fun anIdShorterThanSixCharactersIsNotAnId() {
        // The `{6,}` bound, pinned on both sides because only one of them is an obvious
        // thing to write. The five-character case is the surprising one: the function
        // named `extractYouTubeVideoId` returns the **whole URL**, so a caller that
        // trusts a short id gets a URL handed to `watch?v=`.
        assertEquals("abcde", extractYouTubeVideoId("abcde"))
        assertEquals(
            "https://youtu.be/abcde",
            extractYouTubeVideoId("https://youtu.be/abcde"),
            "too short for the bound, so the trimmed value is the fallback",
        )
        assertEquals("abcdef", extractYouTubeVideoId("abcdef"))
        assertEquals("abc123", extractYouTubeVideoId("https://youtu.be/abc123"))
    }

    @Test
    fun anUnderscoreOrHyphenIsPartOfTheId() {
        // `[A-Za-z0-9_-]` rather than `[A-Za-z0-9]`, so the punctuation real ids carry
        // is not truncated. Six characters of them is enough to clear the bound.
        assertEquals("a_b-c-d", extractYouTubeVideoId("https://youtu.be/a_b-c-d"))
    }

    @Test
    fun theExtractionIsCaseSensitiveEvenThoughTheGateIsNot() {
        // The asymmetry inside one function. The gate is
        // `contains("youtu", ignoreCase = true)`, so an uppercase short link *passes*
        // the gate — and the regex is then applied to the **unlowered** value, where
        // `youtu\.be/` cannot match `YOUTU.BE/`. The result is the whole URL.
        // `classifyTrailerSource` lowercases first and gets this right, so the two
        // functions disagree about the same input in opposite directions.
        assertEquals(
            "https://YOUTU.BE/dQw4w9WgXcQ",
            extractYouTubeVideoId("https://YOUTU.BE/dQw4w9WgXcQ"),
        )
        assertEquals(
            TrailerSource.YOUTUBE,
            classifyTrailerSource("https://YOUTU.BE/dQw4w9WgXcQ"),
            "the classifier agrees with itself, not with the extractor",
        )
    }

    @Test
    fun anUnrelatedQueryParameterIsReadAsTheIdWhenTheUrlMentionsYouTube() {
        // `find` takes the **first** match anywhere in the string, so a `v=` belonging
        // to some other parameter wins over the real id later in the URL. Only the
        // presence of "youtu" anywhere put the regex in play at all.
        assertEquals(
            "zzzzzzzz",
            extractYouTubeVideoId("https://example.com/?v=zzzzzzzz&ref=youtu.be/dQw4w9WgXcQ"),
        )
    }

    @Test
    fun aValueThatMentionsYouTubeButMatchesNothingComesBackWhole() {
        // The final fallback, and the one that makes the `?:` on the call site
        // unreachable: the gate passed, the regex found nothing, and the trimmed value
        // is returned rather than `null`.
        assertEquals(
            "https://example.com/watch-youtube-clips",
            extractYouTubeVideoId("https://example.com/watch-youtube-clips"),
        )
    }

    @Test
    fun theExtractedIdIsUsedWhenItIsTheWholeValue() {
        // The success path stated on its own, so a mutation that made the bare-id case
        // work by accident would still be caught here: a plain id is not "extracted",
        // it is passed through, and both routes must agree.
        assertEquals(extractYouTubeVideoId("dQw4w9WgXcQ"), extractYouTubeVideoId("https://youtu.be/dQw4w9WgXcQ"))
    }
}
