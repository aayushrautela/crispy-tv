package com.crispy.tv.contracts

import com.crispy.tv.domain.media.CalendarContractEnvelope
import com.crispy.tv.domain.media.CalendarContractItem
import com.crispy.tv.domain.media.ContractLandscapeCard
import com.crispy.tv.domain.media.ContractRegularCard
import com.crispy.tv.domain.media.normalizeCalendarEnvelope
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

class CalendarContractTest {
    @Test
    fun fixturesMatchServerCalendarContract() {
        val fixturePaths = ContractTestSupport.fixtureFiles("calendar_contract")
        assertTrue(fixturePaths.isNotEmpty(), "Expected at least one calendar_contract fixture")

        fixturePaths.forEach { path ->
            val fixture = ContractTestSupport.parseFixture(path)
            val caseId = fixture.requireString("case_id", path)
            assertEquals("calendar_contract", fixture.requireString("suite", path), "$caseId: wrong suite")

            val input = fixture.requireJsonObject("input", path)
            val expected = fixture.requireJsonObject("expected", path)
            val route = input.requireString("route", path)
            val payload = input.requireJsonObject("payload", path).toKotlinMap()

            val actual = normalizeCalendarEnvelope(payload, route)
            val expectedValid = expected.requireBoolean("valid", path)
            val expectedNormalized = expected.optionalJsonObject("normalized", path)?.let { parseEnvelope(it, path) }

            assertEquals(expectedValid, actual != null, "$caseId: valid")
            assertEquals(expectedNormalized, actual, "$caseId: normalized")
        }
    }

    private fun parseEnvelope(json: JsonObject, path: Path): CalendarContractEnvelope {
        return CalendarContractEnvelope(
            profileId = json.requireString("profile_id", path),
            source = json.requireString("source", path),
            generatedAt = json.requireString("generated_at", path),
            kind = json.optionalString("kind", path),
            items = json.requireJsonArray("items", path).map { parseItem(it.jsonObject, path) },
        )
    }

    private fun parseItem(json: JsonObject, path: Path): CalendarContractItem {
        return CalendarContractItem(
            bucket = json.requireString("bucket", path),
            media = parseLandscapeCard(json.requireJsonObject("media", path), path),
            relatedShow = parseRegularCard(json.requireJsonObject("related_show", path), path),
            airDate = json.optionalString("air_date", path),
            watched = json.requireBoolean("watched", path),
        )
    }

    private fun parseRegularCard(json: JsonObject, path: Path): ContractRegularCard {
        return ContractRegularCard(
            mediaType = json.requireString("media_type", path),
            mediaKey = json.requireString("media_key", path),
            title = json.requireString("title", path),
            posterUrl = json.requireString("poster_url", path),
            releaseYear = json.optionalInt("release_year", path),
            rating = json.optionalDouble("rating", path),
            genre = json.optionalString("genre", path),
            subtitle = json.optionalString("subtitle", path),
        )
    }

    private fun parseLandscapeCard(json: JsonObject, path: Path): ContractLandscapeCard {
        return ContractLandscapeCard(
            mediaType = json.requireString("media_type", path),
            mediaKey = json.requireString("media_key", path),
            title = json.requireString("title", path),
            posterUrl = json.requireString("poster_url", path),
            backdropUrl = json.requireString("backdrop_url", path),
            releaseYear = json.optionalInt("release_year", path),
            rating = json.optionalDouble("rating", path),
            genre = json.optionalString("genre", path),
            seasonNumber = json.optionalInt("season_number", path),
            episodeNumber = json.optionalInt("episode_number", path),
            episodeTitle = json.optionalString("episode_title", path),
            airDate = json.optionalString("air_date", path),
            runtimeMinutes = json.optionalInt("runtime_minutes", path),
        )
    }
}

private fun JsonObject.optionalDouble(key: String, path: Path): Double? {
    val value = this[key] ?: return null
    if (value is JsonNull) return null
    val primitive = value as? JsonPrimitive ?: error("${path.toDisplayPath()}: '$key' must be double or null")
    return primitive.doubleOrNull ?: error("${path.toDisplayPath()}: '$key' must be double or null")
}

private fun JsonObject.toKotlinMap(): Map<String, Any?> {
    return entries.associate { (key, value) -> key to value.toKotlinValue() }
}

private fun JsonElement.toKotlinValue(): Any? {
    return when (this) {
        is JsonNull -> null
        is JsonObject -> toKotlinMap()
        is kotlinx.serialization.json.JsonArray -> map { element -> element.toKotlinValue() }
        is JsonPrimitive -> if (isString) content else booleanOrNull ?: intOrNull ?: doubleOrNull ?: content
    }
}
