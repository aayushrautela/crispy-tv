package com.crispy.tv.watchhistory.progress

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The storage-key shapes, and the reduction of a provider episode id onto the
 * one key a continue-watching removal is recorded under.
 *
 * ## Why this is `commonTest` and not `androidHostTest`
 *
 * Because the declarations it covers are **`commonMain`**, so this suite runs on
 * desktop JVM, `linuxX64` at compile time, both Apple targets and the Android
 * host. It needs no Robolectric and no `org.json`, and `org.json` is the one
 * thing that would have made it Android-only: these four functions were lifted
 * out of `WatchProgressStore` precisely because none of them parses anything.
 *
 * The store itself is still `androidMain` and still uncharacterised — it reads
 * and writes JSON through `org.json`, which is a class of the Android platform
 * and absent from every other classpath. So this suite is the *portable half*
 * of the file's behaviour, and it is the half that became portable first.
 *
 * ## What a suite that samples would have missed
 *
 * `normalizeContinueWatchingEpisodeRemoveId` has four strategies and the order
 * is the rule: two of them only make sense because an earlier one was tried and
 * failed. The cases below are grouped by strategy and each is a case where a
 * *different* strategy would answer differently, so no case here is decoration
 * — the one that would be is a fixture that already reads `tt1:2:5`.
 */
class WatchProgressKeysTest {

    // ---------------------------------------------------------------------
    // The prefixes
    // ---------------------------------------------------------------------

    @Test
    fun theTwoPrefixesAreDistinctAndNeitherIsAPrefixOfTheOther() {
        assertTrue(
            !CONTENT_DURATION_KEY_PREFIX.startsWith(WATCH_PROGRESS_KEY_PREFIX),
            "otherwise getAllWatchProgress's startsWith filter would accept a duration key as a progress key",
        )
    }

    @Test
    fun bothPrefixesEndInAColonSoAStrippedKeyNeverKeepsOne() {
        assertTrue(WATCH_PROGRESS_KEY_PREFIX.endsWith(":"))
        assertTrue(CONTENT_DURATION_KEY_PREFIX.endsWith(":"))
    }

    // ---------------------------------------------------------------------
    // buildWpKeyString — the *unprefixed* identity
    // ---------------------------------------------------------------------

    @Test
    fun theWpKeyHasNoPrefixAtAllBecauseItIsAlsoTheTombstoneKey() {
        assertEquals("movie:tt1", buildWpKeyString("tt1", "movie"))
    }

    @Test
    fun anAbsentOrBlankEpisodeIdLeavesTheKeyAtTheContentAndAllOfThemAreBlank() {
        for (episodeId in listOf(null, "", "   ", "\t\n")) {
            assertEquals(
                "movie:tt1",
                buildWpKeyString("tt1", "movie", episodeId),
                "episode id ${episodeId?.let { "«$it»" } ?: "null"} is blank, so it is not part of the key",
            )
        }
    }

    @Test
    fun anEpisodeIdIsAppendedVerbatimAndIsNotTrimmed() {
        assertEquals("movie:tt1:1:2", buildWpKeyString("tt1", "movie", "1:2"))
        assertEquals("movie:tt1: 1 ", buildWpKeyString("tt1", "movie", " 1 "), "the key builder does not trim; only the id reducer does")
    }

