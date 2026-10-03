package com.crispy.tv.ui.navigation

import com.crispy.tv.details.RuntimeDetailsEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * [runtimeDetailsEntryOrNull] used to be a seven-line `takeIf` written inline inside
 * the details block of `addHomeNavGraph`, behind a `LocalContext` read, so it had no
 * name and no test. It is here for the second reason too: it is a *decision*, and the
 * platform side of the route-argument reader decides nothing at all.
 *
 * The cases below are the three shapes the strings can arrive in -- a real number, a
 * blank, a non-number -- crossed with the three numbers, because the rule is about the
 * *combination* rather than about any one field.
 */
class RuntimeDetailsEntryOrNullTest {

    @Test
    fun noArgumentAtAllIsNoEntryRatherThanAnEntryOfThreeNulls() {
        // The distinction the screen branches on: an entry carrying three nulls is a
        // different answer from no entry, so it cannot be answered by a sentinel.
        assertNull(runtimeDetailsEntryOrNull(null, null, null))
    }

    @Test
    fun threeBlankStringsAreNoEntryBecauseNoneOfThemIsANumber() {
        // `Bundle` hands back "" for a blank argument, and the argument declarations
        // default three of these four to "". So "" is the *common* input here, not an
        // edge case, and it has to be dropped rather than parsed into anything.
        assertNull(runtimeDetailsEntryOrNull("", "", ""))
    }

    @Test
    fun aSingleReportedNumberIsEnoughToKeepTheEntry() {
        val seasonOnly = assertNotNull(runtimeDetailsEntryOrNull("3", "", ""))
        assertEquals(RuntimeDetailsEntry(seasonNumber = 3), seasonOnly)

        val episodeOnly = assertNotNull(runtimeDetailsEntryOrNull(null, "7", null))
        assertEquals(RuntimeDetailsEntry(episodeNumber = 7), episodeOnly)

        val absoluteOnly = assertNotNull(runtimeDetailsEntryOrNull(null, null, "42"))
        assertEquals(RuntimeDetailsEntry(absoluteEpisodeNumber = 42), absoluteOnly)
    }

    @Test
    fun aNegativeNumberIsKeptRatherThanTreatedAsAbsent() {
        // `toIntOrNull()` answers -1 for "-1", and this function must not re-decide
        // that a negative season is missing: a sentinel here would be indistinguishable
        // from the real thing the rule was written to avoid.
        assertEquals(
            RuntimeDetailsEntry(seasonNumber = -1),
            assertNotNull(runtimeDetailsEntryOrNull("-1", "", "")),
        )
    }

    @Test
    fun aZeroIsKeptBecauseZeroIsANumber() {
        // Season 0 and episode 0 exist in every show with a special, and dropping them
        // would lose the special.
        assertEquals(
            RuntimeDetailsEntry(seasonNumber = 0, episodeNumber = 0),
            assertNotNull(runtimeDetailsEntryOrNull("0", "0", "")),
        )
    }

    @Test
    fun anUnparseableNumberIsDroppedRatherThanBecomingZero() {
        // The failure mode this rule exists to prevent: `""`.toIntOrNull() is null, and
        // a parser that answered 0 instead would hand the screen a season it never got.
        assertNull(runtimeDetailsEntryOrNull("abc", "", ""))
        assertNull(runtimeDetailsEntryOrNull(" 3", "", ""), "a leading space is not a number")
        assertNull(runtimeDetailsEntryOrNull("3.5", "", ""), "a fractional season is not a season")
    }

    @Test
    fun oneGoodNumberSurvivesBesideTwoUnparseableOnes() {
        // The rule is about the combination: one reported number keeps the entry even
        // when the other two are junk.
        assertEquals(
            RuntimeDetailsEntry(absoluteEpisodeNumber = 42),
            assertNotNull(runtimeDetailsEntryOrNull("nope", "", "42")),
        )
    }

    @Test
    fun everyOneOfTheEightCombinationsOfReportedOrNotIsAnsweredExplicitly() {
        // A sweep rather than a sample, because the rule is a three-way `or` and the
        // interesting case is the one a hand-picked list leaves out: all three reported
        // together.
        val reported = listOf<String?>("1", null)
        for (season in reported) {
            for (episode in reported) {
                for (absolute in reported) {
                    val entry = runtimeDetailsEntryOrNull(season, episode, absolute)
                    val anyReported = season != null || episode != null || absolute != null
                    assertEquals(
                        anyReported,
                        entry != null,
                        "season=$season episode=$episode absolute=$absolute",
                    )
                    if (entry != null) {
                        assertEquals(
                            listOfNotNull(
                                season?.toIntOrNull(),
                                episode?.toIntOrNull(),
                                absolute?.toIntOrNull(),
                            ).size,
                            listOfNotNull(
                                entry.seasonNumber,
                                entry.episodeNumber,
                                entry.absoluteEpisodeNumber,
                            ).size,
                            "season=$season episode=$episode absolute=$absolute",
                        )
                    }
                }
            }
        }
    }
}