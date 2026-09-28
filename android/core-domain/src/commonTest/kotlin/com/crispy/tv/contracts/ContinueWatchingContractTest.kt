package com.crispy.tv.contracts

import com.crispy.tv.domain.watch.ContinueWatchingCandidate
import com.crispy.tv.domain.watch.planContinueWatching
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.test.Test
import kotlin.test.assertEquals

class ContinueWatchingContractTest {
    @Test
    fun continueWatchingFixtures() {
        val fixtures = ContractTestSupport.fixtureFiles("continue_watching")
        fixtures.forEach { path ->
            val root = ContractTestSupport.parseFixture(path)
            assertEquals("continue_watching", root.requireString("suite", path), "Wrong suite in $path")

            val nowMs = root.requireLong("now_ms", path)
            val input = root.requireJsonObject("input", path)
            val expected = root.requireJsonObject("expected", path)

            val candidates =
                input.requireJsonArray("candidates", path).map { entry ->
                    val obj = entry as? JsonObject ?: error("$path: candidate must be object")
                    ContinueWatchingCandidate(
                        contentType = obj.requireString("content_type", path),
                        contentId = obj.requireString("content_id", path),
                        episodeKey = obj.optionalString("episode_key", path),
                        progressPercent = obj.requireDouble("progress_percent", path),
                        lastUpdatedMs = obj.requireLong("last_updated_ms", path),
                        isUpNextPlaceholder = obj.optionalBoolean("is_up_next_placeholder") ?: false
                    )
                }

            val maxItems = input.optionalInt("max_items", path) ?: 20
            val actual = planContinueWatching(candidates = candidates, nowMs = nowMs, maxItems = maxItems)

            val expectedItems =
                expected.requireJsonArray("items", path).map { entry ->
                    val obj = entry as? JsonObject ?: error("$path: expected item must be object")
                    ExpectedItem(
                        contentType = obj.requireString("content_type", path),
                        contentId = obj.requireString("content_id", path),
                        episodeKey = obj.optionalString("episode_key", path),
                        progressPercent = obj.requireDouble("progress_percent", path),
                        lastUpdatedMs = obj.requireLong("last_updated_ms", path),
                        isUpNextPlaceholder = obj.optionalBoolean("is_up_next_placeholder") ?: false
                    )
                }

            assertEquals(expectedItems.size, actual.size, "Count mismatch in $path")
            expectedItems.indices.forEach { index ->
                val expectedItem = expectedItems[index]
                val actualItem = actual[index]
                assertEquals(expectedItem.contentType, actualItem.contentType, "Type mismatch at index $index in $path")
                assertEquals(expectedItem.contentId, actualItem.contentId, "Content id mismatch at index $index in $path")
                assertEquals(expectedItem.episodeKey, actualItem.episodeKey, "Episode key mismatch at index $index in $path")
                assertEquals(expectedItem.lastUpdatedMs, actualItem.lastUpdatedMs, "Timestamp mismatch at index $index in $path")
                assertEquals(expectedItem.isUpNextPlaceholder, actualItem.isUpNextPlaceholder, "Placeholder mismatch at index $index in $path")
                assertEquals(
                    expectedItem.progressPercent ?: 0.0,
                    actualItem.progressPercent ?: 0.0,
                    absoluteTolerance = 0.0001,
                    message = "Progress mismatch at index $index in $path"
                )
            }
        }
    }
}

private data class ExpectedItem(
    val contentType: String,
    val contentId: String,
    val episodeKey: String?,
    val progressPercent: Double?,
    val lastUpdatedMs: Long,
    val isUpNextPlaceholder: Boolean
)

private fun JsonObject.requireLong(key: String, path: ContractFixture): Long {
    val primitive = this[key] as? JsonPrimitive
        ?: error("$path: missing long '$key'")
    return primitive.longOrNull
        ?: error("$path: '$key' must be long")
}

private fun JsonObject.requireDouble(key: String, path: ContractFixture): Double {
    val primitive = this[key] as? JsonPrimitive
        ?: error("$path: missing double '$key'")
    return primitive.doubleOrNull
        ?: error("$path: '$key' must be double")
}

private fun JsonObject.optionalBoolean(key: String): Boolean? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    return primitive.booleanOrNull
}