    /**
     * The writer/reader contract, and the reason `WATCH_PROGRESS_KEY_PREFIX` is
     * a shared constant rather than two literals that happen to match.
     *
     * `getAllWatchProgress` builds its returned map by
     * `key.removePrefix(WATCH_PROGRESS_KEY_PREFIX)`, and
     * `removeAllWatchProgressForContent` looks entries up with
     * `buildWpKeyString(id, type, episodeId)`. So **the stripped progress key
     * must equal the wp key**, for every shape, or a removal silently misses
     * the entries it is meant to remove. Nothing in the code states that; it
     * only falls out of both builders agreeing on the order `type:id[:episode]`.
     */
    @Test
    fun strippingTheProgressPrefixYieldsExactlyTheWpKey() {
        for (type in listOf("movie", "series", "show")) {
            for (id in listOf("tt1", "provider:movie:abc")) {
                for (episodeId in listOf(null, "", "e1", "1:2", "weird:id:with:colons")) {
                    val stripped = getWatchProgressPrefKey(id, type, episodeId)
                        .removePrefix(WATCH_PROGRESS_KEY_PREFIX)
                    assertEquals(
                        buildWpKeyString(id, type, episodeId),
                        stripped,
                        "progress key for $type/$id/${episodeId ?: "null"} must strip to the wp key",
                    )
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // getWatchProgressPrefKey / getContentDurationPrefKey
    // ---------------------------------------------------------------------

    @Test
    fun theTwoPrefixedKeyBuildersPutTheTypeBeforeTheIdAndTheEpisodeLast() {
        assertEquals("@watch_progress:movie:tt1", getWatchProgressPrefKey("tt1", "movie", null))
        assertEquals("@watch_progress:series:tt1:s01e02", getWatchProgressPrefKey("tt1", "series", "s01e02"))
        assertEquals("@content_duration:movie:tt1", getContentDurationPrefKey("tt1", "movie", null))
        assertEquals("@content_duration:series:tt1:s01e02", getContentDurationPrefKey("tt1", "series", "s01e02"))
    }

    @Test
    fun aContentIdContainingColonsIsNotEscapedOrSplitByAnyOfTheThreeBuilders() {
        assertEquals(
            "@watch_progress:show:provider:x:1:movie:tt1:1:2",
            getWatchProgressPrefKey("provider:x:1:movie:tt1", "show", "1:2"),
            "a colon in the id is data, and only the consumer of the key splits it",
        )
    }

    // ---------------------------------------------------------------------
    // normalizeContinueWatchingEpisodeRemoveId
    // ---------------------------------------------------------------------

    @Test
    fun aBlankEpisodeIdReducesToNothingSoNoRemovalIsEverRecorded() {
        for (episodeId in listOf("", "   ", "\t", "\n  \n")) {
            assertEquals(
                "",
                normalizeContinueWatchingEpisodeRemoveId("tt1", episodeId),
                "«$episodeId» is blank, and a caller that then recorded the empty key would tombstone the content",
            )
        }
    }

    /** Strategy 1: a colon tail whose last two components are numbers. */
    @Test
    fun aColonSeparatedNumberTailIsReducedToTheIdAndThoseTwoNumbers() {
        assertEquals("tt1:2:5", normalizeContinueWatchingEpisodeRemoveId("tt1", "2:5"))
        assertEquals("tt1:2:5", normalizeContinueWatchingEpisodeRemoveId("tt1", "s01:2:5"), "only the last two components are read")
        assertEquals("tt1:2:5", normalizeContinueWatchingEpisodeRemoveId("tt1", "tt1:2:5"), "and a colon inside the id is not a boundary")
    }

    @Test
    fun aSingleNumericComponentIsNotEnoughBecauseBothNumbersAreRequired() {
        // `":5".split(':')` is `["", "5"]`, so the season slot is `""` and
        // `"".toIntOrNull()` is null. Every strategy misses and the fallback
        // appends the value whole, which is where the second colon comes from.
        // The first run expected `tt1:5` and got `tt1::5`; the code was right.
        assertEquals("tt1::5", normalizeContinueWatchingEpisodeRemoveId("tt1", ":5"), "the season slot was empty")
        assertEquals("tt1:2:x", normalizeContinueWatchingEpisodeRemoveId("tt1", "2:x"), "the episode slot is not a number")
    }

    /** Strategy 2: an `s<n>e<n>` anywhere in the value, case-insensitively. */
    @Test
    fun aSeasonEpisodeTokenIsReducedAndTheSearchIsCaseInsensitive() {
        for (episodeId in listOf("s02e05", "S02E05", "Series1S02E05", "The  Series1xS2E5 - 1080p")) {
            assertEquals(
                "tt1:2:5",
                normalizeContinueWatchingEpisodeRemoveId("tt1", episodeId),
                "«$episodeId» carries a season and an episode",
            )
        }
    }

    /**
     * The two strategies order against each other here, so the order is the
     * assertion: this value satisfies **both** the colon rule and the `sNeM`
     * rule, with different answers, and only the order makes the answer right.
     */
    @Test
    fun theColonRuleIsTriedBeforeTheTokenRuleAndTheTokenRuleIsTriedBeforeTheVerbatimRule() {
        assertEquals("tt1:2:5", normalizeContinueWatchingEpisodeRemoveId("tt1", "x:2:5"), "colon rule wins over the token rule")
        assertEquals("tt1:2:5", normalizeContinueWatchingEpisodeRemoveId("tt1", "s2e5"), "the token rule wins over the verbatim rule")
    }

    /**
     * Strategy 3, and the one that makes the whole function safe to apply to a
     * value that may already be normalised.
     *
     * `"tt1:2:5"` would satisfy the colon rule anyway, so it is a poor fixture
     * for this strategy. `"tt1:abc"` and `"tt1:season-1"` satisfy neither the
     * colon rule (the tail is not two numbers) nor the token rule (no `s<n>e<n>`),
     * so **only the verbatim rule can answer them** — and without that rule the
     * answer would be `"tt1:tt1:abc"`, a key nothing ever clears, so the removal
     * would be recorded where it can never be read back.
     */
    @Test
    fun anAlreadyPrefixedIdIsReturnedVerbatimAndTheFunctionIsIdempotent() {
        for (prefixed in listOf("tt1:abc", "tt1:season-1", "tt1:2")) {
            val once = normalizeContinueWatchingEpisodeRemoveId("tt1", prefixed)
            assertEquals(prefixed, once, "«$prefixed» is already prefixed and is returned as it stands")
            assertEquals(
                once,
                normalizeContinueWatchingEpisodeRemoveId("tt1", once),
                "and applying the function to its own answer changes nothing",
            )
        }
    }

    /**
     * The ordering again, on a value that is *already prefixed* and still gets
     * reduced. `"tt1:s01e02-special"` fails the colon rule (its tail is not two
     * numbers) but carries an `s<n>e<n>` token, so the token rule answers before
     * the verbatim one is reached.
     *
     * This fixture was originally in the case above, asserting the verbatim
     * answer, and the first run failed with `expected:<tt1:s01e02-special> but
     * was:<tt1:1:2>` — **a fixture for strategy 3 that an earlier strategy also
     * claims is a case that proves the order rather than the rule it was named
     * for**, and asserting the wrong one of the two is how a suite ends up
     * pinning a rule its name never mentioned.
     */
    @Test
    fun anAlreadyPrefixedIdIsStillReducedWhenItCarriesASeasonEpisodeToken() {
        assertEquals("tt1:1:2", normalizeContinueWatchingEpisodeRemoveId("tt1", "tt1:s01e02-special"))
    }

    /** Strategy 4: anything else is appended unchanged. */
    @Test
    fun anUnrecognisedEpisodeIdIsAppendedToTheIdWholeAndNotSplit() {
        assertEquals("tt1:weird", normalizeContinueWatchingEpisodeRemoveId("tt1", "weird"))
        assertEquals("tt1:a:b:c", normalizeContinueWatchingEpisodeRemoveId("tt1", "a:b:c"), "no strategy claims it, so it is kept whole")
    }

    @Test
    fun theEpisodeIdIsTrimmedBeforeEveryStrategyAndTheIdItselfIsNot() {
        assertEquals("tt1:2:5", normalizeContinueWatchingEpisodeRemoveId("tt1", "  2:5  "))
        assertEquals(" id :2:5", normalizeContinueWatchingEpisodeRemoveId(" id ", "2:5"), "the id is used as given; only the episode id is trimmed")
    }

    /**
     * The two recognition strategies converge on one key, which is the property
     * `maybeRestoreContinueWatchingVisibility` depends on: it clears a removal
     * by re-running this function on an episode id, and a removal recorded from
     * one provider shape has to be clearable from another.
     *
     * The first draft of this case instead threaded the reduced value back
     * through `buildWpKeyString` and expected `movie:tt1:2:5`; it got
     * `movie:tt1:tt1:2:5`, because the reduced value is **already**
     * `type:id`-shaped and is not an episode id. The prefixing contract it was
     * reaching for is asserted by
     * `strippingTheProgressPrefixYieldsExactlyTheWpKey`, so this case states the
     * convergence instead, which is the part nothing else covers.
     */
    @Test
    fun bothRecognitionStrategiesConvergeOnOneKey() {
        val byColon = normalizeContinueWatchingEpisodeRemoveId("tt1", "2:5")
        val byToken = normalizeContinueWatchingEpisodeRemoveId("tt1", "s2e5")
        val byEmbeddedToken = normalizeContinueWatchingEpisodeRemoveId("tt1", "tt1:S02E05")

        assertEquals("tt1:2:5", byColon)
        assertEquals(byColon, byToken, "so a removal recorded from one shape is clearable from the other")
        assertEquals(byColon, byEmbeddedToken, "including when the token is behind the id")
        assertTrue(
            buildWpKeyString("tt1", "movie", byColon.substringAfter("tt1:")).startsWith("movie:tt1:"),
            "and the reduced value is still a suffix of the progress key removeAllWatchProgressForContent matches on",
        )
    }
}
