package com.crispy.tv.contracts

import com.crispy.tv.domain.watch.EpisodeInfo
import com.crispy.tv.domain.watch.NextEpisodeResult
import com.crispy.tv.domain.watch.findNextEpisode
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

class NextEpisodeContractTest {
    @Test
    fun fixturesMatchNextEpisodeRules() {
        val fixturePaths = ContractTestSupport.fixtureFiles("next_episode")
        assertTrue(fixturePaths.isNotEmpty(), "Expected at least one next_episode fixture")

        fixturePaths.forEach { path ->
            val fixture = ContractTestSupport.parseFixture(path)
            val caseId = fixture.requireString("case_id", path)
            assertEquals("next_episode", fixture.requireString("suite", path), "$caseId: wrong suite")

            val input = fixture.requireJsonObject("input", path)
            val expected = fixture.requireJsonObject("expected", path)

            val actual = findNextEpisode(
                currentSeason = input.requireInt("current_season", path),
                currentEpisode = input.requireInt("current_episode", path),
                episodes = input.requireJsonArray("episodes", path).map { parseEpisode(it.jsonObject, path) },
                watchedSet = input.optionalStringSet("watched_set", path),
                showId = input.optionalString("show_id", path),
                nowMs = fixture.requireLong("now_ms", path),
            )

            assertEquals(parseExpectedResult(expected, path), actual, "$caseId: result")
        }
    }

    private fun parseEpisode(json: JsonObject, path: Path): EpisodeInfo {
        return EpisodeInfo(
            season = json.requireInt("season", path),
            episode = json.requireInt("episode", path),
            title = json.optionalString("title", path),
            released = json.optionalString("released", path),
        )
    }

    private fun parseExpectedResult(json: JsonObject, path: Path): NextEpisodeResult? {
        val result = json["result"] ?: throw AssertionError("${path.toDisplayPath()}: missing 'result'")
        if (result is JsonNull) {
            return null
        }

        val resultObject = result.jsonObject
        return NextEpisodeResult(
            season = resultObject.requireInt("season", path),
            episode = resultObject.requireInt("episode", path),
            title = resultObject.optionalString("title", path),
        )
    }
}

private fun JsonObject.requireLong(key: String, path: Path): Long {
    return this[key]?.jsonPrimitive?.longOrNull
        ?: throw AssertionError("${path.toDisplayPath()}: missing or invalid long '$key'")
}

private fun JsonObject.optionalStringSet(key: String, path: Path): Set<String>? {
    val element: JsonElement = this[key] ?: return null
    if (element is JsonNull) {
        return null
    }
    return element.jsonArray.toStringList(path).toSet()
}
