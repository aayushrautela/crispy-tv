package com.crispy.tv.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText

import androidx.compose.ui.test.v2.runComposeUiTest
import com.crispy.tv.domain.watch.ContinueWatchingCandidate
import com.crispy.tv.domain.watch.ContinueWatchingPlanItem
import com.crispy.tv.domain.watch.planContinueWatching
import com.crispy.tv.watchhistory.ContinueWatchingRail
import com.crispy.tv.watchhistory.describe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The seam proof, as a test.
 *
 * A previous ordering moved 31,000 lines of Compose first and deferred the first
 * off-Android render, so a structurally wrong seam stayed undiscovered for a long
 * time. This test is what makes that failure mode impossible here: it renders real Compose
 * Multiplatform UI on a desktop JVM, with no emulator, no device and no display,
 * so a broken seam fails in seconds on the developer's own machine.
 *
 * It is deliberately a semantic assertion rather than a golden image. A Skia
 * raster differs by Skia version and font availability, so a desktop golden would
 * either be flaky or need a pinned container. What is actually worth proving is
 * that the design system composes, that the planner's real output reaches the
 * screen, and that the responsive spacing helper resolves off-Android. There
 * is deliberately no image-comparison gate: rendering regressions are caught
 * by review, not by pixels.
 *
 * What it renders is `ContinueWatchingRail` from `:android:app`'s `commonMain`,
 * reached through `:app`'s `desktop` JVM variant. This module holds the window and
 * the fixture seed and no presentation of its own, so a regression in the rail
 * fails here as well as in `:app`'s own suites -- and it is the first test in the
 * repository that compiles `:app` code for a target that is not Android.
 */
class ContinueWatchingRailTest {

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun rendersEveryItemThePlannerProduced() {
        val items = planContinueWatching(
            candidates = listOf(
                ContinueWatchingCandidate("series", "tt0903747", "s1e1", 42.0, NOW_MS - 1_000),
                ContinueWatchingCandidate("movie", "tt0111161", null, 61.5, NOW_MS - 500),
            ),
            nowMs = NOW_MS,
        )
        assertEquals(2, items.size, "fixture setup should produce two items")

        runComposeUiTest {
            setContent { ContinueWatchingRail(items = items) }

            onNodeWithText("Continue watching").assertIsDisplayed()
            assertAllItemsDisplayed(items)
        }
    }

    /**
     * The planner's own contract, end to end through the screen.
     *
     * This is what makes the module a seam proof rather than a demo: it starts
     * from a real contract fixture on disk, runs the real planner that
     * `:android:core-domain`'s contract suite pins against the same fixture, and
     * requires the screen to show exactly what that fixture's `expected.items`
     * says. If the planner regressed this fails here and in the contract suite --
     * not silently in a screenshot.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun aRealContractFixtureRendersExactlyItsExpectedItems() {
        val fixture = Json.parseToJsonElement(
            locateFixture("continue_watching/v1/filters_low_complete_and_stale.json").readText(),
        ).jsonObject
        val expected = expectedItems(fixture)

        assertTrue(expected.isNotEmpty(), "the fixture should declare expected items")

        val seed = SeedData.fromFixture(fixture)
        assertEquals(expected.map { it.contentId }, seed.items.map { it.contentId })
        assertEquals(expected.map { it.episodeKey }, seed.items.map { it.episodeKey })
        assertEquals(expected.map { it.progressPercent }, seed.items.map { it.progressPercent })

        runComposeUiTest {
            setContent { ContinueWatchingRail(items = seed.items) }
            assertAllItemsDisplayed(seed.items)
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun everyContinueWatchingFixtureLoadsAndRenders() {
        // The same five fixtures the contract suite exercises, driven through the
        // desktop path as well. Cheap, and it means a fixture that only works on
        // one target cannot pass CI unnoticed.
        val directory = locateFixture("continue_watching/v1").listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json") }
            .orEmpty()
        assertTrue(directory.isNotEmpty(), "expected continue_watching fixtures on disk")

        directory.forEach { file ->
            val fixture = Json.parseToJsonElement(file.readText()).jsonObject
            val seed = SeedData.fromFixture(fixture)
            runComposeUiTest {
                setContent { ContinueWatchingRail(items = seed.items) }
                onNodeWithText("Continue watching").assertIsDisplayed()
            }
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun anEmptyPlanStillRendersTheHeaderRatherThanABlankScreen() {
        runComposeUiTest {
            setContent { ContinueWatchingRail(items = emptyList()) }
            onNodeWithText("Continue watching").assertIsDisplayed()
        }
    }
}

/** Asserts the screen is showing every item, identified the way a screen reader would. */
@OptIn(ExperimentalTestApi::class)
private fun androidx.compose.ui.test.ComposeUiTest.assertAllItemsDisplayed(
    items: List<ContinueWatchingPlanItem>,
) {
    items.forEach { item ->
        onNodeWithContentDescription(describe(item)).assertIsDisplayed()
    }
}

/** The `expected.items` array of a continue_watching fixture, in declaration order. */
private fun expectedItems(fixture: JsonObject): List<ContinueWatchingPlanItem> {
    val items: JsonArray = fixture["expected"]?.jsonObject?.get("items") as? JsonArray
        ?: error("Fixture is missing expected.items")
    return items.map { element ->
        val item = element.jsonObject
        ContinueWatchingPlanItem(
            contentType = item.string("content_type").orEmpty(),
            contentId = item.string("content_id").orEmpty(),
            episodeKey = item.string("episode_key"),
            progressPercent = item.double("progress_percent") ?: 0.0,
            lastUpdatedMs = item.long("last_updated_ms") ?: 0L,
            isUpNextPlaceholder = item.boolean("is_up_next_placeholder") ?: false,
        )
    }
}

private fun JsonObject.string(key: String): String? =
    this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

private fun JsonObject.long(key: String): Long? =
    this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.longOrNull

private fun JsonObject.double(key: String): Double? =
    this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.doubleOrNull

private fun JsonObject.boolean(key: String): Boolean? =
    this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.booleanOrNull

/**
 * Walks up from the working directory looking for `contracts/fixtures/<relativePath>`.
 *
 * Gradle runs tests with the *module* directory as the working directory, not the
 * root project, so the walk is load-bearing rather than defensive. It accepts a
 * file or a directory: one caller reads a single fixture and another lists a
 * suite's directory, and an `exists()` check covers both. An earlier version
 * tested `isFile` and so silently failed to find any directory.
 */
private fun locateFixture(relativePath: String): File {
    val start = File(".").absolutePath
    var directory: File? = File(".").absoluteFile
    while (directory != null) {
        val candidate = File(directory, "contracts/fixtures/$relativePath")
        if (candidate.exists()) return candidate
        directory = directory.parentFile
    }
    error("Could not locate contracts/fixtures/$relativePath from $start")
}

/**
 * A fixed `nowMs`, so rendered percentages are a function of the candidates alone.
 * The planner filters on a 30-day staleness window, and a wall-clock `now` would
 * make this test pass or fail depending on the day it ran.
 */
private const val NOW_MS = 1_700_000_200_000L
