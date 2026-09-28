package com.crispy.tv.desktop

import com.crispy.tv.domain.watch.ContinueWatchingCandidate
import com.crispy.tv.domain.watch.ContinueWatchingPlanItem
import com.crispy.tv.domain.watch.planContinueWatching
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File

/**
 * The desktop window's content: the output of the real planner over real input.
 *
 * The input comes from a contract fixture rather than being invented, so the
 * window renders exactly the shape the contract suite asserts on. That is the
 * point of the seam proof: if `planContinueWatching` changes and a contract is
 * updated in step with it, this changes with it -- and the mismatch surfaces in
 * the contract suite rather than only in somebody's screenshot.
 *
 * Fixtures are read from disk here rather than compiled in the way
 * `:android:core-domain`'s test suite does, because this is an application, not
 * a test. A desktop build has exactly one well-known root and there is no
 * cross-target path problem to solve.
 */
internal data class SeedData(val items: List<ContinueWatchingPlanItem>) {

    companion object {

        private val FIXTURE = "continue_watching/v1/in_progress_before_placeholder.json"

        /**
         * The fixture if it can be found, otherwise a small built-in input.
         *
         * The fallback exists because `./gradlew :desktopApp:run` executes with the
         * root project as its working directory, but a packaged `.dmg` or `.exe` has
         * no repository to read from. Falling back to a real planner call rather
         * than an empty screen keeps the window meaningful in a packaged build.
         */
        fun load(fixturesRoot: File = File("contracts/fixtures")): SeedData {
            val file = File(fixturesRoot, FIXTURE)
            if (!file.isFile) {
                return SeedData(
                    planContinueWatching(
                        candidates = listOf(
                            ContinueWatchingCandidate("series", "tt0903747", "s1e1", 42.0, 1_700_000_000_000),
                            ContinueWatchingCandidate("movie", "tt0111161", null, 61.5, 1_700_000_100_000),
                            ContinueWatchingCandidate("series", "tt0108778", "s1e3", 8.0, 1_700_000_050_000),
                        ),
                        nowMs = 1_700_000_200_000,
                        maxItems = 20,
                    ),
                )
            }
            return fromFixture(Json.parseToJsonElement(file.readText()).jsonObject)
        }

        /** Runs the real planner over one fixture's input. */
        fun fromFixture(fixture: JsonObject): SeedData {
            val nowMs = fixture.long("now_ms") ?: error("Fixture is missing now_ms")
            val input = fixture["input"]?.jsonObject ?: error("Fixture is missing input")
            val maxItems = input.int("max_items") ?: 20

            val candidates = input["candidates"]?.jsonArray.orEmpty().map { element ->
                val candidate = element.jsonObject
                ContinueWatchingCandidate(
                    contentType = candidate.string("content_type").orEmpty(),
                    contentId = candidate.string("content_id").orEmpty(),
                    episodeKey = candidate.string("episode_key"),
                    progressPercent = candidate["progress_percent"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    lastUpdatedMs = candidate.long("last_updated_ms") ?: 0L,
                    isUpNextPlaceholder = candidate["is_up_next_placeholder"]?.jsonPrimitive?.booleanOrNull ?: false,
                )
            }

            return SeedData(planContinueWatching(candidates, nowMs, maxItems))
        }

        private fun JsonObject.string(key: String): String? =
            this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

        private fun JsonObject.long(key: String): Long? =
            this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.longOrNull

        private fun JsonObject.int(key: String): Int? =
            this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content?.toIntOrNull()
    }
}
